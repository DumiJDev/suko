# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Java/Kotlin backend developers building server-rendered web apps on top of
`gg.jte` (JTE — Java Template Engine), typically inside Spring Boot or
Quarkus. They currently author `.jte` templates by hand and are evaluating
Suko as a friendlier, typed, component-oriented authoring layer on top of
the same rendering engine they already use. Secondary audience: the same
developers once they've adopted Suko and are back for reference (language
syntax, the standard component registry, CLI commands) rather than to be
convinced.

## Product Purpose

Suko is a compile-time DSL: `.sk` source files, written with a
component/slot model (typed parameters, `Component` slots, `if`/`for`),
compile to plain `.jte` templates — not to bytecode, not to a runtime
library. The emitted `.jte` is indistinguishable from hand-written JTE, so
it renders through the exact same `gg.jte` engine and Spring/Quarkus
integrations (`jte-spring-boot-starter`, `jte-quarkus`) the ecosystem
already has, with zero adaptation on their side. Success for a visitor is:
understands what problem this solves for a JTE user in under a minute,
finds the install path that matches their build tool, and can find any
component's parameters or the language's syntax without digging into
source.

## Positioning

**1 Suko component → 1 plain JTE template, and nothing else at runtime.**
A competing "nicer templating layer" typically means a new runtime engine
or a rendering abstraction to depend on; Suko's mechanism has no runtime
footprint of its own — it's a build-time compiler whose only artifact is
the `.jte` file a JTE-based app already knows how to consume. This was
verified for real (not just claimed): a disposable Spring Boot app, with
the real Gradle plugin applied, served an HTTP response rendered from a
Suko-compiled template with zero adaptation.

Distribution is copy-source (`suko add <component>`, shadcn/ui-style): the
consumer's project ends up owning the actual `.sk` source, not a library
dependency. This is a consequence of a real architectural constraint, not
a style preference — the current compiler (`ProjectIndex`/
`SukoProjectCompiler`) only understands a single source root, so a true
library-dependency model isn't implementable yet.

## Operating Context

A developer's workflow: install the CLI (jbang, wrapper script, or build
from source) → `suko init` a project → `suko add <component>` to pull
components from the registry into their own source tree → write `.sk`
components → a Gradle or Maven plugin compiles them to `.jte` on build →
their existing Spring Boot/Quarkus app renders them unchanged. The
documentation site is a Suko project itself (dogfooded): its own pages are
`.sk` components compiled to static HTML by a custom generator, not a
separate stack.

## Capabilities and Constraints

- Java 21 is the floor for every module.
- Current version is `0.1.0-SNAPSHOT`; no GitHub Release has been cut yet,
  so the jbang alias and default registry URL don't resolve for a
  stranger until one exists.
- Neither the Gradle plugin nor the Maven plugin is published to the
  Gradle Plugin Portal or Maven Central — both are usable today only via
  local install, a Gradle composite build, or building from source. This
  is a documented, accepted gap, not a bug to paper over on the site.
- Spring Boot integration is verified for real (a real app, real HTTP
  request, real rendered output). Quarkus integration is architecturally
  identical in principle but has not been separately verified — the site
  must not claim it as tested.
- **No VSCode or IntelliJ support exists yet** (no language server, no
  syntax highlighting extension). This is explicitly a pre-1.0 gap the
  site should not imply is solved — e.g., no "install the VSCode
  extension" step anywhere.
- The standard component registry has 8 real components (Button, Input,
  Label, Badge, Alert, Card, Field, Dialog), styled with Tailwind 3.x and,
  for Dialog, Alpine.js — both declared as `externalRequirements` the
  consumer's own app must load, not bundled by Suko.
- The site itself must stay statically generated at build time (no
  client-side framework, no server at request time) — this is a
  demonstration of the project's own "compiles to static HTML with no JVM
  at runtime" capability, not just a hosting convenience.

## Brand Commitments

None. The current teal color and "S"-in-a-square mark were a placeholder
the assistant created; there is no binding name styling, palette,
typography, or symbol to preserve. The name "Suko" itself is fixed.

## Evidence on Hand

- Real, working example source exists and can be shown verbatim:
  `examples/shop` (a runnable e-commerce shop in Suko) and the 8
  registry components under `suko-components/`, all on GitHub
  (`DumiJDev/suko`).
- No real user testimonials, adoption numbers, or case studies exist —
  this is a pre-release, single-maintainer project. Do not fabricate any.
- No comparison benchmarks against other templating approaches have been
  measured; do not invent performance claims.

## Product Principles

1. **Claim only what's verified.** The site's proof points (Spring Boot
   integration, zero-runtime-footprint compilation) are backed by actual
   passing tests, not aspiration — new claims need the same bar before
   they appear on the site.
2. **Docs-for-real-users first.** Getting Started, Language Reference, and
   Components exist to be used while building, not just to look
   impressive on a first visit — code examples must be runnable, not
   illustrative fiction.
3. **Honest about pre-1.0 status.** No Maven Central/Gradle Portal
   publish, no IDE support, no cut release — the site should read as a
   confident but honest pre-1.0 project, not oversell maturity it doesn't
   have yet.
4. **The site is proof of the product, not just marketing about it.** It
   is itself compiled by Suko; that fact is worth surfacing, not hiding.
