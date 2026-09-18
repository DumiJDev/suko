# Suko — Subprojeto 6: Modelo de Componente — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Substituir `slot<T>` por `Component` em toda a superfície do Suko — cardinalidade via `List<Component>`, opcional via `= valor`, render-props via `Function<T, Component>` explícito (remove a heurística de scan do corpo) — e adicionar children implícitos (`children` como nome reservado), chamada de componente como valor, interpolação real `${expr}`/`$ident` em strings/atributos, e auto-`toString` de valores Java arbitrários.

**Architecture:** Mudanças concentradas em `SukoAstBuilder` (reconhecimento de tipo), `JteEmitter` (emissão), `SemanticChecker` (diagnósticos) e `SukoLexer.g4`/`SukoParser.g4` (só a correção do `popMode` da interpolação — o resto reusa gramática já existente). Nenhuma mudança de runtime: tudo continua a compilar para `gg.jte.Content` puro.

**Tech Stack:** Java 21, ANTLR4 4.13.1, gg.jte / jte-runtime 3.1.12, JUnit 5, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-18-suko-modelo-componente.md`

## Global Constraints

- Depois de qualquer alteração a `SukoLexer.g4`/`SukoParser.g4`, correr `gradle generateSukoLexer generateSukoParser --console=plain` e confirmar que a saída não contém a palavra `warning` — convenção obrigatória do projeto.
- Nenhuma forma nova de Java gerado avança para código de produção sem confirmação empírica prévia contra o `gg.jte`/`jte-runtime` 3.1.12 real (sonda de probe, não suposição) — convenção obrigatória do projeto, ver `ARCHITECTURE.md`.
- Testes de render usam `JteRenderSupport.render`/`renderWithDependencies` (motor `gg.jte` real, não simulado) — não validar só compilação do `.jte`.
- Qualquer desvio descoberto por sonda empírica é documentado no código, no ponto exato da descoberta, com o texto exato do erro/comportamento observado — convenção obrigatória do projeto.
- Substituição completa de `slot<T>` por `Component`, sem forma dupla — o projeto não tem consumidores externos ainda.

---

## Descoberta feita ao escrever este plano (desvio da spec, documentado aqui)

A spec (`docs/superpowers/specs/2026-09-18-suko-modelo-componente.md`, secção "Componente como valor") propunha uma nova gramática (`componentCall` sem `slotBlock` dentro de `primary`/`expression`) e um novo nó de AST `Expr.ComponentCallExpr`. **Isto não é preciso.** A gramática já existente (`SukoParser.g4:206`, `expression LPAREN argList? RPAREN # CallExpr`) já permite `CardA()` dentro de qualquer posição de expressão — `SukoAstBuilder.buildExpr` já constrói isto como `Expr.CallExpr(callee=PrimaryExpr("CardA"), args=[])` (confirmado lendo `SukoAstBuilder.java:282-283`). O único trabalho realmente novo é o `JteEmitter` reconhecer, dentro de `emitExpr`, quando o `callee` de um `CallExpr` é um nome de componente conhecido (não um slot, não uma chamada Java arbitrária) e embrulhar em bloco de conteúdo — ver Tarefa 5. Isto elimina a Tarefa de gramática que a spec previa; documentado aqui e a corrigir na spec depois de mergeado (última tarefa deste plano).

---

### Task 1: Sonda — bloco de conteúdo JTE dentro de expressão condicional Java

**Files:**
- Create: `src/test/java/io/suko/lang/ComponentValueProbeTest.java`

**Interfaces:**
- Produces: confirmação (ou refutação, com forma alternativa) de que `!{var c = cond ? @`@template.X()`` : @`@template.Y()``;}${c}` compila e renderiza sob `gg.jte` 3.1.12 — a Tarefa 5 depende deste resultado.

- [ ] **Step 1: Escrever a sonda (sem passar pelo compilador Suko — `.jte` escrito à mão, como todo probe deste projeto)**

```java
package io.suko.lang;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sonda empírica (subprojeto 6, tarefa 1): confirma se um bloco de
 * conteúdo JTE (`@`...``) é válido dentro de uma expressão Java
 * condicional gerada — o mecanismo de que "componente como valor"
 * (tarefa 5) depende. Resultado documentado aqui após correr.
 */
class ComponentValueProbeTest {

    @Test
    void contentBlockInsideTernaryCompilesAndRenders() throws Exception {
        Path tempDir = Files.createTempDirectory("suko-probe-component-value");

        Files.writeString(tempDir.resolve("CardA.jte"), "A\n");
        Files.writeString(tempDir.resolve("CardB.jte"), "B\n");
        Files.writeString(tempDir.resolve("Host.jte"), """
            @param boolean useA

            !{var c = useA ? @`@template.CardA()` : @`@template.CardB()`;}
            ${c}
            """);

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render("Host.jte", Map.of("useA", true), output);

        assertTrue(output.toString().contains("A"), "esperava 'A' na saída, obteve: " + output);
    }
}
```

- [ ] **Step 2: Correr a sonda**

Run: `gradle test --tests "io.suko.lang.ComponentValueProbeTest" --console=plain`

Se **PASSAR**: documentar no topo da classe o resultado confirmado — a Tarefa 5 usa exatamente esta forma (`@`@template.X(args)``) para emitir `CallExpr` de componente em posição de valor.

Se **FALHAR**: ler o erro de compilação Java real (não adivinhar), tentar a alternativa registada na spec (variável de conteúdo local por-ocorrência, `var __t0 = useA ? @`@template.CardA()`` : @`@template.CardB()``;`), documentar qual forma funcionou e porquê no comentário da classe, e ajustar a Tarefa 5 para usar a forma confirmada.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/io/suko/lang/ComponentValueProbeTest.java
git commit -m "test: sonda — bloco de conteúdo JTE em expressão condicional (subprojeto 6, tarefa 1)"
```

---

### Task 2: `Component` substitui `slot<T>` — reconhecimento no AST builder, render-prop estrutural

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Param.java`
- Modify: `src/main/java/io/suko/lang/ast/Type.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java:62-88` (`buildParam`, `slotElementType`)
- Modify: `src/main/java/io/suko/lang/JteEmitter.java` (`jteParamDeclaration`, `resolveSlotIsRenderProp`, remove `renderPropSlotNames`/`collectRenderPropSlots`, atualizar `emit`/`emitWithSourceMap`)
- Test: `src/test/java/io/suko/lang/SukoAstBuilderTest.java` (criar se não existir um ficheiro de teste unitário do builder — confirmar primeiro; se não existir, criar)
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: nada de tarefas anteriores.
- Produces: `Param.SlotParam` ganha um campo `boolean renderProp()` — tarefas seguintes (3, 4, 6) leem `param.renderProp()` diretamente, nunca voltam a fazer scan do corpo.

