package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code suko diff} (Task 12 of the subprojeto 8 plan).
 * <p>
 * The central, binding rule under test throughout this file: {@code suko
 * diff} always compares the local file against the upstream source
 * <strong>after</strong> namespace rewriting, never the raw upstream — a
 * freshly-installed, untouched file must diff as identical even though its
 * raw upstream source (still under {@code io.suko}) differs from the local
 * file (under whatever {@code basePackage} the project uses) on every
 * {@code package}/{@code import} line.
 * </p>
 */
class DiffCommandTest {

    private static final Path REAL_REGISTRY = Path.of("..", "suko-components");

    private Path realRegistryOrSkip() {
        Assumptions.assumeTrue(Files.isRegularFile(REAL_REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");
        return REAL_REGISTRY;
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private void install(Path projectDir, Path registry, String... names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Args args = Args.parse(prepend(new String[] { "add" }, names, "--registry", registry.toString(),
                "--base-package", "com.acme.web"));
        new AddCommand().run(args, printStream(out), projectDir);
    }

    private static String[] prepend(String[] head, String[] names, String... tail) {
        java.util.List<String> all = new java.util.ArrayList<>();
        all.addAll(java.util.List.of(head));
        all.addAll(java.util.List.of(names));
        all.addAll(java.util.List.of(tail));
        return all.toArray(new String[0]);
    }

    private int runDiff(Path projectDir, ByteArrayOutputStream out, String... argv) {
        Args args = Args.parse(argv);
        PrintStream stream = printStream(out);
        try {
            return new DiffCommand().run(args, stream, projectDir);
        } catch (CliException e) {
            stream.println("ERROR: " + e.getMessage());
            return e.exitCode();
        }
    }

    @Test
    void unmodifiedInstalledFileDiffsEmptyWithExitCodeZero(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        install(projectDir, registry, "label");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runDiff(projectDir, out, "diff", "label", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        assertEquals("", out.toString(StandardCharsets.UTF_8), "an unmodified file must diff completely empty");
    }

    @Test
    void modifiedLocalFileDiffsAgainstTheRewrittenUpstreamNotTheRawOne(@TempDir Path projectDir) throws IOException {
        Path registry = realRegistryOrSkip();
        install(projectDir, registry, "label");

        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String original = Files.readString(labelPath);
        Files.writeString(labelPath, original + "\n<div>hand-edited</div>\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runDiff(projectDir, out, "diff", "label", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("+<div>hand-edited</div>") || output.contains("-<div>hand-edited</div>"),
                "diff should show the edited line: " + output);
        // The C2-shaped failure mode this test exists to catch: comparing
        // against the RAW upstream (still under io.suko) would make every
        // package/import line show up as a spurious diff too.
        assertFalse(output.contains("-package io.suko.ui;") || output.contains("+package io.suko.ui;"),
                "must never compare against the raw (pre-rewrite) upstream: " + output);
        assertFalse(output.contains("-package com.acme.web.ui;"),
                "the unchanged package line must not appear as removed: " + output);
    }

    @Test
    void diffOfAComponentNotInstalledProducesAReadableMessage(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        install(projectDir, registry, "label");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runDiff(projectDir, out, "diff", "button", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("button"), "message should name the requested component: " + output);
        assertTrue(output.toLowerCase().contains("not installed") || output.contains("suko add"),
                "message should be readable/actionable: " + output);
    }

    @Test
    void diffWithNoLockfileAtAllProducesAReadableMessage(@TempDir Path projectDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runDiff(projectDir, out, "diff");

        assertNotEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("suko.lock.json"));
    }

    @Test
    void diffWithNoArgumentCoversEveryInstalledComponent(@TempDir Path projectDir) throws IOException {
        Path registry = realRegistryOrSkip();
        install(projectDir, registry, "field");

        Path inputPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Input.sk");
        Files.writeString(inputPath, Files.readString(inputPath) + "\n<div>hand-edited</div>\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runDiff(projectDir, out, "diff", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Input.sk"), "diff should mention the modified file: " + output);
    }
}
