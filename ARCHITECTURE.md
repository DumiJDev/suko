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
um agregador puro — `settings.gradle.kts` + um `build.gradle.kts` que
define `group`/`version`/`repositories` partilhados via `subprojects {}`,
mais o plugin `base` (só para dar um `:clean` a nível de raiz, que também
apaga o `jte-classes/` órfão de builds pré-migração), sem source próprio.

- **`suko-core/`** — o compilador: gramática ANTLR, AST, `SukoAstBuilder`,
  `SemanticChecker`, `JteEmitter`, `JteCompiler`,
  `io.suko.lang.project.*` (`ProjectIndex`, `SukoProjectCompiler`). Sem
  dependência de nenhum outro módulo.
- **`suko-gradle-plugin/`** — `io.suko.lang.gradle.*`
  (`SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`) extraído do
  antigo módulo raiz sem mudança de comportamento. Depende de
  `suko-core`. Aplica `java-gradle-plugin` e declara
  `gradlePlugin { plugins { create("suko") { id = "io.suko.lang" } } }`
  (subprojeto 8, tarefa 4) — `plugins { id("io.suko.lang") }` já resolve
  via composite build (`includeBuild`) e via TestKit; deixou de existir só
  como classe `Plugin<Project>` testada diretamente. **Não publicado no
  Gradle Plugin Portal** (D11): o ID serve composite builds/TestKit, não
  um `plugins { id("io.suko.lang") version "..." }` isolado — publicação
  real fica em aberto, dependente da mesma questão de D1 do subprojeto 7.
- **`suko-maven-plugin/`** — plugin Maven (`SukoCompileMojo`), depende de
  `suko-core`. Ver a ressalva já documentada no item 4 do roadmap sobre o
  descritor de plugin Maven não estar completo — **este lado permanece
  sem ID descobrível**, ao contrário do `suko-gradle-plugin` acima
  (assimetria não fechada pelo subprojeto 8, ver item 4 do roadmap).
- **`suko-registry/`** — modelo de dados do manifesto da biblioteca
  (`RegistryIndex`/`ComponentManifest`/`ComponentFile`/
  `ExternalRequirement`) e leitor/escritor JSON (`RegistryJson`, Gson
  2.11.0 pinado — **não** Jackson, para minimizar transitivas no
  fat-jar da CLI do subprojeto 8). Desde a tarefa 2 do subprojeto 8,
  este módulo **já não depende de `suko-core`** — é puro modelo de
  dados + I/O JSON, só Gson. O gerador do manifesto a partir de `.sk`
  (`RegistryGenerator`, que reusa `ProjectIndex`/`SukoAstBuilder` de
  `suko-core`) foi extraído para o módulo irmão
  **`suko-registry-generator`**, que depende de ambos via `api`
  (`api(project(":suko-registry"))` + `api(project(":suko-core"))`).
  Esta separação é o que agora torna verdadeiro o racional do Gson
  logo acima: antes da tarefa 2, `suko-registry` já expunha `api(suko-core)`,
  o que anulava por completo o benefício de "minimizar transitivas no
  fat-jar da CLI" — só passou a valer depois do split.
- **`suko-registry-generator/`** — gera o manifesto (`registry.json` +
  `components/*.json`) a partir dos fontes `.sk` de `suko-components/`
  (`RegistryGenerator`). Depende de `suko-registry` e `suko-core` via
  `api`. Invocado pela task `generateRegistry` de `suko-components`.
- **`suko-components/`** — biblioteca de componentes (subprojeto 7),
  **conteúdo puro**: sem `src/main/java` e sem nenhuma dependência
  declarada em `main`. Contém `src/main/suko/io/suko/ui/*.sk` (8
  componentes: `Button`, `Input`, `Label`, `Badge`, `Alert`, `Card`,
  `Field`, `Dialog`) mais `registry.json`/`components/*.json`
  gerados e commitados. As dependências em `src/test` (`suko-registry`,
  `testFixtures(suko-core)`, `gg.jte` pinado) existem só para o módulo
  se auto-validar — compilar e renderizar a própria biblioteca com o
  motor `gg.jte` real, e falhar se o manifesto commitado divergir dos
  fontes (`RegistryGoldenTest`).
- **`suko-cli/`** — a ferramenta de linha de comandos `suko` (subprojeto 8):
  `init`/`list`/`add`/`diff`/`update`. Depende de `suko-registry` (modelo +
  JSON) e Gson; **nunca depende de `suko-core`** em produção (`testFixtures(suko-core)`
  e `gg.jte` pinado entram só em `testImplementation`, para o
  `FullCycleTest` de ponta-a-ponta) — o compilador não faz parte do que a
  CLI precisa para copiar ficheiros. Empacotada num único fat jar
  hand-rolled (`Jar` task própria, sem plugin Shadow) e distribuída de três
  formas: o próprio jar, um alias `jbang` (`jbang-catalog.json` na raiz) e
  scripts wrapper (`scripts/suko`/`scripts/suko.bat`). Um binário nativo
  GraalVM é opt-in e exclusivamente do lado do consumidor
  (`jbang --native --build-dir <dir> suko@suko-lang`) — nunca construído nem
  publicado por este projeto. Ver `suko-cli/README.md` para a referência
  de comandos e o desenho de duplo hash do `suko.lock.json`.