- [ ] **Step 1: Escrever o teste de render que falha (RED) — `Component`/`List<Component>`/`Function<T, Component>` substituem `slot<T>`**

```java
// em JteEmitterTest.java

@Test
void rendersComponentSlotAsPlainContent() throws Exception {
    String source = """
        component Card(Component header) {
          <div>{header}</div>
        }
        """;
    // "header" não é mais Function<T,Content> por omissão — é Content nu,
    // porque a declaração agora diz explicitamente "Component", não
    // "slot<T>" invocado como função em lado nenhum do corpo.
    String html = JteRenderSupport.renderWithDependencies(
        """
        component Card(Component header) {
          <div>{header}</div>
        }
        component Host() {
          Card() { "Título" }
        }
        """, "Host", java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
}

@Test
void rendersRenderPropSlotViaExplicitFunctionType() throws Exception {
    String source = """
        component Row(Function<String, Component> label, java.util.List<String> items) {
        }
        """;
    // Assinatura explícita: Function<T, Component> é render-prop mesmo que
    // o corpo NUNCA chame "label(...)" — ao contrário da heurística antiga.
    String html = JteRenderSupport.renderWithDependencies(
        """
        component Row(Function<String, Component> label) {
          <li>{label("x")}</li>
        }
        component Host() {
          Row() { it -> "valor: {it}" }
        }
        """, "Host", java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("valor: x"));
}
```

- [ ] **Step 2: Correr os testes e confirmar que falham**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: FAIL — `Component`/`Function<T, Component>` ainda não é reconhecido por `buildParam` (hoje só `slot<T>`/`List<slot<T>>` são).

- [ ] **Step 3: `Param.SlotParam` ganha `renderProp` estrutural**

`src/main/java/io/suko/lang/ast/Param.java`:

```java
package io.suko.lang.ast;

import java.util.Optional;

public sealed interface Param permits Param.ValueParam, Param.SlotParam {

    String name();

    record ValueParam(Type type, String name, Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }

    record SlotParam(Type elementType, String name, Cardinality cardinality, boolean renderProp,
                      Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }
}
```

- [ ] **Step 4: Remover `Type.isSlot()` (morto após esta tarefa — `Component` substitui `slot`, sem forma dupla)**

`src/main/java/io/suko/lang/ast/Type.java`:

```java
package io.suko.lang.ast;

import java.util.List;

public record Type(String name, List<Type> typeArguments, int arrayDimensions) {
}
```

- [ ] **Step 5: Reescrever `buildParam` em `SukoAstBuilder.java` (substitui linhas 62-88)**

```java
private static final Type CONTENT_ELEMENT_TYPE = new Type("Object", List.of(), 0);

private Param buildParam(SukoParser.ParamContext ctx) {
    Type type = buildType(ctx.type());
    String name = ctx.Identifier().getText();
    Optional<Expr> defaultValue = ctx.expression() == null
        ? Optional.empty()
        : Optional.of(buildExpr(ctx.expression()));

    return tryBuildSlotParam(type, name, defaultValue, ctx)
        .map(Param.class::cast)
        .orElseGet(() -> new Param.ValueParam(type, name, defaultValue, spanOf(ctx)));
}

private Optional<Param.SlotParam> tryBuildSlotParam(Type type, String name, Optional<Expr> defaultValue,
        SukoParser.ParamContext ctx) {
    if (isComponent(type)) {
        return Optional.of(new Param.SlotParam(CONTENT_ELEMENT_TYPE, name, Cardinality.ONE, false, defaultValue, spanOf(ctx)));
    }
    if (isRenderProp(type)) {
        return Optional.of(new Param.SlotParam(type.typeArguments().get(0), name, Cardinality.ONE, true, defaultValue, spanOf(ctx)));
    }
    if ("List".equals(type.name()) && type.typeArguments().size() == 1) {
        Type inner = type.typeArguments().get(0);
        if (isComponent(inner)) {
            return Optional.of(new Param.SlotParam(CONTENT_ELEMENT_TYPE, name, Cardinality.MANY, false, defaultValue, spanOf(ctx)));
        }
        if (isRenderProp(inner)) {
            return Optional.of(new Param.SlotParam(inner.typeArguments().get(0), name, Cardinality.MANY, true, defaultValue, spanOf(ctx)));
        }
    }
    return Optional.empty();
}

private boolean isComponent(Type type) {
    return "Component".equals(type.name()) && type.typeArguments().isEmpty();
}

private boolean isRenderProp(Type type) {
    return "Function".equals(type.name())
        && type.typeArguments().size() == 2
        && isComponent(type.typeArguments().get(1));
}
```

Remover o método `slotElementType` antigo (linhas 84-88) — substituído por `CONTENT_ELEMENT_TYPE`/`tryBuildSlotParam` acima.

- [ ] **Step 6: `JteEmitter` — remover a heurística de scan, ler `renderProp()` direto do `Param.SlotParam`**

Remover por completo os métodos `renderPropSlotNames(ComponentDecl, Set<String>)` e as duas sobrecargas de `collectRenderPropSlots` (`Statement`/`Expr`) — `JteEmitter.java:138-238`.

Atualizar `emit`/`emitWithSourceMap` (remover a variável local `renderPropSlotNames` e o argumento correspondente):

```java
public String emit(ComponentDecl component) {
    java.util.Set<String> slotNames = slotNamesOf(component);

    StringBuilder out = new StringBuilder();
    for (Param param : component.params()) {
        out.append("@param ").append(jteParamDeclaration(param, slotNames)).append('\n');
    }
    out.append('\n');
    for (Statement statement : component.body()) {
        emitStatement(statement, out, slotNames);
    }
    return out.toString();
}
```

(Aplicar a mesma remoção do argumento em `emitWithSourceMap`.)

Reescrever `jteParamDeclaration`:

```java
private String jteParamDeclaration(Param param, java.util.Set<String> slotNames) {
    return switch (param) {
        case Param.ValueParam p -> javaType(p.type()) + " " + p.name()
            + p.defaultValue().map(expr -> " = " + emitExpr(expr, slotNames)).orElse("");
        case Param.SlotParam p when p.cardinality() == Cardinality.ONE && p.renderProp() ->
            "java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content> " + p.name()
                + (p.defaultValue().isPresent() ? " = (java.util.function.Function<" + javaType(p.elementType())
                    + ", gg.jte.Content>) (" + javaType(p.elementType()) + " it) -> null" : "");
        case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
            "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
        case Param.SlotParam p when p.renderProp() ->
            "java.util.List<java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content>> " + p.name()
                + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
        case Param.SlotParam p ->
            "java.util.List<gg.jte.Content> " + p.name()
                + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
    };
}
```

