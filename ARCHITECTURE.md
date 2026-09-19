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

## Estrutura de módulos

Monorepo Gradle multi-módulo (migração documentada em
`docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md`). A raiz é
um agregador puro — `settings.gradle.kts` + um `build.gradle.kts` que só
define `group`/`version`/`repositories` partilhados via `subprojects {}`,
sem source próprio.

- **`suko-core/`** — o compilador: gramática ANTLR, AST, `SukoAstBuilder`,
  `SemanticChecker`, `JteEmitter`, `JteCompiler`,
  `io.suko.lang.project.*` (`ProjectIndex`, `SukoProjectCompiler`). Sem
  dependência de nenhum outro módulo.
- **`suko-gradle-plugin/`** — `io.suko.lang.gradle.*`
  (`SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`) extraído do
  antigo módulo raiz sem mudança de comportamento. Depende de
  `suko-core`. **Lacuna conhecida:** tal como o `suko-maven-plugin` (ver
  item 4 do roadmap abaixo), este módulo nunca foi aplicado como plugin
  Gradle com ID descobrível (`gradlePlugin{}`/
  `META-INF/gradle-plugins/*.properties`) — existe só como classe
  `Plugin<Project>`, testada diretamente, não via `plugins { id(...) }`.
  Preservado tal como estava antes desta migração; não corrigido aqui.
- **`suko-maven-plugin/`** — plugin Maven (`SukoCompileMojo`), depende de
  `suko-core`. Ver a ressalva já documentada no item 4 do roadmap sobre o
  descritor de plugin Maven não estar completo.
- **`suko-components/`** — scaffold vazio para a biblioteca de
  componentes (subprojeto 7), depende de `suko-core`.
- **`suko-website/`** — scaffold vazio para o site de documentação
  (subprojeto 9), depende de `suko-core` e `suko-components`.

`examples/` (ficheiros `.sk` de referência) permanece na raiz do
repositório, fora de qualquer módulo — não é uma unidade de build.

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
  desaçucarar a sintaxe. **Forma concreta escolhida (subprojeto 6,
  substitui a heurística de scan usada até então):** `slot<T>` foi
  removido de toda a superfície da linguagem — substituído por
  `Component`, um nome reconhecido pelo `SukoAstBuilder` do mesmo jeito
  que `Content`/`List`/`Function` já eram (mapeia para `gg.jte.Content`
  no Java gerado; não é interface nova nem runtime Suko). Cardinalidade
  e forma são decididas **pela assinatura declarada**, não por scan do
  corpo:

  | Assinatura `.sk` | Java gerado | Cardinalidade |
  |---|---|---|
  | `Component x` | `gg.jte.Content x` | ONE |
  | `List<Component> x` | `List<gg.jte.Content> x` | MANY |
  | `Function<T, Component> x` | `Function<T, gg.jte.Content> x` | render-prop |

  Opcionalidade reusa o mecanismo de valor por omissão que `ValueParam`/
  `SlotParam` já tinham (`Component x = null`), sem gramática nova. A
  heurística antiga (procurar no corpo um `Expr.CallExpr` cujo `callee`
  tivesse o mesmo nome do slot) foi removida por completo — a forma
  render-prop passa a ser sempre explícita na assinatura
  (`Function<T, Component>`), nunca inferida do uso.

  Consequências visíveis ao autor de `.sk`: um slot `Component` simples
  lê-se como `{header}` (sem `.apply(null)`); um slot render-prop
  (`Function<T, Component>`) precisa de `{header(item)}` ou `{row(item)}`
  para passar o parâmetro — agora porque o tipo declarado o diz, não por
  inferência. O teste `{slot ?: "fallback"}` continua sem suporte para
  AMBOS os casos — `Content` vs `String` continuam sem supertipo comum
  aceite pelo `?:` dessaçucarado, permanecendo como limitação conhecida.
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
- **Ler um slot render-prop exige chamá-lo.** Um slot declarado
  `Function<T, Component>` é sempre render-prop (decisão explícita na
  assinatura, subprojeto 6 — já não é heurística de scan do corpo); o
  `.sk` tem de escrever `{header(item)}` ou `{row(item)}` para passar o
  parâmetro (o emitter traduz um identificador conhecido como slot para
  `.apply(...)`). Consequências: (a) testar "slot não preenchido" é
  `header.apply(null) == null` para render-prop, não `header == null` —
  o valor por omissão de um slot é uma função que devolve `null`, nunca
  a referência `null`; (b) iterar um `List<Function<T, Component>>`
  render-prop obriga o `.sk` a escrever o tipo do item como
  `Function<T, Content>`; (c) `{slot ?: "fallback"}` não compila (ramos
  de tipos incompatíveis). Nada disto é verificado hoje: o erro aparece
  como erro de compilação Java no `.jte` gerado.
