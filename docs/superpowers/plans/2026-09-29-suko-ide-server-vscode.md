# Suko — Subprojeto 11a: Language Server + extensão VSCode — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar o language server `suko-lsp` (diagnósticos em tempo real, go-to-definition, hover, completion de componentes/parâmetros/slots com auto-import) e a extensão VSCode `editors/vscode/` que o embute, gerando um `.vsix` no CI.

**Architecture:** O `suko-core` ganha, de forma retrocompatível, (1) um snapshot de fontes em memória (`SukoSources`) com uma entrada só de verificação, (2) spans e assinaturas completas no `ProjectIndex`, (3) um resolver público chamada→declaração partilhado pelo `SemanticChecker` e pelo server, (4) validação de slots/parâmetros entre ficheiros (D3) e (5) um modo tolerante do `SukoAstBuilder` só para o server. O `suko-lsp` (LSP4J, stdio) só depende do `suko-core`; a extensão é um cliente fino sem lógica de linguagem.

**Tech Stack:** Java 21 (`options.release`), ANTLR 4.13.1, JUnit 5, LSP4J, Gson 2.11.0 (mesma versão da CLI), TypeScript + `vscode-languageclient` + `@vscode/vsce` + `@vscode/test-electron`.

**Spec:** `docs/superpowers/specs/2026-09-28-suko-ide-server-vscode.md` (D1–D4 e "Mudanças no `suko-core`" são vinculativos; este plano não os reabre).

## Global Constraints

- **G1 — Sem trailers de atribuição.** Nenhum commit nem descrição de PR leva `Co-Authored-By:` nem `Claude-Session:` (`CLAUDE.md` da raiz).
- **G2 — Retrocompatibilidade do core.** Plugins Gradle/Maven e CLI não mudam de comportamento, exceto D3 (intencional e documentada). A suite atual de `suko-core`, `suko-gradle-plugin`, `suko-cli`, `suko-components` e `suko-registry-generator` é a guarda: corre-se `./gradlew build` no fim de cada tarefa que toque o core.
- **G3 — Os diagnósticos vêm sempre do caminho normal de parse** (`SukoErrorListener` + `SukoAstBuilder` estrito, idêntico ao do `sukoCompile`). O modo tolerante nunca reporta diagnósticos; só serve completion/navegação.
- **G4 — Gramática intocada.** Nenhuma tarefa edita `SukoLexer.g4`/`SukoParser.g4` (o `antlr4-specialist` só entra se uma tarefa descobrir que é inevitável — parar e reportar antes).
- **G5 — Sem sealed `Error` em `Statement`/`Expr`.** O modo tolerante descarta subárvores; não propaga variantes novas aos `switch` exaustivos do `JteEmitter`/`SemanticChecker`.
- **G6 — Lógica só no server.** A extensão TypeScript não contém regras de linguagem (para o 11b reutilizar o server).
- **G7 — Locais dos números de linha**: citados a 2026-09-29; localizar sempre pelo texto âncora.
- **G8 — Cada tarefa termina com um deliverable testável e um commit.** Testes escritos antes da implementação (vermelho → verde).
- **G9 — Nenhuma dependência nova fora das declaradas na spec** (LSP4J no `suko-lsp`; toolchain npm em `editors/vscode/`).

## Não-objetivos (spec §Não-objetivos, repetidos porque são a tentação)

Java em `${...}` e ligar o `JavacTask` (11c); IntelliJ (11b); qualquer mudança de gramática (incl. `textRun`); compilação incremental; rename/formatação/code actions/semantic tokens; binário nativo; publicação no Marketplace/Open VSX.

## Mapa de ficheiros

| Área | Ficheiros |
|---|---|
| Core (novo) | `suko-core/src/main/java/io/suko/lang/project/SukoSources.java`, `.../project/ProjectAnalysis.java`, `.../project/CallResolver.java`, `.../SukoAstBuilder` (modo tolerante) |
| Core (alterado) | `ProjectIndex`, `ProjectIndexEntry`, `SukoProjectCompiler`, `ast/ComponentDecl`, `ast/Statement` (`ComponentCallStmt`, `Arg`, `SlotFill`), `semantic/SemanticChecker`, `SukoAstBuilder` |
| Server | `suko-lsp/` (novo módulo; `settings.gradle.kts` + `build.gradle.kts`) |
| Extensão | `editors/vscode/` (novo, fora do Gradle) |
| CI | `.github/workflows/build-vscode-extension.yml` |
| Docs | `ARCHITECTURE.md`, roadmap item 11a |

