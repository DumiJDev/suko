package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.Hashes;
import io.suko.cli.LockEntry;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@code suko update} (Task 12 of the subprojeto 8 plan): the
 * five practical cases of the reconciliation matrix (spec D7) applied by a
 * command that never installs anything new, the direct/transitive drag for
 * a bare {@code suko update}, and the orphan report for a transitive that
 * is no longer required.
 */
class UpdateCommandTest {

    private static final Path REAL_REGISTRY = Path.of("..", "suko-components");

    private static final String LABEL_SOURCE = "src/main/suko/io/suko/ui/Label.sk";
    private static final String LABEL_MANIFEST = "components/label.json";

    private Path realRegistryOrSkip() {
        Assumptions.assumeTrue(Files.isRegularFile(REAL_REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");
        return REAL_REGISTRY;
    }

    /** Copies the real registry into a mutable temp directory so a test can simulate an upstream change. */
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

    /** Rewrites label's upstream source in {@code registry} and updates its manifest's sha256 to match. */
    private void tamperLabelUpstream(Path registry, String newContent) {
        try {
            Path sourceFile = registry.resolve(LABEL_SOURCE);
            String oldContent = Files.readString(sourceFile);
            String oldSha = Hashes.sha256OfRaw(oldContent.getBytes(StandardCharsets.UTF_8));

            byte[] newBytes = newContent.getBytes(StandardCharsets.UTF_8);
            Files.writeString(sourceFile, newContent);
            String newSha = Hashes.sha256OfRaw(newBytes);

            Path manifestFile = registry.resolve(LABEL_MANIFEST);
            String manifestJson = Files.readString(manifestFile);
            assertTrue(manifestJson.contains(oldSha), "manifest should still contain the old sha256 before tampering");
            Files.writeString(manifestFile, manifestJson.replace(oldSha, newSha));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }

    private void install(Path projectDir, Path registry, String... names) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<String> argv = new ArrayList<>(List.of("add"));
        argv.addAll(List.of(names));
        argv.addAll(List.of("--registry", registry.toString(), "--base-package", "com.acme.web"));
        Args args = Args.parse(argv.toArray(new String[0]));
        new AddCommand().run(args, printStream(out), projectDir);
    }

    private int runUpdate(Path projectDir, ByteArrayOutputStream out, String... argv) {
        Args args = Args.parse(argv);
        PrintStream stream = printStream(out);
        try {
            new UpdateCommand().run(args, stream, projectDir);
            return 0;
        } catch (CliException e) {
            stream.println("ERROR: " + e.getMessage());
            return e.exitCode();
        }
    }

    private static String readString(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static LockEntry.FileEntry labelFileEntry(Lockfile lockfile) {
        return lockfile.components().stream()
                .filter(c -> c.name().equals("label"))
                .findFirst().orElseThrow()
                .files().get(0);
    }

    // --- Case 1: disk matches, upstream unchanged -> NO_OP ---

    @Test
    void noOpWhenNothingChangedLeavesEverythingByteIdentical(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");
        String lockfileBefore = readString(projectDir.resolve(Lockfile.FILE_NAME));
        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String fileBefore = readString(labelPath);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        assertEquals(fileBefore, readString(labelPath), "an untouched file must not be rewritten");
        assertEquals(lockfileBefore, readString(projectDir.resolve(Lockfile.FILE_NAME)),
                "a no-op update must not change suko.lock.json");
    }

    // --- Case 2: disk matches what was installed, upstream changed -> OVERWRITE silently ---

    @Test
    void overwritesSilentlyWhenDiskUntouchedButUpstreamChanged(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");

        tamperLabelUpstream(registry, "package io.suko.ui;\n<div class=\"label-v2\">{{ text }}</div>\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String content = readString(labelPath);
        assertTrue(content.contains("label-v2"), "disk file should have been overwritten with the new upstream: " + content);
        assertTrue(content.contains("package com.acme.web.ui;"), "namespace should still be rewritten: " + content);

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow();
        LockEntry.FileEntry entry = labelFileEntry(lockfile);
        assertEquals(Hashes.sha256OfNormalized(content.getBytes(StandardCharsets.UTF_8)), entry.localSha256());
    }

    // --- Case 3: disk was edited locally, upstream unchanged -> left alone ---

    @Test
    void keepsLocalEditWhenUpstreamHasNotChanged(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");

        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String edited = readString(labelPath) + "\n<div>hand-edited</div>\n";
        try {
            Files.writeString(labelPath, edited);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        assertEquals(edited, readString(labelPath), "the local edit must be left exactly as the user wrote it");
    }

    // --- Case 4: disk was edited locally AND upstream changed -> CONFLICT ---

    @Test
    void conflictWithoutForceLeavesTheFileIntactAndReturnsNonZero(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");

        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String edited = readString(labelPath) + "\n<div>hand-edited</div>\n";
        try {
            Files.writeString(labelPath, edited);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        tamperLabelUpstream(registry, "package io.suko.ui;\n<div class=\"label-v2\">{{ text }}</div>\n");
        String lockfileBefore = readString(projectDir.resolve(Lockfile.FILE_NAME));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        assertEquals(edited, readString(labelPath), "a conflicting file must be left completely intact without --force");
        assertEquals(lockfileBefore, readString(projectDir.resolve(Lockfile.FILE_NAME)),
                "an aborted update must not touch suko.lock.json");
    }

    @Test
    void conflictWithForceOverwritesAndUpdatesBothHashes(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");

        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        try {
            Files.writeString(labelPath, readString(labelPath) + "\n<div>hand-edited</div>\n");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        tamperLabelUpstream(registry, "package io.suko.ui;\n<div class=\"label-v2\">{{ text }}</div>\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--force", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        String content = readString(labelPath);
        assertTrue(content.contains("label-v2"), "the --force overwrite must land the new upstream content: " + content);
        assertFalse(content.contains("hand-edited"), "the local edit must be gone after a --force overwrite: " + content);

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow();
        LockEntry.FileEntry entry = labelFileEntry(lockfile);
        assertEquals(Hashes.sha256OfNormalized(content.getBytes(StandardCharsets.UTF_8)), entry.localSha256());
        // O sha256 do Label.sk upstream ANTES do tamper acima. Tem de ser
        // atualizado sempre que o Label.sk da biblioteca mudar (mudou no
        // subprojeto 9, com a migração para `${expr}`): o valor sai de
        // suko-components/components/label.json. Sem isto o assertNotEquals
        // passa a ser trivialmente verdadeiro e deixa de provar que o
        // upstreamSha256 foi mesmo atualizado.
        assertNotEquals(entry.upstreamSha256(), "daf4d783950df338e2a97ab877e52fb1764a5b925e6dc8706c9f7ee3c87aaeb0",
                "upstreamSha256 must have been updated to the new manifest hash too");
    }

    // --- update with no argument: walks directs, drags still-required transitives, reports orphans ---

    @Test
    void bareUpdateWalksDirectsAndDragsStillRequiredTransitives(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "field"); // field -> depends on input, label (both transitive)

        tamperLabelUpstream(registry, "package io.suko.ui;\n<div class=\"label-v2\">{{ text }}</div>\n");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        Path labelPath = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        assertTrue(readString(labelPath).contains("label-v2"),
                "label, a transitive dependency of the direct component \"field\", must have been dragged along");

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow();
        assertEquals("transitive", lockfile.components().stream()
                .filter(c -> c.name().equals("label")).findFirst().orElseThrow().reason());
    }

    @Test
    void aTransitiveNoLongerRequiredIsReportedAsOrphanButNotDeleted(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "field"); // pulls in label, input as transitive

        // Simulate "label is no longer required": hand-edit the lockfile so
        // field no longer depends on it in the registry's manifest, leaving
        // label as a transitive entry that the fresh resolution will not
        // include. Simpler and just as faithful: remove label's dependsOn
        // edge from field's manifest in the registry copy.
        Path fieldManifest = registry.resolve("components/field.json");
        try {
            String json = Files.readString(fieldManifest);
            String withoutLabelDependency = json.replace("\"input\",\n    \"label\"", "\"input\"");
            assertTrue(json.contains("\"label\""), "field's manifest should list label under dependsOn before the edit");
            Files.writeString(fieldManifest, withoutLabelDependency);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Path labelPathBefore = projectDir.resolve("src/main/suko/com/acme/web/ui/Label.sk");
        String labelContentBefore = readString(labelPathBefore);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertEquals(0, exitCode, out.toString(StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(labelPathBefore), "an orphaned transitive's file must not be deleted");
        assertEquals(labelContentBefore, readString(labelPathBefore), "an orphaned transitive's file must not be touched");

        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("label"), "label should be reported as orphaned: " + output);
        assertTrue(output.toLowerCase().contains("orphan"), "the report should call it out as an orphan: " + output);

        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow();
        assertTrue(lockfile.components().stream().anyMatch(c -> c.name().equals("label")),
                "label must still be present in suko.lock.json — removing entries is out of scope");
    }

    // --- update with an explicit name that is not installed ---

    @Test
    void updateOfANonInstalledComponentProducesAReadableMessage(@TempDir Path projectDir) {
        Path registry = copyOfRealRegistry(projectDir);
        install(projectDir, registry, "label");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = runUpdate(projectDir, out, "update", "button", "--registry", registry.toString(),
                "--base-package", "com.acme.web");

        assertNotEquals(0, exitCode);
        String output = out.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("button"), "message should name the requested component: " + output);
        assertTrue(output.contains("suko add"), "message should point at suko add: " + output);
    }
}
