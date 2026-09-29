package io.suko.lang.project;

import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** O caminho em memória ({@link SukoSources}) tem de ser indistinguível do
 * caminho do disco — o language server e o sukoCompile não podem divergir.
 * Nota: {@code compile(Path)} delega em {@code compile(SukoSources)}, por isso
 * as comparações Path/Sources só guardam essa delegação; a protecção real
 * contra regressões de comportamento é a suite existente do compilador. O que
 * tem valor próprio aqui é {@code analyze} vs {@code compile} e o overlay. */
class ProjectParityTest {

    private static void write(Path root, String rel, String text) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private static Map<Path, List<SukoDiagnostic>> diagnostics(Map<Path, io.suko.lang.diagnostic.DiagnosticCollector> byFile) {
        Map<Path, List<SukoDiagnostic>> out = new LinkedHashMap<>();
        byFile.forEach((p, d) -> out.put(p, d.getDiagnostics()));
        return out;
    }

    @Test
    void pathAndSourcesProduceSameOutputAndDiagnostics(@TempDir Path root) throws IOException {
        write(root, "ui/NavLink.sk", "package ui;\n\npublic component NavLink(String href) {\n  <a href=\"${href}\">x</a>\n}\n");
        write(root, "Home.sk", "import ui.NavLink;\n\ncomponent Home() {\n  NavLink(href=\"/\")\n}\n");
        write(root, "Broken.sk", "component Broken() {\n  Missing(text=\"x\")\n}\n");
        write(root, "DupA.sk", "public component Dup() { <p>a</p> }\n");
        write(root, "sub/DupB.sk", "public component Dup() { <p>b</p> }\n");

        var viaPath = new SukoProjectCompiler().compile(root);
        var viaSources = new SukoProjectCompiler().compile(SukoSources.fromDirectory(root));

        assertEquals(viaPath.success(), viaSources.success());
        assertEquals(viaPath.generatedJteSources(), viaSources.generatedJteSources());
        assertEquals(diagnostics(viaPath.diagnosticsByFile()), diagnostics(viaSources.diagnosticsByFile()));
    }

    @Test
    void analyzeReportsTheSameDiagnosticsAsCompileWithoutEmitting(@TempDir Path root) throws IOException {
        write(root, "Broken.sk", "component Broken() {\n  Missing(text=\"x\")\n}\n");
        write(root, "Ok.sk", "public component Ok() { <p>ok</p> }\n");
        write(root, "Syntax.sk", "component S( {\n");
        SukoSources sources = SukoSources.fromDirectory(root);

        var compiled = new SukoProjectCompiler().compile(sources);
        ProjectAnalysis analysis = new SukoProjectCompiler().analyze(sources);

        assertEquals(compiled.diagnosticsByFile().keySet(), analysis.files().keySet());
        compiled.diagnosticsByFile().forEach((path, diags) ->
            assertEquals(diags.getDiagnostics(), analysis.files().get(path).diagnostics().getDiagnostics(),
                path.toString()));
        assertNotNull(analysis.files().get(Path.of("Ok.sk")).ast());
        assertNull(analysis.files().get(Path.of("Syntax.sk")).ast());
    }

    @Test
    void overlayIsAnalysedInsteadOfTheDiskContent(@TempDir Path root) throws IOException {
        write(root, "A.sk", "public component A() { <p>disk</p> }\n");
        write(root, "B.sk", "import A;\n\ncomponent B() {\n  A()\n}\n");
        SukoSources disk = SukoSources.fromDirectory(root);
        assertFalse(new SukoProjectCompiler().analyze(disk).files().get(Path.of("B.sk")).diagnostics().hasErrors());

        SukoSources buffer = disk.withOverlay(Map.of(Path.of("B.sk"), "component B() {\n  Nope()\n}\n"));
        var analysis = new SukoProjectCompiler().analyze(buffer);

        assertTrue(analysis.files().get(Path.of("B.sk")).diagnostics().getErrors().stream()
            .anyMatch(d -> "COMPONENT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void componentsLibraryMatchesBetweenPathAndSources() {
        Path library = Path.of("..", "suko-components", "src", "main", "suko");
        if (!Files.isDirectory(library)) {
            return;
        }
        var viaPath = new SukoProjectCompiler().compile(library);
        var viaSources = new SukoProjectCompiler().compile(SukoSources.fromDirectory(library));
        assertEquals(viaPath.generatedJteSources(), viaSources.generatedJteSources());
        assertEquals(diagnostics(viaPath.diagnosticsByFile()), diagnostics(viaSources.diagnosticsByFile()));
    }
}
