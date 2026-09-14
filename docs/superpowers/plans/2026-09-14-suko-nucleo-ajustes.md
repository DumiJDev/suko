# Suko — Ajustes de núcleo pós-subprojeto 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir a superfície de slots simples (voltar a `Content` nu, não `Function`), implementar `var` no núcleo, e tornar `examples/Card.sk` um exemplo concreto (não genérico) — os três pré-requisitos que o `architect` e o utilizador decidiram resolver antes de escrever o spec do subprojeto 2 (Verificador Suko).

**Architecture:** Todas as mudanças são internas a `JteEmitter`/`SukoAstBuilder`/`ast` — nenhuma mudança de gramática ANTLR é necessária (a sintaxe `var x = expr;` e `slot<T>` já existem e fazem parse). Cada tarefa segue TDD com render real via `gg.jte` (não só compilação), no mesmo estilo do subprojeto 1: escrever/reescrever o teste primeiro, confirmar RED, implementar, confirmar GREEN, documentar qualquer desvio descoberto por probe com o texto exato do erro.

**Tech Stack:** Java 21, ANTLR4 4.13.1 (pinado, não tocado nesta ronda), gg.jte 3.1.12 (pinado), JUnit 5, Gradle (usar `gradle`, não `./gradlew` — não há wrapper committado neste repo).

**Spec:** `docs/superpowers/specs/2026-09-14-suko-nucleo-ajustes.md`

## Global Constraints

- Java 21 como piso.
- `org.antlr:antlr4:4.13.1` e `gg.jte:jte:3.1.12` pinadas — não mudar de versão.
- 1 componente → 1 `.jte`.
- Slots são uma variante de `Param`, nunca uma declaração à parte.
- Regenerar gramáticas sem `warning` na saída, se algum `.g4` for tocado (não deveria ser preciso nesta ronda): `gradle generateSukoLexer generateSukoParser --console=plain`.
- TDD com render real via `gg.jte` (`JteRenderSupport`), não só compilação Java.
- Qualquer desvio descoberto por probe empírico deve ser documentado no código, no ponto da descoberta, com o texto exato do erro observado — convenção estabelecida em todas as tarefas do subprojeto 1.
- Rodar `gradle test --console=plain` (comando completo, do diretório raiz do repo) no fim de cada tarefa e confirmar `BUILD SUCCESSFUL` antes de commitar.

---

### Task 1: Slots simples voltam a `Content` nu (render-prop só quando invocado)

