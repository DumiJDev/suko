# Suko — Subprojeto 11a: Language Server + extensão VSCode

Data: 2026-09-28

## Contexto

Os subprojetos 1-10 estão fechados em `main`; o 3 foi reclassificado como
**PARCIAL** a 2026-09-28, porque o `JavacTask` existe e é testado mas não
está ligado ao pipeline (ver `ARCHITECTURE.md`, roadmap item 3). O item 11
do roadmap (suporte de IDE, VSCode + IntelliJ) é o bloqueio declarado da
primeira release: "antes de publicar não temos suporte para vscode e
intellij".

No scoping de 2026-09-28 o utilizador escolheu o nível **experiência
completa** para a release (highlighting, diagnósticos em tempo real,
go-to-definition, hover, completion de componentes/parâmetros **e** de
expressões Java dentro de `${...}`) e aceitou partir o item 11 em três
specs sequenciais, com a release só depois das três:

- **11a** (este documento) — language server Suko + extensão VSCode.
- **11b** — cliente IntelliJ reutilizando o server (LSP4IJ vs. plugin PSI
  próprio decidido nesse scoping).
- **11c** — inteligência Java dentro de `${...}` (completion, hover,
  verificação de tipos), que também liga o `JavacTask` ao pipeline e fecha
  a lacuna de o `sukoCompile` não verificar tipos Java.

O desenho abaixo incorpora a revisão do agente `architect` feita antes
desta spec (limites de módulos, superfície da AST, sonda de tolerância a
erros contra as classes compiladas do `suko-core`).

## Decisões do utilizador

- **D1 — Abordagem:** server Java sobre LSP4J reutilizando o `suko-core`,
  com mudanças pequenas e retrocompatíveis no core. Rejeitadas: server
  TypeScript com gramática própria (tree-sitter), porque duplicaria a
  gramática e o verificador e divergiria; e server Java sem tocar no core
  (gravar buffers em disco + completion por regex), porque recompila do
  disco a cada tecla, perde toda a análise num erro de sintaxe e adivinha
  a completion.
- **D2 — Runtime:** o fat jar do server vai embutido na extensão e corre
  no Java 21+ do utilizador. Rejeitados: binário nativo GraalVM (um por
  plataforma, e o projeto não compila nativos — decisão do subprojeto 8) e
  jbang (depende de jbang instalado e de uma release publicada).
- **D3 — Validação entre ficheiros ligada:** ao passar a indexar os
  parâmetros de cada componente, o compilador passa a validar também as
  chamadas a componentes de outros ficheiros (slots obrigatórios em falta,
  parâmetros inexistentes). Fecha a limitação aceite em `ARCHITECTURE.md`
  ("slot fills não verificados entre ficheiros"). É uma mudança de
  comportamento deliberada: builds que hoje passam podem passar a falhar
  por erros reais que estavam escondidos.
- **D4 — Distribuição:** o 11a entrega só o `.vsix`, gerado no CI como
  artefacto. Publicar no VS Code Marketplace / Open VSX fica para a
  release, tal como o Maven Central e o Gradle Plugin Portal.

## Módulos

- **`suko-lsp`** (novo módulo Gradle, Java 21, LSP4J). Depende só do
  `suko-core`. Empacotado como fat jar com o mesmo padrão do `suko-cli`
  (task `Jar` própria, sem Shadow, `release 21`). **Não** depende do
  `suko-cli`: lê a chave `sourceRoot` do `suko.json` diretamente (o LSP4J
  já traz Gson; alinhar a versão com a do `suko-cli`, 2.11.0).
- **`editors/vscode/`** (novo, fora do Gradle, tal como `examples/`).
  Extensão TypeScript: gramática TextMate, cliente LSP fino, comandos.
  O jar do server é copiado para `editors/vscode/server/` por um script
  antes do empacotamento.

## Mudanças no `suko-core`

Todas retrocompatíveis: os plugins Gradle/Maven e a CLI não mudam de
comportamento (exceto D3, intencional), e a suite atual é a guarda.

1. **`SukoSources`** — snapshot imutável de caminho relativo → texto, com
   `SukoSources.fromDirectory(Path root)` e uma forma de sobrepor
   documentos (`withOverlay(Map<Path,String>)` ou equivalente).
   `ProjectIndex.build(SukoSources)` e
   `SukoProjectCompiler.compile(SukoSources)` são as implementações
   reais; as versões com `Path` passam a delegar nelas. Nova entrada
   **só de verificação** (parse + índice + verificação semântica, sem
   `JteEmitter`) que devolve, por ficheiro, o `SukoFile` e os
   diagnósticos. **Sem compilação incremental:** o server reverifica o
   root inteiro com debounce — o mesmo compromisso aceite no `sukoWatch`.
