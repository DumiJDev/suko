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

    @Test
    void notYetImplementedCommandsReturnNonZeroWithAClearMessage(@TempDir Path projectDir) {
        for (String command : new String[] { "add", "diff", "update" }) {
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            int exitCode = Main.run(new String[] { command }, emptyStdin(), printStream(new ByteArrayOutputStream()),
                    printStream(err), projectDir);

            assertNotEquals(0, exitCode, command + " should not silently succeed");
            assertTrue(err.toString(StandardCharsets.UTF_8).contains(command), "stderr for " + command);
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
    void initDispatchesEndToEnd(@TempDir Path projectDir) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = Main.run(new String[] { "init", "--yes", "--base-package", "com.acme.web" },
                emptyStdin(), printStream(out), printStream(new ByteArrayOutputStream()), projectDir);

        assertEquals(0, exitCode);
        assertTrue(java.nio.file.Files.exists(projectDir.resolve("suko.json")));
    }

    private static InputStream emptyStdin() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }
}
