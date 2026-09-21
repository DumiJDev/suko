package io.suko.lang;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Casos-limite da gramática que a sondagem manual (ver ARCHITECTURE.md /
 * spec do subprojeto 1) encontrou como falhas. Cada teste corresponde a
 * uma correção pontual na gramática — só verifica ausência de erro de
 * parsing, não a árvore resultante (isso é coberto pelos testes de
 * AST/emitter mais à frente).
 */
class SukoGrammarFixesTest {

    static List<String> parseErrors(String source) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener collector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                     int line, int charPositionInLine, String msg,
                                     RecognitionException e) {
                errors.add(line + ":" + charPositionInLine + " " + msg);
            }
        };

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collector);

        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collector);

        parser.compilationUnit();
        return errors;
    }

    @Test
    void booleanLiteralInExpression() {
        List<String> errors = parseErrors("component A() { if (true) { <p>x</p> } }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void hyphenatedTagAndAttributeNames() {
        List<String> errors = parseErrors(
            "component A() { <div data-id=\"1\">x</div> <my-button>y</my-button> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void voidElementsWithoutSelfClosingSlash() {
        List<String> errors = parseErrors(
            "component A() { <p>a<br>b</p> <input type=\"text\"> </div> }"
                .replace(" </div>", "")); // sem tag de fecho pendurada
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void urlInsideTextIsNotTreatedAsComment() {
        List<String> errors = parseErrors(
            "component A() {\n  <p>Veja http://x.com agora</p>\n}");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void lineCommentsStillWork() {
        List<String> errors = parseErrors(
            "// comentário de topo\ncomponent A() {\n  <p>x</p> // comentário no fim da linha\n}");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void dollarSignWithoutIdentifierInString() {
        List<String> errors = parseErrors(
            "component A(String p = \"R$ 10\") { <p>x</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void looseQuoteInTagTextIsPlainText() {
        List<String> errors = parseErrors(
            "component A() { <p>Ecrã de 5\" polegadas</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void realStringLiteralsStillWork() {
        // regressão: garante que o predicado não quebra strings normais
        List<String> errors = parseErrors(
            "component A(String label = \"Dashboard\") { <p>${label}</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void unaryMinus() {
        List<String> errors = parseErrors(
            "component A(int x) { <p>${-1}</p> <p>${-x}</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void renderPropSlotFillSyntax() {
        List<String> errors = parseErrors(
            "component A() { Foo() { row { item -> <li>${item}</li> } } } component Foo() { }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void slotFillWithoutRenderPropStillWorks() {
        // Regressão: verifica que a forma original (sem parâmetro render-prop) continua válida.
        List<String> errors = parseErrors(
            "component A() { Foo() { row { <li>Static</li> } } } component Foo() { }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
}
