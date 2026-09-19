# Suko — Subprojeto 5: Projeto Multi-Ficheiro — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Resolver nomes de componente entre ficheiros `.sk` — `package`/`import` (já existentes na gramática, nunca usados) passam a ser ligados a uma resolução real, com visibilidade `public`/file-private, e um `SukoProjectCompiler` novo (duas fases: index + compile) substitui a compilação isolada por-ficheiro no build Gradle/Maven.

**Architecture:** `ProjectIndex` (pacote novo `io.suko.lang.project`) faz a Fase 1 (scan recursivo, parse leve, tabela `pacote.Componente → (ficheiro, público?, nº params)`). `JteCompiler` ganha um segundo método `compile(ProjectIndex, Path)` que injeta essa tabela em `SemanticChecker`/`JteEmitter` via novos construtores opcionais (os construtores de hoje continuam a existir e a funcionar sem projeto). `SukoProjectCompiler` orquestra a Fase 1 + N chamadas à Fase 2, escrevendo cada `.jte` gerado numa subpasta que espelha o pacote. `SukoCompileTask` (Gradle) e `SukoCompileMojo` (Maven) passam a chamar `SukoProjectCompiler` em vez de `JteCompiler` por ficheiro solto.

**Tech Stack:** Java 21, ANTLR4 4.13.1, gg.jte / jte-runtime 3.1.12, JUnit 5, Gradle, Maven (plugin `suko-maven-plugin`).

**Spec:** `docs/superpowers/specs/2026-09-19-suko-projeto-multificheiro.md`

## Global Constraints

- O projeto NÃO tem `gradlew` commitado — todos os comandos usam `gradle` (sistema), nunca `./gradlew`.
- Depois de qualquer alteração a `SukoLexer.g4`/`SukoParser.g4`, correr `gradle generateSukoLexer generateSukoParser --console=plain` e confirmar que a saída não contém a palavra `warning`.
- Testes de render usam `JteRenderSupport.render`/`renderWithDependencies`/`renderProject` (motor `gg.jte` real, não simulado) sempre que o teste depender de código Java gerado — nunca validar só compilação do `.jte`.
- Qualquer desvio descoberto durante a implementação é documentado no código, no ponto exato da descoberta, com o texto exato do comportamento observado — convenção obrigatória do projeto.
- Git dentro de worktrees deste harness tem um bug intermitente do sandbox (classificador "rtk") em `git status`/`git diff`/`git add` bare — o workaround é invocar sempre via caminho absoluto `/usr/bin/git <subcomando>`. Aplica-se a todos os passos de commit deste plano.
- Nenhum construtor/API pública já existente perde compatibilidade — toda a extensão para "consciência de projeto" é feita por **overloads novos**, nunca por mudança de assinatura de um método já existente (ver Tarefas 4/6).

---

## Descobertas feitas ao escrever este plano (documentadas aqui, não na spec — são detalhe de implementação, não decisão de design)

- `JteEmitter.java:584-596` já tem um comentário do subprojeto 6 prevendo exatamente esta tarefa: *"Se uma futura tarefa cross-file precisar de [resolução por `callResolver`], deve introduzir o campo nesse ponto, atualizando ambos os locais."* A Tarefa 5 implementa isso.
- `SukoAstBuilder.java:168-170` também já tem um comentário do subprojeto 1 antecipando isto: *"fica para a análise semântica (subprojetos 2-3) rejeitar nomes de chamada compostos, ou para o emitter aprender a resolver sub-pacotes, se isso vier a ser suportado."*
- `Statement.ComponentCallStmt.componentName()` já é o texto completo de `qualifiedName` (gramática: `componentCall: qualifiedName typeArguments? LPAREN argList? RPAREN slotBlock?;`) — ou seja, uma chamada `ui.NavLink(...)` já chega ao emitter/verificador como a string literal `"ui.NavLink"`, sem trabalho de gramática adicional. Isto simplifica toda a resolução: não há nó de AST novo a criar para nomes compostos em posição de **statement**.
- Em posição de **expressão** (componente como valor, subprojeto 6), o mecanismo só reconhece um callee `Expr.PrimaryExpr` (nome simples) — um `CallExpr` cujo callee é composto (`ui.NavLink()` como valor) usaria `Expr.AccessExpr`, que nenhum `case` de `emitExpr`/`checkExprForComponentCalls` trata hoje. **Fora de âmbito deste plano**: só a forma statement (`ComponentCallStmt`) resolve nomes compostos entre ficheiros; a forma valor/expressão só resolve nomes curtos pós-import (`Expr.PrimaryExpr`). Registado como limitação conhecida na Tarefa 9.
- `SymbolTable` (subprojeto 2) é por-ficheiro e chaveada só por nome simples — não dá para reaproveitar para o índice de projeto (não tem noção de pacote/visibilidade). `ProjectIndex` é uma estrutura nova e independente, não uma extensão de `SymbolTable`.
- A Fase 1 (`ProjectIndex.build`) só indexa a **assinatura** de cada componente (nome, pacote, `public`, nº de parâmetros) — não indexa slots. Consequência aceite: quando um `ComponentCallStmt` resolve contra o `ProjectIndex` (alvo noutro ficheiro), a verificação de slot fills desse alvo (`SLOT_NOT_FOUND`/`CARDINALITY_VIOLATION`) **não corre** — só existência e visibilidade são verificadas entre ficheiros. Verificação de slot fills cross-ficheiro fica para uma extensão futura (exigiria a Fase 1 indexar params inteiros, não só a contagem). Registado como limitação conhecida na Tarefa 9.
- `SukoGradlePluginTest.java` (existente) **não testa `SukoCompileTask` diretamente** — só testa `JteCompiler` isolado (nome do ficheiro é enganoso). Não há infraestrutura de teste de Gradle `Task`/`Project` neste repo (`ProjectBuilder` nunca foi usado). A Tarefa 7 não introduz essa infraestrutura (fora de âmbito) — a integração do lado Gradle é coberta por revisão de código + pelos testes de `SukoProjectCompiler` (Tarefa 6) + pelo teste de `SukoCompileMojo` (Maven, mais fácil de testar por ser um POJO simples, sem acoplamento a `Project`).

---

### Task 1: Gramática — keyword `public` opcional em `componentDecl`

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4`
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Test: `src/test/java/io/suko/lang/SukoParserSmokeTest.java`

**Interfaces:**
- Produces: token `PUBLIC`; `SukoParser.ComponentDeclContext.PUBLIC()` (nulo quando ausente) — a Tarefa 2 lê isto.

- [ ] **Step 1: Escrever o teste que falha**

Adicionar a `src/test/java/io/suko/lang/SukoParserSmokeTest.java`:

```java
    @Test
    void parsesPublicAndDefaultVisibilityComponentsWithoutErrors() {
        String source = """
            public component NavLink(String href) {
              <a href="{href}">link</a>
            }
            component Helper() {
              <span>x</span>
            }
            """;
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> {
            var lexer = new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(source));
            var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
            parser.setErrorHandler(new org.antlr.v4.runtime.BailErrorStrategy());
            SukoParser.CompilationUnitContext tree = parser.compilationUnit();
            assertEquals(2, tree.componentDecl().size());
            assertTrue(tree.componentDecl(0).PUBLIC() != null, "primeiro componente devia ter modificador public");
            assertTrue(tree.componentDecl(1).PUBLIC() == null, "segundo componente não devia ter modificador public");
        });
    }
```

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle test --tests "io.suko.lang.SukoParserSmokeTest.parsesPublicAndDefaultVisibilityComponentsWithoutErrors" --console=plain`
Expected: FALHA — `public` não é palavra-chave hoje, o `BailErrorStrategy` lança `ParseCancellationException` na primeira linha (o parser vê `Identifier("public") Identifier("component")` sem produção que aceite isso no início de `compilationUnit`).

- [ ] **Step 3: Adicionar o token `PUBLIC` ao lexer**