- **`suko-website/`** — scaffold vazio para o site de documentação
  (subprojeto 10). **Achado, não corrigido aqui:** o
  `build.gradle.kts` deste módulo continua a declarar
  `implementation(project(":suko-components"))` — uma dependência que
  hoje não compila nada, porque `suko-components` não tem `src/main/java`
  desde que passou a conteúdo puro. A dependência que faria sentido é
  sobre `suko-registry` (se `suko-website` vier a listar/renderizar o
  catálogo de componentes) ou nenhuma (se só for consumir os `.sk` via
  `suko add`, como qualquer outro consumidor). Decisão de scoping do
  subprojeto 10, não deste.

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
- **Java 21 como piso declarado, uniformemente, via `options.release`
  (subprojeto 8, tarefa 1, D12).** Todo o monorepo compila para o mesmo
  nível de bytecode através do bloco `subprojects {}` do `build.gradle.kts`
  raiz (`tasks.withType<JavaCompile>().configureEach { options.release.set(21) }`),
  não por um toolchain Gradle por módulo. Motivo: o ambiente de
  desenvolvimento corre com um JDK 25 sem o `foojay-resolver-convention`
  configurado em `settings.gradle.kts`, então o auto-provisionamento de
  toolchain (que precisaria descobrir/descarregar um JDK 21) não era
  fiável — `options.release` não exige ter um JDK 21 instalado nem
  acrescentar esse resolver, só recusa em tempo de compilação qualquer
  API posterior ao 21 e fixa o bytecode em major 65. A uniformidade entre
  módulos é deliberada: aplicar isto só nalguns evitaria o conflito de
  resolução de variante Gradle que já existia entre módulos com versões
  de release diferentes (ver o comentário em
  `suko-maven-plugin/build.gradle.kts`).

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
- **Característica declarada, não limitação escondida: `sukoWatch`
  recompila o `sourceRoot` inteiro a cada evento do filesystem.**
  A tarefa 5 do subprojeto 8 fechou o bug real que existia antes
  (`SukoWatchTask` a usar um caminho antigo, por ficheiro, divergente do
  `sukoCompile` multi-ficheiro) — `SukoWatchTask` passou a chamar o mesmo
  `SukoProjectCompiler`/`ProjectIndex` que `sukoCompile` usa, escreve
  output que espelha pacotes da mesma forma, e vê os mesmos 5
  diagnósticos a nível de projeto. O que fica, deliberadamente, é uma
  troca de simplicidade: qualquer evento do `WatchService` dispara uma
  recompilação completa do `sourceRoot`, não uma recompilação incremental
  do que mudou. Para o tamanho de projeto que a linguagem tem hoje isto é
  imperceptível; uma recompilação incremental de verdade (que reindexar,
  quando, e o que fazer quando um ficheiro que outros importam muda)
  ficaria para um subprojeto dedicado, se algum dia justificar o esforço.
  (Uma lacuna real e separada, ainda aberta: `registerRecursively()` só
  regista subpastas existentes antes do loop de watch arrancar — uma
  pasta de pacote criada depois de `sukoWatch` já estar a correr, por
  exemplo por um `suko add` para um pacote novo, nunca é registada no
  `WatchService` e não dispara recompilação até o watch ser reiniciado.)
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
- **Erros de parse param a compilação no caminho principal, e só nesse.**
  `SukoErrorListener` (`io.suko.lang.diagnostic`) existe em `src/main` e é
  instalado por `JteCompiler.parseAndBuild`, que aborta em
  `diagnostics.hasErrors()` — um `.sk` sintaticamente inválido compilado
  por aí dá erro com ficheiro, linha e coluna, não `.jte` corrompido.
  **Há dois caminhos de parse que removem os error listeners de
  propósito** e não têm essa proteção: `ProjectIndex.parseQuietly`
  (invocado por `ProjectIndex.build`, Fase 1 da indexação multi-ficheiro,
  que só quer a assinatura e delega o diagnóstico à Fase 2) e
  `RegistryGenerator` (geração do manifesto da biblioteca). Nesses dois,
  um ficheiro que deixe de fazer parse produz recuperação silenciosa do
  ANTLR e um AST parcial — no segundo, isso significa um manifesto gerado
  a partir de uma árvore truncada, que sai commitado em JSON com aspeto
  normal. É por isso que o subprojeto 9 manteve a produção legada de
  chaveta nua na gramática e rejeitou a sintaxe antiga no
  `SemanticChecker`, em vez de a apagar do `.g4`.
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
- **Limitação de autoria (subprojeto 7): Tailwind não vê classe
  composta por interpolação.** `class="btn btn-${variant}"` faz parse,
  compila e renderiza sem nenhum erro — o `.jte` gerado produz HTML
  válido em runtime — mas **não estiliza**, porque o scanner do
  Tailwind é estático: ele grepa os ficheiros de `content` à procura de
  tokens com forma de nome de classe, nunca executa nada. `btn-${variant}`
  nunca aparece como literal em lado nenhum do código-fonte, logo a
  classe correspondente nunca entra na folha de estilo gerada. Isto não
  é detetável pelo compilador Suko (que não sabe nada de Tailwind) nem
  é um bug do `gg.jte` — é uma incompatibilidade estrutural entre
  "gerar classes dinamicamente" e "scanner de classes estático", que
  qualquer stack com Tailwind partilha. A biblioteca de componentes
  (`suko-components/`) contorna isto por convenção de autoria (cada
  variante é uma string de classe Tailwind completa dentro de um
  `switch`/`if`, nunca composta com `${}`), verificada por teste
  (`LibraryConventionsTest`) — mas é uma convenção que qualquer `.sk`
  escrito fora dessa biblioteca também precisa de seguir manualmente;
  não há verificação do compilador para isto.
