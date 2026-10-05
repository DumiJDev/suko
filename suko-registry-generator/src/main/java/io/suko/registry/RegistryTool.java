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
        run(args, System::getenv);
    }

    /** Testable entry point: {@code env} replaces {@link System#getenv(String)}. */
    static void run(String[] args, java.util.function.UnaryOperator<String> env) {
        if (args.length == 0) {
            throw new IllegalArgumentException("uso: generate-key --out-dir D --key-id ID --registry-id URL | sign --registry-dir D --key-id ID [--key-file F] [--public-key-file F] [--no-verify]");
        }
        Map<String, String> opts = parse(args);
        switch (args[0]) {
            case "generate-key" -> generateKey(Path.of(require(opts, "out-dir")), keyId(require(opts, "key-id")), require(opts, "registry-id"));
            case "sign" -> sign(Path.of(require(opts, "registry-dir")), keyId(require(opts, "key-id")), opts.get("key-file"),
                opts.get("public-key-file"), opts.containsKey("no-verify"), env);
            default -> throw new IllegalArgumentException("comando desconhecido: " + args[0]);
        }
    }

    private static String keyId(String id) {
        if (!id.matches("[A-Za-z0-9._-]+") || id.equals(".") || id.equals("..")) {
            throw new IllegalArgumentException("key-id inválido (use só [A-Za-z0-9._-]+): " + id);
        }
        return id;
    }

    private static String jsonEscape(String v) {
        StringBuilder sb = new StringBuilder();
        for (char c : v.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private static void generateKey(Path outDir, String keyId, String registryId) {
        Path file = outDir.resolve(keyId + ".private.pem");
        Path pub = outDir.resolve(keyId + ".public.txt");
        if (Files.exists(file) || Files.exists(pub)) {
            throw new IllegalStateException("A chave " + file + " ou " + pub + " já existe; não é sobrescrita");
        }
        boolean createdPrivate = false;
        boolean createdPublic = false;
        try {
            Files.createDirectories(outDir);
            KeyPair pair = RegistrySignature.generateKeyPair();
            try {
                createPrivateFile(file);
                createdPrivate = true;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("A chave " + file + " já existe; não é sobrescrita");
            }
            Files.writeString(file, RegistrySignature.privateKeyToPem(pair.getPrivate()), StandardCharsets.UTF_8,
                StandardOpenOption.WRITE);
            try {
                Files.writeString(pub, RegistrySignature.publicKeyBase64(pair.getPublic()) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
                createdPublic = true;
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("A chave pública " + pub + " já existe; não é sobrescrita");
            }
            System.out.println("Chave privada: " + file + "  (NUNCA a committar)");
            System.out.println("Linha para trusted-keys.json:");
            System.out.println("{\"keyid\": \"" + jsonEscape(keyId) + "\", \"registryId\": \"" + jsonEscape(registryId)
                + "\", \"publicKey\": \"" + RegistrySignature.publicKeyBase64(pair.getPublic()) + "\"}");
        } catch (IOException | RuntimeException e) {
            try {
                if (createdPrivate) Files.deleteIfExists(file);
                if (createdPublic) Files.deleteIfExists(pub);
            } catch (IOException ignored) {
                // melhor esforço
            }
            if (e instanceof RuntimeException re) throw re;
            throw new UncheckedIOException((IOException) e);
        }
    }

    /** Creates the (empty) private-key file already restricted to 0600 where POSIX is available, so the secret is never world-readable, not even briefly. */
    private static void createPrivateFile(Path file) throws IOException {
        try {
            Files.createFile(file, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException e) {
            System.err.println("AVISO: permissões não restringidas (sistema sem POSIX); proteja a chave manualmente");
            Files.createFile(file);
        }
    }

    private static void sign(Path registryDir, String keyId, String keyFile, String publicKeyFile, boolean noVerify,
                             java.util.function.UnaryOperator<String> env) {
        try {
            Path index = registryDir.resolve("registry.json");
            if (!Files.isRegularFile(index)) {
                throw new IllegalStateException("registry.json não existe em " + registryDir);
            }
            String pem;
            if (keyFile != null) {
                pem = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8);
            } else {
                pem = env.apply("SUKO_REGISTRY_SIGNING_KEY");
                if (pem == null || pem.isBlank()) {
                    throw new IllegalStateException("Sem chave: use --key-file <ficheiro> ou a variável SUKO_REGISTRY_SIGNING_KEY");
                }
            }
            Path sigFile = registryDir.resolve("registry.json.sig");
            // nunca deixar uma .sig antiga ao lado de um índice novo
            Files.deleteIfExists(sigFile);
            byte[] indexBytes = Files.readAllBytes(index);
            byte[] sig = RegistrySignature.sign(indexBytes, RegistrySignature.privateKeyFromPem(pem), keyId);
            verifyOwnOutput(indexBytes, sig, keyId, keyFile, publicKeyFile, noVerify);
            Files.write(sigFile, sig);
            System.out.println("Escreveu " + sigFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The JDK cannot derive an Ed25519 public key from a private one, so the
     * check uses {@code --public-key-file} or {@code <keyId>.public.txt} next
     * to the key file (written by generate-key). It always runs unless the
     * caller passes an explicit {@code --no-verify}.
     */
    private static void verifyOwnOutput(byte[] indexBytes, byte[] sig, String keyId, String keyFile,
                                        String publicKeyFile, boolean noVerify) throws IOException {
        if (noVerify) {
            System.err.println("AVISO: --no-verify: a assinatura produzida não foi reverificada.");
            return;
        }
        Path pub;
        if (publicKeyFile != null) {
            pub = Path.of(publicKeyFile);
        } else if (keyFile == null) {
            throw new IllegalStateException("Chave via SUKO_REGISTRY_SIGNING_KEY: indique --public-key-file <ficheiro> para reverificar a assinatura (ou --no-verify)");
        } else {
            pub = Path.of(keyFile).toAbsolutePath().resolveSibling(keyId + ".public.txt");
        }
        if (!Files.isRegularFile(pub)) {
            throw new IllegalStateException("Chave pública " + pub + " não existe; não foi possível reverificar a assinatura (use --public-key-file ou --no-verify)");
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
            if (name.equals("no-verify")) {
                opts.put(name, "true");
                continue;
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
