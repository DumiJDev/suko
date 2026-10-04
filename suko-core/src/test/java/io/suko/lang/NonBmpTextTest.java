package io.suko.lang;

import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Os índices do ANTLR contam code points; um emoji antes do texto não pode deslocar o corte do TextRun. */
class NonBmpTextTest {

    @Test
    void textAfterAnEmojiIsEmittedWhole() {
        var result = new SukoProjectCompiler().compile(SukoSources.of(Path.of("/root"), Map.of(
            Path.of("A.sk"), "component A() {\n  <p>😀 ola mundo</p>\n  <i>😀😀 fim</i>\n}\n")));

        assertTrue(result.success(), result.diagnosticsByFile().toString());
        String jte = result.generatedJteSources().get(Path.of("A.jte"));
        assertTrue(jte.contains("<p>😀 ola mundo</p>"), jte);
        assertTrue(jte.contains("<i>😀😀 fim</i>"), jte);
    }
}