**Files:**
- Modify: `src/main/java/io/suko/lang/JteEmitter.java` (métodos `emit`, `emitWithSourceMap`, `jteParamDeclaration`, `emitComponentCall`, `emitSlotFillContent`; novos métodos `renderPropSlotNames`/`collectRenderPropSlots` (Statement e Expr) e `resolveSlotIsRenderProp`)
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java:208-361` (`rendersRequiredSingleSlot`, `rendersOptionalAndMultipleSlots`, `rendersRenderPropSlot`)
- Modify: `ARCHITECTURE.md` (parágrafos "Forma concreta escolhida" e "Ler um slot exige chamá-lo", adicionados pelo `architect` na revisão pós-subprojeto-1)

**Interfaces:**
- Consumes: `Param.SlotParam(Type elementType, String name, Cardinality cardinality, Optional<Expr> defaultValue, SourceSpan span)`, `Statement`/`Expr` sealed hierarchies (ver `src/main/java/io/suko/lang/ast/Statement.java` e `Expr.java` — não mudam nesta tarefa), `componentsByName` (campo existente de `JteEmitter`).
- Produces: `JteEmitter.jteParamDeclaration(Param, Set<String> slotNames, Set<String> renderPropSlotNames)` — assinatura muda (ganha um 3º parâmetro); `JteEmitter.emitSlotFillContent(Statement.SlotFill, StringBuilder, Set<String> slotNames, Boolean isRenderProp)` — assinatura muda (ganha um 4º parâmetro). Nenhuma classe fora de `JteEmitter` chama estes métodos privados hoje (confirmado por grep), por isso a mudança de assinatura é segura.

- [ ] **Step 1: Reescrever os testes existentes para a superfície nova (RED)**

Substituir o corpo do teste `rendersRequiredSingleSlot` (linha 208-231 de
`src/test/java/io/suko/lang/JteEmitterTest.java`) por:

```java
@Test
void rendersRequiredSingleSlot() throws Exception {
    // Slot simples (nunca invocado como função no corpo de Card) é
    // Content nu — ler é {header}, sem `.apply(null)`. Ver
    // docs/superpowers/specs/2026-09-14-suko-nucleo-ajustes.md, Ajuste 1.
    String source = """
        component Card(slot<String> header) {
          <div class="card">{header}</div>
        }

        component Page() {
          Card() {
            header { <b>Título</b> }
          }
        }
        """;

    String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

    assertTrue(html.contains("<b>Título</b>"));
}
```

Substituir o corpo do teste `rendersOptionalAndMultipleSlots` (linha
233-361) por:

```java
@Test
void rendersOptionalAndMultipleSlots() throws Exception {
    // "Content"/"List" desqualificados exigem o hardcode de
    // JteEmitter.javaType (tarefa 17, inalterado nesta tarefa) — ver
    // ARCHITECTURE.md, "Não há imports automáticos". `title`/`actions`
    // nunca são invocados como função no corpo de Toolbar, por isso
    // ambos são Content/List<Content> nus (não Function).
    String source = """
        component Toolbar(slot<String> title = null, List<slot<String>> actions = null) {
          if (title == null) {
            <div>sem-titulo</div>
          } else {
            <div>{title}</div>
          }
          for (Content action : actions) {
            <span>{action}</span>
          }
        }

        component WithActions() {
          Toolbar() {
            title { <b>Editar</b> }
            actions { <button>Salvar</button> }
            actions { <button>Cancelar</button> }
          }
        }

        component WithoutTitle() {
          Toolbar() {
            actions { <button>Só</button> }
          }
        }
        """;

    String withActions = JteRenderSupport.renderWithDependencies(source, "WithActions", Map.of());
    assertTrue(withActions.contains("<b>Editar</b>"));
    assertTrue(withActions.contains("<button>Salvar</button>"));
    assertTrue(withActions.contains("<button>Cancelar</button>"));

    String withoutTitle = JteRenderSupport.renderWithDependencies(source, "WithoutTitle", Map.of());
    assertTrue(withoutTitle.contains("sem-titulo"));
}
```

Em `rendersRenderPropSlot` (linha 364 em diante): não mudar o `.sk` nem
as asserções (o comportamento não muda — `row` continua a ser invocado
como `row(item)` dentro de `ItemList`, continua render-prop). Só
corrigir os comentários que descrevem a forma `Function`-sempre como
"a única forma" — reformular para deixar claro que este é o caso
render-prop (invocado no corpo), contrastando com os dois testes acima
(caso simples, não invocado).

- [ ] **Step 2: Correr os testes e confirmar que falham (RED genuíno)**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: FAIL em `rendersRequiredSingleSlot` e
`rendersOptionalAndMultipleSlots` — o `JteEmitter` atual ainda gera
`Function<...>` para todos os `SlotParam`, então `{header}`/`{title}`/
`for (Content action : actions)` não compilam contra o `.jte` gerado
hoje. `rendersRenderPropSlot` continua a passar (não foi tocado).
Copiar o texto exato do erro de compilação Java reportado pelo
`gg.jte` para confirmar que é mesmo um erro de tipo (não um erro de
parse ou de configuração de teste).

- [ ] **Step 3: Adicionar a deteção de render-prop ao `JteEmitter`**

Adicionar estes métodos privados a `JteEmitter.java` (perto de
`slotNamesOf`, linha ~106):

```java
private java.util.Set<String> renderPropSlotNames(ComponentDecl component, java.util.Set<String> slotNames) {
    java.util.Set<String> renderProp = new java.util.HashSet<>();
    for (Statement statement : component.body()) {
        collectRenderPropSlots(statement, slotNames, renderProp);
    }
    return renderProp;
}

