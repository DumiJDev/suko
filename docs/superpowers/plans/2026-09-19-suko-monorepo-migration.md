# Suko Monorepo Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reorganize the Suko repository from a single root Gradle module into a 5-module monorepo (`suko-core`, `suko-gradle-plugin`, `suko-maven-plugin`, `suko-components`, `suko-website`), preserving all existing behavior and test coverage exactly.

**Architecture:** Pure structural migration — no new language features, no new diagnostics, no new build logic beyond module wiring. `suko-core` gets the compiler (grammar, AST, checker, emitter, project resolver); `suko-gradle-plugin` gets the existing `io.suko.lang.gradle` package carved out unchanged; `suko-maven-plugin` gets repointed at `suko-core`; `suko-components`/`suko-website` are empty scaffolds for future subprojetos 7/9. The root becomes a pure Gradle aggregator with no source of its own.

**Tech Stack:** Gradle 9.5 multi-project build (Kotlin DSL), Java 21, ANTLR4 4.13.1, `gg.jte` 3.1.12, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md`

## Global Constraints

- No `gradlew` is committed in this repository — every command in this plan uses the system `gradle`, never `./gradlew`.
- Any relocation of the ANTLR grammar files (`SukoLexer.g4`/`SukoParser.g4`) requires running the module's `generateSukoLexer`/`generateSukoParser` tasks afterward and confirming the ANTLR tool prints no `warning` lines before proceeding.
- Task headers in this document are literally `### Task N: ...` in English — the `scripts/task-brief` tool used by subagent-driven-development matches `Task[ \t]+N` and will not find a task titled any other way.
- Git inside this harness's worktrees has an intermittent sandbox bug (an "rtk" classifier error) on bare `git status`/`git diff`/`git add`/`git mv`. The workaround, which always works, is to invoke git via its absolute path: `/usr/bin/git <subcommand>` instead of bare `git <subcommand>`. Use the absolute path for every git command in every task, including `git mv`.
- Use `git mv` (never delete+recreate) for every file/directory relocation in this plan, to preserve history/blame.
- This is a reorganization, not new development: "TDD" here means running the relevant module's test suite (and, for the final task, the whole-repo suite) before and after each structural change to confirm nothing broke — not writing new tests for behavior that doesn't exist yet (the new `suko-components`/`suko-website` modules have no logic to test, only structure).
- Every module shares `group = "io.suko"` and `version = "0.1.0-SNAPSHOT"`, set once in the root `build.gradle.kts`'s `subprojects {}` block — no module's own `build.gradle.kts` repeats these.
- `examples/` (loose `.sk` reference files at the repo root) is explicitly **not** migrated by this plan — it stays exactly where it is.

---

### Task 1: Extract `suko-core`, convert the root to a pure aggregator, repoint `suko-maven-plugin`

This is one atomic task: the root module's Java/ANTLR source has to move to `suko-core` in the same step that the root build script stops trying to compile it and `suko-maven-plugin` stops depending on the (now source-less) root project — splitting these into separate commits would leave a broken build checked into git history in between.

**Files:**
- Move (via `git mv`): every file under `src/main/java/io/suko/lang/` **except** the `gradle/` subpackage, all of `src/main/antlr/`, every file under `src/test/java/io/suko/lang/` **except** the `gradle/` subpackage, and all of `src/test/resources/` — into the equivalent path under `suko-core/`.
- Create: `suko-core/build.gradle.kts`
- Modify: `settings.gradle.kts` (repo root)
- Modify: `build.gradle.kts` (repo root)
- Modify: `suko-maven-plugin/build.gradle.kts`
- Delete: `build.gradle.kts.backup` (repo root)

**Interfaces:**
- Consumes: nothing from an earlier task (this is the first task).
- Produces: the Gradle module `:suko-core`, containing packages `io.suko.lang`, `io.suko.lang.ast`, `io.suko.lang.diagnostic`, `io.suko.lang.project`, `io.suko.lang.semantic`, `io.suko.lang.symbol` unchanged. Task 2 depends on `io.suko.lang.gradle` (not yet moved by this task) still compiling as loose files at the repo root, orphaned from any module, until Task 2 moves it. Tasks 2, 3 and 4 each reference the module coordinate `project(":suko-core")` in their own `build.gradle.kts`.

