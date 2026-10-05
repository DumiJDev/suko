package io.suko.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryToolTest {

    @Test
    void generateKeyThenSignThenVerify(@TempDir Path dir) throws Exception {
        Path reg = dir.resolve("registry");
        Files.createDirectories(reg);
        byte[] index = "{\"schemaVersion\":2}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(reg.resolve("registry.json"), index);

        RegistryTool.main(new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", "k1", "--registry-id", "https://r.example/"});
        Path pem = dir.resolve("k1.private.pem");
        assertTrue(Files.exists(pem));

        RegistryTool.main(new String[] {"sign", "--registry-dir", reg.toString(), "--key-id", "k1", "--key-file", pem.toString()});
        byte[] sig = Files.readAllBytes(reg.resolve("registry.json.sig"));

        // o JDK não deriva a pública de uma privada Ed25519: o gerador guarda-a em <id>.public.txt
        var pub = RegistrySignature.publicKeyFromBase64(Files.readString(dir.resolve("k1.public.txt")).trim());
        TrustedKeys keys = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", "https://r.example/", pub)));
        assertEquals("k1", RegistrySignature.verify(index, sig, keys, "https://r.example/").keyId());
    }

    @Test
    void generateKeyRefusesToOverwrite(@TempDir Path dir) throws Exception {
        String[] args = {"generate-key", "--out-dir", dir.toString(), "--key-id", "k1", "--registry-id", "https://r.example/"};
        RegistryTool.main(args);
        assertThrows(IllegalStateException.class, () -> RegistryTool.main(args));
    }

    @Test
    void signNeverAcceptsTheKeyOnTheCommandLine(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}");
        assertThrows(IllegalArgumentException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k", "--key", "-----BEGIN PRIVATE KEY-----"}));
    }

    @Test
    void signWithoutAKeySourceFails(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}");
        // ambiente injetado: independente de SUKO_REGISTRY_SIGNING_KEY no processo
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.run(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k"}, n -> null));
    }

    @Test
    void signRequiresAnExistingIndex(@TempDir Path dir) throws Exception {
        RegistryTool.main(new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", "k", "--registry-id", "r"});
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k", "--key-file", dir.resolve("k.private.pem").toString()}));
    }

    private static String[] gen(Path dir, String id) {
        return new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", id, "--registry-id", "https://r.example/"};
    }

    @Test
    void wrongKeySelfCheckFailsAndRemovesStaleSig(@TempDir Path dir) throws Exception {
        Path reg = dir.resolve("reg");
        Files.createDirectories(reg);
        Files.writeString(reg.resolve("registry.json"), "{}\n");
        Files.writeString(reg.resolve("registry.json.sig"), "stale");
        Path other = dir.resolve("other");
        RegistryTool.main(gen(dir, "k1"));
        RegistryTool.main(gen(other, "k1"));
        // chave privada de "other" com a pública de "dir"
        assertThrows(IllegalStateException.class, () -> RegistryTool.main(new String[] {"sign", "--registry-dir", reg.toString(),
            "--key-id", "k1", "--key-file", other.resolve("k1.private.pem").toString(),
            "--public-key-file", dir.resolve("k1.public.txt").toString()}));
        assertFalse(Files.exists(reg.resolve("registry.json.sig")));
    }

    @Test
    void noVerifySkipsTheSelfCheck(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}\n");
        Path keys = dir.resolve("keys");
        RegistryTool.main(gen(keys, "k1"));
        Files.delete(keys.resolve("k1.public.txt"));
        String[] base = {"sign", "--registry-dir", dir.toString(), "--key-id", "k1", "--key-file", keys.resolve("k1.private.pem").toString()};
        assertThrows(IllegalStateException.class, () -> RegistryTool.main(base));
        RegistryTool.main(java.util.stream.Stream.concat(java.util.Arrays.stream(base), java.util.stream.Stream.of("--no-verify")).toArray(String[]::new));
        assertTrue(Files.exists(dir.resolve("registry.json.sig")));
    }

    @Test
    void envKeyWithoutPublicKeyFileFails(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}\n");
        Path keys = dir.resolve("keys");
        RegistryTool.main(gen(keys, "k1"));
        String pem = Files.readString(keys.resolve("k1.private.pem"));
        String[] args = {"sign", "--registry-dir", dir.toString(), "--key-id", "k1"};
        var ex = assertThrows(IllegalStateException.class, () -> RegistryTool.run(args, n -> pem));
        assertTrue(ex.getMessage().contains("--public-key-file"), ex.getMessage());
        assertFalse(Files.exists(dir.resolve("registry.json.sig")));
        RegistryTool.run(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k1",
            "--public-key-file", keys.resolve("k1.public.txt").toString()}, n -> pem);
        assertTrue(Files.exists(dir.resolve("registry.json.sig")));
    }

    @Test
    void privateKeyIs0600(@TempDir Path dir) throws Exception {
        RegistryTool.main(gen(dir, "k1"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
            dir.getFileSystem().supportedFileAttributeViews().contains("posix"));
        assertEquals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
            Files.getPosixFilePermissions(dir.resolve("k1.private.pem")));
    }

    @Test
    void keyIdWithTraversalOrOddCharsIsRejected(@TempDir Path dir) throws Exception {
        for (String bad : new String[] {"../x", "a/b", "a b", "..", "", "k\"1"}) {
            assertThrows(IllegalArgumentException.class, () -> RegistryTool.main(gen(dir, bad)), bad);
        }
        try (var list = Files.list(dir)) {
            assertEquals(0, list.count());
        }
    }

    @Test
    void generateKeyLeavesNoPartialStateWhenThePublicFileExists(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("k1.public.txt"), "x");
        assertThrows(IllegalStateException.class, () -> RegistryTool.main(gen(dir, "k1")));
        assertFalse(Files.exists(dir.resolve("k1.private.pem")));
        assertEquals("x", Files.readString(dir.resolve("k1.public.txt")));
    }
}
