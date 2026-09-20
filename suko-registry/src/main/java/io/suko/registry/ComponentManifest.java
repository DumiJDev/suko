package io.suko.registry;

import java.util.List;

public record ComponentManifest(int schemaVersion, String name, String version, String description,
                                String category, String basePackage, String packageSuffix,
                                String component, List<ComponentFile> files,
                                List<String> dependsOn, List<ExternalRequirement> externalRequirements) { }
