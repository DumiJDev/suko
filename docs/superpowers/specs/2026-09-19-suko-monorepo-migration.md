# Suko — Migração para Monorepo Multi-Módulo

## Contexto e motivação

O repositório Suko tem hoje um único módulo Gradle na raiz (`io.suko` /
`suko`) que mistura o compilador core (gramática ANTLR, AST,
`SemanticChecker`, `JteEmitter`, `ProjectIndex`/`SukoProjectCompiler` — cerca
de 2700 linhas) com o código específico do plugin Gradle
(`io.suko.lang.gradle` — `SukoGradlePlugin`, `SukoCompileTask`,
`SukoWatchTask`, `SukoBaseTask`, `SukoExtension` — cerca de 400 linhas) no
mesmo jar. `suko-maven-plugin/` já existe como segundo módulo Gradle
(ligado ao `settings.gradle.kts` só durante o subprojeto 5 — antes disso
nunca tinha compilado).

O utilizador pediu explicitamente a conversão do repositório para um
monorepo multi-módulo, "para maior organização", adiando o pedido até o
subprojeto 5 (Projeto Multi-Ficheiro) estar mergeado em `main` — o que já
aconteceu (PR #2, commit `bb49fce`, 2026-09-19). O roadmap revisto (ver
`ARCHITECTURE.md`) tem dois subprojetos futuros que introduzem módulos
inteiramente novos: subprojeto 7 (registry e biblioteca de componentes,
distribuição estilo shadcn/ui) e subprojeto 9 (site de documentação
Tailwind, construído com o próprio Suko).

Esta spec cobre **apenas a reorganização estrutural** — separar módulos
existentes e criar o scaffolding (esqueleto) dos módulos novos. Não cobre
conteúdo real de components/website (isso é o âmbito dos subprojetos 7 e 9),
nem publicação em repositórios de artefactos, nem criação de descritores de
plugin (Maven ou Gradle) que ainda não existem.

## Módulos finais

Todos os módulos são irmãos na raiz do repositório, prefixo `suko-`, grupo
`io.suko`, versão `0.1.0-SNAPSHOT` (herdada da raiz).

| Módulo | Conteúdo | Depende de (Gradle) |
|---|---|---|
| `suko-core/` | Gramática ANTLR (`src/main/antlr/io/suko/lang/*.g4`), AST (`io.suko.lang.ast`), `SukoAstBuilder`, `SemanticChecker`, `JteEmitter`, `JteCompiler`, `io.suko.lang.project.*` (`ProjectIndex`, `SukoProjectCompiler`, `ProjectIndexEntry`), `io.suko.lang.diagnostic.*`, `io.suko.lang.symbol.*` — tudo o que está hoje na raiz do repositório **exceto** o pacote `io.suko.lang.gradle` | — (só `antlr4`, `gg.jte`) |
| `suko-gradle-plugin/` | `io.suko.lang.gradle.*` extraído tal como está hoje (mesmo pacote Java, só muda de módulo físico): `SukoGradlePlugin`, `SukoCompileTask`, `SukoWatchTask`, `SukoBaseTask`, `SukoExtension` | `suko-core` |
| `suko-maven-plugin/` | Inalterado internamente — só troca a dependência `implementation(project(":"))` por `implementation(project(":suko-core"))` | `suko-core` |
| `suko-components/` | Scaffold vazio: `src/main/suko/` (pasta vazia), `registry.json` esqueleto (`[]` ou `{"components": []}` — decide-se a forma exata no subprojeto 7), `README.md` a apontar para o subprojeto 7 | `suko-core` |
| `suko-website/` | Scaffold vazio: `src/main/suko/` (pasta vazia), `README.md` a apontar para o subprojeto 9 | `suko-core`, `suko-components` |

`examples/` (ficheiros `.sk` soltos de referência, hoje não compilados por
nenhum teste automatizado) **permanece na raiz do repositório**, fora de
qualquer módulo — é material de referência para humanos, não uma unidade de
build.

## Raiz do repositório

- `settings.gradle.kts`:
  ```kotlin
  rootProject.name = "suko"

  include("suko-core", "suko-gradle-plugin", "suko-maven-plugin", "suko-components", "suko-website")
  ```
- `build.gradle.kts` da raiz fica **sem código/source set próprio** — só
  configuração partilhada via `subprojects { ... }` (grupo, versão,
  repositórios comuns `mavenCentral()`/`gradlePluginPortal()`). Toda a
  lógica de geração de gramática ANTLR (`generateSukoLexer`,
  `generateSukoParser`, desativação de `generateGrammarSource`, o
  `sourceSets { main { java { srcDir(...) } } }`) muda por inteiro para
  `suko-core/build.gradle.kts`.
- `build.gradle.kts.backup` (ficheiro cruft rastreado no git, versão
  pré-subprojeto-4 do build script da raiz, já divergente do ficheiro
  atual) é apagado como parte desta limpeza.

## Mecânica da migração

- Mover ficheiros com `git mv` (não apagar+recriar) para preservar
  histórico/blame — aplica-se a todo o código movido para `suko-core/` e
  `suko-gradle-plugin/`.
- `jte-classes/` (output de build gerado na raiz) não precisa de migração —
  é regenerado; pode ficar ou ser limpo, sem impacto funcional.
- Pacotes Java (`io.suko.lang`, `io.suko.lang.ast`, `io.suko.lang.gradle`,
  etc.) **não mudam de nome** — só de pasta física/módulo. Zero mudança de
  import em código consumidor dentro do próprio monorepo (`suko-maven-plugin`
  continua a importar `io.suko.lang.JteCompiler` normalmente, só a
  coordenada Gradle da dependência muda).
- `suko-maven-plugin/build.gradle.kts`: troca `implementation(project(":"))`
  → `implementation(project(":suko-core"))`; o comentário existente sobre
  `sourceCompatibility`/`targetCompatibility` (linhas 42-45) é atualizado
  para referir `suko-core` em vez de "projeto raiz".
- Critério de sucesso: `gradle test` verde em todos os módulos após a
  migração, com os mesmos 117 testes a passar — esta é uma reorganização
  física, nenhuma lógica de negócio muda.

## Fora de âmbito (decisões explícitas desta spec)

- **Publicação** de qualquer módulo no Maven Central ou Gradle Plugin
  Portal.
- **Descritor de plugin Gradle** (`gradlePlugin{}`/marcador
  `META-INF/gradle-plugins/*.properties`) para `suko-gradle-plugin` —
  preserva-se o comportamento atual (plugin existe como classe
  `Plugin<Project>`, nunca foi aplicado como plugin ID descobrível em lado
  nenhum, nem antes nem depois desta migração). Fica documentado como
  limitação conhecida em `ARCHITECTURE.md`, no mesmo estilo já usado para
  `suko-maven-plugin` (subprojeto 5, achado E).
- **Wiring de compilação `.sk`→`.jte`** para `suko-components` e
  `suko-website` — os módulos ficam com fontes `.sk` vazias e sem nenhuma
  tarefa a compilá-las; essa ligação (via `suko-core` diretamente ou via
  `suko-gradle-plugin`) fica para quando os subprojetos 7 e 9
  trouxerem conteúdo real.
- **Conteúdo real** de `suko-components` (biblioteca de componentes) e
  `suko-website` (site de documentação) — subprojetos 7 e 9 do roadmap,
  não desta migração.
- **Migração de `examples/`** para as convenções de pacote do subprojeto 5
  — gap já documentado em `ARCHITECTURE.md` desde o subprojeto 5, não
  reaberto aqui.
