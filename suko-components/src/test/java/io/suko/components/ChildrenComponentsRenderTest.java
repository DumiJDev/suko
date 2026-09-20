package io.suko.components;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render real (motor {@code gg.jte}, nunca comparação de {@code .jte}) dos
 * dois componentes que exercitam {@code children} implícitos com conteúdo
 * de chamada real (Tarefa 7, subprojeto 7): {@code Alert} (conteúdo solto
 * MISTO — texto e HTML no mesmo bloco de chamada) e {@code Card}
 * ({@code Component children} obrigatório mais dois slots nomeados
 * opcionais, {@code header}/{@code footer}).
 *
 * <p>Ao contrário de {@link LeafComponentsRenderTest} (que injeta um
 * {@code Content} sintético construído à mão em Java), estes testes
 * compõem um ficheiro Suko completo — o {@code .sk} real da biblioteca
 * mais um componente {@code Page} local que faz a chamada com um bloco de
 * chamada de verdade — e usam
 * {@link JteRenderSupport#renderWithDependencies} para compilar os dois
 * componentes (Page + o componente da biblioteca) e resolver a chamada
 * aninhada. É isto que prova que o mecanismo de {@code children}
 * implícitos (subprojeto 6) funciona com conteúdo solto genuíno vindo do
 * parser, não só com um {@code Content} fabricado em teste.
 */
class ChildrenComponentsRenderTest {

    private static final Path UI_DIR = Path.of("src", "main", "suko", "io", "suko", "ui");

    @Test
    void alertCollectsMixedLooseTextAndHtmlIntoChildren() throws IOException {
        // Conteúdo solto MISTO: texto solto ("Erro:") seguido de um
        // elemento HTML (<strong>...</strong>) no mesmo bloco de chamada,
        // sem nenhum slot nomeado — o caso que o subprojeto 6 entregou
        // mas que nunca tinha sido exercitado com conteúdo de chamada
        // real (só com um Content sintético, em LeafComponentsRenderTest).
        String page = """
            component Page() {
              Alert(variant = "danger") {
                Erro: <strong>falha ao gravar</strong>
              }
            }
            """;

        String html = renderPageCalling("Alert", page);

        assertTrue(html.contains("bg-red-50"),
            () -> "variante \"danger\" tem de escrever a sua classe Tailwind completa: " + html);
        assertTrue(html.contains("Erro:"),
            () -> "texto solto do bloco de chamada tem de aparecer no children: " + html);
        assertTrue(html.contains("<strong>falha ao gravar</strong>"),
            () -> "elemento HTML solto do bloco de chamada tem de aparecer, não escapado, no children: " + html);
    }

    @Test
    void cardRendersOnlyChildrenWhenHeaderAndFooterOmitted() throws IOException {
        String page = """
            component Page() {
              Card() {
                <p>Só o corpo.</p>
              }
            }
            """;

        String html = renderPageCalling("Card", page);

        assertTrue(html.contains("Só o corpo."), () -> "children tem de aparecer: " + html);
        assertFalse(html.contains("border-b"), () -> "header omitido não pode renderizar a sua div: " + html);
        assertFalse(html.contains("border-t"), () -> "footer omitido não pode renderizar a sua div: " + html);
    }

    @Test
    void cardRendersChildrenAndHeaderWhenHeaderPrecedesLooseContent() throws IOException {
        // Convenção 4 (vinculante): o slot nomeado "header" aparece ANTES
        // do conteúdo solto ("<p>Corpo</p>") no bloco de chamada. A ordem
        // inversa (solto antes de nomeado) engole o slot nomeado em
        // silêncio — bug de gramática conhecido e documentado em
        // ARCHITECTURE.md, não corrigido aqui (ver
        // cardKnownLimitation_looseContentBeforeNamedSlotSwallowsHeader
        // abaixo, que documenta exatamente esse comportamento).
        String page = """
            component Page() {
              Card() {
                header { <h2>Titulo</h2> }
                <p>Corpo</p>
              }
            }
            """;

        String html = renderPageCalling("Card", page);

        assertTrue(html.contains("border-b"), () -> "header fornecido tem de renderizar a sua div: " + html);
        assertTrue(html.contains("<h2>Titulo</h2>"), () -> "conteúdo do slot header tem de aparecer: " + html);
        assertTrue(html.contains("Corpo"), () -> "children tem de aparecer: " + html);
        assertFalse(html.contains("border-t"), () -> "footer omitido não pode renderizar a sua div: " + html);
    }

    @Test
    void cardRendersChildrenHeaderAndFooterWhenBothNamedSlotsPrecedeLooseContent() throws IOException {
        // Mesma convenção 4: os dois slots nomeados ("header", "footer")
        // aparecem ANTES do conteúdo solto no bloco de chamada.
        String page = """
            component Page() {
              Card() {
                header { <h2>Titulo</h2> }
                footer { <small>Rodape</small> }
                <p>Corpo</p>
              }
            }
            """;

        String html = renderPageCalling("Card", page);

        assertTrue(html.contains("border-b"), () -> "header fornecido tem de renderizar a sua div: " + html);
        assertTrue(html.contains("<h2>Titulo</h2>"), () -> "conteúdo do slot header tem de aparecer: " + html);
        assertTrue(html.contains("border-t"), () -> "footer fornecido tem de renderizar a sua div: " + html);
        assertTrue(html.contains("<small>Rodape</small>"), () -> "conteúdo do slot footer tem de aparecer: " + html);
        assertTrue(html.contains("Corpo"), () -> "children tem de aparecer: " + html);
    }

    /**
     * Passo 4 do brief (diagnóstico, NÃO correção — regra D5 do plano:
     * só a correção da tarefa 1 do subprojeto 7 está pré-aprovada).
     *
     * <p>Este teste documenta, com um {@code Card} real (não um exemplo
     * sintético mínimo), o comportamento ATUAL — não desejado, uma
     * limitação de gramática conhecida — da ordem "solto-antes-de-
     * nomeado": quando conteúdo solto aparece ANTES do slot nomeado
     * {@code header} no mesmo bloco de chamada, o {@code textRun}
     * (regra léxica gulosa {@code (~(LBRACE|RBRACE|LT|LTSLASH))+}, sem
     * lookahead intermédio) engole o {@code Identifier} de {@code header}
     * como se fosse mais texto solto. O que sobra, {@code { "Titulo" }},
     * deixa de ser reconhecido como {@code namedSlot} e passa a ser lido
     * como uma {@code interpolation} comum — e o texto literal "header"
     * (a palavra, não o slot) acaba dentro do próprio {@code children}.
     * Resultado observado: a div do header NÃO é renderizada (o
     * parâmetro {@code header} chega como {@code null}, o valor por
     * omissão, porque o fill nunca foi sintetizado); a palavra "header" e
     * o texto do que deveria ser o conteúdo do slot aparecem, ao
     * contrário, dentro da div do {@code children}.
     *
     * <p>Documentado em {@code ARCHITECTURE.md} → "Limitações
     * conhecidas" e listado em
     * {@code docs/superpowers/specs/2026-09-20-suko-registry-componentes.md}
     * (D5) como não corrigível dentro deste subprojeto. A convenção de
     * autoria vinculante (convenção 4) é precisamente contorná-lo:
     * escrever sempre os slots nomeados antes do conteúdo solto.
     */
    @Test
    void cardKnownLimitation_looseContentBeforeNamedSlotSilentlySwallowsHeader() throws IOException {
        // Nota: a reprodução exige que o conteúdo solto ANTES do slot
        // nomeado seja texto puro (não envolto em HTML) — um elemento
        // HTML fechado (ex. "<p>...</p>") introduz uma fronteira de
        // token (LTSLASH) que interrompe o textRun guloso e o bug não se
        // manifesta (confirmado empiricamente ao desenhar este teste).
        String page = """
            component Page() {
              Card() {
                "Corpo solto" header { "Titulo" }
              }
            }
            """;

        String html = renderPageCalling("Card", page);

        // Comportamento atual (não desejado): a div do header não existe.
        assertFalse(html.contains("border-b"),
            () -> "limitação conhecida: a div do header não deve aparecer (o fill nunca chega ao componente): " + html);
        // A palavra "header" e o texto do que deveria ser o slot ficam,
        // em vez disso, dentro do children.
        assertTrue(html.contains("header"),
            () -> "limitação conhecida: a palavra 'header' é engolida como texto solto, dentro do children: " + html);
        assertTrue(html.contains("Titulo"),
            () -> "limitação conhecida: o conteúdo do que deveria ser o slot header aparece dentro do children: " + html);
    }

    /** Lê o {@code .sk} real da biblioteca, remove a linha {@code package}
     * (irrelevante para {@link JteRenderSupport#renderWithDependencies},
     * que compila um único ficheiro em memória sem projeto/pacotes), e
     * junta um componente {@code Page} local que faz a chamada com um
     * bloco de chamada de verdade — depois renderiza {@code Page}. */
    private static String renderPageCalling(String libraryComponentName, String pageSource) throws IOException {
        String librarySource = Files.readString(UI_DIR.resolve(libraryComponentName + ".sk"))
            .replaceFirst("(?m)^package [^;]+;\\n", "");

        String combined = librarySource + "\n" + pageSource;
        return JteRenderSupport.renderWithDependencies(combined, "Page", Map.of());
    }
}