---

## Fase A — `suko-core`

### Task 1: `SukoSources` e entrada só de verificação

**Files:** Create `project/SukoSources.java`, `project/ProjectAnalysis.java`; Modify `ProjectIndex.java`, `SukoProjectCompiler.java`; Test `project/SukoSourcesTest.java`, `project/ProjectParityTest.java`.

- [ ] Teste: `SukoSources.fromDirectory(root)` devolve mapa imutável caminho-relativo → texto só de `.sk`; `withOverlay(Map<Path,String>)` sobrepõe/acrescenta sem mutar o original; overlay com `null`/remoção não suportada (documentar).
- [ ] Teste de paridade (`ProjectParityTest`): para `suko-core/src/test/resources` de projetos multi-ficheiro existentes **e** para `suko-components/src/main/suko`, `SukoProjectCompiler.compile(Path)` e `compile(SukoSources.fromDirectory(...))` produzem os mesmos `.jte` e os mesmos diagnósticos (código, ficheiro, span).
- [ ] Implementar `ProjectIndex.build(SukoSources)` (lógica atual; caminhos passam a relativos ao root) e `SukoProjectCompiler.compile(SukoSources)`; as versões `Path` delegam via `fromDirectory`. **Decisão tomada na implementação (difere do rascunho):** `SukoSources` guarda também o `root` real e o índice continua a expor caminhos **absolutos** (`root.resolve(relativo)`), porque `RegistryGenerator` e `ProjectIndexTest` dependem disso e o server precisa de URIs absolutos. Sem mudança de comportamento nos consumidores.
- [ ] Implementar `SukoProjectCompiler.analyze(SukoSources)` → `ProjectAnalysis` (`Map<Path, FileAnalysis>` com `SukoFile` (nullable em erro de parse), `DiagnosticCollector`), **sem** `JteEmitter`. `compile(...)` passa a ser `analyze` + emissão para não haver dois caminhos que divirjam.
- [x] `./gradlew build` verde (exceto `SukoSpringBootIntegrationTest`, bloqueado por HTTP 429 do Maven Central no sandbox, não por código); commit `feat(core): SukoSources e entrada só de verificação`. Nota: `JteCompiler` ganhou `analyze(ProjectIndex, Path)` (parse + semântica sem emissão), partilhado por `compile` e `SukoProjectCompiler.analyze`.

### Task 2: Spans para navegação

**Files:** Modify `ast/ComponentDecl`, `ast/Statement`, `SukoAstBuilder`; Test `SukoAstSpansTest`.

- [ ] Teste: para um `.sk` com `component Card(String title, Component header) {...}` e `Card(title = "x") { header { ... } }`, o texto coberto por `ComponentDecl.nameSpan`, `ComponentCallStmt.nameSpan`, `Arg.span` (nome do argumento) e `SlotFill.nameSpan` é exatamente `Card` / `Card` / `title` / `header` (usar `startIndex`/`endIndex` sobre o fonte).
- [ ] Adicionar `nameSpan` a `ComponentDecl` e `ComponentCallStmt`; `span` a `Arg` (para argumentos sem nome, span do valor) e `nameSpan` a `SlotFill`. Corrigir todos os sítios de construção (`new ComponentDecl(`, `new ComponentCallStmt(`, `new Statement.Arg(`, `new Statement.SlotFill(`) — `grep` em `src/main` **e** `src/test` (testes que constroem AST à mão).
- [ ] Suite do core verde (nenhum golden `.jte` pode mudar); commit `feat(core): spans de nome em declarações, chamadas, argumentos e slots`.

### Task 3: `ProjectIndexEntry` com parâmetros e span

**Files:** Modify `ProjectIndexEntry`, `ProjectIndex`; Test `ProjectIndexTest`.

