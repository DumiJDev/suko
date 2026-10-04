package io.suko.lsp;

import org.eclipse.lsp4j.Hover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static io.suko.lsp.TestSupport.at;
import static org.junit.jupiter.api.Assertions.*;

class HoverTest {

    private static final String CARD = """
        package ui;

        public component Card(String title, Component header, List<Component> items, Component footer = null, int size = 3) {
          <div>${title}</div>
        }
        """;

    private static final String PAGE = """
        import ui.Card;

        component Local(String text) {
          <b>${text}</b>
        }

        component Page() {
          Card(title = "x") {
            header { <b>h</b> }
          }
          Local(text = "y")
        }
        """;

    private TestSupport project(Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.write("ui/Card.sk", CARD);
        t.write("Page.sk", PAGE);
        return t;
    }

    private static String text(Hover hover) {
        return hover.getContents().getRight().getValue();
    }

    private Hover hover(TestSupport t, String file, String text, String needle, int delta) throws Exception {
        return t.server.getTextDocumentService().hover(new org.eclipse.lsp4j.HoverParams(
            new org.eclipse.lsp4j.TextDocumentIdentifier(t.uri(file)), at(text, needle, 0, delta))).get();
    }

    @Test
    void hoverOnAnImportedComponentShowsSignatureSlotsVisibilityPackageAndFile(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        Hover hover = hover(t, "Page.sk", PAGE, "Card(title", 1);

        String md = text(hover);
        assertTrue(md.startsWith("```suko\n"), md);
        assertTrue(md.contains("public component Card("), md);
        assertTrue(md.contains("String title"), md);
        assertTrue(md.contains("Component footer = null"), md);
        assertTrue(md.contains("int size = 3"), md);
        assertTrue(md.contains("`header` — um bloco, obrigatório"), md);
        assertTrue(md.contains("`footer` — um bloco, opcional"), md);
        assertTrue(md.contains("`public`"), md);
        assertTrue(md.contains("package `ui`"), md);
        assertTrue(md.contains("`ui/Card.sk`"), md);
        assertEquals(7, hover.getRange().getStart().getLine());
    }

    @Test
    void manyCardinalitySlotWithoutDefaultIsNotRequired(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        assertTrue(text(hover(t, "Page.sk", PAGE, "Card(title", 1)).contains("`items` — vários blocos, opcional"));
    }

    @Test
    void privateLocalComponentIsSaidToBePrivateToTheFile(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        String md = text(hover(t, "Page.sk", PAGE, "Local(text", 1));

        assertTrue(md.contains("component Local(String text)"), md);
        assertFalse(md.contains("public component"), md);
        assertTrue(md.contains("privado a este ficheiro"), md);
        assertTrue(md.contains("`Page.sk`"), md);
    }

    @Test
    void hoverOnTheImportLineAndOnArgumentAndSlotNames(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        assertTrue(text(hover(t, "Page.sk", PAGE, "ui.Card", 4)).contains("public component Card("));

        String arg = text(hover(t, "Page.sk", PAGE, "title", 1));
        assertTrue(arg.contains("String title"), arg);
        assertTrue(arg.contains("parâmetro de `Card`"), arg);
        assertFalse(arg.contains("slot de"), arg);

        String slot = text(hover(t, "Page.sk", PAGE, "header", 1));
        assertTrue(slot.contains("Component header"), slot);
        assertTrue(slot.contains("um bloco, obrigatório"), slot);
        assertTrue(slot.contains("slot de `Card`"), slot);
    }

    @Test
    void longSignaturesAreBrokenOverLines(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String md = text(hover(t, "Page.sk", PAGE, "Card(title", 1));
        assertTrue(md.contains("component Card(\n    String title,\n"), md);
        assertTrue(md.contains("\n)\n```"), md);
    }

    @Test
    void aPrivateComponentFromAnotherFileIsNotCalledPrivateToThisFile(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.write("ui/Hidden.sk", "package ui;\n\ncomponent Hidden() {\n  <i>h</i>\n}\n");
        String page = "component P() {\n  ui.Hidden()\n}\n";
        t.write("P.sk", page);

        String md = text(hover(t, "P.sk", page, "ui.Hidden", 4));

        assertTrue(md.contains("privado (só visível em `ui/Hidden.sk`)"), md);
        assertFalse(md.contains("privado a este ficheiro"), md);
    }

    @Test
    void childrenAndRenderPropSlotsExplainThemselves(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.write("Box.sk", "public component Box(Component children, Function<String, Component> row) {\n  <div>${children}</div>\n}\n");
        String page = "import Box;\n\ncomponent P() {\n  Box() { <b>x</b> }\n}\n";
        t.write("P.sk", page);

        String md = text(hover(t, "P.sk", page, "Box()", 1));

        assertTrue(md.contains("`children` — o corpo da chamada `{ … }`, obrigatório"), md);
        assertTrue(md.contains("`row` — bloco que recebe um valor (render-prop), obrigatório"), md);
    }

    @Test
    void nothingToShowGivesNoHover(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String source = "component Lost() {\n  <p>just text</p>\n  Nope()\n}\n";
        t.write("Lost.sk", source);
        assertNull(hover(t, "Lost.sk", source, "just", 1));
        assertNull(hover(t, "Lost.sk", source, "Nope", 1));
    }
}