Reescrever `resolveSlotIsRenderProp`:

```java
private Boolean resolveSlotIsRenderProp(String componentName, String paramName) {
    ComponentDecl target = componentsByName.get(componentName);
    if (target == null) {
        return null;
    }
    for (Param param : target.params()) {
        if (param.name().equals(paramName) && param instanceof Param.SlotParam slotParam) {
            return slotParam.renderProp();
        }
    }
    return null;
}
```

**Limitação aceite, documentada aqui (não corrigida nesta tarefa):** `emitExpr` continua a traduzir QUALQUER chamada `nome(args)` sobre um identificador que coincida com um nome de slot para `.apply(...)` (`JteEmitter.java:575-576`), independentemente de `renderProp()` ser `true`. Chamar um slot `Component` simples (não render-prop) como função (`{header(x)}`) produz `header.apply(x)`, que não compila (`Content` não tem `.apply`) — isto já não é mais indetetável hoje (a informação `renderProp()` existe na declaração), mas o `SemanticChecker` desta tarefa ainda não valida isto. Fica registado como gap para uma ronda futura de diagnósticos, mesmo padrão de outras lacunas já aceites no projeto.

- [ ] **Step 7: Correr os testes e confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 8: Migrar os testes existentes que ainda usam `slot<T>`**

Run: `grep -rln "slot<" src/test/java` — para cada ocorrência, substituir pela forma `Component`/`List<Component>`/`Function<T, Component>` equivalente (mapeamento 1:1 descrito na spec) e confirmar que o teste continua a passar.

- [ ] **Step 9: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add -A
git commit -m "feat(lang): Component substitui slot<T>; render-prop explícito via Function<T,Component>"
```

---

### Task 3: Children implícitos — síntese de `SlotFill("children", ...)` a partir de conteúdo solto

**Files:**
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java:155-183` (`buildComponentCallStmt`)
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `Param.SlotParam` com `renderProp()` da Tarefa 2.
- Produces: qualquer `ComponentCallStmt` cujo `slotBlock` tenha `templateStatement`s soltos (fora de `namedSlot`) ganha automaticamente um `Statement.SlotFill("children", Optional.empty(), stmts)` — a Tarefa 4 (diagnósticos) e a Tarefa 9 (migração de examples) dependem desta forma.

- [ ] **Step 1: Confirmar a forma exata do acessor ANTLR antes de escrever código (lição do projeto: nunca assumir, verificar contra o parser gerado)**

Run: `gradle generateSukoParser --console=plain && grep -A5 "class SlotBlockContext" build/generated-src/antlr/main/io/suko/lang/SukoParser.java`

Confirmar que `SlotBlockContext` expõe `List<TemplateStatementContext> templateStatement()` e `List<NamedSlotContext> namedSlot()` como dois acessores independentes (padrão já confirmado noutras regras do projeto, ex. `templateBlock().templateStatement()`).

- [ ] **Step 2: Escrever o teste que falha (RED)**

```java
// em JteEmitterTest.java

@Test
void syntheticChildrenFillFromLooseContent() throws Exception {
    String html = JteRenderSupport.renderWithDependencies(
        """
        component Field(Component children) {
          <div>{children}</div>
        }
        component Host() {
          Field() {
            "Nome: "
            <input/>
          }
        }
        """, "Host", java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("Nome: "));
    org.junit.jupiter.api.Assertions.assertTrue(html.contains("<input/>"));
}

@Test
void looseContentAndExplicitNamedSlotCoexist() throws Exception {
    String html = JteRenderSupport.renderWithDependencies(
        """
        component Layout(Component children, Component header) {
          <header>{header}</header>
          <main>{children}</main>
        }
        component Host() {
          Layout() {
            "corpo solto"
            header { "Título" }
          }
        }
        """, "Host", java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("corpo solto"));
    org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
}
```

- [ ] **Step 3: Correr os testes, confirmar FAIL**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: FAIL — hoje o conteúdo solto é descartado em silêncio (`buildComponentCallStmt` só lê `ctx.slotBlock().namedSlot()`).

- [ ] **Step 4: Implementar em `buildComponentCallStmt`**

```java
private Statement.ComponentCallStmt buildComponentCallStmt(SukoParser.ComponentCallContext ctx) {
    List<Statement.Arg> args = new ArrayList<>();
    if (ctx.argList() != null) {
        for (SukoParser.ArgContext argCtx : ctx.argList().arg()) {
            Optional<String> name = argCtx.Identifier() == null
                ? Optional.empty()
                : Optional.of(argCtx.Identifier().getText());
            args.add(new Statement.Arg(name, buildExpr(argCtx.expression())));
        }
    }
    List<Statement.SlotFill> slotFills = new ArrayList<>();
    if (ctx.slotBlock() != null) {
        for (SukoParser.NamedSlotContext slotCtx : ctx.slotBlock().namedSlot()) {
            String paramName = slotCtx.Identifier(0).getText();
            Optional<String> lambdaParamName = slotCtx.Identifier().size() > 1
                ? Optional.of(slotCtx.Identifier(1).getText())
                : Optional.empty();
            List<Statement> body = buildStatements(slotCtx.templateStatement());
            slotFills.add(new Statement.SlotFill(paramName, lambdaParamName, body));
        }

        // Children implícitos (subprojeto 6): templateStatement soltos direto
        // dentro do slotBlock (fora de qualquer namedSlot) sintetizam um
        // SlotFill("children", ...) — o autor da chamada nunca escreve
        // "children { ... }" explicitamente. Ordem preservada (ANTLR devolve
        // ctx.slotBlock().templateStatement() na ordem de aparição no fonte).
        List<SukoParser.TemplateStatementContext> looseStatements = ctx.slotBlock().templateStatement();
        if (!looseStatements.isEmpty()) {
            slotFills.add(new Statement.SlotFill("children", Optional.empty(), buildStatements(looseStatements)));
        }
    }

    return new Statement.ComponentCallStmt(ctx.qualifiedName().getText(), args, slotFills, spanOf(ctx));
}
```

