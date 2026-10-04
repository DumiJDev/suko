# Subprojeto 13a — API de extensões: plano de implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Criar o `suko-api` (API de extensões de compile-time), um carregador de extensões no `suko-core` e o alvo JTE como primeira extensão (`suko-jte`), sem mudar nenhum `.jte` gerado nem nenhum diagnóstico existente.

**Architecture:** O AST, os diagnósticos e as entradas do índice mudam para um módulo `suko-api` sem dependências, mantendo os packages. O core descobre extensões por `ServiceLoader` num `ExtensionRegistry` e passa a emitir através de `Target`s escolhidos por uma lista `targets` (por omissão `["jte"]`). O `JteEmitter` muda para o módulo `suko-jte` no mesmo package, e os plugins Gradle/Maven, o site e o LSP trazem-no por omissão.

**Tech Stack:** Java 21, Gradle 9.5 (Kotlin DSL), `java.util.ServiceLoader`, JUnit 5, Gradle TestKit, LSP4J 1.0.0, Gson 2.11.0 (só no LSP), TypeScript (extensão VSCode, uma linha).

**Spec:** `docs/superpowers/specs/2026-10-04-suko-api-extensoes.md` (aprovada a 2026-10-04).

## Global Constraints

- Java 21 (`options.release.set(21)`, já global no `build.gradle.kts` da raiz).
- **Packages não mudam:** `io.suko.lang.ast`, `io.suko.lang.diagnostic`, `io.suko.lang.project` e `io.suko.lang` (o `JteEmitter`) mantêm os seus nomes depois de mudarem de módulo. Nenhum `import` existente pode precisar de mudar.
- `suko-api` não tem dependências além do JDK.
- `suko-jte` depende só do `suko-api` (nunca do `suko-core`).
- Versão da API: `ExtensionApi.VERSION = 1`; o core aceita extensões com o mesmo major.
- Só três pontos de extensão: `Target`, `Vocabulary`, `Checker`.
- `targets` por omissão `["jte"]`. Com um alvo, os caminhos de output são exatamente os de hoje; com vários, cada alvo escreve em `<out>/<targetId>/`.
- Códigos de erro novos: `EXTENSION_CONFLICT`, `EXTENSION_API_MISMATCH`, `TARGET_NOT_FOUND`, `EXTENSION_FAILED`, e os dois que este plano acrescenta (ver "Lacunas da spec resolvidas"): `UNKNOWN_TAG`, `VOCABULARY_NOT_FOUND`.
- O LSP só carrega extensões externas quando o cliente diz que o workspace é confiável.
- A suite atual tem de ficar verde **sem alterar asserções**; só podem mudar dependências entre módulos.
- Commits sem `Co-Authored-By` nem `Claude-Session` (`CLAUDE.md`). Cada despacho de subagente tem de repetir esta regra.

## Lacunas da spec resolvidas neste plano

1. **Tag fora de um vocabulário fechado.** A spec define vocabulários fechados (a `test-ext` tem um), mas não diz que diagnóstico sai quando uma tag não está lá. O plano usa `UNKNOWN_TAG` (ERROR): para cada alvo pedido, uma tag tem de ser aceite por pelo menos um dos vocabulários desse alvo; um vocabulário `open()` aceita tudo. Com só o `jte` (HTML aberto), nunca dispara — nenhum diagnóstico existente muda.
2. **Alvo que declara um vocabulário que ninguém registou.** O plano usa `VOCABULARY_NOT_FOUND` (ERROR, ao nível do projeto).
3. **Source maps no golden.** O `SukoProjectCompiler` não devolve source maps, por isso o golden dos source maps é gerado chamando o `JteEmitter` diretamente sobre a análise do projeto (que passa a viver no `suko-jte` sem mudar de package nem de API).

## Review Focus

1. **Projeto sem extensões declaradas** — tem de compilar byte a byte como hoje; o golden (Task 1) é a prova. Coberto pela Task 1 e reverificado nas Tasks 2, 5 e 9.
2. **O `suko-jte` presente duas vezes no LSP** (embutido no server e listado no `extensions.json` de um projeto) — tem de ser ignorado, não um `EXTENSION_CONFLICT`. Pinado pelo teste `manifestNeverListsTheBuiltInJteJar` (Task 6) e por `duplicateJteFromManifestIsIgnored` (Task 8).
3. **Extensão que lança exceção no meio do build ou do LSP** — o compilador devolve `EXTENSION_FAILED` com o id da extensão e o ficheiro, e continua. Pinado por `checkerThatThrowsBecomesExtensionFailed` e `targetThatThrowsBecomesExtensionFailed` (Task 5) e `crashingExtensionDoesNotKillServer` (Task 8).
4. **Workspace não confiável no LSP** — `extensions.json` presente mas o cliente não confirma confiança: nenhuma extensão externa corre; o JTE continua a funcionar. Pinado por `untrustedWorkspaceIgnoresExtensions` (Task 8).
5. **Vários alvos com subpastas de package** — `targets = ["jte","demo"]` com `.sk` em `a/b/`: os ficheiros vão para `<out>/jte/a/b/` e `<out>/demo/a/b/`, nunca `<out>/a/b/jte/`. Pinado por `multipleTargetsPrefixTheTargetBeforeThePackageDir` (Task 5).

---

### Task 1: Golden de paridade (antes de mover código)

**Files:**
- Create: `suko-core/src/test/java/io/suko/lang/GoldenParityTest.java`
- Create: `suko-core/src/test/resources/golden/` (gerado pelo próprio teste)
- Modify: `suko-core/build.gradle.kts` (passar a system property ao `test`)

**Interfaces:**
- Consumes: `SukoProjectCompiler.compile(Path)`, `SukoProjectCompiler.analyze(SukoSources)`, `JteEmitter.emitWithSourceMap`, `ProjectIndex.resolveImports`, `ProjectIndex.relativeDirToPackagePrefix`.
- Produces: o diretório `golden/` que as Tasks 2, 5 e 9 usam como prova; nada de código.

Raízes cobertas (verificado a 2026-10-04): `../examples` (compila com erros reais — `COMPONENT_NOT_VISIBLE`, `IMPORT_NOT_FOUND` e `examples/invalid/` — e gera 8 `.jte`; o golden guarda os `.jte` **e** os diagnósticos, o que prova também a paridade de diagnósticos), `../suko-components/src/main/suko` (8 `.jte`, sem erros) e `../suko-website/src/main/suko` (4 `.jte`, sem erros).

- [ ] **Step 1: Escrever o teste**

```java
package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceMapEntry;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectAnalysis;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Prova central do 13a: o output do compilador (os .jte, os source maps e os
 * diagnósticos) para três raízes reais fica byte a byte igual antes e depois
 * de o JTE passar para trás da API de extensões. Regenerar só com
 * -Dsuko.updateGolden=true, e só na Task 1.
 */
class GoldenParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden");
    private static final boolean UPDATE = Boolean.getBoolean("suko.updateGolden");

    @ParameterizedTest
    @CsvSource({
        "examples, ../examples",
        "components, ../suko-components/src/main/suko",
        "website, ../suko-website/src/main/suko"
    })
    void outputMatchesGolden(String name, String root) throws IOException {
        Map<String, String> actual = snapshot(Path.of(root));
        Path dir = GOLDEN.resolve(name);
        if (UPDATE) {
            write(dir, actual);
            return;
        }
        assertEquals(read(dir), actual, "output diferente do golden em " + name);
    }

    static Map<String, String> snapshot(Path root) {
        Map<String, String> out = new TreeMap<>();
        var result = new SukoProjectCompiler().compile(root);
        result.generatedJteSources().forEach((path, jte) -> out.put("jte/" + slash(path), jte));

        List<String> diagnostics = new ArrayList<>();
        result.diagnosticsByFile().forEach((file, collector) -> {
            for (SukoDiagnostic d : collector.getDiagnostics()) {
                diagnostics.add(slash(file) + "|" + d.severity() + "|" + d.code() + "|"
                    + (d.span() == null ? "-" : d.span().startLine() + ":" + d.span().startColumn())
                    + "|" + d.message());
            }
        });
        diagnostics.sort(null);
        out.put("diagnostics.txt", String.join("\n", diagnostics) + "\n");

        ProjectAnalysis analysis = new SukoProjectCompiler().analyze(SukoSources.fromDirectory(root));
        StringBuilder maps = new StringBuilder();
        analysis.files().forEach((file, fa) -> {
            if (fa.ast() == null || fa.diagnostics().hasErrors()) {
                return;
            }
            Path parent = file.getParent() == null ? Path.of("") : file.getParent();
            JteEmitter emitter = new JteEmitter(fa.ast().components(),
                analysis.index().resolveImports(fa.ast().imports()),
                ProjectIndex.relativeDirToPackagePrefix(parent));
            for (ComponentDecl c : fa.ast().components()) {
                for (SourceMapEntry e : emitter.emitWithSourceMap(c).sourceMap()) {
                    maps.append(slash(file)).append('#').append(c.name()).append(' ')
                        .append(e.jteLine()).append(" -> ")
                        .append(e.sukoSpan().startLine()).append(':').append(e.sukoSpan().startColumn())
                        .append('\n');
                }
            }
        });
        out.put("sourcemaps.txt", maps.toString());
        return out;
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        for (var e : files.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> read(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(slash(dir.relativize(p)), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
```

(`ProjectAnalysis` é `record ProjectAnalysis(ProjectIndex index, Map<Path, FileAnalysis> files)` com `record FileAnalysis(SukoFile ast, DiagnosticCollector diagnostics)` — verificado. `@ParameterizedTest` vem no `junit-jupiter` já declarado.)

- [ ] **Step 2: Encaminhar a system property no `suko-core/build.gradle.kts`**, dentro do bloco `tasks.test { ... }` existente:

```kotlin
    systemProperty("suko.updateGolden", System.getProperty("suko.updateGolden") ?: "false")
```

- [ ] **Step 3: Gerar o golden com o compilador atual**

Run: `./gradlew :suko-core:test --tests io.suko.lang.GoldenParityTest -Dsuko.updateGolden=true`
Expected: PASS; aparecem `golden/examples`, `golden/components`, `golden/website`, cada um com `jte/…`, `diagnostics.txt` e `sourcemaps.txt`. Confirmar à mão que `golden/components/jte/` tem 8 ficheiros, `golden/website/jte/` 4, e que `golden/examples/diagnostics.txt` lista `COMPONENT_NOT_VISIBLE`.

- [ ] **Step 4: Correr sem atualizar**

Run: `./gradlew :suko-core:test --tests io.suko.lang.GoldenParityTest`
Expected: PASS (3 casos).

- [ ] **Step 5: Commit**

```bash
git add suko-core/build.gradle.kts suko-core/src/test/java/io/suko/lang/GoldenParityTest.java suko-core/src/test/resources/golden
git commit -m "test(core): golden de paridade do output (jte, source maps, diagnósticos) antes do 13a"
```

---

### Task 2: Módulo `suko-api` com o AST, os diagnósticos e as entradas do índice

**Files:**
- Create: `suko-api/build.gradle.kts`
- Modify: `settings.gradle.kts` (incluir `suko-api`)
- Move (com `git mv`, mesmo caminho de package):
  - `suko-core/src/main/java/io/suko/lang/ast/*.java` → `suko-api/src/main/java/io/suko/lang/ast/`
  - `suko-core/src/main/java/io/suko/lang/diagnostic/SukoDiagnostic.java` e `DiagnosticCollector.java` → `suko-api/src/main/java/io/suko/lang/diagnostic/`
  - `suko-core/src/main/java/io/suko/lang/project/ProjectIndexEntry.java` e `ParamInfo.java` → `suko-api/src/main/java/io/suko/lang/project/`
