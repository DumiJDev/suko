package io.suko.components;

import io.suko.registry.ComponentFile;
import io.suko.registry.ComponentManifest;
import io.suko.registry.ExternalRequirement;
import io.suko.registry.GeneratedRegistry;
import io.suko.registry.GeneratorConfig;
import io.suko.registry.RegistryGenerator;
import io.suko.registry.RegistryJson;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Golden test for the real, commited registry manifest (Task 9,
 * subprojeto 7): compares {@link RegistryGenerator#generate} run over the
 * real {@code src/main/suko} sources against {@code registry.json} +
 * {@code components/*.json} committed at the module root. This is the same
 * shape {@link GeneratedRegistry}'s own javadoc describes: the generator is
 * invoked twice — once by {@code generateRegistry} (see
 * {@link #main(String[])}, reused by the Gradle task of the same name) to
 * write the committed files, once more here to compare against them.
 * <p>
 * <strong>The committed manifest is always generated, never hand-edited.</strong>
 * A divergence means the {@code .sk} sources (or {@code descriptions.properties},
 * or the config below) changed without regenerating; the fix is always
 * {@code gradle :suko-components:generateRegistry --console=plain} followed by
 * a commit of the result, never editing the JSON directly.
 * </p>
 */
class RegistryGoldenTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "suko");
    private static final Path MODULE_ROOT = Path.of(".");
    private static final Path COMPONENTS_DIR = MODULE_ROOT.resolve("components");
    private static final String REGENERATE_HINT =
            "regenerate with `gradle :suko-components:generateRegistry --console=plain` and commit the result";

    // ------------------------------------------------------------------
    // Shared config: the SAME GeneratorConfig used by the `generateRegistry`
    // Gradle task's entry point (main, below) and by every test in this
    // class. A single source of truth here is what makes "test compares
    // against what the task would produce" true by construction, instead
    // of by two configs happening to agree.
    // ------------------------------------------------------------------

    // Every component in this library uses plain Tailwind utility classes in
    // its markup (see spec D6), so every component declares this requirement
    // — this is what makes the manifest self-sufficient for consumers that
    // only read the JSON and never the .sk source (subprojeto 8).
    private static final List<ExternalRequirement> TAILWIND =
            java.util.List.of(new ExternalRequirement("css", "tailwindcss", "3.x"));

    private static GeneratorConfig buildConfig() {
        Map<String, GeneratorConfig.ComponentConfig> componentConfigs = new LinkedHashMap<>();
        componentConfigs.put("Button", new GeneratorConfig.ComponentConfig("0.1.0", "action", TAILWIND));
        componentConfigs.put("Input", new GeneratorConfig.ComponentConfig("0.1.0", "form", TAILWIND));
        componentConfigs.put("Label", new GeneratorConfig.ComponentConfig("0.1.0", "form", TAILWIND));
        componentConfigs.put("Badge", new GeneratorConfig.ComponentConfig("0.1.0", "feedback", TAILWIND));
        componentConfigs.put("Alert", new GeneratorConfig.ComponentConfig("0.1.0", "feedback", TAILWIND));
        componentConfigs.put("Card", new GeneratorConfig.ComponentConfig("0.1.0", "layout", TAILWIND));
        componentConfigs.put("Field", new GeneratorConfig.ComponentConfig("0.1.0", "form", TAILWIND));
        // Dialog additionally needs Alpine.js (x-data/x-show), on top of the
        // Tailwind requirement every component shares.
        componentConfigs.put("Dialog", new GeneratorConfig.ComponentConfig("0.1.0", "overlay",
                java.util.List.of(new ExternalRequirement("css", "tailwindcss", "3.x"),
                        new ExternalRequirement("js", "alpinejs", "3.x"))));

        return new GeneratorConfig(
                "io.suko",
                "0.1.0",
                "src/main/suko",
                SOURCE_ROOT.resolve("descriptions.properties"),
                Map.copyOf(componentConfigs));
    }

    /**
     * Entry point for the {@code :suko-components:generateRegistry} Gradle
     * task (see {@code build.gradle.kts}). Deliberately has no logic of its
     * own beyond writing what {@link RegistryGenerator#generate} already
     * produced to disk — every actual rule (one component per file, public,
     * acyclic dependsOn, sha256, description lookup) lives in
     * {@code suko-registry}, already unit-tested there.
     */
    public static void main(String[] args) throws IOException {
        GeneratedRegistry registry = RegistryGenerator.generate(SOURCE_ROOT, buildConfig());

        Files.writeString(MODULE_ROOT.resolve("registry.json"),
                RegistryJson.writeIndex(registry.index()) + System.lineSeparator());

        Files.createDirectories(COMPONENTS_DIR);
        for (Map.Entry<String, ComponentManifest> entry : registry.manifestsByName().entrySet()) {
            Files.writeString(COMPONENTS_DIR.resolve(entry.getKey() + ".json"),
                    RegistryJson.writeManifest(entry.getValue()) + System.lineSeparator());
        }

        System.out.println("Wrote registry.json and " + registry.manifestsByName().size()
                + " component manifest(s) under components/.");
    }

    @Test
    void committedRegistryIndexMatchesTheGeneratedOne() throws IOException {
        GeneratedRegistry registry = RegistryGenerator.generate(SOURCE_ROOT, buildConfig());

        String expected = RegistryJson.writeIndex(registry.index()).strip();
        String actual = Files.readString(MODULE_ROOT.resolve("registry.json")).strip();

        assertEquals(expected, actual, "registry.json diverges from src/main/suko; " + REGENERATE_HINT);
    }

    @Test
    void committedComponentManifestsMatchTheGeneratedOnesOneToOne() throws IOException {
        GeneratedRegistry registry = RegistryGenerator.generate(SOURCE_ROOT, buildConfig());

        for (Map.Entry<String, ComponentManifest> entry : registry.manifestsByName().entrySet()) {
            String name = entry.getKey();
            Path committed = COMPONENTS_DIR.resolve(name + ".json");
            assertTrue(Files.exists(committed),
                    "components/" + name + ".json does not exist; " + REGENERATE_HINT);

            String expected = RegistryJson.writeManifest(entry.getValue()).strip();
            String actual = Files.readString(committed).strip();
            assertEquals(expected, actual,
                    "components/" + name + ".json diverges from src/main/suko; " + REGENERATE_HINT);
        }

        // The reverse direction: no leftover manifest for a component that
        // no longer exists (e.g. a .sk file renamed/removed without
        // regenerating) — that would otherwise never fail any assertion
        // above, since the loop only walks the freshly-generated names.
        Set<String> committedNames;
        try (Stream<Path> listing = Files.list(COMPONENTS_DIR)) {
            committedNames = listing
                    .map(p -> p.getFileName().toString().replaceFirst("\\.json$", ""))
                    .collect(Collectors.toSet());
        }
        assertEquals(registry.manifestsByName().keySet(), committedNames,
                "components/ has a manifest with no corresponding .sk component (or is missing one); "
                        + REGENERATE_HINT);
    }

    @Test
    void dependsOnGraphIsAcyclicAndFieldIsTheOnlyArcToday() {
        GeneratedRegistry registry = RegistryGenerator.generate(SOURCE_ROOT, buildConfig());
        Map<String, ComponentManifest> byName = registry.manifestsByName();

        // Acyclicity itself is already enforced by RegistryGenerator.generate
        // (it throws RegistryGeneratorException on a cycle, see
        // RegistryGeneratorTest#cyclicDependsOnFailsWithAMessageNamingTheCycle);
        // reaching this line without an exception IS the acyclic proof for
        // the real library. What this test adds on top: every dependsOn
        // name actually resolves inside this same registry (not just inside
        // whatever ProjectIndex saw), and the graph shape matches what the
        // library is supposed to contain today.
        for (ComponentManifest manifest : byName.values()) {
            for (String dependency : manifest.dependsOn()) {
                assertTrue(byName.containsKey(dependency),
                        manifest.name() + " declares dependsOn '" + dependency
                                + "', which does not exist in the generated index");
            }
        }

        ComponentManifest field = byName.get("field");
        assertTrue(field != null, "expected a 'field' component in the generated registry");
        assertEquals(Set.of("label", "input"), Set.copyOf(field.dependsOn()),
                "field -> {label, input} is the only dependsOn arc expected in the library today");

        for (ComponentManifest manifest : byName.values()) {
            if (manifest.name().equals("field")) {
                continue;
            }
            assertTrue(manifest.dependsOn().isEmpty(),
                    manifest.name() + " is expected to have no dependsOn today, found: " + manifest.dependsOn());
        }
    }

    @Test
    void sha256OfEveryManifestFileMatchesTheRealFileOnDisk() throws Exception {
        GeneratedRegistry registry = RegistryGenerator.generate(SOURCE_ROOT, buildConfig());

        for (ComponentManifest manifest : registry.manifestsByName().values()) {
            for (ComponentFile file : manifest.files()) {
                Path onDisk = MODULE_ROOT.resolve(file.path());
                assertTrue(Files.exists(onDisk),
                        "manifest '" + manifest.name() + "' references '" + file.path() + "', which does not exist");

                String actualSha256 = sha256Hex(Files.readAllBytes(onDisk));
                assertEquals(actualSha256, file.sha256(),
                        "sha256 mismatch for " + file.path()
                                + " — content changed on disk without a matching manifest 'version' bump/regeneration");
            }
        }
    }

    private static String sha256Hex(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
