package io.suko.lsp;

import io.suko.lang.ast.SourceSpan;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PositionMapperTest {

    private static Range range(int sl, int sc, int el, int ec) {
        return new Range(new Position(sl, sc), new Position(el, ec));
    }

    /** ANTLR: linha 1-based, coluna em code points, índices em code points, fim inclusivo. */
    private static SourceSpan span(String text, String needle) {
        int charStart = text.indexOf(needle);
        int start = text.codePointCount(0, charStart);
        int end = start + needle.codePointCount(0, needle.length()) - 1;
        int line = 1 + (int) text.substring(0, charStart).chars().filter(c -> c == '\n').count();
        int lineStart = text.lastIndexOf('\n', charStart - 1) + 1;
        int column = text.codePointCount(lineStart, charStart);
        return new SourceSpan(line, column, start, end);
    }

    @Test
    void asciiSpanBecomesZeroBasedExclusiveRange() {
        String text = "component A() {\n  <p>${x}</p>\n}\n";
        assertEquals(range(1, 5, 1, 9), new PositionMapper(text).rangeOf(span(text, "${x}")));
    }

    @Test
    void accentedCharactersCountOneUnitInBothConventions() {
        String text = "component Á() {\n  <p>ação ${x}</p>\n}\n";
        assertEquals(range(1, 10, 1, 14), new PositionMapper(text).rangeOf(span(text, "${x}")));
    }

    @Test
    void charactersOutsideTheBmpCountTwoLspUnitsButOneAntlrCodePoint() {
        String text = "component A() {\n  <p>😀😀 ${x}</p>\n}\n";
        SourceSpan span = span(text, "${x}");
        // ANTLR: '  <p>' (5) + 2 emojis + espaço = coluna 8; LSP: 5 + 4 + 1 = 10
        assertEquals(8, span.startColumn());
        assertEquals(range(1, 10, 1, 14), new PositionMapper(text).rangeOf(span));
    }

    @Test
    void indexBasedRangeSurvivesEmojisOnEarlierLines() {
        String text = "// 😀\ncomponent A() {\n}\n";
        assertEquals(range(1, 10, 1, 11), new PositionMapper(text).rangeOf(span(text, "A")));
    }

    @Test
    void crlfLineBreaksDoNotShiftLaterLines() {
        String text = "component A() {\r\n  <p>x</p>\r\n}\r\n";
        assertEquals(range(1, 2, 1, 5), new PositionMapper(text).rangeOf(span(text, "<p>")));
        assertEquals(range(2, 0, 2, 1), new PositionMapper(text).rangeOf(span(text, "}")));
    }

    @Test
    void parseErrorSpansWithoutIndicesUseLineAndColumn() {
        String text = "component A() {\n  <p>😀 ${x</p>\n}\n";
        // 'linha 2, coluna 6 (code points)' aponta para o code point do emoji
        assertEquals(range(1, 5, 1, 7), new PositionMapper(text).rangeOf(new SourceSpan(2, 5, -1, -1)));
        // depois do emoji: coluna ANTLR 6 == LSP 7
        assertEquals(range(1, 7, 1, 8), new PositionMapper(text).rangeOf(new SourceSpan(2, 6, -1, -1)));
    }

    @Test
    void parseErrorAtEndOfLineOrDocumentGivesAnEmptyRange() {
        String text = "component A() {\n  <p>";
        PositionMapper mapper = new PositionMapper(text);
        assertEquals(range(1, 5, 1, 5), mapper.rangeOf(new SourceSpan(2, 5, -1, -1)));
        assertEquals(range(1, 5, 1, 5), mapper.rangeOf(new SourceSpan(2, 99, -1, -1)));
        // linha para lá do fim: assenta na última linha
        assertEquals(range(1, 0, 1, 1), mapper.rangeOf(new SourceSpan(99, 0, -1, -1)));
    }

    @Test
    void unknownLocationMapsToTheStartOfTheDocument() {
        assertEquals(range(0, 0, 0, 0), new PositionMapper("component A() {}").rangeOf(new SourceSpan(0, 0, 0, 0)));
    }

    @Test
    void positionToCodePointIndexRoundTrips() {
        String text = "😀a\nb😀c\n";
        PositionMapper mapper = new PositionMapper(text);
        // 'c' está na linha 1, unidade UTF-16 3; code points até lá: 😀 a \n b 😀 = 5
        assertEquals(5, mapper.codePointIndexAt(new Position(1, 3)));
        assertEquals(new Position(1, 3), mapper.positionOfCodePoint(5));
        assertEquals(new Position(0, 2), mapper.positionOfCodePoint(1));
    }

    @Test
    void outOfRangePositionsAreClamped() {
        PositionMapper mapper = new PositionMapper("ab\ncd");
        assertEquals(2, mapper.charOffsetAt(new Position(0, 99)));
        assertEquals(5, mapper.charOffsetAt(new Position(9, 9)));
        assertEquals(0, mapper.charOffsetAt(new Position(-1, -1)));
    }
}
