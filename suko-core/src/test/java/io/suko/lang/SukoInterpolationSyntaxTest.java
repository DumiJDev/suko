package io.suko.lang;

import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.support.JteRenderSupport;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subprojeto 9 (D1/D4): `${expr}` interpola nas três posições. Enquanto
 * este subprojeto não fecha a porta (Task 10 do plano), a forma legada
 * `{expr}` continua a fazer parse e a produzir o MESMO .jte — é isso que
 * permite migrar o repositório com a suite verde.
 */
class SukoInterpolationSyntaxTest {

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

    static SukoFile ast(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void dollarBraceParsesAsStatementInterpolation() {
        assertEquals(List.of(), parseErrors("component A(String x) { ${x} }"));
    }

    /** C1: a prova de que o textRun guloso não engole o `${`. */
    @Test
    void textRunDoesNotSwallowDollarBrace() {
        SukoFile file = ast("component A(String price) { <p>custa ${price} euros</p> }");
        List<Statement> children =
            ((Statement.HtmlElement) file.components().get(0).body().get(0)).children();
        assertEquals(3, children.size(), "esperado TextRun + Interpolation + TextRun, veio: " + children);
        assertTrue(children.get(0) instanceof Statement.TextRun);
        assertTrue(children.get(1) instanceof Statement.Interpolation);
        assertTrue(children.get(2) instanceof Statement.TextRun);
    }

    @Test
    void dollarBraceRendersTheSameAsLegacyBrace() throws Exception {
        String modern = "component A(String x) { <p>${x}</p> }";
        String legacy = "component A(String x) { <p>{x}</p> }";
        assertEquals(JteRenderSupport.compileToJte(legacy, "A"),
            JteRenderSupport.compileToJte(modern, "A"));
        assertEquals("<p>oi</p>",
            JteRenderSupport.render(modern, "A", Map.of("x", "oi")).trim());
    }

    @Test
    void dollarBraceWorksAsUnquotedAttributeValue() throws Exception {
        String modern = "component A(boolean disabled) { <button disabled=${disabled}>x</button> }";
        String legacy = "component A(boolean disabled) { <button disabled={disabled}>x</button> }";
        assertEquals(List.of(), parseErrors(modern));
        assertEquals(JteRenderSupport.compileToJte(legacy, "A"),
            JteRenderSupport.compileToJte(modern, "A"));
    }

    @Test
    void whitespaceAroundStatementInterpolationIsPreserved() throws Exception {
        String source = "component A(String name) { <p>Ola, ${name}!</p> }";
        assertEquals("<p>Ola, Ana!</p>",
            JteRenderSupport.render(source, "A", Map.of("name", "Ana")).trim());
    }

    @Test
    void stringInterpolationIsUnchanged() throws Exception {
        String source = "component A(String id) { <div id=\"dialog-${id}\">x</div> }";
        assertEquals("<div id=\"dialog-7\">x</div>",
            JteRenderSupport.render(source, "A", Map.of("id", "7")).trim());
    }

    /** D3 REJEITADA: `$ident` dentro de string mantém-se. */
    @Test
    void simpleDollarIdentInStringIsUnchanged() throws Exception {
        String source = "component A(String id) { <div id=\"dialog-$id\">x</div> }";
        assertEquals("<div id=\"dialog-7\">x</div>",
            JteRenderSupport.render(source, "A", Map.of("id", "7")).trim());
    }

    /** C5: chavetas nuas dentro de string continuam texto literal (Alpine). */
    @Test
    void bareBracesInsideStringStayLiteral() throws Exception {
        String source = "component A() { <div x-data=\"{ open: false }\">x</div> }";
        assertEquals("<div x-data=\"{ open: false }\">x</div>",
            JteRenderSupport.render(source, "A", Map.of()).trim());
    }

    /** Ponte de migração: a forma legada ainda faz parse (fecha na Task 10). */
    @Test
    void legacyBraceStillParsesForNow() {
        assertEquals(List.of(), parseErrors("component A(String x) { {x} }"));
        assertEquals(List.of(), parseErrors("component A(String x) { <p id={x}>y</p> }"));
    }
}
