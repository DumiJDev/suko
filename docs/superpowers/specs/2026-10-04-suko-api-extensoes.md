# Suko — Subprojeto 13a: API de extensões (fase 0 do item 13)

Data: 2026-10-04
Estado: **aprovada pelo utilizador a 2026-10-04**. **Ordem revista no mesmo dia (D1, abaixo):** o 13a é o primeiro trabalho a implementar.

## Contexto

O item 13 do roadmap (`ARCHITECTURE.md`) regista a direção decidida pelo
utilizador a 2026-10-03, a partir da issue #7: em vez de crescer o core,
os alvos de compilação e os vocabulários de tags entram como
**extensões que correm só em compile-time** (build e LSP), sobre uma API
pequena e versionada. A arrumação decidida é `suko-api` + `suko-core` +
**um artefacto de compile-time por alvo** (`suko-jte`, `suko-html`,
`suko-javafx`, `suko-tamboui`, variantes `+js`), com vocabulário
**neutro + nativo** e a reatividade resolvida em compile-time.

Este documento é a **fase 0**: criar a API, o carregador e passar o
alvo JTE para trás dela, **sem mudar nenhum `.jte` gerado**. Nenhum alvo
novo entra aqui; a primeira extensão real (TamboUI) é a fase 1.

**Ordem de execução:** o 13a é o **primeiro** trabalho a implementar
(D1, revista a 2026-10-04): mudanças de linguagem e compilador antes do
suporte de editores.

### Interoperabilidade Java (item 12), clarificada pelo utilizador

A 2026-10-03/04 o utilizador definiu o item 12: **"eu crio uma classe
Java que implementa `Component` e importo no `.sk` sem problemas
nenhuns"**; e, em qualquer alvo, cada componente Suko expõe uma API Java
que devolve o `Component` desse alvo, **e vice-versa — o compilador trata
disso** (lógica pesada em componentes Java, UI em Suko com reatividade
em "ilhas"). "java" não é um alvo de render: é
uma camada transversal, no core. A restrição que isto impõe à fase 0:
**cada alvo declara qual é o seu tipo `Component`** (no JTE,
`gg.jte.Content`), para o item 12 entrar depois sem refazer a API.

### Achado que corrige a issue #7

A issue #7 pressupõe que o `SemanticChecker` tem regras HTML (estrutura,
escape, URLs perigosas) a mover para o alvo JTE. **Não tem nenhuma**
(verificado 2026-10-04): aceita qualquer tag, e o escape é feito em
runtime pelo próprio JTE (`ContentType.Html`). Por isso, na fase 0, o
vocabulário HTML do alvo JTE é **aberto** (aceita qualquer tag) — é o
que mantém os diagnósticos e o output idênticos.

## Decisões

- **D1 — Ordem (revista pelo utilizador a 2026-10-04):** as mudanças de
  linguagem/compilador vêm **antes** do suporte de editores, para evitar
  refazer os editores: **13a → item 12 (interop Java) → metade de
  compilador do 11c** (ligar o `JavacTask` e verificar os tipos Java em
  `${...}` no build); só depois os seguimentos do 11a, o 11b e a parte de
  editor do 11c. (Decisão inicial, substituída: implementar depois do
  11b/11c.)
- **D2 — Declaração:** as extensões vêm do **classpath do build**, com
  descoberta por `ServiceLoader`. Resolver coordenadas Maven pela CLI
  fica para depois.
- **D3 — Conteúdo do `suko-api`:** os records do AST e dos diagnósticos
  **mudam de módulo mantendo os packages** (nenhum `import` existente
  parte); interfaces de extensão novas; o índice exposto por uma
  interface só de leitura. Rejeitadas: uma vista espelho do AST com
  adaptadores (duplica o AST inteiro) e as interfaces num package do
  `suko-core` (uma extensão dependeria do compilador inteiro).
- **D4 — Lista de extensões para o LSP no diretório de build**, não no
  `suko.json`: o `suko.json` é do utilizador e vai para o git, e não deve
  guardar caminhos absolutos da máquina. (Ajuste à opção "lista no
  `suko.json`" escolhida no scoping; a intenção — o LSP lê o que o build
  resolveu — mantém-se.)

## Módulos

- **`suko-api`** (novo, só JDK, `release 21`):
  - `io.suko.lang.ast` inteiro (movido do core; só depende de
    `java.util`);
  - `SukoDiagnostic`, `DiagnosticCollector` (movidos; o
    `SukoErrorListener` depende de ANTLR e **fica** no core);
  - `ProjectIndexEntry`, `ParamInfo` (records, movidos) e uma interface
    nova `ProjectView` só de leitura (lookup por nome qualificado,
    componentes `public`, entradas por package);
  - o package novo `io.suko.ext` (secção "API").
