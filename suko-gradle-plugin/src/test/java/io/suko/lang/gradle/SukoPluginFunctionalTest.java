package io.suko.lang.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste funcional (TestKit) do plugin Gradle Suko: confirma que o plugin é
 * descobrível pelo ID {@code io.suko.lang} (via {@code plugins { id(...) }})
 * e que a task {@code sukoCompile} produz .jte a partir de .sk sob a
 * convenção {@code src/main/suko}.
 */
class SukoPluginFunctionalTest {

    @TempDir
    Path projectDir;

    @Test
    void appliesByIdAndCompilesSkToJte() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle.kts"), """
            rootProject.name = "suko-plugin-functional-test"
            """);

        Files.writeString(projectDir.resolve("build.gradle.kts"), """
            plugins {
                id("io.suko.lang")
            }
            """);

        Path sourceDir = projectDir.resolve("src/main/suko/io/demo");
        Files.createDirectories(sourceDir);
        Files.writeString(sourceDir.resolve("Hello.sk"), """
            package io.demo;

            component Hello(String name) {
              <p>${name}</p>
            }
            """);

        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("sukoCompile", "--console=plain")
            .build();

        Path jteFile = projectDir.resolve("build/generated-src/suko/io/demo/Hello.jte");
        assertTrue(Files.exists(jteFile), "Hello.jte devia ter sido gerado em " + jteFile);
    }
}