- [ ] **Step 5: Correr os testes, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/io/suko/lang/SukoAstBuilder.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(ast): children implícitos — conteúdo solto sintetiza SlotFill(children, ...)"
```

---

### Task 4: Diagnósticos — `children` como nome reservado

**Files:**
- Modify: `src/main/java/io/suko/lang/semantic/SemanticChecker.java:57-67` (`checkComponent`)
- Test: `src/test/java/io/suko/lang/semantic/SemanticCheckerTest.java` (confirmar nome exato do ficheiro de teste existente antes de editar — usar `find src/test -iname "*SemanticChecker*"`)

**Interfaces:**
- Consumes: `Statement.SlotFill("children", ...)` sintetizado pela Tarefa 3; `Param.SlotParam` da Tarefa 2.
- Produces: nada consumido por tarefas seguintes — esta tarefa é terminal no grafo de dependências.

**Nota:** os dois outros diagnósticos que a spec descreve ("conteúdo solto sem `children` declarado" e "conteúdo solto + fill explícito `children{}` na mesma chamada") **já são cobertos pela validação genérica de slots que `checkComponentCall` já tem** — `declaredSlot == null` já produz `SLOT_NOT_FOUND` quando o alvo não declara `children`, e a checagem de cardinalidade já produz `CARDINALITY_VIOLATION` quando uma slot `ONE` recebe 2 fills (o caso solto+explícito). Confirmar isto com testes (Step 1-2) antes de escrever qualquer código novo para esses dois casos — só o nome reservado precisa de lógica nova.

- [ ] **Step 1: Escrever os 3 testes (2 confirmam comportamento já existente, 1 é RED genuíno)**

```java
// no ficheiro de teste do SemanticChecker (confirmar nome real primeiro)

@Test
void looseContentWithoutChildrenParamReusesSlotNotFound() {
    // ... construir SukoFile com um Host chamando Field() { "solto" } onde
    // Field não declara nenhum param "children" ...
    // Assert: diagnóstico com code "SLOT_NOT_FOUND" (comportamento já existente)
}

@Test
void looseContentPlusExplicitChildrenFillReusesCardinalityViolation() {
    // ... Field(Component children) chamado como
    // Field() { "solto" children { "explícito" } } ...
    // Assert: diagnóstico com code "CARDINALITY_VIOLATION" (comportamento já existente)
}

@Test
void childrenParamThatIsNotComponentIsReservedNameError() {
    // ... component Field(String children) { } ...
    // Assert: diagnóstico com code "RESERVED_CHILDREN_NAME" (NOVO — RED)
}
```

- [ ] **Step 2: Correr os 3 testes**

Run: `gradle test --tests "io.suko.lang.semantic.*" --console=plain`
Expected: os 2 primeiros PASSAM já (confirmam a reutilização); o 3º FALHA (RED genuíno).

- [ ] **Step 3: Implementar a validação de nome reservado em `checkComponent`**

```java
private void checkComponent(ComponentDecl component) {
    Map<String, Param.SlotParam> declaredSlots = new HashMap<>();
    for (Param param : component.params()) {
        if ("children".equals(param.name()) && !(param instanceof Param.SlotParam)) {
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "'children' é um nome reservado: o parâmetro tem de ser Component ou List<Component>",
                    "RESERVED_CHILDREN_NAME",
                    sourceFile,
                    paramSpan(param)
            ));
        }
        if (param instanceof Param.SlotParam slotParam) {
            declaredSlots.put(slotParam.name(), slotParam);
        }
    }
    for (Statement statement : component.body()) {
        checkStatement(statement, declaredSlots);
    }
}

private SourceSpan paramSpan(Param param) {
    return switch (param) {
        case Param.ValueParam p -> p.span();
        case Param.SlotParam p -> p.span();
    };
}
```

- [ ] **Step 4: Correr os testes, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.semantic.*" --console=plain`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(semantic): 'children' como nome reservado (deve ser Component/List<Component>)"
```

---

### Task 5: Componente como valor — `emitExpr` reconhece `CallExpr` de componente conhecido

**Files:**
- Modify: `src/main/java/io/suko/lang/JteEmitter.java:541-594` (`emitExpr`)
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: resultado confirmado da sonda da Tarefa 1 (forma exata do bloco de conteúdo dentro de expressão); `componentsByName`/`callResolver` já existentes no `JteEmitter`.
- Produces: `Expr.CallExpr` cujo `callee` é `PrimaryExpr` com nome simples presente em `componentsByName` emite como bloco de conteúdo, não como chamada Java — a Tarefa 6 (verificador) depende de a mesma regra de reconhecimento existir também no `SemanticChecker`.

**Âmbito desta tarefa:** só nomes simples (sem ponto) — chamada composta (`ui.NavLink()`) como valor fica fora de âmbito (depende do subprojeto 5, mesma fronteira já documentada para a forma statement).

- [ ] **Step 1: Escrever o teste que falha (RED) — usa a forma confirmada pela Tarefa 1**

```java
// em JteEmitterTest.java

@Test
void componentCallAsExpressionValue() throws Exception {
    String html = JteRenderSupport.renderWithDependencies(
        """
        component CardA() {
          <p>A</p>
        }
        component CardB() {
          <p>B</p>
        }
        component Host(boolean useA) {
          var c = useA ? CardA() : CardB();
          <div>{c}</div>
        }
        """, "Host", java.util.Map.of("useA", true));

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("<p>A</p>"));
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#componentCallAsExpressionValue" --console=plain`
Expected: FAIL — `emitExpr` hoje trata `CardA()` como chamada Java genérica (`CardA()`), que não compila (não existe método `CardA`).

- [ ] **Step 3: Implementar em `emitExpr` — novo `case` ANTES do `case Expr.CallExpr call -> ...` genérico**

```java
// Adicionar logo antes do case genérico de CallExpr (JteEmitter.java:577),
// depois do case que já trata leitura de slot via .apply(...):
case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p && componentsByName.containsKey(p.text()) -> {
    String targetName = callResolver != null ? callResolver.apply(p.text()) : p.text();
    yield "@`@template." + targetName + "(" + emitArgs(call.args(), slotNames) + ")`";
}
```

(Se a Tarefa 1 tiver confirmado uma forma alternativa — variável de conteúdo local — substituir este `yield` pela forma realmente confirmada, documentando a razão no mesmo ponto.)

- [ ] **Step 4: Correr o teste, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#componentCallAsExpressionValue" --console=plain`
Expected: PASS