- Modify: `suko-core/build.gradle.kts` (`api(project(":suko-api"))`)
- Test: a suite existente + `GoldenParityTest`.

**Interfaces:**
- Consumes: nada novo.
- Produces: módulo `suko-api` com os mesmos tipos públicos, nos mesmos packages. O `SukoErrorListener` (depende de ANTLR) **fica** no core.

- [ ] **Step 1: Criar `suko-api/build.gradle.kts`**

```kotlin
plugins {
    id("java-library")
}

// Contrato público das extensões (subprojeto 13a): só JDK, sem dependências.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Incluir no `settings.gradle.kts`** — acrescentar `"suko-api"` à lista do `include(...)` (antes de `"suko-core"`).

- [ ] **Step 3: Mover os ficheiros**

```bash
mkdir -p suko-api/src/main/java/io/suko/lang/{ast,diagnostic,project}
git mv suko-core/src/main/java/io/suko/lang/ast/*.java suko-api/src/main/java/io/suko/lang/ast/
git mv suko-core/src/main/java/io/suko/lang/diagnostic/SukoDiagnostic.java suko-api/src/main/java/io/suko/lang/diagnostic/
git mv suko-core/src/main/java/io/suko/lang/diagnostic/DiagnosticCollector.java suko-api/src/main/java/io/suko/lang/diagnostic/
git mv suko-core/src/main/java/io/suko/lang/project/ProjectIndexEntry.java suko-api/src/main/java/io/suko/lang/project/
git mv suko-core/src/main/java/io/suko/lang/project/ParamInfo.java suko-api/src/main/java/io/suko/lang/project/
```

- [ ] **Step 4: Dependência no `suko-core/build.gradle.kts`** — no bloco `dependencies`, primeira linha:

```kotlin
    api(project(":suko-api"))
```

e mudar `plugins { id("java") ...}` para `id("java-library")` (o `api(...)` exige-o).

- [ ] **Step 5: Verificar que nada no `suko-api` depende do core**

Run: `./gradlew :suko-api:compileJava`
Expected: BUILD SUCCESSFUL. Se falhar por uma classe do core (ANTLR, `ProjectIndex`, `SukoErrorListener`), essa classe não devia ter sido movida: voltar a pô-la no core.

- [ ] **Step 6: Suite inteira e golden**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL; `GoldenParityTest` PASS.

- [ ] **Step 7: Commit**

```bash
git add -A settings.gradle.kts suko-api suko-core
git commit -m "refactor: módulo suko-api com o AST, os diagnósticos e as entradas do índice (packages inalterados)"
```

---

### Task 3: API de extensões e `ExtensionRegistry`

**Files:**
- Create: `suko-api/src/main/java/io/suko/ext/ExtensionApi.java`
- Create: `suko-api/src/main/java/io/suko/ext/SukoExtension.java`
- Create: `suko-api/src/main/java/io/suko/ext/ExtensionContext.java`
- Create: `suko-api/src/main/java/io/suko/ext/Target.java`
- Create: `suko-api/src/main/java/io/suko/ext/Emitted.java`
- Create: `suko-api/src/main/java/io/suko/ext/EmitContext.java`
- Create: `suko-api/src/main/java/io/suko/ext/Vocabulary.java`
- Create: `suko-api/src/main/java/io/suko/ext/TagSpec.java`
- Create: `suko-api/src/main/java/io/suko/ext/Checker.java`
- Create: `suko-api/src/main/java/io/suko/ext/CheckContext.java`
- Create: `suko-api/src/main/java/io/suko/lang/project/ProjectView.java`
- Modify: `suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java` (`implements ProjectView`)
- Create: `suko-core/src/main/java/io/suko/lang/ext/ExtensionRegistry.java`
- Test: `suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java`

**Interfaces:**
- Consumes: `ComponentDecl`, `SukoFile`, `SourceMapEntry`, `SourceSpan`, `SukoDiagnostic`, `DiagnosticCollector`, `ProjectIndexEntry`, `ImportDecl` (Task 2).
- Produces (todas as tasks seguintes dependem destes nomes exatos):
  - `ExtensionApi.VERSION` (`int`, = 1)
  - `SukoExtension { String id(); int apiVersion(); void register(ExtensionContext ctx); }`
  - `ExtensionContext { void target(Target t); void vocabulary(Vocabulary v); void checker(Checker c); }`
  - `Target { String id(); String componentType(); Set<String> vocabularies(); Emitted emit(ComponentDecl component, EmitContext ctx); }`
  - `record Emitted(String relativePath, String source, List<SourceMapEntry> sourceMap)`
  - `record EmitContext(SukoFile file, ProjectView project, Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix)`
  - `Vocabulary { String id(); boolean open(); Optional<TagSpec> tag(String name); }`
  - `record TagSpec(String name, Map<String, String> attributeTypes, boolean allowsChildren)`
  - `Checker { String id(); void check(SukoFile file, CheckContext ctx); }`
  - `record CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics)` com `void report(SukoDiagnostic.Severity severity, String code, String message, SourceSpan span)`
  - `ProjectView { Collection<ProjectIndexEntry> entries(); Optional<ProjectIndexEntry> resolveQualified(String qualifiedName); Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports); ProjectView EMPTY; }`
  - `ExtensionRegistry` (core, package `io.suko.lang.ext`): `static ExtensionRegistry load(ClassLoader)`, `static ExtensionRegistry of(List<SukoExtension>)`, `static ExtensionRegistry defaults()`, `Optional<Target> target(String id)`, `Set<String> targetIds()`, `Optional<Vocabulary> vocabulary(String id)`, `List<Checker> checkers()`, `String ownerOf(Object contribution)`, `List<SukoDiagnostic> loadDiagnostics()`, `static final String BUILT_IN_JTE = "io.suko.jte"`.

- [ ] **Step 1: Escrever o teste do registo**

```java
package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    record Ext(String id, int apiVersion, List<Object> contributions) implements SukoExtension {
        public void register(ExtensionContext ctx) {
            for (Object c : contributions) {
                if (c instanceof Target t) ctx.target(t);
                if (c instanceof Vocabulary v) ctx.vocabulary(v);
                if (c instanceof Checker k) ctx.checker(k);
            }
        }
    }

    static Target target(String id) {
        return new Target() {
            public String id() { return id; }
            public String componentType() { return "x.Component"; }
            public Set<String> vocabularies() { return Set.of(); }
            public Emitted emit(ComponentDecl c, EmitContext ctx) { return new Emitted(c.name(), "", List.of()); }
        };
    }

    static Checker checker(String id) {
        return new Checker() {
            public String id() { return id; }
            public void check(SukoFile file, CheckContext ctx) { }
        };
    }

    @Test
    void registersContributionsAndOrdersCheckersByExtensionId() {
        var registry = ExtensionRegistry.of(List.of(
            new Ext("b.ext", 1, List.of(target("b"), checker("b-check"))),
            new Ext("a.ext", 1, List.of(checker("a-check")))));
        assertEquals(Set.of("b"), registry.targetIds());
        assertEquals(List.of("a-check", "b-check"), registry.checkers().stream().map(Checker::id).toList());
        assertEquals("b.ext", registry.ownerOf(registry.target("b").orElseThrow()));
        assertTrue(registry.loadDiagnostics().isEmpty());
    }

    @Test
    void duplicateTargetIdIsAConflictAndTheFirstByExtensionIdWins() {
        var registry = ExtensionRegistry.of(List.of(
            new Ext("z.ext", 1, List.of(target("demo"))),
            new Ext("a.ext", 1, List.of(target("demo")))));
        assertEquals("a.ext", registry.ownerOf(registry.target("demo").orElseThrow()));
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_CONFLICT", d.code());
        assertTrue(d.message().contains("a.ext") && d.message().contains("z.ext"), d.message());
    }

    @Test
    void wrongApiVersionIsRejectedWithBothVersionsInTheMessage() {
        var registry = ExtensionRegistry.of(List.of(new Ext("old.ext", 99, List.of(target("old")))));
        assertTrue(registry.target("old").isEmpty());
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_API_MISMATCH", d.code());
        assertTrue(d.message().contains("old.ext") && d.message().contains("99")
            && d.message().contains(String.valueOf(ExtensionApi.VERSION)), d.message());
    }

    @Test
    void registerThatThrowsBecomesExtensionFailed() {
        SukoExtension broken = new SukoExtension() {
            public String id() { return "broken.ext"; }
            public int apiVersion() { return ExtensionApi.VERSION; }
            public void register(ExtensionContext ctx) { throw new IllegalStateException("boom"); }
        };
        var registry = ExtensionRegistry.of(List.of(broken));
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_FAILED", d.code());
        assertTrue(d.message().contains("broken.ext") && d.message().contains("boom"), d.message());
    }

    @Test
    void vocabularyLookupByIdAndEmptyProjectView() {
        Vocabulary v = new Vocabulary() {
            public String id() { return "demo"; }
            public boolean open() { return false; }
            public Optional<TagSpec> tag(String name) { return Optional.empty(); }
        };
        var registry = ExtensionRegistry.of(List.of(new Ext("a.ext", 1, List.of(v))));
        assertSame(v, registry.vocabulary("demo").orElseThrow());
        assertTrue(io.suko.lang.project.ProjectView.EMPTY.entries().isEmpty());
    }
}
```

- [ ] **Step 2: Correr e ver falhar**

Run: `./gradlew :suko-core:test --tests io.suko.lang.ext.ExtensionRegistryTest`
Expected: FAIL a compilar (`io.suko.ext` não existe).

- [ ] **Step 3: Criar a API no `suko-api`**

`suko-api/src/main/java/io/suko/ext/ExtensionApi.java`:
```java
package io.suko.ext;

/** Versão major da API de extensões. O core só aceita extensões compiladas contra o mesmo major. */
public final class ExtensionApi {
    public static final int VERSION = 1;

    private ExtensionApi() {
    }
}
```

`SukoExtension.java`:
```java
package io.suko.ext;

/** Uma extensão de compile-time, descoberta por {@code ServiceLoader}. Corre só no build e no LSP. */
public interface SukoExtension {
    /** Identificador único e estável, ex.: {@code "io.suko.jte"}. */
    String id();

    /** Major da API contra a qual foi compilada; normalmente {@link ExtensionApi#VERSION}. */
    int apiVersion();

    void register(ExtensionContext ctx);
}
```

`ExtensionContext.java`:
```java
package io.suko.ext;

public interface ExtensionContext {
    void target(Target target);

    void vocabulary(Vocabulary vocabulary);

    void checker(Checker checker);
}
```

`Target.java`:
```java
package io.suko.ext;

import io.suko.lang.ast.ComponentDecl;

import java.util.Set;

/** Um alvo de compilação: transforma cada componente num ficheiro de output. */
public interface Target {
    /** Ex.: {@code "jte"}. É o nome usado em {@code targets}. */
    String id();

    /** O tipo Java que {@code Component} representa neste alvo (gancho do item 12), ex.: {@code "gg.jte.Content"}. */
    String componentType();

    /** Ids dos vocabulários de tags aceites por este alvo, ex.: {@code {"html"}}. */
    Set<String> vocabularies();

    Emitted emit(ComponentDecl component, EmitContext ctx);
}
```

`Emitted.java`:
```java
package io.suko.ext;

import io.suko.lang.ast.SourceMapEntry;

import java.util.List;

/** {@code relativePath} é relativo à pasta do .sk de origem, ex.: {@code "Hello.jte"}. */
public record Emitted(String relativePath, String source, List<SourceMapEntry> sourceMap) {
}
```

`EmitContext.java`:
```java
package io.suko.ext;