- [ ] Teste: a entrada de um componente com `String title`, `Component header`, `List<Component> items`, `Component footer = null` expõe `params()` com nome, tipo (texto), default (texto ou vazio), `slot?` e cardinalidade; `declarationSpan()` e `nameSpan()` correspondem ao fonte.
- [ ] Novo record `ParamInfo(String name, String type, Optional<String> defaultText, boolean slot, Optional<Cardinality> cardinality, boolean renderProp)`; `ProjectIndexEntry` troca `paramCount` por `List<ParamInfo> params` mantendo `paramCount()` como método derivado (os consumidores atuais não mudam). Guardar o span deixa de ser o mapa privado `spanByQualifiedName`.
- [x] Commit `feat(core): índice do projeto expõe parâmetros e spans` (record `ParamInfo` novo; `ProjectIndexEntry` ganhou `params`, `declarationSpan`, `nameSpan`).

### Task 4: Resolver público chamada → declaração

**Files:** Create `project/CallResolver.java`; Modify `semantic/SemanticChecker`; Test `project/CallResolverTest`.

- [ ] Teste: dado um `SukoFile` + `ProjectIndex`, `CallResolver.resolve(name)` devolve `Resolved.Local(ComponentDecl)`, `Resolved.Project(ProjectIndexEntry)`, `Resolved.NotVisible(entry)` ou `Resolved.NotFound` — casos: mesmo ficheiro, importado (com alias), nome qualificado, privado noutro ficheiro, inexistente.
- [ ] Extrair a lógica de `resolveViaProject`/`currentImportedByShortName`/`nonVisibleImportedNames` do `SemanticChecker` para `CallResolver`; o `SemanticChecker` passa a usá-lo (comportamento e mensagens idênticos — a suite `SemanticChecker*Test` é a guarda, sem editar as suas asserções).
- [x] Commit `refactor(core): resolver público de chamadas, partilhado com o checker`.

### Task 5: Validação de parâmetros e slots (D3 + `PARAM_NOT_FOUND`)

**Files:** Modify `semantic/SemanticChecker`; Test `SemanticCheckerCrossFileTest`, ajustes em `SemanticCheckerTest`.

- [ ] Testes (um por regra, no mesmo ficheiro **e** entre ficheiros): `REQUIRED_SLOT_MISSING`, `SLOT_NOT_FOUND`, `CARDINALITY_VIOLATION`, `PARAM_NOT_FOUND` (mensagem com o nome usado e a lista de parâmetros válidos, no estilo das restantes; `dx-specialist` revê o texto).
- [ ] Generalizar `checkComponentCall` para trabalhar sobre uma lista de `ParamInfo` (construída a partir do `ComponentDecl` local ou do `ProjectIndexEntry`), eliminando o ramo "resolvido via projeto: não verificar". Regras do slot `children` implícito preservadas (subprojeto 6).
- [ ] `PARAM_NOT_FOUND` só para `Arg` com nome; argumentos posicionais fora do âmbito.
- [ ] **Guarda D3:** `./gradlew :suko-components:test :suko-website:build` — os 8 componentes e as páginas do site têm de continuar sem erros. Qualquer falha é um erro real: corrigir o `.sk`, registar no commit e em "Limitações" do `ARCHITECTURE.md`. Correr também `suko-gradle-plugin` (`SukoSpringBootIntegrationTest`) e `suko-cli` (`FullCycleTest`).
- [ ] Commit `feat(core): validação de parâmetros e slots entre ficheiros (D3) e PARAM_NOT_FOUND`.

### Task 6: Modo tolerante do `SukoAstBuilder`

**Files:** Modify `SukoAstBuilder`; Test `SukoAstBuilderTolerantTest`.

- [ ] Testes com os quatro casos obrigatórios, cada um com um segundo componente **válido** no mesmo ficheiro que tem de continuar presente no AST, sem exceção: `${t.` a meio; `component B( {`; `A(` num corpo; `Card(ti` dentro de `<div>`. Mais um caso de regressão: o modo estrito continua a lançar/diagnosticar exatamente como antes (o `JteCompiler` não muda).
- [ ] `SukoAstBuilder.tolerant(String source)` (ou construtor com flag): `try/catch` por componente e por instrução, descartando só essa subárvore; `NullPointerException` incluída (hoje `templateBlock()` rebenta). Sem novas variantes de AST (G5). Nunca ligado ao `JteCompiler`/`ProjectIndex` (G3).
- [ ] Commit `feat(core): modo tolerante do AST builder para o language server`.

