package io.suko.registry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Local filesystem implementation of {@link RegistrySource}. This is the
 * fallback path from spec D1 and the path used for development and tests;
 * the HTTPS implementation is subprojeto 8.
 */
public final class FileSystemRegistrySource implements RegistrySource {

    private static final Pattern WINDOWS_DRIVE_LETTER = Pattern.compile("^[A-Za-z]:[/\\\\].*");

    private final Path base;

    public FileSystemRegistrySource(Path base) {
        this.base = base;
    }

    @Override
    public byte[] resolve(String relativePath) throws IOException {
        requireRelative(relativePath);

        Path baseAbsolute = base.toAbsolutePath().normalize();
        Path resolved = baseAbsolute.resolve(relativePath).normalize();

        if (!resolved.startsWith(baseAbsolute)) {
            throw new IllegalArgumentException(
                    "Path \"" + relativePath + "\" escapes the registry base \"" + base + "\"");
        }

        return Files.readAllBytes(resolved);
    }

    @Override
    public String base() {
        return base.toString();
    }

    private static void requireRelative(String relativePath) {
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")
                || WINDOWS_DRIVE_LETTER.matcher(relativePath).matches()) {
            throw new IllegalArgumentException("Path must be relative to the registry base, found absolute path: " + relativePath);
        }
        if (Path.of(relativePath).isAbsolute()) {
            throw new IllegalArgumentException("Path must be relative to the registry base, found absolute path: " + relativePath);
        }
    }
}