import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.ProjectView;

import java.util.Map;

/**
 * O que um alvo precisa para emitir um componente: o ficheiro, o projeto só
 * de leitura, os componentes importados (resolvidos como o compilador os
 * resolve) e o prefixo de package do ficheiro (ex.: {@code "ui."}).
 */
public record EmitContext(SukoFile file, ProjectView project,
                          Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix) {
}
```

`Vocabulary.java`:
```java
package io.suko.ext;

import java.util.Optional;

public interface Vocabulary {
    /** Ex.: {@code "html"}. */
    String id();

    /** {@code true}: aceita qualquer tag (o HTML do alvo JTE na fase 0). */
    boolean open();

    /** A tag, se for conhecida deste vocabulário. */
    Optional<TagSpec> tag(String name);
}
```

`TagSpec.java`:
```java
package io.suko.ext;

import java.util.Map;

/** {@code attributeTypes}: nome do atributo → tipo Java esperado (ex.: "spacing" → "int"). */
public record TagSpec(String name, Map<String, String> attributeTypes, boolean allowsChildren) {
}
```

`Checker.java`:
```java
package io.suko.ext;

import io.suko.lang.ast.SukoFile;

/** Regra extra do verificador. Corre depois do SemanticChecker, por ordem de id da extensão. */
public interface Checker {
    String id();

    void check(SukoFile file, CheckContext ctx);
}
```

`CheckContext.java`:
```java
package io.suko.ext;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectView;

public record CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics) {

    public void report(SukoDiagnostic.Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, fileName, span));
    }
}
```

`suko-api/src/main/java/io/suko/lang/project/ProjectView.java`:
```java
package io.suko.lang.project;

import io.suko.lang.ast.ImportDecl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Vista só de leitura do índice do projeto, para extensões. Implementada pelo ProjectIndex do core. */
public interface ProjectView {

    Collection<ProjectIndexEntry> entries();

    Optional<ProjectIndexEntry> resolveQualified(String qualifiedName);

    Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports);

    ProjectView EMPTY = new ProjectView() {
        public Collection<ProjectIndexEntry> entries() { return List.of(); }
        public Optional<ProjectIndexEntry> resolveQualified(String qualifiedName) { return Optional.empty(); }
        public Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports) { return Map.of(); }
    };
}
```

- [ ] **Step 4: `ProjectIndex implements ProjectView`**

Em `suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java`, mudar a declaração da classe para `public final class ProjectIndex implements ProjectView` (manter `final`/não-`final` como está) e acrescentar `@Override` aos três métodos que já existem com estas assinaturas (`entries()`, `resolveQualified(String)`, `resolveImports(List<ImportDecl>)`). Se o tipo de retorno de `entries()` for `java.util.Collection<ProjectIndexEntry>`, já bate; não mudar comportamento.

- [ ] **Step 5: Criar `ExtensionRegistry` no core**

`suko-core/src/main/java/io/suko/lang/ext/ExtensionRegistry.java`:
```java
package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.*;

/**
 * As extensões carregadas para um build (ou um projeto no LSP): alvos e
 * vocabulários por id, checkers por ordem estável de id da extensão.
 * Problemas de carregamento viram diagnósticos de projeto, nunca exceções.
 */
public final class ExtensionRegistry {

    /** Id da extensão JTE embutida; o LSP e o manifesto ignoram cópias dela. */
    public static final String BUILT_IN_JTE = "io.suko.jte";

    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<String, Vocabulary> vocabularies = new LinkedHashMap<>();
    private final List<Checker> checkers = new ArrayList<>();
    private final Map<Object, String> owners = new IdentityHashMap<>();
    private final List<SukoDiagnostic> loadDiagnostics = new ArrayList<>();

    private ExtensionRegistry() {
    }

    public static ExtensionRegistry load(ClassLoader loader) {
        List<SukoExtension> found = new ArrayList<>();
        for (SukoExtension extension : ServiceLoader.load(SukoExtension.class, loader)) {
            found.add(extension);
        }
        return of(found);
    }

    /** O registo do próprio classpath do compilador: o caso de quem não declara extensões. */
    public static ExtensionRegistry defaults() {
        return load(ExtensionRegistry.class.getClassLoader());
    }

    public static ExtensionRegistry of(List<SukoExtension> extensions) {
        ExtensionRegistry registry = new ExtensionRegistry();
        List<SukoExtension> sorted = new ArrayList<>(extensions);
        sorted.sort(Comparator.comparing(SukoExtension::id));
        Set<String> seenIds = new HashSet<>();
        for (SukoExtension extension : sorted) {
            if (!seenIds.add(extension.id())) {
                continue; // a mesma extensão vista duas vezes no classpath
            }
            registry.add(extension);
        }
        return registry;
    }

    private void add(SukoExtension extension) {
        String id = extension.id();
        if (extension.apiVersion() != ExtensionApi.VERSION) {
            error("EXTENSION_API_MISMATCH", "A extensão '" + id + "' foi compilada para a API de extensões "
                + extension.apiVersion() + ", mas este compilador usa a versão " + ExtensionApi.VERSION);
            return;
        }
        try {
            extension.register(new ExtensionContext() {
                public void target(Target target) {
                    claim(targets, target.id(), target, id, "alvo");
                }

                public void vocabulary(Vocabulary vocabulary) {
                    claim(vocabularies, vocabulary.id(), vocabulary, id, "vocabulário");
                }

                public void checker(Checker checker) {
                    checkers.add(checker);
                    owners.put(checker, id);
                }
            });
        } catch (RuntimeException e) {
            error("EXTENSION_FAILED", "A extensão '" + id + "' falhou ao registar-se: " + e.getMessage());
        }
    }

    private <T> void claim(Map<String, T> map, String key, T value, String extensionId, String kind) {
        T existing = map.get(key);
        if (existing != null) {
            error("EXTENSION_CONFLICT", "O " + kind + " '" + key + "' é fornecido por '" + owners.get(existing)
                + "' e por '" + extensionId + "'; fica o de '" + owners.get(existing) + "'");
            return;
        }
        map.put(key, value);
        owners.put(value, extensionId);
    }

    private void error(String code, String message) {
        loadDiagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR, message, code, null, SourceSpan.NONE));
    }

    public Optional<Target> target(String id) {
        return Optional.ofNullable(targets.get(id));
    }

    public Set<String> targetIds() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    public Optional<Vocabulary> vocabulary(String id) {
        return Optional.ofNullable(vocabularies.get(id));
    }

    /** Ordenados pelo id da extensão (as extensões são registadas por essa ordem). */
    public List<Checker> checkers() {
        return Collections.unmodifiableList(checkers);
    }

    public String ownerOf(Object contribution) {
        return owners.getOrDefault(contribution, "?");
    }

    public List<SukoDiagnostic> loadDiagnostics() {
        return Collections.unmodifiableList(loadDiagnostics);
    }
}
```

- [ ] **Step 6: Correr e ver passar**

Run: `./gradlew :suko-core:test --tests io.suko.lang.ext.ExtensionRegistryTest`
Expected: PASS (5 testes).

- [ ] **Step 7: Build inteiro**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL (nada usa ainda o registo; golden verde).

- [ ] **Step 8: Commit**

```bash
git add suko-api suko-core
git commit -m "feat(api): interfaces de extensão (Target, Vocabulary, Checker), ProjectView e ExtensionRegistry no core"
```

---

### Task 4: Módulo `suko-jte` — o alvo JTE como extensão

**Files:**
- Create: `suko-jte/build.gradle.kts`
- Modify: `settings.gradle.kts` (incluir `suko-jte`)
- Move: `suko-core/src/main/java/io/suko/lang/JteEmitter.java` → `suko-jte/src/main/java/io/suko/lang/JteEmitter.java` (`git mv`, mesmo package)
- Create: `suko-jte/src/main/java/io/suko/jte/JteTarget.java`
- Create: `suko-jte/src/main/java/io/suko/jte/HtmlVocabulary.java`
- Create: `suko-jte/src/main/java/io/suko/jte/JteExtension.java`
- Create: `suko-jte/src/main/resources/META-INF/services/io.suko.ext.SukoExtension`
- Test: `suko-jte/src/test/java/io/suko/jte/JteExtensionTest.java`

**Interfaces:**
- Consumes: `Target`, `Emitted`, `EmitContext`, `Vocabulary`, `SukoExtension`, `ExtensionApi` (Task 3); `JteEmitter(List<ComponentDecl>, Map<String, ProjectIndexEntry>, String)` e `JteEmitter.emitWithSourceMap(ComponentDecl)` (movidos sem alteração).
- Produces: extensão com id `"io.suko.jte"` (`= ExtensionRegistry.BUILT_IN_JTE`), alvo `"jte"` com `componentType() = "gg.jte.Content"` e `vocabularies() = {"html"}`, vocabulário `"html"` aberto. `JteTarget.emit` devolve `Emitted(component.name() + ".jte", <jte>, <sourceMap>)` — exatamente o que o `JteCompiler` produz hoje.

Nota: o `JteEmitter` não importa `gg.jte` (só escreve texto), por isso o `suko-jte` não precisa da biblioteca JTE em `main`; só nos testes que renderizam.

- [ ] **Step 1: Escrever o teste**

```java
package io.suko.jte;

import io.suko.ext.EmitContext;
import io.suko.ext.Emitted;
import io.suko.ext.ExtensionApi;
import io.suko.ext.SukoExtension;
import io.suko.lang.JteEmitter;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class JteExtensionTest {

    @Test
    void isDiscoverableByServiceLoader() {
        List<String> ids = ServiceLoader.load(SukoExtension.class).stream()
            .map(p -> p.get().id()).toList();
        assertTrue(ids.contains("io.suko.jte"), ids.toString());
    }

    @Test
    void targetDeclaresComponentTypeAndHtmlVocabulary() {
        JteTarget target = new JteTarget();
        assertEquals("jte", target.id());
        assertEquals("gg.jte.Content", target.componentType());
        assertEquals(java.util.Set.of("html"), target.vocabularies());
        assertTrue(new HtmlVocabulary().open());
        assertEquals(ExtensionApi.VERSION, new JteExtension().apiVersion());
    }

    @Test
    void emitIsExactlyWhatTheEmitterProduces() {
        ComponentDecl hello = new ComponentDecl("Hello", List.of(), List.of(), List.of(),
            io.suko.lang.ast.SourceSpan.NONE, false);
        SukoFile file = new SukoFile(java.util.Optional.empty(), List.of(), List.of(hello));
        Emitted emitted = new JteTarget().emit(hello, new EmitContext(file, ProjectView.EMPTY, Map.of(), ""));
        var expected = new JteEmitter(file.components(), Map.of(), "").emitWithSourceMap(hello);
        assertEquals("Hello.jte", emitted.relativePath());
        assertEquals(expected.jteSource(), emitted.source());
        assertEquals(expected.sourceMap(), emitted.sourceMap());
    }
}
```

(Construtor de conveniência real: `ComponentDecl(String name, List<String> typeParameters, List<Param> params, List<Statement> body, SourceSpan span, boolean isPublic)`.)

- [ ] **Step 2: Correr e ver falhar**

Run: `./gradlew :suko-jte:test`
Expected: FAIL (o módulo ainda não existe).

- [ ] **Step 3: Criar o módulo**

`suko-jte/build.gradle.kts`:
```kotlin
plugins {
    id("java-library")
}

