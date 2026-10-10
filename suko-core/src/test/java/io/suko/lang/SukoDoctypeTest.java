package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.ast.Statement;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `<!DOCTYPE html>` só é aceite como PRIMEIRO item do corpo de um componente
 * (regra `componentBody`); qualquer outra forma `<!...>` ou posição dá o
 * diagnóstico INVALID_DOCTYPE.
 */
class SukoDoctypeTest {

    private static List<SukoDiagnostic> diagnosticsOf(String source) {
        return new JteCompiler("test.sk", source).compile().diagnostics().getDiagnostics();
    }

    private static void assertInvalidDoctype(String source) {
        List<SukoDiagnostic> ds = diagnosticsOf(source);
        SukoDiagnostic d = ds.stream().filter(x -> "INVALID_DOCTYPE".equals(x.code())).findFirst()
            .orElseThrow(() -> new AssertionError("sem INVALID_DOCTYPE; diagnósticos: " + ds));
        assertTrue(d.message().contains("<!DOCTYPE html>"), d.message());
    }

    private static ComponentDecl parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());
        assertEquals(0, parser.getNumberOfSyntaxErrors());
        return file.components().get(0);
    }

    @Test
    void doctypeThenHtmlRootParses() {
        String source = "component Layout() { <!DOCTYPE html> <html><body>x</body></html> }";
        assertFalse(new JteCompiler("test.sk", source).compile().diagnostics().hasErrors(),
            diagnosticsOf(source).toString());
    }

    @Test
    void doctypeIsCaseInsensitiveAndNormalised() {
        ComponentDecl c = parse("component L() { <!doctype   HTML\n> <html></html> }");
        Statement.TextRun run = assertInstanceOf(Statement.TextRun.class, c.body().get(0));
        assertEquals("<!DOCTYPE html>", run.text());
        assertInstanceOf(Statement.HtmlElement.class, c.body().get(1));
    }

    @Test
    void astShapeAndPositions() {
        String source = "component L() {\n  <!DOCTYPE html>\n  <html></html>\n}";
        ComponentDecl c = parse(source);
        assertEquals(2, c.body().size());
        Statement.TextRun run = assertInstanceOf(Statement.TextRun.class, c.body().get(0));
        assertEquals("<!DOCTYPE html>", run.text());
        assertEquals(2, run.span().startLine());
        assertEquals(2, run.span().startColumn());
        assertEquals(source.indexOf("<!DOCTYPE"), run.span().startIndex());
        assertEquals(source.indexOf("<!DOCTYPE") + "<!DOCTYPE html>".length() - 1, run.span().endIndex());
    }

    @Test
    void bodyWithoutDoctypeIsUnchanged() {
        ComponentDecl c = parse("component L() { <html></html> }");
        assertEquals(1, c.body().size());
        assertInstanceOf(Statement.HtmlElement.class, c.body().get(0));
    }

    @Test
    void doctypeAfterElementIsRejected() {
        assertInvalidDoctype("component L() { <p>a</p> <!DOCTYPE html> }");
    }

    @Test
    void doctypeInsideElementIsRejected() {
        assertInvalidDoctype("component L() { <div><!DOCTYPE html></div> }");
    }

    @Test
    void doctypeInsideIfIsRejected() {
        assertInvalidDoctype("component L(boolean b) { if (b) { <!DOCTYPE html> <p>a</p> } }");
    }

    @Test
    void doctypeAfterTextIsRejected() {
        assertInvalidDoctype("component L() { olá <!DOCTYPE html> }");
    }

    @Test
    void secondDoctypeIsRejected() {
        assertInvalidDoctype("component L() { <!DOCTYPE html> <!DOCTYPE html> <html></html> }");
    }

    @Test
    void doctypeWithPublicIdIsRejected() {
        assertInvalidDoctype("component L() { <!DOCTYPE html PUBLIC \"x\"> <html></html> }");
    }

    @Test
    void elementDeclarationIsRejected() {
        assertInvalidDoctype("component L() { <!ELEMENT a> <p>x</p> }");
    }

    @Test
    void htmlCommentIsRejected() {
        assertInvalidDoctype("component L() { <!-- c --> <p>x</p> }");
    }

    @Test
    void doctypeWithOtherNameIsRejected() {
        assertInvalidDoctype("component L() { <!DOCTYPE svg> <p>x</p> }");
    }

    @Test
    void notOperatorAfterLessThanWithSpaceStillWorks() {
        String source = "component L(boolean a, boolean b) { if (a < !b) { <p>x</p> } }";
        assertFalse(new JteCompiler("test.sk", source).compile().diagnostics().hasErrors());
    }
}
