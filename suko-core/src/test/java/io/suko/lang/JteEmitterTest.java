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
              <p>Hello, ${name}!</p>
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
    void rendersVarDecl() throws Exception {
        String source = """
            component Greeting(String name) {
              var upper = name.toUpperCase();
              <p>${upper}</p>
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Greeting", Map.of("name", "ana"));

        assertTrue(html.contains("<p>ANA</p>"));
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
              <p>${name} !</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Greeting", Map.of("name", "World"));

        assertEquals("\n<p>World !</p>", html);
    }

    @Test
    void rendersArithmeticAndComparisonExpressions() throws Exception {
        String source = """
            component Sum(int a, int b) {
              <p>${a + b}</p>
              <p>${(a - b) * 2}</p>
              <p>${a > b}</p>
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
              <p>${-a + b}</p>
              <p>${a - -b}</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Neg", Map.of("a", 1, "b", 2));

        assertEquals("\n<p>1</p><p>3</p>", html);
    }

    @Test
    void rendersNullSafeAccessAndElvis() throws Exception {
        String source = """
            component Price(String label) {
              <p>${label?.length() ?: -1}</p>
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
                <li>${item}</li>
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
              <a href=${href}>${label}</a>
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
        // Slot simples (tipo `Component`, não `Function<T, Component>`) é
        // Content nu — ler é {header}, sem `.apply(null)`. Ver
        // docs/superpowers/specs/2026-09-14-suko-nucleo-ajustes.md, Ajuste 1.
        // MIGRADO (tarefa 2, subprojeto 6): `slot<String>` -> `Component`
        // — ver docs/superpowers/specs/2026-09-18-suko-modelo-componente.md,
        // "Decisão central: Component substitui slot<T>".
        String source = """
            component Card(Component header) {
              <div class="card">${header}</div>
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

@Test
    void rendersOptionalAndMultipleSlots() throws Exception {
        // "Content"/"List" desqualificados exigem o hardcode de
        // JteEmitter.javaType (tarefa 17, inalterado nesta tarefa) — ver
        // ARCHITECTURE.md, "Não há imports automáticos". `title`/`actions`
        // são declarados `Component`/`List<Component>` (não
        // `Function<T, Component>`), por isso ambos são Content/List<Content>
        // nus (não Function) — decisão estrutural, já não heurística.
        // MIGRADO (tarefa 2, subprojeto 6): `slot<String>` -> `Component`,
        // `List<slot<String>>` -> `List<Component>`.
        String source = """
            component Toolbar(Component title = null, List<Component> actions = null) {
              if (title == null) {
                <div>sem-titulo</div>
              } else {
                <div>${title}</div>
              }
              for (Content action : actions) {
                <span>${action}</span>
              }
            }

            component WithActions() {
              Toolbar() {
                title { <b>Editar</b> }
                actions { <button>Salvar</button> }
                actions { <button>Cancelar</button> }
              }
            }

            component WithoutTitle() {
              Toolbar() {
                actions { <button>Só</button> }
              }
            }
            """;

        String withActions = JteRenderSupport.renderWithDependencies(source, "WithActions", Map.of());
        assertTrue(withActions.contains("<b>Editar</b>"));
        assertTrue(withActions.contains("<button>Salvar</button>"));
        assertTrue(withActions.contains("<button>Cancelar</button>"));

String withoutTitle = JteRenderSupport.renderWithDependencies(source, "WithoutTitle", Map.of());
        assertTrue(withoutTitle.contains("sem-titulo"));
    }

    @Test
    void rendersRenderPropSlot() throws Exception {
        // DESVIO DO BRIEF (documentado, tarefa 18, Step 4): o brief usa
        // `java.util.List<String> items` como tipo do ValueParam. Verificado
        // diretamente contra o parser real que isto reproduz o mesmo
        // bloqueio já documentado na ruling da tarefa 13: `type` não aceita
        // nomes qualificados ("extraneous input '.' expecting Identifier").
        // Trocado por "List<String>" desqualificado — e, como um
        // `ValueParam` comum (não-slot) de tipo "List" nunca tinha sido
        // exercitado antes desta tarefa, isto expôs um gap real e
        // diferente: "List" desqualificado também não resolve no Java
        // gerado pelo gg.jte (mesmo sintoma de "cannot find symbol" já
        // documentado para "Content" na tarefa 17). Corrigido estendendo
        // `JteEmitter.javaType` (ver comentário lá) para sintetizar
        // "java.util.List" a partir do nome simples "List", seguindo o
        // mesmo padrão já estabelecido — não uma resolução geral de
        // imports. `java.util.List.of("a", "b")`, por outro lado, é um
        // VALOR (expressão), não um tipo: `expression` aceita cadeias de
        // `AccessExpr`/`CallExpr` sobre qualquer `Identifier` inicial, e
        // "java" é apenas mais um identificador nessa cadeia — verificado
        // que isto parseia sem erro (diferente da regra `type`, que é
        // fechada a `Identifier typeArguments? arrayMarker*` sem `DOT`).
        // `{row(item)}` (não `{row.apply(item)}`) é usado de propósito
        // aqui — diferente do `action.apply(null)` da tarefa 17 acima,
        // `row` É literalmente o nome do `SlotParam` declarado em
        // `ItemList`, então esta chamada exercita a própria heurística de
        // `.apply` implícito da Step 3 (identificador simples cujo texto
        // está em `slotNames`), não o contorno manual usado para a
        // variável de `for` do outro teste.
        //
        // NOTA: este é o caso render-prop — hoje reconhecido pela FORMA da
        // declaração (`Function<String, Component> row`), não mais por scan
        // do corpo à procura de uma chamada `row(...)`. Contrastar com os
        // testes `rendersRequiredSingleSlot` e `rendersOptionalAndMultipleSlots`,
        // onde os slots são `Component`/`List<Component>` (não
        // `Function<T, Component>`) e portanto são `Content`/`List<Content>`
        // nu.
        // MIGRADO (tarefa 2, subprojeto 6): `slot<String> row` (render-prop
        // reconhecido antes por scan do corpo) -> `Function<String, Component>
        // row` (render-prop agora reconhecido pela assinatura).
        String source = """
            component ItemList(List<String> items, Function<String, Component> row) {
              <ul>
              for (String item : items) {
                <li>${row(item)}</li>
              }
              </ul>
            }

            component Page() {
              ItemList(items = java.util.List.of("a", "b")) {
                row { item -> <b>${item}</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>a</b>"));
        assertTrue(html.contains("<b>b</b>"));
    }

    @Test
    void rendersListOfFunctionSlotAsListOfRenderProps() throws Exception {
        // Achado "Important" da revisão da tarefa 2: `List<Function<T,
        // Component>>` (MANY + renderProp) — o ramo `isRenderProp(inner)`
        // dentro do caso "List" de `SukoAstBuilder.tryBuildSlotParam` — não
        // tinha nenhuma cobertura de render, apesar de estruturalmente
        // simétrico com os casos ONE+renderProp (`rendersRenderPropSlot`
        // acima) e MANY+não-renderProp (`rendersOptionalAndMultipleSlots`).
        // Múltiplos fills nomeados iguais (`rows { ... } rows { ... }`)
        // exercitam o mesmo agrupamento em `java.util.List.of(...)` já
        // usado para `actions` em `rendersOptionalAndMultipleSlots`, desta
        // vez com cada elemento sendo um `Function<String, Content>` (lido
        // no corpo via `row.apply(...)`, não `{row}` nu).
        String source = """
            component RowList(List<Function<String, Component>> rows) {
              <ul>
              for (Function<String, Content> row : rows) {
                <li>${row.apply("x")}</li>
              }
              </ul>
            }

            component Page() {
              RowList() {
                rows { it -> <b>primeira:${it}</b> }
                rows { it -> <b>segunda:${it}</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>primeira:x</b>"));
        assertTrue(html.contains("<b>segunda:x</b>"));
    }

    @Test
    void rendersValueParamDefaultWhenOmittedAtCallSite() throws Exception {
        String source = """
            component Greeting(String name, String punctuation = "!") {
              <p>${name}${punctuation}</p>
            }

            component Page() {
              Greeting(name = "Ana")
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("Ana!"));
    }

    private static String stripJteControlLines(String html) {
        return html.lines().filter(line -> !line.isBlank()).reduce("", (a, b) -> a + b + "\n");
    }

    @Test
    void rendersComponentSlotAsPlainContent() throws Exception {
        // DESVIO DO BRIEF (documentado, tarefa 2): o brief usa a forma de
        // fill "solto" (`Card() { "Título" }`, sem `header { ... }`).
        // Reproduzido (RED genuíno, com o resto da tarefa já implementado):
        // isto depende da síntese implícita de "children" a partir de
        // conteúdo solto num slotBlock — `SukoAstBuilder.buildComponentCallStmt`
        // hoje só lê `ctx.slotBlock().namedSlot()`, ignorando
        // `templateStatement` soltos, exatamente como documentado na spec
        // (secção "Children implícitos") e implementado só na Tarefa 3
        // deste plano, não nesta. Sem essa síntese, a chamada `Card() {
        // "Título" }` não passa nenhum argumento para o parâmetro `header`
        // (obrigatório, sem valor por omissão), e a compilação do .jte
        // gerado falha com "method render(...) required ...,Content found
        // ...,​" (verificado contra o compilador real do gg.jte, não uma
        // suposição). O objetivo desta tarefa é validar que `Component`
        // sem forma render-prop é reconhecido como Content nu — trocado
        // para a forma de slot nomeado (`header { ... }`), já suportada
        // hoje, que exercita exatamente essa asserção sem depender de uma
        // funcionalidade de uma tarefa futura.
        String html = JteRenderSupport.renderWithDependencies(
            """
            component Card(Component header) {
              <div>${header}</div>
            }
            component Host() {
              Card() {
                header { "Título" }
              }
            }
            """, "Host", java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
    }

    @Test
    void rendersRenderPropSlotViaExplicitFunctionType() throws Exception {
        // DESVIO DO BRIEF (documentado, tarefa 2): mesma causa-raiz do
        // desvio em `rendersComponentSlotAsPlainContent` acima — o brief usa
        // a forma de fill solto (`Row() { it -> ... }`, sem `label { ... }`),
        // que depende da síntese implícita de "children" (Tarefa 3, ainda
        // não implementada nesta tarefa). Reproduzido (RED genuíno):
        // `it -> "valor: {it}"` sem um `Identifier LBRACE` envolvente não
        // corresponde a `namedSlot` (que exige `Identifier LBRACE (Identifier
        // ARROW)? ...`) nem é um `templateStatement` válido isolado — o
        // parser aceita porque `slotBlock` já tolera `templateStatement*`
        // solto, mas nada liga esse conteúdo ao parâmetro `label`, e a
        // chamada resultante fica sem argumento para um parâmetro
        // obrigatório, falhando a compilar o .jte gerado (verificado contra
        // o compilador real do gg.jte). Trocado para a forma de slot nomeado
        // `label { it -> ... }`, já suportada hoje (mesmo padrão do teste
        // pré-existente `rendersRenderPropSlot`), que exercita exatamente a
        // asserção desta tarefa — `Function<T, Component>` é render-prop por
        // assinatura, mesmo sem nenhuma chamada `label(...)` no corpo de
        // Row — sem depender de uma funcionalidade de tarefa futura.
        //
        // DESVIO ADICIONAL DO BRIEF: o corpo do fill usa `<span>valor: {it}
        // </span>` (interpolação HTML, `{expr}` dentro de templateStatement
        // — já suportada e usada no teste pré-existente
        // `rendersRenderPropSlot`), não `"valor: {it}"` (string Suko com
        // `{it}` dentro de aspas). Confirmado lendo a gramática/lexer
        // (SukoLexer.g4/SukoParser.g4) e `SukoAstBuilder.buildStringLiteral`:
        // interpolação DENTRO de uma string literal só é reconhecida via
        // `${expr}`/`$ident` (`EXPR_INTERP_START`/`SIMPLE_INTERP_START`), e
        // mesmo essa sintaxe ainda não é interpretada pelo AST builder hoje
        // — `buildStringLiteral` concatena o texto cru de toda `stringPart`
        // num único `Literal`, independentemente do seu tipo; "interpolação
        // real em strings" é trabalho de design ainda não implementado (ver
        // spec, secção "Interpolação real em strings e atributos"). `{it}`
        // bare dentro de uma string Suko já é hoje, de propósito, texto
        // literal (mesma spec) — não corresponderia a "x" de qualquer forma.
        String html = JteRenderSupport.renderWithDependencies(
            """
            component Row(Function<String, Component> label) {
              <li>${label("x")}</li>
            }
            component Host() {
              Row() {
                label { it -> <span>valor: ${it}</span> }
              }
            }
            """, "Host", java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("valor: x"));
    }

    @Test
    void syntheticChildrenFillFromLooseContent() throws Exception {
        String html = JteRenderSupport.renderWithDependencies(
            """
            component Field(Component children) {
              <div>${children}</div>
            }
            component Host() {
              Field() {
                "Nome: "
                <input/>
              }
            }
            """, "Host", java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Nome: "));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<input/>"));
    }

    @Test
    void looseContentAndExplicitNamedSlotCoexist() throws Exception {
        // DESVIO DO BRIEF: a ordem das duas declarações dentro do slotBlock
        // foi invertida em relação ao brief original ("corpo solto" antes de
        // "header { ... }") — reproduzido empiricamente que a ordem original
        // não compila: `textRun` (regra do parser: `(~(LBRACE|RBRACE|LT|
        // LTSLASH))+`, usada porque templateStatement não tem alternativa
        // dedicada para uma string-literal solta) é gananciosa e, quando um
        // literal solto como "corpo solto" precede imediatamente o
        // identificador de um namedSlot (aqui "header"), esse identificador
        // não é excluído do conjunto de tokens do textRun — é engolido pelo
        // MESMO textRun, e só então o parser encontra "{" e o interpreta
        // como uma simples `interpolation` (`{expr}`), nunca como
        // `namedSlot`. Resultado: `ctx.slotBlock().namedSlot()` fica
        // genuinamente vazio (confirmado inspecionando a saída emitida —
        // só existia um único arg "children", nunca "header") — não é um
        // bug em `buildComponentCallStmt` (Tarefa 3, este ficheiro), é uma
        // limitação pré-existente da gramática (`slotBlock`/`textRun`),
        // fora do escopo desta tarefa (que só mexe em SukoAstBuilder.java).
        // Confirmado que a ordem invertida (namedSlot primeiro, depois o
        // conteúdo solto) parseia corretamente as duas declarações — o que
        // basta para testar "coexistência" (ambas presentes na mesma
        // chamada); a preservação de ordem ENTRE vários statements soltos já
        // é coberta por `syntheticChildrenFillFromLooseContent` acima (duas
        // statements soltas em sequência, sem slot nomeado no meio).
        String html = JteRenderSupport.renderWithDependencies(
            """
            component Layout(Component children, Component header) {
              <header>${header}</header>
              <main>${children}</main>
            }
            component Host() {
              Layout() {
                header { "Título" }
                "corpo solto"
              }
            }
            """, "Host", java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("corpo solto"));
        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
    }

    @Test
    void componentCallAsExpressionValue() throws Exception {
        String html = JteRenderSupport.renderWithDependencies(
            """
            component CardA() {
              <p>A</p>
            }
            component CardB() {
              <p>B</p>
            }
            component Host(boolean useA) {
              var c = useA ? CardA() : CardB();
              <div>${c}</div>
            }
            """, "Host", java.util.Map.of("useA", true));

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("<p>A</p>"));
    }

    @Test
    void rendersAttributeWithRealInterpolation() throws Exception {
        String html = JteRenderSupport.render(
            """
            component Button(String variant) {
              <a class="btn btn-${variant}">Click</a>
            }
            """, "Button", java.util.Map.of("variant", "primary"));

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"btn btn-primary\""));
    }

    @Test
    void interpolatesArbitraryJavaObjectViaToString() throws Exception {
        // "Object" é um tipo desqualificado que já faz parse hoje (java.lang,
        // resolve sem import) e não tem overload dedicado em
        // gg.jte.TemplateOutput.writeUserContent — exatamente o caso confirmado
        // por sonda na spec ("V1"): hoje isto não compila.
        String html = JteRenderSupport.render(
            """
            component Show(Object id) {
              <p>${id}</p>
            }
            """, "Show", java.util.Map.of("id", java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void slotInterpolationIsNeverWrappedInToString() throws Exception {
        // DESVIO DO BRIEF (documentado, tarefa 10): o brief usa
        // `component Card(Component header)` com fill "solto"
        // (`Card() { "Título" }`, sem `header { ... }`). Reproduzido (RED
        // genuíno): a síntese implícita de children (Tarefa 3 deste plano,
        // `SukoAstBuilder.buildComponentCallStmt`) SEMPRE nomeia o
        // SlotFill sintetizado "children" (literal, não o nome do primeiro
        // slot do componente-alvo) — ver comentário em
        // `SukoAstBuilder.java:199-204`. Um param chamado `header` nunca
        // recebe esse fill; a compilação do .jte gerado falha com "No
        // parameter with name children is defined in Card.jte" (verificado
        // contra o compilador real do gg.jte). Renomeado o param para
        // `children`, a mesma convenção já usada no teste
        // `rendersChildrenAndNamedSlotTogether` desta classe, para
        // exercitar a asserção desta tarefa (slot lido por identificador
        // simples nunca é embrulhado em .toString()) sem depender de uma
        // reconciliação nome-do-fill/nome-do-param fora de âmbito aqui.
        String html = JteRenderSupport.renderWithDependencies(
            """
            component Card(Component children) {
              <div>${children}</div>
            }
            component Host() {
              Card() { "Título" }
            }
            """, "Host", java.util.Map.of());

        org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
    }
}