2. **Posições para navegação.**
   - `nameSpan` em `ComponentDecl` e em `ComponentCallStmt` (hoje só há o
     span da declaração/chamada inteira).
   - Spans em `Statement.Arg` e `SlotFill` (hoje não têm).
   - `ProjectIndexEntry` passa a expor a lista de parâmetros (nome, tipo,
     default, se é slot e a sua cardinalidade) e o span da declaração
     (hoje só `paramCount`; o span está privado em `spanByQualifiedName`).
3. **Resolver público** de chamada → declaração, extraído da lógica hoje
   privada no `SemanticChecker` (`resolveViaProject`,
   `currentImportedByShortName`). O `SemanticChecker` passa a usá-lo, e o
   server também — o go-to-definition resolve exatamente como o
   compilador.
4. **Validação entre ficheiros (D3)** no `SemanticChecker`, usando os
   parâmetros agora presentes no `ProjectIndexEntry`:
   - as regras de slots que hoje só correm para chamadas no mesmo
     ficheiro passam a correr também para componentes de outros
     ficheiros, com os códigos existentes (`REQUIRED_SLOT_MISSING`,
     `SLOT_NOT_FOUND`, `CARDINALITY_VIOLATION`);
   - **regra nova** `PARAM_NOT_FOUND`: um argumento com nome
     (`nome = ...`) que não corresponde a nenhum parâmetro do componente
     chamado. Hoje isto não é verificado em lado nenhum (nem no mesmo
     ficheiro) e só falha quando o JTE compila o template; passa a ser
     erro no build, no mesmo ficheiro e entre ficheiros.
5. **Modo tolerante do `SukoAstBuilder`**, usado **só** pelo server.
   Apanha falhas por componente e por instrução e descarta apenas essa
   subárvore; o resto do ficheiro continua analisável. Sem variante
   `Error` nos sealed `Statement`/`Expr` (propagaria a todos os `switch`
   do `JteEmitter` e do `SemanticChecker`). Casos obrigatórios, vindos da
   sonda do architect:
   - `${t.` (acesso a membro a meio) — hoje lança
     `IllegalStateException` e perde o AST do ficheiro;
   - `component B( {` — hoje lança `NullPointerException` em
     `templateBlock()` (o `JteCompiler` só apanha `IllegalStateException`);
   - `A(` num corpo — hoje não dá erro de sintaxe: o `textRun` guloso
     engole-o como texto (por isso a completion **não** pode depender do
     AST);
   - `Card(ti` dentro de `<div>` — hoje já recupera; manter.
   Este é o **terceiro caminho de parse** (além dos dois sem error
   listener já documentados em `ARCHITECTURE.md`) e **nunca** é o caminho
   que reporta diagnósticos: os diagnósticos vêm sempre do caminho
   normal, idêntico ao do `sukoCompile`.

## Language server (`suko-lsp`)

**Descoberta do projeto.** Por pasta do workspace: `sourceRoot` do
`suko.json`; senão `src/main/suko` se existir; senão a setting
`suko.sourceRoot`. Um índice por source root. Workspaces multi-pasta
funcionam, sem resolução de nomes entre roots (a mesma limitação do
compilador — um source root, ver subprojeto 8).

**Sincronização.** `TextDocumentSyncKind.Full`. Documentos abertos
sobrepõem-se ao snapshot do disco via `SukoSources`; alterações a
ficheiros fechados chegam por `workspace/didChangeWatchedFiles`.

**Diagnósticos.** Debounce de 250 ms após a última alteração; reverifica o
root com a entrada só de verificação; publica por ficheiro e limpa os que
deixaram de existir. Códigos, mensagens e severidades são os do
`sukoCompile`. Conversão de posições por tabela de linhas: linhas 1-based
e colunas em code points (ANTLR) → posições LSP 0-based em unidades
UTF-16. Diagnósticos `PARSE_ERROR` com índices `-1` usam linha/coluna.

**Go-to-definition.** Nome de componente numa chamada ou num `import` →
`nameSpan` da declaração (mesmo ou outro ficheiro); nome de argumento
(`title = ...`) ou de slot (`header { ... }`) → o parâmetro na declaração.

**Hover.** Sobre um componente: assinatura com parâmetros (tipos e
defaults), slots e cardinalidade, visibilidade, package e ficheiro.

**Completion**, a partir do contexto de tokens do lexer (não do AST
parcial):
- depois de `import` → nomes qualificados dos componentes `public` do
  root;
- no corpo de um componente → componentes visíveis (mesmo ficheiro +
  importados) e keywords (`if`, `else`, `for`, `switch`, `case`,
  `default`); componentes `public` não importados também aparecem, e ao
  aceitar um é acrescentado o `import` (`additionalTextEdits`), porque o
  import é obrigatório mesmo no mesmo package;
- dentro de `Nome(...)` → parâmetros ainda não passados, como `nome = `;
- dentro de `Nome() { ... }` → slots com nome, como `header { }`.

**Robustez.** Cada pedido é isolado: uma exceção vira log + resposta
vazia, nunca a queda do server. Limite documentado: cada verificação
recompila o root inteiro.

