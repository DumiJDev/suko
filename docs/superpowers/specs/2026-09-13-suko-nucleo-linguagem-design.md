# Suko — Subprojeto 1: Núcleo da linguagem

Data: 2026-09-13

## Contexto e visão do produto

O Suko é uma linguagem de componentes tipados para Java, no espírito
do `templ` (Go): transpila para `.jte` e reaproveita todo o
ecossistema JTE (runtime, integração Spring/Quarkus, performance,
cache de template). O Suko não compete com esse ecossistema — só
substitui a sintaxe do `.jte` em si pela sintaxe do `.sk`, mantendo o
escopo estritamente na camada de view (ver `ARCHITECTURE.md`).

A promessa central da v1 é **"se compila, renderiza"**: o maior valor
de um dev Java adotar o Suko em vez de escrever `.jte` à mão é apanhar
em tempo de compilação, na linha certa do `.sk`, erros que hoje só
apareceriam em runtime ou como erro genérico no `.jte` gerado.

Para chegar lá, o produto está dividido em 4 subprojetos, cada um com
o seu ciclo spec → plano → implementação:

1. **Núcleo da linguagem** (este documento) — gramática, AST,
   `JteEmitter`. Critério de conclusão: um componente com slots,
   generics e slots com parâmetro compila para `.jte` e renderiza de
   verdade via `gg.jte`.
2. **Verificador Suko** — tabela de símbolos de componentes; valida
   chamadas de componente, slots (presença/cardinalidade/parâmetros),
   tipos de children, estrutura HTML, escape e URLs perigosas.
3. **Verificação Java** — stub Java por componente verificado via
   `JavacTask`, com mapeamento de posições de volta ao `.sk`.
4. **Integração no build** — plugin Gradle/Maven, modo watch, erros
   formatados no terminal com excerto do `.sk`.

Fora do âmbito da v1 (para depois): starter Spring com helpers htmx,
LSP/plugins de IDE, `suko fmt`, live reload no browser.

Este documento cobre **apenas o subprojeto 1**.

## Estado herdado

A gramática ANTLR (`SukoLexer.g4` / `SukoParser.g4`) já existe e o
build Gradle compila e passa um smoke test de parsing contra
`examples/Card.sk`. Uma sondagem com 10 casos-limite encontrou 7
falhas que este subprojeto corrige (ver secção seguinte). Não existe
ainda `SukoAstBuilder` nem `JteEmitter`.

## Escopo

### 1. Correções da gramática

Cada item vira um caso de teste que falha antes da correção:

- **Literais booleanos.** `TRUELIT`/`FALSELIT` são declarados antes
  de `BooleanLiteral` no lexer; por maximal-munch/ordem de regras o
  token `BooleanLiteral` nunca é emitido. Corrigir a ordem/composição
  para que `if (true)` funcione.
- **Identificadores com hífen.** `Identifier` não aceita `-`, o que
  quebra atributos HTML reais (`data-id`, `aria-label`) e elementos
  customizados (`<my-button>`). Estender a regra de nome de atributo/
  tag (não o `Identifier` genérico usado em expressões Java) para
  aceitar hífen.
- **Comentário de linha vs. URL em texto.** `//` dentro de `textRun`
  é interpretado como início de comentário e consome até ao fim da
  linha, quebrando `<p>Veja http://x.com</p>`. `LINE_COMMENT` só deve
  aplicar-se fora de contexto de texto de tag — resolver via o mesmo
  mecanismo de fallback léxico usado para `OTHER`, dando precedência a
  texto quando não está claramente em posição de código.
- **`$` solto em string.** `"R$ 10"` falha porque `$` sem
  identificador a seguir não casa nenhuma regra dentro de
  `STRING_MODE`. Adicionar uma regra de fallback que trata `$` sem
  identificador como `STRING_TEXT` literal.
- **Aspas soltas em texto.** `<p>5" tela</p>` abre `STRING_MODE` e
  consome o resto do ficheiro. Como texto de tag e string de valor já
  são resolvidos em pontos diferentes da gramática, a abertura de
  string (`STRING_START`) só deve valer dentro de contexto de
  expressão, nunca dentro de `textRun`.
- **Elementos vazios (void elements).** `<br>`, `<img>`, `<input>`
  exigem hoje `/>`. Adicionar a lista de void elements HTML padrão
  (`br`, `img`, `input`, `hr`, `meta`, `link`, etc.) como alternativa
  válida de `OpenElement` sem exigir fecho.
- **Menos unário.** `{-1}` falha porque a gramática de expressões só
  tem `NotExpr` como prefixo. Adicionar `MINUS expression` como
  alternativa prefixa.

Ficam **fora deste subprojeto** (vão para o subprojeto 2, análise
semântica): tags de abertura/fecho que não coincidem, e para depois
da v1: interpolação de CSS/JS dentro de `<style>`/`<script>` (`{` e
`}` colidem com interpolação; precisa de um modo léxico dedicado).

### 2. Sintaxe de slots e children tipados

Slots são modelados como uma variante de parâmetro, não como uma
declaração à parte — reaproveita `param`/`paramList` já existentes:

```
component Tabs(slot<Tab> tabs) { ... }
component Card(slot<Header> header, List<slot<Action>> actions = []) { ... }
component ItemList<T>(List<T> items, slot<T> row) { ... }
```

