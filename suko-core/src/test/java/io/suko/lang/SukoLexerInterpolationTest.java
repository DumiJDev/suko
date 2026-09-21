package io.suko.lang;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subprojeto 9 (D1/D2): o token `${` passa a ser emitido nas DUAS posições
 * — fora de string (DEFAULT_MODE, sem mudança de modo, ver C6 do plano) e
 * dentro de string (STRING_MODE, com pushMode). O mesmo TIPO de token nos
 * dois casos é o que permite ao parser ter uma única regra `interpolation`.
 */
class SukoLexerInterpolationTest {

    private static List<Token> tokens(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        return stream.getTokens();
    }

    private static long countOfType(String source, int type) {
        return tokens(source).stream().filter(t -> t.getType() == type).count();
    }

    @Test
    void dollarBraceOutsideStringIsExprInterpStart() {
        assertEquals(1, countOfType("component A() { ${x} }", SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void dollarBraceInsideStringIsTheSameTokenType() {
        assertEquals(1, countOfType("component A() { <p id=\"a ${x} b\">y</p> }",
            SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void loneDollarOutsideStringIsNotInterpolation() {
        // "R$ 10" em texto livre continua texto: `$` sozinho nunca foi, e
        // continua a não ser, início de interpolação fora de string.
        assertEquals(0, countOfType("component A() { <p>R$ 10</p> }", SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void simpleInterpStartSurvivesInsideStrings() {
        // D3 REJEITADA: `$ident` dentro de string mantém-se exatamente como
        // está. Este teste existe para falhar se alguém a remover por engano.
        assertEquals(1, countOfType("component A() { <p id=\"a $x b\">y</p> }",
            SukoLexer.SIMPLE_INTERP_START));
    }

    @Test
    void modeStackStaysBalancedAcrossBothPositions() {
        // C6: um `${` de DEFAULT_MODE que empurrasse modo deixaria a pilha
        // suja e o `}` do templateBlock faria um popMode indevido.
        List<Token> all = tokens("component A(String x, String y) { <p id=\"a ${y} b\">${x}</p> }");
        assertTrue(all.stream().anyMatch(t -> t.getType() == Token.EOF),
            "o lexer tem de chegar ao EOF sem rebentar na pilha de modos");
    }
}