// Alvo JTE como extensão (13a): depende só do contrato, nunca do compilador.
dependencies {
    api(project(":suko-api"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

Acrescentar `"suko-jte"` ao `include(...)` do `settings.gradle.kts`, a seguir a `"suko-api"`.

```bash
mkdir -p suko-jte/src/main/java/io/suko/lang suko-jte/src/main/java/io/suko/jte suko-jte/src/main/resources/META-INF/services
git mv suko-core/src/main/java/io/suko/lang/JteEmitter.java suko-jte/src/main/java/io/suko/lang/JteEmitter.java
```

`suko-jte/src/main/java/io/suko/jte/JteTarget.java`:
```java
package io.suko.jte;

import io.suko.ext.EmitContext;
import io.suko.ext.Emitted;
import io.suko.ext.Target;
import io.suko.lang.JteEmitter;
import io.suko.lang.ast.ComponentDecl;

import java.util.Set;

/** O alvo de sempre: cada componente vira um .jte, com o emitter do subprojeto 1 intacto. */
public final class JteTarget implements Target {

    public String id() {
        return "jte";
    }

    public String componentType() {
        return "gg.jte.Content";
    }

    public Set<String> vocabularies() {
        return Set.of("html");
    }

    public Emitted emit(ComponentDecl component, EmitContext ctx) {
        JteEmitter emitter = new JteEmitter(ctx.file().components(), ctx.importedByShortName(), ctx.packagePrefix());
        JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
        return new Emitted(component.name() + ".jte", result.jteSource(), result.sourceMap());
    }
}
```

`HtmlVocabulary.java`:
```java
package io.suko.jte;

import io.suko.ext.TagSpec;
import io.suko.ext.Vocabulary;

import java.util.Optional;

/**
 * HTML aberto: aceita qualquer tag. Na fase 0 não há regras de HTML a impor
 * (o SemanticChecker nunca as teve; o escape é do próprio JTE em runtime).
 */
public final class HtmlVocabulary implements Vocabulary {

    public String id() {
        return "html";
    }

    public boolean open() {
        return true;
    }

    public Optional<TagSpec> tag(String name) {
        return Optional.empty();
    }
}
```

`JteExtension.java`:
```java
package io.suko.jte;

import io.suko.ext.ExtensionApi;
import io.suko.ext.ExtensionContext;
import io.suko.ext.SukoExtension;

public final class JteExtension implements SukoExtension {

    public String id() {
        return "io.suko.jte";
    }

    public int apiVersion() {
        return ExtensionApi.VERSION;
    }

    public void register(ExtensionContext ctx) {
        ctx.target(new JteTarget());
        ctx.vocabulary(new HtmlVocabulary());
    }
}
```

`suko-jte/src/main/resources/META-INF/services/io.suko.ext.SukoExtension`:
```
io.suko.jte.JteExtension
```

- [ ] **Step 4: Manter o core a compilar nesta task**

O `JteCompiler` (core) ainda chama `new JteEmitter(...)` diretamente; até à Task 5 o core precisa do `suko-jte` em `main`. Em `suko-core/build.gradle.kts` acrescentar **temporariamente** (a Task 5 remove):

```kotlin
    implementation(project(":suko-jte")) // TEMPORÁRIO: removido na Task 5
```

e, para os testes e o testFixtures que usam `JteEmitter` diretamente:

```kotlin
    testImplementation(project(":suko-jte"))
    testFixturesImplementation(project(":suko-jte"))
```

- [ ] **Step 5: Correr os testes**

Run: `./gradlew :suko-jte:test :suko-core:test`
Expected: PASS, incluindo `GoldenParityTest`.

- [ ] **Step 6: Commit**

```bash
git add -A settings.gradle.kts suko-jte suko-core
git commit -m "feat(jte): módulo suko-jte com o alvo JTE como extensão (JteEmitter movido, mesmo package)"
```

---

### Task 5: O core compila através do registo de extensões

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/JteCompiler.java`
- Modify: `suko-core/src/main/java/io/suko/lang/project/SukoProjectCompiler.java`
- Create: `suko-core/src/main/java/io/suko/lang/ext/VocabularyChecker.java`
- Modify: `suko-core/build.gradle.kts` (remover o `implementation(project(":suko-jte"))` temporário)
- Modify (dependência de runtime `suko-jte`): `suko-gradle-plugin/build.gradle.kts`, `suko-maven-plugin/build.gradle.kts`, `suko-website/build.gradle.kts`, `suko-lsp/build.gradle.kts`, `suko-components/build.gradle.kts`, `suko-cli/build.gradle.kts`, `suko-registry-generator/build.gradle.kts` (só se os seus testes compilarem)
- Test: `suko-core/src/test/java/io/suko/lang/ext/ExtensionPipelineTest.java`

**Interfaces:**
- Consumes: `ExtensionRegistry` (Task 3), `JteTarget` via `ServiceLoader` (Task 4).
- Produces:
  - `JteCompiler(String fileName, String sukoSource, ExtensionRegistry registry, List<String> targets)`; o construtor de 2 argumentos passa a delegar com `ExtensionRegistry.defaults()` e `List.of("jte")`.
  - `JteCompiler.CompileResult` ganha a componente `Map<String, Map<String, String>> generatedByTarget` (alvo → nome relativo → fonte), com construtor de compatibilidade de 3 argumentos; `generatedJteSources` continua a ter as saídas do primeiro alvo (com um só alvo, exatamente o de hoje).
  - `SukoProjectCompiler(ExtensionRegistry registry, List<String> targets)`; o construtor sem argumentos delega com `defaults()` e `List.of("jte")`.
  - `SukoProjectCompiler.ProjectCompileResult` ganha `List<SukoDiagnostic> projectDiagnostics` (com construtor de compatibilidade de 3 argumentos). Caminhos em `generatedJteSources`: um alvo → `<subdirDoPackage>/<relativePath>`; vários → `<targetId>/<subdirDoPackage>/<relativePath>`.
  - `ProjectAnalysis` (do `analyze`) passa a incluir os diagnósticos dos `Checker` e do `VocabularyChecker` por ficheiro.
  - Códigos novos emitidos: `TARGET_NOT_FOUND`, `VOCABULARY_NOT_FOUND` (projeto), `UNKNOWN_TAG`, `EXTENSION_FAILED` (ficheiro).

- [ ] **Step 1: Escrever os testes do pipeline**

```java
package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionPipelineTest {

    static final class Demo implements SukoExtension {
        final boolean throwInCheck;
        final boolean throwInEmit;

        Demo(boolean throwInCheck, boolean throwInEmit) {
            this.throwInCheck = throwInCheck;
            this.throwInEmit = throwInEmit;
        }

        public String id() { return "test.demo"; }
        public int apiVersion() { return ExtensionApi.VERSION; }

        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "demo"; }
                public String componentType() { return "demo.Node"; }
                public Set<String> vocabularies() { return Set.of("demo"); }
                public Emitted emit(ComponentDecl c, EmitContext e) {
                    if (throwInEmit) throw new IllegalStateException("emit boom");
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
                    if (throwInCheck) throw new IllegalStateException("check boom");
                    for (ComponentDecl c : file.components()) {
                        ctx.report(SukoDiagnostic.Severity.WARNING, "DEMO_CHECK", "visto: " + c.name(), c.span());
                    }
                }
            });
        }
    }

    static ExtensionRegistry registry(SukoExtension... extra) {
        List<SukoExtension> all = new ArrayList<>(List.of(new io.suko.jte.JteExtension()));
        all.addAll(List.of(extra));
        return ExtensionRegistry.of(all);
    }

    static Path write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        return f;
    }

    @Test
    void defaultCompilerStillEmitsJteWithTheOldPaths(@TempDir Path root) throws Exception {
        write(root, "a/b/Hello.sk", "package a.b;\n\ncomponent Hello() {\n  <p>Olá</p>\n}\n");
        var result = new SukoProjectCompiler().compile(root);
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertEquals(Set.of(Path.of("a/b/Hello.jte")), result.generatedJteSources().keySet());
        assertTrue(result.projectDiagnostics().isEmpty());
    }

    @Test
    void multipleTargetsPrefixTheTargetBeforeThePackageDir(@TempDir Path root) throws Exception {
        write(root, "a/b/Hello.sk", "package a.b;\n\ncomponent Hello() {\n  <box><label>x</label></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"))
            .compile(root);
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertEquals(Set.of(Path.of("jte/a/b/Hello.jte"), Path.of("demo/a/b/Hello.demo")),
            result.generatedJteSources().keySet());
        assertEquals("demo:Hello\n", result.generatedJteSources().get(Path.of("demo/a/b/Hello.demo")));
    }

    @Test
    void checkerWarningsAppearInAnalyzeAndCompile(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var compiler = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"));
        var analysis = compiler.analyze(SukoSources.fromDirectory(root));
        var codes = analysis.files().get(Path.of("Hello.sk")).diagnostics().getDiagnostics()
            .stream().map(SukoDiagnostic::code).toList();
        assertTrue(codes.contains("DEMO_CHECK"), codes.toString());
        assertTrue(compiler.compile(root).success());
    }

    @Test
    void tagOutsideAClosedVocabularyIsUnknownTag(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <div></div>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var d = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors().get(0);
        assertEquals("UNKNOWN_TAG", d.code());
        assertTrue(d.message().contains("div") && d.message().contains("demo"), d.message());
    }

    @Test
    void onlyJteNeverReportsUnknownTag(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <made-up-tag></made-up-tag>\n}\n");
        assertTrue(new SukoProjectCompiler().compile(root).success());
    }

    @Test
    void unknownTargetIsAProjectError(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <p>x</p>\n}\n");
        var result = new SukoProjectCompiler(registry(), List.of("jte", "swing")).compile(root);
        assertFalse(result.success());
        var d = result.projectDiagnostics().get(0);
        assertEquals("TARGET_NOT_FOUND", d.code());
        assertTrue(d.message().contains("swing") && d.message().contains("jte"), d.message());
    }

    @Test
    void checkerThatThrowsBecomesExtensionFailed(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(true, false)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var d = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors().get(0);
        assertEquals("EXTENSION_FAILED", d.code());
        assertTrue(d.message().contains("test.demo") && d.message().contains("check boom"), d.message());
    }

    @Test
    void targetThatThrowsBecomesExtensionFailed(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, true)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var codes = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors()
            .stream().map(SukoDiagnostic::code).toList();
        assertEquals(List.of("EXTENSION_FAILED"), codes);
    }

    @Test
    void singleFileCompilerKeepsItsOldContract() {
        var result = new JteCompiler("Hello.sk", "component Hello() {\n  <p>x</p>\n}\n").compile();
        assertTrue(result.success());
        assertEquals(Set.of("Hello.jte"), result.generatedJteSources().keySet());
    }
}
```

- [ ] **Step 2: Correr e ver falhar**

Run: `./gradlew :suko-core:test --tests io.suko.lang.ext.ExtensionPipelineTest`
Expected: FAIL a compilar (construtores novos não existem).

- [ ] **Step 3: `VocabularyChecker`**

`suko-core/src/main/java/io/suko/lang/ext/VocabularyChecker.java`:
```java
package io.suko.lang.ext;

import io.suko.ext.Target;
import io.suko.ext.Vocabulary;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;

/**
 * Para cada alvo pedido, cada tag tem de ser aceite por pelo menos um dos
 * seus vocabulários; um vocabulário aberto aceita tudo. Só com o alvo jte
 * (HTML aberto) nunca dispara.
 */
public final class VocabularyChecker {

    private VocabularyChecker() {
    }

