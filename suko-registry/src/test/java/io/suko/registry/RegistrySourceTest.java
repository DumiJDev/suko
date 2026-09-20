package io.suko.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RegistrySourceTest {

    @Test
    void resolvesRelativePathUnderBase(@TempDir Path base) throws IOException {
        Path componentsDir = base.resolve("components");
        Files.createDirectories(componentsDir);
        Files.writeString(componentsDir.resolve("button.json"), "{\"name\":\"button\"}", StandardCharsets.UTF_8);

        RegistrySource source = new FileSystemRegistrySource(base);

        byte[] content = source.resolve("components/button.json");

        assertEquals("{\"name\":\"button\"}", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void baseReturnsConfiguredBase(@TempDir Path base) {
        RegistrySource source = new FileSystemRegistrySource(base);
        assertEquals(base.toString(), source.base());
    }

    @Test
    void rejectsAbsolutePath(@TempDir Path base) {
        RegistrySource source = new FileSystemRegistrySource(base);

        assertThrows(IllegalArgumentException.class, () -> source.resolve("/etc/passwd"));
    }

    @Test
    void rejectsAbsoluteWindowsStylePath(@TempDir Path base) {
        RegistrySource source = new FileSystemRegistrySource(base);

        assertThrows(IllegalArgumentException.class, () -> source.resolve("C:/secrets/file.json"));
    }

    @Test
    void rejectsPathTraversalEscapingBase(@TempDir Path base) throws IOException {
        Path secret = base.getParent().resolve("secret-outside-base.txt");
        Files.writeString(secret, "top secret", StandardCharsets.UTF_8);

        RegistrySource source = new FileSystemRegistrySource(base);

        assertThrows(IllegalArgumentException.class,
                () -> source.resolve("../secret-outside-base.txt"));

        Files.deleteIfExists(secret);
    }

    @Test
    void allowsDotDotThatStaysInsideBase(@TempDir Path base) throws IOException {
        Path componentsDir = base.resolve("components");
        Files.createDirectories(componentsDir);
        Files.writeString(componentsDir.resolve("button.json"), "content", StandardCharsets.UTF_8);

        RegistrySource source = new FileSystemRegistrySource(base);

        byte[] content = source.resolve("components/../components/button.json");

        assertEquals("content", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void resolveThrowsIOExceptionForMissingFile(@TempDir Path base) {
        RegistrySource source = new FileSystemRegistrySource(base);

        assertThrows(IOException.class, () -> source.resolve("does-not-exist.json"));
    }
}
