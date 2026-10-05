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
        │  - (não valida estrutura HTML; o escape de caracteres é do JTE em
        │    runtime, ContentType.Html. Sinks perigosos e URLs: subprojeto 14,
        │    HtmlSecurityChecker no suko-jte + SukoSafe gerado no projeto)
        │  - rejeita o que não é suportado (generics, nomes de
        │    componente compostos), delegando tipagem Java profunda
        │    ao javac na fase seguinte (subprojeto 3)
        ▼
  Verificação Java [subprojeto 3 — PARCIAL]
        │  - JteCompiler orquestra pipeline completo
        │  - JavacTask compila stubs Java via javac (existe e é
        │    testado, mas ainda NÃO está ligado ao pipeline; ver
        │    roadmap, item 3 — fica para o subprojeto 11c)
        ▼
  Checkers e vocabulários das extensões [subprojeto 13a — CONCLUÍDO]
       │  corre depois do SemanticChecker: UNKNOWN_TAG por alvo
       │  (vocabulários fechados) e os Checker registados
       ▼
  Emissão por alvo (Target, fornecido por uma extensão)
       │  o JteEmitter (módulo suko-jte) é o alvo embutido "jte";
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

- **`suko-api/`** — o contrato das extensões (13a): `io.suko.ext.*`
  (`SukoExtension`, `Target` — com `emitProject` —, `Vocabulary`, `Checker`,
  `ExtensionApi.VERSION`, contextos, `Emitted`, `ProjectOutput`/`ProjectEmitContext`
  e `SecurityOptions`). Só JDK; é o único módulo que uma extensão de
  terceiros precisa de ver. Ver `suko-api/README.md`.
- **`suko-core/`** — o compilador: gramática ANTLR, AST, `SukoAstBuilder`,
  `SemanticChecker`, `JteCompiler`, `io.suko.lang.ext.*` (`ExtensionRegistry`,
  `VocabularyChecker`, `ExtensionManifest`, `ExtensionFailures`),
  `io.suko.lang.project.*` (`ProjectIndex`, `SukoProjectCompiler`). **Sem
  JTE**: já não conhece o `JteEmitter` (o 13a moveu-o para `suko-jte`);
  depende de `suko-api`.
- **`suko-gradle-plugin/`** — `io.suko.lang.gradle.*`
  (`SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`) extraído do
  antigo módulo raiz sem mudança de comportamento. Depende de
  `suko-core` e `suko-jte`. Aplica `java-gradle-plugin` e declara
  `gradlePlugin { plugins { create("suko") { id = "io.suko.lang" } } }`
  (subprojeto 8, tarefa 4) — `plugins { id("io.suko.lang") }` já resolve
  via composite build (`includeBuild`) e via TestKit; deixou de existir só
  como classe `Plugin<Project>` testada diretamente. **Não publicado no
  Gradle Plugin Portal** (D11): o ID serve composite builds/TestKit, não
  um `plugins { id("io.suko.lang") version "..." }` isolado — publicação
  real fica em aberto, dependente da mesma questão de D1 do subprojeto 7.
- **`suko-maven-plugin/`** — plugin Maven (`SukoCompileMojo`), depende de
  `suko-core` e `suko-jte`. Tem um `META-INF/maven/plugin.xml` escrito à mão
  (goal `compile`, 8 parâmetros), ver "Ressalva fechada" no item 4 do roadmap.
  Não está publicado num repositório Maven; só há testes ao nível da Mojo e do
  descritor, nenhum teste automático corre um `mvn` real.
- **`suko-jte/`** — o alvo JTE embutido (`JteEmitter`, `JteExtension`,
  vocabulário HTML aberto), registado por `ServiceLoader` através da mesma
  API que uma extensão externa usa. Critério de aceitação do 13a: o `.jte`
  gerado e os source maps ficam byte a byte iguais (`GoldenParityTest`).
  Os plugins Gradle/Maven e o LSP trazem-no embutido.
- **`suko-test-ext/`** — extensão de exemplo (`DemoExtension`: alvo `demo`,
  vocabulário fechado `demo`, checker `demo-check`), **só para testes** —
  não é publicada nem faz parte de nenhum fat jar.
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
  gerados e commitados. As dependências em `src/test` (`suko-jte`, `suko-registry-generator`,
  `testFixtures(suko-core)`, `gg.jte` pinado) existem só para o módulo
  se auto-validar — compilar e renderizar a própria biblioteca com o
  motor `gg.jte` real, e falhar se o manifesto commitado divergir dos
  fontes (`RegistryGoldenTest`).
- **`suko-cli/`** — a ferramenta de linha de comandos `suko` (subprojeto 8):
  `init`/`list`/`add`/`diff`/`update`. Depende de `suko-registry` (modelo +
  JSON) e Gson; **nunca depende de `suko-core`** em produção (`testFixtures(suko-core)`, `suko-jte`
  e `gg.jte` pinado entram só em `testImplementation`, para o
  `FullCycleTest` de ponta-a-ponta) — o compilador não faz parte do que a
  CLI precisa para copiar ficheiros. Empacotada num único fat jar
  hand-rolled (`Jar` task própria, sem plugin Shadow) e distribuída de três
  formas: o próprio jar, um alias `jbang` (`jbang-catalog.json` na raiz) e
  scripts wrapper (`scripts/suko`/`scripts/suko.bat`). Um binário nativo
  GraalVM é opt-in e exclusivamente do lado do consumidor
  (`jbang --native --build-dir <dir> suko@DumiJDev/suko`) — nunca construído nem
  publicado por este projeto. Ver `suko-cli/README.md` para a referência
  de comandos, o desenho de duplo hash do `suko.lock.json` e a verificação da
  assinatura do registry.
- **`suko-lsp/`** — language server do Suko (subprojeto 11a): LSP4J sobre
  stdio, depende de `suko-core` e `suko-jte` (exclui o ANTLR *tool* que o plugin
  `antlr` põe no `api` do core; usa `antlr4-runtime`). Fat jar à mão, como o
  da CLI. Lê o `sourceRoot` do `suko.json` com Gson, sem depender do
  `suko-cli`. Ver `suko-lsp/README.md`.
- **`editors/vscode/`** — extensão VSCode (fora do Gradle, tal como
  `examples/`): gramática TextMate, cliente fino que arranca
  `java -jar server/suko-lsp.jar` (Java 21+), sem lógica de linguagem.
  Ver `editors/vscode/README.md`.
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

`examples/` é hoje a loja Suko (`examples/shop`, Spring Boot + H2, subprojeto 14),
um build Gradle separado fora do `settings.gradle.kts` da raiz. Os antigos `.sk`
de referência (`Card.sk`, `layout/`, `forms/`, `dashboard/`) vivem em
`suko-core/src/test/resources/fixtures/legacy`.

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
  **Verificado a sério para Spring Boot** (`SukoSpringBootIntegrationTest`,
  `suko-gradle-plugin`): um projeto Spring Boot descartável, com o
  plugin `io.suko.lang` real aplicado por ID e `outputDir =
  "src/main/jte"` (a convenção do `jte-spring-boot-starter`,
  `gg.jte.developmentMode=true`), sobe a aplicação a sério e um pedido
  HTTP real a um `@Controller` renderiza corretamente um `.jte`
  compilado a partir de um `.sk` — zero adaptação, exatamente como a
  afirmação de design sempre implicou, mas nunca tinha sido testado
  até agora. Esse mesmo teste apanhou um bug real, independente do
  Spring: `SukoExtension.getSourceDirAsPath()`/`getOutputDirAsPath()`
  faziam `Path.of(string)` bruto — relativo ao `user.dir` do processo,
  não ao diretório do projeto — pelo que qualquer `suko { outputDir =
  "..." }` explícito no build script do consumidor (não a convenção
  por omissão, que escapava ao bug por vir pré-resolvida para
  absoluto) resolvia para o sítio errado. Corrigido para resolver
  sempre via `ProjectLayout.getProjectDirectory().dir(...)`.
  Quarkus não foi verificado da mesma forma — o mecanismo
  (`jte-quarkus` a consumir o mesmo `.jte` puro) é idêntico em
  princípio, mas fica como lacuna aceite até haver um teste
  equivalente.
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
  lê-se como `${header}` (sem `.apply(null)`); um slot render-prop
  (`Function<T, Component>`) precisa de `${header(item)}` ou `${row(item)}`
  para passar o parâmetro — agora porque o tipo declarado o diz, não por
  inferência. O teste `${slot ?: "fallback"}` continua sem suporte para
  AMBOS os casos — `Content` vs `String` continuam sem supertipo comum
  aceite pelo `?:` dessaçucarado, permanecendo como limitação conhecida.
