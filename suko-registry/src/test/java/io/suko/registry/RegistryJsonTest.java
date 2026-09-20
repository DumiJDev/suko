package io.suko.registry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryJsonTest {

    private ComponentManifest sampleManifest() {
        return new ComponentManifest(
                RegistryIndex.SCHEMA_VERSION,
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
    }

    private RegistryIndex sampleIndex() {
        RegistryIndex.Entry entry = new RegistryIndex.Entry(
                "button", "1.0.0", "A reusable button component", "UI", "components/button.json");
        return new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.1.0", "io.suko", List.of(entry));
    }

    @Test
    void roundTripsComponentManifest() {
        ComponentManifest original = sampleManifest();
        String json = RegistryJson.writeManifest(original);
        ComponentManifest parsed = RegistryJson.readManifest(json);
        assertEquals(original, parsed);
    }

    @Test
    void roundTripsRegistryIndex() {
        RegistryIndex original = sampleIndex();
        String json = RegistryJson.writeIndex(original);
        RegistryIndex parsed = RegistryJson.readIndex(json);
        assertEquals(original, parsed);
    }

    @Test
    void rejectsManifestWithUnknownSchemaVersionWithReadableMessage() {
        String json = """
                {
                  "schemaVersion": 99,
                  "name": "button",
                  "version": "1.0.0",
                  "description": "d",
                  "category": "UI",
                  "basePackage": "io.suko",
                  "packageSuffix": "ui",
                  "component": "Button",
                  "files": [],
                  "dependsOn": [],
                  "externalRequirements": []
                }
                """;

        RegistryJsonException ex = assertThrows(RegistryJsonException.class,
                () -> RegistryJson.readManifest(json));

        assertTrue(ex.getMessage().contains("99"), "message should mention the found version: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(RegistryIndex.SCHEMA_VERSION)),
                "message should mention the supported version: " + ex.getMessage());
    }

    @Test
    void rejectsIndexWithUnknownSchemaVersionWithReadableMessage() {
        String json = """
                {
                  "schemaVersion": 42,
                  "registryVersion": "0.1.0",
                  "basePackage": "io.suko",
                  "components": []
                }
                """;

        RegistryJsonException ex = assertThrows(RegistryJsonException.class,
                () -> RegistryJson.readIndex(json));

        assertTrue(ex.getMessage().contains("42"), "message should mention the found version: " + ex.getMessage());
        assertTrue(ex.getMessage().contains(String.valueOf(RegistryIndex.SCHEMA_VERSION)),
                "message should mention the supported version: " + ex.getMessage());
    }

    @Test
    void rejectsManifestMissingSchemaVersionField() {
        String json = """
                {
                  "name": "button",
                  "version": "1.0.0"
                }
                """;

        RegistryJsonException ex = assertThrows(RegistryJsonException.class,
                () -> RegistryJson.readManifest(json));

        assertTrue(ex.getMessage().toLowerCase().contains("schemaversion"),
                "message should name the missing field: " + ex.getMessage());
    }

    @Test
    void rejectsManifestMissingRequiredField() {
        String json = """
                {
                  "schemaVersion": 1,
                  "version": "1.0.0",
                  "description": "d",
                  "category": "UI",
                  "basePackage": "io.suko",
                  "packageSuffix": "ui",
                  "component": "Button",
                  "files": [],
                  "dependsOn": [],
                  "externalRequirements": []
                }
                """;

        RegistryJsonException ex = assertThrows(RegistryJsonException.class,
                () -> RegistryJson.readManifest(json));

        assertTrue(ex.getMessage().toLowerCase().contains("name"),
                "message should name the missing field: " + ex.getMessage());
    }

    @Test
    void rejectsMalformedJsonWithReadableMessage() {
        String json = "{ this is not valid json";

        RegistryJsonException ex = assertThrows(RegistryJsonException.class,
                () -> RegistryJson.readManifest(json));

        assertNotNull(ex.getMessage());
        assertFalse(ex.getMessage().isBlank());
    }

    @Test
    void writeManifestProducesStableKeyOrder() {
        String json = RegistryJson.writeManifest(sampleManifest());

        int schemaVersionIdx = json.indexOf("\"schemaVersion\"");
        int nameIdx = json.indexOf("\"name\"");
        int versionIdx = json.indexOf("\"version\"");
        int descriptionIdx = json.indexOf("\"description\"");
        int categoryIdx = json.indexOf("\"category\"");
        int basePackageIdx = json.indexOf("\"basePackage\"");
        int packageSuffixIdx = json.indexOf("\"packageSuffix\"");
        int componentIdx = json.indexOf("\"component\"");
        int filesIdx = json.indexOf("\"files\"");
        int dependsOnIdx = json.indexOf("\"dependsOn\"");
        int externalReqIdx = json.indexOf("\"externalRequirements\"");

        assertTrue(schemaVersionIdx < nameIdx);
        assertTrue(nameIdx < versionIdx);
        assertTrue(versionIdx < descriptionIdx);
        assertTrue(descriptionIdx < categoryIdx);
        assertTrue(categoryIdx < basePackageIdx);
        assertTrue(basePackageIdx < packageSuffixIdx);
        assertTrue(packageSuffixIdx < componentIdx);
        assertTrue(componentIdx < filesIdx);
        assertTrue(filesIdx < dependsOnIdx);
        assertTrue(dependsOnIdx < externalReqIdx);
    }

    @Test
    void writeManifestIsPrettyPrinted() {
        String json = RegistryJson.writeManifest(sampleManifest());
        assertTrue(json.contains("\n"), "expected indented (multi-line) output");
    }
}
