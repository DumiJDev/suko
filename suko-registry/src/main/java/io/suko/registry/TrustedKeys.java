package io.suko.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

/** Conjunto de chaves públicas confiáveis; cada chave está ligada a UM registryId. */
public final class TrustedKeys {

    public record Key(String keyId, String registryId, PublicKey publicKey) {
        public Key {
            if (keyId == null || registryId == null || publicKey == null) {
                throw new IllegalArgumentException("trusted key: keyId, registryId e publicKey são obrigatórios");
            }
        }
    }

    private final List<Key> keys;

    private TrustedKeys(List<Key> keys) {
        this.keys = List.copyOf(keys);
    }

    public static TrustedKeys of(List<Key> keys) {
        return new TrustedKeys(keys);
    }

    public static TrustedKeys empty() {
        return new TrustedKeys(List.of());
    }

    public List<Key> forRegistry(String registryId) {
        return keys.stream().filter(k -> k.registryId().equals(registryId)).toList();
    }

    public boolean isEmpty() {
        return keys.isEmpty();
    }

    public TrustedKeys plus(TrustedKeys other) {
        List<Key> all = new ArrayList<>(keys);
        all.addAll(other.keys);
        return new TrustedKeys(all);
    }

    /** {@code {"keys":[{"keyid":"..","registryId":"..","publicKey":"<base64 X.509>"}]}} */
    public static TrustedKeys parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("keys") || !root.getAsJsonObject().get("keys").isJsonArray()) {
                throw new IllegalArgumentException("trusted-keys: esperado {\"keys\": [...]}");
            }
            JsonArray array = root.getAsJsonObject().getAsJsonArray("keys");
            List<Key> out = new ArrayList<>();
            for (JsonElement e : array) {
                JsonObject o = e.getAsJsonObject();
                out.add(new Key(o.get("keyid").getAsString(), o.get("registryId").getAsString(),
                    RegistrySignature.publicKeyFromBase64(o.get("publicKey").getAsString())));
            }
            return new TrustedKeys(out);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("trusted-keys inválido: " + e.getMessage(), e);
        }
    }
}