private void collectRenderPropSlots(Statement statement, java.util.Set<String> slotNames, java.util.Set<String> renderProp) {
    switch (statement) {
        case Statement.HtmlElement element -> {
            for (Statement.Attribute attribute : element.attributes()) {
                collectRenderPropSlots(attribute.value(), slotNames, renderProp);
            }
            for (Statement child : element.children()) {
                collectRenderPropSlots(child, slotNames, renderProp);
            }
        }
        case Statement.TextRun ignored -> { }
        case Statement.Interpolation interpolation ->
            collectRenderPropSlots(interpolation.expr(), slotNames, renderProp);
        case Statement.IfStmt ifStmt -> {
            collectRenderPropSlots(ifStmt.condition(), slotNames, renderProp);
            for (Statement s : ifStmt.thenBranch()) collectRenderPropSlots(s, slotNames, renderProp);
            for (Statement s : ifStmt.elseBranch()) collectRenderPropSlots(s, slotNames, renderProp);
        }
        case Statement.ForStmt forStmt -> {
            collectRenderPropSlots(forStmt.iterable(), slotNames, renderProp);
            for (Statement s : forStmt.body()) collectRenderPropSlots(s, slotNames, renderProp);
        }
        case Statement.SwitchStmt switchStmt -> {
            collectRenderPropSlots(switchStmt.subject(), slotNames, renderProp);
            for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                collectRenderPropSlots(switchCase.matchValue(), slotNames, renderProp);
                for (Statement s : switchCase.body()) collectRenderPropSlots(s, slotNames, renderProp);
            }
            for (Statement s : switchStmt.defaultCase()) collectRenderPropSlots(s, slotNames, renderProp);
        }
        case Statement.ComponentCallStmt call -> {
            for (Statement.Arg arg : call.args()) collectRenderPropSlots(arg.value(), slotNames, renderProp);
            for (Statement.SlotFill fill : call.slotFills()) {
                for (Statement s : fill.body()) collectRenderPropSlots(s, slotNames, renderProp);
            }
        }
    }
}

