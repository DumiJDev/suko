package io.suko.lang.project;

import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SukoProjectCompilerTest {

    @Test
    void compilesMultiplePackagesIntoMirroredJteSources(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="${href}">link</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;

            component Home() {
              NavLink(href="/")
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertTrue(result.generatedJteSources().containsKey(Path.of("ui", "NavLink.jte")));
        assertTrue(result.generatedJteSources().containsKey(Path.of("Home.jte")));
        assertTrue(result.generatedJteSources().get(Path.of("Home.jte")).contains("@template.ui.NavLink("));
    }

    @Test
    void reportsFailureWithoutThrowingWhenAFileHasErrors(@TempDir Path sourceRoot) throws IOException {
        Files.writeString(sourceRoot.resolve("Broken.sk"), """
            component Broken() {
              Missing(text="x")
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertFalse(result.success());
        DiagnosticCollector diagnostics = result.diagnosticsByFile().get(Path.of("Broken.sk"));
        assertNotNull(diagnostics);
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> "COMPONENT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void rendersEndToEndAcrossPackagesViaRenderProject(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href, String label) {
              <a href="${href}">{label}</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;

            component Home() {
              NavLink(href="/", label="Início")
            }
            """);

        String html = JteRenderSupport.renderProject(sourceRoot, "Home", Map.of());
        assertTrue(html.contains("href=\"/\""), html);
        assertTrue(html.contains("Início"), html);
    }
}