## Extensão VSCode (`editors/vscode/`)

- **Linguagem `suko`** para `.sk`: `language-configuration.json` com
  comentários `//` e `/* */`, pares `{}` `()` `""` e auto-fecho.
- **Gramática TextMate:** `package`/`import`/`public component`,
  keywords de controlo, literais, tags e atributos HTML, `${...}` como
  região Java embutida (que o 11c enriquece), `$ident` dentro de
  strings, comentários.
- **Arranque:** `java -jar server/suko-lsp.jar` por stdio com
  `vscode-languageclient`. Java procurado por esta ordem: setting
  `suko.java.home` → `JAVA_HOME` → `java` no PATH; a versão é verificada
  (21+) antes de arrancar. Se faltar ou for antiga: mensagem com a versão
  encontrada e um botão que abre a setting.
- **Settings:** `suko.java.home`, `suko.sourceRoot`, `suko.trace.server`.
- **Comando** "Suko: Restart Language Server" e canal de output com os
  logs do server.
- **Build:** `npm run package` gera o `.vsix`; o jar vem de
  `./gradlew :suko-lsp:fatJar`. Novo workflow do GitHub Actions constrói
  jar + `.vsix` e publica o `.vsix` como artefacto do run. `engines.vscode`
  declara a versão mínima.
- **Cliente fino:** toda a lógica da linguagem vive no server Java, para o
  11b a reutilizar sem duplicação.

## Testes

**Core (JUnit):**
- Paridade `Path` vs. `SukoSources`: mesmos `.jte` e mesmos diagnósticos
  para os projetos de teste existentes e para `suko-components`.
- Spans novos confirmados pelo texto que cobrem.
- Resolver público: mesmo ficheiro, importado, inexistente.
- D3: um teste de erro por regra, no mesmo ficheiro e entre ficheiros
  (`REQUIRED_SLOT_MISSING`, `SLOT_NOT_FOUND`, `CARDINALITY_VIOLATION`,
  `PARAM_NOT_FOUND`); os 8 componentes do registry continuam a
  compilar sem erros — se algum falhar, é um erro real, corrigido e
  registado.
- Modo tolerante: os quatro casos acima produzem AST útil para o resto do
  ficheiro, sem exceção.

**Server (JUnit + cliente LSP4J em memória):**
- Abrir/editar publica e depois limpa os diagnósticos certos, com posições
  corretas em linhas com acentos e caracteres fora do BMP.
- Definition, hover e completion nos casos acima, incluindo auto-import.
- Um pedido que lança exceção devolve vazio e o server continua vivo.

**Extensão:** teste de fumo com `@vscode/test-electron` no CI — ativa,
o server responde, um `.sk` com erro produz um diagnóstico.

## Critérios de conclusão

- `./gradlew build` verde.
- O workflow gera o `.vsix` como artefacto.
- Verificação manual num VSCode real com o `.vsix` instalado, num projeto
  criado com `suko init`: highlighting, erros enquanto se escreve,
  go-to-definition, hover e completion com auto-import.
- `ARCHITECTURE.md` atualizado: módulos `suko-lsp` e `editors/vscode`,
  terceiro caminho de parse, validação entre ficheiros (e remoção da
  limitação correspondente), item 11a como CONCLUÍDO.

## Não-objetivos (11a)

- Java dentro de `${...}` (completion, hover, tipos) e ligar o
  `JavacTask` — 11c.
- IntelliJ — 11b.
- Mudanças à gramática (incluindo o `textRun` guloso).
- Compilação incremental.
- Rename, formatação, code actions, semantic tokens.
- Binário nativo do server.
- Publicação no Marketplace / Open VSX.

## Riscos

- **Tolerância a erros** é o risco principal: se o modo tolerante não
  cobrir um padrão comum de edição, a análise desse ficheiro degrada até
  ao último AST bom. Mitigado pelos casos de teste obrigatórios e pela
  completion por tokens, que não depende do AST.
- **D3 pode partir builds existentes** ao revelar erros reais entre
  ficheiros. Mitigado por correr a suite e o `suko-components` como
  guarda e por documentar a mudança.
- **Desempenho** da reverificação do root inteiro em projetos grandes —
  aceite para a release, documentado, com compilação incremental como
  melhoria futura.

## Execução

Plano com tarefas pequenas, via SDD, despachadas para os agentes
especialistas do projeto: `java-specialist` (core e server),
`dx-specialist` (mensagens, completion, extensão), `antlr4-specialist`
apenas se alguma tarefa tocar a gramática (não previsto), `architect` na
revisão final. Sem trailers de atribuição nos commits (`CLAUDE.md`).

## Próximos passos

1. Revisão desta spec pelo utilizador.
2. Plano de implementação (writing-plans).
3. Depois do 11a: scoping do 11b (IntelliJ) e do 11c (Java em `${...}`).
