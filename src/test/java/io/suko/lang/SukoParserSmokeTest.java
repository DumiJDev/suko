package io.suko.lang;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test: parseia examples/Card.sk de ponta a ponta usando o
 * Lexer/Parser gerados pelo ANTLR a partir de SukoLexer.g4 /
 * SukoParser.g4, e falha se houver qualquer erro de sintaxe.
 *
 * Isso valida em código o que só pôde ser checado estaticamente
 * (geração do ANTLR sem erros/avisos) no ambiente onde a gramática
 * foi originalmente escrita — em especial o ponto mais arriscado:
 * texto misturado com for/if dentro de tags, e interpolação {expr}
 * dentro de conteúdo de tag.
 */
class SukoParserSmokeTest {

    @Test
    void parsesCardExampleWithoutErrors() throws IOException {
        Path file = Path.of("examples/Card.sk");
        String source = Files.readString(file);

        List<String> errors = new ArrayList<>();
        BaseErrorListener collector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                     int line, int charPositionInLine, String msg,
                                     RecognitionException e) {
                errors.add("line " + line + ":" + charPositionInLine + " " + msg);
            }
        };

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collector);

        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collector);

        SukoParser.CompilationUnitContext tree = parser.compilationUnit();

        // Imprime a árvore em formato LISP — útil pra inspecionar
        // visualmente se algo vier estruturalmente errado mesmo
        // sem erro de sintaxe (ex: um componentDecl "engoliu" o
        // próximo por engano).
        System.out.println(tree.toStringTree(parser));

        assertTrue(errors.isEmpty(), "Erros de parsing encontrados: " + errors);
        assertEquals(3, tree.componentDecl().size(),
            "esperado 3 componentDecl no Card.sk (Card, NavLink, Page)");
    }
}