- **Tipos qualificados não fazem parse.** `java.util.List<T>` é
  rejeitado (`type: Identifier typeArguments? arrayMarker*`).
- **Projeto multi-ficheiro (subprojeto 5).** `package foo.bar;`/`import
  foo.bar.Card as C;` (gramática já existente desde o subprojeto 1,
  nunca usados antes) passam a ser resolvidos de verdade por
  `io.suko.lang.project.ProjectIndex` (Fase 1: scan recursivo,
  indexação de assinatura — nome, pacote, `public`, nº de params) e
  `SukoProjectCompiler` (Fase 2: `JteCompiler.compile(ProjectIndex,
  Path)` por ficheiro, com o índice injetado). `package foo.bar;` só é
  válido dentro de `<sourceRoot>/foo/bar/` (`PACKAGE_DIRECTORY_MISMATCH`
  caso contrário); ficheiros sem `package` não têm restrição de pasta.
  Visibilidade: `public component X` é chamável de qualquer ficheiro do
  projeto (via import ou nome totalmente qualificado); sem modificador,
  só do próprio ficheiro (`COMPONENT_NOT_VISIBLE` caso contrário) — não
  há nível "mesmo pacote". Cada `.jte` gerado é escrito numa subpasta
  que espelha o pacote de origem (`ui/NavLink.jte`), o que já fecha o
  antigo bug de `TemplateNotFoundException` em nomes compostos: `gg.jte`
  já lia o ponto de `@template.ui.NavLink(...)` como separador de path,
  só faltava o ficheiro existir nessa subpasta.
- **Lacuna conhecida: `sukoWatch` não foi migrado para o modelo
  multi-ficheiro.** `SukoCompileTask` (Gradle) passou a usar o
  `SukoProjectCompiler` neste subprojeto, mas `SukoWatchTask` continua
  a chamar o caminho antigo, por ficheiro (`new JteCompiler(...).compile()`
  com um `Files.list` não recursivo). Consequências: (a) o modo watch
  escreve output **plano**, sem espelhar pacotes, divergindo do
  `sukoCompile` na mesma pasta de output; (b) nenhum dos 5 diagnósticos
  a nível de projeto deste subprojeto (`IMPORT_NOT_FOUND`, `COMPONENT_NOT_VISIBLE`,
  `AMBIGUOUS_IMPORT`, `PACKAGE_DIRECTORY_MISMATCH`, `DUPLICATE_COMPONENT`) é visto em watch;
  (c) chamadas cross-ficheiro não resolvem em watch. Deliberadamente
  **não** corrigido na revisão final do subprojeto 5: a semântica de
  recompilação incremental em modo watch (que reindexar, quando, e o
  que fazer quando um ficheiro que outros importam muda) precisa do seu
  próprio desenho e testes. Fica sinalizado como tarefa futura.
- **Limitação aceite: verificação de slot fills não atravessa
  ficheiros.** A Fase 1 do `ProjectIndex` só indexa a assinatura
  superficial de cada componente (nome, pacote, `public`, nº de
  params), não os slots — uma chamada a um componente definido noutro
  ficheiro só é verificada quanto a existência e visibilidade, nunca
  quanto a `SLOT_NOT_FOUND`/`CARDINALITY_VIOLATION`. Extensão futura
  exigiria a Fase 1 indexar os `Param.SlotParam` inteiros.
- **Limitação aceite: componente-como-valor com nome composto/importado
  não resolve.** `var c = ui.NavLink();` ou `var c = ImportedAlias();`
  em posição de **expressão** só reconhece um callee `Expr.PrimaryExpr`
  simples já conhecido no ficheiro atual — a resolução cross-ficheiro
  do subprojeto 5 só cobre a forma **statement**
  (`Statement.ComponentCallStmt`, que já carrega o nome composto
  completo desde o subprojeto 1) e nomes curtos pós-import em posição
  de valor. Um nome composto (`ui.NavLink()`) como valor de expressão
  continua fora de âmbito — precisaria de reconhecer `Expr.AccessExpr`
  como callee, não implementado.
