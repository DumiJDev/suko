package io.suko.lang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SukoSecurityTestKitTest {

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void generatesSukoSafeIntoTheJavaSourceDirAndAuditsTrustedUses(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"demo-shop\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { java; id("io.suko.lang") }
            suko { security { urlSchemes = listOf("https", "mailto") } }
            """);
        write(project.resolve("src/main/suko/ui/Link.sk"), """
            package ui;
            public component Link(String url, String embed) {
              <a href=${url}>l</a>
              <iframe src=${trustedUrl(embed)}></iframe>
            }
            """);

        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile", "--stacktrace").build();

        Path safe = project.resolve("build/generated-src/suko-java/io/suko/generated/demo_shop/SukoSafe.java");
        assertTrue(Files.exists(safe), "SukoSafe.java deveria ter sido gerada\n" + result.getOutput());
        assertTrue(Files.readString(safe).contains("Set.of(\"https\", \"mailto\")"));
        String jte = Files.readString(project.resolve("build/generated-src/suko/ui/Link.jte"));
        assertTrue(jte.contains("io.suko.generated.demo_shop.SukoSafe.url(url)"), jte);
        String audit = Files.readString(project.resolve("build/suko/security-audit.json"));
        assertTrue(audit.contains("TRUSTED_URL") && audit.contains("\"expression\": \"embed\""), audit);
    }

    @Test
    void forbiddenSchemeFailsTheBuildWithAClearMessage(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            suko { security { urlSchemes = listOf("https", "javascript") } }
            """);
        write(project.resolve("src/main/suko/A.sk"), "component A() { <p>x</p> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").buildAndFail();
        assertTrue(result.getOutput().contains("javascript"), result.getOutput());
    }

    @Test
    void unsafeSinkFailsTheBuild(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), "plugins { id(\"io.suko.lang\") }");
        write(project.resolve("src/main/suko/A.sk"), "component A(String x) { <script>${x}</script> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").buildAndFail();
        assertTrue(result.getOutput().contains("UNSAFE_SINK"), result.getOutput());
    }

    @Test
    void checkerWarningsAreVisibleOnASuccessfulBuild(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            suko { security { strictCsp = true } }
            """);
        write(project.resolve("src/main/suko/A.sk"), "component A() { <div style=\"color: red\">x</div> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").build();
        assertTrue(result.getOutput().contains("CSP_INLINE"), result.getOutput());
    }
}
