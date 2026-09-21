package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.Lockfile;
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
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end tests for {@code suko add} (Task 11 of the subprojeto 8 plan):
 * the full 8-step pipeline (config, resolve, fetch+verify, rewrite,
 * reconcile, write, lockfile, external requirements), exercised against a
 * real (test-local, mutable) copy of the {@code suko-components} registry
 * so that fetch failures and hash mismatches can be simulated by editing
 * files on disk, rather than by inventing a fake {@link
 * io.suko.registry.RegistrySource}.
 */
class AddCommandTest {

    private static final Path REAL_REGISTRY = Path.of("..", "suko-components");

    private Path realRegistryOrSkip() {
        Assumptions.assumeTrue(Files.isRegularFile(REAL_REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");
        return REAL_REGISTRY;
    }

    /** Copies the real registry into a mutable temp directory so a test can tamper with it. */
    private Path copyOfRealRegistry(Path tempDir) {
        Path source = realRegistryOrSkip();
        Path target = tempDir.resolve("registry-copy");
        try {
            Files.walk(source).forEach(p -> {
                try {
                    Path relative = source.relativize(p);
                    Path destination = target.resolve(relative.toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(p, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    private int run(Path projectDir, PrintStream out, String... argv) {
        Args args = Args.parse(argv);
        try {
            new AddCommand().run(args, out, projectDir);
            return 0;
        } catch (CliException e) {
            out.println("ERROR: " + e.getMessage());
            return e.exitCode();
        }
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private static long countRegularFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try {
            return Files.walk(dir).filter(Files::isRegularFile).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void addLabelWritesRewrittenFileAndCreatesLockfile(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = run(projectDir, printStream(out), "add", "label",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));

        Path written = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        assertTrue(Files.isRegularFile(written), "expected " + written + " to exist");
        String content = readString(written);
        assertTrue(content.contains("package com.acme.web.ui;"), "package should be rewritten: " + content);
        assertFalse(content.contains("io.suko"), "old base package must not remain: " + content);

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow(() -> new AssertionError("suko.lock.json not written"));
        assertEquals(1, lockfile.components().size());
        assertEquals("label", lockfile.components().get(0).name());
        assertEquals("direct", lockfile.components().get(0).reason());
    }

    @Test
    void addFieldWritesAllThreeFilesWithCorrectReasons(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = run(projectDir, printStream(out), "add", "field",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));

        assertTrue(Files.isRegularFile(projectDir.resolve("src/main/suko/com/acme/web/ui/Field.sk")));
        assertTrue(Files.isRegularFile(projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk")));
        assertTrue(Files.isRegularFile(projectDir.resolve("src/main/suko/com/acme/web/ui/Input.sk")));

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow();
        assertEquals(3, lockfile.components().size());
        assertEquals("direct", reasonOf(lockfile, "field"));
        assertEquals("transitive", reasonOf(lockfile, "label"));
        assertEquals("transitive", reasonOf(lockfile, "input"));
    }

    @Test
    void aFailureToFetchOneComponentsFileLeavesNothingOnDiskAndNoLockfile(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        // "field" resolves to [input, label, field] in topological order;
        // deleting field's own source file simulates its content failing
        // to be fetched (a 404), the third component in that order.
        Path fieldSource = registry.resolve("src/main/suko/io/suko/ui/Field.sk");
        assertTrue(Files.isRegularFile(fieldSource));
        try {
            Files.delete(fieldSource);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = run(projectDir, printStream(out), "add", "field",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        assertFalse(Files.exists(projectDir.resolve(Lockfile.FILE_NAME)), "lockfile must not be written");
        Path srcRoot = projectDir.resolve("src/main/suko");
        assertEquals(0, countRegularFiles(srcRoot), "no file must have been written: " + out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void hashMismatchAgainstManifestAbortsEverythingAndNamesTheFile(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        Path fieldSource = registry.resolve("src/main/suko/io/suko/ui/Field.sk");
        try {
            Files.writeString(fieldSource, Files.readString(fieldSource) + "\n// tampered\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = run(projectDir, printStream(out), "add", "field",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Field.sk"), "message should name the offending file: " + output);
        assertFalse(Files.exists(projectDir.resolve(Lockfile.FILE_NAME)), "lockfile must not be written");
        assertEquals(0, countRegularFiles(projectDir.resolve("src/main/suko")), "no file must have been written");
    }

    @Test
    void unforcedConflictWithAnExistingEditedFileAbortsBeforeWritingOthers(@TempDir Path projectDir) throws IOException {
        Path registry = realRegistryOrSkip();

        // Label.sk already exists at the destination, "edited" (never
        // installed by the CLI: no suko.lock.json entry for it at all).
        Path labelDestination = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        Files.createDirectories(labelDestination.getParent());
        Files.writeString(labelDestination, "package com.acme.web.ui;\n// hand-written, not installed by suko\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = run(projectDir, printStream(out), "add", "field",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        assertFalse(Files.exists(projectDir.resolve(Lockfile.FILE_NAME)), "lockfile must not be written");
        assertFalse(Files.exists(projectDir.resolve("src/main/suko/com/acme/web/ui/Field.sk")),
                "field must not be written when label conflicts");
        assertFalse(Files.exists(projectDir.resolve("src/main/suko/com/acme/web/ui/Input.sk")),
                "input must not be written when label conflicts");
        // Label.sk itself must be left exactly as the user wrote it.
        assertEquals("package com.acme.web.ui;\n// hand-written, not installed by suko\n",
                Files.readString(labelDestination));
    }

    @Test
    void dryRunPrintsThePlanAndWritesNothing(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = run(projectDir, printStream(out), "add", "label", "--dry-run",
                "--registry", registry.toString(), "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("label"), "plan should mention the component: " + output);
        assertFalse(Files.exists(projectDir.resolve(Lockfile.FILE_NAME)), "dry run must not write the lockfile");
        assertFalse(Files.exists(projectDir.resolve("src/main/suko")), "dry run must not create any file/folder");
    }

    @Test
    void secondAddOfTheSameComponentWithNoChangesIsANoOpAndDoesNotRewriteTheLockfile(@TempDir Path projectDir) {
        Path registry = realRegistryOrSkip();
        ByteArrayOutputStream out1 = new ByteArrayOutputStream();

        int firstExitCode = run(projectDir, printStream(out1), "add", "label",
                "--registry", registry.toString(), "--base-package", "com.acme.web");
        assertEquals(0, firstExitCode, out1.toString(StandardCharsets.UTF_8));

        String lockfileAfterFirstRun = readString(projectDir.resolve(Lockfile.FILE_NAME));

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        int secondExitCode = run(projectDir, printStream(out2), "add", "label",
                "--registry", registry.toString(), "--base-package", "com.acme.web");
        assertEquals(0, secondExitCode, out2.toString(StandardCharsets.UTF_8));

        String lockfileAfterSecondRun = readString(projectDir.resolve(Lockfile.FILE_NAME));

        assertEquals(lockfileAfterFirstRun, lockfileAfterSecondRun,
                "a no-op second `suko add` must not change suko.lock.json");
        assertTrue(out2.toString(StandardCharsets.UTF_8).contains("up to date"),
                "second run should report the file as up to date: " + out2.toString(StandardCharsets.UTF_8));
    }

    private static String reasonOf(Lockfile lockfile, String name) {
        return lockfile.components().stream()
                .filter(c -> c.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("component \"" + name + "\" not found in lockfile"))
                .reason();
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
