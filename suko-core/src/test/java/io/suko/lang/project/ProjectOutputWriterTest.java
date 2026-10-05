package io.suko.lang.project;

import io.suko.ext.ProjectOutput;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectOutputWriterTest {

    private static SukoProjectCompiler.ProjectCompileResult emptyResult() throws Exception {
        Path src = Files.createTempDirectory("pow");
        Files.writeString(src.resolve("A.sk"), "component A() { <p>x</p> }");
        return new SukoProjectCompiler(io.suko.lang.ext.ExtensionRegistry.load(
            ProjectOutputWriterTest.class.getClassLoader()), List.of("jte")).compile(src);
    }

    private static void run(Path dir, List<String> lines) throws Exception {
        Path manifest = dir.resolve("state/java-outputs.txt");
        Files.createDirectories(manifest.getParent());
        Files.write(manifest, lines);
        ProjectOutputWriter.write(emptyResult(), dir.resolve("out"), dir.resolve("java"), manifest);
    }

    @Test
    void lineEqualToRootDeletesNothing(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("java"));
        run(dir, List.of(root.toAbsolutePath().toString()));
        assertTrue(Files.isDirectory(root));
    }

    @Test
    void traversalRelativeAndOutsideLinesAreIgnored(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("java"));
        Path victim = Files.writeString(dir.resolve("victim.txt"), "x");
        Path rel = Files.writeString(dir.resolve("rel.txt"), "x");
        run(dir, List.of(root.toAbsolutePath() + "/../victim.txt", "../rel.txt", "java/../rel.txt",
            victim.toAbsolutePath().toString()));
        assertTrue(Files.exists(victim));
        assertTrue(Files.exists(rel));
    }

    @Test
    void symlinkedDirectoryEscapeIsSkipped(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("java"));
        Path outside = Files.createDirectories(dir.resolve("outside"));
        Path victim = Files.writeString(outside.resolve("v.txt"), "x");
        try {
            Files.createSymbolicLink(root.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks not supported");
        }
        run(dir, List.of(root.toAbsolutePath() + "/link/v.txt"));
        assertTrue(Files.exists(victim));
    }

    @Test
    void nonEmptyDirectoryLineDoesNotThrow(@TempDir Path dir) throws Exception {
        Path d = Files.createDirectories(dir.resolve("java/pkg"));
        Files.writeString(d.resolve("f.txt"), "x");
        run(dir, List.of(d.toAbsolutePath().toString()));
        assertTrue(Files.exists(d.resolve("f.txt")));
    }

    @Test
    void staleFileRemovedAndEmptyParentsPrunedButNotUserDirs(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("java"));
        Path stale = Files.createDirectories(root.resolve("old/pkg")).resolve("Stale.java");
        Files.writeString(stale, "x");
        Path hand = Files.createDirectories(root.resolve("hand")).resolve("Mine.java");
        Files.writeString(hand, "x");
        Path userEmpty = Files.createDirectories(root.resolve("userEmpty"));
        run(dir, List.of(stale.toAbsolutePath().toString()));
        assertFalse(Files.exists(stale));
        assertFalse(Files.exists(root.resolve("old")));
        assertTrue(Files.exists(hand));
        assertTrue(Files.isDirectory(userEmpty));
        assertTrue(Files.isDirectory(root));
    }
}
