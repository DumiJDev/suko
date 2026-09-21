package io.suko.cli;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class HashesTest {

    @Test
    void crlfAndLfVariantsOfTheSameContentHashTheSame() {
        byte[] lf = "line one\nline two\n".getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "line one\r\nline two\r\n".getBytes(StandardCharsets.UTF_8);

        assertEquals(Hashes.sha256OfNormalized(lf), Hashes.sha256OfNormalized(crlf));
    }

    @Test
    void loneCrIsAlsoNormalizedToLf() {
        byte[] lf = "line one\nline two\n".getBytes(StandardCharsets.UTF_8);
        byte[] cr = "line one\rline two\r".getBytes(StandardCharsets.UTF_8);

        assertEquals(Hashes.sha256OfNormalized(lf), Hashes.sha256OfNormalized(cr));
    }

    @Test
    void differentContentProducesDifferentHash() {
        byte[] a = "content a".getBytes(StandardCharsets.UTF_8);
        byte[] b = "content b".getBytes(StandardCharsets.UTF_8);

        assertNotEquals(Hashes.sha256OfNormalized(a), Hashes.sha256OfNormalized(b));
    }

    @Test
    void hashIsLowercaseHex64Chars() {
        String hash = Hashes.sha256OfNormalized("anything".getBytes(StandardCharsets.UTF_8));

        assertEquals(64, hash.length());
        assertEquals(hash, hash.toLowerCase());
        assertEquals(hash, hash.replaceAll("[^0-9a-f]", ""));
    }
}
