# Suko — Pipeline de compilação

```
.sk (fonte Suko)
       │
       ▼
 ANTLR Lexer/Parser (gerado a partir de SukoLexer.g4 + SukoParser.g4)
       │  produz: ParseTree + diagnostics de parse
       ▼
 SukoAstBuilder (Visitor)
       │  produz: AST tipado (SukoFile, ComponentDecl, Statement, Expr, ...)
       ▼
Análise semântica [subprojeto 2 — CONCLUÍDO]
        │  - tabela de símbolos de componentes
        │  - valida chamadas de componente
        │  - valida slots (presença/cardinalidade/parâmetros)
        │  - valida estrutura HTML, escape e URLs perigosas
        │  - rejeita o que não é suportado (generics, nomes de
        │    componente compostos), delegando tipagem Java profunda
        │    ao javac na fase seguinte (subprojeto 3)
        ▼
  Verificação Java [subprojeto 3 — CONCLUÍDO]
        │  - JteCompiler orquestra pipeline completo
        │  - JavacTask compila stubs Java via javac
        │  - Mapeia erros de compilação para .sk via source map
        ▼
  JteEmitter (Visitor sobre o AST)
       │  produz: arquivo .jte equivalente, 1:1 por componente
       ▼
 .jte (arquivo intermediário, gerado e versionável)
       │
       ▼
 Compilador JTE padrão (gg.jte) — inalterado
       │
       ▼
 Classe Java compilada, renderização em runtime
```

## Decisões de design que moldam o pipeline

- **Geração de JTE puro como intermediário** (não bytecode direto):
  o `.jte` gerado fica no `build/generated-src`, é legível e
  debugável, e o Suko não precisa reimplementar nada que o JTE já
  resolve bem (cache de template, performance, integração com
  Spring/Quarkus).
- **Escopo do Suko é só a camada de view.** Rotas, controllers,
  DI etc. continuam exatamente como no ecossistema JTE hoje
  (jte-spring-boot-starter, jte-quarkus, etc.) — o Suko não tenta
  competir nesse espaço, só substitui a sintaxe do `.jte` em si.
- **1 componente Suko → 1 template JTE.** Mantém rastreabilidade
  simples: erro de renderização em produção aponta pro `.jte`
  gerado, que por sua vez mapeia 1:1 de volta pro `.sk` de origem
  via source maps. O nome do `.jte` é o nome simples do componente
  (`ComponentDecl.name()`), num diretório plano — ainda não há regra
  para dois componentes com o mesmo nome simples em pacotes
  diferentes. O source map existe como `JteEmitter.EmitResult`
  (linha do `.jte` → `SourceSpan` do `.sk`), mas nada em `src/main`
  escreve ficheiros: não há ainda um driver de compilação
  (`.sk` no disco → `.jte` + `.map` no disco); os testes fazem-no à
  mão. Esse driver é o ponto de entrada natural do plugin de build
  (subprojeto 4) e o sítio onde o verificador (subprojeto 2) se liga.
- **Slots nomeados** (`Layout(...) { Sidebar { } Content { } }`)
  viram parâmetros de conteúdo do próprio JTE (o JTE já suporta
  parâmetros de conteúdo/`@Content` nativamente), então a tradução é
  direta — não é preciso inventar um mecanismo de runtime novo, só
  desaçucarar a sintaxe. **Forma concreta escolhida (revisada pela tarefa
  18 do subprojeto 1, corrigida nesta ronda):** *um `slot<T>` passa a ser
  emitido como `Function<T, Content>` **se e só se** o corpo do componente
  onde ele é declarado o invoca como função — ou seja, existe pelo menos um
  `Expr.CallExpr` cujo `callee` é um `Expr.PrimaryExpr` com o mesmo nome do
  slot, em qualquer lugar do corpo do componente (dentro de `if`/`for`/`switch`
  incluso). Se não houver nenhuma invocação assim, o slot é "simples" e o
  Java gerado usa `gg.jte.Content`/`List<gg.jte.Content>` nu, legível como
  `{header}` sem necessidade de `.apply(null)`. Essa deteção é sintática
  (scan do corpo por `CallExpr` sobre o próprio nome do slot), não uma
  declaração explícita do autor do `.sk`.

  Consequências visíveis ao autor de `.sk`: um slot simples pode agora ser
  lido como `{header}` (sem `.apply(null)`); um slot render-prop ainda
  precisa de `{header(item)}` ou `{row(item)}` para passar o parâmetro.
  O teste `{slot ?: "fallback"}` continua sem suporte para AMBOS os casos
  — `Content` vs `String` continuam sem supertipo comum aceite pelo
  `?:` dessaçucarado, permanecendo como limitação para o subprojeto 2.
