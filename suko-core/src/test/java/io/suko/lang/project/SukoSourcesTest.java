package io.suko.lang.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SukoSourcesTest {

    @Test
    void fromDirectoryReadsOnlySkFilesWithRelativePaths(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("ui"));
        Files.writeString(root.resolve("ui/A.sk"), "component A() {}");
        Files.writeString(root.resolve("B.sk"), "component B() {}");
        Files.writeString(root.resolve("notes.txt"), "ignored");

        SukoSources sources = SukoSources.fromDirectory(root);

        assertEquals(List.of(Path.of("B.sk"), Path.of("ui/A.sk")), List.copyOf(sources.files().keySet()));
        assertEquals("component A() {}", sources.files().get(Path.of("ui/A.sk")));
        assertEquals(root.resolve("ui/A.sk"), sources.absolute(Path.of("ui/A.sk")));
    }

    @Test
    void overlayReplacesAndAddsWithoutMutatingTheOriginal(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("A.sk"), "disk");
        SukoSources disk = SukoSources.fromDirectory(root);

        SukoSources overlaid = disk.withOverlay(Map.of(Path.of("A.sk"), "buffer", Path.of("New.sk"), "new"));

        assertEquals("disk", disk.files().get(Path.of("A.sk")));
        assertEquals(1, disk.files().size());
        assertEquals("buffer", overlaid.files().get(Path.of("A.sk")));
        assertEquals("new", overlaid.files().get(Path.of("New.sk")));
    }

    @Test
    void withoutRemovesAFileAndSnapshotIsImmutable(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("A.sk"), "a");
        SukoSources sources = SukoSources.fromDirectory(root);

        assertTrue(sources.without(Path.of("A.sk")).files().isEmpty());
        assertEquals(1, sources.files().size());
        assertThrows(UnsupportedOperationException.class, () -> sources.files().clear());
    }

    @Test
    void absoluteOrUnnormalisedPathsAreRejected() {
        Path root = Path.of("/root");
        assertThrows(IllegalArgumentException.class, () -> SukoSources.of(root, Map.of(Path.of("/root/A.sk"), "x")));
        assertThrows(IllegalArgumentException.class, () -> SukoSources.of(root, Map.of(Path.of("ui/../A.sk"), "x")));
        SukoSources ok = SukoSources.of(root, Map.of(Path.of("A.sk"), "x"));
        assertThrows(IllegalArgumentException.class, () -> ok.withOverlay(Map.of(Path.of("/root/B.sk"), "x")));
    }
}
