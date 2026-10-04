# suko-api

O contrato das extensões de compile-time do Suko (subprojeto 13a). Só depende do
JDK e dos tipos de leitura do compilador que as assinaturas expõem (AST,
diagnósticos, `ProjectView`). Uma extensão corre **apenas no build e no LSP**,
nunca em runtime. `ExtensionApi.VERSION` é hoje `1`.

**A API v1 é provisória até à primeira release.** Qualquer mudança incompatível
(novo subtipo selado de `Statement`/`Expr`/`Param`, novo componente de record num
contexto ou em `ProjectIndexEntry`, mudança de assinatura) incrementa `VERSION`.

Ainda não há release nem extensões de terceiros publicadas; este documento
descreve o que existe no repositório.

## Os três pontos de extensão

Uma extensão implementa `SukoExtension` (`id()`, `apiVersion()`, `register(ExtensionContext)`)
e, em `register`, regista o que quiser:

| Ponto | Interface | Para quê |
|---|---|---|
| Alvo | `Target` | `id()` (o nome usado em `targets`), `componentType()` (o tipo Java de `Component` neste alvo — gancho do item 12, ainda não consumido), `vocabularies()` (ids dos vocabulários aceites) e `emit(ComponentDecl, EmitContext)` que devolve um `Emitted` (caminho relativo à pasta do `.sk`, texto e source map). |
| Vocabulário | `Vocabulary` | `id()`, `open()` (`true` aceita qualquer tag) e `tag(nome)` → `Optional<TagSpec>`. |
| Verificador | `Checker` | `id()` e `check(SukoFile, CheckContext)`; reporta com `ctx.report(severidade, código, mensagem, span)`. Corre depois do `SemanticChecker`, por ordem de id da extensão. |

O `jte` embutido (`suko-jte`) é implementado por esta mesma API.

## Registar por ServiceLoader

Ficheiro `META-INF/services/io.suko.ext.SukoExtension` no jar da extensão, com o
nome da classe por linha:

```
io.suko.testext.DemoExtension
```

## Exemplo mínimo: a `DemoExtension` (`suko-test-ext`)

Um alvo `demo` que emite `demo:<Nome>` num ficheiro `<Nome>.demo`, um vocabulário
fechado `demo` (`box`, `label`) e um checker que avisa `DEMO_CHECK` por componente:

```java
public final class DemoExtension implements SukoExtension {
    public String id() { return "io.suko.testext"; }
    public int apiVersion() { return ExtensionApi.VERSION; }

    public void register(ExtensionContext ctx) {
        ctx.target(new Target() {
            public String id() { return "demo"; }
            public String componentType() { return "demo.Node"; }
            public Set<String> vocabularies() { return Set.of("demo"); }
            public Emitted emit(ComponentDecl c, EmitContext e) {
                return new Emitted(c.name() + ".demo", "demo:" + c.name() + "\n", List.of());
            }
        });
        ctx.vocabulary(new Vocabulary() {
            public String id() { return "demo"; }
            public boolean open() { return false; }
            public Optional<TagSpec> tag(String name) {
                return Set.of("box", "label").contains(name)
                    ? Optional.of(new TagSpec(name, Map.of(), true)) : Optional.empty();
            }
        });
        ctx.checker(new Checker() {
            public String id() { return "demo-check"; }
            public void check(SukoFile file, CheckContext ctx) {
                for (ComponentDecl c : file.components()) {
                    ctx.report(SukoDiagnostic.Severity.WARNING, "DEMO_CHECK", "visto: " + c.name(), c.span());
                }
            }
        });
    }
}
```

(Versão completa em `suko-test-ext/src/main/java/io/suko/testext/DemoExtension.java`;
a original também tem um ramo que lança de propósito, para os testes de robustez.)

## Regras de versão, conflito e falha

- **Versão:** o core só aceita extensões com `apiVersion() == ExtensionApi.VERSION`;
  outra versão major dá `EXTENSION_API_MISMATCH` e a extensão não é registada.
- **Conflito:** dois alvos (ou dois vocabulários) com o mesmo id dão
  `EXTENSION_CONFLICT`; fica o da extensão com id menor (as extensões são
  ordenadas por id). A mesma extensão vista duas vezes no classpath conta uma vez.
- **Alvos e vocabulários:** um alvo pedido que ninguém fornece dá `TARGET_NOT_FOUND`;
  um alvo que declara um vocabulário não registado dá `VOCABULARY_NOT_FOUND`; uma
  tag fora dos vocabulários (todos fechados) de um alvo dá `UNKNOWN_TAG`.
- **Falha:** uma extensão que lance exceção (em `id`/`apiVersion`/`register`, num
  checker, em `emit`, em `vocabularies`/`open`/`tag`), falhe a carregar ou dê
  `LinkageError`/`Error` vira um diagnóstico `EXTENSION_FAILED` com o id da
  extensão (e o ficheiro, quando há); o compilador continua e o LSP não cai. Exceção:
  `VirtualMachineError` que não seja `StackOverflowError` (ex.: `OutOfMemoryError`) é
  relançado. Extensões não estão em sandbox.

## Como usar

Declarar a extensão no projeto (`sukoExtensions` no Gradle, `<dependencies>` do plugin
no Maven) e escolher os alvos — ver a secção "Extensions" do `README.md` da raiz. O
LSP só carrega extensões em workspaces confiáveis.

## Limitações conhecidas e checklist para o item 12

- `TagSpec.attributeTypes` e `allowsChildren` ainda não são aplicados pelo core.
- `Target.emit` devolve um único `Emitted`; jte+js/html+js e o item 12 precisam de
  várias saídas por componente.
- `EmitContext` não tem resolvedor de chamadas (hoje só `importedByShortName` e
  `packagePrefix`); `CheckContext` não expõe os alvos ativos.
- Os contextos e `ProjectIndexEntry` são records; considerar interfaces antes do item 12.
