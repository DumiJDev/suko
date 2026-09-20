package io.suko.registry;

import java.io.IOException;

/**
 * A source of registry content, addressed by paths relative to a base.
 * <p>
 * The base is either a local directory (see {@link FileSystemRegistrySource})
 * or, in subprojeto 8, an HTTPS URL — the two are the same operation because
 * every path inside a manifest is relative to a base, never absolute and
 * never a complete URL (see spec D1). This is deliberately the only shape
 * shared by both transports.
 * </p>
 */
public interface RegistrySource {

    /**
     * Reads a path relative to {@link #base()}.
     * <p>
     * Implementations MUST reject absolute paths and any {@code relativePath}
     * that, once resolved against the base, escapes it (path traversal). This
     * is a security boundary, not just robustness: subprojeto 8 feeds content
     * originating outside this repository through this method.
     * </p>
     *
     * @throws IllegalArgumentException if {@code relativePath} is absolute or
     *                                   escapes the base
     * @throws IOException              if the resolved resource cannot be read
     */
    byte[] resolve(String relativePath) throws IOException;

    /** The base this source resolves relative paths against. */
    String base();
}
