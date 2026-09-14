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
        // DESVIO DO BRIEF (documentado, tarefa 18, retroativo à tarefa 16):
        // `{header}` sozinho deixou de compilar desde que todo SlotParam
        // passou a ser emitido como `Function<T, Content>` em vez de
        // `Content` — ler o slot exige agora uma chamada explícita
        // (`{header(null)}`), tratada pelo emitter como `.apply(null)`
        // (ver Step 3 do brief da tarefa 18 / JteEmitter.emitExpr).
        String source = """
            component Card(slot<String> header) {
              <div class="card">{header(null)}</div>
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
        // DESVIO DO BRIEF (documentado, tarefa 17): a fonte .sk original do
        // brief usa nomes de tipo QUALIFICADOS (`java.util.List<slot<String>>`,
        // `gg.jte.Content`). Verificado diretamente contra o parser gerado
        // que isto reproduz o mesmo bloqueio já documentado na ruling da
        // tarefa 13: `type: Identifier typeArguments? arrayMarker*` não
        // aceita ponto ("extraneous input '.' expecting Identifier"). Por
        // isso aqui usamos `List<slot<String>>` (sem qualificação — "List"
        // já é o nome verificado por `buildParam` para detectar
        // Cardinality.MANY) e `Content` (sem qualificação) no for-loop.
        // Confirmado por probe direto contra o TemplateEngine real que
        // "Content"/"List" desqualificados NÃO resolvem no Java gerado por
        // gg.jte (sem import automático nenhum para tipos fora de
        // java.lang) — por isso `JteEmitter.javaType` foi ajustado (ver
        // comentário lá) para sintetizar "gg.jte.Content" a partir do nome
        // simples "Content" escrito no .sk, o mesmo padrão que
        // `jteParamDeclaration` já usa para os próprios SlotParam.
        //
        // DESVIO ADICIONAL (documentado, tarefa 17): o brief original usa
        // `{title ?: "sem-titulo"}` para expressar o fallback. Verificado
        // (RED genuíno, não hipótese) contra o compilador Java real dentro
        // do gg.jte: `title` é sempre do tipo `gg.jte.Content` (todo
        // SlotParam é emitido como Content/List<Content>, nunca como o T de
        // `slot<T>` — ver nota da classe sobre slots serem uniformemente
        // Function<T, Content>/Content), e "sem-titulo" é um `String`. O
        // desaçucaramento de `?:` gera
        // `(title == null ? "sem-titulo" : title)`, uma expressão
        // condicional poli cujos dois ramos (String, Content) não têm
        // supertipo comum aceite por nenhuma sobrecarga de
        // `TemplateOutput.writeUserContent` — falha a compilar
        // ("Content cannot be converted to String" / "String cannot be
        // converted to Content"). Isto não é um bug do emitter: é uma
        // limitação real de mistura de tipos ao usar `?:` sobre um slot,
        // que só a análise semântica dos subprojetos 2/3 poderia detectar/
        // proibir. Adaptado aqui para `if/else` (ambos os ramos permanecem
        // Statement, sem essa restrição de tipo de expressão), que exercita
        // o mesmo comportamento observável (fallback quando o slot não é
        // preenchido) sem tropeçar nesta limitação.
        //
        // DESVIO ADICIONAL (documentado, tarefa 18): desde que todo
        // `slot<T>` passa a ser emitido como `Function<T, Content>` (nunca
        // `Content` puro — ver Step 1 do brief da tarefa 18), `{title}`
        // sozinho não compila mais (precisa de `{title(null)}`, tratado
        // pelo emitter como `.apply(null)` — Step 3), e o item do `for`
        // sobre `actions` (agora `List<Function<String, Content>>`, não
        // `List<Content>`) precisa de mudar de `Content` para
        // `Function<String, Content>` desqualificado — "Function"
        // desqualificado sofre do MESMO problema de "cannot find symbol"
        // já documentado para "Content"/"List" na tarefa 17, então
        // `JteEmitter.javaType` foi estendido (ver comentário lá) para
        // sintetizar também "java.util.function.Function" a partir do
        // nome simples "Function" — verificado por probe direto contra o
        // TemplateEngine real antes de assumir esta forma (ver ruling do
        // brief desta tarefa, Step 6).
        //
        // DESVIO ADICIONAL, e correção à sugestão literal do brief (Step
        // 6): o brief propõe `{action(null)}` dentro do `for` (mesma forma
        // usada para `title(null)`), mas `action` aqui é uma variável do
        // `for` (não um `SlotParam` do componente `Toolbar` — esse é
        // `actions`, no plural), então a heurística sintática de
        // `emitExpr` (Step 3: "identificador simples cujo texto está em
        // `slotNames`, o conjunto de nomes de SlotParam DECLARADOS no
        // componente") não a reconhece, e `action(null)` seria emitido
        // literalmente como uma chamada de método `action(null)` sobre uma
        // variável — não compila em Java ("action" não é um método).
        // Verificado (RED genuíno) contra o compilador real do gg.jte.
        // Escrever `{action.apply(null)}` explicitamente no .sk contorna
        // isto: `action.apply` é um `AccessExpr` comum (não um
        // `PrimaryExpr` simples), então cai no caso genérico de `CallExpr`
        // e emite exatamente `action.apply(null)`, que compila porque
        // `action` é de facto `Function<String, Content>`. Isto é
        // consistente com a decisão de desenho da tarefa (nenhuma
        // resolução de nomes/tabela de símbolos nesta camada) — a
        // heurística de `.apply` implícito só cobre o caso mais comum
        // (ler diretamente um SlotParam pelo seu próprio nome), não
        // qualquer variável de tipo `Function` derivada dele.
        //
        // DESVIO ADICIONAL, e correção a uma suposição errada minha nesta
        // mesma tarefa: `if (title == null)` DEIXOU de funcionar como
        // "slot não preenchido" — verificado (RED genuíno, não hipótese)
        // contra o compilador e o motor reais. A razão: o valor por
        // omissão de um SlotParam Cardinality.ONE já não é o literal
        // `null` (isso mudou no Step 1 desta tarefa, ver comentário em
        // `JteEmitter.jteParamDeclaration`) — é sempre uma instância de
        // `Function` (mesmo quando "vazia", devolve `null` quando chamada:
        // `(String it) -> null`), precisamente porque `gg.jte`, no modo de
        // render sem tipos usado por `JteRenderSupport`
        // (`params.getOrDefault(...)`), exige um valor-alvo tipado para o
        // lambda de omissão. Consequência: `title` nunca é `null` como
        // REFERÊNCIA — o slot "vazio" agora só se distingue chamando-o e
        // comparando o RESULTADO (`title.apply(null) == null`), não a
        // própria referência da função. Ajustado para `if (title.apply(null)
        // == null)`.
        String source = """
            component Toolbar(slot<String> title = null, List<slot<String>> actions = null) {
              if (title.apply(null) == null) {
                <div>sem-titulo</div>
              } else {
                <div>{title(null)}</div>
              }
              for (Function<String, Content> action : actions) {
                <span>{action.apply(null)}</span>
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
        String source = """
            component ItemList(List<String> items, slot<String> row) {
              <ul>
              for (String item : items) {
                <li>{row(item)}</li>
              }
              </ul>
            }

            component Page() {
              ItemList(items = java.util.List.of("a", "b")) {
                row { item -> <b>{item}</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>a</b>"));
        assertTrue(html.contains("<b>b</b>"));
    }

    private static String stripJteControlLines(String html) {
        return html.lines().filter(line -> !line.isBlank()).reduce("", (a, b) -> a + b + "\n");
    }
}
