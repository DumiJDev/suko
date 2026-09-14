package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void rendersNullSafeAccessAndElvis() throws Exception {
        String source = """
            component Price(String label) {
              <p>{label?.length() ?: -1}</p>
            }
            """;

        String withValue = JteRenderSupport.render(source, "Price", java.util.Collections.singletonMap("label", "abc"));
        String withNull = JteRenderSupport.render(source, "Price", java.util.Collections.singletonMap("label", null));

        assertEquals("\n<p>3</p>", withValue);
        assertEquals("\n<p>-1</p>", withNull);
    }

    @Test
    void rendersIfElse() throws Exception {
        String source = """
            component Status(boolean ok) {
              if (ok) {
                <p>Tudo bem</p>
              } else {
                <p>Falhou</p>
              }
            }
            """;

        assertEquals("<p>Tudo bem</p>\n", stripJteControlLines(JteRenderSupport.render(source, "Status", Map.of("ok", true))));
        assertEquals("<p>Falhou</p>\n", stripJteControlLines(JteRenderSupport.render(source, "Status", Map.of("ok", false))));
    }

    @Test
    void rendersForLoopOverGenericList() throws Exception {
        // DESVIO DO BRIEF: o brief literal usa
        // `component Items<T>(java.util.List<T> items) { for (T item : items) ... }`.
        // Dois problemas reais, verificados contra os motores reais (ANTLR e
        // gg.jte), impedem essa forma exata:
        //   1. A gramática do Suko (`type: Identifier typeArguments? arrayMarker*`,
        //      SukoParser.g4) não aceita nomes de tipo qualificados com ponto —
        //      "java.util.List<T>" falha a analisar ("extraneous input '.'
        //      expecting Identifier"), um gap pré-existente e não relacionado ao
        //      `for`, fora do âmbito desta tarefa (que é só AST/emitter do forStmt).
        //   2. Mesmo contornando (1) usando um tipo não-qualificado, a sintaxe real
        //      de `@param` do gg.jte (verificada por experimento direto contra o
        //      motor real, JavaParamInfo.parse, jte 3.1.12) NÃO suporta declarar um
        //      parâmetro de tipo genérico próprio do template (`@param <T> ...`) —
        //      o "<...>" só serve para não partir tipos genéricos já concretos
        //      (ex.: "List<String>") ao separar tipo de nome; não declara uma
        //      variável de tipo nova. Um `T` desligado nunca resolve para uma
        //      classe Java real. Não há mecanismo de import no JteEmitter (tarefa
        //      13 não o adiciona — SukoFile.imports() ainda não é lido por
        //      JteEmitter.emit) que pudesse mitigar (1) de outra forma.
        // Por isso este teste mantém `Items<T>` na declaração do componente (para
        // continuar a exercitar ComponentDecl.typeParameters(), como o brief pede)
        // mas usa um tipo concreto e não-qualificado (String[]) para o próprio
        // parâmetro/for-loop, evitando os dois gaps acima. O mecanismo do
        // Statement.ForStmt/emitForStmt em si é agnóstico ao tipo do item — o
        // emitter só copia o texto do tipo tal como escrito — por isso este teste
        // continua a validar genuinamente o `for` desaçucarado para `@for`/`@endfor`
        // através do motor gg.jte real.
        String source = """
            component Items<T>(String[] items) {
              <ul>
              for (String item : items) {
                <li>{item}</li>
              }
              </ul>
            }
            """;

        String html = JteRenderSupport.render(source, "Items", Map.of("items", new String[] {"a", "b", "c"}));

        assertTrue(html.contains("<li>a</li>"));
        assertTrue(html.contains("<li>b</li>"));
        assertTrue(html.contains("<li>c</li>"));
    }

    @Test
    void rendersSwitchWithDefault() throws Exception {
        String source = """
            component Role(String role) {
              switch (role) {
                case "admin" -> { <p>Admin</p> }
                case "guest" -> { <p>Visitante</p> }
                default -> { <p>Desconhecido</p> }
              }
            }
            """;

        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "admin")).contains("<p>Admin</p>"));
        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "guest")).contains("<p>Visitante</p>"));
        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "other")).contains("<p>Desconhecido</p>"));
    }

    @Test
    void rendersComponentComposition() throws Exception {
        String source = """
            component NavLink(String label, String href) {
              <a href={href}>{label}</a>
            }

            component Menu(String activeLabel) {
              <nav>
              NavLink(label = activeLabel, href = "/")
              </nav>
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Menu", Map.of("activeLabel", "Home"));

        assertTrue(html.contains("<a href=\"/\">Home</a>"));
    }

    @Test
    void rendersRequiredSingleSlot() throws Exception {
        String source = """
            component Card(slot<String> header) {
              <div class="card">{header}</div>
            }

            component Page() {
              Card() {
                header { <b>Título</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>Título</b>"));
    }

    private static String stripJteControlLines(String html) {
        return html.lines().filter(line -> !line.isBlank()).reduce("", (a, b) -> a + b + "\n");
    }
}
