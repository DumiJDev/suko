package io.suko.lang.project;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiFileAcceptanceTest {

    private void writeThreeFileFixture(Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);

        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href, String label) {
              <a href="${href}">{label}</a>
            }
            """);

        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            component Dot() {
              <span class="dot"></span>
            }

            public component Badge(String text) {
              <span class="badge">
                {text}
                Dot()
              </span>
            }
            """);
        // NOTA (achado de aceitação, Tarefa 8): {text} tem de vir ANTES de
        // Dot() aqui, não depois. A gramática (componentCall: qualifiedName
        // LPAREN argList? RPAREN slotBlock?, slotBlock: LBRACE
        // (namedSlot|templateStatement)* RBRACE) associa greedily qualquer
        // "{...}" que siga imediatamente uma chamada ao seu slotBlock —
        // "Dot()\n{text}" parseia como UMA chamada Dot(){text}, sintetizando
        // um SlotFill("children", ...) implícito (ver "Children implícitos"
        // em SukoAstBuilder.buildComponentCallStmt, subprojeto 6, pré-
        // -existente a este plano). Como Dot() não declara nenhum slot, isto
        // produzia @template.ui.Dot(children = @`text`) no .jte gerado, que
        // o gg.jte real rejeita em tempo de render
        // ("No parameter with name children is defined in ui/Dot.jte") —
        // confirmado como uma ambiguidade de fixture, não um bug de
        // produção do subprojeto 5: a ordem inversa (usada aqui) não tem
        // nenhum "{" logo a seguir a uma chamada, por isso parseia como duas
        // statements irmãs independentes, como o teste sempre pretendeu.

        // NOTA (achado de aceitação, Tarefa 8): o fixture original do brief
        // só chamava Badge duas vezes (Pill + ui.Badge), o que só pode
        // produzir 2 renderizações de Dot() — nunca as 3 exigidas pela
        // asserção abaixo (nenhuma correção de código de produção pode
        // reconciliar "2 chamadas no fixture" com "3 esperado na asserção";
        // confirmado ao correr o teste sem esta chamada extra: "expected:
        // <3> but was: <2>"). Adicionada uma terceira chamada a Pill (mesmo
        // texto "novo", reafirmando que o alias curto resolve
        // consistentemente em chamadas repetidas) só para fazer o fixture
        // coincidir com a contagem que a própria asserção sempre pretendeu
        // testar — nenhuma asserção foi alterada.
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;
            import ui.Badge as Pill;

            component Home() {
              <div>
                NavLink(href="/", label="Início")
                Pill(text="novo")
                Pill(text="novo")
                ui.Badge(text="qualificado")
              </div>
            }
            """);
    }

    @Test
    void resolvesShortNameAliasAndFullyQualifiedNameAcrossPackages(@TempDir Path sourceRoot) throws IOException {
        writeThreeFileFixture(sourceRoot);

        String html = JteRenderSupport.renderProject(sourceRoot, "Home", Map.of());

        assertTrue(html.contains("href=\"/\""), html);
        assertTrue(html.contains("Início"), html);
        assertTrue(html.contains("novo"), html);
        assertTrue(html.contains("qualificado"), html);
        assertEquals(3, html.split("class=\"dot\"", -1).length - 1,
            "Dot() (file-private) é chamado 3 vezes dentro do próprio ficheiro Badge.sk");
    }

    @Test
    void filePrivateComponentCannotBeCalledFromAnotherFile(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            component Dot() {
              <span class="dot"></span>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.Dot;

            component Home() {
              Dot()
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertFalse(result.success());
        assertTrue(result.diagnosticsByFile().get(Path.of("Home.sk")).getErrors().stream()
            .anyMatch(d -> "IMPORT_NOT_FOUND".equals(d.code()) || "COMPONENT_NOT_VISIBLE".equals(d.code())),
            result.diagnosticsByFile().get(Path.of("Home.sk")).toString());
    }

    @Test
    void cyclicCrossFileCallsCompileAndRenderSuccessfully(@TempDir Path sourceRoot) throws IOException {
        Path cycleDir = sourceRoot.resolve("cycle");
        Files.createDirectories(cycleDir);

        Files.writeString(cycleDir.resolve("A.sk"), """
            package cycle;

            import cycle.B;

            public component A(boolean stop) {
              if (stop) {
                <p>fim A</p>
              } else {
                B(stop = true)
              }
            }
            """);
        Files.writeString(cycleDir.resolve("B.sk"), """
            package cycle;

            import cycle.A;

            public component B(boolean stop) {
              if (stop) {
                <p>fim B</p>
              } else {
                A(stop = true)
              }
            }
            """);

        String html = JteRenderSupport.renderProject(sourceRoot, "cycle/A", Map.of("stop", false));
        assertTrue(html.contains("fim B"), html);
    }
}