private void collectRenderPropSlots(Expr expr, java.util.Set<String> slotNames, java.util.Set<String> renderProp) {
    switch (expr) {
        case Expr.CallExpr call -> {
            if (call.callee() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text())) {
                renderProp.add(p.text());
            }
            collectRenderPropSlots(call.callee(), slotNames, renderProp);
            for (Expr arg : call.args()) collectRenderPropSlots(arg, slotNames, renderProp);
        }
        case Expr.PrimaryExpr ignored -> { }
        case Expr.StringLiteralExpr ignored -> { }
        case Expr.AccessExpr access -> collectRenderPropSlots(access.target(), slotNames, renderProp);
        case Expr.NotExpr not -> collectRenderPropSlots(not.operand(), slotNames, renderProp);
        case Expr.UnaryMinusExpr unaryMinus -> collectRenderPropSlots(unaryMinus.operand(), slotNames, renderProp);
        case Expr.BinaryExpr binary -> {
            collectRenderPropSlots(binary.left(), slotNames, renderProp);
            collectRenderPropSlots(binary.right(), slotNames, renderProp);
        }
        case Expr.TernaryExpr ternary -> {
            collectRenderPropSlots(ternary.condition(), slotNames, renderProp);
            collectRenderPropSlots(ternary.whenTrue(), slotNames, renderProp);
            collectRenderPropSlots(ternary.whenFalse(), slotNames, renderProp);
        }
        case Expr.ParenExpr paren -> collectRenderPropSlots(paren.inner(), slotNames, renderProp);
        case Expr.SafeAccessExpr safeAccess -> collectRenderPropSlots(safeAccess.target(), slotNames, renderProp);
        case Expr.ElvisExpr elvis -> {
            collectRenderPropSlots(elvis.left(), slotNames, renderProp);
            collectRenderPropSlots(elvis.right(), slotNames, renderProp);
        }
    }
}
```

Nota: `Expr.CallExpr` cujo `callee()` é um `Expr.SafeAccessExpr` (ex.:
`label?.length()`) cai no `case Expr.CallExpr call` genérico acima —
correto, porque um slot nunca é o alvo de um `SafeAccessExpr` como
callee de chamada nesta gramática (ver o caso especial equivalente em
`emitExpr`, que só existe para desaçucarar `?.` seguido de chamada, não
para deteção de slots).

- [ ] **Step 4: Rescrever `jteParamDeclaration` para ramificar em render-prop vs simples**

Em `JteEmitter.java`, mudar a assinatura de `jteParamDeclaration`
(linha 116) de `(Param param, java.util.Set<String> slotNames)` para
`(Param param, java.util.Set<String> slotNames, java.util.Set<String> renderPropSlotNames)`,
e o corpo do `switch` (linha 129-188) para:

```java
return switch (param) {
    case Param.ValueParam p -> javaType(p.type()) + " " + p.name()
        + p.defaultValue().map(expr -> " = " + emitExpr(expr, slotNames)).orElse("");
    case Param.SlotParam p when p.cardinality() == Cardinality.ONE && renderPropSlotNames.contains(p.name()) ->
        "java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content> " + p.name()
            + (p.defaultValue().isPresent() ? " = (java.util.function.Function<" + javaType(p.elementType())
                + ", gg.jte.Content>) (" + javaType(p.elementType()) + " it) -> null" : "");
    case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
        "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
    case Param.SlotParam p when renderPropSlotNames.contains(p.name()) ->
        "java.util.List<java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content>> " + p.name()
            + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
    case Param.SlotParam p ->
        "java.util.List<gg.jte.Content> " + p.name()
            + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
};
```

Manter os comentários "DESVIO DO BRIEF" já existentes acima do método
(continuam válidos — descrevem o valor por omissão de `ValueParam` e o
motivo do cast explícito no lambda de `Function`, nenhum dos dois muda)
mas apagar o comentário da tarefa 18 que dizia "o brief propõe... mas
TODO slot passa a ser Function" — não é mais verdade.

Atualizar os dois pontos de chamada:

`emit` (linha 52-64):
```java
public String emit(ComponentDecl component) {
    java.util.Set<String> slotNames = slotNamesOf(component);
    java.util.Set<String> renderPropSlotNames = renderPropSlotNames(component, slotNames);

    StringBuilder out = new StringBuilder();
    for (Param param : component.params()) {
        out.append("@param ").append(jteParamDeclaration(param, slotNames, renderPropSlotNames)).append('\n');
    }
    out.append('\n');
    for (Statement statement : component.body()) {
        emitStatement(statement, out, slotNames);
    }
    return out.toString();
}
```

`emitWithSourceMap` (linha 69-96), mesma mudança nas duas linhas que
chamam `jteParamDeclaration`:
```java
java.util.Set<String> slotNames = slotNamesOf(component);
java.util.Set<String> renderPropSlotNames = renderPropSlotNames(component, slotNames);
StringBuilder probe = new StringBuilder();
for (Param param : component.params()) {
    probe.append("@param ").append(jteParamDeclaration(param, slotNames, renderPropSlotNames)).append('\n');
}
```

- [ ] **Step 5: Adicionar `resolveSlotIsRenderProp` e usá-la no ponto de chamada**

Adicionar perto de `resolveSlotCardinality` (linha ~311):

```java
/** Mesma limitação documentada em resolveSlotCardinality: devolve null
 * quando o componente-alvo não é conhecido nesta emissão. */
private Boolean resolveSlotIsRenderProp(String componentName, String paramName) {
    ComponentDecl target = componentsByName.get(componentName);
    if (target == null) {
        return null;
    }
    java.util.Set<String> targetSlotNames = slotNamesOf(target);
    return renderPropSlotNames(target, targetSlotNames).contains(paramName);
}
```

Em `emitComponentCall` (linha 272-306), calcular e passar o resultado
a `emitSlotFillContent`:

```java
for (var entry : grouped.entrySet()) {
    if (!first) out.append(", ");
    java.util.List<Statement.SlotFill> fills = entry.getValue();
    boolean wrapAsList = fills.size() > 1
        || resolveSlotCardinality(call.componentName(), entry.getKey()) == Cardinality.MANY;
    Boolean isRenderProp = resolveSlotIsRenderProp(call.componentName(), entry.getKey());
    if (!wrapAsList) {
        out.append(entry.getKey()).append(" = ");
        emitSlotFillContent(fills.get(0), out, slotNames, isRenderProp);
    } else {
        out.append(entry.getKey()).append(" = java.util.List.of(");
        for (int i = 0; i < fills.size(); i++) {
            if (i > 0) out.append(", ");
            emitSlotFillContent(fills.get(i), out, slotNames, isRenderProp);
        }
        out.append(")");
    }
    first = false;
}
```

E `emitSlotFillContent` (linha 324-340) passa a:

```java
/** Quando isRenderProp é null (componente-alvo desconhecido nesta
 * emissão — ver comentário de resolveSlotCardinality), o melhor
 * heurístico disponível sem tabela de símbolos é olhar para o próprio
 * ponto de chamada: se o .sk escreveu "nome -> ..." (lambdaParamName
 * presente), o autor claramente pretendia um render-prop. */