- `slot<T>` — um único slot, obrigatório salvo se tiver valor por
  omissão (mesma regra que `param` comum).
- `List<slot<T>>` — vários slots do mesmo tipo.
- Uso como render-prop (slot com parâmetro): no ponto de chamada,
  `row { item -> <li>{item.name}</li> }` — o emitter traduz para
  `Function<T, gg.jte.Content>`.
- `slot<T>` é um tipo especial reconhecido pelo compilador Suko, não
  uma classe Java real; o verificador (subprojeto 2) tem um caso
  próprio para ele.

**Validado por spike:** o motor `gg.jte` 3.1.12 aceita nativamente
`nomeDoParam = item -> @`...`` como valor de um parâmetro
`Function<T, Content>`, com o content block a fechar sobre a variável
`item` do lambda. Testado com um `.jte` escrito à mão, compilado e
renderizado via `TemplateEngine.create(DirectoryCodeResolver, ContentType.Html)`
— código descartável, não faz parte do repositório. Não há necessidade
de nenhum mecanismo extra no runtime: a tradução do Suko para esta
forma do JTE é direta.

### 3. AST tipado

Um `record` Java por conceito da gramática, cada um carregando um
`SourceSpan` (linha/coluna de início e fim, mais offset absoluto de
carácter — este offset é o que o subprojeto 3 vai precisar para
mapear o stub Java de volta ao `.sk`):

- `SukoFile(packageName, imports, components)`
- `ComponentDecl(name, typeParams, params, body: TemplateBlock, span)`
- `Param` (interface selada):
  `ValueParam(type, name, default)` |
  `SlotParam(elementType, name, cardinality: ONE | MANY, default)`
- `Statement` (interface selada):
  `VarDecl, IfStmt, ForStmt, SwitchStmt, ComponentCall, HtmlElement,
  Interpolation, TextRun`
- `Expr` (interface selada), espelhando a gramática de expressões:
  `PrimaryExpr, AccessExpr, SafeAccessExpr, CallExpr, NotExpr,
  UnaryMinusExpr, MulExpr, AddExpr, RelExpr, EqExpr, AndExpr, OrExpr,
  ElvisExpr, TernaryExpr, ParenExpr`

### 4. `SukoAstBuilder`

Visitor gerado pelo ANTLR (`SukoParserBaseVisitor`). Tradução
puramente estrutural do parse tree para o AST — nenhuma lógica
semântica aqui (isso é todo o âmbito dos subprojetos 2 e 3). Como a
gramática corrigida já garante a forma da árvore, o único "erro"
possível nesta camada é um bug interno (forma inesperada de parse
tree), tratado como falha de asserção interna, nunca como diagnóstico
para o utilizador.

### 5. `JteEmitter`

Visitor sobre o AST, produzindo um `.jte` por `ComponentDecl`:

- `ValueParam` → `@param` normal do JTE.
- `SlotParam` cardinalidade `ONE` sem render-prop → `gg.jte.Content`.
- `SlotParam` cardinalidade `MANY` → `java.util.List<gg.jte.Content>`.
- `SlotParam` com render-prop → `java.util.function.Function<T, gg.jte.Content>`
  (ver spike acima).
- `?.` e `?:` são desaçucarados na emissão para Java puro
  (`!= null ? ... : ...`), já que o JTE não tem esses operadores.
- Menos unário, `if`/`for`/`switch`/chamadas de componente: tradução
  próxima de 1:1, dado que a gramática de expressões já é um
  subconjunto de Java.
- **Source map:** o emitter mantém, por componente, uma tabela
  linha-do-`.jte` → span-do-`.sk`, guardada ao lado do `.jte` gerado
  (formato a definir no plano — provável ficheiro `.map` irmão, não
  embutido no `.jte`). Consumida pelos subprojetos 2 e 3.

## Testes

- **Golden-file por caso de gramática:** cada correção da secção 1
  ganha um `.sk` mínimo com o `.jte` esperado lado a lado.
- **Golden-file para o `Card.sk`** e para os exemplos de slots
  (simples, múltiplo, render-prop).
- **Teste de integração ponta-a-ponta:** estende o smoke test atual
  (que hoje só faz parse) para também compilar o `.jte` gerado com o
  `gg.jte` de verdade e renderizar, comparando o HTML de saída.
- Casos fora de escopo do parser (tags mal fechadas, etc.) não geram
  teste aqui — ficam para o subprojeto 2.

## Fora de escopo (explícito)

- Qualquer diagnóstico voltado ao utilizador (mensagens de erro
  amigáveis, verificação de tipos, verificação de componentes/slots)
  — subprojetos 2 e 3.
- Plugin de build, modo watch, formatação de erro no terminal —
  subprojeto 4.
- Interpolação de CSS/JS inline em `<style>`/`<script>`.
- Qualquer coisa de interatividade no browser (htmx ou outro) — não é
  âmbito do Suko; a app final integra isso via atributos HTML normais
  que o Suko já passa através dos elementos crus.

## Riscos residuais

- A desambiguação texto-vs-comentário e texto-vs-string dentro de
  `textRun` pode introduzir ambiguidades novas de lookahead do ANTLR;
  a mitigação é regenerar a gramática após cada correção e confirmar
  ausência de avisos, como já é prática no projeto.
- O formato exato do source map fica em aberto para o plano de
  implementação — este documento só compromete a existência dele e o
  que ele mapeia (linha do `.jte` → span do `.sk`).
