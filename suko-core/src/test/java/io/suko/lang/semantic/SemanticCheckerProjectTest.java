package io.suko.lang.semantic;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.symbol.SymbolTable;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCheckerProjectTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    private static DiagnosticCollector checkAgainstProject(Path sourceRoot, String source, String fileName, Path fileRelativePath) {
        ProjectIndex index = ProjectIndex.build(sourceRoot);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        new SemanticChecker(new SymbolTable(), diagnostics, fileName, index, fileRelativePath).check(parse(source));
        return diagnostics;
    }

    private void writeUiBadge(Path sourceRoot, boolean isPublic) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        // Nota: "public" precede a keyword 'component', não o ficheiro
        // inteiro — construir a string com a keyword no sítio certo em vez
        // de prefixar o bloco de texto completo.
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            %scomponent Badge(String text) {
              <span>${text}</span>
            }
            """.formatted(isPublic ? "public " : ""));
    }

    @Test
    void importNotFoundIsReported(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            import ui.DoesNotExist;

            component Home() {
              <div>x</div>
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "IMPORT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void componentNotVisibleIsReportedForNonPublicImport(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, false);
        String source = """
            import ui.Badge;

            component Home() {
              Badge(text="x")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "COMPONENT_NOT_VISIBLE".equals(d.code())));
    }

    /** Revisão final, achado G: antes disparava DUAS vezes — uma na linha do
     * import, outra em cada chamada — para o mesmo problema. E o call site
     * também não pode cair num COMPONENT_NOT_FOUND espúrio. */
    @Test
    void componentNotVisibleFiresExactlyOnceForImportedNonPublicComponent(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, false);
        String source = """
            import ui.Badge;

            component Home() {
              Badge(text="x")
              Badge(text="y")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));

        assertEquals(1, diagnostics.getDiagnostics().stream()
                .filter(d -> "COMPONENT_NOT_VISIBLE".equals(d.code())).count(),
            diagnostics.toString());
        assertTrue(diagnostics.getDiagnostics().stream().noneMatch(d -> "COMPONENT_NOT_FOUND".equals(d.code())),
            "a supressão no call site não pode trocar um diagnóstico por outro: " + diagnostics);
        assertEquals(1, diagnostics.getDiagnostics().size(), diagnostics.toString());
    }

    /** Subprojeto 5: o furo real na visibilidade — um componente file-private
     * noutro ficheiro, chamado dentro de uma tag HTML (não como statement
     * top-level), tem de disparar COMPONENT_NOT_VISIBLE tal como dispararia
     * fora da tag (R2, subprojeto 7). */
    @Test
    void reportsNonVisibleComponentCalledInsideHtmlElement(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, false);
        // Chamada por nome QUALIFICADO, sem `import` — não passa pelo
        // caminho de checkImportsAndBuildAliasMap (que já reportaria
        // COMPONENT_NOT_VISIBLE na linha do import, mascarando o bug real).
        // Isto força a visibilidade a ser verificada no próprio call site,
        // dentro de checkExprForComponentCalls/checkComponentCall — que só é
        // alcançado se checkStatement descer para dentro de <div> (R2).
        String source = """
            component Home() {
              <div>ui.Badge(text="x")</div>
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "COMPONENT_NOT_VISIBLE".equals(d.code())),
            diagnostics.toString());
    }

    @Test
    void packageDirectoryMismatchIsReported(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            package ui;

            component Wrong() {
              <div>x</div>
            }
            """;
        // ficheiro fisicamente na raiz, mas declara package ui — não bate
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Wrong.sk", Path.of("Wrong.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "PACKAGE_DIRECTORY_MISMATCH".equals(d.code())));
    }

    /** Revisão final, achado F: o diagnóstico reportava sempre 0:0 em vez da
     * posição real da declaração `package`. */
    @Test
    void packageDirectoryMismatchPointsAtTheRealPackageDeclaration(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            // comentário antes do package

            package ui;

            component Wrong() {
              <div>x</div>
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Wrong.sk", Path.of("Wrong.sk"));

        var mismatch = diagnostics.getDiagnostics().stream()
            .filter(d -> "PACKAGE_DIRECTORY_MISMATCH".equals(d.code()))
            .findFirst().orElseThrow();
        assertEquals(3, mismatch.span().startLine(), "linha real do `package ui;`");
        assertEquals(0, mismatch.span().startColumn());
        assertTrue(mismatch.span().endIndex() > mismatch.span().startIndex(),
            "span real, não o placeholder 0:0:0:0");
    }

    @Test
    void ambiguousImportIsReportedWhenTwoUnaliasedImportsShareShortName(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        Path otherDir = sourceRoot.resolve("other");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("Badge.sk"), """
            package other;

            public component Badge(String text) {
              <em>${text}</em>
            }
            """);

        String source = """
            import ui.Badge;
            import other.Badge;

            component Home() {
              Badge(text="x")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "AMBIGUOUS_IMPORT".equals(d.code())));
    }

    @Test
    void wellFormedCrossFileCallIsClean(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            import ui.Badge;

            component Home() {
              Badge(text="x")
              ui.Badge(text="y")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().isEmpty(), diagnostics.getDiagnostics().toString());
    }

    @Test
    void threeArgConstructorStillWorksWithoutProjectAwareness() {
        String source = """
            component Home() {
              <div>x</div>
            }
            """;
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        new SemanticChecker(new SymbolTable(), diagnostics, "Home.sk").check(parse(source));
        assertTrue(diagnostics.getDiagnostics().isEmpty());
    }
}
