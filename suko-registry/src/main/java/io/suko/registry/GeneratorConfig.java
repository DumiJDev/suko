package io.suko.registry;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Configuration for {@link RegistryGenerator#generate(Path, GeneratorConfig)},
 * covering everything the {@code .sk} sources themselves cannot express.
 * <p>
 * {@code descriptionsFile} points at a {@code descriptions.properties} file
 * (key = component name, e.g. {@code Field=Label + input emparelhados...}).
 * <strong>Decided by the user (2026-09-20):</strong> descriptions come only
 * from this file, never from {@code //} comments in the {@code .sk} source —
 * the AST does not guarantee comment preservation today, and inventing a
 * description from the component's name would silently produce garbage
 * documentation. A component with no entry in this file fails generation
 * with an explicit message naming it, rather than falling back to a guess.
 * </p>
 * <p>
 * {@code componentConfigs} carries the remaining per-component metadata that
 * likewise cannot be derived from the source: semantic {@code version},
 * {@code category}, and {@code externalRequirements}. Same rule applies — a
 * component missing an entry fails generation explicitly.
 * </p>
 */
public record GeneratorConfig(
        String basePackage,
        String registryVersion,
        String sourceRootPrefix,
        Path descriptionsFile,
        Map<String, ComponentConfig> componentConfigs
) {

    /**
     * Per-component metadata that has no representation in the {@code .sk}
     * source and is therefore supplied by the caller, keyed by the
     * component's declared name (e.g. {@code "Field"}) in
     * {@link GeneratorConfig#componentConfigs()}.
     */
    public record ComponentConfig(String version, String category, List<ExternalRequirement> externalRequirements) {
    }
}
