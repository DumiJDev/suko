package io.suko.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ArgsTest {

    @Test
    void parsesKnownCommandWithNoFlags() {
        Args args = Args.parse(new String[] { "list" });

        assertEquals("list", args.command());
        assertTrue(args.positionals().isEmpty());
        assertFalse(args.help());
    }

    @Test
    void unknownCommandThrowsWithHelpAndNamesTheCommand() {
        CliException e = assertThrows(CliException.class, () -> Args.parse(new String[] { "frobnicate" }));

        assertTrue(e.getMessage().contains("frobnicate"), "message should name the offending command: " + e.getMessage());
        assertTrue(e.getMessage().contains("Usage: suko"), "message should include usage/help: " + e.getMessage());
        assertNotEquals(0, e.exitCode(), "an unknown command must be a non-zero exit code");
    }

    @Test
    void noCommandAtAllThrowsWithUsage() {
        CliException e = assertThrows(CliException.class, () -> Args.parse(new String[0]));

        assertTrue(e.getMessage().contains("Usage: suko"));
        assertNotEquals(0, e.exitCode());
    }

    @Test
    void noCommandButHelpFlagReturnsHelpArgsInsteadOfThrowing() {
        Args args = Args.parse(new String[] { "--help" });

        assertNull(args.command());
        assertTrue(args.help());
    }

    @Test
    void globalFlagsAreRecognizedBeforeTheCommand() {
        Args args = Args.parse(new String[] { "--registry", "/tmp/registry", "list" });

        assertEquals("list", args.command());
        assertEquals("/tmp/registry", args.registryBase());
    }

    @Test
    void globalFlagsAreRecognizedAfterTheCommand() {
        Args args = Args.parse(new String[] { "list", "--registry", "/tmp/registry" });

        assertEquals("list", args.command());
        assertEquals("/tmp/registry", args.registryBase());
    }

    @Test
    void globalFlagsAreRecognizedInterleavedWithPositionalArguments() {
        Args args = Args.parse(new String[] { "add", "button", "--base-package", "com.acme.web", "field" });

        assertEquals("add", args.command());
        assertEquals("com.acme.web", args.basePackage());
        assertEquals(java.util.List.of("button", "field"), args.positionals());
    }

    @Test
    void allGlobalFlagsAreParsed() {
        Args args = Args.parse(new String[] {
                "add", "button",
                "--registry", "/some/registry",
                "--registry-ref", "v0.2.0",
                "--source-root", "src/main/suko",
                "--base-package", "com.acme.web",
                "--force",
                "--dry-run",
                "--yes"
        });

        assertEquals("/some/registry", args.registryBase());
        assertEquals("v0.2.0", args.registryRef());
        assertEquals("src/main/suko", args.sourceRoot());
        assertEquals("com.acme.web", args.basePackage());
        assertTrue(args.force());
        assertTrue(args.dryRun());
        assertTrue(args.yes());
    }

    @Test
    void flagMissingItsValueAtEndOfArgsProducesReadableMessage() {
        CliException e = assertThrows(CliException.class, () -> Args.parse(new String[] { "list", "--registry" }));

        assertTrue(e.getMessage().contains("--registry"), "message should name the flag: " + e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("requires a value"), "message should say a value is required: " + e.getMessage());
    }

    @Test
    void valueFlagFollowedByAnotherFlagTokenConsumesItAsTheValueRatherThanErroring() {
        // By design, the token right after a value-flag is always consumed
        // as its value, even if it looks like another flag: rejecting it
        // outright would be a worse failure mode than the (rare) surprise
        // of a ref/path that happens to start with "--". We assert the
        // actual, deliberate behavior here so a change to it is conscious.
        Args args = Args.parse(new String[] { "list", "--registry-ref", "--yes" });

        assertEquals("--yes", args.registryRef());
        assertFalse(args.yes());
    }

    @Test
    void unknownFlagProducesReadableMessage() {
        CliException e = assertThrows(CliException.class, () -> Args.parse(new String[] { "list", "--bogus" }));

        assertTrue(e.getMessage().contains("--bogus"), "message should name the offending flag: " + e.getMessage());
    }

    @Test
    void helpFlagIsRecognizedAlongsideACommand() {
        Args args = Args.parse(new String[] { "add", "--help" });

        assertEquals("add", args.command());
        assertTrue(args.help());
    }
}
