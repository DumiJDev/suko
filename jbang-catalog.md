# `jbang-catalog.json`

This catalog is what makes `jbang suko@suko-lang` (or `jbang suko@suko-lang/suko`)
resolve to a released `suko-cli` fat jar, without publishing anything to
Maven Central or any other Maven repository — subprojeto 7's D1 already
established that no such publication exists in this repo, and this catalog
does not change that. `script-ref` points directly at a GitHub Release
asset (`suko-cli-<version>-all.jar`, produced by Task 13's `fatJar` Gradle
task); every new release updates that one URL (and the version embedded in
it) to the new tag's asset.

## Running the CLI via jbang

```
jbang suko@suko-lang --help
```

`jbang` downloads the jar referenced by the alias above (once, then caches
it) and runs it with `java -jar`, exactly like the wrapper scripts in
`scripts/` do for a locally-downloaded jar.

## Running the CLI as a native binary (opt-in, consumer-side only)

**No native binary is built or published by this project.** The one and
only supported way to get one is for the *consumer* to compile it locally,
with `jbang`, from the same fat jar the alias above already points at:

```
jbang --native --build-dir <a-directory-of-your-choice> suko@suko-lang
```

- `--build-dir` is **not optional** here. Without it, the first native
  compilation of a jbang alias that points at an already-built jar (as
  opposed to a jbang script compiled from source) fails with an error
  about jbang trying to write the image into a non-existent cache
  directory — a real, reproduced jbang bug
  ([jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623)),
  open and not yet released as of this writing. `--build-dir` sidesteps it
  entirely and, as a bonus, gives the resulting binary a predictable
  location that `jbang` reuses on subsequent invocations instead of
  recompiling. Once #2623 ships in a released `jbang` version,
  `--build-dir` stops being strictly necessary, but it remains
  recommended for that same predictable-location reason.
- This requires GraalVM (with `native-image`) installed locally
  (`GRAALVM_HOME`/`PATH`), and `jbang` itself. Neither is provided by this
  project or by any CI here — there is no CI building or publishing a
  native binary, no per-platform release matrix, and the binary that comes
  out is specific to whatever machine/OS/CPU it was compiled on.
- The reflection needed by the manifest/lockfile's Gson-based (de)serialization
  is pre-recorded in `suko-cli/src/main/resources/META-INF/native-image/io.suko/suko-cli/reachability-metadata.json`,
  embedded in the fat jar, and honored automatically by `native-image` — no
  extra flags needed. **Any new CLI command, or any new field read/written
  reflectively by Gson, requires re-running the `native-image-agent` smoke
  script (see Task 13's report) and re-committing that file** — it does not
  update itself, and a stale entry does not fail the build or the fat jar,
  only the native binary, and only on the code path that was missed.
