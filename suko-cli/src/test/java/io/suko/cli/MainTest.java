package io.suko.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link Main#run} (the testable, non-{@code System.exit} entry
 * point) end-to-end: unknown command produces help text on stderr and a
 * non-zero exit code, exactly as the brief for Task 7 requires.
 */
class MainTest {

    @Test
    void unknownCommandPrintsHelpToStderrAndReturnsNonZero(@TempDir Path projectDir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "frobnicate" }, emptyStdin(), printStream(new ByteArrayOutputStream()),
                printStream(err), projectDir);

        assertNotEquals(0, exitCode);
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("frobnicate"), "stderr: " + errText);
        assertTrue(errText.contains("Usage: suko"), "stderr: " + errText);
    }

    @Test
    void topLevelHelpFlagPrintsUsageToStdoutAndReturnsZero() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "--help" }, emptyStdin(), printStream(out),
                printStream(new ByteArrayOutputStream()), Path.of("."));

        assertEquals(0, exitCode);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: suko"));
    }

    /**
     * Task 12: {@code diff} and {@code update} are now real commands, not
     * stubs — run against an empty project (no {@code suko.lock.json} at
     * all) they must each surface a clean, readable {@link CliException}
     * message (never a stack trace) with a non-zero exit code, exactly
     * like every other command that hits a precondition it cannot satisfy.
     */
    @Test
    void diffAndUpdateOnAnEmptyProjectReturnNonZeroWithAClearMessage(@TempDir Path projectDir) {
        for (String command : new String[] { "diff", "update" }) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = Main.run(new String[] { command }, emptyStdin(), printStream(new ByteArrayOutputStream()),
                    printStream(err), projectDir);

            assertNotEquals(0, exitCode, command + " should not silently succeed");
            String errText = err.toString(StandardCharsets.UTF_8);
            assertTrue(errText.contains(Lockfile.FILE_NAME), "stderr for " + command + ": " + errText);
            assertFalse(errText.contains("Exception"), "should not leak a stack trace: " + errText);
        }
    }

    @Test
    void listDispatchesToListCommandAndSurfacesItsCliExceptionCleanly(@TempDir Path projectDir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "list" }, emptyStdin(), printStream(new ByteArrayOutputStream()),
                printStream(err), projectDir);

        assertNotEquals(0, exitCode);
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("suko init"), "stderr: " + errText);
        assertFalse(errText.contains("Exception"), "should not leak a stack trace: " + errText);
    }

    @Test
    void addDispatchesToAddCommandAndSurfacesItsCliExceptionCleanly(@TempDir Path projectDir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "add", "button" }, emptyStdin(), printStream(new ByteArrayOutputStream()),
                printStream(err), projectDir);

        assertNotEquals(0, exitCode);
        String errText = err.toString(StandardCharsets.UTF_8);
        assertTrue(errText.contains("suko init"), "stderr: " + errText);
        assertFalse(errText.contains("Exception"), "should not leak a stack trace: " + errText);
    }

    @Test
    void initDispatchesEndToEnd(@TempDir Path projectDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "init", "--yes", "--base-package", "com.acme.web" },
                emptyStdin(), printStream(out), printStream(new ByteArrayOutputStream()), projectDir);

        assertEquals(0, exitCode);
        assertTrue(java.nio.file.Files.exists(projectDir.resolve("suko.json")));
    }

    /**
     * Fix round 1, Achado 2: {@code suko init --help} must show help and
     * return without running the interactive flow at all — in particular,
     * it must never touch stdin (an empty stdin here would otherwise make
     * a real run of InitCommand hang reading prompts, or silently accept
     * every default and write a bogus suko.json) and must never write
     * suko.json.
     */
    @Test
    void initHelpShowsHelpAndDoesNotRunTheInteractiveFlow(@TempDir Path projectDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "init", "--help" }, emptyStdin(), printStream(out),
                printStream(new ByteArrayOutputStream()), projectDir);

        assertEquals(0, exitCode);
        String helpText = out.toString(StandardCharsets.UTF_8);
        assertTrue(helpText.contains("Usage: suko init"), "stdout: " + helpText);
        assertTrue(helpText.contains("--base-package"), "stdout: " + helpText);
        assertFalse(java.nio.file.Files.exists(projectDir.resolve("suko.json")),
                "suko init --help must not write suko.json");
    }

    /**
     * Fix round 1, Achado 2: {@code suko list --help} must show help and
     * return without attempting to read any registry (no --registry flag
     * and no suko.json are given here — a real run of ListCommand would
     * throw a CliException about a missing registry; this test would fail
     * with that exception's message on stderr, not the expected help on
     * stdout, if the fix regressed).
     */
    @Test
    void listHelpShowsHelpAndDoesNotRunTheRealCommand(@TempDir Path projectDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "list", "--help" }, emptyStdin(), printStream(out),
                printStream(err), projectDir);

        assertEquals(0, exitCode);
        String helpText = out.toString(StandardCharsets.UTF_8);
        assertTrue(helpText.contains("Usage: suko list"), "stdout: " + helpText);
        assertEquals("", err.toString(StandardCharsets.UTF_8), "must not attempt the real command and fail");
    }

    @Test
    void helpIsAvailablePerCommandForEveryCommand(@TempDir Path projectDir) {
        for (String command : Args.COMMANDS) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int exitCode = Main.run(new String[] { command, "--help" }, emptyStdin(), printStream(out),
                    printStream(new ByteArrayOutputStream()), projectDir);

            assertEquals(0, exitCode, command + " --help should exit 0");
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("Usage: suko " + command),
                    command + " --help stdout: " + out.toString(StandardCharsets.UTF_8));
        }
    }

    private static InputStream emptyStdin() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }
}