- **Interpolação é sempre `${...}`, em todas as posições (subprojeto 9).**
  A regra da linguagem é independente da posição: `${expr}` interpola no
  corpo de um componente, dentro de uma tag, num valor de atributo sem
  aspas (`disabled=${disabled}`) e dentro de um literal de string
  (`id="dialog-${id}"`); uma **chaveta nua nunca interpola, em posição
  nenhuma**. Antes do subprojeto 9 a regra era o inverso conforme a
  posição — `{expr}` interpolava fora de strings e era texto literal
  dentro delas — e era essa inversão, mais do que o número de grafias, o
  que confundia quem chegava.
  **Porquê `${}` e não `{}`:** chaveta nua colide com usos reais que a
  linguagem já aceita e de que a biblioteca de componentes depende —
  `x-data="{ open: false }"` (Alpine, em `Dialog.sk`), `style="--tw-ring:
  {0}"`, `onclick="if(x){go()}"`. `${}` não colide com nenhum, é o que o
  `.jte` gerado já escrevia (o autor passa a ler a mesma grafia no fonte e
  no artefacto intermédio), e tem precedente em Kotlin, template literals
  de JS, Groovy e JSP EL.
  **A forma curta `$ident` dentro de strings mantém-se** (`"ola $name"`) —
  é um atalho do mesmo sigilo, não uma segunda regra posicional.
  **A gramática continua a aceitar a chaveta nua**, de propósito: quem a
  rejeita é o `SemanticChecker` (`LEGACY_BRACE_INTERPOLATION`,
  `LEGACY_BRACE_ATTRIBUTE`, ambos ERROR, com a correção literal na
  mensagem). Removê-la do `.g4` faria o ANTLR recuperar em silêncio nos
  dois caminhos de parse sem error listener (e o terceiro, tolerante, só do
  language server) — ver o bullet sobre erros de
  parse em "Limitações conhecidas".
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

`suko-core/src/test/resources/fixtures/legacy/Card.sk` (antes `examples/Card.sk`) é a **referência da superfície da linguagem**, não
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

- **Layouts e componentes reutilizáveis.** Os fixtures `fixtures/legacy/layout/` (antes `examples/layout/`) mostram
  como criar componentes reutilizáveis (`Layout`, `Card`, `Modal`, `Button`,
  `Input`, `Select`) com slots nomeados. O `fixtures/legacy/forms/` demonstra
  formulários completos com validação de erros. O `fixtures/legacy/dashboard/`
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
  `.sk` tem de escrever `${header(item)}` ou `${row(item)}` para passar o
  parâmetro (o emitter traduz um identificador conhecido como slot para
  `.apply(...)`). Consequências: (a) testar "slot não preenchido" é
  `header.apply(null) == null` para render-prop, não `header == null` —
  o valor por omissão de um slot é uma função que devolve `null`, nunca
  a referência `null`; (b) iterar um `List<Function<T, Component>>`
  render-prop obriga o `.sk` a escrever o tipo do item como
  `Function<T, Content>`; (c) `${slot ?: "fallback"}` não compila (ramos
  de tipos incompatíveis). Nada disto é verificado hoje: o erro aparece
  como erro de compilação Java no `.jte` gerado.
- **Tipos qualificados não fazem parse.** `java.util.List<T>` é
  rejeitado (`type: Identifier typeArguments? arrayMarker*`).
- **Projeto multi-ficheiro (subprojeto 5).** `package foo.bar;`/`import
  foo.bar.Card as C;` (gramática já existente desde o subprojeto 1,
  nunca usados antes) passam a ser resolvidos de verdade por
  `io.suko.lang.project.ProjectIndex` (Fase 1: scan recursivo,
  indexação da assinatura completa — nome, pacote, `public` e parâmetros com tipo, default, slot/cardinalidade e spans; ver `ProjectIndexEntry`/`ParamInfo`) e
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
- **Validação de slots e parâmetros atravessa ficheiros (subprojeto 11a, D3).**
  Como o `ProjectIndex` passou a guardar os parâmetros de cada componente,
  as regras `REQUIRED_SLOT_MISSING`, `SLOT_NOT_FOUND` e
  `CARDINALITY_VIOLATION` valem também para componentes de outros
  ficheiros, e há uma regra nova, `PARAM_NOT_FOUND`: um argumento com nome
  que não é parâmetro do componente chamado (com "quis dizer 'x'?" para
  gralhas, ou a lista de válidos). Um slot pode ser passado como argumento
  nomeado (`Card(header = h)`) e conta como preenchido; o mesmo slot como
  argumento **e** como bloco é `CARDINALITY_VIOLATION`. É uma mudança de
  comportamento deliberada: builds que passavam podem falhar por erros reais
  que estavam escondidos (nenhum dos componentes de `suko-components`, do site
  ou dos exemplos falhou). **Limitação:** só cobre chamadas em posição de
  instrução; uma chamada usada como valor (`var c = Card(titel = "x")`) não
  é verificada.