- **`suko-core`** passa a depender do `suko-api`. Fica com gramática,
  parser, `SukoAstBuilder`, `TolerantParser`, `SemanticChecker`,
  `CallResolver`, `ProjectIndex` (implementa `ProjectView`),
  `SukoProjectCompiler`/`SukoSources`, e o **carregador de extensões**
  (novo). **Deixa de conhecer o JTE.**
- **`suko-jte`** (novo): o alvo JTE como extensão — o `JteEmitter`
  movido do core e uma `JteExtension` registada por `ServiceLoader`. Os
  plugins Gradle/Maven, o gerador do site e o LSP trazem-no por
  omissão; para o utilizador nada muda.
- **`JavacTask`:** hoje não é chamado por nenhum código de produção. O
  13a corre antes da verificação de tipos Java, por isso **não lhe
  toca**: fica onde está. A regra para quando essa verificação for
  ligada: o que for independente do alvo (análise Java das expressões)
  fica no core; o que for específico do JTE (stubs `.jte`) vai para o
  `suko-jte`.
- **Sem mudanças:** `suko-cli` e `suko-registry-generator` não compilam
  para nenhum alvo (só parse e índice).

## API (`io.suko.ext`)

```java
public interface SukoExtension {
    String id();                         // "io.suko.jte"
    int apiVersion();                    // major da API contra a qual foi compilada
    void register(ExtensionContext ctx); // ctx.target(..), ctx.vocabulary(..), ctx.checker(..)
}

public interface Target {
    String id();                         // "jte"
    String componentType();              // "gg.jte.Content": o tipo Component deste alvo
    Set<String> vocabularies();          // vocabulários aceites, ex.: {"html"}
    Emitted emit(ComponentDecl component, EmitContext ctx);
}
public record Emitted(String relativePath, String source, List<SourceMapEntry> sourceMap) {}
// EmitContext: o SukoFile, o ProjectView e a resolução de chamadas
// (a mesma do compilador, via CallResolver).

public interface Vocabulary {
    String id();                         // "html"
    boolean open();                      // aceita qualquer tag (HTML na fase 0)
    Optional<TagSpec> tag(String name);  // tag conhecida: atributos e tipos
}
public record TagSpec(String name, Map<String, String> attributeTypes, boolean allowsChildren) {}
// attributeTypes: nome do atributo → tipo Java esperado (ex.: "spacing" → "int").
// Na fase 0 nenhum vocabulário fechado é usado em produção (só a test-ext).

public interface Checker {
    String id();
    void check(SukoFile file, CheckContext ctx); // ctx.report(severity, code, mensagem, span), ctx.project()
}
```

- **Só três pontos de extensão** (`Target`, `Vocabulary`, `Checker`).
  Namespaces de atributos, comandos da CLI, origens de registry e
  contribuições ao LSP ficam para quando uma segunda extensão precisar
  deles — cada ponto exposto é uma promessa de compatibilidade.
- **`componentType()`** é o gancho do item 12: o verificador saberá que
  tipo uma classe Java importada tem de implementar.
- **Versão:** o core aceita extensões com o mesmo `apiVersion` major.
- **Multi-alvo desde a forma:** a configuração aceita uma lista
  `targets` (por omissão `["jte"]`). Com um só alvo, o output fica
  exatamente onde está hoje (igualdade byte a byte, incluindo caminhos);
  com vários, cada alvo escreve em `<out>/<targetId>/`.

## Carregador, verificação e erros

`ExtensionLoader` (core): recebe um `ClassLoader`, descobre extensões por
`ServiceLoader`, regista-as por ordem estável de `id` e monta um registo
(alvos e vocabulários por id, checkers ordenados por id — diagnósticos
estáveis entre execuções).

Ordem da verificação: o `SemanticChecker` corre como hoje; depois correm
os `Checker` das extensões; com o vocabulário `html` aberto, nenhuma tag
é recusada — **a fase 0 não muda nenhum diagnóstico existente**.

Erros, sempre como diagnósticos (nunca exceções soltas):

| Código | Quando |
|---|---|
| `EXTENSION_CONFLICT` | duas extensões reclamam o mesmo id de alvo ou de vocabulário (nomeia as duas) |
| `EXTENSION_API_MISMATCH` | `apiVersion` major diferente (nomeia a extensão e as duas versões) |
| `TARGET_NOT_FOUND` | um alvo pedido em `targets` não é fornecido por nenhuma extensão (lista os disponíveis) |
| `EXTENSION_FAILED` | um `emit` ou `check` lança exceção (id da extensão + ficheiro); compilador e LSP continuam vivos |