- [ ] **Step 1: Record the starting test count**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`. Note the total test count reported (should be 117, matching the state at the end of subprojeto 5) — this is the baseline this task's own verification (Step 13) will be measured against.

- [ ] **Step 2: Move the core `main/java` sources**

```bash
mkdir -p suko-core/src/main/java/io/suko/lang
/usr/bin/git mv src/main/java/io/suko/lang/JavacTask.java suko-core/src/main/java/io/suko/lang/JavacTask.java
/usr/bin/git mv src/main/java/io/suko/lang/JteCompiler.java suko-core/src/main/java/io/suko/lang/JteCompiler.java
/usr/bin/git mv src/main/java/io/suko/lang/JteEmitter.java suko-core/src/main/java/io/suko/lang/JteEmitter.java
/usr/bin/git mv src/main/java/io/suko/lang/SukoAstBuilder.java suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java
/usr/bin/git mv src/main/java/io/suko/lang/ast suko-core/src/main/java/io/suko/lang/ast
/usr/bin/git mv src/main/java/io/suko/lang/diagnostic suko-core/src/main/java/io/suko/lang/diagnostic
/usr/bin/git mv src/main/java/io/suko/lang/project suko-core/src/main/java/io/suko/lang/project
/usr/bin/git mv src/main/java/io/suko/lang/semantic suko-core/src/main/java/io/suko/lang/semantic
/usr/bin/git mv src/main/java/io/suko/lang/symbol suko-core/src/main/java/io/suko/lang/symbol
```

Expected: no errors. `src/main/java/io/suko/lang/` now contains only the `gradle/` subdirectory (5 files: `SukoBaseTask.java`, `SukoCompileTask.java`, `SukoExtension.java`, `SukoGradlePlugin.java`, `SukoWatchTask.java`) — leave those exactly where they are, Task 2 moves them.

- [ ] **Step 3: Move the ANTLR grammar**

```bash
/usr/bin/git mv src/main/antlr suko-core/src/main/antlr
```

Expected: `suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4` and `SukoParser.g4` now exist; `src/main/antlr/` no longer exists.

- [ ] **Step 4: Move the core `test/java` sources**

```bash
mkdir -p suko-core/src/test/java/io/suko/lang
/usr/bin/git mv src/test/java/io/suko/lang/ComponentValueProbeTest.java suko-core/src/test/java/io/suko/lang/ComponentValueProbeTest.java
/usr/bin/git mv src/test/java/io/suko/lang/DiagnosticCollectorTest.java suko-core/src/test/java/io/suko/lang/DiagnosticCollectorTest.java
/usr/bin/git mv src/test/java/io/suko/lang/FinalReviewFixesTest.java suko-core/src/test/java/io/suko/lang/FinalReviewFixesTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JavacTaskTest.java suko-core/src/test/java/io/suko/lang/JavacTaskTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JteCompilerTest.java suko-core/src/test/java/io/suko/lang/JteCompilerTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java suko-core/src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JteEmitterProjectTest.java suko-core/src/test/java/io/suko/lang/JteEmitterProjectTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JteEmitterSourceMapTest.java suko-core/src/test/java/io/suko/lang/JteEmitterSourceMapTest.java
/usr/bin/git mv src/test/java/io/suko/lang/JteEmitterTest.java suko-core/src/test/java/io/suko/lang/JteEmitterTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SemanticCheckerTest.java suko-core/src/test/java/io/suko/lang/SemanticCheckerTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SukoAstBuilderTest.java suko-core/src/test/java/io/suko/lang/SukoAstBuilderTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SukoAstBuilderVisibilityImportTest.java suko-core/src/test/java/io/suko/lang/SukoAstBuilderVisibilityImportTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SukoEndToEndTest.java suko-core/src/test/java/io/suko/lang/SukoEndToEndTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SukoGrammarFixesTest.java suko-core/src/test/java/io/suko/lang/SukoGrammarFixesTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SukoParserSmokeTest.java suko-core/src/test/java/io/suko/lang/SukoParserSmokeTest.java
/usr/bin/git mv src/test/java/io/suko/lang/SymbolTableTest.java suko-core/src/test/java/io/suko/lang/SymbolTableTest.java
/usr/bin/git mv src/test/java/io/suko/lang/project suko-core/src/test/java/io/suko/lang/project
/usr/bin/git mv src/test/java/io/suko/lang/semantic suko-core/src/test/java/io/suko/lang/semantic
/usr/bin/git mv src/test/java/io/suko/lang/support suko-core/src/test/java/io/suko/lang/support
```

Expected: `src/test/java/io/suko/lang/` now contains only the `gradle/` subdirectory (`SukoGradlePluginTest.java`, `SukoWatchTaskE2ETest.java`) — leave those, Task 2 moves them.

- [ ] **Step 5: Move the test resources**

```bash
/usr/bin/git mv src/test/resources suko-core/src/test/resources
```

Expected: `suko-core/src/test/resources/golden/Card.jte` and `NavLink.jte` now exist.

- [ ] **Step 6: Fix the moved tests' `examples/` fixture paths**

Several tests moved in Step 4 read fixture files under `examples/` with bare relative paths that assume the JVM's working directory is the repository root — true today only because the root module's project directory *is* the repository root. Gradle's `Test` task defaults its working directory to the *module's own* project directory. `examples/` deliberately stays at the repo root, one level *above* `suko-core/` (per this migration's spec) — so each of these paths needs a `../` prefix to keep resolving to the same file. This is the only test-code change in this plan; everything else is a pure physical move.

Note what does **not** need this fix, so it's not touched by mistake: `JteEmitterGoldenFileTest.java`'s two `Path.of("src/test/resources/golden/...")` reads (lines 27, 35) are correct as-is — `src/test/resources/` moved *with* this module in Step 5, so it's still directly under `suko-core/`, exactly where the default (module-relative) working directory expects it. And `SemanticCheckerTest.java`'s two `"examples/Card.sk"` references (lines 21, 25) never touch the filesystem — the first is just a label string passed to `SemanticChecker`'s constructor for diagnostic messages, the second is `new java.io.File("examples/Card.sk").toString()`, which returns the constructor argument unchanged with no I/O, and its result (`sukoSource`) is never even read afterward. Leave that file untouched.

In `suko-core/src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java`, replace every occurrence of the literal string `"examples/Card.sk"` with `"../examples/Card.sk"` (2 occurrences: lines 25 and 33).

In `suko-core/src/test/java/io/suko/lang/FinalReviewFixesTest.java`, replace every occurrence of the literal string `"examples/layout/LayoutComponents.sk"` with `"../examples/layout/LayoutComponents.sk"` (2 occurrences: lines 43 and 55).

In `suko-core/src/test/java/io/suko/lang/SukoEndToEndTest.java`, replace the literal string `"examples/Card.sk"` with `"../examples/Card.sk"` (1 occurrence: line 29).

In `suko-core/src/test/java/io/suko/lang/SukoParserSmokeTest.java`, replace these 4 lines:

```java
        parseFile("examples/Card.sk", 4, "Card, NavLink, Page, AdminPanel");