Em `src/main/antlr/io/suko/lang/SukoLexer.g4`, logo a seguir a `AS : 'as';`:

```antlr
AS        : 'as';
PUBLIC    : 'public';
```

- [ ] **Step 4: Tornar `componentDecl` aceitar o modificador**

Em `src/main/antlr/io/suko/lang/SukoParser.g4`, mudar:

```antlr
componentDecl
    : COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN templateBlock
    ;
```

para:

```antlr
componentDecl
    : PUBLIC? COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN templateBlock
    ;
```

- [ ] **Step 5: Regenerar o lexer/parser e confirmar ausência de warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sucesso, saída sem a palavra `warning`.

- [ ] **Step 6: Rodar o teste, confirmar que passa**

Run: `gradle test --tests "io.suko.lang.SukoParserSmokeTest.parsesPublicAndDefaultVisibilityComponentsWithoutErrors" --console=plain`
Expected: PASS

- [ ] **Step 7: Rodar toda a suite (garante que nenhum `.sk`/teste existente usa `public` como identificador de componente/param, o que agora seria um erro de sintaxe)**

Run: `gradle test --console=plain`
Expected: verde, mesma contagem de testes de antes + 1.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add src/main/antlr/io/suko/lang/SukoLexer.g4 src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoParserSmokeTest.java
/usr/bin/git commit -m "feat(gramatica): keyword public opcional em componentDecl (subprojeto 5)"
```

---

### Task 2: AST — `ComponentDecl.isPublic`, `ast.ImportDecl`, `SukoAstBuilder`

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/ComponentDecl.java`
- Create: `src/main/java/io/suko/lang/ast/ImportDecl.java`
- Modify: `src/main/java/io/suko/lang/ast/SukoFile.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify (só para compilar — 21 sites, ver Step 5): `src/test/java/io/suko/lang/JavacTaskTest.java`, `src/test/java/io/suko/lang/SemanticCheckerTest.java`, `src/test/java/io/suko/lang/SymbolTableTest.java`
- Test: `src/test/java/io/suko/lang/SukoAstBuilderVisibilityImportTest.java` (novo)

**Interfaces:**
- Consumes: `SukoParser.ComponentDeclContext.PUBLIC()` (Tarefa 1).
- Produces: `ComponentDecl(String name, List<String> typeParameters, List<Param> params, List<Statement> body, SourceSpan span, boolean isPublic)` — **`isPublic` é o último campo**; `ImportDecl(String qualifiedName, Optional<String> alias, SourceSpan span)`; `SukoFile.imports()` passa a devolver `List<ImportDecl>` (antes `List<String>`). Tarefas 3, 4, 5, 6 dependem destes tipos exatos.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/io/suko/lang/SukoAstBuilderVisibilityImportTest.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SukoAstBuilderVisibilityImportTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void readsPublicModifierAndDefaultsToFalse() {
        SukoFile file = parse("""
            public component NavLink() {
              <a>x</a>
            }
            component Helper() {
              <span>x</span>
            }
            """);

        ComponentDecl navLink = file.components().get(0);
        ComponentDecl helper = file.components().get(1);

        assertTrue(navLink.isPublic(), "NavLink foi declarado public");
        assertFalse(helper.isPublic(), "Helper não tem modificador — devia ser file-private por omissão");
    }

    @Test
    void readsImportsWithAndWithoutAlias() {
        SukoFile file = parse("""
            package app;

            import ui.NavLink;
            import ui.Badge as Pill;

            component Home() {
              <div>x</div>
            }
            """);

        assertEquals("app", file.packageName().orElseThrow());
        assertEquals(2, file.imports().size());

        ImportDecl first = file.imports().get(0);
        assertEquals("ui.NavLink", first.qualifiedName());
        assertTrue(first.alias().isEmpty());

        ImportDecl second = file.imports().get(1);
        assertEquals("ui.Badge", second.qualifiedName());
        assertEquals("Pill", second.alias().orElseThrow());
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle compileTestJava --console=plain`
Expected: FALHA a compilar — `ComponentDecl.isPublic()` e `io.suko.lang.ast.ImportDecl` ainda não existem.

- [ ] **Step 3: `ComponentDecl` ganha `isPublic` como último campo**

Editar `src/main/java/io/suko/lang/ast/ComponentDecl.java`:

```java
package io.suko.lang.ast;

import java.util.List;

public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span, boolean isPublic) {
}
```

- [ ] **Step 4: Criar `ImportDecl` e mudar `SukoFile.imports()`**

Criar `src/main/java/io/suko/lang/ast/ImportDecl.java`:

```java
package io.suko.lang.ast;

import java.util.Optional;

public record ImportDecl(String qualifiedName, Optional<String> alias, SourceSpan span) {
}
```

Editar `src/main/java/io/suko/lang/ast/SukoFile.java`:

```java
package io.suko.lang.ast;

import java.util.List;
import java.util.Optional;

public record SukoFile(Optional<String> packageName, List<ImportDecl> imports, List<ComponentDecl> components) {
}
```

- [ ] **Step 5: Corrigir os 21 call sites existentes de `new ComponentDecl(...)` que quebraram**

`isPublic` é um campo novo no fim do record — todo construtor posicional existente perde um argumento. Rodar `gradle compileTestJava --console=plain` lista o erro em cada um destes sites; adicionar `, false` logo a seguir ao último argumento (`span`) em cada um (nenhum teste existente antes desta tarefa se importa com visibilidade, `false`/file-private preserva o comportamento anterior):

- `src/test/java/io/suko/lang/JavacTaskTest.java` — linhas 22, 47, 56, 97
- `src/test/java/io/suko/lang/SemanticCheckerTest.java` — linhas 38, 45, 49, 73, 97, 101, 125, 129, 151, 155, 178, 183, 209, 231
- `src/test/java/io/suko/lang/SymbolTableTest.java` — linhas 19, 34, 36

- [ ] **Step 6: Atualizar `SukoAstBuilder`**

Em `src/main/java/io/suko/lang/SukoAstBuilder.java`, mudar o corpo de `build`:

```java
    public SukoFile build(SukoParser.CompilationUnitContext ctx) {
        Optional<String> packageName = ctx.packageDecl() == null
            ? Optional.empty()
            : Optional.of(ctx.packageDecl().qualifiedName().getText());

        List<ImportDecl> imports = new ArrayList<>();
        for (SukoParser.ImportDeclContext importCtx : ctx.importDecl()) {
            Optional<String> alias = importCtx.Identifier() == null
                ? Optional.empty()
                : Optional.of(importCtx.Identifier().getText());
            imports.add(new ImportDecl(importCtx.qualifiedName().getText(), alias, spanOf(importCtx)));
        }

        List<ComponentDecl> components = new ArrayList<>();
        for (SukoParser.ComponentDeclContext componentCtx : ctx.componentDecl()) {
            components.add(buildComponent(componentCtx));
        }

        return new SukoFile(packageName, imports, components);
    }
```

e o fim de `buildComponent`:

```java
        List<Statement> body = buildStatements(ctx.templateBlock().templateStatement());

        return new ComponentDecl(ctx.Identifier().getText(), typeParameters, params, body, spanOf(ctx),
            ctx.PUBLIC() != null);
    }
```

(`spanOf` já é `private`, `buildComponent` já está na mesma classe — nenhuma mudança de visibilidade necessária. `import io.suko.lang.ast.ImportDecl;` não é preciso — `SukoAstBuilder.java` já importa `io.suko.lang.ast.*`.)

- [ ] **Step 7: Rodar o teste novo, confirmar que passa**

Run: `gradle test --tests "io.suko.lang.SukoAstBuilderVisibilityImportTest" --console=plain`
Expected: PASS

