package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderiza de ponta a ponta através do pipeline real:
 * SukoAstBuilder -> JteEmitter -> gg.jte. Complementa o
 * SukoParserSmokeTest (que só verifica ausência de erro de parsing).
 *
 * `Card<T>` (o componente genérico real de examples/Card.sk) não é
 * renderizado aqui — ver ARCHITECTURE.md, "Limitações conhecidas (fim
 * do subprojeto 1)": gg.jte não tem forma de declarar uma variável de
 * tipo própria do template, confirmado empiricamente (tarefas 13, 17 e
 * a investigação desta tarefa). A cobertura de `Card` fica ao nível do
 * texto `.jte` emitido (golden file, tarefa 21), não de render real.
 */
class SukoEndToEndTest {

    @Test
    void rendersNavLinkFromCardExample() throws Exception {
        String source = Files.readString(Path.of("../examples/Card.sk"));

        String html = JteRenderSupport.renderWithDependencies(source, "NavLink", Map.of(
            "label", "Perfil",
            "href", "/perfil"));

        assertTrue(html.contains("<a href=\"/perfil\">Perfil</a>"));
    }

    @Test
    void rendersCardShapedComponentWithItems() throws Exception {
        // Mesma forma estrutural do Card<T> real (título + lista condicional
        // + for + interpolação aninhada com ?./?:), mas com o item concreto
        // como String em vez do T genérico — ver a nota sobre a ruling do
        // architect no topo deste ficheiro para o porquê.
        String source = """
            component CardOfNames(String title, List<String> names, String emptyLabel = "Sem itens") {
              <div class="card">
                <h2>{title}</h2>
                if (names.size() > 0) {
                  <ul>
                  for (String name : names) {
                    <li>{name?.trim() ?: "sem nome"}</li>
                  }
                  </ul>
                } else {
                  <p>{emptyLabel}</p>
                }
              </div>
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "CardOfNames", Map.of(
            "title", "Produtos",
            "names", List.of("Café", "Chá"),
            "emptyLabel", "Sem itens"));

        assertTrue(html.contains("Produtos"));
        assertTrue(html.contains("Café"));
        assertTrue(html.contains("Chá"));
    }

    @Test
    void rendersCardShapedComponentEmptyState() throws Exception {
        String source = """
            component CardOfNames(String title, List<String> names, String emptyLabel = "Sem itens") {
              <div class="card">
                <h2>{title}</h2>
                if (names.size() > 0) {
                  <ul>
                  for (String name : names) {
                    <li>{name?.trim() ?: "sem nome"}</li>
                  }
                  </ul>
                } else {
                  <p>{emptyLabel}</p>
                }
              </div>
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "CardOfNames", Map.of(
            "title", "Produtos",
            "names", List.of(),
            "emptyLabel", "Nada por aqui"));

        assertTrue(html.contains("Nada por aqui"));
    }
}
