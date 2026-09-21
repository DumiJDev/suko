package io.suko.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * Local filesystem implementation of {@link RegistrySource}. This is the
 * fallback path from spec D1 and the path used for development and tests;
 * {@link HttpRegistrySource} is the HTTPS counterpart (subprojeto 8).
 */
public final class FileSystemRegistrySource implements RegistrySource {

    private final Path base;

    public FileSystemRegistrySource(Path base) {
        this.base = base;
    }

    @Override
    public byte[] resolve(String relativePath) throws IOException {
        RegistryPaths.requireRelative(relativePath);

        Path baseAbsolute = base.toAbsolutePath().normalize();
        Path resolved = baseAbsolute.resolve(relativePath).normalize();

        // Lexical containment first: rejects "../escapes-the-base" style
        // paths outright, before we even touch the filesystem.
        if (!resolved.startsWith(baseAbsolute)) {
            throw new IllegalArgumentException(
                    "Path \"" + relativePath + "\" escapes the registry base \"" + base + "\"");
        }

        // Path.normalize() is purely lexical: it does not resolve symlinks.
        // A symlink living inside the base but pointing outside of it (e.g.
        // "components/evil-link.txt -> /outside/secret") would pass the
        // check above while still reading a file outside the base at the
        // OS level. Resolve real paths (following symlinks) and re-check
        // containment before actually reading the file.
        //
        // If the target does not exist yet, toRealPath() throws
        // NoSuchFileException; that is a "missing file" outcome, not a
        // security violation, so we fall through and let
        // Files.readAllBytes report the natural IOException instead of
        // misreporting it as a path-escape.
        Path resolvedReal;
        try {
            resolvedReal = resolved.toRealPath();
        } catch (NoSuchFileException e) {
            return Files.readAllBytes(resolved);
        }

        Path baseReal = baseAbsolute.toRealPath();
        if (!resolvedReal.startsWith(baseReal)) {
            throw new IllegalArgumentException(
                    "Path \"" + relativePath + "\" resolves (via symlink) outside the registry base \""
                            + base + "\"");
        }

        return Files.readAllBytes(resolvedReal);
    }

    @Override
    public String base() {
        return base.toString();
    }
}
