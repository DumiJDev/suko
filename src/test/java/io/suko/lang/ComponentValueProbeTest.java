package io.suko.lang;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonda empírica (subprojeto 6, tarefa 1): confirma se um bloco de
 * conteúdo JTE (`@`...``) é válido dentro de uma expressão Java
 * condicional gerada — o mecanismo de que "componente como valor"
 * (tarefa 5) depende.
 *
 * <p><b>Resultado: PASSOU.</b> Sob {@code gg.jte} 3.1.12, a forma
 * {@code !{var c = cond ? @`@template.X()` : @`@template.Y()`;}${c}}
 * compila e renderiza corretamente — o bloco de conteúdo JTE é válido
 * como operando de um ternário Java dentro de código gerado. A tarefa 5
 * deve emitir exatamente esta forma (`@`@template.X(args)``) para
 * "componente como valor" em posição condicional; não é necessária a
 * alternativa de fallback (variável de conteúdo local por-ocorrência).
 */
class ComponentValueProbeTest {

    @Test
    void contentBlockInsideTernaryCompilesAndRenders() throws Exception {
        Path tempDir = Files.createTempDirectory("suko-probe-component-value");

        Files.writeString(tempDir.resolve("CardA.jte"), "A\n");
        Files.writeString(tempDir.resolve("CardB.jte"), "B\n");
        Files.writeString(tempDir.resolve("Host.jte"), """
            @param boolean useA

            !{var c = useA ? @`@template.CardA()` : @`@template.CardB()`;}
            ${c}
            """);

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render("Host.jte", Map.of("useA", true), output);

        assertTrue(output.toString().contains("A"), "esperava 'A' na saída, obteve: " + output);
    }
}
