# suko-components

Component library for Suko: 8 real `.sk` components (`Button`, `Input`,
`Label`, `Badge`, `Alert`, `Card`, `Field`, `Dialog`), plus a JSON
manifest (`registry.json` + `components/*.json`) generated from those
sources. This module is **pure content** — there is no
`src/main/java` and it declares no dependency in `main`; the
dependencies you'll see in `build.gradle.kts` exist only so the module
can validate itself in `src/test` (compile and render the whole
library, and fail if the committed manifest has drifted from the
sources). The code that actually reads/writes/generates the manifest
lives in the sibling module `suko-registry`, not here.

## Distribution model: copy-source, not a dependency

Suko ships this library the same way shadcn/ui ships its components:
by copying source into your project, not by pulling in a compiled jar.
When the CLI in subprojeto 8 exists, `suko add field` will copy
`Field.sk` (and its transitive dependencies, `Label.sk`/`Input.sk`)
into your own `src/main/suko` — rewriting the package prefix to match
your project — and from that point on the file is **yours**: you edit
it, version it, and diverge from upstream exactly like you would with
any other file you wrote yourself. There is no runtime coupling to
this module and no version to keep in sync afterwards.

This is a deliberate architecture constraint, not a style preference:
the Suko compiler's project model (`ProjectIndex`/`SukoProjectCompiler`)
only understands a single source root today, so "your app's source +
a library's source, resolved together" isn't something the compiler
can do yet. See `ARCHITECTURE.md` → roadmap item 8 for the full
rationale.

**The CLI that consumes this manifest does not exist yet.** This
module (subprojeto 7) produces the data (`registry.json` +
`components/*.json`); the `suko add` command that reads it (subprojeto
8) is future work. Until then, the only way to use these components is
to copy the `.sk` files under `src/main/suko/io/suko/ui/` by hand.

## External requirements

Every component here assumes the consumer's project already has:

- **Tailwind CSS 3.x** — every component's markup is plain Tailwind
  utility classes; nothing here generates or ships CSS.
- **Alpine.js 3.x** — only `Dialog` needs it (for `x-data`/`x-show`
  open/close state); the other seven components have no JS
  dependency.

**Real gotcha, not a hypothetical one:** Tailwind's `content` scanner
is static — it greps your source files for class-name-shaped tokens,
it does not execute anything. That means Tailwind's `content` glob in
your `tailwind.config.js` has to actually see the classes this library
uses. Two ways to get there, with different consequences:

- Point `content` at the copied `.sk` files themselves (e.g.
  `./src/main/suko/**/*.sk`). Simplest, and works today because these
  components only ever use full, static class-name strings (see the
  authoring convention below) — never `class="btn btn-${variant}"`.
- Point `content` at the generated `.jte` output in `build/` instead.
  This works too (the classes survive emission unchanged), but ties
  your Tailwind build to running the Suko compiler first, and to
  `build/` not being wiped between the two steps — a `gradle clean`
  right before a Tailwind build with no recompile in between will scan
  an empty/missing directory and silently drop styles.

Either way, if you compose a class name at runtime instead of writing
each variant as a full literal string, Tailwind's scanner never sees
it and the class is missing from the generated stylesheet even though
your `.sk` compiles and renders fine — see `ARCHITECTURE.md` →
"Limitações conhecidas" for this documented as a language-level
limitation, not just a library convention.

## Authoring conventions

If you're adding a new component to this library, every `.sk` file
under `src/main/suko/io/suko/ui/` is checked against eight conventions
(enforced by `LibraryConventionsTest`, not just documented — a
violation fails `gradle :suko-components:test`):

1. every component is `public component` (file-private components
   can't be part of a distributable library);
2. one component per file, and the file name matches the component
   name exactly (`Button.sk` declares `Button`);
3. **never** `${...}` inside a `class` attribute — write each variant
   as a complete Tailwind class string inside a `switch`/`if` instead
   (this is the convention that keeps the Tailwind gotcha above from
   biting; see `Button.sk`'s variant `switch` for the pattern);
4. named slots come **before** loose content in a call block (loose
   content written before a named slot silently swallows the slot —
   see `ARCHITECTURE.md` → "Limitações conhecidas");
5. no `{slot ?: "fallback"}` (unsupported — `Content` vs. `String`
   have no common supertype the desugared `?:` accepts);
6. no `var c = Componente() { ... }` (a component call used as a value
   can't take a slot block; the grammar silently treats the block as
   loose text instead of rejecting it);
7. no literal `<`/`>` inside string literals;
8. imports are fully qualified and **never** aliased (`import
   io.suko.ui.Label;`, not `import io.suko.ui.Label as L;`) — the
   future CLI's package-prefix rewrite depends on seeing the real
   qualified name.

`Field.sk` is the only component with a non-empty `dependsOn` (it
imports `Label` and `Input`); `Dialog` is the only one with an
`externalRequirements` entry (Alpine.js). Neither dependency is
inferred by magic — both come straight from reading the `.sk` source
(imports and a fixed per-component requirements list respectively).

## Known limitation: `Dialog` has no built-in close button

`Dialog` gives you `x-data="{ open: false }"` and `x-show` on the
outer `<div>` — enough to toggle visibility from wherever you trigger
it — but it does **not** ship an interactive close control (e.g. an
"x" button wired to `x-on:click="open = false"`). That's not a design
choice, it's a Suko grammar limitation: `htmlName` doesn't accept `:`
or `@` in an attribute name, so neither Alpine.js binding form
(`x-on:click="..."` or `@click="..."`) can be written in a `.sk` file
today. Composing your own close button in the consumer project (after
`suko add dialog` copies the source to you) is on you until that
grammar gap is closed — see `ARCHITECTURE.md` → "Limitações
conhecidas".

## Regenerating the manifest

`registry.json` and `components/*.json` are **generated, never edited
by hand**. After changing a `.sk` file (or `descriptions.properties`,
which is the single source of each component's `description` field —
not `//` comments in the `.sk`), regenerate with:

```
gradle :suko-components:generateRegistry --console=plain
```

This task is deliberately thin — all the logic (one component per
file, `public`-only, acyclic `dependsOn`, `sha256`, description
lookup) lives in `suko-registry`, tested there by JUnit; the task just
invokes the same entry point `RegistryGoldenTest` uses to compare, so
"what the test checks" and "what this task writes" can't diverge.
It does **not** run as part of `build`/`check` — the committed JSON is
the reference, checked by `RegistryGoldenTest` in `test`; regenerating
is a manual step you run and commit after changing a source file.
