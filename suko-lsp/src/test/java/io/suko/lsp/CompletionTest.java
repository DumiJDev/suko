package io.suko.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CompletionTest {

    private static final String CARD = """
        package ui;

        public component Card(String title, Component header, List<Component> items, Component footer = null) {
          <div>${title}</div>
        }
        """;
    private static final String HIDDEN = "package ui;\n\ncomponent Hidden() {\n  <i>h</i>\n}\n";
    private static final String BADGE = "package ui;\n\npublic component Badge(String text) {\n  <b>${text}</b>\n}\n";

    /** Escreve o projecto de exemplo; o texto sob teste marca o cursor com {@code |}. */
    private TestSupport project(Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.write("ui/Card.sk", CARD);
        t.write("ui/Hidden.sk", HIDDEN);
        t.write("ui/Badge.sk", BADGE);
        return t;
    }

    private List<CompletionItem> complete(TestSupport t, String file, String textWithCursor) throws Exception {
        int bar = textWithCursor.indexOf('|');
        String text = textWithCursor.substring(0, bar) + textWithCursor.substring(bar + 1);
        int line = (int) text.substring(0, bar).chars().filter(c -> c == '\n').count();
        int column = bar - (text.lastIndexOf('\n', bar - 1) + 1);
        t.open(file, text);
        var result = t.server.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(t.uri(file)), new Position(line, column))).get();
        return result.getRight().getItems();
    }

    private static List<String> labels(List<CompletionItem> items) {
        return items.stream().map(CompletionItem::getLabel).collect(Collectors.toList());
    }

    private static CompletionItem item(List<CompletionItem> items, String label) {
        return items.stream().filter(i -> i.getLabel().equals(label)).findFirst()
            .orElseThrow(() -> new AssertionError(label + " não está em " + labels(items)));
    }

    private static TextEdit edit(CompletionItem item) {
        return item.getTextEdit().getLeft();
    }

    // ------------------------------------------------------------------ import

    @Test
    void afterImportOffersThePublicComponentsOfTheProjectAsQualifiedNames(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<CompletionItem> items = complete(t, "P.sk", "import ui.Ca|\n\ncomponent P() {\n  <p>x</p>\n}\n");

        assertTrue(labels(items).containsAll(List.of("ui.Card", "ui.Badge")), labels(items).toString());
        assertFalse(labels(items).contains("ui.Hidden"), "componente não public não é importável");
        TextEdit e = edit(item(items, "ui.Card"));
        assertEquals("ui.Card;", e.getNewText());
        assertEquals(new Position(0, 7), e.getRange().getStart(), "substitui o nome qualificado já escrito");
        assertEquals(new Position(0, 12), e.getRange().getEnd());
    }

    @Test
    void importCompletionSkipsWhatIsAlreadyImportedAndDoesNotDuplicateTheSemicolon(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<CompletionItem> items = complete(t, "P.sk", "import ui.Card;\nimport |;\n\ncomponent P() {\n  <p>x</p>\n}\n");

        assertFalse(labels(items).contains("ui.Card"));
        assertEquals("ui.Badge", edit(item(items, "ui.Badge")).getNewText());
    }

    // -------------------------------------------------------------------- body

    @Test
    void inTheBodyOffersVisibleComponentsAndKeywordsAndAutoImportsTheOthers(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String source = "import ui.Card;\n\ncomponent Local() {\n  <b>l</b>\n}\n\ncomponent P() {\n  |\n}\n";

        List<CompletionItem> items = complete(t, "P.sk", source);

        assertTrue(labels(items).containsAll(List.of("Local", "Card", "Badge", "if", "else", "for", "switch", "case", "default")),
            labels(items).toString());
        assertFalse(labels(items).contains("Hidden"));

        assertNull(item(items, "Card").getAdditionalTextEdits(), "já importado: sem edição extra");
        assertNull(item(items, "Local").getAdditionalTextEdits());

        CompletionItem badge = item(items, "Badge");
        assertEquals(1, badge.getAdditionalTextEdits().size());
        TextEdit importEdit = badge.getAdditionalTextEdits().get(0);
        assertEquals("\nimport ui.Badge;", importEdit.getNewText());
        assertEquals(new Position(0, 15), importEdit.getRange().getStart(), "logo a seguir ao `;` do último import");
        assertEquals("Badge($0)", edit(badge).getNewText());
    }

    @Test
    void autoImportGoesAfterThePackageOrAtTheTopWhenThereAreNoImports(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        var withPackage = complete(t, "sub/A.sk", "package sub;\n\ncomponent A() {\n  |\n}\n");
        TextEdit afterPackage = item(withPackage, "Badge").getAdditionalTextEdits().get(0);
        assertEquals("\n\nimport ui.Badge;", afterPackage.getNewText());
        assertEquals(new Position(0, 12), afterPackage.getRange().getStart());

        var bare = complete(t, "B.sk", "component B() {\n  |\n}\n");
        TextEdit atTop = item(bare, "Badge").getAdditionalTextEdits().get(0);
        assertEquals("import ui.Badge;\n\n", atTop.getNewText());
        assertEquals(new Position(0, 0), atTop.getRange().getStart());
    }

    @Test
    void typedPrefixIsReplacedEvenAfterCharactersOutsideTheBmp(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<CompletionItem> items = complete(t, "P.sk", "component P() {\n  <b>😀</b> Ba|\n}\n");

        TextEdit e = edit(item(items, "Badge"));
        // "  <b>😀</b> " = 2 + 3 + 2 (o emoji vale 2 unidades UTF-16) + 4 + 1
        assertEquals(new Position(1, 12), e.getRange().getStart());
        assertEquals(new Position(1, 14), e.getRange().getEnd());
    }

    @Test
    void suggestsInsideNestedBlocksAndAfterAClosingTag(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        assertTrue(labels(complete(t, "A.sk", "component A(List<String> xs) {\n  for (x : xs) {\n    |\n  }\n}\n")).contains("Badge"));
        assertTrue(labels(complete(t, "B.sk", "component B() {\n  if (true) {\n    <p>x</p>\n  } else {\n    |\n  }\n}\n")).contains("Badge"));
        assertTrue(labels(complete(t, "C.sk", "component C() {\n  <div>|</div>\n}\n")).contains("Badge"));
    }

    // --------------------------------------------------------------- arguments

    @Test
    void insideACallOffersTheParametersNotYetPassed(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String head = "import ui.Card;\n\ncomponent P() {\n";

        List<CompletionItem> first = complete(t, "P.sk", head + "  Card(|)\n}\n");
        assertEquals(4, first.size(), labels(first).toString());
        assertTrue(labels(first).containsAll(List.of("title", "header", "items", "footer")));
        assertEquals("title = ", edit(item(first, "title")).getNewText());
        assertEquals("String title", item(first, "title").getDetail());

        List<CompletionItem> rest = complete(t, "P.sk", head + "  Card(title = \"x\", |)\n}\n");
        assertFalse(labels(rest).contains("title"));
        assertTrue(labels(rest).containsAll(List.of("header", "items", "footer")));

        List<CompletionItem> typed = complete(t, "P.sk", head + "  Card(title = \"x\", he|)\n}\n");
        assertEquals(new Position(3, 20), edit(item(typed, "header")).getRange().getStart());
    }

    @Test
    void valueParametersComeBeforeSlotsAndValuePositionsGetNothing(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String head = "import ui.Card;\n\ncomponent P() {\n";

        List<CompletionItem> items = complete(t, "P.sk", head + "  Card(|)\n}\n");
        assertTrue(item(items, "title").getSortText().compareTo(item(items, "header").getSortText()) < 0);

        assertTrue(complete(t, "P.sk", head + "  Card(title = |)\n}\n").isEmpty(), "posição de valor: Java, do 11c");
        assertTrue(complete(t, "P.sk", head + "  Card(title = foo(|))\n}\n").isEmpty(), "chamada de método, não de componente");
    }

    @Test
    void argumentCompletionSurvivesAnUnfinishedCall(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        // `Card(` sem fecho: o parser engole isto como texto, os tokens não
        List<CompletionItem> items = complete(t, "P.sk", "import ui.Card;\n\ncomponent P() {\n  <div>\n    Card(|\n  </div>\n}\n");
        assertTrue(labels(items).contains("title"), labels(items).toString());
    }

    // ------------------------------------------------------------------- slots

    @Test
    void insideACallBlockOffersTheSlotsAsBlocksAndTheBodyCompletions(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<CompletionItem> items = complete(t, "P.sk", "import ui.Card;\n\ncomponent P() {\n  Card(title = \"x\") {\n    |\n  }\n}\n");

        assertTrue(labels(items).containsAll(List.of("header", "items", "footer")), labels(items).toString());
        assertEquals("header { $0 }", edit(item(items, "header")).getNewText());
        assertTrue(item(items, "header").getDetail().contains("obrigatório"));
        assertTrue(labels(items).contains("Badge"), "conteúdo solto também é válido no bloco");
        assertFalse(labels(items).contains("title"), "title é um parâmetro, não um slot");
    }

    @Test
    void insideASlotBodyThereAreNoSlotSuggestions(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);

        List<CompletionItem> items = complete(t, "P.sk",
            "import ui.Card;\n\ncomponent P() {\n  Card(title = \"x\") {\n    header {\n      |\n    }\n  }\n}\n");

        assertFalse(labels(items).contains("footer"));
        assertTrue(labels(items).contains("Badge"));
    }

    // ------------------------------------------------------------------- nada

    @Test
    void nothingIsSuggestedWhereACompletionWouldBeWrong(@TempDir Path folder) throws Exception {
        TestSupport t = project(folder);
        String head = "import ui.Card;\n\ncomponent P(String name) {\n";

        assertTrue(complete(t, "P.sk", head + "  <div cla|>x</div>\n}\n").isEmpty(), "atributo de tag");
        assertTrue(complete(t, "P.sk", head + "  <div class=\"a|\">x</div>\n}\n").isEmpty(), "dentro de uma string");
        assertTrue(complete(t, "P.sk", head + "  <p>${name.|}</p>\n}\n").isEmpty(), "Java em ${}: 11c");
        assertTrue(complete(t, "P.sk", head + "  <p>uma frase com Ba|</p>\n}\n").isEmpty(), "prosa");
        assertTrue(complete(t, "P.sk", head + "  // um comentário Ba|\n}\n").isEmpty(), "comentário");
        assertTrue(complete(t, "P.sk", "import ui.Card;\n\ncomponent P(Str|) {\n}\n").isEmpty(), "cabeçalho do componente");
        assertTrue(complete(t, "P.sk", head + "  if (na|) {\n  }\n}\n").isEmpty(), "condição");
        assertTrue(complete(t, "P.sk", "|").isEmpty(), "nível de topo");
    }
}