- **Texto dentro de tags resolvido no parser, não no lexer.** A
  primeira tentativa usava um modo léxico `TEXT` (entrado via ação
  do parser logo após o `>` de uma tag de abertura) para lexar
  texto puro sem competir com palavras-chave. Isso quebrava
  exatamente o caso que o Suko precisa: `for`/`if` como filhos
  diretos de uma tag (`<ul> for (item : items) { <li>...</li> } </ul>`),
  porque, uma vez dentro do modo TEXT, o lexer fica cego para
  `for`/`if` como token — eles virariam texto literal por engano.
  A solução adotada: `for`/`if`/`var`/`switch`/`componentCall`
  continuam sendo tokens normais o tempo todo (nenhum modo léxico
  extra), e um `textRun` (`(~(LBRACE|RBRACE|LT))+`) funciona como
  alternativa de último recurso dentro de `templateStatement`. O
  ANTLR resolve a ambiguidade certa via lookahead completo: só cai
  em `textRun` quando a sequência de tokens não fecha um `forStmt`/
  `ifStmt`/etc. de verdade — ou seja, "for" como palavra solta em
  texto continua funcionando. O texto literal final (com
  espaçamento e hífens preservados) é recuperado no AST builder
  pela posição de caractere no fonte, não por concatenação de
  tokens.

## Limitações conhecidas (fim do subprojeto 2)

`examples/Card.sk` é a **referência da superfície da linguagem**, não
do que o emitter já renderiza — e, com a decisão de roadmap sobre
generics abaixo, é hoje um programa que o verificador do subprojeto 2
terá de rejeitar. Cada item abaixo foi confirmado empiricamente
(contra o `gg.jte` 3.1.12 real nas tarefas 13/17/19 do subprojeto 1, ou
por sondagem direta ao parser/emitter durante a revisão de
arquitetura):

- **Generics de componente são só sintaxe.** `component Card<T>(...)`
  faz parse e o `<T>` é carregado no AST, mas nunca é emitido: o
  `gg.jte` não tem forma de declarar uma variável de tipo própria do
  template. O `<T>` do `@param <T> List<T> x` serve apenas para evitar
  quebra por espaços num tipo já concreto; `@param <T> T item` gera
  Java corrompido e não compila. Vale mesmo no caso opaco (`Box<T>(T
  value)`), porque a falha está na declaração da variável de tipo, não
  no acesso a membros. Renderização genérica real exige o Suko fazer
  erasure para um tipo-limite no momento do emit — análise que não
  existe. A *sintaxe* de limite, essa, já existe na gramática
  (`typeParameter: Identifier (COLON type)?`, ou seja `<T: Item>`),
  mas o `SukoAstBuilder` descarta o limite (`ComponentDecl.typeParameters`
  é `List<String>`). O mesmo vale para argumentos de tipo no ponto de
  chamada: `Box<String>(v = "x")` faz parse e os `<String>` são
  descartados em silêncio (`ComponentCallStmt` não tem campo para eles).
  **Decisão de roadmap:** o verificador (subprojeto 2) rejeita ambas as
  formas com um erro explícito de "ainda não suportado"; a renderização
  real de generics é um subprojeto dedicado, fora dos subprojetos 2-4.

