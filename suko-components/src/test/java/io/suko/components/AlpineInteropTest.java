package io.suko.components;

import gg.jte.Content;
import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render real (motor {@code gg.jte}) de {@code Dialog} (Tarefa 8,
 * subprojeto 7) — o único componente da biblioteca com
 * {@code externalRequirements} de Alpine.js. É a prova empírica, com um
 * componente real (não um exemplo sintético mínimo), da decisão de
 * sintaxe do subprojeto 6 de rejeitar chaveta nua como interpolação: a
 * interpolação Suko dentro de um literal string exige {@code $}
 * ({@code ${expr}} ou {@code $ident} — ver {@code SukoLexer.g4},
 * {@code STRING_MODE}), por isso um valor de atributo Alpine como
 * {@code x-data="{ open: false }"}, que não contém nenhum {@code $},
 * sobrevive ao emit inteiramente literal — o JavaScript de Alpine nunca é
 * confundido com um ponto de interpolação Suko.
 *
 * <p>Três asserções, correspondendo aos três pontos do brief:
 * <ol>
 *   <li>(a) {@code x-data="{ open: false }"} sai literal no HTML;</li>
 *   <li>(b) um {@code ${expr}} real, no mesmo componente (o atributo
 *       {@code id="dialog-${id}"}), é de facto interpolado;</li>
 *   <li>(c) conteúdo interpolado ({@code ${title}}, corpo HTML) é
 *       escapado — payload {@code <script>} não pode sobreviver cru.</li>
 * </ol>
 */
class AlpineInteropTest {

    private static final Path UI_DIR = Path.of("src", "main", "suko", "io", "suko", "ui");

    @Test
    void xDataSurvivesLiteralWhileDollarInterpolationAndEscapingBothWork() throws IOException {
        Content children = out -> out.writeContent("Tens a certeza?");
        Map<String, Object> params = Map.of(
            "id", "confirm",
            "title", "<script>alert(1)</script>",
            "children", children
        );

        String html = render("Dialog", params);

        // (a) Alpine x-data: literal, sem "$", não pode ser tratado como
        // interpolação Suko — tem de sair carácter-por-carácter.
        assertTrue(html.contains("x-data=\"{ open: false }\""),
            () -> "x-data tem de sair literal, sem ser interpolado: " + html);
        assertTrue(html.contains("x-show=\"open\""),
            () -> "x-show tem de sair literal: " + html);

        // (b) "${id}" É interpolação Suko real (dentro do MESMO
        // componente que tem o x-data literal ao lado) — o valor do
        // parâmetro id tem de aparecer no atributo id do <div>.
        assertTrue(html.contains("id=\"dialog-confirm\""),
            () -> "${id} tem de ser interpolado dentro do atributo id: " + html);

        // (c) ${title} é interpolação de conteúdo (contexto HTML body) —
        // tem de ser escapada, nunca sair como HTML/JS cru.
        assertTrue(html.contains("&lt;script&gt;"),
            () -> "conteúdo interpolado de title tem de ser escapado: " + html);
        assertFalse(html.contains("<script>alert(1)</script>"),
            () -> "payload não escapado não pode sobreviver no output: " + html);

        // children (children reais, não title) continuam a aparecer.
        assertTrue(html.contains("Tens a certeza?"), () -> "children tem de aparecer: " + html);
    }

    /**
     * Fix round 1 (revisão de segurança da Tarefa 8): codifica como teste
     * permanente da suite o achado Important do {@code security-specialist}
     * — confirmado à parte, com um teste descartável já removido, que o
     * {@code gg.jte} escapa aspas em {@code ${id}} no contexto de
     * atributo HTML, impedindo que um {@code id} malicioso feche o
     * atributo e injete um atributo HTML novo (ex. {@code onmouseover}).
     * Payload exato usado na revisão: {@code confirm" onmouseover="alert(1)}.
     */
    @Test
    void idAttributeEscapesQuotesAndCannotBreakOutToInjectAnAttribute() throws IOException {
        Content children = out -> out.writeContent("Tens a certeza?");
        Map<String, Object> params = Map.of(
            "id", "confirm\" onmouseover=\"alert(1)",
            "title", "Confirmar",
            "children", children
        );

        String html = render("Dialog", params);

        // A aspa do valor de id tem de sair escapada (contexto de
        // atributo HTML do gg.jte) — nunca uma aspa literal, que
        // fecharia o atributo id em aberto.
        assertTrue(html.contains("&#34;"),
            () -> "aspa do payload tem de ser escapada (contexto de atributo HTML): " + html);

        // O atributo id inteiro, com o payload escapado dentro do MESMO
        // valor entre aspas — onmouseover nunca vira um atributo HTML
        // real, só texto dentro do valor de id.
        assertTrue(html.contains("id=\"dialog-confirm&#34; onmouseover=&#34;alert(1)\""),
            () -> "payload tem de permanecer inteiro, escapado, dentro do valor de id: " + html);

        // Não pode existir "onmouseover=\"" como atributo HTML real (fora
        // do valor escapado de id) — só a forma escapada acima.
        assertFalse(html.contains("onmouseover=\"alert(1)\""),
            () -> "onmouseover não pode sobreviver como atributo HTML real (aspas não escapadas): " + html);
    }

    private static String render(String componentName, Map<String, Object> params) throws IOException {
        String source = Files.readString(UI_DIR.resolve(componentName + ".sk"));
        return JteRenderSupport.render(source, componentName, params);
    }
}
