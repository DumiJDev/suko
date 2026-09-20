package io.suko.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelTest {

    @Test
    void testSchemaVersion() {
        assertEquals(1, RegistryIndex.SCHEMA_VERSION);
    }

    @Test
    void testComponentManifestWithEmptyDependsOn() {
        ComponentManifest manifest = new ComponentManifest(
                1,
                "button",
                "1.0.0",
                "A reusable button component",
                "UI",
                "io.suko.components",
                "button",
                "Button",
                List.of(new ComponentFile("index.suko", "Button.suko", "abc123")),
                List.of(),  // empty dependsOn
                List.of()   // empty externalRequirements
        );

        assertEquals("button", manifest.name());
        assertEquals("1.0.0", manifest.version());
        assertEquals("A reusable button component", manifest.description());
        assertEquals("UI", manifest.category());
        assertTrue(manifest.dependsOn().isEmpty());
    }

    @Test
    void testRegistryIndexWithCompleteManifest() {
        ComponentManifest manifest = new ComponentManifest(
                1,
                "button",
                "1.0.0",
                "A reusable button component",
                "UI",
                "io.suko.components",
                "button",
                "Button",
                List.of(new ComponentFile("index.suko", "Button.suko", "abc123")),
                List.of("core"),
                List.of(new ExternalRequirement("gradle", "com.example:lib", "1.0.0"))
        );

        RegistryIndex.Entry entry = new RegistryIndex.Entry(
                "button",
                "1.0.0",
                "A reusable button component",
                "UI",
                "button.json"
        );

        RegistryIndex index = new RegistryIndex(
                1,
                "1.0.0",
                "io.suko.components",
                List.of(entry)
        );

        assertEquals(1, index.schemaVersion());
        assertEquals("1.0.0", index.registryVersion());
        assertEquals("io.suko.components", index.basePackage());
        assertEquals(1, index.components().size());

        RegistryIndex.Entry retrieved = index.components().get(0);
        assertEquals("button", retrieved.name());
        assertEquals("1.0.0", retrieved.version());
        assertEquals("A reusable button component", retrieved.description());
        assertEquals("UI", retrieved.category());
        assertEquals("button.json", retrieved.manifest());
    }

    @Test
    void testComponentFileRelativePathsArePreserved() {
        ComponentFile file = new ComponentFile("relative/path/to/index.suko", "Button.suko", "sha256hash");

        assertEquals("relative/path/to/index.suko", file.path());
        assertEquals("Button.suko", file.target());
        assertEquals("sha256hash", file.sha256());
    }

    @Test
    void testExternalRequirement() {
        ExternalRequirement req = new ExternalRequirement("gradle", "com.google.code.gson:gson", "2.11.0");

        assertEquals("gradle", req.kind());
        assertEquals("com.google.code.gson:gson", req.id());
        assertEquals("2.11.0", req.versionRange());
    }
}
