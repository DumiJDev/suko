package io.suko.lang;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Casos-limite da gramática que a sondagem manual (ver ARCHITECTURE.md /
 * spec do subprojeto 1) encontrou como falhas. Cada teste corresponde a
 * uma correção pontual na gramática — só verifica ausência de erro de
 * parsing, não a árvore resultante (isso é coberto pelos testes de
 * AST/emitter mais à frente).
 */
class SukoGrammarFixesTest {

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

    @Test
    void booleanLiteralInExpression() {
        List<String> errors = parseErrors("component A() { if (true) { <p>x</p> } }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void hyphenatedTagAndAttributeNames() {
        List<String> errors = parseErrors(
            "component A() { <div data-id=\"1\">x</div> <my-button>y</my-button> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
}