- **Limitação aceite: `SemanticChecker` não desce a HTML aninhado.**
  `checkStatement` trata `ComponentCallStmt`, `VarDecl`,
  `Interpolation`, `IfStmt`, `ForStmt` e `SwitchStmt`, mas não tem caso
  para `Statement.HtmlElement` — os `children()` de uma tag nunca são
  percorridos. Uma chamada de componente aninhada dentro de uma tag
  (`<div>SomeComponent()</div>`) escapa por completo à validação
  semântica: `COMPONENT_NOT_FOUND`, `COMPONENT_NOT_VISIBLE`,
  `SLOT_NOT_FOUND`, `CARDINALITY_VIOLATION`, etc. nunca disparam para
  ela, mesmo que o componente não exista ou não seja `public`. Pré-
  existente a este subprojeto (não introduzido nem corrigido pelas
  Tarefas 1-8) — descoberto durante a Tarefa 8. Correção exigiria
  adicionar um caso `Statement.HtmlElement` a `checkStatement` que
  chame `checkStatementList` sobre `children()`.
  **Consequência prática (revisão final do subprojeto 5) — isto não é
  cosmético.** Como a esmagadora maioria das chamadas reais em Suko é
  escrita dentro de uma tag HTML (`<div>SomeComponent()</div>`), as
  próprias verificações de existência e visibilidade que este
  subprojeto introduz (`COMPONENT_NOT_VISIBLE`, `IMPORT_NOT_FOUND`,
  `COMPONENT_NOT_FOUND`) são trivialmente contornadas no caso comum. O
  modelo de visibilidade tal como está entregue é, na prática,
  "verificado apenas no nível de topo do corpo de um componente", não
  "verificado". O que faz a funcionalidade parecer correta hoje é o
  **emitter** (que resolve as chamadas aninhadas corretamente), não o
  **verificador** — fechar esta lacuna é pré-requisito para se poder
  dizer que a visibilidade é realmente imposta.
- **Children implícitos (subprojeto 6) resolveram o caso principal, mas
  há uma ordem que ainda engole um slot nomeado em silêncio.** Um
  componente que declara um parâmetro `Component children` (ou
  `List<Component> children`) recebe automaticamente todo o conteúdo
  solto de um bloco de chamada (`Layout() { <p>x</p> }` já não é
  descartado — vira o fill `children`). A gramática continua a mesma
  (`slotBlock: LBRACE (namedSlot | templateStatement)* RBRACE`); o que
  mudou foi o `SukoAstBuilder` passar a ler também os `templateStatement`
  soltos, não só os `namedSlot`. **Ressalva de ordem, confirmada
  empiricamente na tarefa 3 do subprojeto 6:** quando conteúdo solto
  aparece **antes** de um slot nomeado no mesmo bloco de chamada
  (`Layout() { "corpo solto" header { "Título" } }`), o slot nomeado
  `header` desaparece — não por causa da síntese de `children`, mas por
  uma limitação de gramática independente: `textRun`
  (`(~(LBRACE|RBRACE|LT|LTSLASH))+`) é um único closure guloso sem
  lookahead intermédio, por isso engole o `Identifier` de `header` como
  se fosse mais texto solto, e o `{ "Título" }` que sobra é lido como
  `interpolation` comum, nunca como `namedSlot`. Conteúdo solto **depois**
  de um slot nomeado (`header { "Título" } "corpo solto"`) funciona
  corretamente, e conteúdo solto sozinho (sem nenhum slot nomeado no
  mesmo bloco) também funciona corretamente — só a ordem
  "solto-antes-de-nomeado" tem este problema. Correção real exigiria
  mudar `textRun` na gramática (fora do escopo do subprojeto 6); fica
  registada aqui como convenção de autoria a evitar (escrever slots
  nomeados antes de conteúdo solto num mesmo bloco de chamada) até haver
  correção de gramática dedicada.