- **`Dialog` (biblioteca de componentes, subprojeto 7) não tem botão de
  fecho interativo embutido** — não por escolha de design, mas porque a
  gramática não permite escrevê-lo. `htmlName`
  (`Identifier (MINUS Identifier)*`) não aceita `:` nem `@` num nome de
  atributo, logo nenhuma das duas formas reais de vincular um evento em
  Alpine.js (`x-on:click="..."` ou `@click="..."`) pode ser escrita num
  `.sk` hoje. `Dialog.sk` usa `x-data`/`x-show` no `<div>` externo
  (que não precisam de `:`/`@`) para abrir/fechar a partir de fora, mas
  não consegue oferecer um controlo de fecho próprio. Estender
  `htmlName` para aceitar estes carateres é trabalho de gramática fora
  do âmbito do subprojeto 7 (regra D5: só a correção da Tarefa 1 estava
  pré-aprovada).
- **`suko-cli` (subprojeto 8): sem hash nem assinatura sobre os próprios
  documentos JSON do registry.** `registry.json`/`components/*.json`
  chegam ao consumidor como texto simples, verificado apenas pelo
  `sha256` de cada ficheiro de componente **individual** — mas nada
  assina o índice/manifesto em si. A confiança de que o documento
  recebido é o que o mantenedor publicou é inteiramente do transporte:
  `HttpRegistrySource` exige HTTPS e recusa redirects, mas não há
  verificação criptográfica adicional acima disso. Um registry
  comprometido (ou um MITM que quebrasse TLS) poderia servir um
  `registry.json` alterado com hashes de ficheiro internamente
  consistentes entre si.
- **`suko-cli`: impossível pinar a versão de um componente
  independentemente da tag do registry (D5).** Ver `suko-cli/README.md` →
  "Registry pinning é por tag, não por versão de componente" para a
  explicação completa; resumindo, `suko add`/`suko update` operam sempre
  contra **uma** tag/ref do registry de cada vez, porque o manifesto é um
  único documento JSON atómico por release do registry — não existe hoje
  um mecanismo de histórico de versões por componente que permita "botão
  X na versão N, botão Y na versão N+1" na mesma instalação.
- **`suko-cli`: binário nativo GraalVM é exclusivamente
  `jbang --native --build-dir ... suko@suko-lang` do lado do consumidor,**
  nunca construído nem publicado por este projeto — não há tarefa Gradle
  de `native-image`, não há CI a produzir um binário, e não há matriz de
  plataformas. `--build-dir` não é opcional: sem ele, a primeira
  compilação nativa de um alias jbang que aponta para um jar já
  construído (em vez de um script jbang compilado a partir de fonte)
  falha num bug real e ainda aberto à data desta spec
  ([jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623)),
  na forma como o jbang pré-cria o diretório de cache para esse caso.

## Validação feita até agora

O subprojeto 1 está concluído e mergeado (`76f9306`). A validação já
não é análise estática da gramática: a suite de testes compila `.sk`
para `.jte` e renderiza o resultado com o motor `gg.jte` 3.1.12 real
(`JteRenderSupport`), incluindo golden-files (`suko-core/src/test/resources/golden/`)
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

