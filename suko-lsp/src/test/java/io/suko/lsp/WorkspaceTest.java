package io.suko.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceTest {

    private static Path dir(Path base, String rel) throws IOException {
        return Files.createDirectories(base.resolve(rel));
    }

    @Test
    void sukoJsonSourceRootWinsOverTheConvention(@TempDir Path folder) throws IOException {
        dir(folder, "src/main/suko");
        Path custom = dir(folder, "views");
        Files.writeString(folder.resolve("suko.json"), "{\"schemaVersion\":1,\"sourceRoot\":\"views\"}");

        assertEquals(Optional.of(custom), ProjectLocator.locate(folder, ""));
    }

    @Test
    void fallsBackToTheConventionThenToTheSetting(@TempDir Path folder) throws IOException {
        Path setting = dir(folder, "ui");
        assertEquals(Optional.of(setting), ProjectLocator.locate(folder, "ui"));

        Path conventional = dir(folder, "src/main/suko");
        assertEquals(Optional.of(conventional), ProjectLocator.locate(folder, "ui"));
    }

    @Test
    void brokenOrDanglingSukoJsonNeverThrows(@TempDir Path folder) throws IOException {
        Path conventional = dir(folder, "src/main/suko");
        Files.writeString(folder.resolve("suko.json"), "{ not json");
        assertEquals(Optional.of(conventional), ProjectLocator.locate(folder, ""));

        Files.writeString(folder.resolve("suko.json"), "{\"sourceRoot\":\"missing\"}");
        assertEquals(Optional.of(conventional), ProjectLocator.locate(folder, ""));

        Files.writeString(folder.resolve("suko.json"), "[1]");
        assertEquals(Optional.of(conventional), ProjectLocator.locate(folder, ""));
    }

    @Test
    void folderWithoutASourceRootHasNoProject(@TempDir Path folder) {
        assertTrue(ProjectLocator.locate(folder, "").isEmpty());
    }

    @Test
    void oneProjectPerRootAndFilesGoToTheirOwnRoot(@TempDir Path base) throws IOException {
        Path a = dir(base, "a/src/main/suko");
        Path b = dir(base, "b/src/main/suko");
        Workspace workspace = new Workspace();
        workspace.configure(List.of(base.resolve("a"), base.resolve("b")), "");

        assertEquals(2, workspace.projects().size());
        assertEquals(a, workspace.projectFor(a.resolve("X.sk")).orElseThrow().root());
        assertEquals(b, workspace.projectFor(b.resolve("sub/Y.sk")).orElseThrow().root());
        assertTrue(workspace.projectFor(base.resolve("elsewhere/Z.sk")).isEmpty());
        assertTrue(workspace.projectFor(a.resolve("notes.txt")).isEmpty());
    }

    @Test
    void rediscoveryKeepsOpenBuffersOfProjectsThatStay(@TempDir Path folder) throws IOException {
        Path root = dir(folder, "src/main/suko");
        Workspace workspace = new Workspace();
        workspace.configure(List.of(folder), "");
        Project project = workspace.projectFor(root.resolve("A.sk")).orElseThrow();
        project.put(Path.of("A.sk"), "component A() {}");

        workspace.rediscover();

        assertSame(project, workspace.projectFor(root.resolve("A.sk")).orElseThrow());
        assertTrue(project.isOpen(Path.of("A.sk")));
    }

    @Test
    void openBuffersOverlayTheDiskWithoutTouchingIt(@TempDir Path folder) throws IOException {
        Path root = dir(folder, "src/main/suko");
        Files.writeString(root.resolve("A.sk"), "public component A() { <p>disk</p> }\n");
        Project project = new Project(root);

        assertEquals("public component A() { <p>disk</p> }\n", project.sources().files().get(Path.of("A.sk")));

        project.put(Path.of("A.sk"), "buffer");
        project.put(Path.of("Unsaved.sk"), "new");
        assertEquals("buffer", project.sources().files().get(Path.of("A.sk")));
        assertEquals("new", project.sources().files().get(Path.of("Unsaved.sk")));
        assertEquals("public component A() { <p>disk</p> }\n", Files.readString(root.resolve("A.sk")));

        project.close(Path.of("A.sk"));
        project.close(Path.of("Unsaved.sk"));
        assertEquals("public component A() { <p>disk</p> }\n", project.sources().files().get(Path.of("A.sk")));
        assertFalse(project.sources().files().containsKey(Path.of("Unsaved.sk")));
    }

    @Test
    void diskChangesAreSeenAfterDiskChangedAndAnalysisIsCached(@TempDir Path folder) throws IOException {
        Path root = dir(folder, "src/main/suko");
        Files.writeString(root.resolve("A.sk"), "component A() { <p>1</p> }\n");
        Project project = new Project(root);

        var first = project.analysis();
        assertSame(first, project.analysis(), "sem alterações, a verificação não se repete");

        Files.writeString(root.resolve("B.sk"), "component B() { <p>2</p> }\n");
        assertEquals(1, project.analysis().files().size(), "o disco só é relido quando avisado");

        project.diskChanged();
        assertEquals(2, project.analysis().files().size());
        assertNotSame(first, project.analysis());
    }

    @Test
    void uriToPathAndBack(@TempDir Path folder) {
        Path file = folder.resolve("with space/A.sk").toAbsolutePath().normalize();
        assertEquals(file, Workspace.pathOf(file.toUri().toString()));
    }
}
