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
              <span>{text}</span>
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

    @Test
    void ambiguousImportIsReportedWhenTwoUnaliasedImportsShareShortName(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        Path otherDir = sourceRoot.resolve("other");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("Badge.sk"), """
            package other;

            public component Badge(String text) {
              <em>{text}</em>
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