- **Layouts e componentes reutilizáveis.** Os exemplos `examples/layout/` mostram
  como criar componentes reutilizáveis (`Layout`, `Card`, `Modal`, `Button`,
  `Input`, `Select`) com slots nomeados. O `examples/forms/` demonstra
  formulários completos com validação de erros. O `examples/dashboard/`
  mostra um painel de controle com navegação e listas dinâmicas.

- **Slots nomeados.** Todos os componentes usam slots nomeados (`header`,
  `sidebar`, `footer`, `body`, `content`, `header`, `sidebar`, `footer`),
  permitindo renderização condicional e lógica de negócios clara.

- **Renderização ponta-a-ponta.** Os exemplos são testados com
  `JteEmitterGoldenFileTest` e `SukoParserSmokeTest`, garantindo que
  a pipeline de compilação funciona corretamente.
- **Ler um slot exige chamá-lo (apenas para slots render-prop).**
  Como um slot só é emitido como `Function<T, Content>` quando invocado
  como função no corpo do componente (ver "Decisões de design"), um slot
  simples pode agora ser lido como `{header}` (sem `.apply(null)`).
  Para slots render-prop, o `.sk` tem de escrever `{header(item)}` para
  passar o parâmetro (o emitter traduz um identificador conhecido como slot
  para `.apply(...)`). Pelo mesmo motivo: (a) testar "slot não preenchido"
  é `header.apply(null) == null` para render-prop, não `header == null` — o
  valor por omissão de um slot é uma função que devolve `null`, nunca a
  referência `null`; (b) iterar um `List<slot<T>>` render-prop obriga o `.sk`
  a escrever o tipo do item como `Function<T, Content>`; (c) `{slot ?:
  "fallback"}` não compila (ramos de tipos incompatíveis). Nada disto é
  verificado hoje: o erro aparece como erro de compilação Java no `.jte`
  gerado.
- **Tipos qualificados não fazem parse.** `java.util.List<T>` é
  rejeitado (`type: Identifier typeArguments? arrayMarker*`).
- **Não há imports automáticos.** O `.jte` gerado não importa nada; o
  tipo tem de ser resolúvel tal como escrito. Na prática o emitter
  compensa com uma lista fechada de nomes sintetizados em
  `JteEmitter.javaType` (`Content` → `gg.jte.Content`, `List` →
  `java.util.List`, `Function` → `java.util.function.Function`) — o que
  torna esses três nomes efetivamente reservados: um tipo do utilizador
  com um desses nomes é reescrito em silêncio. `SukoFile.imports()`
  nunca é lido, e o `as` de `import ... as X;` é descartado pelo AST
  builder, apesar de o diagrama acima prometer "resolve imports/alias".
- **Chamada de componente com nome composto falha em runtime.**
  `ui.NavLink(...)` faz parse e é emitido literalmente como
  `@template.ui.NavLink(...)`; o `gg.jte` lê o ponto como separador de
  caminho (`ui/NavLink.jte`) e falha com `TemplateNotFoundException` em
  tempo de render, não em tempo de build.
- **Conteúdo anónimo num bloco de chamada é descartado em silêncio.**
  A gramática aceita `Layout() { <p>x</p> side { ... } }`
  (`slotBlock: LBRACE (namedSlot | templateStatement)* RBRACE`), mas o
  AST builder só lê os `namedSlot` — o `<p>x</p>` desaparece sem erro.
  Não existe conceito de "children por omissão" na linguagem.
- **`var` faz parse mas rebenta o compilador.** `var x = 1;` está em
  `templateStatement` na gramática e na spec do subprojeto 1, mas
  `Statement` não tem variante `VarDecl` e o `SukoAstBuilder` lança
  `IllegalStateException("templateStatement ainda não suportado")`.
- **Interpolação dentro de literal de string não funciona.**
  `{"Olá ${name}!"}` é erro de parse, embora o lexer tenha os tokens
  (`SIMPLE_INTERP_START`, `EXPR_INTERP_START`) e `stringPart` os aceite;
  `Expr.StringPart` só tem a variante `Literal`.