```
```java
        parseFile("examples/dashboard/Dashboard.sk", 4, "NavLink, Dashboard, AdminPanel, ManagerPanel");
```
```java
        parseFile("examples/forms/Forms.sk", 2, "LoginForm, RegistrationForm");
```
```java
        parseFile("examples/layout/LayoutComponents.sk", 7, "Layout, Card, Modal, Button, Input, Select, ItemList");
```

with:

```java
        parseFile("../examples/Card.sk", 4, "Card, NavLink, Page, AdminPanel");
```
```java
        parseFile("../examples/dashboard/Dashboard.sk", 4, "NavLink, Dashboard, AdminPanel, ManagerPanel");
```
```java
        parseFile("../examples/forms/Forms.sk", 2, "LoginForm, RegistrationForm");
```
```java
        parseFile("../examples/layout/LayoutComponents.sk", 7, "Layout, Card, Modal, Button, Input, Select, ItemList");
```

- [ ] **Step 7: Create `suko-core/build.gradle.kts`**

This is the ANTLR generation logic and dependency set from the current root `build.gradle.kts`, relocated verbatim, **minus** `compileOnly(gradleApi())`/`testImplementation(gradleApi())`/`testImplementation(gradleTestKit())` (those only supported the `io.suko.lang.gradle` package, which no longer lives in this module), **minus** `group`/`version` (now set once for every module by the root's `subprojects {}` block, Step 10). No working-directory override is needed — Step 6 already fixed the only paths that would have broken.

```kotlin
plugins {
    id("java")
    id("antlr")
}

repositories {
    mavenCentral()
}

