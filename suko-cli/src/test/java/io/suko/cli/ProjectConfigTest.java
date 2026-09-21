package io.suko.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ProjectConfigTest {

    @Test
    void writesThenReadsBackIdenticalConfig(@TempDir Path projectDir) {
        ProjectConfig config = new ProjectConfig(1, "src/main/suko", "com.acme.web",
                new ProjectConfig.Registry("https://raw.githubusercontent.com/acme/suko/v0.2.0/suko-components/", "v0.2.0"));

        config.write(projectDir);

        Optional<ProjectConfig> read = ProjectConfig.load(projectDir);
        assertTrue(read.isPresent());
        assertEquals(config, read.get());
    }

    @Test
    void writtenFileMatchesD6ShapeAndUsesLf(@TempDir Path projectDir) throws IOException {
        ProjectConfig config = new ProjectConfig(1, "src/main/suko", "com.acme.web",
                new ProjectConfig.Registry("https://raw.githubusercontent.com/acme/suko/v0.2.0/suko-components/", "v0.2.0"));

        config.write(projectDir);

        String written = Files.readString(projectDir.resolve("suko.json"), StandardCharsets.UTF_8);
        assertFalse(written.contains("\r\n"), "must be written with LF only, found CRLF: " + written);
        assertTrue(written.contains("\"schemaVersion\": 1"));
        assertTrue(written.contains("\"sourceRoot\": \"src/main/suko\""));
        assertTrue(written.contains("\"basePackage\": \"com.acme.web\""));
        assertTrue(written.contains("\"registry\""));
        assertTrue(written.contains("\"base\": \"https://raw.githubusercontent.com/acme/suko/v0.2.0/suko-components/\""));
        assertTrue(written.contains("\"ref\": \"v0.2.0\""));
    }

    @Test
    void loadReturnsEmptyWhenNoSukoJsonExists(@TempDir Path projectDir) {
        assertTrue(ProjectConfig.load(projectDir).isEmpty());
    }

    @Test
    void resolvePrefersFlagOverFileOverDefault(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("suko.json"), """
                {
                  "schemaVersion": 1,
                  "sourceRoot": "from-file/suko",
                  "basePackage": "com.file.pkg",
                  "registry": { "base": "from-file-base", "ref": "from-file-ref" }
                }
                """, StandardCharsets.UTF_8);

        // Flag overrides file for sourceRoot and basePackage; file value
        // is used for registryRef since no flag is given for it.
        Args args = Args.parse(new String[] {
                "list",
                "--source-root", "from-flag/suko",
                "--base-package", "com.flag.pkg",
                "--registry", "from-flag-base"
        });

        ProjectConfig resolved = ProjectConfig.resolve(args, projectDir);

        assertEquals("from-flag/suko", resolved.sourceRoot());
        assertEquals("com.flag.pkg", resolved.basePackage());
        assertEquals("from-flag-base", resolved.registry().base());
        assertEquals("from-file-ref", resolved.registry().ref());
    }

    @Test
    void resolveFallsBackToFileWhenNoFlagGiven(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("suko.json"), """
                {
                  "schemaVersion": 1,
                  "sourceRoot": "from-file/suko",
                  "basePackage": "com.file.pkg",
                  "registry": { "base": "from-file-base", "ref": "from-file-ref" }
                }
                """, StandardCharsets.UTF_8);

        Args args = Args.parse(new String[] { "list" });

        ProjectConfig resolved = ProjectConfig.resolve(args, projectDir);

        assertEquals("from-file/suko", resolved.sourceRoot());
        assertEquals("com.file.pkg", resolved.basePackage());
    }

    @Test
    void resolveFallsBackToDefaultSourceRootWhenNeitherFlagNorFilePresent(@TempDir Path projectDir) {
        Args args = Args.parse(new String[] { "list", "--base-package", "com.acme.web" });

        ProjectConfig resolved = ProjectConfig.resolve(args, projectDir);

        assertEquals(ProjectConfig.DEFAULT_SOURCE_ROOT, resolved.sourceRoot());
    }

    @Test
    void resolveWithoutSukoJsonAndWithoutBasePackageFlagSuggestsInit(@TempDir Path projectDir) {
        Args args = Args.parse(new String[] { "list" });

        CliException e = assertThrows(CliException.class, () -> ProjectConfig.resolve(args, projectDir));

        assertTrue(e.getMessage().contains("suko init"), "message should suggest `suko init`: " + e.getMessage());
        assertFalse(e.getMessage().toLowerCase().contains("exception"), "message should not look like a stack trace: " + e.getMessage());
    }

    @Test
    void basePackageReservedWordSegmentIsRejectedAndNamed() {
        CliException e = assertThrows(CliException.class, () -> ProjectConfig.validateBasePackage("com.class.ui"));

        assertTrue(e.getMessage().contains("\"class\""), "message should name the offending segment: " + e.getMessage());
    }

    @Test
    void basePackageSegmentStartingWithDigitIsRejectedAndNamed() {
        CliException e = assertThrows(CliException.class, () -> ProjectConfig.validateBasePackage("com.9lives.ui"));

        assertTrue(e.getMessage().contains("\"9lives\""), "message should name the offending segment: " + e.getMessage());
    }

    @Test
    void emptyBasePackageIsRejected() {
        assertThrows(CliException.class, () -> ProjectConfig.validateBasePackage(""));
    }

    @Test
    void basePackageWithEmptySegmentIsRejectedAndNamesTheSegmentPosition() {
        CliException e = assertThrows(CliException.class, () -> ProjectConfig.validateBasePackage("com..acme"));

        assertTrue(e.getMessage().contains("segment 2"), "message should name which segment is empty: " + e.getMessage());
    }

    @Test
    void validBasePackagePassesWithoutThrowing() {
        assertDoesNotThrow(() -> ProjectConfig.validateBasePackage("com.acme.web"));
    }

    @Test
    void unsupportedFutureSchemaVersionIsRejectedNamingBothVersions(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("suko.json"), """
                {
                  "schemaVersion": 99,
                  "sourceRoot": "src/main/suko",
                  "basePackage": "com.acme.web",
                  "registry": { "base": "b", "ref": "r" }
                }
                """, StandardCharsets.UTF_8);

        CliException e = assertThrows(CliException.class, () -> ProjectConfig.load(projectDir));

        assertTrue(e.getMessage().contains("99"));
        assertTrue(e.getMessage().contains(String.valueOf(ProjectConfig.SCHEMA_VERSION)));
    }

    @Test
    void malformedJsonProducesReadableMessageNotAStackTrace(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve("suko.json"), "{ not valid json", StandardCharsets.UTF_8);

        CliException e = assertThrows(CliException.class, () -> ProjectConfig.load(projectDir));

        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }
}