private void emitSlotFillContent(Statement.SlotFill slotFill, StringBuilder out,
        java.util.Set<String> slotNames, Boolean isRenderProp) {
    boolean renderProp = isRenderProp != null ? isRenderProp : slotFill.lambdaParamName().isPresent();
    if (renderProp) {
        String lambdaParam = slotFill.lambdaParamName().orElse("__ignored");
        out.append(lambdaParam).append(" -> @`");
    } else {
        out.append("@`");
    }
    for (Statement statement : slotFill.body()) {
        emitStatement(statement, out, slotNames);
    }
    out.append('`');
}
```

- [ ] **Step 6: Correr a suite completa e confirmar GREEN**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, incluindo `JteEmitterGoldenFileTest`,
`SukoEndToEndTest` e `JteEmitterSourceMapTest` (nenhum deles depende de
slots — confirmar que continuam a passar sem alteração, não só os
testes de `JteEmitterTest`). Se algum golden-file falhar
inesperadamente, ler o diff exato antes de assumir que é preciso
regenerar — pode ser sinal de uma regressão real.

- [ ] **Step 7: Atualizar `ARCHITECTURE.md`**

No parágrafo "Forma concreta escolhida (tarefa 18 do subprojeto 1...)"
(secção de decisões de design, sobre slots), substituir a descrição de
"todo slot<T> é emitido uniformemente como Function..." pela forma
nova: slots invocados como função dentro do corpo do componente
declarante são `Function<T, Content>` (render-prop); os restantes são
`Content`/`List<Content>` nu. Explicar que a deteção é sintática
(scan do corpo por `CallExpr` sobre o próprio nome do slot), não uma
declaração explícita do autor do `.sk`. No parágrafo "Ler um slot
exige chamá-lo" (limitações conhecidas), remover ou reduzir a um caso
só aplicável a slots render-prop de facto (a limitação de
`{slot ?: "fallback"}` continua válida para AMBOS os casos — Content
vs String no `?:` continua sem supertipo comum — manter essa frase).

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java ARCHITECTURE.md
git commit -m "$(cat <<'EOF'
fix(emitter): slot simples volta a Content nu, render-prop só quando invocado

Reverte o desvio da tarefa 18 (todo slot<T> virava Function<T,Content>
incondicionalmente), que contradizia o próprio spec do subprojeto 1
(SlotParam sem render-prop -> Content; com render-prop -> Function).
Um slot passa a ser render-prop se e só se o corpo do componente que o
declara o invoca como função (CallExpr sobre o próprio nome do slot);
caso contrário emite Content/List<Content> nu, legível como {header}
sem `.apply(null)`.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: `var` deixa de rebentar o compilador

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java` (adicionar `VarDecl` ao `sealed interface`)
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java` (visitor de `templateStatement`, remover o `throw` para `varDecl`)
- Modify: `src/main/java/io/suko/lang/JteEmitter.java` (`emitStatement`, novo `case Statement.VarDecl`)
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java` (novo teste `rendersVarDecl`)

**Interfaces:**
- Consumes: `SukoParser.VarDeclContext` (gerado a partir de `varDecl: VAR Identifier EQ expression SEMI`, já existe, inalterado nesta tarefa), `buildExpr(ExpressionContext)` (método já existente de `SukoAstBuilder`), `spanOf(ParserRuleContext)` (idem).
- Produces: `Statement.VarDecl(String name, Expr value, SourceSpan span)` — novo tipo, usado só dentro de `component.body()`/blocos de `if`/`for`/`switch`, nenhuma tarefa futura fora deste plano depende dele ainda.