dependencies {
    antlr("org.antlr:antlr4:4.13.1")

    implementation("gg.jte:jte:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named("generateGrammarSource") {
    enabled = false
}

val grammarDir = layout.projectDirectory.dir("src/main/antlr/io/suko/lang")
val antlrOutputDir = layout.buildDirectory.dir("generated-src/antlr/main/io/suko/lang")

val generateSukoLexer = tasks.register<JavaExec>("generateSukoLexer") {
    val outDir = antlrOutputDir.get().asFile
    doFirst { outDir.mkdirs() }

    classpath = configurations["antlr"]
    mainClass.set("org.antlr.v4.Tool")

    args = listOf(
        "-visitor",
        "-package", "io.suko.lang",
        "-o", outDir.path,
        grammarDir.file("SukoLexer.g4").asFile.path
    )

    inputs.file(grammarDir.file("SukoLexer.g4"))
    outputs.dir(outDir)
}

val generateSukoParser = tasks.register<JavaExec>("generateSukoParser") {
    dependsOn(generateSukoLexer)

    val outDir = antlrOutputDir.get().asFile
    doFirst { outDir.mkdirs() }

    classpath = configurations["antlr"]
    mainClass.set("org.antlr.v4.Tool")

    args = listOf(
        "-visitor",
        "-package", "io.suko.lang",
        "-lib", outDir.path,
        "-o", outDir.path,
        grammarDir.file("SukoParser.g4").asFile.path
    )

    inputs.file(grammarDir.file("SukoParser.g4"))
    outputs.dir(outDir)
}

sourceSets {
    main {
        java {
            srcDir(antlrOutputDir)
        }
    }
}

tasks.compileJava {
    dependsOn(generateSukoParser)
}

tasks.test {
    useJUnitPlatform()
    dependsOn(generateSukoParser)
}
```

- [ ] **Step 8: Run the ANTLR generation tasks and confirm no warnings**

Run: `gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser`
Expected: `BUILD SUCCESSFUL`, and the ANTLR tool's own console output contains no line starting with `warning`. If a warning appears, stop and investigate before continuing — a grammar-generation warning was not present before this migration.

- [ ] **Step 9: Update `settings.gradle.kts`**

```kotlin
rootProject.name = "suko"

include("suko-core", "suko-maven-plugin")
```

- [ ] **Step 10: Rewrite the root `build.gradle.kts` as a pure aggregator**

```kotlin
subprojects {
    group = "io.suko"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

- [ ] **Step 11: Repoint `suko-maven-plugin/build.gradle.kts` at `suko-core`**

Modify `suko-maven-plugin/build.gradle.kts`:

Remove the now-redundant lines (already set for every module by the root's `subprojects {}` block from Step 10):
```kotlin
group = "io.suko"
version = "0.1.0-SNAPSHOT"
```
and
```kotlin
repositories {
    mavenCentral()
    gradlePluginPortal()
}
```

Replace:
```kotlin
    // Suko core (root project — grammar, AST, JteCompiler, SukoProjectCompiler)
    implementation(project(":"))
```
with:
```kotlin
    // Suko core (grammar, AST, JteCompiler, SukoProjectCompiler)
    implementation(project(":suko-core"))
```

Update the comment at the bottom of the file (currently lines 42-45) that explains why `sourceCompatibility`/`targetCompatibility` is left unset:

Replace:
```kotlin
// Java version: deixado ao default (mesmo JDK que o projeto raiz, do qual este
// módulo depende via `project(":")`) — um `sourceCompatibility`/`targetCompatibility`
// explícito a 17 aqui entra em conflito de resolução de variante com o projeto raiz,
// que não fixa versão e por isso assume o JDK atual (ver descoberta acima).
```
with:
```kotlin
// Java version: deixado ao default (mesmo JDK que suko-core, do qual este
// módulo depende via `project(":suko-core")`) — um `sourceCompatibility`/
// `targetCompatibility` explícito a 17 aqui entra em conflito de resolução de
// variante com suko-core, que não fixa versão e por isso assume o JDK atual
// (ver descoberta acima).
```

- [ ] **Step 12: Delete the stale backup file**

```bash
/usr/bin/git rm build.gradle.kts.backup
```

- [ ] **Step 13: Run the suite and confirm the expected (reduced) count**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, **112** tests, 0 failures, 0 errors — covering `suko-core` and `suko-maven-plugin` only. This is **117 minus 5**, not a regression: the 5 tests are `SukoGradlePluginTest` (3 `@Test` methods) and `SukoWatchTaskE2ETest` (2 `@Test` methods), which still exist as loose `.java` files at `src/main/java/io/suko/lang/gradle/` and `src/test/java/io/suko/lang/gradle/` — orphaned from any Gradle module now that the root no longer applies the `java` plugin, so Gradle doesn't compile or run them at all (no error, they're just invisible to the build until Task 2 gives them a module). Confirm the count is exactly 112, not less — anything under 112 means something in `suko-core` or `suko-maven-plugin` broke during the move, most likely one of the `examples/` fixture paths Step 6 was supposed to fix.

- [ ] **Step 14: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "refactor(monorepo): extrai suko-core, raiz vira agregador, suko-maven-plugin aponta para suko-core"
```

---

### Task 2: Extract `suko-gradle-plugin`

**Files:**
- Move (via `git mv`): `src/main/java/io/suko/lang/gradle/` and `src/test/java/io/suko/lang/gradle/` into the equivalent paths under `suko-gradle-plugin/`.
- Create: `suko-gradle-plugin/build.gradle.kts`
- Modify: `settings.gradle.kts` (repo root)

**Interfaces:**
- Consumes: `project(":suko-core")` (Task 1) as a compile/test dependency — `io.suko.lang.gradle.SukoCompileTask`/`SukoWatchTask` call `io.suko.lang.JteCompiler` and `io.suko.lang.project.SukoProjectCompiler` from `suko-core`.
- Produces: the Gradle module `:suko-gradle-plugin`, package `io.suko.lang.gradle` unchanged (`SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`, `SukoBaseTask`, `SukoExtension`). No later task in this plan depends on this module directly.

- [ ] **Step 1: Move the Gradle-plugin sources**

```bash
mkdir -p suko-gradle-plugin/src/main/java/io/suko/lang
/usr/bin/git mv src/main/java/io/suko/lang/gradle suko-gradle-plugin/src/main/java/io/suko/lang/gradle
mkdir -p suko-gradle-plugin/src/test/java/io/suko/lang
/usr/bin/git mv src/test/java/io/suko/lang/gradle suko-gradle-plugin/src/test/java/io/suko/lang/gradle
```

Expected: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/` has all 5 files (`SukoBaseTask.java`, `SukoCompileTask.java`, `SukoExtension.java`, `SukoGradlePlugin.java`, `SukoWatchTask.java`); `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/` has both test files (`SukoGradlePluginTest.java`, `SukoWatchTaskE2ETest.java`).

- [ ] **Step 2: Remove the now-empty directory husks left at the repo root**

```bash
find src -type d -empty -delete
```

Expected: the `src/` directory at the repo root no longer exists at all (everything under it has been moved out by Task 1 and this step). This is a filesystem tidy-up only — git never tracked the empty directories, so there is nothing to `git rm`.

- [ ] **Step 3: Create `suko-gradle-plugin/build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":suko-core"))

    compileOnly(gradleApi())

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: Update `settings.gradle.kts`**

```kotlin
rootProject.name = "suko"

include("suko-core", "suko-gradle-plugin", "suko-maven-plugin")
```

- [ ] **Step 5: Run the whole-repo suite and confirm the count is back to 117**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, **117** tests, 0 failures, 0 errors, across all three modules (`suko-core`, `suko-gradle-plugin`, `suko-maven-plugin`) — full parity restored with the pre-migration baseline recorded in Task 1 Step 1.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "refactor(monorepo): extrai suko-gradle-plugin de io.suko.lang.gradle"
```

---

### Task 3: Scaffold `suko-components`

**Files:**
- Create: `suko-components/build.gradle.kts`
- Create: `suko-components/src/main/suko/README.md`
- Create: `suko-components/registry.json`
- Create: `suko-components/README.md`
- Modify: `settings.gradle.kts` (repo root)

**Interfaces:**
- Consumes: `project(":suko-core")` (Task 1) as a dependency — not yet used by any code (no `.sk` sources exist yet), but declared now so subprojeto 7 doesn't need a build-file change to start using it.
- Produces: the Gradle module `:suko-components`, empty of source, with no `.sk`-compilation wiring — explicitly deferred per the spec. Task 4 depends on `project(":suko-components")` as a dependency coordinate (also unused for now).

- [ ] **Step 1: Create the module's `build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":suko-core"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Create the empty `.sk` source directory with a placeholder**

Git doesn't track empty directories, so `src/main/suko/` needs a file inside it to exist once committed.

Create `suko-components/src/main/suko/README.md`:

```markdown
# Component sources

This directory will hold one `.sk` file per component, distributed
shadcn/ui-style (the future `suko add` CLI copies a component's source
file into the consumer's own project rather than pulling a compiled
dependency).

Empty as of the monorepo migration
(`docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md`) — real
content is subprojeto 7 of the roadmap
(`ARCHITECTURE.md` → "Roadmap por subprojeto").
```

- [ ] **Step 3: Create the registry manifest skeleton**

Create `suko-components/registry.json`:

```json
{
  "components": []
}
```

- [ ] **Step 4: Create the module README**

Create `suko-components/README.md`:

```markdown
# suko-components

Registry and component library for Suko, distributed shadcn/ui-style
(source files copied into the consumer's project via a future `suko add`
CLI, not a compiled dependency).

Scaffolding only as of the monorepo migration — no components yet. See
`registry.json` for the (currently empty) manifest format, and
`src/main/suko/` for where component sources will live. Real content is
subprojeto 7 of the roadmap (`ARCHITECTURE.md` → "Roadmap por subprojeto").
```

- [ ] **Step 5: Update `settings.gradle.kts`**

```kotlin
rootProject.name = "suko"

include("suko-core", "suko-gradle-plugin", "suko-maven-plugin", "suko-components")
```

- [ ] **Step 6: Run the module's build and the whole-repo suite**

Run: `gradle :suko-components:build`
Expected: `BUILD SUCCESSFUL` (no test classes exist yet, so `:suko-components:test` reports `NO-SOURCE` or completes with 0 tests — either is correct, not a failure).

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, still 117 tests total (this module contributes 0).

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(monorepo): scaffold do módulo suko-components (subprojeto 7, vazio)"
```

---

### Task 4: Scaffold `suko-website`

**Files:**
- Create: `suko-website/build.gradle.kts`
- Create: `suko-website/src/main/suko/README.md`
- Create: `suko-website/README.md`
- Modify: `settings.gradle.kts` (repo root)

**Interfaces:**
- Consumes: `project(":suko-core")` (Task 1) and `project(":suko-components")` (Task 3) as dependencies — not yet used by any code, declared now for the same reason as Task 3.
- Produces: the Gradle module `:suko-website`, empty of source, with no `.sk`-compilation wiring. No later task in this plan depends on this module.

- [ ] **Step 1: Create the module's `build.gradle.kts`**

```kotlin
plugins {
    id("java")
}

dependencies {
    implementation(project(":suko-core"))
    implementation(project(":suko-components"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 2: Create the empty `.sk` source directory with a placeholder**

Create `suko-website/src/main/suko/README.md`:

```markdown
# Website pages

This directory will hold the `.sk` sources for the Suko documentation
site itself, built with Suko (dogfooding) and consuming
`suko-components`.

Empty as of the monorepo migration
(`docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md`) — real
content is subprojeto 9 of the roadmap
(`ARCHITECTURE.md` → "Roadmap por subprojeto"), which depends on
subprojeto 7 (`suko-components`) having real components to consume.
```

- [ ] **Step 3: Create the module README**

Create `suko-website/README.md`:

```markdown
# suko-website

Suko's own documentation site — built in `.sk`/Suko itself (dogfooding),
consuming `suko-components`.

Scaffolding only as of the monorepo migration — no pages yet. See
`src/main/suko/` for where page sources will live. Real content is
subprojeto 9 of the roadmap (`ARCHITECTURE.md` → "Roadmap por
subprojeto"), which depends on subprojeto 7 (`suko-components`).
```

- [ ] **Step 4: Update `settings.gradle.kts`**

```kotlin
rootProject.name = "suko"

include("suko-core", "suko-gradle-plugin", "suko-maven-plugin", "suko-components", "suko-website")
```

- [ ] **Step 5: Run the module's build and the whole-repo suite**

Run: `gradle :suko-website:build`
Expected: `BUILD SUCCESSFUL`.

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, still 117 tests total.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(monorepo): scaffold do módulo suko-website (subprojeto 9, vazio)"
```

---

### Task 5: Document the new structure in `ARCHITECTURE.md` and run the final full verification

**Files:**
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Consumes: the final 5-module layout from Tasks 1-4 (nothing to add or change in code — this task is documentation plus a final full-suite check).
- Produces: nothing consumed by a later task (this is the last task in the plan).

- [ ] **Step 1: Add a module-structure section**

Insert a new section immediately after the pipeline diagram, before the existing `## Decisões de design que moldam o pipeline` heading (currently line 39) in `ARCHITECTURE.md`:

```markdown
## Estrutura de módulos

Monorepo Gradle multi-módulo (migração documentada em
`docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md`). A raiz é
um agregador puro — `settings.gradle.kts` + um `build.gradle.kts` que só
define `group`/`version`/`repositories` partilhados via `subprojects {}`,
sem source próprio.

- **`suko-core/`** — o compilador: gramática ANTLR, AST, `SukoAstBuilder`,
  `SemanticChecker`, `JteEmitter`, `JteCompiler`,
  `io.suko.lang.project.*` (`ProjectIndex`, `SukoProjectCompiler`). Sem
  dependência de nenhum outro módulo.
- **`suko-gradle-plugin/`** — `io.suko.lang.gradle.*`
  (`SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`) extraído do
  antigo módulo raiz sem mudança de comportamento. Depende de
  `suko-core`. **Lacuna conhecida:** tal como o `suko-maven-plugin` (ver
  item 4 do roadmap abaixo), este módulo nunca foi aplicado como plugin
  Gradle com ID descobrível (`gradlePlugin{}`/
  `META-INF/gradle-plugins/*.properties`) — existe só como classe
  `Plugin<Project>`, testada diretamente, não via `plugins { id(...) }`.
  Preservado tal como estava antes desta migração; não corrigido aqui.
- **`suko-maven-plugin/`** — plugin Maven (`SukoCompileMojo`), depende de
  `suko-core`. Ver a ressalva já documentada no item 4 do roadmap sobre o
  descritor de plugin Maven não estar completo.
- **`suko-components/`** — scaffold vazio para a biblioteca de
  componentes (subprojeto 7), depende de `suko-core`.
- **`suko-website/`** — scaffold vazio para o site de documentação
  (subprojeto 9), depende de `suko-core` e `suko-components`.

`examples/` (ficheiros `.sk` de referência) permanece na raiz do
repositório, fora de qualquer módulo — não é uma unidade de build.
```

- [ ] **Step 2: Update roadmap item 4's Maven ressalva to also mention the Gradle plugin gap**

Find this paragraph (search for `**Ressalva (Tarefa 7 do subprojeto 5):**` in the `## Roadmap por subprojeto` section):

```markdown
   **Ressalva (Tarefa 7 do subprojeto 5):** o módulo `suko-maven-plugin`
   compila e tem testes unitários, mas **ainda não produz um descritor de
   plugin utilizável** (`META-INF/maven/plugin.xml`) — a geração,
   não-funcional, foi removida; ver o comentário em
   `suko-maven-plugin/build.gradle.kts`. Ou seja, `mvn suko:compile` ainda
   não é executável end-to-end. Ver também a lacuna do `sukoWatch` nas
   limitações do subprojeto 5, acima.
```

Replace with:

```markdown
   **Ressalva (Tarefa 7 do subprojeto 5):** o módulo `suko-maven-plugin`
   compila e tem testes unitários, mas **ainda não produz um descritor de
   plugin utilizável** (`META-INF/maven/plugin.xml`) — a geração,
   não-funcional, foi removida; ver o comentário em
   `suko-maven-plugin/build.gradle.kts`. Ou seja, `mvn suko:compile` ainda
   não é executável end-to-end. O mesmo vale para `suko-gradle-plugin`
   (ver "Estrutura de módulos" acima): existe como classe
   `Plugin<Project>` testada diretamente, nunca foi aplicado como plugin
   com ID descobrível em lado nenhum. Ver também a lacuna do `sukoWatch`
   nas limitações do subprojeto 5, acima.
```

- [ ] **Step 3: Run the complete verification**

Run: `gradle test --console=plain`
Expected: `BUILD SUCCESSFUL`, **117** tests, 0 failures, 0 errors, across `suko-core`, `suko-gradle-plugin`, `suko-maven-plugin` (`suko-components`/`suko-website` contribute 0, as before).

Run: `gradle projects`
Expected: lists all 5 subprojects (`suko-core`, `suko-gradle-plugin`, `suko-maven-plugin`, `suko-components`, `suko-website`) under the root project `suko`.

- [ ] **Step 4: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "docs(monorepo): documenta a nova estrutura de módulos em ARCHITECTURE.md"
```
