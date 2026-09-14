package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compara o .jte gerado, byte a byte, com um fixture revisto à mão em
 * src/test/resources/golden/. Complementa os testes de render (que
 * validam comportamento, não o texto gerado) — uma mudança não
 * intencional de formatação no JteEmitter falha aqui mesmo que o HTML
 * final renderizado continue correto.
 *
 * O golden de "Card" cobre o componente genérico Card<T> real (nunca
 * compilado/renderizado via gg.jte — ver ARCHITECTURE.md, "Limitações
 * conhecidas (fim do subprojeto 1)" e a ruling da tarefa 19 no ledger).
 * O texto emitido contém "T" literal, não resolvido — comportamento
 * esperado, documentado aqui deliberadamente.
 */
class JteEmitterGoldenFileTest {

    @Test
    void cardMatchesGoldenFile() throws Exception {
        String sukoSource = Files.readString(Path.of("examples/Card.sk"));
        String actual = JteRenderSupport.compileToJte(sukoSource, "Card");
        String expected = Files.readString(Path.of("src/test/resources/golden/Card.jte"));
        assertEquals(expected, actual);
    }

    @Test
    void navLinkMatchesGoldenFile() throws Exception {
        String sukoSource = Files.readString(Path.of("examples/Card.sk"));
        String actual = JteRenderSupport.compileToJte(sukoSource, "NavLink");
        String expected = Files.readString(Path.of("src/test/resources/golden/NavLink.jte"));
        assertEquals(expected, actual);
    }
}