Nota sobre `suko-core/src/test/resources/golden/Card.jte`: é um golden de *texto*
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
   não é executável end-to-end. **Assimetria (fechada só de um lado pelo
   subprojeto 8, tarefa 4):** o caminho Gradle está hoje suportado de
   ponta-a-ponta — `suko-gradle-plugin` aplica `java-gradle-plugin` e
   declara `gradlePlugin { plugins { create("suko") { id = "io.suko.lang" } } }`,
   com `plugins { id("io.suko.lang") }` a resolver de verdade via
   composite build (`includeBuild`) e via TestKit (ver "Estrutura de
   módulos" acima e `FullCycleTest` do subprojeto 8) — mas **sem
   publicação no Gradle Plugin Portal**, então um `plugins { id(...)
   version "..." }` isolado, fora de um composite build, ainda não
   resolve. O caminho Maven **continua sem mudança**: nenhum
   `plugin.xml` foi produzido, a ressalva acima mantém-se integralmente.
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
7-11 do roadmap revisto, que dependem do 5 e do 6 (ver a spec do
subprojeto 6 para a origem dos itens 7, 8 e 10; o item 9, interpolação,
tem origem própria nesta spec):

7. **Registry/biblioteca de componentes** — CONCLUÍDO. Spec formal em
   `docs/superpowers/specs/2026-09-20-suko-registry-componentes.md`,
   plano em `docs/superpowers/plans/2026-09-20-suko-registry-componentes.md`.
   `suko-components/` preenchido com 8 componentes `.sk` reais
   (`Button`, `Input`, `Label`, `Badge`, `Alert`, `Card`, `Field`,
   `Dialog`, Tailwind 3.x + Alpine 3.x) e um manifesto
   (`registry.json` + `components/*.json`) gerado a partir dos fontes
   e commitado, num módulo novo dedicado a essa lógica
   (`suko-registry`). Corrigido também o único bug de compilador
   pré-aprovado deste subprojeto (`SemanticChecker` passou a descer a
   HTML aninhado — antes, uma chamada de componente dentro de uma tag
   escapava por completo à validação semântica).
8. **CLI de distribuição** (`suko add`, estilo shadcn/ui) — CONCLUÍDO.
   Spec formal em `docs/superpowers/specs/2026-09-20-suko-cli-distribuicao.md`,
   plano em `docs/superpowers/plans/2026-09-20-suko-cli-distribuicao.md`.
   Módulo novo `suko-cli`: `suko init`/`list`/`add`/`diff`/`update`, copia o
   código-fonte `.sk` para o projeto do consumidor, que passa a possuir
   e customizar esse código (não é dependência de biblioteca); dependia
   do 7. **Racional do modelo copy-source, além do consumidor ficar
   dono do código:** `ProjectIndex.build(Path sourceRoot)` e
   `SukoProjectCompiler.compile(Path sourceRoot)` só aceitam **um**
   source root — não há hoje noção de "source root da app + source
   root de biblioteca" no compilador. Uma biblioteca-como-dependência
   real não é implementável sem trabalho de compilador; o modelo
   shadcn não é preferência de estilo, é o único que a arquitetura
   atual suporta. Distribuição: um único fat jar (`Jar` task própria, sem
   Shadow), servido por um alias `jbang` e por scripts wrapper; um
   binário nativo GraalVM é opt-in, exclusivamente compilado pelo
   consumidor (`jbang --native --build-dir ...`), nunca por este projeto.
   Ver `suko-cli/README.md` e "Limitações conhecidas" acima para o
   desenho de duplo hash do lockfile e as lacunas aceites (sem
   assinatura no JSON do registry, sem pinagem por componente).
9. **Unificação da sintaxe de interpolação** — CONCLUÍDO. Spec em
   `docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md`,
   plano em `docs/superpowers/plans/2026-09-21-suko-interpolacao-unificada.md`.
   `${expr}` passa a ser a única forma de interpolar, nas três posições
   (statement/corpo de tag, valor de atributo sem aspas, literal de
   string); a chaveta nua deixa de interpolar em qualquer posição e passa
   a erro com a correção literal na mensagem. `$ident` dentro de strings
   mantém-se (D3 rejeitada). Antecipado à frente do site de documentação
   por pedido explícito do utilizador.
10. **Site de documentação** — preenche `suko-website/`. Inclui
    compilação para HTML estático em build-time (deployável em
    serverless/CDN sem JVM em runtime) como primeiro caso de uso real
    dessa capacidade, antes de generalizá-la no compilador.
11. **Suporte de IDE** (VSCode + IntelliJ) — language server sobre o
    `DiagnosticCollector`/`SemanticChecker` já existentes. Sequenciado
    depois do 7/8 (quer uma superfície de AST/diagnostics estável), mas
    sem dependência bloqueante neles. IntelliJ não fala LSP nativamente
    (LSP4IJ vs. plugin PSI-based próprio) — decisão a tomar no scoping
    deste item.
