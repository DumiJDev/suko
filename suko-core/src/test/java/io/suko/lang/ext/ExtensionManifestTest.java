package io.suko.lang.ext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionManifestTest {

    @Test
    void writesClasspathAndTargetsAsJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build/suko/extensions.json");
        ExtensionManifest.write(file, List.of(Path.of("/a/x.jar"), Path.of("/b/y \"q\".jar")), List.of("jte", "demo"));
        String json = Files.readString(file);
        assertEquals("{\"classpath\":[\"/a/x.jar\",\"/b/y \\\"q\\\".jar\"],\"targets\":[\"jte\",\"demo\"]}", json);
    }

    @Test
    void manifestNeverListsTheBuiltInJteJar() {
        // No classpath dos testes do core só está o suko-jte (embutido): nada a listar.
        assertEquals(List.of(), ExtensionManifest.extensionJars(ExtensionManifestTest.class.getClassLoader()));
    }
}