- [ ] **Step 5: Correr a suite completa (confirmar que o novo `case` não capturou chamadas Java legítimas por engano — ex. `name.toUpperCase()` não tem `componentsByName` a colidir, mas testar explicitamente)**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(emitter): chamada de componente como valor de expressão (nomes simples)"
```

---

### Task 6: Verificador para chamada-como-valor

**Files:**
- Modify: `src/main/java/io/suko/lang/semantic/SemanticChecker.java`
- Test: ficheiro de teste do `SemanticChecker` (mesmo da Tarefa 4)

**Interfaces:**
- Consumes: reconhecimento de `CallExpr`-como-componente da Tarefa 5 (mesma regra: `callee` é `PrimaryExpr` com nome presente na `SymbolTable`).
- Produces: nada consumido por tarefas seguintes.

- [ ] **Step 1: Escrever o teste que falha (RED)**

```java
@Test
void componentCallAsValueValidatesExistence() {
    // ... component Host() { var c = NaoExiste(); } ...
    // Assert: diagnóstico "COMPONENT_NOT_FOUND" para "NaoExiste"
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --tests "io.suko.lang.semantic.*" --console=plain`
Expected: FAIL — `checkStatement` hoje só desce a `Statement.ComponentCallStmt`/`IfStmt`/`ForStmt`/`SwitchStmt` (default `{}`), nunca inspeciona expressões dentro de `VarDecl`/`Interpolation` à procura de chamadas de componente.

- [ ] **Step 3: Implementar — nova checagem de expressão, reusando a mesma regra de reconhecimento da Tarefa 5 (nome simples presente na `SymbolTable`)**

```java
private void checkStatement(Statement statement, Map<String, Param.SlotParam> currentScopeSlots) {
    switch (statement) {
        case Statement.ComponentCallStmt call -> checkComponentCall(call, currentScopeSlots);
        case Statement.VarDecl varDecl -> checkExprForComponentCalls(varDecl.value());
        case Statement.Interpolation interpolation -> checkExprForComponentCalls(interpolation.expr());
        case Statement.IfStmt ifStmt -> {
            checkExprForComponentCalls(ifStmt.condition());
            checkStatementList(ifStmt.thenBranch(), currentScopeSlots);
            checkStatementList(ifStmt.elseBranch(), currentScopeSlots);
        }
        case Statement.ForStmt forStmt -> checkStatementList(forStmt.body(), currentScopeSlots);
        case Statement.SwitchStmt switchStmt -> {
            for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                checkStatementList(switchCase.body(), currentScopeSlots);
            }
            checkStatementList(switchStmt.defaultCase(), currentScopeSlots);
        }
        default -> {}
    }
}

/** Só desce o suficiente para achar CallExpr(callee=PrimaryExpr) top-level
 * dentro de ternários/parênteses — mesma regra estrutural do JteEmitter
 * (tarefa 5): não é uma resolução de tipos completa. */
private void checkExprForComponentCalls(Expr expr) {
    switch (expr) {
        case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p -> {
            ComponentDecl target = symbolTable.lookup(p.text());
            if (target == null && looksLikeComponentName(p.text())) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + p.text() + "' não encontrado",
                        "COMPONENT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
            }
        }
        case Expr.TernaryExpr ternary -> {
            checkExprForComponentCalls(ternary.condition());
            checkExprForComponentCalls(ternary.whenTrue());
            checkExprForComponentCalls(ternary.whenFalse());
        }
        case Expr.ParenExpr paren -> checkExprForComponentCalls(paren.inner());
        default -> {}
    }
}

/** Distingue "provável chamada de componente" de uma chamada de método/
 * função Java qualquer: convenção do projeto (ver ComponentDecl) é nome de
 * componente começar por maiúscula — mesma convenção já usada em todos os
 * exemplos e specs. Evita falso positivo em `toUpperCase()` etc. */
private boolean looksLikeComponentName(String name) {
    return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
}
```

**Limitação aceite, documentada aqui:** ao contrário do `JteEmitter` (Tarefa 5), onde o reconhecimento vive dentro de `emitExpr` e por isso se aplica universalmente a qualquer posição de expressão (argumentos, atributos, iterável de `for`, sujeito de `switch`, ...), esta tarefa só valida `VarDecl`/`Interpolation`/condição de `IfStmt` — as posições mais comuns. Um `CardA()` inválido passado como argumento doutra chamada (`Wrapper(child = NaoExiste())`) ainda compila sem erro do verificador, mas falha a emitir Java válido (o emitter tenta `@template.NaoExiste(...)`, que não resolve). Cobertura completa (percorrer `Arg`/`Attribute`/iterável/sujeito) fica para uma ronda futura de diagnósticos — mesmo padrão de gaps já aceites no projeto.

- [ ] **Step 4: Correr o teste, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.semantic.*" --console=plain`
Expected: PASS

- [ ] **Step 5: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(semantic): valida existência de componente chamado como valor de expressão"
```

---

### Task 7: Lexer — `RBRACE` sai de `${...}` de volta a `STRING_MODE`

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4:56`
- Test: `src/test/java/io/suko/lang/SukoParserSmokeTest.java` (ou o ficheiro de smoke test do parser existente — confirmar nome com `find`)

**Interfaces:**
- Produces: `${expr}`/`$ident` dentro de um literal de string fazem parse corretamente (o resto da string depois do `}` volta a ser lexado em `STRING_MODE`) — a Tarefa 8 (AST) depende disto.

- [ ] **Step 1: Escrever o teste de parse que falha (RED)**

```java
// no ficheiro de smoke test do parser

@Test
void parsesInterpolatedStringWithTrailingText() {
    String source = """
        component Greeting(String name) {
          <p>{"Olá ${name}!"}</p>
        }
        """;
    // Só precisa de não lançar exceção de parse — a tradução para AST/Java
    // é a Tarefa 8/9. Confirma que o lexer volta a STRING_MODE depois do
    // '}' de fecho e lexa "!" + STRING_END corretamente.
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
        var lexer = new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(source));
        var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
        parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
        parser.compilationUnit();
    });
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --tests "*parsesInterpolatedStringWithTrailingText*" --console=plain`
Expected: FAIL (erro de parse ou `BailErrorStrategy` a abortar) — confirma o bug documentado na spec.

- [ ] **Step 3: Corrigir `RBRACE` no lexer — `popMode` condicional (não pode ser `-> popMode` incondicional: `}` fecha `templateBlock`/`slotBlock`/`if`/`for` etc. na pilha de modos vazia, e `popMode()` numa pilha vazia lança exceção)**

`src/main/antlr/io/suko/lang/SukoLexer.g4:56`, substituir:

```
RBRACE    : '}';
```

por:

