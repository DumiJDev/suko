package io.suko.registry;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistrySignatureTest {

    static final byte[] INDEX = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);

    private TrustedKeys trusting(KeyPair kp, String keyId, String registryId) {
        return TrustedKeys.of(List.of(new TrustedKeys.Key(keyId, registryId, kp.getPublic())));
    }

    @Test
    void validSignatureVerifies() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        assertEquals("k1", RegistrySignature.verify(INDEX, sig, trusting(kp, "k1", "https://r.example/"), "https://r.example/").keyId());
    }

    @Test
    void tamperedIndexIsRejected() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        byte[] tampered = "{\"schemaVersion\":3}".getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(tampered, sig, trusting(kp, "k1", "https://r.example/"), "https://r.example/"));
        assertEquals("REGISTRY_BAD_SIGNATURE", e.code());
    }

    @Test
    void signatureFromAnUntrustedKeyIsRejected() throws Exception {
        KeyPair attacker = RegistrySignature.generateKeyPair();
        KeyPair real = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, attacker.getPrivate(), "k1");
        assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(INDEX, sig, trusting(real, "k1", "https://r.example/"), "https://r.example/"));
    }

    @Test
    void aKeyBoundToAnotherRegistryNeverValidates() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(INDEX, sig, trusting(kp, "k1", "https://other.example/"), "https://r.example/"));
    }

    @Test
    void multipleSignaturesAcceptAnyTrustedOne() throws Exception {
        KeyPair old = RegistrySignature.generateKeyPair();
        KeyPair fresh = RegistrySignature.generateKeyPair();
        byte[] a = RegistrySignature.sign(INDEX, old.getPrivate(), "old");
        byte[] b = RegistrySignature.sign(INDEX, fresh.getPrivate(), "new");
        byte[] both = RegistrySignature.combine(a, b);
        assertEquals("new", RegistrySignature.verify(INDEX, both, trusting(fresh, "new", "https://r.example/"), "https://r.example/").keyId());
    }

    @Test
    void malformedSignatureFilesAreSecurityErrorsNotCrashes() {
        TrustedKeys keys = TrustedKeys.empty();
        for (String junk : new String[] {"", "not json", "{}", "{\"signatures\":\"x\"}", "{\"keyid\":1}", "[]"}) {
            assertThrows(RegistrySecurityException.class,
                () -> RegistrySignature.verify(INDEX, junk.getBytes(StandardCharsets.UTF_8), keys, "https://r.example/"), junk);
        }
    }

    @Test
    void pemRoundTrip() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        var back = RegistrySignature.privateKeyFromPem(RegistrySignature.privateKeyToPem(kp.getPrivate()));
        byte[] sig = RegistrySignature.sign(INDEX, back, "k");
        assertEquals("k", RegistrySignature.verify(INDEX, sig, trusting(kp, "k", "r"), "r").keyId());
        assertEquals(kp.getPublic(), RegistrySignature.publicKeyFromBase64(RegistrySignature.publicKeyBase64(kp.getPublic())));
    }

    @Test
    void trustedKeysParse() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        String json = "{\"keys\":[{\"keyid\":\"k\",\"registryId\":\"https://r.example/\",\"publicKey\":\""
            + RegistrySignature.publicKeyBase64(kp.getPublic()) + "\"}]}";
        TrustedKeys keys = TrustedKeys.parse(json);
        assertEquals(1, keys.forRegistry("https://r.example/").size());
        assertTrue(keys.forRegistry("https://x/").isEmpty());
        assertTrue(TrustedKeys.parse("{\"keys\":[]}").forRegistry("a").isEmpty());
    }

    private static final String R = "https://r.example/";

    @Test
    void trailingDataAfterTheSignatureDocumentIsRejected() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        String good = new String(RegistrySignature.sign(INDEX, kp.getPrivate(), "k1"), StandardCharsets.UTF_8);
        for (String suffix : new String[] {"{}", " garbage", "]"}) {
            byte[] bad = (good + suffix).getBytes(StandardCharsets.UTF_8);
            assertThrows(RegistrySecurityException.class,
                () -> RegistrySignature.verify(INDEX, bad, trusting(kp, "k1", R), R), suffix);
        }
    }

    @Test
    void wrongAlgorithmWrongKeyIdAndWrongLengthAreRejected() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        String good = new String(RegistrySignature.sign(INDEX, kp.getPrivate(), "k1"), StandardCharsets.UTF_8);
        TrustedKeys keys = trusting(kp, "k1", R);
        byte[] wrongAlg = good.replace("ed25519", "rsa").getBytes(StandardCharsets.UTF_8);
        byte[] wrongId = good.replace("\"k1\"", "\"k2\"").getBytes(StandardCharsets.UTF_8);
        String shortSig = "{\"signatures\":[{\"keyid\":\"k1\",\"alg\":\"ed25519\",\"sig\":\"AAAA\"}]}";
        for (byte[] b : new byte[][] {wrongAlg, wrongId, shortSig.getBytes(StandardCharsets.UTF_8)}) {
            var e = assertThrows(RegistrySecurityException.class, () -> RegistrySignature.verify(INDEX, b, keys, R));
            assertEquals("REGISTRY_BAD_SIGNATURE", e.code());
        }
    }

    @Test
    void oversizedAndOverlongSignatureFilesAreRejected() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] one = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        byte[][] many = new byte[RegistrySignature.MAX_SIGNATURES + 1][];
        java.util.Arrays.fill(many, one);
        byte[] tooMany = RegistrySignature.combine(many);
        TrustedKeys keys = trusting(kp, "k1", R);
        assertThrows(RegistrySecurityException.class, () -> RegistrySignature.verify(INDEX, tooMany, keys, R));
        byte[] huge = new byte[RegistrySignature.MAX_SIGNATURE_FILE_BYTES + 1];
        java.util.Arrays.fill(huge, (byte) ' ');
        assertThrows(RegistrySecurityException.class, () -> RegistrySignature.verify(INDEX, huge, keys, R));
    }

    @Test
    void anUnrelatedValidSignatureDoesNotMaskABadOne() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        byte[] other = "{\"schemaVersion\":9}".getBytes(StandardCharsets.UTF_8);
        assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(other, sig, trusting(kp, "k1", R), R));
    }

    @Test
    void invalidTrustedKeysAreIllegalArgumentNotCrashes() {
        for (String junk : new String[] {"not json", "[]", "{\"keys\":[1]}", "{\"keys\":[{\"keyid\":\"k\"}]}",
            "{\"keys\":[{\"keyid\":\"k\",\"registryId\":\"r\",\"publicKey\":\"AAAA\"}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> TrustedKeys.parse(junk), junk);
        }
    }
}