### Task 7: Revisão intermédia da Fase A

- [ ] Despachar `architect` para rever a Fase A contra a spec (superfície de AST, terceiro caminho de parse, D3) antes de começar o server. Corrigir o que apontar; só depois avançar.

---

## Fase B — `suko-lsp`

### Task 8: Módulo, dependências e fat jar

- [ ] `include("suko-lsp")` em `settings.gradle.kts`; `suko-lsp/build.gradle.kts`: `implementation(project(":suko-core"))`, `org.eclipse.lsp4j:org.eclipse.lsp4j` (versão estável mais recente compatível com Java 21, pinada), Gson 2.11.0; task `fatJar` no padrão do `suko-cli` (task `Jar` própria, sem Shadow) com `Main-Class`; `suko-lsp` **não** depende de `suko-cli`.
- [ ] Teste de fumo: `fatJar` arranca `java -jar` e responde a `initialize` por stdio (JUnit com processo filho).
- [ ] Commit `feat(lsp): módulo suko-lsp com fat jar`.

### Task 9: Conversão de posições

**Files:** `PositionMapper` + `PositionMapperTest`.

- [ ] Testes: linhas com acentos, emojis fora do BMP (par de surrogates), CRLF e LF; ida-e-volta offset ↔ posição LSP; diagnóstico `PARSE_ERROR` com índices `-1` usa linha/coluna (ANTLR: 1-based, colunas em code points → LSP 0-based, UTF-16).
- [ ] Tabela de linhas por documento; um único ponto de conversão usado por todas as features.
- [ ] Commit.

### Task 10: Descoberta de projeto e sincronização

- [ ] Testes: `sourceRoot` do `suko.json` (lido com Gson, sem depender do `suko-cli`); senão `src/main/suko`; senão setting `suko.sourceRoot`; workspace multi-pasta → um índice por root, sem resolução entre roots.
- [ ] `TextDocumentSyncKind.Full`; documentos abertos entram como overlay em `SukoSources`; `workspace/didChangeWatchedFiles` para ficheiros fechados.
- [ ] Commit.

### Task 11: Diagnósticos com debounce

- [ ] Testes com cliente LSP4J em memória: abrir ficheiro com erro → `publishDiagnostics` com código/mensagem/severidade do `sukoCompile` e posição certa (linhas com acentos e fora do BMP); editar para corrigir → diagnósticos limpos; apagar/fechar ficheiro → limpa os que deixaram de existir; várias alterações em <250 ms → uma só verificação (relógio injetável para o teste, sem `sleep`).
- [ ] Debounce de 250 ms; `analyze(SukoSources)` do Task 1; publica por ficheiro.
- [ ] Commit.

### Task 12: Go-to-definition

- [ ] Testes: nome de componente numa chamada (mesmo ficheiro e outro), num `import`; nome de argumento → parâmetro; nome de slot (`header { }`) → parâmetro. Nada → resposta vazia.
- [ ] Usa `CallResolver` (mesma resolução do compilador) e os `nameSpan`s; localização por posição sobre o AST **estrito** do último parse bom (fallback: tolerante).
- [ ] Commit.

### Task 13: Hover

- [ ] Testes: assinatura com tipos e defaults, slots e cardinalidade, visibilidade, package, ficheiro. Texto revisto pelo `dx-specialist`.
- [ ] Commit.

### Task 14: Completion por tokens

- [ ] Testes por contexto (lexer, não AST — o `A(` engolido como `textRun` prova porquê): após `import` → qualificados `public`; corpo → visíveis + keywords (`if else for switch case default`); componentes `public` não importados com `additionalTextEdits` de `import` (posição correta com/sem `package` e imports existentes); dentro de `Nome(...)` → parâmetros ainda não passados como `nome = `; dentro de `Nome() { }` → slots como `header { }`.
- [ ] Contexto determinado por tokens do lexer até ao cursor, tolerante a fonte incompleto.
- [ ] Commit.

