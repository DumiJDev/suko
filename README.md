# Suko

A compiler for a component-oriented template language that compiles `.sk` files to JTE (Java Template Engine) templates.

## Overview

Suko is a template language designed for building UI components in Java/Kotlin web applications. It provides a clean, component-based syntax that compiles to JTE templates, which can then be rendered at runtime using the standard JTE engine.

```
.sk (Suko source) → JteCompiler → .jte (JTE template) → JTE Runtime → HTML
```

## Features

- **Component-based syntax** - Define reusable UI components with parameters and slots
- **Type-safe** - Full Java type checking via JavacTask (Subproject 3)
- **Content parameters** - `Component`/`List<Component>`/`Function<T, Component>` typed parameters, filled via named call-site blocks, plus implicit `children` for unlabeled content
- **Source maps** - Errors mapped back to original `.sk` files
- **Gradle plugin** - `sukoCompile` and `sukoWatch` tasks
- **Maven plugin** - `suko:compile` goal
- **Secure by default** - URL allowlist, `UNSAFE_SINK` errors, signed registry index (see [Security](#security))

## Quick Start

### Try a component (`suko-cli`)

```bash
jbang suko@DumiJDev/suko init --yes --base-package com.example.app
jbang suko@DumiJDev/suko add button
```

See `suko-cli/README.md` for installation options (jbang, fat jar, wrapper
scripts, and an opt-in consumer-compiled native binary) and the full
command reference.

### Gradle

⚠️ Not published to the Gradle Plugin Portal: `suko-gradle-plugin` declares a
real, discoverable plugin ID (`gradlePlugin{}`, `id = "io.suko.lang"`), which
already resolves via a composite build (`includeBuild`) or Gradle TestKit —
but there is no Portal publication behind it, so the exact snippet below
(which assumes a Portal-resolved `version "0.1.0"`) won't work as a
standalone `plugins {}` block today. See "Estrutura de módulos" in
[ARCHITECTURE.md](ARCHITECTURE.md) for the current gap. The snippet
documents the intended usage once a real publication exists.

```kotlin
// build.gradle.kts
plugins {
    id("io.suko.lang") version "0.1.0"
}

suko {
    sourceDir = file("src/main/suko")
    outputDir = file("build/generated-src/suko")
}
```

```bash
# Compile .sk files to .jte
gradle sukoCompile

# Watch mode - recompiles on changes
gradle sukoWatch
```

### Maven

`suko-maven-plugin` ships a hand-written plugin descriptor (`META-INF/maven/plugin.xml`, goal `compile`, 6 parameters) and was verified once with a real `mvn` run (before 13a added `targets`/`buildDirectory`), but it is not published to a Maven repository and no automated test runs a real `mvn` (only Mojo- and descriptor-level tests) — see ARCHITECTURE.md, roadmap item 4.

```xml
<plugin>
    <groupId>io.suko</groupId>
    <artifactId>suko-maven-plugin</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <executions>
        <execution>
            <goals>
                <goal>compile</goal>
            </goals>
        </execution>
    </executions>
    <configuration>
        <sourceDir>${project.basedir}/src/main/suko</sourceDir>
        <outputDir>${project.build.directory}/generated-sources/suko</outputDir>
    </configuration>
</plugin>
```

```bash
# Compile .sk files to .jte
mvn suko:compile
```

## Language Syntax

### Components

```suko
package com.example.ui;

component Card(String title, List<String> items, String emptyLabel = "Sem itens") {
  <div class="card">
    <h2>${title}</h2>
    if (items.size() > 0) {
      <ul>
        for (String item : items) {
          <li>${item}</li>
        }
      </ul>
    } else {
      <p>${emptyLabel}</p>
    }
  </div>
}
```

A page-level component may open with `<!DOCTYPE html>`. It is accepted only as the first
item of a component body (case-insensitive); any other `<!...>` declaration, or a doctype
anywhere else, is rejected with `INVALID_DOCTYPE`. Lexing caveat: `a<!b` starts a
declaration, so write `a < !b`.

### Using Components

```suko
component Page(User user) {
  Layout(title = "Dashboard") {
    sidebar {
      NavLink(label = "Home", href = "/")
      NavLink(label = "Perfil")
    }
    content {
      switch (user.role) {
        case "admin" -> { AdminPanel() }
        case "guest" -> { <p>Bem-vindo, visitante</p> }
        default -> { <p>Bem-vindo, ${user.name}</p> }
      }
    }
  }
}
```

### Slots (Content Parameters)

`slot<T>` was removed in favor of `Component`, `List<Component>`, and `Function<T, Component>` — cardinality is expressed with ordinary Java generics, and a component's parameter type tells the caller how to fill it. Call-site slot-fill blocks (`sidebar { ... }`) are unchanged:

```suko
component Layout(String title, Component sidebar, Component content) {
  <html>
    <head><title>${title}</title></head>
    <body>
      <aside>${sidebar}</aside>
      <main>${content}</main>
    </body>
  </html>
}

component Page() {
  Layout(title = "My Page") {
    sidebar { <nav>...</nav> }
    content { <p>Main content</p> }
  }
}
```

### Examples

The `examples/shop` directory is a runnable e-commerce shop (Spring Boot + H2) written in Suko: catalogue, product page, search, reviews, cart and checkout, with Spring Security (CSRF, session handling) and an XSS corpus. It is a separate Gradle build that uses the `io.suko.lang` plugin through `includeBuild("../..")`, so it is not part of the root build. Run its tests with `./gradlew -p examples/shop test`. Its `.sk` sources (`examples/shop/src/main/suko`) are also a root of the compiler's golden test (`GoldenParityTest`).

The earlier loose reference files (`Card.sk`, `dashboard/`, `forms/`, `layout/`, `invalid/Card.sk`) now live under `suko-core/src/test/resources/fixtures/` as parser and compiler test fixtures, not as examples.

### Extensions (targets, vocabularies, checkers)

Compile-time extensions (subprojeto 13a) add compilation targets, tag
vocabularies and extra checkers. They run only in the build and in the LSP
and are discovered via `ServiceLoader`; the contract is in
[`suko-api/README.md`](suko-api/README.md). The built-in `jte` target is
implemented through the same API. Without extensions nothing changes: the
default is `targets = ["jte"]` and the output is byte-for-byte what it was.
There is no release yet and no third-party extension is published; the
example below uses a placeholder artifact.

```kotlin
// build.gradle.kts of a project
dependencies { "sukoExtensions"("io.exemplo:suko-minha-extensao:1.0.0") }
suko { targets.set(listOf("jte", "demo")) }
```

```xml
<!-- pom.xml: extensions go in the plugin's own <dependencies> -->
<plugin>
  <groupId>io.suko</groupId>
  <artifactId>suko-maven-plugin</artifactId>
  <configuration><targets><target>jte</target><target>demo</target></targets></configuration>
  <dependencies>
    <dependency><groupId>io.exemplo</groupId><artifactId>suko-minha-extensao</artifactId><version>1.0.0</version></dependency>
  </dependencies>
</plugin>
```

- With more than one target, output goes to `<outputDir>/<target>/<package dirs>/`;
  with a single target the layout is unchanged. Maven also accepts `-Dsuko.targets=...`.
- An unknown target fails with `TARGET_NOT_FOUND` (the message lists the
  available ones). Other new codes: `EXTENSION_CONFLICT`,
  `EXTENSION_API_MISMATCH`, `VOCABULARY_NOT_FOUND`, `UNKNOWN_TAG`,
  `EXTENSION_FAILED` (an extension that throws is reported with its id and
  the file; the build continues) — see ARCHITECTURE.md, item 13.
- Each build writes `build/suko/extensions.json` (Gradle) or
  `target/suko/extensions.json` (Maven) — the extension jars (absolute paths)
  and the targets — so the language server can load the same extensions.
  It is not written when there are no sources, nor by `sukoWatch`, and the
  write is not atomic. With a customised Gradle `buildDirectory` the LSP does
  not find it (it only looks in `build/suko` and `target/suko`); with no
  `extensions.json` the LSP stays silent.
- Going from one target to several moves templates from `<out>/pkg/X.jte` to
  `<out>/jte/pkg/X.jte` (repoint the JTE root, e.g. Spring's `src/main/jte`);
  neither `sukoCompile` nor `suko:compile` deletes orphaned outputs.
- Gradle writes the whole `sukoExtensions` configuration (transitive deps
  included) to `extensions.json`; Maven only the jars that carry a provider
  file, so an extension with runtime dependencies works under `mvn` but fails
  in the LSP of Maven projects (`EXTENSION_FAILED`). Follow-up.
- **Trust rule (LSP / VSCode):** extensions are third-party code. The language
  server loads them only when VSCode reports the workspace as trusted. In an
  untrusted workspace only the built-in `jte` runs and `extensions.json` is
  never read. The manifest is ignored above 1 MiB; classpath entries must be
  absolute, existing `.jar` files (UNC paths are rejected); `extensions.json`
  is watched and reloaded when it changes; granting trust restarts the server;
  `sourceRoot` from `suko.json` must stay inside the workspace folder. There
  is no IntelliJ support yet (11b).
- A successful `sukoCompile`/`suko:compile` now prints warnings and info
  diagnostics (e.g. a checker's `WARNING`), as the LSP does.

## Security

Subprojeto 14 makes the compiler secure by default for components that use the
`html` vocabulary (the `jte` target). Threat model, configuration and the registry
trust model are in [`docs/security.md`](docs/security.md); the spec is in
`docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md`. There is no
release yet and no pentest has been done (the shop below is the planned target).

- **URLs:** dynamic values in URL attributes (`href`, `src`, `action`, `srcset`, ...) go
  through a generated `SukoSafe` class (JDK only, written into your project). Only
  `urlSchemes` (default `http`, `https`, `mailto`, `tel`) and relative URLs pass;
  anything else becomes `about:invalid#suko-blocked`.
- **Sinks:** a dynamic value in `<script>`/`<style>` content, `on*`, `srcdoc`, `style`,
  `<base>`, dynamic embeds, `x-*`/`hx-on*`, ... is a compile error, `UNSAFE_SINK`.
- **Automatic:** `target` adds `rel="noopener"`; `UPPERCASE_NAME` catches names the JTE
  `OwaspHtmlPolicy` refuses; with `strictCsp`, `CSP_INLINE` warns about inline code.
- **Explicit escapes:** `trustedUrl(...)` (URL attributes only) and `trustedStyle(...)`
  (`style` only) report `TRUSTED_URL`/`TRUSTED_STYLE` (INFO) and are listed in
  `build/suko/security-audit.json` (Maven: `target/suko/`). Misusing them, or declaring
  that name, is `RESERVED_NAME`. `trustedHtml` does not exist.
- **JTE policy:** with the `gg.jte.gradle` plugin applied, the Suko Gradle plugin sets
  `htmlPolicyClass` to `gg.jte.html.OwaspHtmlPolicy` unless `jtePolicy = false` or you set
  your own. Maven only warns.
- **Registry:** `suko-cli` verifies an Ed25519 signature of `registry.json`, per-manifest
  hashes, expiry and rollback (`REGISTRY_UNSIGNED`, `REGISTRY_BAD_SIGNATURE`,
  `REGISTRY_MISMATCH`, `REGISTRY_EXPIRED`, `REGISTRY_ROLLBACK`, `REGISTRY_MANIFEST_HASH`,
  `REGISTRY_INVALID`). The embedded key list is empty until the first release, so the
  default `suko add` against the official HTTPS registry fails with `REGISTRY_UNSIGNED`
  for now; use `--registry <local path> --allow-unsigned` or `registry.publicKeys` in
  `suko.json`. `--allow-unsigned` only works for local registries.

```kotlin
suko {
  security {
    urlSchemes.set(listOf("http", "https", "mailto", "tel"))  // default
    imageDataTypes.set(listOf("png", "webp"))  // subset of png,gif,jpeg,webp,avif
    strictCsp.set(true)
    codeAttributes.add("x-custom")
    urlAttributes.add("data-url")
    jtePolicy.set(true)                        // default
  }
}
```

```xml
<configuration>
  <security>
    <urlSchemes><urlScheme>https</urlScheme></urlSchemes>
    <strictCsp>true</strictCsp>
  </security>
</configuration>
```

`generatedPackage` and `generatedJavaDir` place `SukoSafe` (Gradle defaults:
`io.suko.generated.<project>`, `build/generated-src/suko-java`; Maven:
`target/generated-sources/suko-java`, package derived from the `artifactId`).
Forbidden schemes in `urlSchemes` (`javascript`, `vbscript`, `data`, `blob`,
`filesystem`) fail the build. CSRF, authentication, response headers and sanitising
third-party HTML remain the application's job; `examples/shop` shows one way.

Known gaps (see ARCHITECTURE.md, item 14): the LSP ignores the build's
`suko.security`; `:`/`@` are not allowed in attribute
names (`x-on:click`, `@click`); components only take library parameter types.

## Project Structure

```
suko/
├── build.gradle.kts              # Root aggregator — no source of its own
├── settings.gradle.kts           # Declares the modules below
├── suko-api/                     # Extension contract (io.suko.ext.*), JDK only
├── suko-jte/                     # Built-in JTE target, registered through the extension API
├── suko-test-ext/                # Example extension (DemoExtension), tests only
├── suko-core/                    # The compiler (no JTE; ExtensionRegistry)
│   ├── src/main/antlr/io/suko/lang/   # ANTLR grammar files
│   ├── src/main/java/io/suko/lang/    # Core compiler
│   │   ├── JteCompiler.java      # Main compilation pipeline
│   │   ├── JavacTask.java        # Java type checking
│   │   ├── ast/                  # AST node types
│   │   ├── diagnostic/           # Error handling
│   │   ├── project/              # Multi-file resolution (ProjectIndex, SukoProjectCompiler)
│   │   ├── semantic/             # Semantic analysis
│   │   └── symbol/               # Symbol table
│   └── src/test/                 # Unit and integration tests
├── suko-gradle-plugin/           # Gradle plugin (io.suko.lang.gradle.*), discoverable ID "io.suko.lang" (not Portal-published)
├── suko-maven-plugin/            # Maven plugin module (hand-written plugin.xml; not published; no automated real-`mvn` test)
├── suko-registry/                # Registry data model + JSON I/O (registry.json/manifest), no suko-core dependency
├── suko-registry-generator/      # Generates the manifest from suko-components' .sk sources
├── suko-components/              # Component library: 8 real .sk components + generated registry.json/manifest
├── suko-cli/                     # `suko` CLI: init/list/add/diff/update, copy-source distribution
├── suko-website/                 # Future docs site built in Suko (empty scaffold)
├── examples/shop/                # Runnable example shop (Spring Boot + H2), separate Gradle build
└── docs/superpowers/             # Specs and plans
```

## Subprojects

| Subproject | Status | Description |
|------------|--------|-------------|
| 1. Core language | ✅ Done | ANTLR grammar, AST, SukoAstBuilder, JteEmitter, source maps |
| 2. Suko checker | ✅ Done | DiagnosticCollector, SymbolTable, SemanticChecker |
| 3. Java verification | ✅ Done | JteCompiler, JavacTask |
| 4. Build integration | ✅ Done | Gradle plugin, Maven plugin, watch mode |
| 5. Multi-file project | ✅ Done | Cross-file `package`/`import` resolution via `ProjectIndex`, `public`/file-private visibility, output mirrors packages |
| 6. Component model | ✅ Done | `Component`/`List<Component>`/`Function<T, Component>` replace `slot<T>`, implicit `children`, component-as-value, real string interpolation |
| 7. Component registry/library | ✅ Done | shadcn/ui-style distribution — depends on 5 and 6, populates `suko-components/` |
| 8. Distribution CLI (`suko add`) | ✅ Done | `suko-cli` module: `suko init`/`list`/`add`/`diff`/`update`, depends on 7 |
| 9. Interpolation unification | ✅ Done | `${expr}` is the only interpolation syntax, in all three positions; bare braces never interpolate |
| 10. Documentation site | Planned | Built in Suko itself, depends on 7 and 8, populates `suko-website/` |
| 14. Secure by default | ✅ Done | URL allowlist (`SukoSafe`), `UNSAFE_SINK`, CSP lint, signed registry index, XSS corpus, `examples/shop`; see [`docs/security.md`](docs/security.md) |

The repository itself was also restructured into a multi-module monorepo, starting with the 5 modules described in `docs/superpowers/specs/2026-09-19-suko-monorepo-migration.md` (`suko-core`/`suko-gradle-plugin`/`suko-maven-plugin`/`suko-components`/`suko-website`) and now at 8 modules total, shown in the tree above (`suko-registry`, `suko-registry-generator`, and `suko-cli` added by subprojetos 7 and 8).

## Development

### Building

```bash
# No gradlew wrapper is committed in this repo — use the system Gradle install.

# Build all modules
gradle build

# Run tests
gradle test
```

### Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed pipeline architecture and design decisions.

### Specifications

Each subproject has a specification document in `docs/superpowers/specs/` and an implementation plan in `docs/superpowers/plans/`.

## Requirements

- Java 21+ (records, sealed interfaces, pattern-matching `switch`)
- Gradle 9.x (no wrapper is committed — use a system install) or Maven 3.9+

## License

Apache License 2.0