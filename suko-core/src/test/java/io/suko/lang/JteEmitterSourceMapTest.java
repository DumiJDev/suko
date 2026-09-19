package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JteEmitterSourceMapTest {

    @Test
    void mapsTopLevelStatementsToTheirSukoLine() {
        String source = """
            component Greeting(String name) {
              <p>Hello, {name}!</p>
              <p>Bye</p>
            }
            """;

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());
        ComponentDecl component = file.components().get(0);

        JteEmitter.EmitResult result = new JteEmitter().emitWithSourceMap(component);

        assertFalse(result.sourceMap().isEmpty());
        // A primeira statement de topo ("<p>Hello...") começa na linha 2 do .sk.
        assertEquals(2, result.sourceMap().get(0).sukoSpan().startLine());
        // A segunda ("<p>Bye</p>") começa na linha 3 do .sk.
        assertEquals(3, result.sourceMap().get(1).sukoSpan().startLine());
    }
}
