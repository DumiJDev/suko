package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SukoAstSpansTest {

    private static final String SOURCE = """
        public component Card(String title, Component header) {
          <div>
            <h1>${title}</h1>
          </div>
        }

        component Page() {
          Card(title = "x") {
            header { <b>hi</b> }
          }
          ui.Other("positional")
        }
        """;

    private static String text(SourceSpan span) {
        return SOURCE.substring(span.startIndex(), span.endIndex() + 1);
    }

    private static SukoFile parse() {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(SOURCE));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(SOURCE).build(parser.compilationUnit());
    }

    @Test
    void declarationNameSpanCoversOnlyTheName() {
        ComponentDecl card = parse().components().get(0);
        assertEquals("Card", text(card.nameSpan()));
        assertEquals(1, card.nameSpan().startLine());
        assertEquals("public component".length() + 1, card.nameSpan().startColumn());
    }

    @Test
    void callNameArgAndSlotSpansCoverTheirText() {
        ComponentDecl page = parse().components().get(1);
        Statement.ComponentCallStmt card = (Statement.ComponentCallStmt) page.body().get(0);
        assertEquals("Card", text(card.nameSpan()));
        assertEquals("title", text(card.args().get(0).span()));
        assertEquals("header", text(card.slotFills().get(0).nameSpan()));

        Statement.ComponentCallStmt other = (Statement.ComponentCallStmt) page.body().get(1);
        assertEquals("ui.Other", text(other.nameSpan()));
        assertEquals("\"positional\"", text(other.args().get(0).span()));
    }
}
