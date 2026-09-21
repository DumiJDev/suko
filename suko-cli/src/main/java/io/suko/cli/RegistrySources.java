package io.suko.cli;

import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.HttpRegistrySource;
import io.suko.registry.RegistrySource;

import java.nio.file.Path;

/**
 * Helper for resolving a registry base (URL or file path) to the appropriate
 * {@link RegistrySource} implementation.
 */
public final class RegistrySources {

    private RegistrySources() {
    }

    /**
     * Determines whether the registry base is an HTTP(S) URL or a file system
     * path, and returns the appropriate {@link RegistrySource}.
     *
     * @param base the registry base (e.g., "http://example.com/registry" or
     *             "/path/to/registry")
     * @return an {@link HttpRegistrySource} if base starts with {@code http://}
     *         or {@code https://}, otherwise a {@link FileSystemRegistrySource}
     */
    public static RegistrySource resolve(String base) {
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return new HttpRegistrySource(base);
        }
        return new FileSystemRegistrySource(Path.of(base));
    }
}
