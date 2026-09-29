package io.suko.lsp;

import org.eclipse.lsp4j.Location;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static io.suko.lsp.TestSupport.at;
import static io.suko.lsp.TestSupport.slice;
import static org.junit.jupiter.api.Assertions.*;

class DefinitionTest {

    private static final String CARD = """
        package ui;

        public component Card(String title, Component header, List<Component> items) {
          <div>${title}</div>
        }
        """;

    private static final String PAGE = """
        import ui.Card;

        component Page() {
          Card(title = "x") {
            header { <b>h</b> }
          }
        }
        """;

    private TestSupport project(Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.write("ui/Card.sk", CARD);
        t.write("Page.sk", PAGE);
        return t;
    }

    @Test
    void componentNameInACallJumpsToTheDeclarationInAnotherFile(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<Location> result = t.definition("Page.sk", at(PAGE, "Card(title", 0, 2));

        assertEquals(1, result.size());
        assertEquals(t.uri("ui/Card.sk"), result.get(0).getUri());
        assertEquals("Card", slice(CARD, result.get(0).getRange()));
        assertEquals(2, result.get(0).getRange().getStart().getLine());
    }

    @Test
    void importedNameInTheImportLineJumpsToo(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<Location> result = t.definition("Page.sk", at(PAGE, "ui.Card", 0, 4));

        assertEquals(t.uri("ui/Card.sk"), result.get(0).getUri());
        assertEquals("Card", slice(CARD, result.get(0).getRange()));
    }

    @Test
    void argumentNameJumpsToTheParameterAndSlotNameToTheSlotParameter(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        Location arg = t.definition("Page.sk", at(PAGE, "title", 0, 1)).get(0);
        assertEquals(t.uri("ui/Card.sk"), arg.getUri());
        assertEquals("title", slice(CARD, arg.getRange()));

        Location slot = t.definition("Page.sk", at(PAGE, "header", 0, 1)).get(0);
        assertEquals("header", slice(CARD, slot.getRange()));
    }

    @Test
    void callInTheSameFileJumpsToTheLocalDeclaration(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        String source = "component Badge(String text) {\n  <b>${text}</b>\n}\n\ncomponent Home() {\n  Badge(text = \"x\")\n}\n";
        t.write("Home.sk", source);

        List<Location> byCall = t.definition("Home.sk", at(source, "Badge(text", 0, 1));
        assertEquals(t.uri("Home.sk"), byCall.get(0).getUri());
        assertEquals(0, byCall.get(0).getRange().getStart().getLine());

        List<Location> byArg = t.definition("Home.sk", at(source, "text =", 0, 0));
        assertEquals("text", slice(source, byArg.get(0).getRange()));
        assertEquals(0, byArg.get(0).getRange().getStart().getLine());
    }

    @Test
    void qualifiedNameWithoutImportAndComponentUsedAsValueResolve(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String page = "component Other() {\n  ui.Card(title = \"x\") { header { <b>h</b> } }\n  var c = ui.Card(title = \"y\");\n}\n";
        t.write("Other.sk", page);

        assertEquals(t.uri("ui/Card.sk"), t.definition("Other.sk", at(page, "ui.Card", 0, 3)).get(0).getUri());

        String valuePage = "import ui.Card;\n\ncomponent V() {\n  var c = Card(\"y\");\n}\n";
        t.open("V.sk", valuePage);
        assertEquals(t.uri("ui/Card.sk"), t.definition("V.sk", at(valuePage, "Card(", 0, 1)).get(0).getUri());
    }

    @Test
    void definitionSeesUnsavedBuffersAndKeepsWorkingWhileTheFileIsBroken(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String edited = "import ui.Card;\n\ncomponent Page() {\n  Card(title = \"x\") {\n    header { ${t. }\n  }\n}\n";
        t.open("Page.sk", edited);

        List<Location> result = t.definition("Page.sk", at(edited, "Card(title", 0, 1));

        assertEquals(t.uri("ui/Card.sk"), result.get(0).getUri());
    }

    @Test
    void nothingUnderTheCursorOrUnknownNamesGiveNoLocations(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String source = "component Lost() {\n  <p>just text</p>\n  Nope()\n}\n";
        t.write("Lost.sk", source);

        assertTrue(t.definition("Lost.sk", at(source, "just", 0, 1)).isEmpty());
        assertTrue(t.definition("Lost.sk", at(source, "Nope", 0, 1)).isEmpty());
    }

    @Test
    void documentsOutsideAnySourceRootGiveNoLocations(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        var result = t.server.getTextDocumentService().definition(new org.eclipse.lsp4j.DefinitionParams(
            new org.eclipse.lsp4j.TextDocumentIdentifier(folder.resolve("elsewhere/X.sk").toUri().toString()),
            new org.eclipse.lsp4j.Position(0, 0))).get();
        assertTrue(result.getLeft().isEmpty());
    }
}
