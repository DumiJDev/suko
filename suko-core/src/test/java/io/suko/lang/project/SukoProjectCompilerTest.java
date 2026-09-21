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

    /** Revisão final, achado A: um único .sk malformado fazia a Fase 1
     * (ProjectIndex.build) rebentar com NPE e abortava a compilação do
     * projeto INTEIRO, incluindo ficheiros válidos. */
    @Test
    void malformedFileDoesNotAbortTheWholeProjectCompile(@TempDir Path sourceRoot) throws IOException {
        Files.writeString(sourceRoot.resolve("Bad.sk"), """
            component Bad(java.util.List<String> xs {
            <div>
            """);
        Files.writeString(sourceRoot.resolve("Good.sk"), """
            component Good() {
              <p>ok</p>
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result =
            assertDoesNotThrow(() -> new SukoProjectCompiler().compile(sourceRoot));

        assertFalse(result.success(), "o ficheiro malformado tem de falhar a compilação");
        assertTrue(result.generatedJteSources().containsKey(Path.of("Good.jte")),
            "o ficheiro válido tem de continuar a compilar: " + result.generatedJteSources().keySet());
        assertTrue(result.generatedJteSources().get(Path.of("Good.jte")).contains("<p>ok</p>"));

        DiagnosticCollector badDiagnostics = result.diagnosticsByFile().get(Path.of("Bad.sk"));
        assertNotNull(badDiagnostics);
        assertFalse(badDiagnostics.getErrors().isEmpty(),
            "a Fase 2 tem de reportar o erro de sintaxe real do ficheiro malformado");
    }

    /** Revisão final, achado B: um ficheiro SEM `package` numa subpasta
     * produzia .jte em sub/ mas chamadas @template.X(...) resolvidas na
     * raiz -> TemplateNotFoundException em tempo de render, com
     * success=true e zero diagnósticos. */
    @Test
    void noPackageFileInSubdirectoryResolvesAgainstItsDirectory(@TempDir Path sourceRoot) throws IOException {
        Path subDir = sourceRoot.resolve("sub");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("Foo.sk"), """
            component Bar() {
              <span class="bar">bar</span>
            }

            component Foo() {
              <div>
                Bar()
              </div>
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertTrue(result.generatedJteSources().containsKey(Path.of("sub", "Bar.jte")));
        assertTrue(result.generatedJteSources().containsKey(Path.of("sub", "Foo.jte")));
        assertTrue(result.generatedJteSources().get(Path.of("sub", "Foo.jte")).contains("@template.sub.Bar("),
            result.generatedJteSources().get(Path.of("sub", "Foo.jte")));

        String html = JteRenderSupport.renderProject(sourceRoot, "sub/Foo", Map.of());
        assertTrue(html.contains("class=\"bar\""), html);
    }

    /** Revisão final, achado C: dois componentes com o mesmo nome qualificado
     * sobrescreviam-se em silêncio (success=true, zero diagnósticos). */
    @Test
    void duplicateQualifiedComponentIsReportedInBothFiles(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("BadgeA.sk"), """
            package ui;

            public component Badge(String text) {
              <span class="a">${text}</span>
            }
            """);
        Files.writeString(uiDir.resolve("BadgeB.sk"), """
            package ui;

            public component Badge(String text) {
              <span class="b">${text}</span>
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertFalse(result.success());
        for (String file : new String[] {"BadgeA.sk", "BadgeB.sk"}) {
            DiagnosticCollector diagnostics = result.diagnosticsByFile().get(Path.of("ui", file));
            assertNotNull(diagnostics, file);
            assertTrue(diagnostics.getErrors().stream()
                    .anyMatch(d -> "DUPLICATE_COMPONENT".equals(d.code())
                        && d.message().contains("ui.Badge")
                        && d.message().contains("BadgeA.sk")
                        && d.message().contains("BadgeB.sk")),
                file + ": " + diagnostics);
        }
        assertFalse(result.generatedJteSources().containsKey(Path.of("ui", "Badge.jte")),
            "nenhum .jte pode ser produzido para um nome em colisão");
    }

    @Test
    void rendersEndToEndAcrossPackagesViaRenderProject(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href, String label) {
              <a href="${href}">${label}</a>
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
