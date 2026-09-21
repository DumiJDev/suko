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

## Quick Start

### Try a component (`suko-cli`)

```bash
jbang suko@<owner> init --yes --base-package com.example.app
jbang suko@<owner> add button
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

⚠️ Not yet functional end-to-end: `suko-maven-plugin` compiles and has unit tests, but doesn't yet produce a usable plugin descriptor (`META-INF/maven/plugin.xml`) — see the roadmap ressalva in [ARCHITECTURE.md](ARCHITECTURE.md). The snippet documents the intended usage once that's added.

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
        default -> { <p>Bem-vindo, {user.name}</p> }
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
    <head><title>{title}</title></head>
    <body>
      <aside>{sidebar}</aside>
      <main>{content}</main>
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

The `examples/` directory contains Suko programs that demonstrate real usage patterns. Their `package`/`import` declarations match the multi-file compiler's directory convention (subproject 5), but they are not exercised by any automated test as a full multi-file project — `Forms.sk` in particular calls `Button`/`Input`/`Select` (declared in `LayoutComponents.sk`) with HTML-tag-style syntax and arguments that don't match those components' real signatures, a known pre-existing inconsistency, not something introduced by the directory-convention fix.

- **`Card.sk`** - reference surface of the language: a concrete `Card` component, `NavLink`, a `Page` using the `Layout` component with named slots (`header`, `sidebar`, `body`, `footer`), and a conditional `AdminPanel`.
- **`dashboard/Dashboard.sk`** - a dashboard layout with role-based panels (`AdminPanel`/`ManagerPanel`), sidebar navigation, and a conditional items list.
- **`forms/Forms.sk`** - login and registration forms using imported `Button`, `Input`, and `Select` components, with `Map<String, String>` validation errors.
- **`layout/LayoutComponents.sk`** - reusable layout components (`Layout`, `Card`, `Modal`, `Button`, `Input`, `Select`) with slot parameters.
- **`invalid/Card.sk`** - the rejected generic `Card<T>` case, kept as a fixture documenting the current generics limitation.

```
examples/
├── Card.sk
├── dashboard/
│   └── Dashboard.sk
├── forms/
│   └── Forms.sk
├── invalid/
│   └── Card.sk
└── layout/
    └── LayoutComponents.sk
```

## Project Structure

```
suko/
├── build.gradle.kts              # Root aggregator — no source of its own
├── settings.gradle.kts           # Declares the 8 modules below
├── suko-core/                    # The compiler
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
├── suko-maven-plugin/            # Maven plugin module (no plugin.xml yet — not end-to-end usable)
├── suko-registry/                # Registry data model + JSON I/O (registry.json/manifest), no suko-core dependency
├── suko-registry-generator/      # Generates the manifest from suko-components' .sk sources
├── suko-components/              # Component library: 8 real .sk components + generated registry.json/manifest
├── suko-cli/                     # `suko` CLI: init/list/add/diff/update, copy-source distribution
├── suko-website/                 # Future docs site built in Suko (empty scaffold)
├── examples/                     # Example .sk files
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
| 9. Documentation site | Planned | Built in Suko itself, depends on 7 and 8, populates `suko-website/` |

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