package io.suko.registry;

import java.util.List;

public record RegistryIndex(int schemaVersion, String registryVersion, String basePackage,
                            List<Entry> components) {
    public static final int SCHEMA_VERSION = 1;

    public record Entry(String name, String version, String description,
                        String category, String manifest) { }
}
