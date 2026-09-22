package io.suko.components;

import gg.jte.Content;
import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render real (motor {@code gg.jte}, nunca comparação de {@code .jte})
 * de cada um dos três componentes folha (Tarefa 6, subprojeto 7):
 * {@code Input}, {@code Label} e {@code Badge}. Complementa (não
 * substitui) as guardas genéricas de {@link LibraryCompilesTest} e
 * {@link LibraryConventionsTest} com asserções específicas sobre o HTML
 * produzido — atributos presentes, escape aplicado, e valor por omissão
 * respeitado quando o parâmetro é {@code = null}.
 */
class LeafComponentsRenderTest {

    private static final Path UI_DIR = Path.of("src", "main", "suko", "io", "suko", "ui");

    @Test
    void labelRendersTextEscaped() throws IOException {
        String html = render("Label", Map.of("text", "<script>alert(1)</script>"));

        assertTrue(html.contains("<label>"), () -> "esperava elemento <label>: " + html);
        assertTrue(html.contains("&lt;script&gt;"),
            () -> "texto do slot tem de ser escapado (contexto HTML body): " + html);
        assertFalse(html.contains("<script>"), () -> "HTML não escapado não pode aparecer no output: " + html);
    }

    @Test
    void inputRendersRequiredAttributesAndRespectsDefaults() throws IOException {
        Map<String, Object> params = new HashMap<>();
        params.put("id", "email");
        params.put("name", "email");
        // type, required e placeholder ficam por omissão — prova dos
        // `= "text"`, `= false` e `= null` declarados no .sk.

        String html = render("Input", params);

        assertTrue(html.contains("<input"), () -> "esperava elemento <input>: " + html);
        assertTrue(html.contains("id=\"email\""), () -> "atributo id tem de refletir o parâmetro: " + html);
        assertTrue(html.contains("name=\"email\""), () -> "atributo name tem de refletir o parâmetro: " + html);
        assertTrue(html.contains("type=\"text\""), () -> "type por omissão devia ser \"text\": " + html);
        // Achado empírico: para um atributo cujo valor é INTEIRAMENTE uma
        // interpolação booleana (`required=${required}`), o gg.jte trata-o
        // como atributo booleano HTML — omite-o por completo quando falso,
        // em vez de escrever `required="false"`.
        assertFalse(html.contains("required"), () -> "required por omissão (false) não pode aparecer no atributo: " + html);
        // placeholder = null: JTE escreve string vazia via ${}, nunca a
        // palavra "null" — confirma que o valor por omissão null é
        // tratado como ausência de conteúdo, não como texto literal.
        assertFalse(html.contains("null"), () -> "placeholder por omissão (null) não pode aparecer como texto: " + html);
    }

    @Test
    void inputRendersExplicitAttributesAndEscapesPlaceholder() throws IOException {
        Map<String, Object> params = Map.of(
            "id", "pwd",
            "name", "pwd",
            "type", "password",
            "required", true,
            "placeholder", "\"quoted\" & <tag>"
        );

        String html = render("Input", params);

        assertTrue(html.contains("type=\"password\""), () -> "type explícito tem de substituir o omisso: " + html);
        // Mesmo achado: quando true, o gg.jte escreve o atributo booleano
        // "nu" (sem `="true"`), não `required="true"`.
        assertTrue(html.contains(" required "), () -> "required explícito (true) tem de aparecer como atributo booleano nu: " + html);
        assertFalse(html.contains("\"quoted\" & <tag>"),
            () -> "placeholder tem de passar por escape de atributo HTML: " + html);
        // Achado empírico: o gg.jte escapa `<` para `&lt;` em contexto de
        // atributo, mas não escapa o `>` de fecho correspondente (não é
        // estritamente necessário em HTML fora de contextos especiais) —
        // por isso a asserção verifica só `&lt;`, não `&lt;tag&gt;`.
        assertTrue(html.contains("&lt;tag"), () -> "'<' do placeholder tem de ser escapado: " + html);
    }

    @Test
    void badgeRendersVariantClassAndChildrenContent() throws IOException {
        Content children = out -> out.writeContent("Ativo");

        String html = render("Badge", Map.of("variant", "success", "children", children));

        assertTrue(html.contains("<span"), () -> "esperava elemento <span>: " + html);
        assertTrue(html.contains("bg-green-50"),
            () -> "variante \"success\" tem de escrever a classe Tailwind completa: " + html);
        assertTrue(html.contains("Ativo"), () -> "conteúdo do slot children tem de aparecer no output: " + html);
    }

    @Test
    void badgeFallsBackToDefaultVariantWhenOmitted() throws IOException {
        // `children` é obrigatório (sem `= null`, conforme o brief da
        // tarefa 6): tem de ser sempre fornecido. Só `variant` fica por
        // omissão aqui, para provar o valor por omissão `"default"`.
        Content children = out -> out.writeContent("Novo");
        Map<String, Object> params = new HashMap<>();
        params.put("children", children);

        String html = render("Badge", params);

        assertTrue(html.contains("<span"), () -> "esperava elemento <span>: " + html);
        assertTrue(html.contains("bg-gray-50"),
            () -> "variante por omissão (\"default\") tem de escrever a sua classe Tailwind: " + html);
        assertTrue(html.contains("Novo"), () -> "conteúdo do slot children tem de aparecer no output: " + html);
    }

    private static String render(String componentName, Map<String, Object> params) throws IOException {
        String source = readSource(componentName);
        return JteRenderSupport.render(source, componentName, params);
    }

    private static String readSource(String componentName) throws IOException {
        return Files.readString(UI_DIR.resolve(componentName + ".sk"));
    }
}