```
// DESVIO DO BRIEF (subprojeto 6, tarefa 7): "-> popMode" incondicional
// fecharia também todo "}" que fecha templateBlock/slotBlock/if/for/switch
// (a esmagadora maioria dos casos), cuja pilha de modos está vazia nesse
// ponto — popMode() nessa condição lança exceção no runtime ANTLR. Só o
// "}" que fecha um "${...}" (EXPR_INTERP_START empurrou DEFAULT_MODE de
// dentro de STRING_MODE) tem de voltar de modo; esse é sempre o único "}"
// com pilha não-vazia neste ponto, porque a gramática de `expression` não
// tem chaves em si mesma (ver comentário em SukoParser.g4 junto a
// stringPart). ANTLR não permite condição num comando "->", por isso usa-se
// ação embutida em vez do atalho de comando — mesmo padrão de desvio já
// usado em LINE_COMMENT.
RBRACE
    : '}' { if (!_modeStack.isEmpty()) popMode(); }
    ;
```

- [ ] **Step 4: Regenerar o lexer/parser e confirmar ausência de `warning`**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem a palavra `warning` na saída.

- [ ] **Step 5: Correr o teste, confirmar GREEN**

Run: `gradle test --tests "*parsesInterpolatedStringWithTrailingText*" --console=plain`
Expected: PASS

- [ ] **Step 6: Correr a suite completa (confirmar que nenhum `}` normal quebrou)**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "fix(lexer): RBRACE sai de \${...} de volta a STRING_MODE (popMode condicional)"
```

---

### Task 8: AST — `Expr.StringPart.Interp`/`SimpleInterp`

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Expr.java:19-22` (`StringPart`)
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java:328-335` (`buildStringLiteral`)
- Test: `src/test/java/io/suko/lang/SukoAstBuilderTest.java` (ou equivalente — confirmar nome)

**Interfaces:**
- Consumes: parse correto de `${expr}`/`$ident` da Tarefa 7.
- Produces: `Expr.StringLiteralExpr` pode ter partes `Literal`/`Interp`/`SimpleInterp` misturadas — a Tarefa 9 (emitter) consome isto.

- [ ] **Step 1: Escrever o teste que falha (RED)**

```java
@Test
void buildsMixedStringLiteralParts() {
    String source = """
        component Greeting(String name) {
          <p>{"Olá ${name}!"}</p>
        }
        """;
    var lexer = new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(source));
    var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
    var file = new io.suko.lang.SukoAstBuilder(source).build(parser.compilationUnit());

    // Greeting.body() == [HtmlElement("p", children=[Interpolation(StringLiteralExpr)])]
    var p = (io.suko.lang.ast.Statement.HtmlElement) file.components().get(0).body().get(0);
    var interpolation = (io.suko.lang.ast.Statement.Interpolation) p.children().get(0);
    var stringLiteral = (io.suko.lang.ast.Expr.StringLiteralExpr) interpolation.expr();

    org.junit.jupiter.api.Assertions.assertEquals(3, stringLiteral.parts().size());
    var part0 = (io.suko.lang.ast.Expr.StringPart.Literal) stringLiteral.parts().get(0);
    org.junit.jupiter.api.Assertions.assertEquals("Olá ", part0.javaEscapedText());
    org.junit.jupiter.api.Assertions.assertInstanceOf(io.suko.lang.ast.Expr.StringPart.Interp.class, stringLiteral.parts().get(1));
    var part2 = (io.suko.lang.ast.Expr.StringPart.Literal) stringLiteral.parts().get(2);
    org.junit.jupiter.api.Assertions.assertEquals("!", part2.javaEscapedText());
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --console=plain` (o teste indicado)
Expected: FAIL — `buildStringLiteral` hoje concatena tudo numa única `Literal`.

- [ ] **Step 3: `Expr.StringPart` ganha as variantes novas**

`src/main/java/io/suko/lang/ast/Expr.java:19-22`, substituir:

```java
sealed interface StringPart permits StringPart.Literal {
    record Literal(String javaEscapedText) implements StringPart {
    }
}
```

por:

```java
sealed interface StringPart permits StringPart.Literal, StringPart.Interp, StringPart.SimpleInterp {
    record Literal(String javaEscapedText) implements StringPart {
    }

    /** "${expr}" */
    record Interp(Expr expr) implements StringPart {
    }

    /** "$identificador" */
    record SimpleInterp(String identifier) implements StringPart {
    }
}
```

- [ ] **Step 4: Reescrever `buildStringLiteral`**

```java
private Expr.StringLiteralExpr buildStringLiteral(SukoParser.StringLiteralContext ctx) {
    List<Expr.StringPart> parts = new ArrayList<>();
    StringBuilder literalRun = new StringBuilder();
    for (SukoParser.StringPartContext partCtx : ctx.stringPart()) {
        if (partCtx.EXPR_INTERP_START() != null) {
            flushLiteral(parts, literalRun);
            parts.add(new Expr.StringPart.Interp(buildExpr(partCtx.expression())));
        } else if (partCtx.SIMPLE_INTERP_START() != null) {
            flushLiteral(parts, literalRun);
            // "$nome" — remove o "$" inicial do texto do token.
            parts.add(new Expr.StringPart.SimpleInterp(partCtx.getText().substring(1)));
        } else {
            literalRun.append(partCtx.getText());
        }
    }
    flushLiteral(parts, literalRun);
    return new Expr.StringLiteralExpr(parts, spanOf(ctx));
}

private void flushLiteral(List<Expr.StringPart> parts, StringBuilder literalRun) {
    if (literalRun.length() > 0) {
        parts.add(new Expr.StringPart.Literal(literalRun.toString()));
        literalRun.setLength(0);
    }
}
```

(Confirmar os nomes exatos dos acessores gerados — `partCtx.EXPR_INTERP_START()`/`partCtx.SIMPLE_INTERP_START()` — contra `build/generated-src/antlr/main/io/suko/lang/SukoParser.java`, `StringPartContext`, antes de compilar; a regra `stringPart: STRING_TEXT | STRING_ESCAPE | SIMPLE_INTERP_START | EXPR_INTERP_START expression RBRACE | SIMPLE_DOLLAR` gera um accessor por token/regra nomeada nela referenciada.)

- [ ] **Step 5: Correr o teste, confirmar GREEN**

Run: `gradle test --console=plain` (o teste indicado)
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat(ast): StringPart.Interp/SimpleInterp — literais de string com partes interpoladas"
```

---

### Task 9: Emitter — concatenação Java para strings interpoladas + diagnóstico de `{ident}` mal-escrito

