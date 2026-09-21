package io.suko.cli;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 hashing with line-ending normalization (spec D8).
 * <p>
 * The CLI always <em>writes</em> LF, never a platform-specific line
 * separator (see {@link NamespaceRewriter}). But a consumer whose Git
 * checkout has {@code core.autocrlf=true} can end up with CRLF on disk
 * even though the CLI wrote LF — {@code core.autocrlf} rewrites the file
 * on checkout, after the CLI is done. If hashing did not normalize this
 * away, every such consumer would see the reconciliation matrix
 * (see {@link Reconciler}) misreport an untouched file as "edited
 * locally", purely because of line endings.
 * </p>
 * <p>
 * {@link #sha256OfNormalized(byte[])} is therefore the <em>only</em>
 * hashing entry point this module uses to compare bytes on disk against a
 * stored hash: CRLF and CR are both folded to LF before the digest is
 * computed, so a file that only differs from what the CLI wrote by its
 * line endings hashes identically.
 * </p>
 */
public final class Hashes {

    private Hashes() {
    }

    /**
     * Hashes {@code content} as UTF-8 text, after normalizing every
     * {@code \r\n} and lone {@code \r} to {@code \n}. The result is a
     * lowercase hex-encoded SHA-256 digest.
     */
    public static String sha256OfNormalized(byte[] content) {
        return sha256Hex(normalizeLineEndings(content));
    }

    /**
     * Plain (unnormalized) SHA-256 of {@code content}, matching how
     * {@code suko-registry-generator} computes {@link
     * io.suko.registry.ComponentFile#sha256()} — from raw source bytes,
     * before any namespace rewriting or line-ending normalization.
     * <p>
     * Deliberately distinct from {@link #sha256OfNormalized(byte[])}: this
     * method is for the one place the manifest's own hash is compared
     * against real bytes — freshly fetched, pre-rewrite content, as part of
     * verifying it against {@code ComponentFile.sha256()} ({@code
     * AddCommand}, {@code DiffCommand}, {@code UpdateCommand}, all before
     * any rewriting happens). It must never be used to compare bytes
     * already on disk against a stored hash — that comparison is always
     * {@link #sha256OfNormalized(byte[])}, via {@link Reconciler}.
     * </p>
     */
    public static String sha256OfRaw(byte[] content) {
        return sha256Hex(content);
    }

    private static byte[] normalizeLineEndings(byte[] content) {
        String text = new String(content, StandardCharsets.UTF_8);
        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        return normalized.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by every JDK's default security
            // provider (JCA standard algorithm names); this cannot
            // actually happen on a conforming JVM.
            throw new IllegalStateException("SHA-256 MessageDigest not available", e);
        }
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }
}
