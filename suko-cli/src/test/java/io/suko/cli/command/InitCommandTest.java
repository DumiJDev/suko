package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.ProjectConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Not explicitly listed in the Task 7 brief's file list, but {@code suko
 * init} is the command a first-time user runs before anything else works —
 * exercising it (interactive prompts, --yes, and the mandatory basePackage
 * question) is squarely within "implementar suko init", not scope creep.
 */
class InitCommandTest {

    @Test
    void interactivePromptsShowVisibleDefaultsAndAcceptBlankLinesAsThem(@TempDir Path projectDir) {
        // Blank line for source root and registry ref/base (accept
        // defaults); only basePackage is actually typed, since it has no
        // default to accept.
        InputStream stdin = lines("", "com.acme.web", "", "");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init" });
        new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("[src/main/suko]"), "default source root should be shown: " + output);
        assertTrue(output.contains("Base package"), "should prompt for basePackage: " + output);
        assertFalse(output.matches("(?s).*Base package \\[.*\\].*"), "basePackage prompt must show no default: " + output);

        ProjectConfig written = ProjectConfig.load(projectDir).orElseThrow();
        assertEquals("src/main/suko", written.sourceRoot());
        assertEquals("com.acme.web", written.basePackage());
    }

    @Test
    void typingAValueOverridesTheDefault(@TempDir Path projectDir) {
        InputStream stdin = lines("custom/root", "com.acme.web", "v9.9.9", "https://example.test/registry/");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init" });
        new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir);

        ProjectConfig written = ProjectConfig.load(projectDir).orElseThrow();
        assertEquals("custom/root", written.sourceRoot());
        assertEquals("v9.9.9", written.registry().ref());
        assertEquals("https://example.test/registry/", written.registry().base());
    }

    @Test
    void yesAcceptsDefaultsWithoutPromptingWhenBasePackageFlagGiven(@TempDir Path projectDir) {
        InputStream stdin = lines(); // nothing to read; must not block on input
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init", "--yes", "--base-package", "com.acme.web" });
        new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir);

        ProjectConfig written = ProjectConfig.load(projectDir).orElseThrow();
        assertEquals(ProjectConfig.DEFAULT_SOURCE_ROOT, written.sourceRoot());
        assertEquals("com.acme.web", written.basePackage());
    }

    @Test
    void yesFailsWhenBasePackageIsMissingBecauseItCannotBeGuessed(@TempDir Path projectDir) {
        InputStream stdin = lines();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init", "--yes" });

        CliException e = assertThrows(CliException.class,
                () -> new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir));

        assertTrue(e.getMessage().contains("--base-package"), "message: " + e.getMessage());
        assertFalse(Files.exists(projectDir.resolve("suko.json")), "must not write a partial suko.json on failure");
    }

    @Test
    void existingSukoJsonIsNotOverwrittenWithoutForce(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("suko.json"), "{}", StandardCharsets.UTF_8);
        InputStream stdin = lines();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init", "--yes", "--base-package", "com.acme.web" });

        CliException e = assertThrows(CliException.class,
                () -> new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir));

        assertTrue(e.getMessage().contains("--force"), "message: " + e.getMessage());
        assertEquals("{}", Files.readString(projectDir.resolve("suko.json"), StandardCharsets.UTF_8));
    }

    @Test
    void forceOverwritesExistingSukoJson(@TempDir Path projectDir) throws Exception {
        Files.writeString(projectDir.resolve("suko.json"), "{}", StandardCharsets.UTF_8);
        InputStream stdin = lines();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init", "--yes", "--force", "--base-package", "com.acme.web" });
        new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir);

        ProjectConfig written = ProjectConfig.load(projectDir).orElseThrow();
        assertEquals("com.acme.web", written.basePackage());
    }

    @Test
    void invalidBasePackageTypedInteractivelyIsRejected(@TempDir Path projectDir) {
        InputStream stdin = lines("", "com.class.web");
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        Args args = Args.parse(new String[] { "init" });

        CliException e = assertThrows(CliException.class,
                () -> new InitCommand().run(args, stdin, printStream(stdout), printStream(stdout), projectDir));

        assertTrue(e.getMessage().contains("\"class\""), "message: " + e.getMessage());
    }

    private static InputStream lines(String... lines) {
        String joined = String.join("\n", lines) + "\n";
        return new ByteArrayInputStream(joined.getBytes(StandardCharsets.UTF_8));
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }
}