- **`List<Component> children` (MANY) também recebe o conteúdo solto**
  (corrigido na revisão final do subprojeto 6: o `SukoAstBuilder`
  sintetiza o fill `children` sem saber a cardinalidade do alvo — isso é
  informação semântica, não sintática). O que NÃO acontece é a repartição:
  todo o conteúdo solto de uma chamada vira **um único elemento** da lista,
  nunca vários, porque não há separador na gramática entre "grupos" de
  conteúdo solto. Para obter vários elementos, escrevem-se fills nomeados
  explícitos (`children { ... } children { ... }`), que continuam a
  funcionar e podem coexistir com conteúdo solto na mesma chamada (o
  conteúdo solto acrescenta mais um elemento à lista). **Limitação aceite:**
  essa mistura "solto + `children { ... }` explícito" só é erro
  (`CARDINALITY_VIOLATION`) quando `children` é `Component` (ONE, onde os
  dois fills são genuinamente ambíguos); em `List<Component>` é aceite em
  silêncio, por ser um resultado bem definido (dois elementos de lista).
- **Uma chamada de componente usada como VALOR não pode levar um bloco de
  slot, e escrevê-lo não dá erro.** `var c = Card();` funciona (é
  `Statement.VarDecl`), e `Card() { ... }` funciona como statement, mas a
  combinação `var c = Card() { "x" };` não existe na gramática — e não é
  rejeitada: `textRun` (o mesmo closure guloso da ressalva de ordem acima)
  engole `var c = Card() ` como **texto literal**, o `{ "x" }` que sobra
  vira uma `interpolation` comum e o `;` vira mais texto. O `.jte` gerado
  passa a conter literalmente `var c = Card() ${"x"};` no meio do HTML, e
  qualquer leitura posterior de `{c}` falha a compilar ("cannot find
  symbol: c") — nunca há `Statement.VarDecl`. Correção real exigiria mudar
  a gramática (fora do escopo do subprojeto 6). Mitigação atual: o
  `SemanticChecker` deteta heuristicamente um `textRun` cujo texto contém
  `var x = Componente(` (com `Componente` a resolver na tabela de símbolos)
  e emite o aviso `VAR_DECL_NOT_PARSED`.
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
   **Ressalva (Tarefa 7 do subprojeto 5):** o módulo `suko-maven-plugin`
   compila e tem testes unitários, mas **ainda não produz um descritor de
   plugin utilizável** (`META-INF/maven/plugin.xml`) — a geração,
   não-funcional, foi removida; ver o comentário em
   `suko-maven-plugin/build.gradle.kts`. Ou seja, `mvn suko:compile` ainda
   não é executável end-to-end. O mesmo vale para `suko-gradle-plugin`
   (ver "Estrutura de módulos" acima): existe como classe
   `Plugin<Project>` testada diretamente, nunca foi aplicado como plugin
   com ID descobrível em lado nenhum. Ver também a lacuna do `sukoWatch`
   nas limitações do subprojeto 5, acima.
5. **Projeto multi-ficheiro (resolução de nomes)** — CONCLUÍDO
   (`docs/superpowers/specs/2026-09-19-suko-projeto-multificheiro.md`).
   `package`/`import` resolvidos de verdade via `ProjectIndex` +
   `SukoProjectCompiler`; visibilidade `public`/file-private; output
   espelha pacotes.
6. **Modelo de Componente** — CONCLUÍDO
   (`docs/superpowers/specs/2026-09-18-suko-modelo-componente.md`).
   `slot<T>` removido por completo, substituído por `Component`/
   `List<Component>`/`Function<T, Component>` reconhecidos pela
   assinatura declarada (sem heurística de scan do corpo); children
   implícitos via parâmetro reservado `children`; chamada de componente
   como valor de expressão (reusa a gramática `CallExpr` já existente,
   sem nó de AST novo); interpolação real `${expr}`/`$ident` em strings
   e atributos; auto-`toString` null-safe para identificadores simples
   de tipo Java arbitrário não coberto pelos overloads do
   `gg.jte.TemplateOutput`. `examples/layout/LayoutComponents.sk`
   migrado como prova end-to-end.

Fora destes seis, como subprojeto dedicado e sem data: renderização
real de componentes genéricos (erasure para tipo-limite); subprojetos
7-9 do roadmap revisto (registry/biblioteca de componentes, CLI de
distribuição, site de documentação — ver a spec do subprojeto 6 para o
roadmap completo), que dependem do 5 e do 6.