**Files:**
- Modify: `src/main/java/io/suko/lang/JteEmitter.java:605-614` (`emitStringLiteral`)
- Modify: `src/main/java/io/suko/lang/semantic/SemanticChecker.java`
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`
- Test: ficheiro de teste do `SemanticChecker`

**Interfaces:**
- Consumes: `Expr.StringPart.Interp`/`SimpleInterp` da Tarefa 8.
- Produces: nada consumido por tarefas seguintes.

- [ ] **Step 1: Escrever o teste de render que falha (RED)**

```java
// em JteEmitterTest.java

@Test
void rendersAttributeWithRealInterpolation() throws Exception {
    String html = JteRenderSupport.render(
        """
        component Button(String variant) {
          <a class="btn btn-${variant}">Click</a>
        }
        """, "Button", java.util.Map.of("variant", "primary"));

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("class=\"btn btn-primary\""));
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#rendersAttributeWithRealInterpolation" --console=plain`
Expected: FAIL — `emitStringLiteral` hoje ignora partes que não são `Literal`.

- [ ] **Step 3: Reescrever `emitStringLiteral` — concatenação Java quando há partes não-literais**

`emitStringLiteral` hoje não recebe `slotNames` (`JteEmitter.java:605`); uma interpolação `${slot}` dentro de uma string precisa de `emitExpr` resolver `.apply(...)` como qualquer outra leitura de slot, o que exige o `slotNames` do componente atual. Mudar a assinatura para receber `slotNames` e propagar no único call-site (`emitExpr`'s `case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral, slotNames)`, `JteEmitter.java:544`).

```java
private String emitStringLiteral(Expr.StringLiteralExpr stringLiteral, java.util.Set<String> slotNames) {
    List<Expr.StringPart> parts = stringLiteral.parts();
    boolean hasInterpolation = parts.stream().anyMatch(p -> !(p instanceof Expr.StringPart.Literal));
    if (!hasInterpolation) {
        StringBuilder sb = new StringBuilder("\"");
        for (Expr.StringPart part : parts) {
            sb.append(((Expr.StringPart.Literal) part).javaEscapedText());
        }
        return sb.append('"').toString();
    }

    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
        if (i > 0) sb.append(" + ");
        switch (parts.get(i)) {
            case Expr.StringPart.Literal literal -> sb.append('"').append(literal.javaEscapedText()).append('"');
            case Expr.StringPart.Interp interp -> sb.append('(').append(emitExpr(interp.expr(), slotNames)).append(')');
            case Expr.StringPart.SimpleInterp simple -> sb.append(simple.identifier());
        }
    }
    return sb.toString();
}
```

- [ ] **Step 4: Correr o teste, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#rendersAttributeWithRealInterpolation" --console=plain`
Expected: PASS

- [ ] **Step 5: Diagnóstico — `{ident}` mal-escrito dentro de uma string onde `ident` é um parâmetro conhecido do componente**

Escrever o teste (RED):

```java
@Test
void bareBraceInStringSuggestsDollarSyntax() {
    // ... component Button(String variant) { <a class="btn btn-{variant}">x</a> } ...
    // Assert: diagnóstico "BARE_BRACE_IN_STRING" mencionando "${variant}"
}
```

Implementar: como `{variant}` dentro de uma string é hoje texto literal (não gera nenhum nó de AST distinto — fica dentro de `StringPart.Literal`), a deteção é uma verificação textual sobre o conteúdo de cada `Literal` de cada `StringLiteralExpr` do componente, usando os nomes dos `Param` desse componente.

```java
// SemanticChecker.java — chamado a partir de checkComponent, com o
// conjunto de nomes de parâmetros do componente atual.
private static final java.util.regex.Pattern BARE_BRACE_IDENT =
    java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

private void checkBareBraceInStrings(ComponentDecl component) {
    java.util.Set<String> paramNames = component.params().stream()
        .map(Param::name).collect(java.util.stream.Collectors.toSet());
    for (Statement statement : component.body()) {
        checkBareBraceInStatement(statement, paramNames);
    }
}

private void checkBareBraceInStatement(Statement statement, java.util.Set<String> paramNames) {
    switch (statement) {
        case Statement.HtmlElement element -> {
            for (Statement.Attribute attribute : element.attributes()) {
                checkBareBraceInExpr(attribute.value(), paramNames, attribute.span());
            }
            for (Statement child : element.children()) {
                checkBareBraceInStatement(child, paramNames);
            }
        }
        case Statement.Interpolation interpolation ->
            checkBareBraceInExpr(interpolation.expr(), paramNames, interpolation.span());
        default -> {}
    }
}

private void checkBareBraceInExpr(Expr expr, java.util.Set<String> paramNames, SourceSpan span) {
    if (!(expr instanceof Expr.StringLiteralExpr stringLiteral)) {
        return;
    }
    for (Expr.StringPart part : stringLiteral.parts()) {
        if (!(part instanceof Expr.StringPart.Literal literal)) {
            continue;
        }
        var matcher = BARE_BRACE_IDENT.matcher(literal.javaEscapedText());
        while (matcher.find()) {
            String ident = matcher.group(1);
            if (paramNames.contains(ident)) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "'{" + ident + "}' dentro de uma string é texto literal — use '${" + ident + "}' para interpolar",
                        "BARE_BRACE_IN_STRING",
                        sourceFile,
                        span
                ));
            }
        }
    }
}
```

Chamar `checkBareBraceInStrings(component)` a partir de `checkComponent`, depois do loop de `checkStatement`.

- [ ] **Step 6: Correr o teste, confirmar GREEN**

- [ ] **Step 7: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(emitter): interpolação real em strings/atributos; diagnóstico de {ident} mal-escrito"
```

---

### Task 10: Auto-`toString` de valores Java arbitrários

**Files:**
- Modify: `src/main/java/io/suko/lang/JteEmitter.java:378-382` (`emitStatement`, caso `Interpolation`)
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `renderProp()`/tipo estrutural de `Param.SlotParam` (Tarefa 2) para a exceção de slots.
- Produces: nada consumido por tarefas seguintes.

- [ ] **Step 1: Escrever o teste que falha (RED) — hoje não compila (confirmado por sonda na spec)**

```java
@Test
void interpolatesArbitraryJavaObjectViaToString() throws Exception {
    // "Object" é um tipo desqualificado que já faz parse hoje (java.lang,
    // resolve sem import) e não tem overload dedicado em
    // gg.jte.TemplateOutput.writeUserContent — exatamente o caso confirmado
    // por sonda na spec ("V1"): hoje isto não compila.
    String html = JteRenderSupport.render(
        """
        component Show(Object id) {
          <p>{id}</p>
        }
        """, "Show", java.util.Map.of("id", java.util.UUID.fromString("11111111-1111-1111-1111-111111111111")));

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("11111111-1111-1111-1111-111111111111"));
}
```

- [ ] **Step 2: Correr o teste, confirmar FAIL**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#interpolatesArbitraryJavaObjectViaToString" --console=plain`
Expected: FAIL — `${id}` hoje gera Java que não compila para um tipo sem overload em `writeUserContent` (confirmado por sonda na spec, "V1").