## Pontos de entrada

- **Plugin Gradle:** configuração nova `sukoExtensions`; `sukoCompile` e
  `sukoWatch` carregam-na num classloader próprio; o `suko-jte` vem
  sempre incluído pelo plugin; propriedade `targets` em `suko { }`.
  Uma tarefa escreve `build/suko/extensions.json` (classpath das
  extensões + alvos) para o LSP.
- **Plugin Maven:** extensões como `<dependencies>` do próprio plugin
  (o mecanismo nativo do Maven); parâmetro `targets`; escreve
  `target/suko/extensions.json`.
- **LSP:** lê `build/suko/extensions.json` / `target/suko/extensions.json`
  e **só carrega extensões num workspace confiável**. Sem ficheiro, ou
  num workspace não confiável: só o JTE embutido, com um aviso uma vez.
  Os `Checker` das extensões entram nos diagnósticos do editor.
- **Gerador do site:** alvo `jte` por omissão, sem mudanças.

## Testes

- **Golden byte a byte** (a prova central). O primeiro passo do plano,
  antes de mover código, grava os `.jte` e os source maps que o
  compilador atual gera para `examples/`, `suko-components`, as páginas
  do `suko-website` e os fixtures de teste do core. Depois da
  refatoração, um teste recompila tudo pelo caminho novo (carregador +
  `suko-jte`) e compara byte a byte. Se o JTE não couber na API sem
  mudar o output, quem está errada é a API, não o golden.
- **Extensão de teste `test-ext`** (só em testes): um alvo `demo` que
  emite um texto trivial, um vocabulário `demo` fechado com duas tags e
  um `Checker` que emite um aviso com código próprio. Corre:
  - **Gradle:** TestKit com `sukoExtensions` e `targets = ["jte",
    "demo"]`; verifica o output em `<out>/jte/` e `<out>/demo/`.
  - **Maven:** teste ao nível da Mojo (como o `SukoCompileMojoProjectTest`
    existente), com um classloader do plugin que inclui a `test-ext`. A
    verificação com `mvn` real é manual, como no subprojeto 4.
  - **LSP:** no harness LSP4J em memória do `suko-lsp`, com um
    `extensions.json` e workspace confiável — o aviso do `Checker`
    aparece como diagnóstico; num workspace não confiável não aparece.
- **Erros:** um teste por código da tabela; no `EXTENSION_FAILED`, o
  compilador e o LSP sobrevivem.
- **Sem regressões:** a suite atual verde sem alterar asserções; só
  mudam dependências entre módulos.

## Critérios de conclusão

- `./gradlew build` verde, golden igual byte a byte.
- A `test-ext` funciona no Gradle, na Mojo Maven e no LSP.
- Um projeto sem extensões declaradas compila exatamente como hoje, sem
  dependências novas visíveis para o utilizador.
- `ARCHITECTURE.md` atualizado: módulos `suko-api`/`suko-jte`, a API, o
  13a como CONCLUÍDO, e o item 12 com a definição do utilizador (classe
  Java que implementa `Component`, importável no `.sk`).

## Não-objetivos (13a)

- O alvo TamboUI e as primitivas neutras de vocabulário (fase 1).
- `state`, `derived`, lambdas, alvos `+js`.
- Namespaces de atributos, comandos da CLI, origens de registry,
  contribuições ao LSP como pontos de extensão.
- Resolver coordenadas Maven pela CLI.
- O gerador do site como extensão (`static-html`).
- A interoperabilidade Java em si (item 12) — só o gancho
  `componentType()`.

## Riscos

- **O AST passa a ser contrato público.** Mitigado pelo `apiVersion` e
  por só expor três pontos de extensão.
- **Código de terceiros no build e no LSP.** Mitigado por carregar no
  LSP só em workspaces confiáveis, por isolar falhas como
  `EXTENSION_FAILED` e por revisão do `security-specialist`.
- **Os editores vêm depois:** o `suko-lsp` (11a) passa a usar o
  carregador e o `suko-jte`; o 13a ajusta-o o mínimo para continuar a
  funcionar (com os testes atuais como guarda), e o resto fica para a
  fase dos editores.

## Execução

Via SDD com os agentes especialistas do projeto: `java-specialist`
(mudança de módulos, API, carregador, pontos de entrada), `jte-specialist`
(garantir que o `suko-jte` mantém o comportamento do JTE),
`security-specialist` (carregamento no LSP), `architect` (revisão
final). Sem trailers de atribuição nos commits (`CLAUDE.md`).

## Próximos passos

1. Revisão desta spec pelo utilizador.
2. Plano de implementação (writing-plans) — a escrever agora (D1
   revista).
