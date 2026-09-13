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

    @Test
    void rendersArithmeticAndComparisonExpressions() throws Exception {
        String source = """
            component Sum(int a, int b) {
              <p>{a + b}</p>
              <p>{(a - b) * 2}</p>
              <p>{a > b}</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Sum", Map.of("a", 5, "b", 3));

        // Whitespace puramente entre tags (fim de linha + indentação entre
        // </p> e <p>) não gera nenhum textRun — ver comentário em
        // SukoAstBuilder.textOf() — por isso não aparece na saída; só o "\n"
        // inicial (entre @param e o corpo do template, ver
        // rendersStaticParagraphWithInterpolatedParam) permanece. Ajustado à
        // saída real observada (brief, Step 5), não a uma suposição sobre
        // como o .jte gerado deveria ficar.
        assertEquals("\n<p>8</p><p>4</p><p>true</p>", html);
    }

    @Test
    void unaryMinusBindsTighterThanBinaryMinus() throws Exception {
        // Fecha uma lacuna deixada pela tarefa 7: a gramática resolve
        // -1 + 2 como (-1) + 2 (não -(1 + 2)) pela ordem das alternativas do
        // ANTLR, e 1 - -1 deve emitir "1 - -1" (dois tokens de menos
        // separados por espaço), nunca "1 --1"/"1--1", que o javac
        // interpretaria como decremento ou erro de sintaxe. buildExpr/
        // emitExpr não fazem nada de especial para isto — apenas percorrem a
        // forma da árvore que a gramática já produz — mas nenhum teste
        // anterior tinha exercitado este caminho.
        String source = """
            component Neg(int a, int b) {
              <p>{-a + b}</p>
              <p>{a - -b}</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Neg", Map.of("a", 1, "b", 2));

        assertEquals("\n<p>1</p><p>3</p>", html);
    }
}
