# Suko

A compiler for a component-oriented template language that compiles `.sk` files to JTE (Java Template Engine) templates.

## Overview

Suko is a template language designed for building UI components in Java/Kotlin web applications. It provides a clean, component-based syntax that compiles to JTE templates, which can then be rendered at runtime using the standard JTE engine.

```
.sk (Suko source) → JteCompiler → .jte (JTE template) → JTE Runtime → HTML
```

## Features

- **Component-based syntax** - Define reusable UI components with parameters and slots
- **Type-safe** - Full Java type checking via JavacTask (Subprojeto 3)
- **Slot support** - Named slots with cardinality validation (ONE/MANY)
- **Source maps** - Errors mapped back to original `.sk` files
- **Gradle plugin** - `sukoCompile` and `sukoWatch` tasks
- **Maven plugin** - `suko:compile` goal

## Quick Start

### Gradle

```kotlin
// build.gradle.kts
plugins {
    id("io.suko") version "0.1.0"
}

suko {
    sourceDir = file("src/main/suko")
    outputDir = file("build/generated-src/suko")
}
```

```bash
# Compile .sk files to .jte
./gradlew sukoCompile

# Watch mode - recompiles on changes
./gradlew sukoWatch
```

### Maven

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
    Sidebar {
      NavLink(label = "Home", href = "/")
      NavLink(label = "Perfil")
    }
    Content {
      switch (user.role) {
        case "admin" -> { AdminPanel() }
        case "guest" -> { <p>Bem-vindo, visitante</p> }
        default -> { <p>Bem-vindo, {user.name}</p> }
      }
    }
  }
}
```

### Slots

```suko
component Layout(String title, slot<Content> sidebar, slot<Content> content) {
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

## Project Structure

```
suko/
├── build.gradle.kts              # Main build configuration
├── src/
│   ├── main/
│   │   ├── antlr/io/suko/lang/   # ANTLR grammar files
│   │   ├── java/io/suko/lang/    # Core compiler
│   │   │   ├── JteCompiler.java  # Main compilation pipeline
│   │   │   ├── JavacTask.java    # Java type checking
│   │   │   ├── diagnostic/       # Error handling
│   │   │   ├── semantic/         # Semantic analysis
│   │   │   ├── symbol/           # Symbol table
│   │   │   └── gradle/           # Gradle plugin
│   │   └── resources/            # Resources
│   └── test/                     # Unit and integration tests
├── suko-maven-plugin/            # Maven plugin module
├── examples/                     # Example .sk files
└── docs/superpowers/             # Specs and plans
```

## Subprojects

| Subproject | Status | Description |
|------------|--------|-------------|
| 1. Núcleo da linguagem | ✅ Concluído | Gramática ANTLR, AST, SukoAstBuilder, JteEmitter, source maps |
| 2. Verificador Suko | ✅ Concluído | DiagnosticCollector, SymbolTable, SemanticChecker |
| 3. Verificação Java | ✅ Concluído | JteCompiler, JavacTask |
| 4. Integração no build | ✅ Concluído | Gradle plugin, Maven plugin, Watch mode |

## Development

### Building

```bash
# Build all modules
./gradlew build

# Run tests
./gradlew test
```

### Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed pipeline architecture and design decisions.

### Specifications

Each subproject has a specification document in `docs/superpowers/specs/` and an implementation plan in `docs/superpowers/plans/`.

## Requirements

- Java 17+
- Gradle 8.7+ (for the plugin) or Maven 3.9+

## License

Apache License 2.0