- [ ] **Step 8: Rodar toda a suite**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 9: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/ast/ComponentDecl.java src/main/java/io/suko/lang/ast/ImportDecl.java src/main/java/io/suko/lang/ast/SukoFile.java src/main/java/io/suko/lang/SukoAstBuilder.java src/test/java/io/suko/lang/SukoAstBuilderVisibilityImportTest.java src/test/java/io/suko/lang/JavacTaskTest.java src/test/java/io/suko/lang/SemanticCheckerTest.java src/test/java/io/suko/lang/SymbolTableTest.java
/usr/bin/git commit -m "feat(ast): ComponentDecl.isPublic e ImportDecl com alias (subprojeto 5)"
```

---

### Task 3: `ProjectIndex` — Fase 1 (scan recursivo + índice de nomes)

**Files:**
- Create: `src/main/java/io/suko/lang/project/ProjectIndexEntry.java`
- Create: `src/main/java/io/suko/lang/project/ProjectIndex.java`
- Test: `src/test/java/io/suko/lang/project/ProjectIndexTest.java`

**Interfaces:**
- Consumes: `ComponentDecl.isPublic()`, `SukoFile.packageName()`, `SukoFile.imports()` (Tarefa 2).
- Produces: `ProjectIndexEntry(String qualifiedName, String simpleName, Path sourceFile, boolean isPublic, int paramCount)`; `ProjectIndex.build(Path sourceRoot)`; `ProjectIndex.resolveQualified(String) -> Optional<ProjectIndexEntry>`; `ProjectIndex.resolveImports(List<ImportDecl>) -> Map<String, ProjectIndexEntry>`; `ProjectIndex.packageToRelativeDir(String) -> Path` (estático). Tarefas 4, 5, 6 consomem todos estes.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/io/suko/lang/project/ProjectIndexTest.java`:

```java
package io.suko.lang.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexTest {

    @Test
    void indexesPublicAndPrivateComponentsAcrossPackages(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="{href}">link</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            component Home() {
              <div>x</div>
            }
            """);

        ProjectIndex index = ProjectIndex.build(sourceRoot);

        ProjectIndexEntry navLink = index.resolveQualified("ui.NavLink").orElseThrow();
        assertEquals("NavLink", navLink.simpleName());
        assertTrue(navLink.isPublic());
        assertEquals(1, navLink.paramCount());
        assertEquals(uiDir.resolve("NavLink.sk"), navLink.sourceFile());

        assertTrue(index.resolveQualified("Home").isPresent(), "componente sem package fica no pacote raiz");
        assertTrue(index.resolveQualified("ui.DoesNotExist").isEmpty());
    }

    @Test
    void resolveImportsBuildsShortNameAndAliasMap(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            public component Badge(String text) {
              <span>{text}</span>
            }
            """);

        ProjectIndex index = ProjectIndex.build(sourceRoot);

        io.suko.lang.ast.ImportDecl aliased = new io.suko.lang.ast.ImportDecl(
            "ui.Badge", java.util.Optional.of("Pill"), new io.suko.lang.ast.SourceSpan(0, 0, 0, 0));

        Map<String, ProjectIndexEntry> resolved = index.resolveImports(java.util.List.of(aliased));

        assertEquals("ui.Badge", resolved.get("Pill").qualifiedName());
        assertNull(resolved.get("Badge"), "sem alias explícito, a chave é sempre o alias — não o nome curto também");
    }

    @Test
    void packageToRelativeDirMapsDotsToPathSegments() {
        assertEquals(Path.of("foo", "bar"), ProjectIndex.packageToRelativeDir("foo.bar"));
        assertEquals(Path.of("ui"), ProjectIndex.packageToRelativeDir("ui"));
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle compileTestJava --console=plain`
Expected: FALHA — `io.suko.lang.project.ProjectIndex`/`ProjectIndexEntry` ainda não existem.

- [ ] **Step 3: Criar `ProjectIndexEntry`**

Criar `src/main/java/io/suko/lang/project/ProjectIndexEntry.java`:

```java
package io.suko.lang.project;

import java.nio.file.Path;

public record ProjectIndexEntry(
    String qualifiedName,
    String simpleName,
    Path sourceFile,
    boolean isPublic,
    int paramCount
) {
}
```

- [ ] **Step 4: Criar `ProjectIndex`**

Criar `src/main/java/io/suko/lang/project/ProjectIndex.java`:

```java
package io.suko.lang.project;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fase 1 (indexação) do SukoProjectCompiler: varre um sourceRoot
 * recursivamente e regista a assinatura pública de cada componente —
 * nunca corre SemanticChecker/JteEmitter aqui. Isto resolve referências
 * entre ficheiros em qualquer ordem, incluindo ciclos (A chama B e B
 * chama A é legítimo — gg.jte resolve @template.x(...) em tempo de
 * render, não em tempo de compilação Suko).
 *
 * LIMITAÇÃO ACEITE (descoberta ao escrever este plano): só a assinatura
 * superficial é indexada (nome, pacote, public, nº de params) — não os
 * slots. Um ComponentCallStmt que resolve contra este índice (alvo
 * noutro ficheiro) não tem verificação de slot fills entre ficheiros;
 * só existência/visibilidade. Ver Tarefa 4.
 */
public class ProjectIndex {

    private final Map<String, ProjectIndexEntry> byQualifiedName = new LinkedHashMap<>();

    private ProjectIndex() {
    }

    public static ProjectIndex build(Path sourceRoot) {
        ProjectIndex index = new ProjectIndex();

        List<Path> skFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            skFiles = walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Path skFile : skFiles) {
            // NOTA (descoberta ao escrever este plano): esta fase usa o parser
            // ANTLR por omissão (sem BailErrorStrategy nem SukoErrorListener) —
            // recupera de erros de sintaxe "best effort" e pode produzir um AST
            // parcial para um .sk malformado. Aceite: a Fase 1 só existe para
            // descobrir nomes; o erro de sintaxe real é reportado pela Fase 2
            // (JteCompiler, que já tem SukoErrorListener) quando esse ficheiro
            // for efetivamente compilado.
            String source;
            try {
                source = Files.readString(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
            SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
            SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());

            String packagePrefix = file.packageName().map(p -> p + ".").orElse("");
            for (ComponentDecl component : file.components()) {
                String qualifiedName = packagePrefix + component.name();
                index.byQualifiedName.put(qualifiedName, new ProjectIndexEntry(
                    qualifiedName, component.name(), skFile, component.isPublic(), component.params().size()));
            }
        }

        return index;
    }

    public Optional<ProjectIndexEntry> resolveQualified(String qualifiedName) {
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    /** Resolve os imports de um ficheiro contra este índice: chave = alias
     * quando existe, senão o nome curto do alvo. Imports que não resolvem
     * (alvo inexistente) são simplesmente omitidos aqui — a Tarefa 4
     * (SemanticChecker) é quem decide reportar IMPORT_NOT_FOUND. */
    public Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports) {
        Map<String, ProjectIndexEntry> byShortName = new LinkedHashMap<>();
        for (ImportDecl imp : imports) {
            resolveQualified(imp.qualifiedName()).ifPresent(entry -> {
                String key = imp.alias().orElse(entry.simpleName());
                byShortName.putIfAbsent(key, entry);
            });
        }
        return byShortName;
    }

    /** "foo.bar" -> "foo/bar" (Path com segmentos, não string) — regra
     * package↔pasta usada tanto na indexação como na verificação. */
    public static Path packageToRelativeDir(String packageName) {
        Path dir = Path.of("");
        for (String segment : packageName.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir;
    }
}
```

- [ ] **Step 5: Rodar o teste, confirmar que passa**

Run: `gradle test --tests "io.suko.lang.project.ProjectIndexTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Rodar toda a suite**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/project/ProjectIndex.java src/main/java/io/suko/lang/project/ProjectIndexEntry.java src/test/java/io/suko/lang/project/ProjectIndexTest.java
/usr/bin/git commit -m "feat(project): ProjectIndex — Fase 1, indexação de nomes de componente (subprojeto 5)"
```

---

### Task 4: `SemanticChecker` — 4 diagnósticos novos, consciente de projeto