    public static void check(SukoFile file, String fileName, List<Target> targets, ExtensionRegistry registry,
                      DiagnosticCollector diagnostics) {
        for (Target target : targets) {
            List<Vocabulary> vocabularies = target.vocabularies().stream()
                .flatMap(id -> registry.vocabulary(id).stream()).toList();
            if (vocabularies.stream().anyMatch(Vocabulary::open)) {
                continue;
            }
            for (ComponentDecl component : file.components()) {
                walk(component.body(), target, vocabularies, fileName, diagnostics);
            }
        }
    }

    private static void walk(List<Statement> statements, Target target, List<Vocabulary> vocabularies,
                             String fileName, DiagnosticCollector diagnostics) {
        for (Statement statement : statements) {
            switch (statement) {
                case Statement.HtmlElement e -> {
                    boolean known = vocabularies.stream().anyMatch(v -> v.tag(e.tagName()).isPresent());
                    if (!known) {
                        diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                            "A tag '" + e.tagName() + "' não existe no alvo '" + target.id() + "' (vocabulários: "
                                + String.join(", ", target.vocabularies()) + ")",
                            "UNKNOWN_TAG", fileName, e.span()));
                    }
                    walk(e.children(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.IfStmt s -> {
                    walk(s.thenBranch(), target, vocabularies, fileName, diagnostics);
                    walk(s.elseBranch(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.ForStmt s -> walk(s.body(), target, vocabularies, fileName, diagnostics);
                case Statement.SwitchStmt s -> {
                    for (Statement.SwitchCase c : s.cases()) {
                        walk(c.body(), target, vocabularies, fileName, diagnostics);
                    }
                    walk(s.defaultCase(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), target, vocabularies, fileName, diagnostics);
                    }
                }
                default -> {
                }
            }
        }
    }
}
```

(Se algum `List` destes records puder ser `null` — ex.: `elseBranch` sem `else` — confirmar no `SukoAstBuilder` e proteger com `List.of()`; a suite do `SemanticChecker` já percorre a mesma árvore e é a referência.)

- [ ] **Step 4: `JteCompiler` sem `JteEmitter`**

Em `suko-core/src/main/java/io/suko/lang/JteCompiler.java`:

1. Campos e construtores:
```java
    private final String fileName;
    private final String sukoSource;
    private final ExtensionRegistry registry;
    private final List<String> targets;

    public JteCompiler(String fileName, String sukoSource) {
        this(fileName, sukoSource, ExtensionRegistry.defaults(), List.of("jte"));
    }

    public JteCompiler(String fileName, String sukoSource, ExtensionRegistry registry, List<String> targets) {
        this.fileName = fileName;
        this.sukoSource = sukoSource;
        this.registry = registry;
        this.targets = List.copyOf(targets);
    }
```
2. `CompileResult` ganha `generatedByTarget`:
```java
    public record CompileResult(
        boolean success,
        DiagnosticCollector diagnostics,
        Map<String, String> generatedJteSources,
        Map<String, Map<String, String>> generatedByTarget
    ) {
        public CompileResult(boolean success, DiagnosticCollector diagnostics, Map<String, String> generatedJteSources) {
            this(success, diagnostics, generatedJteSources, Map.of());
        }

        public static CompileResult success(Map<String, String> jteSources) {
            return new CompileResult(true, new DiagnosticCollector(), jteSources);
        }

        public static CompileResult failure(DiagnosticCollector diagnostics) {
            return new CompileResult(false, diagnostics, Map.of());
        }
    }
```
3. Depois do `SemanticChecker` em `compile()` e em `analyze(...)`, correr as extensões (método novo):
```java
    private void runExtensionChecks(SukoFile sukoFile, ProjectView project, DiagnosticCollector diagnostics) {
        List<Target> resolved = targets.stream().flatMap(id -> registry.target(id).stream()).toList();
        VocabularyChecker.check(sukoFile, fileName, resolved, registry, diagnostics);
        for (Checker checker : registry.checkers()) {
            try {
                checker.check(sukoFile, new CheckContext(fileName, project, diagnostics));
            } catch (RuntimeException e) {
                diagnostics.add(new SukoDiagnostic(Severity.ERROR,
                    "A extensão '" + registry.ownerOf(checker) + "' falhou em " + fileName + " (" + checker.id()
                        + "): " + e.getMessage(), "EXTENSION_FAILED", fileName, SourceSpan.NONE));
            }
        }
    }
```
Chamar `runExtensionChecks(sukoFile, ProjectView.EMPTY, diagnostics)` em `compile()` (antes do `if (diagnostics.hasErrors())`) e `runExtensionChecks(sukoFile, projectIndex, diagnostics)` em `analyze(...)` (depois do `SemanticChecker`).(`VocabularyChecker.check` é `public static` — chamado do package `io.suko.lang`. Imports novos no `JteCompiler`: `io.suko.ext.*`, `io.suko.lang.ext.ExtensionRegistry`, `io.suko.lang.ext.VocabularyChecker`, `io.suko.lang.project.ProjectView`.)
4. `emitAll` emite por alvo:
```java
    private CompileResult emitAll(SukoFile sukoFile, ProjectView project,
                                  Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        Map<String, Map<String, String>> byTarget = new LinkedHashMap<>();
        EmitContext ctx = new EmitContext(sukoFile, project, importedByShortName, packagePrefix);
        for (String targetId : targets) {
            Target target = registry.target(targetId).orElse(null);
            if (target == null) {
                continue; // TARGET_NOT_FOUND é diagnóstico de projeto (SukoProjectCompiler)
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            for (ComponentDecl component : sukoFile.components()) {
                try {
                    Emitted emitted = target.emit(component, ctx);
                    outputs.put(emitted.relativePath(), emitted.source());
                } catch (RuntimeException e) {
                    diagnostics.add(new SukoDiagnostic(Severity.ERROR,
                        "A extensão '" + registry.ownerOf(target) + "' falhou ao emitir " + component.name()
                            + " para o alvo '" + targetId + "': " + e.getMessage(),
                        "EXTENSION_FAILED", fileName, component.span()));
                }
            }
            byTarget.put(targetId, outputs);
        }
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }
        Map<String, String> first = byTarget.isEmpty() ? Map.of() : byTarget.values().iterator().next();
        return new CompileResult(true, new DiagnosticCollector(), first, byTarget);
    }
```
e os dois chamadores: `compile()` → `emitAll(sukoFile, ProjectView.EMPTY, Map.of(), "")`; `compile(ProjectIndex, Path)` → `emitAll(sukoFile, projectIndex, importedByShortName, currentPackagePrefix)` (os mesmos valores que hoje passa ao `JteEmitter`). Remover o import/uso de `JteEmitter`.

- [ ] **Step 5: `SukoProjectCompiler` com registo, alvos e diagnósticos de projeto**

Em `suko-core/src/main/java/io/suko/lang/project/SukoProjectCompiler.java`:
```java
    private final ExtensionRegistry registry;
    private final List<String> targets;

    public SukoProjectCompiler() {
        this(ExtensionRegistry.defaults(), List.of("jte"));
    }

    public SukoProjectCompiler(ExtensionRegistry registry, List<String> targets) {
        this.registry = registry;
        this.targets = List.copyOf(targets);
    }

    public record ProjectCompileResult(
        boolean success,
        Map<Path, DiagnosticCollector> diagnosticsByFile,
        Map<Path, String> generatedJteSources,
        List<SukoDiagnostic> projectDiagnostics
    ) {
        public ProjectCompileResult(boolean success, Map<Path, DiagnosticCollector> diagnosticsByFile,
                                    Map<Path, String> generatedJteSources) {
            this(success, diagnosticsByFile, generatedJteSources, List.of());
        }
    }

    /** Problemas do registo e alvos/vocabulários pedidos que não existem. */
    public List<SukoDiagnostic> projectDiagnostics() {
        List<SukoDiagnostic> out = new ArrayList<>(registry.loadDiagnostics());
        for (String id : targets) {
            var target = registry.target(id);
            if (target.isEmpty()) {
                out.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "O alvo '" + id + "' não é fornecido por nenhuma extensão; disponíveis: "
                        + String.join(", ", new java.util.TreeSet<>(registry.targetIds())),
                    "TARGET_NOT_FOUND", null, io.suko.lang.ast.SourceSpan.NONE));
                continue;
            }
            for (String vocabulary : target.get().vocabularies()) {
                if (registry.vocabulary(vocabulary).isEmpty()) {
                    out.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                        "O alvo '" + id + "' usa o vocabulário '" + vocabulary + "', que nenhuma extensão fornece",
                        "VOCABULARY_NOT_FOUND", null, io.suko.lang.ast.SourceSpan.NONE));
                }
            }
        }
        return out;
    }
```
- `new JteCompiler(relative.toString(), text)` passa a `new JteCompiler(relative.toString(), text, registry, targets)` nos dois sítios (`analyze` e `compile`).
- Em `compile(SukoSources)`: `List<SukoDiagnostic> project = projectDiagnostics();` no início; `success` começa `duplicateDiagnostics.isEmpty() && project.stream().noneMatch(d -> d.severity() == ERROR)`; o ciclo de escrita passa a:
```java
            Path outputSubDir = relative.getParent() == null ? Path.of("") : relative.getParent();
            boolean multi = targets.size() > 1;
            for (var targetEntry : result.generatedByTarget().entrySet()) {
                Path base = multi ? Path.of(targetEntry.getKey()).resolve(outputSubDir) : outputSubDir;
                for (var entry : targetEntry.getValue().entrySet()) {
                    generatedJteSources.put(base.resolve(entry.getKey()), entry.getValue());
                }
            }
```
- devolver `new ProjectCompileResult(success, diagnosticsByFile, generatedJteSources, project)`.
- Em `analyze`, acrescentar os diagnósticos de projeto ao `ProjectAnalysis` só se o record tiver onde os pôr; senão expor `projectDiagnostics()` público (acima) e o LSP chama-o (Task 8). **Não** alterar o record `ProjectAnalysis`.

- [ ] **Step 6: O core deixa de depender do `suko-jte` em `main`**

Em `suko-core/build.gradle.kts`: remover `implementation(project(":suko-jte")) // TEMPORÁRIO`, remover `implementation("gg.jte:jte:3.1.12")` **só se** nenhum ficheiro de `suko-core/src/main` importar `gg.jte` (verificado a 2026-10-04: nenhum importa; o `JavacTask` só tem a string `"gg.jte.Content"`). Manter `testImplementation(project(":suko-jte"))`, `testFixturesImplementation(project(":suko-jte"))` e `testFixturesImplementation("gg.jte:jte:3.1.12")`; acrescentar `testRuntimeOnly("gg.jte:jte:3.1.12")` se algum teste do core renderizar com o motor real.

- [ ] **Step 7: Os consumidores trazem o `suko-jte` por omissão**

Acrescentar ao bloco `dependencies` de cada um:
- `suko-gradle-plugin/build.gradle.kts`: `implementation(project(":suko-jte"))`
- `suko-maven-plugin/build.gradle.kts`: `implementation(project(":suko-jte"))`
- `suko-website/build.gradle.kts`: `implementation(project(":suko-jte"))`
- `suko-lsp/build.gradle.kts`: `implementation(project(":suko-jte"))` (o fat jar passa a incluí-lo)
- `suko-components/build.gradle.kts`: `testImplementation(project(":suko-jte"))`
- `suko-cli/build.gradle.kts`: `testImplementation(project(":suko-jte"))` (o `FullCycleTest` usa o plugin Gradle)
- `suko-registry-generator`: não compila para nenhum alvo; só acrescentar se os seus testes falharem por `TARGET_NOT_FOUND`.

