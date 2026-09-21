package io.suko.registry;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Shared path-safety validation for {@link RegistrySource} implementations.
 * <p>
 * Both {@link FileSystemRegistrySource} and {@link HttpRegistrySource} accept
 * a {@code relativePath} that ultimately comes from a manifest &mdash; content
 * that may originate outside this repository (see spec D1 and
 * {@link RegistrySource#resolve(String)}'s Javadoc). Rejecting an absolute
 * path (Unix-style, Windows-style, or a Windows drive letter) is the first,
 * transport-independent line of defense; this class exists so that check is
 * written exactly once instead of drifting between the two implementations.
 * </p>
 * <p>
 * This is deliberately <em>not</em> the whole story: {@link
 * FileSystemRegistrySource} layers two more containment checks on top of this
 * (lexical {@code ../} escape via {@code normalize()}, and a {@code
 * toRealPath()} re-check to catch symlinks), and {@link HttpRegistrySource}
 * performs the equivalent lexical escape check for its own base-concatenation
 * scheme. Neither of those belongs here because they are transport-specific.
 * </p>
 */
final class RegistryPaths {

    private static final Pattern WINDOWS_DRIVE_LETTER = Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private RegistryPaths() {
    }

    /**
     * Rejects {@code relativePath} if it is absolute in any form: a leading
     * {@code /} or {@code \}, a Windows drive letter (e.g. {@code C:/}), or
     * anything {@link Path#isAbsolute()} considers absolute on this JVM's
     * default filesystem.
     *
     * @throws IllegalArgumentException if {@code relativePath} is absolute
     */
    static void requireRelative(String relativePath) {
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || WINDOWS_DRIVE_LETTER.matcher(relativePath).matches()) {
            throw new IllegalArgumentException("Path must be relative to the registry base, found absolute path: " + relativePath);
        }
        if (Path.of(relativePath).isAbsolute()) {
            throw new IllegalArgumentException("Path must be relative to the registry base, found absolute path: " + relativePath);
        }
    }
}
