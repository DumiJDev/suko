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
        // sem --key-file e sem SUKO_REGISTRY_SIGNING_KEY no ambiente do teste
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k"}));
    }

    @Test
    void signRequiresAnExistingIndex(@TempDir Path dir) throws Exception {
        RegistryTool.main(new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", "k", "--registry-id", "r"});
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k", "--key-file", dir.resolve("k.private.pem").toString()}));
    }
}
