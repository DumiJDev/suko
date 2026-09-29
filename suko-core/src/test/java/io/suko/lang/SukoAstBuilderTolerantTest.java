package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** O modo tolerante serve o language server: um ficheiro a meio de ser escrito
 * tem de continuar analisável. O modo estrito (build) não pode mudar. */
class SukoAstBuilderTolerantTest {

    private static final String GOOD = "\ncomponent Good(String title) {\n  <b>ok</b>\n}\n";

    private static List<String> names(SukoFile file) {
        return file.components().stream().map(ComponentDecl::name).toList();
    }

    private static ComponentDecl component(SukoFile file, String name) {
        return file.components().stream().filter(c -> c.name().equals(name)).findFirst()
            .orElseThrow(() -> new AssertionError(name + " em falta: " + names(file)));
    }

    private static void assertGoodIntact(String source, SukoFile file) {
        ComponentDecl good = component(file, "Good");
        assertEquals(1, good.params().size());
        assertEquals("title", good.params().get(0).name());
        assertEquals("Good", source.substring(good.nameSpan().startIndex(), good.nameSpan().endIndex() + 1));
        assertFalse(good.body().isEmpty());
    }

    @Test
    void memberAccessLeftOpenDoesNotSwallowTheNextComponent() {
        String source = "component A() {\n  <p>${t.\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        assertTrue(names(file).contains("A"), names(file).toString());
        assertGoodIntact(source, file);
    }

    @Test
    void unclosedParameterListKeepsTheNameAndTheNextComponent() {
        String source = "component B( {\n  <p>x</p>\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        assertTrue(names(file).contains("B"), names(file).toString());
        assertGoodIntact(source, file);
    }

    @Test
    void callWithoutClosingParenDoesNotBreakTheFile() {
        String source = "component C() {\n  <div>\n    A(\n  </div>\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        assertEquals(List.of("C", "Good"), names(file));
        assertGoodIntact(source, file);
    }

    @Test
    void partialArgumentInsideATagStillRecovers() {
        String source = "component D() {\n  <div>\n    Card(ti\n  </div>\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        assertEquals(List.of("D", "Good"), names(file));
        assertGoodIntact(source, file);
        assertFalse(component(file, "D").body().isEmpty());
    }

    @Test
    void headerIsKeptAndSpansMatchTheRealFileEvenAfterBrokenComponents() {
        String source = "package ui;\n\nimport other.Badge;\n\ncomponent A() {\n  <p>${t.\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        assertEquals("ui", file.packageName().orElseThrow());
        assertEquals("other.Badge", file.imports().get(0).qualifiedName());
        assertGoodIntact(source, file);
        assertEquals(source.indexOf("Good"), component(file, "Good").nameSpan().startIndex());
    }

    @Test
    void spansStayAlignedWithNonBmpCharactersInEarlierComponents() {
        String source = "component A() {\n  <p>😀 ${t.\n}\n" + GOOD;
        SukoFile file = TolerantParser.parse(source);
        ComponentDecl good = component(file, "Good");
        assertEquals(source.codePointCount(0, source.indexOf("Good")), good.nameSpan().startIndex());
    }

    @Test
    void validFilesGiveTheSameComponentsAsTheStrictBuilder() {
        String source = "package ui;\n\npublic component A(String x) {\n  <p>${x}</p>\n}\n" + GOOD;
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(source))));
        SukoFile strict = new SukoAstBuilder(source).build(parser.compilationUnit());
        assertEquals(strict, TolerantParser.parse(source));
    }

    @Test
    void neverThrowsOnGarbage() {
        for (String source : List.of("", "}}}{{{", "component", "component (", "public public component X(",
                "${", "\"unterminated", "component A() { if (", "import ;")) {
            assertDoesNotThrow(() -> TolerantParser.parse(source), source);
        }
    }

    @Test
    void strictBuilderIsUnchanged() {
        String source = "component B( {\n  <p>x</p>\n}\n";
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        SukoParser.CompilationUnitContext tree = parser.compilationUnit();
        assertThrows(RuntimeException.class, () -> new SukoAstBuilder(source).build(tree));
    }

    @Test
    void discardsOnlyTheBrokenStatement() {
        String source = "component A() {\n  <p>ok</p>\n  <p>${t.</p>\n  <i>after</i>\n}\n";
        ComponentDecl a = component(TolerantParser.parse(source), "A");
        assertTrue(a.body().stream().anyMatch(s -> s instanceof Statement.HtmlElement h && h.tagName().equals("p")));
    }
}