- [ ] **Step 1: Localizar o ponto exato do `throw` a substituir**

Em `SukoAstBuilder.java`, encontrar o método que despacha
`templateStatement` (procurar
`"templateStatement ainda não suportado"`). Ler as ~15 linhas à volta
para confirmar a forma do `switch`/`if-else` existente antes de editar
— não adivinhar a partir da descrição deste plano.

- [ ] **Step 2: Escrever o teste primeiro (RED)**

Adicionar a `JteEmitterTest.java`:

```java
@Test
void rendersVarDecl() throws Exception {
    String source = """
        component Greeting(String name) {
          var upper = name.toUpperCase();
          <p>{upper}</p>
        }
        """;

    String html = JteRenderSupport.renderWithDependencies(source, "Greeting", Map.of("name", "ana"));

    assertTrue(html.contains("<p>ANA</p>"));
}
```

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersVarDecl" --console=plain`
Expected: FAIL — hoje lança `IllegalStateException("templateStatement ainda não suportado")` durante o `SukoAstBuilder`, antes sequer de chegar ao emitter.

- [ ] **Step 3: Adicionar `Statement.VarDecl` ao AST**

Em `Statement.java`, mudar a lista `permits` (linha 5-6) para incluir
`Statement.VarDecl`, e adicionar o record:

```java
record VarDecl(String name, Expr value, SourceSpan span) implements Statement {
}
```

- [ ] **Step 4: Construir `VarDecl` no `SukoAstBuilder`**

No ponto localizado no Step 1, substituir o `throw` (só para o caso
`varDecl`) por:

```java
if (ctx.varDecl() != null) {
    SukoParser.VarDeclContext varDeclCtx = ctx.varDecl();
    return new Statement.VarDecl(
        varDeclCtx.Identifier().getText(),
        buildExpr(varDeclCtx.expression()),
        spanOf(varDeclCtx));
}
```

Ajustar a forma exata (nomes de método/variável do contexto ANTLR) ao
padrão real encontrado no Step 1 — a gramática (`varDecl: VAR Identifier EQ expression SEMI`)
garante que `ctx.varDecl().Identifier()` e `ctx.varDecl().expression()`
existem com esses nomes de acessor.

- [ ] **Step 5: Correr o teste de novo e confirmar novo ponto de falha**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersVarDecl" --console=plain`
Expected: FAIL, mas agora dentro de `JteEmitter.emitStatement` (`switch`
não exaustivo — `Statement.VarDecl` não tratado — erro de compilação
Java do próprio projeto, não do `.jte` gerado). Se a falha for outra
(ex.: ainda no `SukoAstBuilder`), voltar ao Step 4 antes de avançar.

- [ ] **Step 6: Probar a forma exata do `.jte` gerado contra o `gg.jte` real**

Antes de escrever o `case` definitivo, escrever um pequeno probe (pode
ser um teste JUnit temporário, apagado depois deste step) que gera à
mão duas ou três formas candidatas de `.jte` para uma declaração local
e as compila/renderiza via `JteRenderSupport`/`TemplateEngine`
diretamente — candidatos a testar, nesta ordem:
1. `!{var upper = name.toUpperCase();}` (bloco de código gg.jte)
2. `!{ upper = name.toUpperCase(); }` com declaração antecipada noutro sítio (rejeitar se exigir mudança estrutural maior)

Confirmar qual compila e renderiza corretamente com o motor `gg.jte`
3.1.12 real (não assumir a partir de documentação ou memória — ver
Global Constraints). Registar o texto exato do erro de qualquer
candidato que falhe, e qual funcionou, num comentário no código do
`case` final (Step 7) — mesmo padrão dos "DESVIO DO BRIEF" já usados
em todo o `JteEmitter`. Se nenhum candidato óbvio funcionar, consultar
o agente `jte-specialist` antes de inventar uma sintaxe nova.

- [ ] **Step 7: Implementar `case Statement.VarDecl` em `JteEmitter.emitStatement`**

