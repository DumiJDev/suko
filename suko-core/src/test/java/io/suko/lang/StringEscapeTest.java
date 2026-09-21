package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Escapes dentro de literais de string Suko. Antes do subprojeto 9 não
 * existia um único teste sobre isto (C7 do plano) e `\$` produzia Java
 * inválido — "illegal escape character" no javac. Com `${...}` a ser o
 * único sigilo de interpolação em todas as posições (D1), `\$` passou a
 * ser a única saída de emergência do autor para escrever um `$` literal.
 */
class StringEscapeTest {

    @Test
    void escapedDollarRendersAsLiteralDollar() throws Exception {
        String source = """
            component Price() {
              <p>${"custa \\$5"}</p>
            }
            """;
        assertEquals("<p>custa $5</p>", JteRenderSupport.render(source, "Price", Map.of()).trim());
    }

    @Test
    void escapedDollarInAttributeRendersAsLiteralDollar() throws Exception {
        String source = """
            component Price() {
              <p title="custa \\$5">x</p>
            }
            """;
        assertEquals("<p title=\"custa $5\">x</p>",
            JteRenderSupport.render(source, "Price", Map.of()).trim());
    }

    @Test
    void escapedQuoteStillWorks() throws Exception {
        String source = """
            component Q() {
              <p>${"diz \\"oi\\""}</p>
            }
            """;
        // Valor observado no motor gg.jte real (ContentType.Html): aspas
        // dentro de conteúdo de texto (não de atributo) não são escapadas
        // — só `<`, `>` e `&` o são em `htmlContent`; `"` só precisa de
        // escape dentro de um valor de atributo. Ver ledger do
        // subprojeto 9, task 5.
        assertEquals("<p>diz \"oi\"</p>", JteRenderSupport.render(source, "Q", Map.of()).trim());
    }

    @Test
    void escapedBackslashStillWorks() throws Exception {
        String source = """
            component B() {
              <p>${"c:\\\\tmp"}</p>
            }
            """;
        assertEquals("<p>c:\\tmp</p>", JteRenderSupport.render(source, "B", Map.of()).trim());
    }

    @Test
    void unescapedDollarIdentStillInterpolates() throws Exception {
        // D3 REJEITADA: o escape não pode ter partido a forma curta.
        String source = """
            component Greet(String name) {
              <p title="ola $name">x</p>
            }
            """;
        assertEquals("<p title=\"ola Ana\">x</p>",
            JteRenderSupport.render(source, "Greet", Map.of("name", "Ana")).trim());
    }

    @Test
    void escapedDollarFollowedByLiteralBraceIsNotReinterpretedAsInterpolation() throws Exception {
        // Caso adversarial da Task 0 (achado D6): `\$` seguido de `{x}`
        // literal (não escapado) forma visualmente "${x}" no texto — o
        // risco era o próprio gg.jte reler isto como uma NOVA interpolação
        // sua, o que seria pior que o bug atual. Verificado empiricamente
        // contra o motor real: `\u0024` nunca introduz um `${` no `.jte`
        // gerado (ao contrário de um `$` cru), por isso não há
        // reinterpretação — o texto sai literal.
        String source = """
            component Lit() {
              <p>${"literal \\${x}"}</p>
            }
            """;
        assertEquals("<p>literal ${x}</p>", JteRenderSupport.render(source, "Lit", Map.of()).trim());
    }
}