**Files:**
- Modify: `src/main/java/io/suko/lang/semantic/SemanticChecker.java`
- Test: `src/test/java/io/suko/lang/semantic/SemanticCheckerProjectTest.java` (novo)

**Interfaces:**
- Consumes: `ProjectIndex` inteiro (Tarefa 3).
- Produces: `SemanticChecker(SymbolTable, DiagnosticCollector, String sourceFile, ProjectIndex, Path fileRelativePath)` — novo construtor de 5 args; o de 3 args existente **não muda** (delega no novo com `null, null`). Diagnósticos: `IMPORT_NOT_FOUND`, `COMPONENT_NOT_VISIBLE`, `PACKAGE_DIRECTORY_MISMATCH`, `AMBIGUOUS_IMPORT`. A Tarefa 6 (`JteCompiler.compile(ProjectIndex, Path)`) consome o construtor novo.

- [ ] **Step 1: Escrever os testes que falham**

Criar `src/test/java/io/suko/lang/semantic/SemanticCheckerProjectTest.java`:

```java
package io.suko.lang.semantic;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.symbol.SymbolTable;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCheckerProjectTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    private static DiagnosticCollector checkAgainstProject(Path sourceRoot, String source, String fileName, Path fileRelativePath) {
        ProjectIndex index = ProjectIndex.build(sourceRoot);
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        new SemanticChecker(new SymbolTable(), diagnostics, fileName, index, fileRelativePath).check(parse(source));
        return diagnostics;
    }

    private void writeUiBadge(Path sourceRoot, boolean isPublic) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), (isPublic ? "public " : "") + """
            package ui;

            component Badge(String text) {
              <span>{text}</span>
            }
            """);
    }

    @Test
    void importNotFoundIsReported(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            import ui.DoesNotExist;

            component Home() {
              <div>x</div>
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "IMPORT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void componentNotVisibleIsReportedForNonPublicImport(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, false);
        String source = """
            import ui.Badge;

            component Home() {
              Badge(text="x")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "COMPONENT_NOT_VISIBLE".equals(d.code())));
    }

    @Test
    void packageDirectoryMismatchIsReported(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            package ui;

            component Wrong() {
              <div>x</div>
            }
            """;
        // ficheiro fisicamente na raiz, mas declara package ui — não bate
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Wrong.sk", Path.of("Wrong.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "PACKAGE_DIRECTORY_MISMATCH".equals(d.code())));
    }

    @Test
    void ambiguousImportIsReportedWhenTwoUnaliasedImportsShareShortName(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        Path otherDir = sourceRoot.resolve("other");
        Files.createDirectories(otherDir);
        Files.writeString(otherDir.resolve("Badge.sk"), """
            package other;

            public component Badge(String text) {
              <em>{text}</em>
            }
            """);

        String source = """
            import ui.Badge;
            import other.Badge;

            component Home() {
              Badge(text="x")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().stream().anyMatch(d -> "AMBIGUOUS_IMPORT".equals(d.code())));
    }

    @Test
    void wellFormedCrossFileCallIsClean(@TempDir Path sourceRoot) throws IOException {
        writeUiBadge(sourceRoot, true);
        String source = """
            import ui.Badge;

            component Home() {
              Badge(text="x")
              ui.Badge(text="y")
            }
            """;
        DiagnosticCollector diagnostics = checkAgainstProject(sourceRoot, source, "Home.sk", Path.of("Home.sk"));
        assertTrue(diagnostics.getDiagnostics().isEmpty(), diagnostics.getDiagnostics().toString());
    }

    @Test
    void threeArgConstructorStillWorksWithoutProjectAwareness() {
        String source = """
            component Home() {
              <div>x</div>
            }
            """;
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        new SemanticChecker(new SymbolTable(), diagnostics, "Home.sk").check(parse(source));
        assertTrue(diagnostics.getDiagnostics().isEmpty());
    }
}
```

- [ ] **Step 2: Rodar os testes, confirmar que falham**

Run: `gradle compileTestJava --console=plain`
Expected: FALHA — o construtor de 5 argumentos ainda não existe.

- [ ] **Step 3: Adicionar campos e o construtor novo**

Em `src/main/java/io/suko/lang/semantic/SemanticChecker.java`, adicionar aos imports:

```java
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;

import java.nio.file.Path;
```

Mudar o topo da classe:

```java
public class SemanticChecker {

    private final SymbolTable symbolTable;
    private final DiagnosticCollector diagnostics;
    private final String sourceFile;
    private final ProjectIndex projectIndex;
    private final Path fileRelativePath;
    private Map<String, ProjectIndexEntry> currentImportedByShortName = Map.of();

    public SemanticChecker(SymbolTable symbolTable, DiagnosticCollector diagnostics, String sourceFile) {
        this(symbolTable, diagnostics, sourceFile, null, null);
    }

    public SemanticChecker(SymbolTable symbolTable, DiagnosticCollector diagnostics, String sourceFile,
                            ProjectIndex projectIndex, Path fileRelativePath) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.sourceFile = sourceFile;
        this.projectIndex = projectIndex;
        this.fileRelativePath = fileRelativePath;
    }
```

- [ ] **Step 4: Estender `check` para validar imports/pacote e construir o mapa de alias**

Mudar `check`:

```java
    public void check(SukoFile sukoFile) {
        registerComponents(sukoFile);
        currentImportedByShortName = projectIndex == null ? Map.of() : checkImportsAndBuildAliasMap(sukoFile);
        if (projectIndex != null) {
            checkPackageDirectoryMismatch(sukoFile);
        }
        for (ComponentDecl component : sukoFile.components()) {
            checkComponent(component);
        }
    }

    private Map<String, ProjectIndexEntry> checkImportsAndBuildAliasMap(SukoFile sukoFile) {
        Map<String, ProjectIndexEntry> byShortName = new HashMap<>();
        for (ImportDecl imp : sukoFile.imports()) {
            var found = projectIndex.resolveQualified(imp.qualifiedName());
            if (found.isEmpty()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import não encontrado: '" + imp.qualifiedName() + "'",
                        "IMPORT_NOT_FOUND",
                        sourceFile,
                        imp.span()
                ));
                continue;
            }
            ProjectIndexEntry entry = found.get();
            if (!entry.isPublic()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + imp.qualifiedName() + "' não é public — não pode ser importado",
                        "COMPONENT_NOT_VISIBLE",
                        sourceFile,
                        imp.span()
                ));
            }
            String key = imp.alias().orElse(entry.simpleName());
            if (imp.alias().isEmpty() && byShortName.containsKey(key)) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import ambíguo: '" + key + "' já foi importado de outro pacote — use 'as' para desambiguar",
                        "AMBIGUOUS_IMPORT",
                        sourceFile,
                        imp.span()
                ));
            } else {
                byShortName.put(key, entry);
            }
        }
        return byShortName;
    }

    private void checkPackageDirectoryMismatch(SukoFile sukoFile) {
        if (sukoFile.packageName().isEmpty()) {
            return;
        }
        Path expectedDir = ProjectIndex.packageToRelativeDir(sukoFile.packageName().get());
        Path actualDir = fileRelativePath.getParent() == null ? Path.of("") : fileRelativePath.getParent();
        if (!expectedDir.equals(actualDir)) {
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "package " + sukoFile.packageName().get() + " não corresponde à pasta do ficheiro ('" + actualDir + "')",
                    "PACKAGE_DIRECTORY_MISMATCH",
                    sourceFile,
                    new SourceSpan(0, 0, 0, 0)
            ));
        }
    }
```

- [ ] **Step 5: Resolver chamadas contra o `ProjectIndex` quando não encontradas localmente**

Mudar `checkComponentCall` — a verificação de `calledComponent == null` passa a tentar o projeto antes de reportar `COMPONENT_NOT_FOUND`:

