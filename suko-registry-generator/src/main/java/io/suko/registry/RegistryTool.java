package io.suko.registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/** Ferramenta do mantenedor: gerar o par de chaves e assinar o índice. Nunca recebe a chave em argv. */
public final class RegistryTool {

    private RegistryTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("uso: generate-key --out-dir D --key-id ID --registry-id URL | sign --registry-dir D --key-id ID [--key-file F]");
        }
        Map<String, String> opts = parse(args);
        switch (args[0]) {
            case "generate-key" -> generateKey(Path.of(require(opts, "out-dir")), require(opts, "key-id"), require(opts, "registry-id"));
            case "sign" -> sign(Path.of(require(opts, "registry-dir")), require(opts, "key-id"), opts.get("key-file"));
            default -> throw new IllegalArgumentException("comando desconhecido: " + args[0]);
        }
    }

    private static void generateKey(Path outDir, String keyId, String registryId) {
        try {
            Files.createDirectories(outDir);
            Path file = outDir.resolve(keyId + ".private.pem");
            KeyPair pair = RegistrySignature.generateKeyPair();
            try {
                createPrivateFile(file);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("A chave " + file + " já existe; não é sobrescrita");
            }
            Files.writeString(file, RegistrySignature.privateKeyToPem(pair.getPrivate()), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE);
            Path pub = outDir.resolve(keyId + ".public.txt");
            Files.writeString(pub, RegistrySignature.publicKeyBase64(pair.getPublic()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            System.out.println("Chave privada: " + file + "  (NUNCA a committar)");
            System.out.println("Linha para trusted-keys.json:");
            System.out.println("{\"keyid\": \"" + keyId + "\", \"registryId\": \"" + registryId
                + "\", \"publicKey\": \"" + RegistrySignature.publicKeyBase64(pair.getPublic()) + "\"}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Creates the (empty) private-key file already restricted to 0600 where POSIX is available, so the secret is never world-readable, not even briefly. */
    private static void createPrivateFile(Path file) throws IOException {
        try {
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            // sistemas de ficheiros sem POSIX (Windows): fica a permissão por omissão
            Files.createFile(file);
        }
    }

    private static void sign(Path registryDir, String keyId, String keyFile) {
        try {
            Path index = registryDir.resolve("registry.json");
            if (!Files.isRegularFile(index)) {
                throw new IllegalStateException("registry.json não existe em " + registryDir);
            }
            String pem;
            if (keyFile != null) {
                pem = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8);
            } else {
                pem = System.getenv("SUKO_REGISTRY_SIGNING_KEY");
                if (pem == null || pem.isBlank()) {
                    throw new IllegalStateException("Sem chave: use --key-file <ficheiro> ou a variável SUKO_REGISTRY_SIGNING_KEY");
                }
            }
            byte[] indexBytes = Files.readAllBytes(index);
            byte[] sig = RegistrySignature.sign(indexBytes, RegistrySignature.privateKeyFromPem(pem), keyId);
            verifyOwnOutput(indexBytes, sig, keyId, keyFile);
            Files.write(registryDir.resolve("registry.json.sig"), sig);
            System.out.println("Escreveu " + registryDir.resolve("registry.json.sig"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The JDK cannot derive an Ed25519 public key from a private one, so the
     * check uses {@code <keyId>.public.txt} next to the key file (written by
     * generate-key). Without it (env-var key) the signature is not re-verified here.
     */
    private static void verifyOwnOutput(byte[] indexBytes, byte[] sig, String keyId, String keyFile) throws IOException {
        if (keyFile == null) {
            System.err.println("AVISO: sem " + keyId + ".public.txt ao lado da chave, a assinatura não foi reverificada.");
            return;
        }
        Path pub = Path.of(keyFile).toAbsolutePath().resolveSibling(keyId + ".public.txt");
        if (!Files.isRegularFile(pub)) {
            System.err.println("AVISO: " + pub + " não existe; a assinatura não foi reverificada.");
            return;
        }
        var key = RegistrySignature.publicKeyFromBase64(Files.readString(pub, StandardCharsets.UTF_8).trim());
        try {
            RegistrySignature.verify(indexBytes, sig, TrustedKeys.of(java.util.List.of(new TrustedKeys.Key(keyId, "self-check", key))), "self-check");
        } catch (RegistrySecurityException e) {
            throw new IllegalStateException("A assinatura produzida não verifica com a chave pública " + pub + " (a chave privada não corresponde?): " + e.getMessage());
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("argumento inesperado: " + args[i]);
            }
            String name = args[i].substring(2);
            if (name.equals("key")) {
                throw new IllegalArgumentException("a chave privada nunca se passa na linha de comandos (use --key-file ou SUKO_REGISTRY_SIGNING_KEY)");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("falta o valor de --" + name);
            }
            opts.put(name, args[++i]);
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null) {
            throw new IllegalArgumentException("falta --" + name);
        }
        return v;
    }
}
