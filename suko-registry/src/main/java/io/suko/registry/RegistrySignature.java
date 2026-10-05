package io.suko.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Assinatura Ed25519 do índice do registry. A assinatura cobre os bytes exatos de
 * {@code registry.json} e é verificada <strong>antes</strong> de qualquer parse do índice.
 * Só JDK ({@code java.security}); sem dependências de crypto.
 */
public final class RegistrySignature {

    /** Tamanho máximo aceite do ficheiro de assinatura (defesa contra ficheiros hostis). */
    static final int MAX_SIGNATURE_FILE_BYTES = 64 * 1024;
    /** Máximo de entradas consideradas numa lista de assinaturas. */
    static final int MAX_SIGNATURES = 16;
    /** Uma assinatura Ed25519 tem sempre 64 bytes. */
    private static final int ED25519_SIGNATURE_BYTES = 64;

    public record Verified(String keyId) {
    }

    private RegistrySignature() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponível neste JDK", e);
        }
    }

    public static byte[] sign(byte[] indexBytes, PrivateKey key, String keyId) {
        if (keyId == null || keyId.isEmpty()) {
            throw new IllegalArgumentException("keyId obrigatório");
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(indexBytes);
            JsonObject o = new JsonObject();
            o.addProperty("keyid", keyId);
            o.addProperty("alg", "ed25519");
            o.addProperty("sig", Base64.getEncoder().encodeToString(s.sign()));
            JsonArray list = new JsonArray();
            list.add(o);
            JsonObject root = new JsonObject();
            root.add("signatures", list);
            return (root + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Junta vários ficheiros .sig numa lista de assinaturas. */
    public static byte[] combine(byte[]... sigFiles) {
        JsonArray all = new JsonArray();
        for (byte[] f : sigFiles) {
            try {
                all.addAll(JsonParser.parseString(new String(f, StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonArray("signatures"));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("ficheiro de assinatura inválido: " + e.getMessage(), e);
            }
        }
        JsonObject root = new JsonObject();
        root.add("signatures", all);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Verifica {@code sigJson} contra {@code indexBytes}. Aceita se QUALQUER assinatura
     * for válida para uma chave confiável ligada a {@code registryId} com o mesmo keyid.
     * Tudo o resto (ilegível, alg errado, keyid desconhecido, tamanho errado, chave de
     * outro registry) é {@code REGISTRY_BAD_SIGNATURE}; nunca há degradação para "sem assinatura".
     */
    public static Verified verify(byte[] indexBytes, byte[] sigJson, TrustedKeys keys, String registryId) {
        if (indexBytes == null || sigJson == null || keys == null || registryId == null) {
            throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE", "argumentos de verificação em falta");
        }
        if (sigJson.length > MAX_SIGNATURE_FILE_BYTES) {
            throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE", "ficheiro de assinatura demasiado grande");
        }
        JsonArray signatures;
        try {
            // parseString rejeita dados após o documento ("Did not consume the entire document").
            JsonElement root = JsonParser.parseString(new String(sigJson, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("signatures")
                || !root.getAsJsonObject().get("signatures").isJsonArray()) {
                throw new IllegalArgumentException("esperado {\"signatures\": [...]}");
            }
            signatures = root.getAsJsonObject().getAsJsonArray("signatures");
        } catch (RuntimeException e) {
            throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE", "ficheiro de assinatura ilegível: " + e.getMessage());
        }
        if (signatures.size() > MAX_SIGNATURES) {
            throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE", "demasiadas assinaturas");
        }
        for (JsonElement el : signatures) {
            try {
                JsonObject o = el.getAsJsonObject();
                String keyId = strictString(o, "keyid");
                if (!"ed25519".equals(strictString(o, "alg"))) {
                    continue;
                }
                byte[] sig = Base64.getDecoder().decode(strictString(o, "sig"));
                if (sig.length != ED25519_SIGNATURE_BYTES) {
                    continue;
                }
                for (TrustedKeys.Key key : keys.forRegistry(registryId)) {
                    if (!key.keyId().equals(keyId)) {
                        continue;
                    }
                    Signature s = Signature.getInstance("Ed25519");
                    s.initVerify(key.publicKey());
                    s.update(indexBytes);
                    if (s.verify(sig)) {
                        return new Verified(keyId);
                    }
                }
            } catch (GeneralSecurityException | RuntimeException ignored) {
                // assinatura mal formada: tenta a seguinte
            }
        }
        throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE",
            "nenhuma assinatura válida de uma chave confiável para o registry " + registryId);
    }

    private static String strictString(JsonObject o, String field) {
        JsonElement e = o.get(field);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("campo '" + field + "' em falta ou não é texto");
        }
        return e.getAsString();
    }

    public static String publicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey publicKeyFromBase64(String b64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("chave pública inválida: " + e.getMessage(), e);
        }
    }

    public static String privateKeyToPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }

    public static PrivateKey privateKeyFromPem(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("chave privada inválida: " + e.getMessage(), e);
        }
    }
}
