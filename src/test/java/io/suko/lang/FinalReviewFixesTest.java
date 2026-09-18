package io.suko.lang;

import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.semantic.SemanticChecker;
import io.suko.lang.support.JteRenderSupport;
import io.suko.lang.symbol.SymbolTable;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regressões da revisão final do subprojeto 6 (achados 1-5). Cada teste
 * refere explicitamente o achado que cobre e usa o motor gg.jte real
 * (JteRenderSupport) sempre que o achado é sobre código gerado.
 */
class FinalReviewFixesTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    private static DiagnosticCollector checkSource(String source, String fileName) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        new SemanticChecker(new SymbolTable(), diagnostics, fileName).check(parse(source));
        return diagnostics;
    }

    // --- Achado 1: o exemplo migrado nunca era verificado nem renderizado ---

    @Test
    void layoutComponentsExampleIsSemanticallyClean() throws Exception {
        String path = "examples/layout/LayoutComponents.sk";
        String source = Files.readString(Path.of(path));

        DiagnosticCollector diagnostics = checkSource(source, path);

        assertTrue(diagnostics.getDiagnostics().isEmpty(),
                "o exemplo migrado tem de passar o verificador sem um único diagnóstico: " + diagnostics.getDiagnostics());
        assertFalse(diagnostics.getDiagnostics().stream().anyMatch(d -> "BARE_BRACE_IN_STRING".equals(d.code())));
    }

    @Test
    void layoutComponentsButtonRendersInterpolatedClass() throws Exception {
        String source = Files.readString(Path.of("examples/layout/LayoutComponents.sk"));

        String html = JteRenderSupport.renderWithDependencies(source, "Button", Map.of(
                "href", "/go",
                "label", "Clicar",
                "variant", "primary",
                "disabled", false));

        assertTrue(html.contains("class=\"btn btn-primary\""), html);
        assertTrue(html.contains("Clicar"), html);
    }

    // --- Achado 3: concatenação de string sem âncora String ---

    @Test
    void adjacentNumericInterpolationsConcatenateInsteadOfAdding() throws Exception {
        String html = JteRenderSupport.render("""
            component Show(int a, int b) {
              <p class="${a}${b}">x</p>
            }
            """, "Show", Map.of("a", 1, "b", 2));

        assertTrue(html.contains("class=\"12\""), "esperava concatenação textual '12', obtido: " + html);
        assertFalse(html.contains("class=\"3\""), "soma aritmética em vez de concatenação: " + html);
    }

    @Test
    void pureSingleNonStringInterpolationIsUsableWhereStringIsExpected() throws Exception {
        String html = JteRenderSupport.renderWithDependencies("""
            component Label(String text) {
              <p>{text}</p>
            }
            component Host(int count) {
              Label(text="$count")
            }
            """, "Host", Map.of("count", 7));

        assertTrue(html.contains("<p>7</p>"), html);
    }

    // --- Achado 4: auto-toString não chegava à interpolação dentro de strings ---

    @Test
    void arbitraryObjectParamInterpolatedInsideAttributeCompilesAndRenders() throws Exception {
        String uuid = "11111111-1111-1111-1111-111111111111";

        String html = JteRenderSupport.render("""
            component Show(Object id) {
              <a href="${id}">link</a>
            }
            """, "Show", Map.of("id", java.util.UUID.fromString(uuid)));

        assertTrue(html.contains("href=\"" + uuid + "\""), html);
    }

    // --- Achado 2: children MANY também recebe conteúdo solto (um só elemento) ---

    @Test
    void looseContentIntoManyChildrenBecomesASingleListElement() throws Exception {
        String html = JteRenderSupport.renderWithDependencies("""
            component Stack(List<Component> children) {
              <ul>
                for (Content c : children) {
                  <li>{c}</li>
                }
              </ul>
            }
            component Host() {
              Stack() {
                <b>a</b>
                <i>b</i>
              }
            }
            """, "Host", Map.of());

        assertTrue(html.contains("<li><b>a</b><i>b</i></li>"), html);
        assertEquals(1, html.split("<li>", -1).length - 1, "todo o conteúdo solto tem de virar UM elemento de lista: " + html);
    }

    @Test
    void explicitChildrenFillAndLooseContentCoexistOnManyWithoutDiagnostics() throws Exception {
        String source = """
            component Stack(List<Component> children) {
              <ul>
                for (Content c : children) {
                  <li>{c}</li>
                }
              </ul>
            }
            component Host() {
              Stack() {
                children { <b>x</b> }
                <i>y</i>
              }
            }
            """;

        assertTrue(checkSource(source, "test.sk").getDiagnostics().isEmpty(),
                "limitação aceite e documentada: em children MANY a mistura não é erro");

        String html = JteRenderSupport.renderWithDependencies(source, "Host", Map.of());
        assertTrue(html.contains("<li><b>x</b></li>"), html);
        assertTrue(html.contains("<li><i>y</i></li>"), html);
    }

    // --- Achado 5: chamada de componente como valor + bloco de slot ---

    @Test
    void componentCallAsValueWithSlotBlockIsFlaggedAsUnparsedVarDecl() {
        DiagnosticCollector diagnostics = checkSource("""
            component Card(Component children) {
              <div>{children}</div>
            }
            component Host() {
              var c = Card() { "x" };
              <div>{c}</div>
            }
            """, "test.sk");

        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "VAR_DECL_NOT_PARSED".equals(d.code())),
                "esperava o aviso da limitação documentada: " + diagnostics.getDiagnostics());
    }

    @Test
    void wellFormedComponentCallAsValueIsNotFlagged() {
        DiagnosticCollector diagnostics = checkSource("""
            component CardA() {
              <p>A</p>
            }
            component Host() {
              var c = CardA();
              <div>{c}</div>
            }
            """, "test.sk");

        assertTrue(diagnostics.getDiagnostics().isEmpty(), diagnostics.getDiagnostics().toString());
    }

    // --- Regressão pós-revisão-final: âncora "" + X do achado 3 quebra
    // null para uma única interpolação de um ValueParam de tipo referência
    // conhecido (ex. String) — "" + null é o TEXTO "null" em Java, não vazio.

    @Test
    void singleNullableParamInterpolatedAloneRendersEmptyNotNullText() throws Exception {
        String html = JteRenderSupport.render("""
            component Show(String label = null) {
              <a title="$label">x</a>
            }
            """, "Show", Map.of());

        assertTrue(html.contains("title=\"\""), "esperava atributo vazio, obtido: " + html);
        assertFalse(html.contains("null"), "regressão: texto literal 'null' na saída: " + html);
    }

    @Test
    void singleNullableParamInterpolatedAloneRendersValueWhenPresent() throws Exception {
        String html = JteRenderSupport.render("""
            component Show(String label = null) {
              <a title="$label">x</a>
            }
            """, "Show", Map.of("label", "ok"));

        assertTrue(html.contains("title=\"ok\""), html);
    }
}