Usar a forma confirmada no Step 6. Formato esperado (ajustar ao
resultado real do probe):

```java
case Statement.VarDecl varDecl ->
    out.append("!{var ").append(varDecl.name()).append(" = ")
        .append(emitExpr(varDecl.value(), slotNames)).append(";}\n");
```

- [ ] **Step 8: Correr o teste e confirmar GREEN**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersVarDecl" --console=plain`
Expected: PASS, `html` contém `<p>ANA</p>`.

- [ ] **Step 9: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "$(cat <<'EOF'
feat(ast,emitter): implementa var (Statement.VarDecl)

var x = expr; fazia parse desde o subprojeto 1 mas o SukoAstBuilder
lançava IllegalStateException para qualquer templateStatement do tipo
varDecl. Adiciona Statement.VarDecl ao AST e a emissão correspondente
no JteEmitter, verificada por probe direto contra o gg.jte 3.1.12 real.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `examples/Card.sk` deixa de ser genérico

**Files:**
- Modify: `examples/Card.sk`
- Create: `examples/invalid/Card.sk`
- Modify: `src/test/resources/golden/Card.jte`
- Modify: `src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java:11-21` (javadoc da classe)
- Modify: `src/test/java/io/suko/lang/SukoEndToEndTest.java:13-24` (javadoc da classe, hoje explica por que `Card<T>` não renderiza — deixa de ser verdade) e adicionar um teste novo que renderiza `Card` diretamente de `examples/Card.sk`
- Modify: `ARCHITECTURE.md` (frase sobre `examples/Card.sk` ser "hoje um programa que o verificador do subprojeto 2 terá de rejeitar", adicionada pelo `architect`)

**Interfaces:**
- Consumes: Tasks 1 e 2 deste plano devem estar mergeadas antes desta (o novo `Card.sk` não usa slots nem `var`, mas o golden regenerado tem de refletir o `JteEmitter` já corrigido pela Task 1 — gerar o golden depois, não antes).
- Produces: nenhuma interface nova — só dados de exemplo/teste.

- [ ] **Step 1: Reescrever `examples/Card.sk`**

Substituir a declaração genérica:

```
component Card<T>(String title, List<T> items, String emptyLabel = "Sem itens") {
  <div class="card">
    <h2>{title}</h2>
    if (items.size() > 0) {
      <ul>
        for (T item : items) {
          <li>{item.name} — {item.price?.format() ?: "sem preço"}</li>
        }
      </ul>
    } else {
      <p>{emptyLabel}</p>
    }
  </div>
}
```

por uma versão concreta com `String` como tipo do item (sem membros
`.name`/`.price` — `String` não os tem):

```
component Card(String title, List<String> items, String emptyLabel = "Sem itens") {
  <div class="card">
    <h2>{title}</h2>
    if (items.size() > 0) {
      <ul>
        for (String item : items) {
          <li>{item}</li>
        }
      </ul>
    } else {
      <p>{emptyLabel}</p>
    }
  </div>
}
```

Não tocar em `NavLink` nem `Page` no mesmo ficheiro — ficam como
estão.

- [ ] **Step 2: Criar `examples/invalid/Card.sk`**

Novo ficheiro, com o `component Card<T>(...)` genérico ORIGINAL
(copiado tal e qual do texto do Step 1, antes da edição), como único
componente do ficheiro, precedido por este comentário:

```
// Exemplo de programa REJEITADO a partir do subprojeto 2 (Verificador
// Suko): componentes genéricos fazem parse mas não renderizam sob
// gg.jte — ver ARCHITECTURE.md, "Limitações conhecidas". Mantido aqui
// como fixture do que o verificador deve sinalizar como "ainda não
// suportado", não como exemplo funcional.
component Card<T>(String title, List<T> items, String emptyLabel = "Sem itens") {
  <div class="card">
    <h2>{title}</h2>
    if (items.size() > 0) {
      <ul>
        for (T item : items) {
          <li>{item.name} — {item.price?.format() ?: "sem preço"}</li>
        }
      </ul>
    } else {
      <p>{emptyLabel}</p>
    }
  </div>
}
```

- [ ] **Step 3: Regenerar `src/test/resources/golden/Card.jte`**