Atenção ao fat jar do `suko-lsp` e a qualquer outro fat jar: o ficheiro `META-INF/services/io.suko.ext.SukoExtension` tem de sobreviver ao empacotamento (se a task `Jar` própria usar `duplicatesStrategy = EXCLUDE`, garantir que só existe um ficheiro destes — o do `suko-jte`).

- [ ] **Step 8: Correr os testes novos e a suite inteira**

Run: `./gradlew :suko-core:test --tests io.suko.lang.ext.ExtensionPipelineTest`
Expected: PASS (9 testes).

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL, `GoldenParityTest` PASS, nenhuma asserção alterada. Se um módulo falhar com `TARGET_NOT_FOUND`, falta-lhe o `suko-jte` no classpath (Step 7).

- [ ] **Step 9: Commit**

```bash
git add -A suko-core suko-gradle-plugin suko-maven-plugin suko-website suko-lsp suko-components suko-cli suko-registry-generator
git commit -m "feat(core): compila através do registo de extensões (alvos, vocabulários, checkers); core sem JTE"
```

---

### Task 6: Extensão de teste e o plugin Gradle (`sukoExtensions`, `targets`, `extensions.json`)

**Files:**
- Create: `suko-test-ext/build.gradle.kts`, `suko-test-ext/src/main/java/io/suko/testext/DemoExtension.java`, `suko-test-ext/src/main/resources/META-INF/services/io.suko.ext.SukoExtension`
- Modify: `settings.gradle.kts` (incluir `suko-test-ext`)
- Create: `suko-core/src/main/java/io/suko/lang/ext/ExtensionManifest.java`
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoGradlePlugin.java`, `SukoExtension.java`, `SukoCompileTask.java`, `SukoWatchTask.java`
- Modify: `suko-gradle-plugin/build.gradle.kts` (o teste recebe o jar da `test-ext`)
- Test: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoExtensionsTestKitTest.java`, `suko-core/src/test/java/io/suko/lang/ext/ExtensionManifestTest.java`

**Interfaces:**
- Consumes: `SukoProjectCompiler(ExtensionRegistry, List<String>)`, `ProjectCompileResult.projectDiagnostics()`, `ExtensionRegistry.load(ClassLoader)` (Tasks 3 e 5).
- Produces:
  - `suko-test-ext`: extensão id `"io.suko.testext"`, alvo `"demo"` (emite `<Nome>.demo` com `demo:<Nome>\n`, vocabulário `"demo"`), vocabulário `"demo"` fechado com `box` e `label`, checker `"demo-check"` que emite `WARNING DEMO_CHECK "visto: <Nome>"` por componente e lança `IllegalStateException("boom")` para um componente chamado `Boom`.
  - `ExtensionManifest` (core): `static void write(Path file, List<Path> extensionJars, List<String> targets)` e `static List<Path> extensionJars(ClassLoader loader)` (os jars que fornecem `META-INF/services/io.suko.ext.SukoExtension`, **excluindo** os que só fornecem `io.suko.jte.JteExtension`). Formato: `{"classpath":["<abs>",...],"targets":["jte",...]}` em UTF-8.
  - Gradle: configuração `sukoExtensions`; `suko { targets = listOf("jte", "demo") }` (`ListProperty<String>`, convenção `["jte"]`); `sukoCompile` escreve `build/suko/extensions.json` e imprime `projectDiagnostics`.

- [ ] **Step 1: Teste do manifesto**

```java
package io.suko.lang.ext;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionManifestTest {

    @Test
    void writesClasspathAndTargetsAsJson(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("build/suko/extensions.json");
        ExtensionManifest.write(file, List.of(Path.of("/a/x.jar"), Path.of("/b/y \"q\".jar")), List.of("jte", "demo"));
        String json = Files.readString(file);
        assertEquals("{\"classpath\":[\"/a/x.jar\",\"/b/y \\\"q\\\".jar\"],\"targets\":[\"jte\",\"demo\"]}", json);
    }

    @Test
    void manifestNeverListsTheBuiltInJteJar() {
        // No classpath dos testes do core só está o suko-jte (embutido): nada a listar.
        assertEquals(List.of(), ExtensionManifest.extensionJars(ExtensionManifestTest.class.getClassLoader()));
    }
}
```

