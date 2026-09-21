package io.suko.cli;

import io.suko.cli.command.ListCommand;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code suko list} tested against the real {@code suko-components/}
 * registry (a checked-out, file-system copy of what would be served over
 * HTTPS in production) rather than a synthetic fixture — the brief for
 * Task 7 asks specifically for this so the test breaks if the real
 * registry's shape ever drifts from what {@code RegistryJson} expects.
 */
class ListCommandTest {

    /**
     * The real registry lives at {@code suko-components/} relative to the
     * repository root. Gradle runs each subproject's tests with that
     * subproject's directory as the working directory, so from
     * {@code suko-cli/} it is one level up.
     */
    private static final Path REAL_REGISTRY_BASE = Path.of("..", "suko-components");

    @Test
    void listsRealRegistryComponentsAlignedByColumn() {
        assumeRealRegistryExists();

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

        Args args = Args.parse(new String[] { "list", "--registry", REAL_REGISTRY_BASE.toString() });
        new ListCommand().run(args, out, Path.of("."));

        String output = captured.toString(StandardCharsets.UTF_8);
        String[] lines = output.strip().split("\n");

        assertTrue(lines.length > 1, "expected a header line plus at least one component: " + output);
        assertTrue(lines[0].contains("NAME") && lines[0].contains("VERSION")
                && lines[0].contains("CATEGORY") && lines[0].contains("DESCRIPTION"), "header: " + lines[0]);

        // The real registry (suko-components/registry.json) is known to
        // contain "button" — assert on its presence and that its row is
        // column-aligned with the header (same start column for VERSION).
        boolean foundButton = false;
        int versionColumn = lines[0].indexOf("VERSION");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].startsWith("button")) {
                foundButton = true;
                assertTrue(lines[i].length() >= versionColumn, "row too short to be aligned: " + lines[i]);
            }
        }
        assertTrue(foundButton, "expected to find \"button\" in the real registry listing:\n" + output);
    }

    @Test
    void resolveViaSukoJsonFileWhenNoRegistryFlagGiven(@TempDir Path projectDir) throws IOException {
        assumeRealRegistryExists();

        ProjectConfig config = new ProjectConfig(1, "src/main/suko", "com.acme.web",
                new ProjectConfig.Registry(REAL_REGISTRY_BASE.toAbsolutePath().normalize().toString(), "main"));
        config.write(projectDir);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);

        Args args = Args.parse(new String[] { "list" });
        new ListCommand().run(args, out, projectDir);

        String output = captured.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("button"), "expected the registry read via suko.json to list \"button\":\n" + output);
    }

    @Test
    void missingRegistryConfigurationSuggestsInitOrFlag() {
        Args args = Args.parse(new String[] { "list" });

        CliException e = assertThrows(CliException.class,
                () -> new ListCommand().run(args, System.out, Path.of(".", "does-not-matter")));

        assertTrue(e.getMessage().contains("suko init"), "message: " + e.getMessage());
        assertTrue(e.getMessage().contains("--registry"), "message: " + e.getMessage());
    }

    private static void assumeRealRegistryExists() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(REAL_REGISTRY_BASE.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping real-registry test");
    }
}
