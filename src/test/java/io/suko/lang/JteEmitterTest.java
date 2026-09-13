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
}