- **Erros em cascata quando o ficheiro que declara um componente não faz
  parse (decisão pendente, 11a).** Um `.sk` sem AST utilizável sai do
  `ProjectIndex`, pelo que todos os ficheiros que o importam ganham
  `IMPORT_NOT_FOUND`/`COMPONENT_NOT_FOUND` enquanto se escreve. No build é só
  ruído (o build já falha); no editor os erros aparecem e desaparecem nos
  chamadores. Mantido igual ao `sukoCompile` de propósito (os diagnósticos do
  editor têm de ser os do build); a alternativa — o índice marcar ficheiros
  com erro de sintaxe e o checker suprimir o "não encontrado" — muda o build
  e precisa de decisão explícita.
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
  correção de gramática dedicada. **Efeito colateral positivo do
  subprojeto 9:** o `{ "Título" }` que sobra já não é lido em silêncio
  como interpolação válida — é chaveta nua, logo dispara
  `LEGACY_BRACE_INTERPOLATION` (ERROR). A limitação em si (o `textRun`
  engolir o `Identifier` de `header`) não foi corrigida e continua a
  valer, mas o sintoma deixou de ser HTML corrompido em silêncio e passou
  a ser um erro de compilação visível.
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
  qualquer leitura posterior de `${c}` falha a compilar ("cannot find
  symbol: c") — nunca há `Statement.VarDecl`. Correção real exigiria mudar
  a gramática (fora do escopo do subprojeto 6). Mitigação atual: o
  `SemanticChecker` deteta heuristicamente um `textRun` cujo texto contém
  `var x = Componente(` (com `Componente` a resolver na tabela de símbolos)
  e emite o aviso `VAR_DECL_NOT_PARSED`. **Efeito colateral positivo do
  subprojeto 9:** o `{ "x" }` que sobra do `textRun` guloso também passa a
  disparar `LEGACY_BRACE_INTERPOLATION` (ERROR) em vez de virar
  interpolação válida em silêncio — o `VAR_DECL_NOT_PARSED` continua a ser
  a única heurística que aponta para a causa real (chamada de componente
  como valor com bloco de slot), mas o sintoma imediato deixou de ser HTML
  corrompido sem aviso.
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
  **Terceiro caminho de parse (subprojeto 11a): `TolerantParser` +
  `SukoAstBuilder.tolerant`, só para o language server** (completion e
  navegação num ficheiro a meio de ser escrito). Nunca reporta diagnósticos —
  esses vêm sempre do caminho estrito, idêntico ao do `sukoCompile` — e não
  acrescenta variantes `Error` aos `sealed` `Statement`/`Expr`: descarta a
  subárvore que não consegue construir. Como a recuperação de erros do ANTLR
  engole os componentes seguintes para dentro do que está partido
  (`${t.` sem fecho), quando o parse do ficheiro inteiro tem erros de sintaxe
  cada componente é reparseado sozinho, com o resto do ficheiro substituído
  por espaços (mantendo quebras de linha e nº de code points, para os spans
  coincidirem com os do ficheiro real).
- **`</` literal em texto livre** é erro de parse (consequência aceite
  da desambiguação do `textRun`).
- **Um literal de string Suko não pode conter `<` nem `>`**
  (consequência aceite do predicado `canStartStringLiteral` do lexer,
  que é o que permite aspas soltas em prosa: `<p>5" tela</p>`).
- **Um `//` no fim absoluto do ficheiro, sem newline a seguir,** não
  conta como comentário (consequência aceite da desambiguação
  comentário-vs-URL).
- **Espaço em branco órfão entre dois statements não-textRun irmãos é
  perdido** (`${x} ${y}` emite `${x}${y}`).
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
  documentos JSON do registry — FECHADO no subprojeto 14.** O
  `registry.json` é agora schema 2 com `manifestSha256` por entrada e uma
  assinatura Ed25519 (`registry.json.sig`) verificada pela CLI antes do
  parse; ver o item 14 e `suko-cli/README.md`. (Até ao subprojeto 8 a
  confiança era só do transporte: HTTPS sem redirects.)
- **`$` seguido de identificador dentro de uma string é sempre
  interpolação Suko — colide com as *magic properties* do Alpine.js.**
  `x-data="$store.foo"`, `x-on:click="$dispatch('evento')"`, `$el`,
  `$refs`: o `.jte` gerado tenta resolver um símbolo Java `store`/
  `dispatch` e o `javac` falha com "cannot find symbol". É o espelho exato
  do problema que levou a rejeitar `{}` dentro de strings no subprojeto 6.
  Hoje não morde porque `htmlName` ainda não aceita `:` nem `@` num nome
  de atributo (limitação separada, acima) — **morde no momento em que
  essa limitação for levantada**, e o subprojeto que alargar `htmlName`
  para interop com Alpine tem de resolver as duas em conjunto, não pode
  assumir que `$` está livre. Mitigação disponível hoje: o escape `\$`
  (`x-data="\$store.foo"`). Manter `$ident` foi decisão explícita do
  utilizador no subprojeto 9 (D3 rejeitada), com este custo aceite.
- **Não há escape para um `${` literal fora de uma string.** Dentro de uma
  string escreve-se `\$` (`"literal \${x}"` sobrevive intacto ao `gg.jte`
  real, confirmado por sonda na Task 5); em texto livre não há forma de
  escrever `${` literal — o que torna JS com template literals dentro de
  um `<script>` inline não escrevível num `.sk`. Já era verdade para `{`
  antes do subprojeto 9 (ver a limitação "Espaço em branco órfão" e as
  duas de gulodice do `textRun` acima); passou a ser verdade também para
  `${`.
- **A mensagem de `LEGACY_BRACE_INTERPOLATION`/`LEGACY_BRACE_ATTRIBUTE`
  só reconstrói a correção literal para um identificador simples.**
  `{title}` produz a mensagem exata `'{title}' já não interpola — escreva
  '${title}'` (o `SemanticChecker` lê `Expr.PrimaryExpr.text()` direto do
  AST); para qualquer expressão composta — `{user.name}`, `{a + b}`,
  `{items.size()}` — não há representação textual reconstruível a partir
  do `Expr` tipado sem duplicar o `JteEmitter` como segundo
  pretty-printer, e a mensagem degrada para o genérico `'{expr}' já não
  interpola — escreva '${expr}'`, com `expr` literal, não a expressão real
  do autor. O `SourceSpan` do diagnóstico continua a apontar para a
  posição exata (linha/coluna corretas num IDE ou na saída de erro), só o
  texto da mensagem em si não é a correção literal nesses casos. Decisão
  explícita do controlador do plano do subprojeto 9 depois da revisão da
  Task 11: aceite como está, não é lacuna a fechar agora.
- **O manifesto do registry não declara versão mínima de linguagem.**
  `RegistryIndex`/`ComponentManifest` têm `schemaVersion`,
  `registryVersion` e `version` por componente, mas nada que diga "este
  fonte exige um compilador Suko >= X". Um consumidor com compilador
  antigo (anterior ao subprojeto 9) que faça `suko add --ref main` recebe
  fonte já migrada para `${expr}` que o seu compilador antigo rejeita como
  chaveta nua legada. O que o protege por omissão é a tag do registry
  derivar da versão da CLI (D5 do subprojeto 8); um `--ref` explícito
  contorna essa proteção.
- **`suko-cli`: impossível pinar a versão de um componente
  independentemente da tag do registry (D5).** Ver `suko-cli/README.md` →
  "Registry pinning é por tag, não por versão de componente" para a
  explicação completa; resumindo, `suko add`/`suko update` operam sempre
  contra **uma** tag/ref do registry de cada vez, porque o manifesto é um
  único documento JSON atómico por release do registry — não existe hoje
  um mecanismo de histórico de versões por componente que permita "botão
  X na versão N, botão Y na versão N+1" na mesma instalação.
- **`suko-cli`: binário nativo GraalVM é exclusivamente
  `jbang --native --build-dir ... suko@DumiJDev/suko` do lado do consumidor,**
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

O subprojeto 3 está parcial: implementado `JteCompiler` (pipeline
completo de compilação) e `JavacTask` (verificação Java com stubs e
mapeamento de erros para `.sk`), mas o `JavacTask` só é exercitado pelo
`JavacTaskTest` — nenhum código de produção o chama, por isso nem o
`sukoCompile` nem o `suko:compile` verificam hoje os tipos Java das
expressões (verificado 2026-09-28). Ligá-lo fica para o subprojeto 11c.

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
3. **Verificação Java** — PARCIAL. `JteCompiler` orquestra o pipeline completo (parse → semantic check → JTE emit). `JavacTask` (compilar stubs Java e mapear erros para `.sk`) existe e está coberto por `JavacTaskTest`, **mas não está ligado ao pipeline**: nenhum código de produção o chama (verificado 2026-09-28), por isso o `sukoCompile` não verifica os tipos Java das expressões — um `List` cru com `for (String item : items)` passa o build e só falha quando o JTE compila o template. Ligá-lo fica para o subprojeto 11c (inteligência Java em `${...}`), que precisa da mesma análise. **Regra (spec do 13a):** a análise Java independente do alvo fica no core; os stubs específicos do JTE pertencem ao `suko-jte`. Hoje o `JavacTask` ainda tem `gg.jte.Content` fixo (`JavacTask.java:189`) e chaves `.jte` (`:220`) — ao ligá-lo no 11c isto tem de passar para o alvo.
4. **Integração no build** — CONCLUÍDO. Plugin Gradle (`sukoCompile`, `sukoWatch`), plugin Maven (`suko:compile`), modo watch com `WatchService`, E2E tests.
   **Ressalva fechada:** o módulo `suko-maven-plugin` agora tem um
   `META-INF/maven/plugin.xml` completo e correto (goal `compile`,
   implementation, phase `generate-sources`, os 8 parâmetros (`project`, `sourceDir`,
   `outputDir`, `generatedPackage`, `generatedJavaDir`, `security`, `targets`,
   `buildDirectory`; os dois novos vêm do subprojeto 14) com
   `<configuration>`/`default-value` a espelhar os campos `@Parameter`
   de `SukoCompileMojo`), mantido à mão em vez de gerado pelo
   `maven-plugin-plugin` — a tentativa anterior de o gerar via uma tarefa
   Gradle nunca funcionou (buildscript classpath sem os artefactos do
   gerador; ver o comentário histórico em
   `suko-maven-plugin/build.gradle.kts`), e reimplementar a API do gerador
   fora de um build Maven real seria mais risco do que o hand-maintenance
   deste ficheiro pequeno e estável. `PluginDescriptorConsistencyTest`
   garante que os nomes de parâmetro no XML não dessincronizam dos campos
   da Mojo. Verificado ponta-a-ponta com um `mvn generate-sources` real
   (goal ligado por `<execution>`, pelo goal totalmente qualificado
   `io.suko:suko-maven-plugin:<version>:compile`, e pelo atalho
   `mvn suko:compile` com `io.suko` em `pluginGroups`) contra um projeto
   consumidor de fora do repositório — `mvn suko:compile` **é executável
   end-to-end** (verificação manual feita uma vez, antes do 13a; **não há
   teste automático que corra um `mvn` real** — só `PluginDescriptorConsistencyTest`
   e testes ao nível da Mojo). **Caminho Gradle** (contexto que se mantém): `suko-gradle-plugin`
   aplica `java-gradle-plugin` e declara
   `gradlePlugin { plugins { create("suko") { id = "io.suko.lang" } } }`,
   com `plugins { id("io.suko.lang") }` a resolver de verdade via
   composite build (`includeBuild`) e via TestKit (ver "Estrutura de
   módulos" acima e `FullCycleTest` do subprojeto 8) — mas **sem
   publicação no Gradle Plugin Portal**, então um `plugins { id(...)
   version "..." }` isolado, fora de um composite build, ainda não
   resolve; o mesmo vale para o Maven — nenhum dos dois plugins está
   publicado num repositório real, só instalável/resolvível localmente.
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
   e atributos (generalizada a todas as posições pelo subprojeto 9);
   auto-`toString` null-safe para identificadores simples
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
   desenho de duplo hash do lockfile e as lacunas aceites (sem pinagem
   por componente; o índice do registry passou a ser assinado no
   subprojeto 14).
9. **Unificação da sintaxe de interpolação** — CONCLUÍDO. Spec em
   `docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md`,
   plano em `docs/superpowers/plans/2026-09-21-suko-interpolacao-unificada.md`.
   `${expr}` passa a ser a única forma de interpolar, nas três posições
   (statement/corpo de tag, valor de atributo sem aspas, literal de
   string); a chaveta nua deixa de interpolar em qualquer posição e passa
   a erro com a correção literal na mensagem. `$ident` dentro de strings
   mantém-se (D3 rejeitada). Antecipado à frente do site de documentação
   por pedido explícito do utilizador.
10. **Site de documentação** — CONCLUÍDO. `suko-website/` compila as
    suas próprias páginas `.sk` para HTML estático em build-time
    (`WebsiteGenerator`: `.sk` → `.jte` → HTML via gg.jte, Tailwind
    purgado pela tarefa `buildTailwindCss`), publicado no GitHub Pages
    (`.github/workflows/deploy-website.yml`, https://dumijdev.github.io/suko/)
    sem JVM em runtime — o primeiro caso de uso real dessa capacidade;
    generalizá-la no compilador continua por fazer. As páginas de
    componentes são geradas a partir do registry real (código-fonte,
    parâmetros e requisitos lidos dos manifestos), e todos os exemplos
    da Language Reference e do Getting Started foram compilados pelo
    plugin Gradle e renderizados pelo gg.jte num projeto limpo. Desenho
    visual registado em `DESIGN.md`/`PRODUCT.md`. Lacuna documentada no
    próprio site: o `sukoCompile` não verifica os tipos Java das
    expressões (um `List` cru passa o build e só falha no JTE).
11. **Suporte de IDE** (VSCode + IntelliJ) — partido em três specs
    sequenciais (**release:** decidido pelo utilizador a 2026-10-04 que a
    primeira release só sai depois do 11b, do 11c, do 13a — `suko-api`,
    extensão `suko-jte`, core sem JTE —, da interoperabilidade Java do
    item 12 e, acrescentado no mesmo dia, do subprojeto 14 — segurança
    por omissão): **11a** language server +
    extensão VSCode, **11b** cliente IntelliJ reutilizando o server, **11c**
    inteligência Java dentro de `${...}` (liga o `JavacTask` ao pipeline).
    - **11a — IMPLEMENTADO, verificação manual pendente.** Spec
      `docs/superpowers/specs/2026-09-28-suko-ide-server-vscode.md`, plano
      `docs/superpowers/plans/2026-09-29-suko-ide-server-vscode.md`. Módulos
      `suko-lsp/` (LSP4J 1.0.0, fat jar de 1,8 MB) e `editors/vscode/`
      (extensão TypeScript empacotada com esbuild; o jar vai embutido).
      Diagnósticos com debounce de 250 ms, go-to-definition, hover e
      completion com auto-import; core ganhou `SukoSources`,
      `SukoProjectCompiler.analyze`, `CallResolver`, spans de nome, D3
      (ver acima) e o `TolerantParser`. Verificado: suites Gradle, testes de
      integração que falam com o jar real por JSON-RPC com a biblioteca de
      protocolo do VS Code (`vscode-languageserver-protocol`; não exercita a
      conversão de URIs do `vscode-languageclient`), testes unitários e de gramática. **Não
      verificado:** o teste Electron (`@vscode/test-electron`) nunca correu —
      o sandbox de desenvolvimento não descarrega o VS Code — e o workflow
      `build-vscode-extension.yml` ainda não correu; falta a verificação manual
      pedida na spec (`.vsix` instalado num projeto criado com `suko init`).
      Não publicado no Marketplace/Open VSX (D4).
    - **Contratos que o 11b/11c vão herdar:** `SourceSpan` conta **code
      points** (não UTF-16) e `endIndex` é inclusivo; `SukoSources`,
      `SukoProjectCompiler.analyze`/`ProjectAnalysis`, `CallResolver` e
      `TolerantParser` são superfície pública usada pelo `suko-lsp`;
      `ProjectIndexEntry` trocou `paramCount` por `params`/`declarationSpan`/
      `nameSpan` (quebra de compatibilidade de fonte, menor). `SukoSources` e
      `ProjectIndex` iteram por ordem de `Path.compareTo` (antes, a do
      `Files.walk`), logo "o primeiro" de dois `DUPLICATE_COMPONENT` é
      determinístico por sistema operativo (no Windows ignora maiúsculas). A
      correção do `textOf` muda o `.jte` gerado para fontes com caracteres fora
      do BMP (antes truncado). Os diagnósticos do editor são os do
      `sukoCompile` **mais** os avisos (o build descartava-os em sucesso; corrigido
      no subprojeto 14, R3: `SukoCompileTask.printDiagnostics` e o Mojo imprimem
      `WARNING`/`INFO`).
    - **Seguimento do 11a, por fazer (revisão final):** (a) desempenho — cada
      `didChange` invalida a cache e completion/hover/definition recompilam
      o root inteiro de forma síncrona na thread do LSP4J, e a thread do debounce
      segura o monitor do `Project` durante a compilação: os pedidos deviam usar
      a última análise concluída, com o `analyze` fora do lock, contador de
      geração e cache de parse por texto — medir com `suko-components` e o site
      antes da release; (b) quando um `Project` sai no `rediscover` (`sourceRoot`
      mudado no `suko.json`) os seus diagnósticos não são limpos e os buffers
      abertos antes de existir projeto perdem-se; (c) `sourceRoot` não é limitado
      ao workspace (`"../../.."` faz `Files.walk` enorme) e cada gravação relê o
      root inteiro em vez do ficheiro alterado; (d) o ANTLR 4.13.1 está fixado em
      dois sítios (`suko-core` tool, `suko-lsp` runtime) — manter iguais.
    - 11b e 11c: por fazer. **Ordem revista pelo utilizador (2026-10-04):**
      primeiro as mudanças de linguagem/compilador — **13a** (API de
      extensões) → **item 12** (interop Java ↔ Suko) → **metade de
      compilador do 11c** (ligar o `JavacTask`, verificar tipos Java em
      `${...}` no build) — e só depois os editores (seguimentos do 11a,
      11b, parte de editor do 11c), para não refazer o suporte de IDE.
      **Scoping do 11b já iniciado (2026-10-04), em pausa:** o utilizador
      quer **integração profunda com o Java do IntelliJ** e escolheu a
      abordagem **híbrida** — linguagem nativa (PSI a partir da gramática
      ANTLR via `antlr4-intellij-adaptor`) com injeção de Java em `${...}`
      e referências `.sk` ↔ `.sk`, mais LSP4IJ para a semântica Suko vinda
      do mesmo `suko-lsp`; Community + Ultimate; referências Java ↔ `.sk`
      dentro do item 12.
12. **Interoperabilidade Java ↔ Suko** — intenção futura registada pelo
    utilizador em 2026-09-28, sem data nem spec. A ideia: um componente
    Suko é, na essência, uma função Java que devolve `Component` — e
    `Component` já é uma interface (no JTE gerado, `gg.jte.Content`, uma
    interface funcional). Daí duas direções: (a) chamar componentes
    Suko a partir de código Java como funções normais (ex.: `Hello.of(name)`
    a devolver `Component`), e (b) importar num `.sk` componentes
    escritos em Java puro (uma classe/método Java que devolve
    `Component`), tal como hoje se importa outro `.sk`.
    **Clarificado pelo utilizador (2026-10-04):** "crio uma classe Java
    que implementa `Component` e importo no `.sk` sem problemas nenhuns,
    e vice-versa — o compilador trata disso". As duas direções são
    simétricas e transparentes: um `.sk` importa uma classe Java que
    implementa o `Component` do alvo como importa outro `.sk`, e o código
    Java usa um componente Suko como usa uma classe Java. A divisão de
    trabalho que isto permite: **lógica pesada em componentes Java, UI em
    Suko** (que terá a sua própria reatividade em "ilhas", resolvida em
    compile-time — item 13). "java" é por isso uma camada transversal no
    core, não um alvo de render; em qualquer alvo do item 13, o tipo
    `Component` é o desse alvo (`gg.jte.Content` no JTE, `Node` no
    JavaFX, `Element` no TamboUI). O subprojeto 13a (API de extensões)
    prepara o gancho: cada alvo declara o seu tipo `Component`
    (`Target.componentType()`). Pontos a
    resolver no scoping: que API Java é gerada por componente (hoje o
    output é só `.jte`), como o `ProjectIndex`/`SemanticChecker`
    resolvem um símbolo que vive em Java em vez de `.sk`, e a relação
    com o item 11c (análise Java dentro de `${...}`) e com a limitação
    de um único source root.
13. **Transpilação para UIs de desktop/terminal (Swing, JavaFX,
    TamboUI)** — intenção futura registada pelo utilizador em
    2026-09-29, sem data nem spec. A ideia: o mesmo fonte `.sk` (componentes,
    slots, `if`/`for`/`switch`, interpolação) passar a poder ser
    transpilado, além do `.jte`, para código Java que constrói árvores de
    widgets de três toolkits: **Swing** (`JComponent`), **JavaFX**
    (`Node`) e **TamboUI** (TUI em Java, widgets de terminal). Hoje o
    pipeline tem um único backend (`JteEmitter`); esta intenção implica
    um segundo eixo de variação — o *alvo* — sem tocar na gramática nem
    no verificador na medida do possível. Pontos a resolver no scoping:
    (a) **abstração de backend** — extrair de `JteEmitter` uma interface
    de emitter sobre o mesmo AST, com o `.jte` como uma implementação
    entre outras, e decidir onde se escolhe o alvo (opção em
    `suko.json`/no plugin Gradle/Maven, por ficheiro ou por projeto);
    (b) **modelo de elementos** — as tags de hoje são HTML
    (o `SemanticChecker` **não** valida estrutura HTML; o escape de caracteres é do JTE em runtime, `ContentType.Html`; os sinks perigosos e as URLs são verificados pelo `HtmlSecurityChecker` do `suko-jte` e pelo `SukoSafe` gerado, subprojeto 14); é
    preciso decidir se os alvos nativos usam um vocabulário próprio de
    widgets (`<Button>`, `<VBox>`, ...), um vocabulário comum mapeado
    para cada toolkit, ou tags HTML mapeadas para widgets, e como o
    verificador passa a saber qual o vocabulário válido por alvo;
    (c) **`Component`** — no backend JTE é `gg.jte.Content` (uma
    interface funcional que escreve texto); nos alvos nativos seria um
    tipo de widget/fábrica (`Supplier<Node>`, `JComponent`, ...), o que
    muda a forma dos slots `Function<T, Component>` e dos children
    implícitos; (d) **modelo de renderização** — JTE é *render uma vez,
    para texto*. **Corrigido a 2026-10-03 (issue #7):** o TamboUI 0.5.0
    **não** é retido — `ToolkitRunner.run(Supplier<Element>)` reconstrói
    a árvore a cada frame e o estado dos widgets vive fora dela
    (verificado com `javap` sobre os jars e num spike no BuildCLI), ou
    seja, é *função de estado → árvore*, o mesmo modelo mental do JTE.
    Só Swing e JavaFX são realmente retidos (precisam de mutar a árvore
    existente). Em todos os alvos nativos falta uma história para estado
    e eventos (`on:key`, handlers), sem a qual a transpilação só cobre
    UIs estáticas — a decisão de escopo é se o Suko cresce além da
    "camada de view" de servidor definida em "Decisões de design";
    (e) **relação com o item 12** (interoperabilidade Java ↔ Suko), que
    é o mecanismo natural para ligar handlers e estado escritos em Java
    a componentes Suko; com o 11c (a verificação de lambdas e estado
    dentro de `${...}` depende da análise Java); e com o item 11 (o
    suporte de IDE terá de conhecer o vocabulário por alvo). **Ordem
    revista (issue #7):** TamboUI primeiro — sendo *estado → árvore*,
    é o alvo nativo mais barato (sem reconciliação; o diff célula a
    célula do terminal já é do TamboUI) e tem um primeiro consumidor
    real (BuildCLI); Swing e JavaFX depois, porque exigem mutações
    dirigidas de uma árvore retida.

    **13a — API de extensões: CONCLUÍDO (2026-10-04)** (primeira fatia
    do item; ainda sem release — a release espera por 11b, 11c, 13a e 12).
    Spec: `docs/superpowers/specs/2026-10-04-suko-api-extensoes.md`;
    plano: `docs/superpowers/plans/2026-10-04-suko-api-extensoes.md`.
    Entregou os módulos `suko-api`, `suko-jte` e `suko-test-ext` (ver
    "Estrutura de módulos"), o `ExtensionRegistry` no core, o parâmetro
    de alvos (`suko { targets }` no Gradle, `<targets>` no Maven; por
    omissão `jte`), a configuração Gradle `sukoExtensions`, as extensões
    nas `<dependencies>` do plugin Maven, o ficheiro
    `build/suko/extensions.json` (Gradle) / `target/suko/extensions.json`
    (Maven) e o carregamento no LSP, só em workspaces confiáveis. Com
    vários alvos o output vai para `<out>/<alvo>/<package>/`; com um só
    (o caso de hoje) o layout não muda. Projeto sem extensões declaradas
    compila byte a byte como antes. Códigos de erro novos:
    `EXTENSION_CONFLICT` (mesmo id de alvo/vocabulário fornecido por duas
    extensões: fica o da extensão de id menor), `EXTENSION_API_MISMATCH`
    (extensão compilada para outro major de `ExtensionApi.VERSION`),
    `TARGET_NOT_FOUND` (alvo pedido que ninguém fornece),
    `VOCABULARY_NOT_FOUND` (alvo que declara um vocabulário não
    registado), `UNKNOWN_TAG` (tag fora dos vocabulários de um alvo
    fechado) e `EXTENSION_FAILED` (código de extensão que lança — incluindo
    `LinkageError` e a maioria dos `Error`, mas não os `VirtualMachineError`
    à exceção de `StackOverflowError` — ou que falha ao carregar/registar;
    o compilador regista o id da extensão e continua).

    *Lacunas da spec resolvidas neste plano:* (1) **tag fora de um
    vocabulário fechado** → `UNKNOWN_TAG` (ERROR): para cada alvo pedido,
    a tag tem de ser aceite por pelo menos um dos seus vocabulários; um
    vocabulário `open()` aceita tudo, por isso com só o `jte` nunca
    dispara e nenhum diagnóstico existente muda; (2) **alvo que declara
    um vocabulário que ninguém registou** → `VOCABULARY_NOT_FOUND` (ERROR,
    nível de projeto); (3) **source maps no golden** — o
    `SukoProjectCompiler` não devolve source maps, por isso o golden dos
    source maps chama o `JteEmitter` diretamente sobre a análise do
    projeto.

    *Segurança e confiança no LSP:* extensões são código de terceiros.
    O LSP só as carrega quando o VSCode diz que o workspace é confiável;
    num workspace não confiável corre só o JTE embutido e o
    `extensions.json` nem é lido. Um manifesto com mais de 1 MiB é
    ignorado; as entradas do classpath têm de ser caminhos absolutos para
    ficheiros `.jar` existentes (diretórios e UNC rejeitados); o
    `sourceRoot` do `suko.json` fica confinado à pasta do workspace;
    conceder confiança reinicia o servidor e o `extensions.json` é
    vigiado e recarregado quando muda. O jar do JTE listado no manifesto
    é ignorado (já vem embutido). Extensões **não** estão em sandbox: no
    build correm com os privilégios do Gradle/Maven.

    *Limitações conhecidas do 13a (não escondidas):* (a) ~~o
    `sukoCompile`/`suko:compile` não mostra avisos em sucesso~~ — **fechado**
    no subprojeto 14 (R3: Gradle e Maven imprimem `WARNING`/`INFO` em sucesso); (b) o `extensions.json` **não é escrito** quando não há
    fontes (Gradle: nenhum `.sk`; Maven: a pasta de fontes não existe) nem
    pelo `sukoWatch`, e a escrita não é atómica, logo o LSP pode ver um
    manifesto antigo ou truncado (este último é ignorado); (c)
    `Target.componentType()` é o gancho do item 12 e hoje nada no
    compilador o consome; (d) não há extensão externa publicada nem
    suporte IntelliJ (11b); (e) o Gradle escreve no `extensions.json` toda a
    configuração `sukoExtensions` (incluindo dependências transitivas), o
    Maven só os jars com ficheiro de serviço — uma extensão com dependências de
    runtime funciona no `mvn` mas falha no LSP de projetos Maven
    (`EXTENSION_FAILED`); follow-up; (f) passar de um para dois alvos move os
    templates de `<out>/pkg/X.jte` para `<out>/jte/pkg/X.jte` (reapontar a raiz
    JTE, ex. `src/main/jte` do Spring), e nem `sukoCompile` nem `suko:compile`
    apagam saídas órfãs; (g) o LSP sem `extensions.json` fica **em silêncio**
    (a spec dizia avisar uma vez; desvio deliberado para evitar ruído); (h) com
    um `buildDirectory` personalizado no Gradle, o LSP só procura
    `build/suko` e `target/suko`; (i) `TagSpec.attributeTypes` e
    `allowsChildren` ainda não são aplicados pelo core; (j) o `JavacTask` ainda está no core com
    `"gg.jte.Content"` e as chaves `.jte` fixas — pela regra do item 3, os
    stubs específicos do JTE passam para o `suko-jte` (antes do 11c).

    *`ExtensionApi` v1 é provisória até à primeira release.* Qualquer mudança
    incompatível (novo subtipo selado de `Statement`/`Expr`/`Param`, novo
    componente de record num contexto ou em `ProjectIndexEntry`, mudança de
    assinatura) incrementa `VERSION`. Checklist antes do item 12: `Target.emit`
    devolve um único `Emitted` (jte+js/html+js e o item 12 precisam de várias
    saídas por componente); `EmitContext` não tem resolvedor de chamadas (a spec
    prometia um equivalente de `CallResolver`; hoje só `importedByShortName` +
    `packagePrefix`); `CheckContext` não expõe os alvos ativos (parcialmente fechado:
    `activeVocabularies` expõe os vocabulários ativos, não os alvos); contextos e
    `ProjectIndexEntry` são records (considerar interfaces).

    *Alterações da v1 feitas no subprojeto 14 (ruling R1: mudou-se a v1 no
    lugar, `VERSION` continua 1, por ainda não haver extensões externas).*
    Acrescentou-se: `Target.emitProject` com `ProjectOutput`/`ProjectEmitContext`
    (emissão ao nível do projeto — cobre o que a 13b espera, ex. ficheiros JS);
    o record `SecurityOptions`; `CheckContext.options` e
    `CheckContext.activeVocabularies`; e a constante `Severity.INFO`. Atenção: `INFO`
    é uma constante nova do enum, logo uma extensão com um `switch` exaustivo
    sobre `Severity` deixa de compilar. O construtor legado de 3 argumentos de
    `CheckContext` passa `activeVocabularies = {"html"}` (falha fechada: um
    contexto legado não desliga os checkers de segurança).

    *O `.jte` gerado já não é autónomo (subprojeto 14).* Referencia
    `<generatedPackage>.SukoSafe` e só compila quando essa classe está no
    classpath. Gradle: a ligação ao `compileJava` precisa do plugin `java` — sem ele
    não há `SukoSafe`. Em montagens multi-módulo que escrevem o `.jte` noutro
    módulo, esse módulo tem de ver o `SukoSafe`. O gerador do site
    (`WebsiteGenerator`) ignora `projectOutputs` (ver os pendentes do item 14).

    *Follow-ups arrumados (sem dono):* descoberta que termina em `hasNext()`
    a falhar; rollback parcial de registo; `extensions.json` não atómico; manifesto
    não escrito sem fontes nem pelo `sukoWatch`; `stat` com seguimento de
    symlinks antes da verificação UNC no Windows; fallback silencioso do
    `sourceRoot`; chamadas a extensões sem guarda dentro de handlers de
    `catch`; jars bloqueados no Windows pelo classloader do LSP; fat jar com
    `DuplicatesStrategy.EXCLUDE` pode perder o ficheiro de serviços de um
    segundo built-in; clash de nome entre `io.suko.ext.SukoExtension` e o
    `SukoExtension` do DSL Gradle (decidir antes da release); pacotes divididos
    entre jars excluem JPMS.

    **Direção decidida pelo utilizador (2026-10-03): extensões em
    compile-time, não crescimento do core.** Os alvos e vocabulários
    entram como *extensões* que correm só no build e no LSP, sobre uma
    API pequena e versionada (proposta na issue #7: `Target`,
    `Vocabulary`, `Checker` primeiro; outros pontos só quando uma
    segunda extensão precisar). **Core:** gramática única (as extensões
    não mudam a sintaxe — usam tags e atributos com namespace como
    `on:`/`bind:`), AST, símbolos/`ProjectIndex`/imports/visibilidade,
    componentes/slots/cardinalidade, as construções genéricas
    (`if`/`for`/`switch`, interpolação e — quando existirem — `state`,
    `derived` e lambdas, que cada alvo declara se suporta),
    diagnósticos e source maps, a análise Java dentro de `${...}` (11c)
    e a interoperabilidade Java (12), a API e o carregador de extensões
    e o host genérico do LSP. **Extensões:** os alvos (o JTE embutido mas
    implementado pela mesma API — critério: `.jte` gerado byte a byte
    igual —, o gerador de HTML estático do site, TamboUI, Swing,
    JavaFX), os vocabulários (o HTML acompanha o alvo JTE; as regras de sinks e de
    URLs perigosas existem desde o subprojeto 14 — `HtmlSecurityChecker` e
    `SukoSafe` — e mantêm revisão de segurança; o escape de caracteres
    continua a ser do JTE em runtime), os
    namespaces de atributos, verificadores extra (a11y, i18n),
    origens de registry, comandos da CLI e contribuições ao LSP. **A
    reatividade também é resolvida em compile-time** (modelo
    Svelte/Solid, não React): o compilador calcula estaticamente o
    grafo `state` → `derived` → subárvores e gera setters que marcam o
    que mudou, `derived` recalculado só quando as dependências mudam e,
    nos alvos retidos, mutações dirigidas — sem proxies, subscrições em
    runtime nem virtual DOM. O que não é resolúvel em compile-time é
    pequeno: agendar frames de forma thread-safe e coalescida (valores
    vindos de outras threads), a identidade de instâncias com estado
    entre frames (cache por posição + `key`) e a ligação ao event loop
    do toolkit. Proposta para isso: a extensão do alvo **gera** essas
    poucas classes de suporte para dentro do código gerado do projeto,
    em vez de um jar de runtime — "nenhuma dependência do Suko em
    runtime" mantém-se em todos os alvos. Riscos: cada ponto de
    extensão é uma promessa de compatibilidade; extensões são código de
    terceiros a correr no build e no LSP (o LSP só as carrega em
    workspaces confiáveis). Sem spec ainda; depende de 11c e 12.

    **Arrumação em artefactos e multi-alvo (decidido pelo utilizador,
    2026-10-03).** `suko-api` (a API de extensões — `Target`,
    `Vocabulary`, `Checker` — e uma vista só de leitura do AST, dos
    diagnósticos e do índice; o único contrato que as extensões veem),
    `suko-core` (compilador, verificador, análise Java, carregador de
    extensões) e **um artefacto de compile-time por alvo**: `suko-jte`,
    `suko-html`, `suko-javafx`, `suko-tamboui` (sobre JLine), e
    variantes `+js` para a web (`jte+js`, `html+js`). Objetivo: o mesmo
    ficheiro (ex.: `intro.sk`) compilar para vários alvos no mesmo
    projeto. **Vocabulário: neutro + nativo.** O core define
    primitivas neutras (`<column>`, `<row>`, `<text>`, `<button>`,
    `<list>`, `<input>`, ...) que todos os alvos mapeiam (HTML, `VBox`,
    `Toolkit.column`, ...); cada alvo acrescenta as suas tags nativas
    (HTML livre, widgets JavaFX/TamboUI). Um ficheiro que usa uma tag
    nativa fica preso a esse alvo, e o verificador diz-o; um ficheiro só
    com primitivas compila para todos. **"java" não é um alvo de
    render:** é a camada transversal do item 12 — em qualquer alvo, cada
    componente expõe uma API Java (classe/método que devolve o
    `Component` desse alvo: `gg.jte.Content` no JTE, `Node` no JavaFX,
    `Element` no TamboUI), e um `.sk` pode importar componentes escritos
    em Java; vive no core, e cada extensão só define o tipo de
    `Component`. **Alvos `+js`:** a reatividade resolvida em
    compile-time gera JS vanilla mínimo só para os componentes com
    estado/eventos (HTML renderizado no servidor + "ilhas" interativas,
    modelo Svelte), sem framework no cliente; é o alvo mais sensível em
    segurança. **Revisto a 2026-10-04 (13b):** não há variantes `+js`
    separadas — o suporte a JS vive no `suko-jte` (o alvo continua `jte`),
    com `reactive component`, `state`/`derived` (com `var`), `on:evento=${lambda}`
    e `bind:`; spec em
    `docs/superpowers/specs/2026-10-04-suko-reatividade-ilhas.md`,
    implementação depois do item 12; (gerar JS a partir de expressões mantendo o escape) e
    exige revisão do `security-specialist`. Riscos: a matriz de testes
    cresce com cada alvo (começar com dois — JTE, já existente, e
    TamboUI, da issue #7); as primitivas neutras tendem para o mínimo
    denominador comum, por isso ficam poucas e as tags nativas são a
    saída.

14. **Segurança por omissão** — **CONCLUÍDO** (2026-10-05; ramo
    `subprojeto-14-seguranca`, ainda sem PR). Decidido pelo utilizador a
    2026-10-04 ("aplica todas as medidas de segurança"); bloqueava a primeira
    release. Spec em `docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md`,
    plano em `docs/superpowers/plans/2026-10-05-suko-seguranca-por-omissao.md`,
    modelo de ameaças e configuração em `docs/security.md`.
    O JTE só escapa caracteres no render; o Suko acrescenta o que depende
    do significado do valor: allowlist de protocolos de URL em todos os
    componentes (classe `SukoSafe` gerada no projeto, só JDK, sem dependência
    de runtime), erro `UNSAFE_SINK` para valores dinâmicos em `<script>`/
    `<style>`/`on*`/`srcdoc`/`style`/`<base>`/..., `rel="noopener"` automático,
    lint de CSP estrita, `OwaspHtmlPolicy` do JTE ativada pelo plugin Gradle,
    assinatura Ed25519 do índice do registry (CLI), corpus XSS (`XssCorpusTest`)
    e a loja `examples/shop` (Spring Boot + H2) como alvo do pentest.

    **Códigos novos:** `UNSAFE_SINK` (ERROR), `RESERVED_NAME` (ERROR),
    `UPPERCASE_NAME` (ERROR), `TRUSTED_URL`/`TRUSTED_STYLE` (INFO),
    `CSP_INLINE` (WARNING, só com `strictCsp`) e, na CLI, `REGISTRY_UNSIGNED`,
    `REGISTRY_BAD_SIGNATURE`, `REGISTRY_MISMATCH`, `REGISTRY_EXPIRED`,
    `REGISTRY_ROLLBACK`, `REGISTRY_MANIFEST_HASH` e `REGISTRY_INVALID`
    (este último — schema, datas ilegíveis, caminho de manifesto inválido — não
    estava na tabela da spec). Configuração: `suko { security { ... } }` (Gradle)
    e `<security>` (Maven); auditoria dos `trusted*` em `security-audit.json`.
    Alterações ao compilador feitas ao construir a loja: o emissor qualifica
    `Map` como `java.util.Map` em `@param`/`@for`.

    **Lacunas conhecidas (registadas, não corrigidas):**

    - (a) **Tipos de parâmetros.** Os componentes só recebem tipos de biblioteca
      (`String`, `List`, `Map`...); a loja usa `List<Map<String,String>>` como
      view models. Item 12 / 11c.
    - (b) **`<!DOCTYPE html>`** não faz parse (`PARSE_ERROR` em `<!`): as páginas
      escritas em Suko não têm doctype e abrem em modo quirks.
    - (c) **Política do JTE.** A loja usa o plugin `gg.jte.gradle` em modo
      `generate()` com templates pré-compilados e uma tarefa `verifyJtePolicy`;
      a `OwaspHtmlPolicy` está aplicada aí (provado). Para utilizadores Gradle o
      plugin do Suko define `htmlPolicyClass` de forma preguiçosa quando
      `gg.jte.gradle` está aplicado e `jtePolicy` é `true`. No Maven só há
      aviso. A compilação de templates em runtime (modo de desenvolvimento do
      JTE) é só de demonstração e fica fora do pentest.
    - (d) **Chave do registry.** `trusted-keys.json` da CLI está vazio até à
      primeira release: até lá, `suko add` contra o registry oficial (HTTPS)
      falha com `REGISTRY_UNSIGNED`. O `.sig` do registry oficial é um passo de
      release (checklist em `docs/security.md`).
    - (e) **Maven** só avisa sobre `htmlPolicyClass` (ver c).
    - (f) **Atributos de extensões.** Os atributos de código/URL declarados por
      `Vocabulary` (a spec previa-os) não estão implementados; só por
      configuração (`codeAttributes`/`urlAttributes`).
    - (g) **LSP.** Usa as opções de segurança por omissão: o `suko.security` do
      build não é lido (o `extensions.json` poderia transportá-las).
    - (h) **Playwright com CSP/Trusted Types** fica para o pentest.
    - (i) **Ed25519 no native-image da CLI.** Foi exercitado
      manualmente pelo implementador da Tarefa 11 com um GraalVM CE local (relato
      dele: 139 s, `list`/`add` com registry assinado). O `NativeImageSmokeTest`
      é saltado quando não há GraalVM no PATH (na última corrida do repositório
      inteiro foi saltado). A inclusão do recurso `trusted-keys.json` por glob só
      fica provada quando uma release embutir uma chave real.
    - (j) **Gramática (vista ao construir a loja).** `<img>`/`<input>` precisam de
      `/>`; chavetas em texto de `<style>` e `<` dentro de strings de atributos
      não fazem parse; componentes do mesmo package exigem `import` explícito;
      `for` é palavra reservada, logo `<label for=...>` não faz parse; texto
      como `Site (opcional)` é lido como chamada de componente.
    - (k) **`:` e `@` em nomes de atributo** não são aceites pelo lexer, por isso
      `x-on:click`, `@click`, `hx-on:click` e `xlink:href` não se escrevem, e os
      ramos de `:`/`@` do verificador são código morto até o lexer os permitir.
    - (l) **Modelo de confiança do registry** (detalhes em `docs/security.md`):
      a assinatura protege o transporte/anfitrião, não um `suko.json` ou
      lockfile hostil; apagar o lockfile repõe o anti-strip/rollback; o
      lockfile guarda **um** registry (trocar `--registry` perde a memória do
      anterior); unidades de rede mapeadas parecem locais; refs móveis sem
      `expires` podem ser reapresentadas (a política de release deve defini-lo).
    - (m) **Loja.** Limitações documentadas em `examples/shop/README.md`
      (cabeçalhos em recusas do firewall/Tomcat, whitelabel, sem limites contra
      abuso, caracteres de controlo/bidi em nomes). Uma corrida no checkout
      (várias encomendas do mesmo carrinho) foi encontrada na revisão e
      corrigida.

    **Pendentes do subprojeto 14** (revisão do architect; um por linha):

    - 13b: a spec `docs/superpowers/specs/2026-10-04-suko-reatividade-ilhas.md`
      está desalinhada com o 14 — `onclick` WARNING vs ERROR (~108-109, ~267, ~278);
      "componentes simples sem verificação de URL" (~297-298) já está fechado; o
      seu `suko { js { urlSchemes } }` (~288) tem de se fundir em
      `security.urlSchemes`; a "classe auxiliar" do servidor (~292-296) deve ser o
      `SukoSafe`; o `sanitizeUrl` do cliente (~286, `new URL(v, baseURI)`) é outro
      algoritmo face ao `SukoSafe.url` (`ｊａｖａｓｃｒｉｐｔ:` em largura total difere)
      — especificar o cliente como port do `SukoSafe.url` com vetores partilhados
      do `xss-corpus.txt`. A spec **não** foi editada; só fica o registo.
    - API v2 (decisões para o próximo incremento de `VERSION`): (a) `SecurityOptions`
      é específica de HTML mas é o único canal de opções (`generatedPackage` e
      `generatedJavaDir` deviam ser opções de projeto); (b) o `ProjectOutputWriter`
      escreve `RESOURCE` na raiz dos templates e a limpeza só segue `JAVA_SOURCE`
      (o JS da 13b vai para `static/suko`); (c) o `SecurityAudit` do core fixa os
      códigos `TRUSTED_*` do `suko-jte` e analisa a mensagem após `": "` — usar uma
      carga estruturada no diagnóstico; (d) o `HtmlSecurityChecker` trata todo o
      atributo `on*` como handler — o `on:click=${lambda}` da 13b precisa de uma
      exceção explícita; (e) item 12: uma classe Java que implemente `Component`
      escreve HTML cru (mesma confiança que os slots) e o checker nunca a vê.
    - `<!DOCTYPE html>` não faz parse, logo todas as páginas escritas em Suko
      renderizam em modo quirks — corrigir antes do pentest (portão).
    - Loja/pentest: a loja expõe pouco a um pentest (`cssValue`, `pathSegment`,
      `srcset`/`imageDataTypes`, `rel`/`noopener`, `ping`, `hx-*` e `trusted*`
      nunca recebem input HTTP; não há área autenticada) — criar um conjunto de
      páginas/perfil de laboratório antes do pentest, e documentar como definir
      `htmlPolicy` num bean `TemplateEngine` personalizado (pergunta M4 da spec).
    - Teste no core que renderize o golden com a `OwaspHtmlPolicy` ligada.
    - `WebsiteGenerator` ignora `projectOutputs`: a primeira página do site com
      `href` dinâmico falha (falta `io.suko.generated.SukoSafe`).
    - Teste com `mvn` real da ligação do POJO `<security>` através do `plugin.xml`
      escrito à mão.
    - O LSP usa as opções de segurança por omissão.
    - Parqueados do ledger: testes do encaminhamento de `emitProject`; exceção
      engolida em `activeVocabularies`; `type`/`is`/`classid` não cobertos pelo
      checker; órfãos em `generatedJavaDir` quando o diretório muda; `TrustedKeys`
      permissivo + `catch` silencioso no `verify`; `publicKeys` comparadas por
      string exata; o lockfile lembra um só registry; unidades de rede mapeadas
      contam como locais; falta `[::1]` numa mensagem do `VerifiedIndex` (~l. 156).

    **Portão de release/pentest (explícito):** checklist de release em
    `docs/security.md` (incluindo o `.sig` do registry oficial e a chave embutida),
    o pentest à loja, o `<!DOCTYPE html>` e o teste com `mvn` real do `<security>`.

    **Ordem:** 13a ✔ → 14 ✔ → item 12 → 13b → pentest à loja → 11c →
    editores. A release continua bloqueada por 11b, 11c e item 12 (13a e 14
    cumpridos). No fim da 13b: a loja ganha ilhas JS e é o alvo do pentest.
