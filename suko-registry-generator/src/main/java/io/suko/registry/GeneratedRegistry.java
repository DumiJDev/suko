package io.suko.registry;

import java.util.Map;

/**
 * In-memory result of {@link RegistryGenerator#generate}: the top-level
 * {@link RegistryIndex} plus every {@link ComponentManifest}, keyed by the
 * lowercase registry name (matching {@link RegistryIndex.Entry#name()} and
 * {@link ComponentManifest#name()}).
 * <p>
 * Task 9 invokes the generator twice against this shape: once to write
 * {@code registry.json}/{@code components/<name>.json}, once more to compare
 * against what is committed — a divergence between the two runs (or between
 * a fresh run and the committed JSON) means the manifest has drifted from
 * the sources.
 * </p>
 */
public record GeneratedRegistry(RegistryIndex index, Map<String, ComponentManifest> manifestsByName) {
}
