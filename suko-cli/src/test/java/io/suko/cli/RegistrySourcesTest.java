package io.suko.cli;

import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.HttpRegistrySource;
import io.suko.registry.RegistrySource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RegistrySourcesTest {

    @Test
    void resolvesHttpsUrlToHttpRegistrySource() {
        RegistrySource source = RegistrySources.resolve("https://example.com/registry/");

        assertInstanceOf(HttpRegistrySource.class, source);
    }

    @Test
    void resolvesAbsolutePathToFileSystemRegistrySource() {
        RegistrySource source = RegistrySources.resolve("/path/to/registry");

        assertInstanceOf(FileSystemRegistrySource.class, source);
    }

    @Test
    void resolvesRelativePathToFileSystemRegistrySource() {
        RegistrySource source = RegistrySources.resolve("./path/to/registry");

        assertInstanceOf(FileSystemRegistrySource.class, source);
    }

    @Test
    void resolvesWindowsPathToFileSystemRegistrySource() {
        RegistrySource source = RegistrySources.resolve("C:\\path\\to\\registry");

        assertInstanceOf(FileSystemRegistrySource.class, source);
    }
}
