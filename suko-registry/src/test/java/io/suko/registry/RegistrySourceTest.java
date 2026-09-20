package io.suko.registry;

import org.junit.jupiter.api.Assumptions;
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

    /**
     * {@code Path.normalize()} is purely lexical and does not follow
     * symlinks. A symlink that lives inside the base but points outside of
     * it must still be rejected, otherwise the containment guarantee in
     * {@link RegistrySource#resolve(String)}'s Javadoc is false.
     * <p>
     * Symlink creation can fail for reasons unrelated to this test (missing
     * OS privilege, e.g. on some Windows CI runners without the
     * "create symbolic links" right). In that case the test is explicitly
     * aborted with {@link Assumptions#abort(String)} — visible as "skipped"
     * with a reason in the report — rather than silently treated as a pass.
     * </p>
     */
    @Test
    void rejectsSymlinkEscapingBase(@TempDir Path base) throws IOException {
        Path outsideDir = Files.createTempDirectory("suko-registry-outside-");
        Path secret = outsideDir.resolve("secret.txt");
        Files.writeString(secret, "top secret via symlink", StandardCharsets.UTF_8);

        Path componentsDir = base.resolve("components");
        Files.createDirectories(componentsDir);
        Path link = componentsDir.resolve("evil-link.txt");

        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("Symbolic links are not supported/permitted in this environment: " + e);
            return;
        }

        try {
            RegistrySource source = new FileSystemRegistrySource(base);

            assertThrows(IllegalArgumentException.class,
                    () -> source.resolve("components/evil-link.txt"));
        } finally {
            Files.deleteIfExists(link);
            Files.deleteIfExists(secret);
            Files.deleteIfExists(outsideDir);
        }
    }
}
