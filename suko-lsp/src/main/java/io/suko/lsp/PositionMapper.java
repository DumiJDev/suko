package io.suko.lsp;

import io.suko.lang.ast.SourceSpan;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;

import java.util.ArrayList;
import java.util.List;

/**
 * Único ponto de conversão entre as posições do compilador e as do LSP,
 * por documento. As duas convenções diferem em três coisas:
 * <ul>
 *   <li>linhas: ANTLR 1-based, LSP 0-based;</li>
 *   <li>colunas e índices absolutos: o ANTLR conta <b>code points</b>, o LSP
 *       conta unidades <b>UTF-16</b> (um emoji vale 1 no ANTLR e 2 no LSP);</li>
 *   <li>fim: {@code SourceSpan.endIndex} é inclusivo, {@code Range.end} exclusivo.</li>
 * </ul>
 * Linhas são delimitadas por {@code \n} (o ANTLR só conta esse); um {@code \r}
 * antes dele (CRLF) fica no fim da linha anterior, onde não atrapalha. Um
 * {@code \r} isolado não é fim de linha aqui — divergiria do lexer.
 */
final class PositionMapper {

    private final String text;
    /** Offset UTF-16 do início de cada linha (índice 0 = linha 1 do ANTLR). */
    private final int[] lineStarts;
    private final boolean hasSurrogates;

    PositionMapper(String text) {
        this.text = text;
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        boolean surrogates = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                starts.add(i + 1);
            } else if (Character.isSurrogate(c)) {
                surrogates = true;
            }
        }
        this.lineStarts = starts.stream().mapToInt(Integer::intValue).toArray();
        this.hasSurrogates = surrogates;
    }

    /** Posição LSP de um índice em code points (o {@code startIndex} de um {@link SourceSpan}). */
    Position positionOfCodePoint(int codePointIndex) {
        return positionOfChar(charOffset(codePointIndex));
    }

    /** Posição LSP de um offset UTF-16. */
    Position positionOfChar(int charOffset) {
        int offset = Math.max(0, Math.min(charOffset, text.length()));
        int line = lineIndexOf(offset);
        return new Position(line, offset - lineStarts[line]);
    }

    /**
     * Intervalo LSP de um span. Spans com índices ({@code >= 0}) usam-nos; os do
     * {@code SukoErrorListener} têm índices {@code -1} e só linha/coluna
     * (coluna em code points), e cobrem o code point apontado.
     */
    Range rangeOf(SourceSpan span) {
        if (span.startLine() <= 0) {
            // (0,0,0,0) é o "sem posição" do compilador (ex.: AST_BUILDER_ERROR)
            return new Range(new Position(0, 0), new Position(0, 0));
        }
        if (span.startIndex() >= 0 && span.endIndex() >= span.startIndex()) {
            int start = charOffset(span.startIndex());
            int end = charOffset(span.endIndex() + 1);
            return new Range(positionOfChar(start), positionOfChar(end));
        }
        int lineIndex = Math.min(span.startLine() - 1, lineStarts.length - 1);
        int lineStart = lineStarts[lineIndex];
        int lineEnd = lineIndex + 1 < lineStarts.length ? lineStarts[lineIndex + 1] - 1 : text.length();
        int start = Math.min(lineStart + offsetByCodePointsClamped(lineStart, span.startColumn(), lineEnd), lineEnd);
        int end = start < lineEnd ? start + Character.charCount(text.codePointAt(start)) : start;
        return new Range(positionOfChar(start), positionOfChar(end));
    }

    /** Offset UTF-16 → índice em code points (para comparar com {@code SourceSpan.startIndex}). */
    int codePointIndexAt(Position position) {
        int offset = charOffsetAt(position);
        return hasSurrogates ? text.codePointCount(0, offset) : offset;
    }

    /** Offset UTF-16 de uma posição LSP, com clamp ao documento e ao fim da linha. */
    int charOffsetAt(Position position) {
        int line = Math.max(0, Math.min(position.getLine(), lineStarts.length - 1));
        int lineStart = lineStarts[line];
        int lineEnd = line + 1 < lineStarts.length ? lineStarts[line + 1] - 1 : text.length();
        return Math.min(lineStart + Math.max(0, position.getCharacter()), Math.max(lineStart, lineEnd));
    }

    int lineCount() {
        return lineStarts.length;
    }

    private int charOffset(int codePointIndex) {
        if (!hasSurrogates) {
            return Math.max(0, Math.min(codePointIndex, text.length()));
        }
        int clamped = Math.max(0, Math.min(codePointIndex, text.codePointCount(0, text.length())));
        return text.offsetByCodePoints(0, clamped);
    }

    private int offsetByCodePointsClamped(int lineStart, int codePoints, int lineEnd) {
        int maxCodePoints = text.codePointCount(lineStart, lineEnd);
        int n = Math.max(0, Math.min(codePoints, maxCodePoints));
        return text.offsetByCodePoints(lineStart, n) - lineStart;
    }

    private int lineIndexOf(int offset) {
        int lo = 0;
        int hi = lineStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (lineStarts[mid] <= offset) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }
}