- **Erros de parse não param a compilação.** Não há `ErrorListener` em
  `src/main`: o ANTLR imprime o erro no stderr e o `SukoAstBuilder`
  continua a percorrer uma árvore com nós de erro, produzindo `.jte`
  corrompido em silêncio. Diagnóstico de sintaxe é infraestrutura em
  falta, não coberta por nenhum subprojeto até agora.
- **`</` literal em texto livre** é erro de parse (consequência aceite
  da desambiguação do `textRun`).
- **Um literal de string Suko não pode conter `<` nem `>`**
  (consequência aceite do predicado `canStartStringLiteral` do lexer,
  que é o que permite aspas soltas em prosa: `<p>5" tela</p>`).
- **Um `//` no fim absoluto do ficheiro, sem newline a seguir,** não
  conta como comentário (consequência aceite da desambiguação
  comentário-vs-URL).
- **Espaço em branco órfão entre dois statements não-textRun irmãos é
  perdido** (`{x} {y}` emite `${x}${y}`).
- **Backtick não escapado dentro de um slot fill corrompe o `.jte`
  gerado** (o conteúdo do fill é escrito dentro de `` @`...` `` sem
  escape). É um bug de fidelidade de output, não de segurança: `.sk` é
  código do developer, não input não-confiável.

## Validação feita até agora

O subprojeto 1 está concluído e mergeado (`76f9306`). A validação já
não é análise estática da gramática: a suite de testes compila `.sk`
para `.jte` e renderiza o resultado com o motor `gg.jte` 3.1.12 real
(`JteRenderSupport`), incluindo golden-files (`src/test/resources/golden/`)
e um teste ponta-a-ponta. A prática de regenerar as gramáticas
(`gradle generateSukoLexer generateSukoParser --console=plain`) e
confirmar ausência da palavra `warning` na saída continua a valer para
qualquer alteração aos `.g4`.

O subprojeto 2 está concluído: implementado `DiagnosticCollector`,
`SukoErrorListener`, `SymbolTable` e `SemanticChecker`. Os
componentes são registrados na tabela de símbolos e as chamadas de
componente validadas (existência, slots obrigatórios, cardinalidade).

O subprojeto 3 está concluído: implementado `JteCompiler` (pipeline
completo de compilação) e `JavacTask` (verificação Java com stubs e
mapeamento de erros para `.sk`). A compilação de `.jte` via `javac`
valida a assinatura Java dos componentes e mapeia erros de compilação
de volta ao `.sk` original usando source maps.

Nota sobre `src/test/resources/golden/Card.jte`: é um golden de *texto*
emitido, não um `.jte` que compile — contém `@param java.util.List<T>`
com um `T` nunca declarado, exatamente a limitação de generics acima.

## Roadmap por subprojeto

Cada subprojeto tem o seu ciclo spec → plano → implementação em
`docs/superpowers/specs/` e `docs/superpowers/plans/`.

1. **Núcleo da linguagem** — CONCLUÍDO. Gramática, AST,
   `SukoAstBuilder`, `JteEmitter`, source map em memória.
2. **Verificador Suko** — CONCLUÍDO. `DiagnosticCollector`,
   `SukoErrorListener`, `SymbolTable`, `SemanticChecker`.
   Validadores de componentes e slots implementados.
3. **Verificação Java** — CONCLUÍDO. `JteCompiler` orquestra o pipeline completo (parse → semantic check → JTE emit). `JavacTask` compila stubs Java e mapeia erros para `.sk`.
4. **Integração no build** — CONCLUÍDO. Plugin Gradle (`sukoCompile`, `sukoWatch`), plugin Maven (`suko:compile`), modo watch com `WatchService`, E2E tests.

Fora destes quatro, como subprojeto dedicado e sem data: renderização
real de componentes genéricos (erasure para tipo-limite).