```java
    private void checkComponentCall(Statement.ComponentCallStmt call, Map<String, Param.SlotParam> currentScopeSlots) {
        ComponentDecl calledComponent = symbolTable.lookup(call.componentName());
        if (calledComponent == null) {
            ProjectIndexEntry resolved = resolveViaProject(call.componentName());
            if (resolved == null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + call.componentName() + "' não encontrado",
                        "COMPONENT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
                return;
            }
            if (!resolved.isPublic()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + call.componentName() + "' não é public",
                        "COMPONENT_NOT_VISIBLE",
                        sourceFile,
                        call.span()
                ));
            }
            // Resolvido via projeto: a Fase 1 só indexa a assinatura (ver
            // ProjectIndex), não os slots — verificação de slot fills
            // cross-ficheiro não é feita aqui (limitação aceite, Tarefa 9).
            return;
        }

        Map<String, Param.SlotParam> calledSlots = new HashMap<>();
        // ... resto do método sem mudanças (verificação de slot fills, cardinalidade, etc.)
```

E adicionar o novo método privado auxiliar, usado tanto por `checkComponentCall` acima como por `checkExprForComponentCalls` abaixo:

```java
    private ProjectIndexEntry resolveViaProject(String name) {
        if (projectIndex == null) {
            return null;
        }
        if (name.contains(".")) {
            return projectIndex.resolveQualified(name).orElse(null);
        }
        return currentImportedByShortName.get(name);
    }
```

E `checkExprForComponentCalls` (chamada de componente como valor — só nomes simples, ver "Descobertas" no topo deste plano):

```java
    private void checkExprForComponentCalls(Expr expr) {
        switch (expr) {
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p -> {
                ComponentDecl target = symbolTable.lookup(p.text());
                if (target == null) {
                    ProjectIndexEntry resolved = resolveViaProject(p.text());
                    if (resolved == null && looksLikeComponentName(p.text())) {
                        diagnostics.add(new SukoDiagnostic(
                                SukoDiagnostic.Severity.ERROR,
                                "Componente '" + p.text() + "' não encontrado",
                                "COMPONENT_NOT_FOUND",
                                sourceFile,
                                call.span()
                        ));
                    } else if (resolved != null && !resolved.isPublic()) {
                        diagnostics.add(new SukoDiagnostic(
                                SukoDiagnostic.Severity.ERROR,
                                "Componente '" + p.text() + "' não é public",
                                "COMPONENT_NOT_VISIBLE",
                                sourceFile,
                                call.span()
                        ));
                    }
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
```

(`import io.suko.lang.ast.SourceSpan;` — confirmar se já está incluído pelo `import io.suko.lang.ast.*;` do topo do ficheiro; se o ficheiro importar tipos individualmente em vez de wildcard, adicionar.)

- [ ] **Step 6: Rodar os testes novos, confirmar que passam**

Run: `gradle test --tests "io.suko.lang.semantic.SemanticCheckerProjectTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Rodar toda a suite (garante que nenhum teste de `SemanticCheckerTest` existente quebrou)**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/semantic/SemanticChecker.java src/test/java/io/suko/lang/semantic/SemanticCheckerProjectTest.java
/usr/bin/git commit -m "feat(verificador): IMPORT_NOT_FOUND, COMPONENT_NOT_VISIBLE, PACKAGE_DIRECTORY_MISMATCH, AMBIGUOUS_IMPORT (subprojeto 5)"
```

---

### Task 5: `JteEmitter` — resolver nomes importados/qualificados no Java gerado

**Files:**
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Test: `src/test/java/io/suko/lang/JteEmitterProjectTest.java` (novo)