(Em Windows os caminhos têm `\`; o teste usa caminhos POSIX literais, e `write` só os escapa — não os normaliza.)

- [ ] **Step 2: `ExtensionManifest`**

```java
package io.suko.lang.ext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * O ficheiro que o build escreve (build/suko/extensions.json ou
 * target/suko/extensions.json) para o LSP saber que extensões o projeto usa.
 * Fica no diretório de build e não no suko.json: tem caminhos absolutos da
 * máquina (spec do 13a, D4).
 */
public final class ExtensionManifest {

    static final String SERVICE = "META-INF/services/io.suko.ext.SukoExtension";
    static final Set<String> BUILT_IN_PROVIDERS = Set.of("io.suko.jte.JteExtension");

    private ExtensionManifest() {
    }

    public static void write(Path file, List<Path> extensionJars, List<String> targets) {
        String json = "{\"classpath\":" + array(extensionJars.stream().map(Path::toString).toList())
            + ",\"targets\":" + array(targets) + "}";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jars do classloader que declaram extensões, sem o JTE embutido (o LSP já o traz). */
    public static List<Path> extensionJars(ClassLoader loader) {
        List<Path> jars = new ArrayList<>();
        try {
            for (URL url : Collections.list(loader.getResources(SERVICE))) {
                if (!"jar".equals(url.getProtocol())) {
                    continue; // diretórios de classes (testes, IDE): não vão para o manifesto
                }
                Set<String> providers = providersOf(url);
                if (BUILT_IN_PROVIDERS.containsAll(providers)) {
                    continue;
                }
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                jars.add(Path.of(connection.getJarFileURL().toURI()));
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Não foi possível listar as extensões do classpath", e);
        }
        return jars;
    }

    private static Set<String> providersOf(URL url) throws IOException {
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(l -> l.replaceAll("#.*", "").trim()).filter(l -> !l.isEmpty())
                .collect(Collectors.toSet());
        }
    }

    private static String array(List<String> values) {
        return values.stream().map(ExtensionManifest::quote).collect(Collectors.joining(",", "[", "]"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
```

Run: `./gradlew :suko-core:test --tests io.suko.lang.ext.ExtensionManifestTest`
Expected: PASS.

- [ ] **Step 3: Módulo `suko-test-ext`** (só para testes; nunca publicado)

`suko-test-ext/build.gradle.kts`:
```kotlin
plugins {
    id("java")
}

// Extensão mínima que prova a API do 13a nos três pontos de entrada.
// Não é publicada nem usada fora de testes.
dependencies {
    compileOnly(project(":suko-api"))
}
```
Acrescentar `"suko-test-ext"` ao `include(...)`.

`suko-test-ext/src/main/java/io/suko/testext/DemoExtension.java`:
```java
package io.suko.testext;

import io.suko.ext.*;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DemoExtension implements SukoExtension {

    public String id() {
        return "io.suko.testext";
    }

    public int apiVersion() {
        return ExtensionApi.VERSION;
    }

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
                    if (c.name().equals("Boom")) {
                        // Para os testes de robustez (Task 8): uma extensão que rebenta.
                        throw new IllegalStateException("boom");
                    }
                    ctx.report(SukoDiagnostic.Severity.WARNING, "DEMO_CHECK", "visto: " + c.name(), c.span());
                }
            }
        });
    }
}
```
`suko-test-ext/src/main/resources/META-INF/services/io.suko.ext.SukoExtension`:
```
io.suko.testext.DemoExtension
```

Run: `./gradlew :suko-test-ext:jar`
Expected: BUILD SUCCESSFUL; `suko-test-ext/build/libs/suko-test-ext-0.1.0-SNAPSHOT.jar`.

- [ ] **Step 4: Teste TestKit (falha primeiro)**

`suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoExtensionsTestKitTest.java`:
```java
package io.suko.lang.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SukoExtensionsTestKitTest {

    @Test
    void extensionFromSukoExtensionsRunsAndWritesManifest(@TempDir Path project) throws Exception {
        String extJar = System.getProperty("suko.testExtJar").replace('\\', '/');
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"demo-app\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            dependencies { "sukoExtensions"(files("%s")) }
            suko { targets.set(listOf("jte", "demo")) }
            """.formatted(extJar));
        Path sk = project.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(sk.getParent());
        Files.writeString(sk, "package ui;\n\ncomponent Hello() {\n  <box><label>x</label></box>\n}\n");

        BuildResult result = GradleRunner.create().withProjectDir(project.toFile())
            .withPluginClasspath().withArguments("sukoCompile", "--stacktrace").build();

        Path out = project.resolve("build/generated-src/suko");
        assertTrue(Files.exists(out.resolve("jte/ui/Hello.jte")), result.getOutput());
        assertEquals("demo:Hello\n", Files.readString(out.resolve("demo/ui/Hello.demo")));
        assertTrue(result.getOutput().contains("DEMO_CHECK"), result.getOutput());
        String manifest = Files.readString(project.resolve("build/suko/extensions.json"));
        assertTrue(manifest.contains("suko-test-ext") && manifest.contains("\"demo\""), manifest);
    }

    @Test
    void projectWithoutExtensionsIsUnchanged(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"plain\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), "plugins { id(\"io.suko.lang\") }\n");
        Path sk = project.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(sk.getParent());
        Files.writeString(sk, "package ui;\n\ncomponent Hello() {\n  <p>x</p>\n}\n");
        GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").build();
        assertTrue(Files.exists(project.resolve("build/generated-src/suko/ui/Hello.jte")));
    }
}
```

Em `suko-gradle-plugin/build.gradle.kts`, no `tasks.test { }`:
```kotlin
    val testExtJar = project(":suko-test-ext").tasks.named<Jar>("jar")
    dependsOn(testExtJar)
    systemProperty("suko.testExtJar", testExtJar.get().archiveFile.get().asFile.absolutePath)
```

Run: `./gradlew :suko-gradle-plugin:test --tests io.suko.lang.gradle.SukoExtensionsTestKitTest`
Expected: FAIL (configuração `sukoExtensions` e `targets` não existem).

- [ ] **Step 5: Implementar no plugin**

`SukoExtension.java` (o da extensão Gradle `suko { }`, não confundir com a interface da API): acrescentar

```java
    private final ListProperty<String> targets;
    // no construtor:
        this.targets = objectFactory.listProperty(String.class);
    public ListProperty<String> getTargets() {
        return targets;
    }
```
(import `org.gradle.api.provider.ListProperty`.)

`SukoGradlePlugin.apply`:
```java
        extension.getTargets().convention(java.util.List.of("jte"));
        var sukoExtensions = project.getConfigurations().create("sukoExtensions", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setDescription("Extensões de compile-time do Suko (alvos, vocabulários, checkers)");
        });
```
e em cada `register(...)` das duas tasks: `task.extensionClasspath = sukoExtensions;`.

`SukoBaseTask` (é onde vive o campo `protected SukoExtension extension;` partilhado pelas duas tasks): acrescentar

```java
    protected org.gradle.api.file.FileCollection extensionClasspath;

    @org.gradle.api.tasks.Classpath
    public org.gradle.api.file.FileCollection getExtensionClasspath() {
        return extensionClasspath;
    }

    @Input
    public java.util.List<String> getTargets() {
        return getExtension().getTargets().get();
    }
```

e no `SukoExtension` (Gradle), que já guarda a `ProjectLayout`:

```java
    public Path getExtensionManifestPath() {
        return projectLayout.getBuildDirectory().file("suko/extensions.json").get().getAsFile().toPath();
    }
```

`SukoCompileTask.compile()` — trocar a criação do compilador por:
```java
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        for (java.io.File f : getExtensionClasspath().getFiles()) {
            try {
                urls.add(f.toURI().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        java.util.List<String> targets = getTargets();
        try (java.net.URLClassLoader loader = new java.net.URLClassLoader(
                urls.toArray(new java.net.URL[0]), SukoCompileTask.class.getClassLoader())) {
            io.suko.lang.ext.ExtensionRegistry registry = io.suko.lang.ext.ExtensionRegistry.load(loader);
            io.suko.lang.ext.ExtensionManifest.write(getExtension().getExtensionManifestPath(),
                getExtensionClasspath().getFiles().stream().map(java.io.File::toPath).toList(), targets);
            var result = new io.suko.lang.project.SukoProjectCompiler(registry, targets).compile(sourceDir);
            for (var d : result.projectDiagnostics()) {
                getLogger().error("[{}] {}: {}", d.severity(), d.code(), d.message());
            }
            // ... o resto do método atual (escrever ficheiros, imprimir diagnósticos, falhar se !success)
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
```
(O manifesto é escrito mesmo com a lista vazia: o LSP distingue "sem extensões" de "sem build".)

`SukoWatchTask`: a mesma construção do registo (mesmo classloader) e `new SukoProjectCompiler(registry, targets)` no sítio onde hoje chama `new SukoProjectCompiler()`; fechar o classloader quando o watch termina.

- [ ] **Step 6: Correr**

Run: `./gradlew :suko-gradle-plugin:test`
Expected: PASS (testes antigos e os 2 novos).

- [ ] **Step 7: Commit**

```bash
git add -A settings.gradle.kts suko-test-ext suko-core suko-gradle-plugin
git commit -m "feat(gradle): configuração sukoExtensions, targets e build/suko/extensions.json; extensão de teste"
```

---

### Task 7: Plugin Maven (`targets`, extensões nas `<dependencies>` do plugin)

**Files:**
- Modify: `suko-maven-plugin/src/main/java/io/suko/lang/maven/SukoCompileMojo.java`
- Modify: `suko-maven-plugin/src/main/resources/META-INF/maven/plugin.xml`
- Modify: `suko-maven-plugin/build.gradle.kts` (o teste recebe o jar da `test-ext`)
- Test: `suko-maven-plugin/src/test/java/io/suko/lang/maven/SukoCompileMojoExtensionsTest.java`

**Interfaces:**
- Consumes: `ExtensionRegistry.load`, `ExtensionManifest.write`/`extensionJars`, `SukoProjectCompiler(ExtensionRegistry, List<String>)` (Tasks 3, 5, 6).
- Produces: parâmetro `targets` (`java.util.List`, propriedade `suko.targets`, por omissão `jte`); escreve `${project.build.directory}/suko/extensions.json`; um método package-private `ClassLoader extensionLoader()` que devolve o classloader do plugin (as `<dependencies>` do plugin já lá estão) — sobreposto no teste.

- [ ] **Step 1: Teste ao nível da Mojo (falha primeiro)**

Ler `SukoCompileMojoProjectTest.java` e seguir a forma como ele instancia a Mojo e injeta os campos `@Parameter` (reflexão sobre os campos privados). Novo teste:

```java
package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SukoCompileMojoExtensionsTest {

    @Test
    void pluginDependencyExtensionRunsForRequestedTargets(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("src/main/suko/ui/Hello.sk");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package ui;\n\ncomponent Hello() {\n  <box></box>\n}\n");
        Path out = dir.resolve("target/generated-sources/suko");
        URL ext = Path.of(System.getProperty("suko.testExtJar")).toUri().toURL();
        ClassLoader pluginLoader = new URLClassLoader(new URL[] {ext}, SukoCompileMojo.class.getClassLoader());

        SukoCompileMojo mojo = new SukoCompileMojo() {
            @Override
            ClassLoader extensionLoader() {
                return pluginLoader;
            }
        };
        set(mojo, "sourceDir", dir.resolve("src/main/suko").toFile());
        set(mojo, "outputDir", out.toFile());
        set(mojo, "buildDirectory", dir.resolve("target").toFile());
        set(mojo, "targets", List.of("jte", "demo"));
        mojo.execute();

        assertTrue(Files.exists(out.resolve("jte/ui/Hello.jte")));
        assertEquals("demo:Hello\n", Files.readString(out.resolve("demo/ui/Hello.demo")));
        String manifest = Files.readString(dir.resolve("target/suko/extensions.json"));
        assertTrue(manifest.contains("suko-test-ext"), manifest);
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field f = SukoCompileMojo.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
```

No `suko-maven-plugin/build.gradle.kts`, o mesmo bloco do Step 4 da Task 6 (`dependsOn` do jar da `test-ext` e `systemProperty("suko.testExtJar", ...)`) dentro de `tasks.test`.

Run: `./gradlew :suko-maven-plugin:test --tests io.suko.lang.maven.SukoCompileMojoExtensionsTest`
Expected: FAIL (campos `targets`/`buildDirectory` e `extensionLoader()` não existem).

- [ ] **Step 2: Implementar na Mojo**

Campos novos:
```java
    @Parameter(property = "suko.targets", defaultValue = "jte")
    private java.util.List<String> targets;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    private File buildDirectory;

    /** O classloader do plugin: as extensões declaradas em <plugin><dependencies> já estão nele. */
    ClassLoader extensionLoader() {
        return SukoCompileMojo.class.getClassLoader();
    }
```
Em `execute()`, trocar `new io.suko.lang.project.SukoProjectCompiler().compile(...)` por:
```java
            java.util.List<String> requested = targets == null || targets.isEmpty() ? java.util.List.of("jte") : targets;
            ClassLoader loader = extensionLoader();
            io.suko.lang.ext.ExtensionRegistry registry = io.suko.lang.ext.ExtensionRegistry.load(loader);
            io.suko.lang.ext.ExtensionManifest.write(buildDirectory.toPath().resolve("suko/extensions.json"),
                io.suko.lang.ext.ExtensionManifest.extensionJars(loader), requested);
            io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
                new io.suko.lang.project.SukoProjectCompiler(registry, requested).compile(sourceDir.toPath());
            for (var d : result.projectDiagnostics()) {
                getLog().error("[" + d.severity() + "] " + d.code() + ": " + d.message());
            }
```
(o resto do método fica igual).

- [ ] **Step 3: `plugin.xml`** — dentro de `<parameters>` acrescentar:

```xml
        <parameter>
          <name>targets</name>
          <type>java.util.List</type>
          <required>false</required>
          <editable>true</editable>
          <description>Alvos de compilação (ids de Target das extensões). Por omissão: jte.</description>
        </parameter>
        <parameter>
          <name>buildDirectory</name>
          <type>java.io.File</type>
          <required>false</required>
          <editable>false</editable>
          <description>Diretório de build; recebe suko/extensions.json para o language server.</description>
        </parameter>
```
e dentro de `<configuration>`:
```xml
        <targets implementation="java.util.List" default-value="jte">${suko.targets}</targets>
        <buildDirectory implementation="java.io.File" default-value="${project.build.directory}"/>
```

- [ ] **Step 4: Correr os testes do módulo**

Run: `./gradlew :suko-maven-plugin:test`
Expected: PASS — inclui o `PluginDescriptorConsistencyTest`, que tem de aceitar os dois parâmetros novos (se falhar, o XML e os campos não batem: corrigir o XML, não o teste).

- [ ] **Step 5: Commit**

```bash
git add suko-maven-plugin
git commit -m "feat(maven): parâmetro targets, extensões nas dependencies do plugin e target/suko/extensions.json"
```

---

### Task 8: LSP carrega extensões só em workspace confiável

**Files:**
- Create: `suko-lsp/src/main/java/io/suko/lsp/ProjectExtensions.java`
- Modify: `suko-lsp/src/main/java/io/suko/lsp/Project.java`, `Workspace.java`, `SukoLanguageServer.java`, `SukoWorkspaceService.java` (ler `trusted` das `initializationOptions`), `DiagnosticsService.java` (publicar `projectDiagnostics`)
- Modify: `editors/vscode/src/extension.ts` (passar `trusted`)
- Modify: `suko-lsp/build.gradle.kts` (o teste recebe o jar da `test-ext`)
- Modify: `suko-lsp/src/test/java/io/suko/lsp/TestSupport.java` (construtor com opções de inicialização)
- Test: `suko-lsp/src/test/java/io/suko/lsp/ExtensionsTest.java`

**Interfaces:**
- Consumes: `ExtensionRegistry`, `ExtensionManifest` (formato JSON da Task 6), `SukoProjectCompiler(ExtensionRegistry, List<String>)`, `SukoProjectCompiler.projectDiagnostics()`.
- Produces:
  - `ProjectExtensions.forProject(Path sourceRoot, Path workspaceFolder, boolean trusted)` → `record Loaded(ExtensionRegistry registry, List<String> targets, Optional<String> notice)`. Procura, a subir do `sourceRoot` até ao `workspaceFolder` (inclusive), o primeiro `build/suko/extensions.json` ou `target/suko/extensions.json`. Sem ficheiro → só o embutido, `targets = ["jte"]`. Com ficheiro e `trusted == false` → só o embutido, `targets = ["jte"]`, e `notice` com a explicação (o server faz `window/logMessage` uma vez). Com ficheiro e confiável → `URLClassLoader(classpath, ProjectExtensions.class.getClassLoader())`, registo carregado, `targets` do ficheiro; jars cujo único provider seja `io.suko.jte.JteExtension` são ignorados (o `ExtensionRegistry.of` já descarta ids repetidos).
  - Opção de inicialização `trusted` (booleano; ausente = `false`).

- [ ] **Step 1: Testes (falham primeiro)** — seguir o harness de `DiagnosticsTest.java`/`TestSupport.java` para arrancar o server em memória e esperar diagnósticos.

```java
package io.suko.lsp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionsTest {

    static Path projectWithManifest(Path root, List<String> classpath) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        Files.writeString(root.resolve("src/main/suko/Hello.sk"), "component Hello() {\n  <box></box>\n}\n");
        io.suko.lang.ext.ExtensionManifest.write(root.resolve("build/suko/extensions.json"),
            classpath.stream().map(Path::of).toList(), List.of("jte", "demo"));
        return root;
    }

    static String extJar() {
        return System.getProperty("suko.testExtJar");
    }

    @Test
    void trustedWorkspaceRunsTheProjectExtensions(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertEquals(List.of("jte", "demo"), loaded.targets());
        assertTrue(loaded.registry().target("demo").isPresent());
        assertTrue(loaded.notice().isEmpty());
    }

    @Test
    void untrustedWorkspaceIgnoresExtensions(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, false);
        assertEquals(List.of("jte"), loaded.targets());
        assertTrue(loaded.registry().target("demo").isEmpty());
        assertTrue(loaded.registry().target("jte").isPresent());
        assertTrue(loaded.notice().orElseThrow().contains("confi"), loaded.notice().toString());
    }

    @Test
    void noManifestMeansOnlyTheBuiltInJte(@TempDir Path root) throws Exception {
        Files.createDirectories(root.resolve("src/main/suko"));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertEquals(List.of("jte"), loaded.targets());
        assertTrue(loaded.registry().loadDiagnostics().isEmpty());
    }

    @Test
    void duplicateJteFromManifestIsIgnored(@TempDir Path root) throws Exception {
        String jteJar = Path.of(io.suko.jte.JteExtension.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI()).toString();
        projectWithManifest(root, List.of(jteJar, extJar()));
        var loaded = ProjectExtensions.forProject(root.resolve("src/main/suko"), root, true);
        assertTrue(loaded.registry().loadDiagnostics().isEmpty(), loaded.registry().loadDiagnostics().toString());
    }

    static com.google.gson.JsonObject trusted(boolean value) {
        com.google.gson.JsonObject options = new com.google.gson.JsonObject();
        options.addProperty("trusted", value);
        return options;
    }

    static List<String> codes(TestSupport t, String file) {
        return t.client.lastFor(t.uri(file)).getDiagnostics().stream()
            .map(d -> d.getCode().getLeft()).toList();
    }

    @Test
    void trustedExtensionCheckerShowsUpAsEditorDiagnostic(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar())); // antes do server: o projeto é descoberto no initialize
        TestSupport t = new TestSupport(root, trusted(true));
        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Hello.sk").contains("DEMO_CHECK"), codes(t, "Hello.sk").toString());
    }

    @Test
    void untrustedServerDoesNotRunTheCheckerButStillWorks(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        TestSupport t = new TestSupport(root, trusted(false));
        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertFalse(codes(t, "Hello.sk").contains("DEMO_CHECK"), codes(t, "Hello.sk").toString());
    }

    @Test
    void crashingExtensionDoesNotKillServer(@TempDir Path root) throws Exception {
        projectWithManifest(root, List.of(extJar()));
        TestSupport t = new TestSupport(root, trusted(true));
        t.open("Boom.sk", "component Boom() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Boom.sk").contains("EXTENSION_FAILED"), codes(t, "Boom.sk").toString());

        t.open("Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        t.scheduler.fire();
        assertTrue(codes(t, "Hello.sk").contains("DEMO_CHECK"), "o server continua vivo depois da falha");
    }
}
```

`TestSupport` ganha um construtor com opções de inicialização (o atual delega com `null`):

```java
    TestSupport(Path folder) throws IOException {
        this(folder, null);
    }

    TestSupport(Path folder, Object initializationOptions) throws IOException {
        this.folder = folder;
        this.root = Files.createDirectories(folder.resolve("src/main/suko"));
        this.server = new SukoLanguageServer(scheduler, DiagnosticsService.DEBOUNCE_MILLIS);
        server.connect(client);
        InitializeParams params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(folder.toUri().toString(), "ws")));
        params.setInitializationOptions(initializationOptions);
        server.initialize(params).join();
    }
```

(O `projectWithManifest` cria `src/main/suko/Hello.sk` no disco; os testes abrem os ficheiros com o mesmo texto, por isso a sobreposição do editor e o disco coincidem.)

No `suko-lsp/build.gradle.kts`, `tasks.test`: o mesmo bloco `dependsOn`/`systemProperty("suko.testExtJar", ...)`.

Run: `./gradlew :suko-lsp:test --tests io.suko.lsp.ExtensionsTest`
Expected: FAIL (`ProjectExtensions` não existe).

- [ ] **Step 2: `ProjectExtensions`**

```java
package io.suko.lsp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.suko.lang.ext.ExtensionRegistry;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extensões de um projeto no editor. Lê o manifesto que o build escreveu e só
 * carrega código de terceiros num workspace confiável (spec do 13a).
 */
final class ProjectExtensions {

    record Loaded(ExtensionRegistry registry, List<String> targets, Optional<String> notice) {
    }

    private ProjectExtensions() {
    }

    static Loaded forProject(Path sourceRoot, Path workspaceFolder, boolean trusted) {
        Optional<Path> manifest = findManifest(sourceRoot, workspaceFolder);
        Loaded builtIn = new Loaded(ExtensionRegistry.defaults(), List.of("jte"), Optional.empty());
        if (manifest.isEmpty()) {
            return builtIn;
        }
        if (!trusted) {
            return new Loaded(builtIn.registry(), builtIn.targets(), Optional.of(
                "Suko: extensões do projeto ignoradas porque o workspace não é confiável (" + manifest.get() + ")"));
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(manifest.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            List<URL> urls = new ArrayList<>();
            for (var e : json.getAsJsonArray("classpath")) {
                urls.add(Path.of(e.getAsString()).toUri().toURL());
            }
            List<String> targets = new ArrayList<>();
            for (var e : json.getAsJsonArray("targets")) {
                targets.add(e.getAsString());
            }
            // Não se fecha: o registo vive enquanto o projeto viver no server.
            URLClassLoader loader = new URLClassLoader(urls.toArray(new URL[0]),
                ProjectExtensions.class.getClassLoader());
            return new Loaded(ExtensionRegistry.load(loader), targets.isEmpty() ? List.of("jte") : targets,
                Optional.empty());
        } catch (Exception e) {
            return new Loaded(builtIn.registry(), builtIn.targets(), Optional.of(
                "Suko: não foi possível ler " + manifest.get() + ": " + e.getMessage()));
        }
    }

    private static Optional<Path> findManifest(Path sourceRoot, Path workspaceFolder) {
        Path stop = workspaceFolder.toAbsolutePath().normalize();
        for (Path dir = sourceRoot.toAbsolutePath().normalize(); dir != null; dir = dir.getParent()) {
            for (String candidate : List.of("build/suko/extensions.json", "target/suko/extensions.json")) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
            if (dir.equals(stop)) {
                break;
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 3: Ligar ao `Project`, `Workspace` e ao arranque**

- `SukoWorkspaceService`: método `static boolean trustedFrom(Object initializationOptions)` que lê `{"trusted": true}` (ausente/não-booleano → `false`), no mesmo estilo de `sourceRootFrom`.
- `SukoLanguageServer.initialize`: `boolean trusted = SukoWorkspaceService.trustedFrom(params.getInitializationOptions());` e passá-lo ao `workspace.configure(folders, sourceRootSetting, trusted)`.
- `Workspace.configure(List<Path>, String, boolean)`: guardar `trusted`; em `rediscover`, ao criar um `Project`, passar `ProjectExtensions.forProject(root, folder, trusted)`. Manter um `configure(List<Path>, String)` que delega com `false` só se houver chamadores que não seja o server (testes).
- `Project`: novo construtor `Project(Path root, ProjectExtensions.Loaded extensions)`; o atual `Project(Path root)` delega com `new ProjectExtensions.Loaded(ExtensionRegistry.defaults(), List.of("jte"), Optional.empty())`. No sítio onde hoje faz `new SukoProjectCompiler().analyze(sources())`, passar a `new SukoProjectCompiler(extensions.registry(), extensions.targets()).analyze(sources())`. Expor `Optional<String> notice()` e `List<SukoDiagnostic> projectDiagnostics()` (= `new SukoProjectCompiler(...).projectDiagnostics()`).
- `SukoLanguageServer`/`Workspace`: depois de `rediscover`, para cada projeto com `notice()` presente, enviar uma vez `client.logMessage(new MessageParams(MessageType.Warning, notice))`.
- `DiagnosticsService`: publicar os `projectDiagnostics()` do projeto como diagnósticos do primeiro ficheiro aberto desse projeto, com `range` 0:0 (o LSP não tem um URI de "projeto"); se não houver ficheiro aberto, só `logMessage`.

- [ ] **Step 4: Cliente VSCode passa `trusted`** — em `editors/vscode/src/extension.ts`:

```ts
    initializationOptions: {
      sourceRoot: config.get<string>('sourceRoot', ''),
      // O server só carrega extensões do projeto (código de terceiros) se o workspace for confiável.
      trusted: vscode.workspace.isTrusted,
    },
```

Run: `cd editors/vscode && npm run compile` (ou o script de build existente em `package.json`)
Expected: compila sem erros de TypeScript.

- [ ] **Step 5: Correr**

Run: `./gradlew :suko-lsp:test`
Expected: PASS (testes antigos e os 7 novos).

- [ ] **Step 6: Revisão de segurança** — despachar o `security-specialist` sobre `ProjectExtensions.java`, a leitura de `trusted` e o `URLClassLoader` (critérios: nenhuma extensão externa carregada sem `trusted == true`; o manifesto é lido mas nunca executado sem confiança; falhas de leitura não derrubam o server). Corrigir o que apontar antes do commit.

- [ ] **Step 7: Commit**

```bash
git add suko-lsp editors/vscode/src/extension.ts
git commit -m "feat(lsp): extensões do projeto via extensions.json, só em workspace confiável"
```

---

### Task 9: Documentação e verificação final

**Files:**
- Modify: `ARCHITECTURE.md` (estrutura de módulos, diagrama do pipeline, item 13 → 13a CONCLUÍDO, códigos de erro novos)
- Modify: `suko-gradle-plugin/README.md` / `suko-maven-plugin/README.md` / `suko-lsp/README.md` (se existirem; senão uma secção no README da raiz): `sukoExtensions`, `targets`, `extensions.json`, regra de confiança
- Create: `suko-api/README.md` (o contrato: os três pontos de extensão, `ExtensionApi.VERSION`, regras de conflito e falha, como registar por `ServiceLoader`, exemplo mínimo = a `DemoExtension`)

**Interfaces:**
- Consumes: tudo o que as Tasks 1–8 produziram.
- Produces: documentação; nenhum código.

- [ ] **Step 1: `ARCHITECTURE.md`**
  - Na lista/diagrama de módulos: `suko-api` (contrato, só JDK), `suko-jte` (alvo JTE embutido), `suko-test-ext` (só testes); `suko-core` "sem JTE, com `ExtensionRegistry`".
  - No pipeline: depois do `SemanticChecker`, "checkers e vocabulários das extensões"; a emissão "por alvo (`Target`)".
  - Item 13: acrescentar "**13a — CONCLUÍDO**" com spec e plano, e a lista dos códigos `EXTENSION_CONFLICT`, `EXTENSION_API_MISMATCH`, `TARGET_NOT_FOUND`, `VOCABULARY_NOT_FOUND`, `UNKNOWN_TAG`, `EXTENSION_FAILED`.
  - Registar as lacunas da spec resolvidas aqui (secção "Lacunas da spec resolvidas neste plano").

- [ ] **Step 2: READMEs** com os exemplos reais:

```kotlin
// build.gradle.kts de um projeto
dependencies { "sukoExtensions"("io.exemplo:suko-minha-extensao:1.0.0") }
suko { targets.set(listOf("jte", "demo")) }
```
```xml
<!-- pom.xml -->
<plugin>
  <groupId>io.suko</groupId>
  <artifactId>suko-maven-plugin</artifactId>
  <configuration><targets><target>jte</target><target>demo</target></targets></configuration>
  <dependencies>
    <dependency><groupId>io.exemplo</groupId><artifactId>suko-minha-extensao</artifactId><version>1.0.0</version></dependency>
  </dependencies>
</plugin>
```

- [ ] **Step 3: Verificação final**

Run: `./gradlew clean build`
Expected: BUILD SUCCESSFUL; `GoldenParityTest` PASS (prova byte a byte); nenhuma asserção de teste antiga alterada — confirmar com `git diff main -- '*/src/test/**' | grep '^-.*assert'` vazio, salvo os testes novos.

Run: `git grep -n "import io.suko.lang.JteEmitter\|new JteEmitter" -- suko-core/src/main`
Expected: nenhum resultado (o core já não conhece o JTE).

- [ ] **Step 4: Revisão final** — despachar o `architect` para a revisão do ramo inteiro contra a spec (`docs/superpowers/specs/2026-10-04-suko-api-extensoes.md`) e este plano; corrigir o que for material.

- [ ] **Step 5: Commit**

```bash
git add ARCHITECTURE.md suko-api/README.md suko-gradle-plugin suko-maven-plugin suko-lsp README.md
git commit -m "docs: 13a concluído — módulos suko-api/suko-jte, extensões, alvos e códigos de erro"
```
