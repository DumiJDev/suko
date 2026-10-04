package io.suko.lang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SukoExtensionsTestKitTest {

    @Test
    void extensionFromSukoExtensionsRunsAndWritesManifest(@TempDir Path project) throws Exception {
        String extJar = System.getProperty("suko.testExtJar").replace('\\', '/');
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"demo-app\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            dependencies { "sukoExtensions"(files("%s")) }
            suko { targets.set(listOf("jte", "demo")) }
            """.formatted(extJar));
        Path sk = project.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(sk.getParent());
        Files.writeString(sk, "package ui;\n\ncomponent Hello() {\n  <box><label>x</label></box>\n}\n");

        BuildResult result = GradleRunner.create().withProjectDir(project.toFile())
            .withPluginClasspath().withArguments("sukoCompile", "--stacktrace").build();

        Path out = project.resolve("build/generated-src/suko");
        assertTrue(Files.exists(out.resolve("jte/ui/Hello.jte")), result.getOutput());
        assertEquals("demo:Hello\n", Files.readString(out.resolve("demo/ui/Hello.demo")));
        String manifest = Files.readString(project.resolve("build/suko/extensions.json"));
        assertTrue(manifest.contains("suko-test-ext") && manifest.contains("\"demo\""), manifest);
    }

    @Test
    void projectWithoutExtensionsIsUnchanged(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"plain\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { id(\"io.suko.lang\") }\n");
        Path sk = project.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(sk.getParent());
        Files.writeString(sk, "package ui;\n\ncomponent Hello() {\n  <p>x</p>\n}\n");
        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").build();
        assertTrue(Files.exists(project.resolve("build/generated-src/suko/ui/Hello.jte")));
    }
}
