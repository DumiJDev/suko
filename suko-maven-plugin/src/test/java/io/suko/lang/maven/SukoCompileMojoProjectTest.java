package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SukoCompileMojoProjectTest {

    @Test
    void compilesNestedPackagesIntoMirroredOutputDirectories(@TempDir Path sourceRoot, @TempDir Path outputDir) throws Exception {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="${href}">link</a>
            }
            """);

        SukoCompileMojo mojo = new SukoCompileMojo();
        setField(mojo, "sourceDir", sourceRoot.toFile());
        setField(mojo, "outputDir", outputDir.toFile());

        mojo.execute();

        assertTrue(Files.exists(outputDir.resolve("ui").resolve("NavLink.jte")),
            "esperava ui/NavLink.jte espelhando o pacote");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
