package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JteEmitterTest {

    @Test
    void rendersStaticParagraphWithInterpolatedParam() throws Exception {
        String source = """
            component Greeting(String name) {
              <p>Hello, {name}!</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Greeting", Map.of("name", "World"));

        // O .jte gerado tem uma linha em branco entre a declaração @param e o
        // corpo do template (ver JteEmitter.emit) — o motor gg.jte preserva
        // essa quebra de linha tal como escrita no template, por isso a
        // saída renderizada começa com "\n" antes do <p>. Ajustado à saída
        // real observada (brief, Step 6), não a uma suposição sobre o
        // comportamento do gg.jte.
        assertEquals("\n<p>Hello, World!</p>", html);
    }

    @Test
    void preservesWhitespaceImmediatelyAfterInterpolation() throws Exception {
        // Regressão: o espaço entre "{name}" e "!" fica, no fonte, logo a
        // seguir ao "}" que fecha a interpolação — não pertence a nenhum
        // token (WS é sempre `-> skip`, ver textOf() em SukoAstBuilder), por
        // isso o textRun seguinte (" !") precisa de recuar o seu início para
        // o capturar. Sem essa correção o espaço desaparecia: "World!" em vez
        // de "World !".
        String source = """
            component Greeting(String name) {
              <p>{name} !</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Greeting", Map.of("name", "World"));

        assertEquals("\n<p>World !</p>", html);
    }
}
