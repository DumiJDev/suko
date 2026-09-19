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
 * Smoke test: parseia exemplos .sk de ponta a ponta usando o
 * Lexer/Parser gerados pelo ANTLR a partir de SukoLexer.g4 /
 * SukoParser.g4, e falha se houver qualquer erro de sintaxe.
 *
 * Isso valida em codigo o que so poderia ser checado estaticamente
 * (geracao do ANTLR sem erros/avisos) no ambiente onde a gramatica
 * foi originalmente escrita — em especial o ponto mais arriscado:
 * texto misturado com for/if dentro de tags, e interpolacao {expr}
 * dentro de conteudo de tag.
 */
class SukoParserSmokeTest {

    private void parseFile(String path, int expectedComponentCount, String expectedNames) throws IOException {
        Path file = Path.of(path);
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

        System.out.println(tree.toStringTree(parser));

        assertTrue(errors.isEmpty(), "Erros de parsing encontrados: " + errors);
        assertEquals(expectedComponentCount, tree.componentDecl().size(),
            "esperado " + expectedComponentCount + " componentDecl em " + path + " (" + expectedNames + ")");
    }

    @Test
    void parsesCardExampleWithoutErrors() throws IOException {
        parseFile("examples/Card.sk", 4, "Card, NavLink, Page, AdminPanel");
    }

    @Test
    void parsesDashboardExampleWithoutErrors() throws IOException {
        parseFile("examples/dashboard/Dashboard.sk", 4, "NavLink, Dashboard, AdminPanel, ManagerPanel");
    }

    @Test
    void parsesFormsExampleWithoutErrors() throws IOException {
        parseFile("examples/forms/Forms.sk", 2, "LoginForm, RegistrationForm");
    }

    @Test
    void parsesLayoutExampleWithoutErrors() throws IOException {
        parseFile("examples/layout/LayoutComponents.sk", 7, "Layout, Card, Modal, Button, Input, Select, ItemList");
    }

    @Test
    void parsesInterpolatedStringWithTrailingText() {
        String source = """
            component Greeting(String name) {
              <p>{"Olá ${name}!"}</p>
            }
            """;
        // Só precisa de não lançar exceção de parse — a tradução para AST/Java
        // é a Tarefa 8/9. Confirma que o lexer volta a STRING_MODE depois do
        // '}' de fecho e lexa "!" + STRING_END corretamente.
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            var lexer = new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(source));
            var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
            parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
            parser.compilationUnit();
        });
    }

    @Test
    void parsesPublicAndDefaultVisibilityComponentsWithoutErrors() {
        String source = """
            public component NavLink(String href) {
              <a href="{href}">link</a>
            }
            component Helper() {
              <span>x</span>
            }
            """;
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            var lexer = new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(source));
            var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
            parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
            SukoParser.CompilationUnitContext tree = parser.compilationUnit();
            assertEquals(2, tree.componentDecl().size());
            assertTrue(tree.componentDecl(0).PUBLIC() != null, "primeiro componente devia ter modificador public");
            assertTrue(tree.componentDecl(1).PUBLIC() == null, "segundo componente não devia ter modificador public");
        });
    }
}
