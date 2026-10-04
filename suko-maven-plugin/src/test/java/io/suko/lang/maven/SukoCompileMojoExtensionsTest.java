package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SukoCompileMojoExtensionsTest {

    @Test
    void pluginDependencyExtensionRunsForRequestedTargets(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package ui;\n\ncomponent Hello() {\n  <box></box>\n}\n");
        Path out = dir.resolve("target/generated-sources/suko");
        URL ext = Path.of(System.getProperty("suko.testExtJar")).toUri().toURL();
        ClassLoader pluginLoader = new URLClassLoader(new URL[] {ext}, SukoCompileMojo.class.getClassLoader());

        SukoCompileMojo mojo = new SukoCompileMojo() {
            @Override
            ClassLoader extensionLoader() {
                return pluginLoader;
            }
        };
        set(mojo, "sourceDir", dir.resolve("src/main/suko").toFile());
        set(mojo, "outputDir", out.toFile());
        set(mojo, "buildDirectory", dir.resolve("target").toFile());
        set(mojo, "targets", List.of("jte", "demo"));
        mojo.execute();

        assertTrue(Files.exists(out.resolve("jte/ui/Hello.jte")));
        assertEquals("demo:Hello\n", Files.readString(out.resolve("demo/ui/Hello.demo")));
        String manifest = Files.readString(dir.resolve("target/suko/extensions.json"));
        assertTrue(manifest.contains("suko-test-ext"), manifest);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = SukoCompileMojo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
