package io.suko.components;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Render real (motor {@code gg.jte}, compilação de projeto multi-ficheiro
 * via {@link io.suko.lang.project.SukoProjectCompiler}) de {@code Field}
 * (Tarefa 8, subprojeto 7) — o único componente da biblioteca com
 * {@code dependsOn} não-vazio.
 *
 * <p>Ao contrário de {@link ChildrenComponentsRenderTest} (que junta um
 * componente {@code Page} sintético a um único {@code .sk} lido de disco
 * e compila com {@code JteRenderSupport.renderWithDependencies}), este
 * teste usa {@link JteRenderSupport#renderProject} sobre a árvore REAL da
 * biblioteca ({@code src/main/suko}) — prova, com conteúdo real (não um
 * componente {@code Page} fabricado), que a resolução cross-ficheiro do
 * subprojeto 5 (import por nome totalmente qualificado, sem alias — ver
 * convenção 8) funciona para `Label` e `Input`, ambos declarados em
 * ficheiros `.sk` diferentes de `Field`, no mesmo pacote `io.suko.ui`.
 *
 * <p>Também prova, depois da correção da Tarefa 1 do plano deste
 * subprojeto (verificação de chamadas de componente aninhadas dentro de
 * um elemento HTML), que as duas chamadas `Label(...)`/`Input(...)`
 * dentro do `<div>` de `Field` são efetivamente verificadas pelo
 * {@code SemanticChecker} — não só emitidas sem checagem.
 */
class CompositionRenderTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "suko");

    @Test
    void fieldRendersBothLabelAndInputOutput() throws IOException {
        Map<String, Object> params = Map.of(
            "id", "email",
            "name", "email",
            "label", "Email"
        );

        String html = JteRenderSupport.renderProject(SOURCE_ROOT, "io/suko/ui/Field", params);

        assertTrue(html.contains("<label>"), () -> "esperava o elemento <label> de Label: " + html);
        assertTrue(html.contains("Email"), () -> "texto do slot de Label tem de aparecer: " + html);
        assertTrue(html.contains("<input"), () -> "esperava o elemento <input> de Input: " + html);
        assertTrue(html.contains("id=\"email\""), () -> "atributo id de Input tem de refletir o parâmetro: " + html);
        assertTrue(html.contains("name=\"email\""), () -> "atributo name de Input tem de refletir o parâmetro: " + html);
        assertTrue(html.contains("type=\"text\""), () -> "type por omissão de Input (\"text\") tem de aparecer: " + html);
    }
}
