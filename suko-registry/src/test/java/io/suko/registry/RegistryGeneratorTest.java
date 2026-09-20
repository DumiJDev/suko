package io.suko.registry;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Fixtures live under {@code src/test/resources/generator-fixtures/} and are
 * a small, independent mini-library — not the real 8-component library of
 * Task 5-8, which does not exist yet when this test is written.
 */
class RegistryGeneratorTest {

    private static final Path VALID_FIXTURE = Path.of("src/test/resources/generator-fixtures/valid");

    private static GeneratorConfig configFor(Path fixtureRoot, Map<String, GeneratorConfig.ComponentConfig> componentConfigs) {
        return new GeneratorConfig(
                "io.suko",
                "0.1.0",
                "src/main/suko",
                fixtureRoot.resolve("descriptions.properties"),
                componentConfigs);
    }

    private static GeneratorConfig.ComponentConfig config(String version, String category) {
        return new GeneratorConfig.ComponentConfig(version, category, List.of());
    }

    @Test
    void componentNameIsLowercaseOfComponentDeclName() {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));

        ComponentManifest label = registry.manifestsByName().get("label");
        assertNotNull(label, registry.manifestsByName().keySet().toString());
        assertEquals("label", label.name());
        assertEquals("Label", label.component());

        ComponentManifest field = registry.manifestsByName().get("field");
        assertNotNull(field);
        assertEquals("field", field.name());
        assertEquals("Field", field.component());
    }

    @Test
    void packageSuffixIsPackageMinusBasePackage() {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));

        assertEquals("ui", registry.manifestsByName().get("label").packageSuffix());
        assertEquals("io.suko", registry.manifestsByName().get("label").basePackage());
    }

    @Test
    void dependsOnComesFromImportsResolvedAgainstTheRegistryAndIgnoresExternalImports() {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));

        ComponentManifest field = registry.manifestsByName().get("field");
        // Field.sk also imports io.suko.external.Thing, which does not exist
        // anywhere under this fixture's source root: it must be silently
        // ignored, not turned into an error or into a dependsOn entry.
        assertEquals(List.of("label"), field.dependsOn());

        ComponentManifest label = registry.manifestsByName().get("label");
        assertEquals(List.of(), label.dependsOn());
    }

    @Test
    void sha256MatchesTheRealFileContent() throws Exception {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));

        byte[] content = java.nio.file.Files.readAllBytes(VALID_FIXTURE.resolve("io/suko/ui/Label.sk"));
        String expectedSha256 = sha256Hex(content);

        ComponentManifest label = registry.manifestsByName().get("label");
        assertEquals(1, label.files().size());
        assertEquals(expectedSha256, label.files().get(0).sha256());
        assertEquals("src/main/suko/io/suko/ui/Label.sk", label.files().get(0).path());
        assertEquals("ui/Label.sk", label.files().get(0).target());
    }

    @Test
    void fileWithTwoComponentsFailsWithExplicitMessage() {
        Path fixture = Path.of("src/test/resources/generator-fixtures/two-components-per-file");

        RegistryGeneratorException exception = assertThrows(RegistryGeneratorException.class,
                () -> RegistryGenerator.generate(fixture, configFor(fixture, Map.of())));

        assertTrue(exception.getMessage().contains("Bad.sk"), exception.getMessage());
        assertTrue(exception.getMessage().contains("one component per file"), exception.getMessage());
    }

    @Test
    void componentWithoutPublicFailsWithExplicitMessage() {
        Path fixture = Path.of("src/test/resources/generator-fixtures/not-public");

        RegistryGeneratorException exception = assertThrows(RegistryGeneratorException.class,
                () -> RegistryGenerator.generate(fixture, configFor(fixture, Map.of())));

        assertTrue(exception.getMessage().contains("Bad"), exception.getMessage());
        assertTrue(exception.getMessage().contains("public"), exception.getMessage());
    }

    @Test
    void cyclicDependsOnFailsWithAMessageNamingTheCycle() {
        Path fixture = Path.of("src/test/resources/generator-fixtures/cyclic");

        RegistryGeneratorException exception = assertThrows(RegistryGeneratorException.class,
                () -> RegistryGenerator.generate(fixture, configFor(fixture, Map.of(
                        "A", config("1.0.0", "form"),
                        "B", config("1.0.0", "form")))));

        assertTrue(exception.getMessage().contains("a"), exception.getMessage());
        assertTrue(exception.getMessage().contains("b"), exception.getMessage());
        assertTrue(exception.getMessage().toLowerCase(java.util.Locale.ROOT).contains("cycl"),
                exception.getMessage());
    }

    @Test
    void missingDescriptionFailsWithExplicitMessage() {
        GeneratorConfig config = configFor(VALID_FIXTURE, Map.of("Field", config("1.0.0", "form")));
        // Overwrite with a descriptions file missing the "Label" entry.
        GeneratorConfig configMissingLabelDescription = new GeneratorConfig(
                config.basePackage(), config.registryVersion(), config.sourceRootPrefix(),
                Path.of("src/test/resources/generator-fixtures/valid/descriptions-missing-label.properties"),
                Map.of("Label", config("1.0.0", "form"), "Field", config("1.0.0", "form")));

        RegistryGeneratorException exception = assertThrows(RegistryGeneratorException.class,
                () -> RegistryGenerator.generate(VALID_FIXTURE, configMissingLabelDescription));

        assertTrue(exception.getMessage().contains("Label"), exception.getMessage());
    }

    private static String sha256Hex(byte[] content) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(content);
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