Correr `JteEmitterGoldenFileTest.cardMatchesGoldenFile` (vai falhar —
o golden antigo ainda tem o `Card<T>` genérico). Capturar o `actual`
que o teste calcula (`JteRenderSupport.compileToJte(sukoSource, "Card")`
sobre o novo `examples/Card.sk`) — por exemplo, adicionando
temporariamente `System.out.println(actual)` ao teste, correndo-o, e
copiando a saída exata para `src/test/resources/golden/Card.jte`, ou
escrevendo um pequeno `main`/teste utilitário que grava o resultado
diretamente no ficheiro. Remover qualquer código temporário de captura
antes do commit.

- [ ] **Step 4: Correr os dois testes de golden-file**

Run: `gradle test --tests "io.suko.lang.JteEmitterGoldenFileTest" --console=plain`
Expected: `BUILD SUCCESSFUL` — `cardMatchesGoldenFile` compara contra o
golden regenerado no Step 3; `navLinkMatchesGoldenFile` não deve mudar
(confirma que `NavLink`, inalterado, continua a produzir o mesmo
`.jte`).

- [ ] **Step 5: Atualizar o javadoc de `JteEmitterGoldenFileTest`**

Linhas 17-21: remover a frase "O golden de 'Card' cobre o componente
genérico Card<T> real (nunca compilado/renderizado via gg.jte...)" —
deixou de ser verdade. Substituir por algo como: "O golden de 'Card'
cobre o componente concreto (não genérico) em examples/Card.sk — a
versão genérica original vive em examples/invalid/Card.sk, como
fixture do subprojeto 2."

- [ ] **Step 5b: Atualizar `SukoEndToEndTest` e adicionar render real de `Card`**

O javadoc da classe (linhas 13-24) explica hoje por que `Card<T>` não
é renderizado — deixa de ser verdade assim que `Card` for concreto.
Reescrever essas linhas para refletir que `Card` (agora concreto) É
renderizado diretamente a partir de `examples/Card.sk`, e que
`examples/invalid/Card.sk` preserva o caso genérico não-renderizável
(mesma nota do `JteEmitterGoldenFileTest`). Adicionar um novo teste
que exercita isto de verdade (não só o golden-file de texto):

```java
@Test
void rendersCardFromExampleFile() throws Exception {
    String source = Files.readString(Path.of("examples/Card.sk"));

    String html = JteRenderSupport.renderWithDependencies(source, "Card", Map.of(
        "title", "Produtos",
        "items", List.of("Café", "Chá"),
        "emptyLabel", "Sem itens"));

    assertTrue(html.contains("Produtos"));
    assertTrue(html.contains("Café"));
    assertTrue(html.contains("Chá"));
}
```

- [ ] **Step 6: Atualizar `ARCHITECTURE.md`**

Localizar a frase (adicionada pelo `architect` na revisão pós-
subprojeto-1) que diz que `examples/Card.sk` "é hoje um programa que o
verificador do subprojeto 2 terá de rejeitar" e ajustá-la: `Card.sk` é
agora concreto e válido; é `examples/invalid/Card.sk` que documenta o
caso genérico rejeitado.

- [ ] **Step 7: Correr a suite completa**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`. Prestar atenção especial a
`SukoParserSmokeTest`/`SukoGrammarFixesTest` se algum deles ler
`examples/Card.sk` diretamente (grep antes de assumir que não afeta
nada).

- [ ] **Step 8: Commit**

```bash
git add examples/Card.sk examples/invalid/Card.sk src/test/resources/golden/Card.jte src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java src/test/java/io/suko/lang/SukoEndToEndTest.java ARCHITECTURE.md
git commit -m "$(cat <<'EOF'
docs(examples): Card.sk deixa de ser genérico; caso genérico move para examples/invalid/

O verificador do subprojeto 2 vai rejeitar componentes genéricos
(decisão de roadmap já tomada). Mantinha o exemplo-bandeira do projeto
como um programa inválido no dia em que o subprojeto 2 começasse a
correr sobre ele. Card.sk passa a usar um tipo concreto (String);
examples/invalid/Card.sk preserva o Card<T> original como fixture do
que o verificador deve rejeitar.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