### Task 15: Robustez e logging

- [ ] Teste: um pedido cujo handler lança devolve vazio/log, o server continua vivo e responde ao pedido seguinte. Envolver cada handler num isolador único; logs por `window/logMessage` e stderr.
- [ ] Commit.

---

## Fase C — Extensão VSCode

### Task 16: Scaffold e gramática TextMate

- [ ] `editors/vscode/`: `package.json` (`engines.vscode` mínimo, `contributes.languages/grammars/configuration/commands`, settings `suko.java.home`/`suko.sourceRoot`/`suko.trace.server`), `language-configuration.json` (`//`, `/* */`, pares, auto-fecho), `syntaxes/suko.tmLanguage.json` (`package`/`import`/`public component`, keywords, literais, tags/atributos, `${...}` como região Java embutida, `$ident` em strings, comentários).
- [ ] Teste de gramática com `vscode-tmgrammar-test` sobre snapshots de `examples/*.sk` e de um componente de `suko-components`.
- [ ] Commit.

### Task 17: Cliente LSP e descoberta de Java

- [ ] `src/extension.ts`: `java -jar server/suko-lsp.jar` por stdio; Java por `suko.java.home` → `JAVA_HOME` → `java` no PATH; verificar 21+ antes de arrancar (função pura testável com testes unitários: parse de `java -version`); mensagem com a versão encontrada e botão que abre a setting; comando "Suko: Restart Language Server"; canal de output.
- [ ] `scripts/copy-server.*`: copia o jar de `./gradlew :suko-lsp:fatJar` para `editors/vscode/server/`; `npm run package` gera o `.vsix`.
- [ ] Commit.

### Task 18: Teste de fumo e CI

- [ ] `@vscode/test-electron`: ativa, o server responde, um `.sk` com erro produz um diagnóstico.
- [ ] `.github/workflows/build-vscode-extension.yml`: JDK 21, `./gradlew :suko-lsp:fatJar`, `npm ci`, testes, `npm run package`, `actions/upload-artifact` do `.vsix`. **Sem publicar** (D4).
- [ ] Commit.

---

## Fase D — Fecho

### Task 19: Documentação e revisão final

- [ ] `ARCHITECTURE.md`: módulos `suko-lsp` e `editors/vscode`; terceiro caminho de parse (tolerante, nunca reporta diagnósticos); validação entre ficheiros (remover a limitação "verificação de slot fills não atravessa ficheiros" e a nota do `ProjectIndex`); `PARAM_NOT_FOUND`; item 11a CONCLUÍDO no roadmap.
- [ ] `suko-lsp/README.md` e `editors/vscode/README.md` (instalação do `.vsix`, requisitos Java 21+, settings).
- [ ] `./gradlew build` verde a nível da raiz.
- [ ] Despachar `architect` para a revisão final da branch inteira; corrigir achados.
- [ ] **Verificação manual pelo utilizador** (critério da spec): `.vsix` instalado num VSCode real, projeto criado com `suko init` — highlighting, erros ao escrever, go-to-definition, hover, completion com auto-import. Esta tarefa não fecha sem essa confirmação.

## Riscos e pontos de decisão

- **Tolerância a erros (Task 6)** é o risco principal; se um padrão comum de edição não for coberto, o fallback é o último AST estrito bom + completion por tokens.
- **D3 (Task 5)** pode revelar erros reais em `.sk` existentes: parar e reportar em vez de suprimir a regra.
- **`ProjectIndexEntry.sourceFile()` relativo (Task 1)** toca vários consumidores; a suite do `suko-gradle-plugin` e do `suko-cli` apanha regressões.
- **Versão do LSP4J e do `vscode-languageclient`** são fixadas na Task 8/17 pelo que resolver no momento, e registadas no commit.

## Ordem e dependências

Tasks 1→2→3→4→5 (sequenciais, tocam os mesmos ficheiros); Task 6 pode correr em paralelo com 4–5. Fase B depende de 1, 3, 4 e 6. Fase C depende só de 8 (jar) e pode começar assim que o `initialize` responder.
