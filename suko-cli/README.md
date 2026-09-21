# suko-cli

The `suko` command-line tool: installs and updates components from a Suko
registry into your own project, shadcn/ui-style — it copies `.sk` source
into your `sourceRoot`, it does not add a library dependency. See
`ARCHITECTURE.md` → roadmap item 8 for why copy-source is the only model
the current compiler architecture supports (`ProjectIndex`/
`SukoProjectCompiler` only understand a single source root).

## Installing the CLI

Exactly one artifact exists: the fat jar (`suko-cli-<version>-all.jar`,
built by this module's hand-written `Jar` task — no Shadow plugin, no other
dependency-bundling tool). There are three ways to run it:

### 1. jbang (recommended)

```
jbang suko@<owner> --help
```

The alias in the repo root's `jbang-catalog.json` points straight at a
GitHub Release asset (the fat jar) — nothing is published to Maven Central
or any other Maven repository. `jbang` downloads the jar once, caches it,
and runs it with `java -jar`. See `jbang-catalog.md` for the exact alias
name and how new releases update it.

### 2. Wrapper scripts

`scripts/suko` (POSIX shell) and `scripts/suko.bat` (Windows) are minimal
wrappers: they locate a `suko-cli-*-all.jar` next to themselves (or, in a
source checkout, under `suko-cli/build/libs/`) and run
`java -jar <that jar> "$@"`. Nothing more — not a build tool, not a jbang
replacement, just "I already have the jar, run it."

### 3. Plain `java -jar`

```
java -jar suko-cli-<version>-all.jar --help
```

Requires a Java 21+ runtime on `PATH`.

### Native binary (opt-in, consumer-side only)

**No native binary is built or published by this project.** If you want
one, you compile it yourself, once, locally, with GraalVM (`native-image`)
already installed:

```
jbang --native --build-dir <a-directory-of-your-choice> suko@<owner>
```

`--build-dir` is **not optional**. Without it, the first native compilation
of a jbang alias that points at an already-built jar (as opposed to a jbang
script compiled from source) fails on a real, still-open jbang bug
([jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623)) in how
jbang pre-creates the cache directory for that case. `--build-dir`
sidesteps it entirely.

The resulting binary is specific to whatever machine/OS/CPU you compiled it
on — there is no CI here building or publishing one, no per-platform
release matrix, and no Gradle task in this repo that invokes
`native-image`. It's a startup-time convenience for whoever wants it; the
fat jar remains the primary distribution path, and it's what the jbang
alias runs even without `--native`.

## Quick start

```
suko init                    # writes suko.json (interactive; --yes to skip prompts)
suko list                    # shows what's available in the configured registry
suko add button              # copies button.sk (and its deps) into your sourceRoot
suko diff button              # compares your copy against upstream, post-rewrite
suko update                  # refreshes every directly-installed component
```

## Commands

- **`suko init`** — writes `suko.json` (interactive, or non-interactive with
  `--yes` plus `--base-package`). Every field has a visible built-in default
  except `basePackage`, which is always asked explicitly: guessing it from
  folder structure would fail silently, and the wrong guess would only
  surface much later as a confusing package/folder-mismatch error from the
  compiler.
- **`suko list`** — reads the configured registry's index and prints the
  available components.
- **`suko add <component> [<component>...]`** — resolves the transitive
  `dependsOn` closure, fetches every file, verifies each one's `sha256`
  against the manifest (before any rewriting), rewrites each file's package
  declaration to your `basePackage`, reconciles against any existing
  `suko.lock.json` entry (see "Updates and local edits" below), writes the
  files, writes the lockfile last, and prints any external requirements the
  installed components declare.
- **`suko diff [<component>...]`** — compares the on-disk copy of an
  installed component's file(s) against upstream, **after** namespace
  rewriting (comparing against the raw upstream would make the
  `package`/`import` lines alone show up as a difference on every single
  file — the same mistake as comparing hashes across the rewrite boundary,
  just in disguise). Prints a line diff for anything that differs. Exit
  code `0` means every installed file matches its rewritten upstream
  exactly; `1` means at least one differs — the conventional Unix `diff`
  convention (distinct from the exit code used for a usage/config error).