**Interfaces:**
- Consumes: `Map<String, ProjectIndexEntry>` (produzido por `ProjectIndex.resolveImports`, Tarefa 3).
- Produces: `JteEmitter(List<ComponentDecl> allComponents, Map<String, ProjectIndexEntry> importedByShortName)` — novo construtor; os dois construtores existentes (`JteEmitter()`, `JteEmitter(List<ComponentDecl>)`) continuam iguais, delegando com `Map.of()`. A Tarefa 6 consome o construtor novo.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/io/suko/lang/JteEmitterProjectTest.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectIndexEntry;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JteEmitterProjectTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void resolvesImportedShortNameToQualifiedTemplateCall() {
        SukoFile file = parse("""
            import ui.NavLink;

            component Home() {
              NavLink(href="/")
            }
            """);
        ComponentDecl home = file.components().get(0);

        ProjectIndexEntry navLink = new ProjectIndexEntry("ui.NavLink", "NavLink", Path.of("ui/NavLink.sk"), true, 1);
        JteEmitter emitter = new JteEmitter(List.of(home), Map.of("NavLink", navLink));

        String jte = emitter.emit(home);
        assertTrue(jte.contains("@template.ui.NavLink("), jte);
    }

    @Test
    void leavesFullyQualifiedCallUntouched() {
        SukoFile file = parse("""
            component Home() {
              ui.NavLink(href="/")
            }
            """);
        ComponentDecl home = file.components().get(0);

        JteEmitter emitter = new JteEmitter(List.of(home), Map.of());
        String jte = emitter.emit(home);
        assertTrue(jte.contains("@template.ui.NavLink("), jte);
    }

    @Test
    void resolvesImportedShortNameUsedAsExpressionValue() {
        SukoFile file = parse("""
            import ui.CardA;

            component Home() {
              var c = CardA();
              {c}
            }
            """);
        ComponentDecl home = file.components().get(0);

        ProjectIndexEntry cardA = new ProjectIndexEntry("ui.CardA", "CardA", Path.of("ui/CardA.sk"), true, 0);
        JteEmitter emitter = new JteEmitter(List.of(home), Map.of("CardA", cardA));

        String jte = emitter.emit(home);
        assertTrue(jte.contains("@`@template.ui.CardA("), jte);
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle compileTestJava --console=plain`
Expected: FALHA — o construtor de 2 argumentos ainda não existe.

- [ ] **Step 3: Adicionar o campo e o construtor novo**

Em `src/main/java/io/suko/lang/JteEmitter.java`, adicionar import:

```java
import io.suko.lang.project.ProjectIndexEntry;
```

Mudar o construtor:

```java
    private final Map<String, ComponentDecl> componentsByName;
    private final Map<String, ProjectIndexEntry> importedByShortName;

    public JteEmitter() {
        this(List.of(), Map.of());
    }

    public JteEmitter(List<ComponentDecl> allComponents) {
        this(allComponents, Map.of());
    }

    public JteEmitter(List<ComponentDecl> allComponents, Map<String, ProjectIndexEntry> importedByShortName) {
        this.componentsByName = allComponents.stream()
            .collect(Collectors.toMap(ComponentDecl::name, Function.identity()));
        this.importedByShortName = importedByShortName;
    }
```

- [ ] **Step 4: Resolver o nome do template em `emitComponentCall`**

Mudar a primeira linha de `emitComponentCall` (era `out.append("@template.").append(call.componentName()).append('(');`):

```java
    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out, java.util.Set<String> slotNames) {
        out.append("@template.").append(resolveTemplatePath(call.componentName())).append('(');
```

e adicionar o novo método privado (perto de `resolveSlotCardinality`):

```java
    /** Tarefa 5 (subprojeto 5): resolve um nome de chamada para o caminho
     * de template real a usar em @template.<...>(...). Um nome já
     * conhecido localmente (componentsByName) ou já totalmente
     * qualificado (contém '.') passa literal — gg.jte já lê o ponto como
     * separador de path (confirmado empiricamente, ver ARCHITECTURE.md).
     * Só um nome curto pós-import é reescrito para o qualifiedName real. */
    private String resolveTemplatePath(String componentName) {
        if (componentsByName.containsKey(componentName)) {
            return componentName;
        }
        ProjectIndexEntry entry = importedByShortName.get(componentName);
        return entry != null ? entry.qualifiedName() : componentName;
    }
```

- [ ] **Step 5: Resolver o mesmo caso em posição de expressão (componente como valor)**

Mudar o `case` de `emitExpr` (era `case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p && componentsByName.containsKey(p.text()) -> "@\`@template." + p.text() + "(" ...`):

```java
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p
                && (componentsByName.containsKey(p.text()) || importedByShortName.containsKey(p.text())) ->
                "@`@template." + resolveTemplatePath(p.text()) + "(" + emitArgs(call.args(), slotNames) + ")`";
```

- [ ] **Step 6: Rodar os testes novos, confirmar que passam**

Run: `gradle test --tests "io.suko.lang.JteEmitterProjectTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Rodar toda a suite**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterProjectTest.java
/usr/bin/git commit -m "feat(emitter): resolve nomes importados/curtos para o qualifiedName real (subprojeto 5)"
```

---

### Task 6: `JteCompiler.compile(ProjectIndex, Path)` + `SukoProjectCompiler` (Fase 1 + Fase 2)

**Files:**
- Modify: `src/main/java/io/suko/lang/JteCompiler.java`
- Create: `src/main/java/io/suko/lang/project/SukoProjectCompiler.java`
- Modify (test support): `src/test/java/io/suko/lang/support/JteRenderSupport.java`
- Test: `src/test/java/io/suko/lang/project/SukoProjectCompilerTest.java` (novo)

**Interfaces:**
- Consumes: `ProjectIndex` (Tarefa 3), `SemanticChecker` 5-arg (Tarefa 4), `JteEmitter` 2-arg (Tarefa 5).
- Produces: `JteCompiler.compile(ProjectIndex, Path fileRelativePath) -> CompileResult` (`compile()` sem args continua igual); `SukoProjectCompiler.compile(Path sourceRoot) -> ProjectCompileResult(boolean success, Map<Path, DiagnosticCollector> diagnosticsByFile, Map<Path, String> generatedJteSources)`; `JteRenderSupport.renderProject(Path sourceRoot, String entryRelativePath, Map<String,Object> params) -> String`. A Tarefa 7 (build Gradle/Maven) e a Tarefa 8 (e2e) consomem `SukoProjectCompiler`/`renderProject`.

- [ ] **Step 1: Escrever o teste que falha**

Criar `src/test/java/io/suko/lang/project/SukoProjectCompilerTest.java`:

```java
package io.suko.lang.project;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SukoProjectCompilerTest {

    @Test
    void compilesMultiplePackagesIntoMirroredJteSources(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="{href}">link</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;

            component Home() {
              NavLink(href="/")
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertTrue(result.generatedJteSources().containsKey(Path.of("ui", "NavLink.jte")));
        assertTrue(result.generatedJteSources().containsKey(Path.of("Home.jte")));
        assertTrue(result.generatedJteSources().get(Path.of("Home.jte")).contains("@template.ui.NavLink("));
    }

    @Test
    void reportsFailureWithoutThrowingWhenAFileHasErrors(@TempDir Path sourceRoot) throws IOException {
        Files.writeString(sourceRoot.resolve("Broken.sk"), """
            component Broken() {
              Missing(text="x")
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertFalse(result.success());
        DiagnosticCollector diagnostics = result.diagnosticsByFile().get(Path.of("Broken.sk"));
        assertNotNull(diagnostics);
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> "COMPONENT_NOT_FOUND".equals(d.code())));
    }

    @Test
    void rendersEndToEndAcrossPackagesViaRenderProject(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href, String label) {
              <a href="{href}">{label}</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;

            component Home() {
              NavLink(href="/", label="Início")
            }
            """);

        String html = JteRenderSupport.renderProject(sourceRoot, "Home", Map.of());
        assertTrue(html.contains("href=\"/\""), html);
        assertTrue(html.contains("Início"), html);
    }
}
```

`DiagnosticCollector` precisa de ser importado (`io.suko.lang.diagnostic.DiagnosticCollector`) no teste acima.

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle compileTestJava --console=plain`
Expected: FALHA — `io.suko.lang.project.SukoProjectCompiler` e `JteRenderSupport.renderProject` ainda não existem.

- [ ] **Step 3: Extrair o parse+AST partilhado em `JteCompiler` e adicionar o `compile` consciente de projeto**

Em `src/main/java/io/suko/lang/JteCompiler.java`, adicionar imports:

```java
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;

import java.nio.file.Path;
```

Substituir o corpo da classe (mantém `record CompileResult` como está) pelo seguinte, extraindo o parse+AST comum:

```java
    private SukoFile parseAndBuild(DiagnosticCollector diagnostics) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));

        SukoErrorListener parseErrorListener = new SukoErrorListener(diagnostics, fileName);
        parser.removeErrorListeners();
        parser.addErrorListener(parseErrorListener);

        SukoParser.CompilationUnitContext compilationUnitCtx = parser.compilationUnit();
        if (diagnostics.hasErrors()) {
            return null;
        }

        try {
            return new SukoAstBuilder(sukoSource).build(compilationUnitCtx);
        } catch (IllegalStateException e) {
            diagnostics.add(new SukoDiagnostic(
                Severity.ERROR,
                "Erro ao construir AST: " + e.getMessage(),
                "AST_BUILDER_ERROR",
                fileName,
                new SourceSpan(0, 0, 0, 0)
            ));
            return null;
        }
    }

    public CompileResult compile() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SukoFile sukoFile = parseAndBuild(diagnostics);
        if (sukoFile == null) {
            return CompileResult.failure(diagnostics);
        }

        SymbolTable symbolTable = new SymbolTable();
        new SemanticChecker(symbolTable, diagnostics, fileName).check(sukoFile);
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        return emitAll(sukoFile, new JteEmitter(sukoFile.components()));
    }

    /** Consciente de projeto (subprojeto 5): resolve import/visibilidade/
     * nome-composto contra o ProjectIndex de todo o projeto, não só deste
     * ficheiro. Usado por SukoProjectCompiler (Fase 2). */
    public CompileResult compile(ProjectIndex projectIndex, Path fileRelativePath) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SukoFile sukoFile = parseAndBuild(diagnostics);
        if (sukoFile == null) {
            return CompileResult.failure(diagnostics);
        }

        SymbolTable symbolTable = new SymbolTable();
        new SemanticChecker(symbolTable, diagnostics, fileName, projectIndex, fileRelativePath).check(sukoFile);
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        Map<String, ProjectIndexEntry> importedByShortName = projectIndex.resolveImports(sukoFile.imports());
        return emitAll(sukoFile, new JteEmitter(sukoFile.components(), importedByShortName));
    }

    private CompileResult emitAll(SukoFile sukoFile, JteEmitter emitter) {
        Map<String, String> jteSources = new LinkedHashMap<>();
        for (ComponentDecl component : sukoFile.components()) {
            JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
            jteSources.put(component.name() + ".jte", result.jteSource());
        }
        return CompileResult.success(jteSources);
    }
```

(remove o corpo antigo de `compile()` que fazia tudo inline — o comportamento externo do método de 0 argumentos não muda, só foi fatorado.)

- [ ] **Step 4: Criar `SukoProjectCompiler`**

Criar `src/main/java/io/suko/lang/project/SukoProjectCompiler.java`:

```java
package io.suko.lang.project;

import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.DiagnosticCollector;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Orquestra a Fase 1 (ProjectIndex.build) + a Fase 2 (JteCompiler por
 * ficheiro, com o índice injetado) — não é um fork de JteCompiler, reusa-o.
 * Cada .jte gerado é escrito numa chave que espelha a pasta do ficheiro
 * .sk de origem (ex. "ui/NavLink.jte"), fechando o bug de
 * TemplateNotFoundException em nomes compostos documentado no
 * ARCHITECTURE.md (gg.jte já lê o ponto de @template.ui.NavLink(...)
 * como separador de path — só faltava o .jte existir nessa subpasta).
 */
public class SukoProjectCompiler {

    public record ProjectCompileResult(
        boolean success,
        Map<Path, DiagnosticCollector> diagnosticsByFile,
        Map<Path, String> generatedJteSources
    ) {
    }

    public ProjectCompileResult compile(Path sourceRoot) {
        ProjectIndex index = ProjectIndex.build(sourceRoot);

        List<Path> skFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            skFiles = walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<Path, DiagnosticCollector> diagnosticsByFile = new LinkedHashMap<>();
        Map<Path, String> generatedJteSources = new LinkedHashMap<>();
        boolean success = true;

        for (Path skFile : skFiles) {
            Path relative = sourceRoot.relativize(skFile);
            String source;
            try {
                source = Files.readString(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            JteCompiler compiler = new JteCompiler(relative.toString(), source);
            JteCompiler.CompileResult result = compiler.compile(index, relative);
            diagnosticsByFile.put(relative, result.diagnostics());

            if (!result.success()) {
                success = false;
                continue;
            }

            Path outputSubDir = relative.getParent() == null ? Path.of("") : relative.getParent();
            for (var entry : result.generatedJteSources().entrySet()) {
                generatedJteSources.put(outputSubDir.resolve(entry.getKey()), entry.getValue());
            }
        }

        return new ProjectCompileResult(success, diagnosticsByFile, generatedJteSources);
    }
}
```

- [ ] **Step 5: Adicionar `renderProject` ao suporte de teste**

Em `src/test/java/io/suko/lang/support/JteRenderSupport.java`, adicionar import:

```java
import io.suko.lang.project.SukoProjectCompiler;
```

e o método novo (ao lado de `renderWithDependencies`):

```java
    /** Compila um projeto multi-ficheiro real (SukoProjectCompiler) e
     * renderiza o template pelo caminho relativo, sem ".jte" (ex.
     * "ui/NavLink" ou "Home"). Usado pelos testes do subprojeto 5. */
    public static String renderProject(Path sourceRoot, String entryRelativePath, Map<String, Object> params) throws IOException {
        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);
        if (!result.success()) {
            throw new IllegalStateException("Compilação do projeto falhou: " + result.diagnosticsByFile());
        }

        Path tempDir = Files.createTempDirectory("suko-jte-render-project");
        for (var entry : result.generatedJteSources().entrySet()) {
            Path jteFile = tempDir.resolve(entry.getKey().toString());
            Files.createDirectories(jteFile.getParent());
            Files.writeString(jteFile, entry.getValue());
        }

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(entryRelativePath + ".jte", params, output);
        return output.toString();
    }
```

- [ ] **Step 6: Rodar os testes novos, confirmar que passam**

Run: `gradle test --tests "io.suko.lang.project.SukoProjectCompilerTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Rodar toda a suite (garante que `JteCompiler.compile()` sem args continua com o mesmo comportamento externo depois da extração)**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/JteCompiler.java src/main/java/io/suko/lang/project/SukoProjectCompiler.java src/test/java/io/suko/lang/support/JteRenderSupport.java src/test/java/io/suko/lang/project/SukoProjectCompilerTest.java
/usr/bin/git commit -m "feat(project): SukoProjectCompiler — Fase 1 + Fase 2, output espelha pacotes (subprojeto 5)"
```

---

### Task 7: Integração no build — Gradle `SukoCompileTask` e Maven `SukoCompileMojo`

**Files:**
- Modify: `src/main/java/io/suko/lang/gradle/SukoCompileTask.java`
- Modify: `suko-maven-plugin/src/main/java/io/suko/lang/maven/SukoCompileMojo.java`
- Test: `suko-maven-plugin/src/test/java/io/suko/lang/maven/SukoCompileMojoProjectTest.java` (novo)

**Interfaces:**
- Consumes: `SukoProjectCompiler.compile(Path)` (Tarefa 6).

**Nota de âmbito (ver "Descobertas" no topo do plano):** não há infraestrutura de teste de `Task`/`Project` do Gradle neste repo — a Tarefa só altera a produção do lado Gradle e é validada por revisão + pelos testes de `SukoProjectCompiler` (Tarefa 6, já prova a mesma lógica de escrita em subpastas que ambos os lados passam a partilhar). O teste automatizado desta tarefa cobre o lado Maven, mais simples de instanciar diretamente.

- [ ] **Step 1: Escrever o teste que falha (lado Maven)**

Criar `suko-maven-plugin/src/test/java/io/suko/lang/maven/SukoCompileMojoProjectTest.java`:

```java
package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SukoCompileMojoProjectTest {

    @Test
    void compilesNestedPackagesIntoMirroredOutputDirectories(@TempDir Path sourceRoot, @TempDir Path outputDir) throws Exception {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="${href}">link</a>
            }
            """);

        SukoCompileMojo mojo = new SukoCompileMojo();
        setField(mojo, "sourceDir", sourceRoot.toFile());
        setField(mojo, "outputDir", outputDir.toFile());

        mojo.execute();

        assertTrue(Files.exists(outputDir.resolve("ui").resolve("NavLink.jte")),
            "esperava ui/NavLink.jte espelhando o pacote");
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
```

- [ ] **Step 2: Rodar o teste, confirmar que falha**

Run: `gradle :suko-maven-plugin:test --tests "io.suko.lang.maven.SukoCompileMojoProjectTest" --console=plain` (se o módulo Maven não tiver task Gradle própria, rodar `gradle test --console=plain` a partir da raiz e confirmar que o teste falha por `ui/NavLink.jte` não existir — a Mojo ainda escreve tudo flat)

Expected: FALHA — hoje `SukoCompileMojo` escreve `NavLink.jte` direto em `outputDir`, sem subpasta `ui/`.

- [ ] **Step 3: Reescrever `SukoCompileMojo.execute()`**

Substituir o corpo de `src/main/java/io/suko/lang/maven/SukoCompileMojo.java` (dentro de `suko-maven-plugin/`) — remover `findSkFiles`/`compileFile`, e mudar `execute()`:

```java
    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Suko Maven Plugin - Compiling .sk files to .jte");
        getLog().info("Source directory: {}", sourceDir);
        getLog().info("Output directory: {}", outputDir);

        if (!sourceDir.exists()) {
            getLog().warn("Source directory does not exist: {}", sourceDir);
            return;
        }

        try {
            Files.createDirectories(outputDir.toPath());

            io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
                new io.suko.lang.project.SukoProjectCompiler().compile(sourceDir.toPath());

            for (var entry : result.generatedJteSources().entrySet()) {
                Path jtePath = outputDir.toPath().resolve(entry.getKey());
                Files.createDirectories(jtePath.getParent());
                Files.writeString(jtePath, entry.getValue());
                getLog().info("Generated: {}", jtePath);
            }

            for (var fileEntry : result.diagnosticsByFile().entrySet()) {
                for (var diag : fileEntry.getValue().getErrors()) {
                    getLog().error("[" + diag.severity() + "] " + fileEntry.getKey() + " - " + diag.code() + ": " + diag.message());
                }
            }

            if (!result.success()) {
                throw new MojoExecutionException("Suko compilation failed — see diagnostics above");
            }

            getLog().info("Successfully compiled project");
        } catch (java.io.IOException e) {
            throw new MojoExecutionException("Failed to compile Suko files", e);
        }
    }
```

(o campo `generatedPackage` e o import `io.suko.lang.JteCompiler` deixam de ser usados por este método — se `generatedPackage` não for usado em nenhum outro sítio da classe, manter o campo como está, só remover o import morto de `JteCompiler` e os métodos `findSkFiles`/`compileFile`.)

- [ ] **Step 4: Rodar o teste Maven, confirmar que passa**

Run: `gradle test --tests "io.suko.lang.maven.SukoCompileMojoProjectTest" --console=plain` (ajustar o comando ao módulo real — confirmar no `settings.gradle`/`pom.xml` como este submódulo é buildado antes de escrever o comando final no ledger)

Expected: PASS

- [ ] **Step 5: Reescrever `SukoCompileTask.compile()` (Gradle) da mesma forma**

Substituir o corpo de `src/main/java/io/suko/lang/gradle/SukoCompileTask.java` — remover `findSkFiles`/`compileSingleFile`:

```java
    @TaskAction
    public void compile() {
        Path sourceDir = getExtension().getSourceDirAsPath();
        Path outputDir = getExtension().getOutputDirAsPath();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
            new io.suko.lang.project.SukoProjectCompiler().compile(sourceDir);

        for (var entry : result.generatedJteSources().entrySet()) {
            Path jteFile = outputDir.resolve(entry.getKey());
            try {
                Files.createDirectories(jteFile.getParent());
                Files.writeString(jteFile, entry.getValue());
                getLogger().lifecycle("Compiled: {}", entry.getKey());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + jteFile, e);
            }
        }

        for (var fileEntry : result.diagnosticsByFile().entrySet()) {
            printDiagnostics(fileEntry.getValue(), fileEntry.getKey().toString());
        }

        if (!result.success()) {
            throw new RuntimeException("Suko compilation failed — see diagnostics above");
        }
    }
```

(`printDiagnostics(DiagnosticCollector, String)` já existe e não muda — ver classe atual.)

- [ ] **Step 6: Rodar toda a suite**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add src/main/java/io/suko/lang/gradle/SukoCompileTask.java suko-maven-plugin/src/main/java/io/suko/lang/maven/SukoCompileMojo.java suko-maven-plugin/src/test/java/io/suko/lang/maven/SukoCompileMojoProjectTest.java
/usr/bin/git commit -m "feat(build): SukoCompileTask/SukoCompileMojo usam SukoProjectCompiler, output espelha pacotes (subprojeto 5)"
```

---

### Task 8: Testes de aceitação end-to-end — fixture multi-ficheiro completa

**Files:**
- Test: `src/test/java/io/suko/lang/project/MultiFileAcceptanceTest.java` (novo)

**Interfaces:**
- Consumes: `SukoProjectCompiler` (Tarefa 6), `JteRenderSupport.renderProject` (Tarefa 6).

- [ ] **Step 1: Escrever os testes de aceitação**

Criar `src/test/java/io/suko/lang/project/MultiFileAcceptanceTest.java`:

```java
package io.suko.lang.project;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MultiFileAcceptanceTest {

    private void writeThreeFileFixture(Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);

        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href, String label) {
              <a href="${href}">{label}</a>
            }
            """);

        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            component Dot() {
              <span class="dot"></span>
            }

            public component Badge(String text) {
              <span class="badge">
                Dot()
                {text}
              </span>
            }
            """);

        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.NavLink;
            import ui.Badge as Pill;

            component Home() {
              <div>
                NavLink(href="/", label="Início")
                Pill(text="novo")
                ui.Badge(text="qualificado")
              </div>
            }
            """);
    }

    @Test
    void resolvesShortNameAliasAndFullyQualifiedNameAcrossPackages(@TempDir Path sourceRoot) throws IOException {
        writeThreeFileFixture(sourceRoot);

        String html = JteRenderSupport.renderProject(sourceRoot, "Home", Map.of());

        assertTrue(html.contains("href=\"/\""), html);
        assertTrue(html.contains("Início"), html);
        assertTrue(html.contains("novo"), html);
        assertTrue(html.contains("qualificado"), html);
        assertEquals(3, html.split("class=\"dot\"", -1).length - 1,
            "Dot() (file-private) é chamado 3 vezes dentro do próprio ficheiro Badge.sk");
    }

    @Test
    void filePrivateComponentCannotBeCalledFromAnotherFile(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            component Dot() {
              <span class="dot"></span>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            import ui.Dot;

            component Home() {
              Dot()
            }
            """);

        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);

        assertFalse(result.success());
        assertTrue(result.diagnosticsByFile().get(Path.of("Home.sk")).getErrors().stream()
            .anyMatch(d -> "IMPORT_NOT_FOUND".equals(d.code()) || "COMPONENT_NOT_VISIBLE".equals(d.code())),
            result.diagnosticsByFile().get(Path.of("Home.sk")).toString());
    }

    @Test
    void cyclicCrossFileCallsCompileAndRenderSuccessfully(@TempDir Path sourceRoot) throws IOException {
        Path cycleDir = sourceRoot.resolve("cycle");
        Files.createDirectories(cycleDir);

        Files.writeString(cycleDir.resolve("A.sk"), """
            package cycle;

            import cycle.B;

            public component A(boolean stop) {
              if (stop) {
                <p>fim A</p>
              } else {
                B(stop = true)
              }
            }
            """);
        Files.writeString(cycleDir.resolve("B.sk"), """
            package cycle;

            import cycle.A;

            public component B(boolean stop) {
              if (stop) {
                <p>fim B</p>
              } else {
                A(stop = true)
              }
            }
            """);

        String html = JteRenderSupport.renderProject(sourceRoot, "cycle/A", Map.of("stop", false));
        assertTrue(html.contains("fim B"), html);
    }
}
```

- [ ] **Step 2: Rodar os testes, confirmar que falham ou passam parcialmente**

Run: `gradle test --tests "io.suko.lang.project.MultiFileAcceptanceTest" --console=plain`

Expected: se as Tarefas 1-7 foram todas implementadas corretamente, estes testes já devem PASSAR sem nenhum código novo — esta tarefa é de aceitação, não de implementação. Se algum falhar, é sinal de uma lacuna nas tarefas anteriores: investigar e corrigir na camada certa (não aqui), documentando a descoberta no ponto exato.

- [ ] **Step 3: Rodar toda a suite**

Run: `gradle test --console=plain`
Expected: verde.

- [ ] **Step 4: Commit**

```bash
/usr/bin/git add src/test/java/io/suko/lang/project/MultiFileAcceptanceTest.java
/usr/bin/git commit -m "test(subprojeto 5): aceitação end-to-end — import, alias, nome qualificado, visibilidade, ciclo"
```

---

### Task 9: `ARCHITECTURE.md` — fechar as limitações resolvidas, documentar o novo modelo

**Files:**
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: Remover a limitação "Não há imports automáticos"**

Localizar o bloco (visto em `ARCHITECTURE.md:172-180` ao escrever este plano) que começa com `**Não há imports automáticos.**` e termina antes de `**Chamada de componente com nome composto falha em runtime.**` — remover o bloco inteiro (resolvido pelas Tarefas 2-6).

- [ ] **Step 2: Remover a limitação "Chamada de componente com nome composto falha em runtime"**

Remover o bloco `**Chamada de componente com nome composto falha em runtime.**` inteiro (resolvido pela Tarefa 6 — output em subpastas).

- [ ] **Step 3: Adicionar uma secção nova descrevendo o modelo de projeto multi-ficheiro**

Inserir, no mesmo sítio de onde os dois blocos acima foram removidos:

```markdown
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
```

- [ ] **Step 4: Atualizar a linha do roadmap para "CONCLUÍDO"**

No bloco "Roadmap por subprojeto", mudar:

```markdown
5. **Projeto multi-ficheiro (resolução de nomes)** — spec própria,
   ainda por escrever. Cobre `TemplateResolver`/`SukoProjectCompiler`
   feito de raiz (reusando `JteCompiler`, não um fork).
```

para:

```markdown
5. **Projeto multi-ficheiro (resolução de nomes)** — CONCLUÍDO
   (`docs/superpowers/specs/2026-09-19-suko-projeto-multificheiro.md`).
   `package`/`import` resolvidos de verdade via `ProjectIndex` +
   `SukoProjectCompiler`; visibilidade `public`/file-private; output
   espelha pacotes.
```

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add ARCHITECTURE.md
/usr/bin/git commit -m "docs: ARCHITECTURE.md — subprojeto 5 concluído, remove limitações resolvidas"
```

---

## Após todas as tarefas

Rodar a suite completa uma última vez (`gradle test --console=plain`) e seguir para `superpowers:finishing-a-development-branch` (revisão final de todo o branch antes de merge/PR), tal como nos subprojetos anteriores.