- [ ] **Step 3: Implementar a forma ternária null-safe, com exceção para slots/`Component`**

```java
case Statement.Interpolation interpolation -> {
    Expr expr = interpolation.expr();
    if (isContentTyped(expr, slotNames)) {
        out.append("${").append(emitExpr(expr, slotNames)).append('}');
    } else {
        String emitted = emitExpr(expr, slotNames);
        out.append("${").append(emitted).append(" == null ? null : (").append(emitted).append(").toString()}");
    }
}
```

```java
/** Não embrulhar em .toString() quando a expressão já é um slot (identificador
 * cujo nome está em slotNames) — .toString() num slot imprimiria a
 * identidade do objeto Java, não o conteúdo (ver spec, secção auto-toString). */
private boolean isContentTyped(Expr expr, java.util.Set<String> slotNames) {
    return expr instanceof Expr.PrimaryExpr p && slotNames.contains(p.text());
}
```

**Limitação aceite, documentada aqui:** a exceção acima só cobre o caso de um slot lido diretamente (`{header}`); uma expressão mais complexa que produza um `Content`/`Component` por outro caminho (ex. `{cond ? header : outroSlot}`) ainda seria embrulhada incorretamente em `.toString()`. Fora de âmbito desta tarefa — precisaria de inferência de tipo real, que o projeto já decidiu não fazer neste nível (ver `ARCHITECTURE.md`, limitações conhecidas).

- [ ] **Step 4: Correr o teste, confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#interpolatesArbitraryJavaObjectViaToString" --console=plain`
Expected: PASS

- [ ] **Step 5: Regressão — confirmar que ler um slot simples continua sem `.toString()`**

```java
@Test
void slotInterpolationIsNeverWrappedInToString() throws Exception {
    String html = JteRenderSupport.renderWithDependencies(
        """
        component Card(Component header) {
          <div>{header}</div>
        }
        component Host() {
          Card() { "Título" }
        }
        """, "Host", java.util.Map.of());

    org.junit.jupiter.api.Assertions.assertTrue(html.contains("Título"));
}
```

Run: `gradle test --tests "io.suko.lang.JteEmitterTest#slotInterpolationIsNeverWrappedInToString" --console=plain`
Expected: PASS

- [ ] **Step 6: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(emitter): auto-toString null-safe para valores Java arbitrários (exceto slots)"
```

---

### Task 11: Migração de `examples/` + atualização de `ARCHITECTURE.md` e da spec

**Files:**
- Modify: pelo menos 1-2 ficheiros em `examples/` que usem `slot<T>` hoje (confirmar com `grep -rl "slot<" examples/`)
- Modify: `ARCHITECTURE.md`
- Modify: `docs/superpowers/specs/2026-09-18-suko-modelo-componente.md` (corrigir a secção "Componente como valor" com a descoberta documentada no topo deste plano)
- Test: `src/test/java/io/suko/lang/SukoParserSmokeTest.java` (ou equivalente)

**Interfaces:**
- Consumes: todas as tarefas anteriores — esta é a tarefa de aceitação end-to-end.

- [ ] **Step 1: Identificar os exemplos a migrar**

Run: `grep -rl "slot<" examples/`

Escolher pelo menos 1 exemplo com slot `ONE` simples e 1 com render-prop (ou `List<slot<T>>`), para exercitar os dois caminhos migrados na Tarefa 2.

- [ ] **Step 2: Escrever/atualizar o smoke test para os exemplos escolhidos (RED só se o smoke test ainda referenciar a sintaxe antiga)**

Confirmar que `SukoParserSmokeTest` (ou equivalente) continua a fazer parse+render real dos ficheiros migrados.

- [ ] **Step 3: Migrar os `.sk` escolhidos — `slot<T>` → `Component`/`List<Component>`/`Function<T, Component>` conforme o caso**

- [ ] **Step 4: Correr o smoke test, confirmar GREEN**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Atualizar `ARCHITECTURE.md`**

- Seção "Decisões de design que moldam o pipeline": substituir o parágrafo sobre slots (`slot<T>`/render-prop por deteção de scan) pela descrição do modelo `Component` (cardinalidade via `List<Component>`, opcional via `= valor`, render-prop via `Function<T, Component>` explícito).
- Remover a limitação "Interpolação dentro de literal de string não funciona" (resolvida pelas Tarefas 7-9).
- Remover/corrigir a limitação "Conteúdo anónimo num bloco de chamada é descartado em silêncio" (resolvida pela Tarefa 3 — só para `Component` ONE; anotar que `List<Component>` continua a exigir fills nomeados explícitos).
- Atualizar "Roadmap por subprojeto": subprojeto 6 passa a CONCLUÍDO.

- [ ] **Step 6: Corrigir a spec com a descoberta documentada no topo deste plano**

Editar `docs/superpowers/specs/2026-09-18-suko-modelo-componente.md`, secção "Componente como valor", substituindo a proposta de gramática nova por uma nota remetendo para este plano (a gramática `expression LPAREN argList? RPAREN` já existente cobre o caso, sem `Expr.ComponentCallExpr` nem mudança de gramática).

- [ ] **Step 7: Commit final**

```bash
git add -A
git commit -m "docs: subprojeto 6 concluído — migra examples/, atualiza ARCHITECTURE.md e corrige a spec"
```

---

## Resumo de dependências entre tarefas

```
1 (sonda) ──> 5 (componente como valor no emitter) ──> 6 (verificador)
2 (Component substitui slot<T>) ──> 3 (children implícitos) ──> 4 (diagnósticos children)
2 ──> 5
7 (lexer popMode) ──> 8 (AST StringPart) ──> 9 (emitter + diagnóstico string)
10 (auto-toString) depende só de 2 (renderProp para a exceção de slots)
11 (migração + docs) depende de TODAS as anteriores
```

Tarefas 7-9 (interpolação) e a Tarefa 10 (auto-toString) são independentes de 2-6 e podem correr em paralelo com elas, exceto pela dependência pontual de `renderProp()` (Tarefa 2) na exceção da Tarefa 10.