- **`suko update [<component>...]`** — reapplies the reconciliation matrix
  to already-installed components. With no arguments, updates every
  directly-installed component (and whatever their *current* `dependsOn`
  still requires) and reports any transitive component that's no longer
  required by that graph as orphaned — left on disk untouched, never
  deleted; that's out of scope. `--dry-run` shows the plan without writing
  anything; `--force` overwrites files that would otherwise be refused
  (conflicting or unowned). Never a three-way merge, under any
  circumstance: a conflicting file is refused, or overwritten outright with
  `--force` — never merged.

## The lockfile: two hashes, not one

`suko.lock.json` records, per installed file, **two separate SHA-256
hashes**:

- **`upstreamSha256`** — the hash of the file exactly as the registry's
  manifest declares it, **before** namespace rewriting. This is what
  detects "upstream changed": is the manifest's current hash for this file
  still the one recorded when it was installed?
- **`localSha256`** — the hash of the bytes actually written to disk,
  **after** namespace rewriting (and line-ending normalization). This is
  the *only* hash ever compared against what's currently on disk: is the
  file still exactly what the CLI last wrote, or has it been edited since?

These two hashes are never interchangeable, and mixing them up is the most
expensive failure mode this tool can have. The manifest's hash is computed
over the file's raw bytes as stored in the registry — with the registry's
own `package`/`import` lines, not yours. Comparing that hash directly
against your on-disk file would make every single installed file look
"edited" the moment it's installed, because the namespace rewrite already
changed those lines before a byte ever touched your disk. The CLI never
does this: `upstreamSha256` is compared only against the manifest's current
hash, `localSha256` only against disk.

The two hashes combine into six possible outcomes (`suko update`'s
reconciliation matrix), collapsing to what you'll actually see:

| Disk vs. `localSha256` | Manifest vs. `upstreamSha256` | Result |
|---|---|---|
| matches | unchanged | nothing to do |
| matches | changed | safe to overwrite — nothing local is lost |
| edited locally | unchanged | left alone, reported as locally edited |
| edited locally | changed | conflict — refused unless `--force`, no merge |
| file missing | (n/a) | (re)installed |
| file present, no lockfile entry | (n/a) | refused unless `--force` — not something the CLI wrote |

## What the CLI writes — and what it never touches

`suko` only ever writes to three places: your configured `sourceRoot` (the
component `.sk` files themselves), `suko.json` (project config, written by
`suko init`), and `suko.lock.json` (written last, after every file in a
given run has already been written). It never edits any other file in your
project, and it never edits a file under `sourceRoot` that it did not
originally create — an on-disk file with no lockfile entry is treated as
yours, and `suko add`/`suko update` refuse to touch it without an explicit
`--force`.

## Registry pinning is by registry tag, not by component version

`suko add` and `suko update` operate against **one registry ref** (tag) at
a time — by default, the tag matching the CLI's own version
(`v<Version.current()>`), overridable with `--registry-ref`. There is no
way to pin one component to an older version while the rest of your
project's installed components track the current tag.

This isn't a missing feature so much as a consequence of what the registry
actually is: one atomic JSON document (`registry.json` + `components/*.json`)
per registry release, with no per-component version history mechanism. A
tag identifies one complete, self-consistent snapshot of every component at
once; there's no facility today for "component X's manifest as it existed
three tags ago, alongside everything else at the current tag." If you need
an older version of a single component, the practical path is to check out
that older registry tag entirely and `suko add` from it.

## External requirements (Tailwind, Alpine)

After a successful `suko add`, the CLI prints any external requirements the
installed components declare (e.g. Tailwind CSS, Alpine.js) — it never
edits your Tailwind config or otherwise installs these for you. For the
Tailwind `content`-globbing gotcha specifically (why the glob has to
actually see the copied `.sk` files, and what breaks if it doesn't), see
`suko-components/README.md` → "External requirements" rather than a second
copy of that explanation here.

## Building from source

```
gradle :suko-cli:fatJar
```

Produces `suko-cli/build/libs/suko-cli-<version>-all.jar`. `suko-cli` does
not depend on `suko-core` (production classpath) — it depends only on
`suko-registry` (registry data model + JSON I/O) and Gson; it has no
gradle/build-time dependency on the Suko compiler itself, since its job is
to move `.sk` source files around, not compile them.
