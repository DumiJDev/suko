package io.suko.registry;

import java.util.List;

/**
 * Registry index, schema 2. {@code expires} is optional ({@code null} for
 * immutable tags); {@code issuedAt} and {@code expires} are ISO-8601 UTC
 * ({@link java.time.Instant#toString()}).
 */
public record RegistryIndex(int schemaVersion, String registryVersion, String basePackage,
                            String registryId, String ref, String issuedAt, String expires,
                            List<Entry> components) {
    public static final int SCHEMA_VERSION = 2;

    public record Entry(String name, String version, String description,
                        String category, String manifest, String manifestSha256) { }
}
