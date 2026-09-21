package io.suko.cli;

import io.suko.lang.gradle.SukoWatchTask;
import io.suko.lang.support.JteRenderSupport;
import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 14 of the subprojeto 8 plan — the plan's capstone, proving its whole
 * thesis end-to-end: {@code suko add} copies real, correctly-rewritten
 * source that a consumer's own Gradle build can compile (via the real
 * {@code io.suko.lang} plugin, applied by ID under Gradle TestKit) and
 * render (with the real {@code gg.jte} engine — compiling alone is never
 * enough, project convention since subprojeto 1) — with zero manually
 * authored {@code .sk}.
 * <p>
 * This is the only test in the whole plan that crosses CLI + plugin +
 * compiler + render engine, all four together, against one single
 * temporary consumer project built purely by running the real CLI and the
 * real plugin, never by hand-authoring any intermediate artifact.
 * </p>
 * <p>
 * Deliberately does NOT add {@code suko-core} to {@code suko-cli}'s
 * {@code main} dependencies — every dependency this test needs (the
 * compiler via {@code testFixtures(suko-core)}, {@code gg.jte} itself, the
 * Gradle plugin, Gradle TestKit) is {@code testImplementation} only, exactly
 * like {@code suko-components} has done since subprojeto 7. See this
 * module's {@code build.gradle.kts} for the dependency block and for how
 * the cross-module TestKit plugin classpath problem below is solved.
 * </p>
 */
class FullCycleTest {

    /**
     * The real, committed suko-components registry — not a synthetic
     * fixture. Resolved the same way {@code AddCommandTest} already does
     * (relative to this module's directory), and skipped rather than
     * failed if it isn't there for some reason (e.g. a partial checkout),
     * matching that test's own convention.
     */
    private static final Path REGISTRY = Path.of("..", "suko-components").toAbsolutePath().normalize();

    @TempDir
    Path consumerProjectDir;

    @Test
    void addCompileRenderAndWatchProduceTheSameMirroredJteOutput() throws Exception {
        Assumptions.assumeTrue(Files.isRegularFile(REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");

        // --- Step 1: assemble the temporary consumer project. Nothing
        // .sk is hand-authored: settings.gradle.kts and build.gradle.kts
        // are the only two files written before `suko add` runs.
        Files.writeString(consumerProjectDir.resolve("settings.gradle.kts"), """
                rootProject.name = "full-cycle-test-consumer"
                """);
        Files.writeString(consumerProjectDir.resolve("build.gradle.kts"), """
                plugins {
                    id("io.suko.lang")
                }
                """);

        // --- Step 2: run the CLI for real, through the same testable
        // entry point MainTest already uses (Main.run — never
        // System.exit, explicit streams and project directory).
        ByteArrayOutputStream addOut = new ByteArrayOutputStream();
        ByteArrayOutputStream addErr = new ByteArrayOutputStream();
        int addExitCode = Main.run(new String[] {
                "add", "field",
                "--registry", REGISTRY.toString(),
                "--base-package", "com.acme.web",
                "--source-root", "src/main/suko",
                "--yes"
        }, emptyStdin(), printStream(addOut), printStream(addErr), consumerProjectDir);

        assertEquals(0, addExitCode, "suko add field failed:\n" + addErr.toString(StandardCharsets.UTF_8)
                + "\n" + addOut.toString(StandardCharsets.UTF_8));

        Path skDir = consumerProjectDir.resolve("src/main/suko/com/acme/web/ui");
        Path fieldSk = skDir.resolve("Field.sk");
        Path labelSk = skDir.resolve("Label.sk");
        Path inputSk = skDir.resolve("Input.sk");
        assertTrue(Files.isRegularFile(fieldSk), "expected " + fieldSk + " to exist");
        assertTrue(Files.isRegularFile(labelSk), "expected " + labelSk + " to exist");
        assertTrue(Files.isRegularFile(inputSk), "expected " + inputSk + " to exist");

        String fieldSource = Files.readString(fieldSk);
        assertTrue(fieldSource.contains("package com.acme.web.ui;"),
                "package must be rewritten to the consumer's base package: " + fieldSource);
        assertTrue(fieldSource.contains("import com.acme.web.ui.Input;"),
                "import of Input must be rewritten, fully qualified: " + fieldSource);
        assertTrue(fieldSource.contains("import com.acme.web.ui.Label;"),
                "import of Label must be rewritten, fully qualified: " + fieldSource);
        // Checked line by line, not as a blanket substring search: Field.sk's
        // own doc comments legitimately mention "io.suko.ui" in prose (as an
        // example of the upstream package name) — NamespaceRewriter never
        // touches comments (only exact package/import declaration lines),
        // so that mention is expected to survive untouched. What must NOT
        // survive is an actual package/import line still pointing upstream.
        assertTrue(fieldSource.lines().noneMatch(line -> {
            String trimmed = line.strip();
            return (trimmed.startsWith("package ") || trimmed.startsWith("import "))
                    && trimmed.contains("io.suko");
        }), "no package/import line may still reference the upstream base package: " + fieldSource);

        Lockfile lockfile = Lockfile.load(consumerProjectDir)
                .orElseThrow(() -> new AssertionError("suko.lock.json was not written"));
        assertEquals(3, lockfile.components().size(), "expected field + its transitive label/input");
        assertEquals("direct", reasonOf(lockfile, "field"));
        assertEquals("transitive", reasonOf(lockfile, "label"));
        assertEquals("transitive", reasonOf(lockfile, "input"));
        for (LockEntry component : lockfile.components()) {
            assertFalse(component.files().isEmpty(), component.name() + " should have written at least one file");
            for (LockEntry.FileEntry file : component.files()) {
                assertNotNull(file.upstreamSha256(), "upstreamSha256 for " + file.target());
                assertNotNull(file.localSha256(), "localSha256 for " + file.target());
                assertFalse(file.upstreamSha256().isBlank());
                assertFalse(file.localSha256().isBlank());
            }
        }

        // --- Step 3: compile with the real plugin, applied by ID, via
        // Gradle TestKit. Zero diagnostics is the proof that both the
        // import rewriting (Step 2 above) AND the dependsOn graph are
        // correct: Field, once installed side by side with Label and
        // Input, must resolve both without a single [ERROR]/[WARNING].
        BuildResult compileResult = GradleRunner.create()
                .withProjectDir(consumerProjectDir.toFile())
                .withPluginClasspath(pluginUnderTestClasspath())
                .withArguments("sukoCompile", "--console=plain", "--stacktrace")
                .build();

        assertFalse(compileResult.getOutput().contains("[ERROR]"),
                "sukoCompile must produce zero diagnostics:\n" + compileResult.getOutput());
        assertFalse(compileResult.getOutput().contains("[WARNING]"),
                "sukoCompile must produce zero diagnostics:\n" + compileResult.getOutput());

        Path generatedDir = consumerProjectDir.resolve("build/generated-src/suko");
        Path fieldJte = generatedDir.resolve("com/acme/web/ui/Field.jte");
        Path labelJte = generatedDir.resolve("com/acme/web/ui/Label.jte");
        Path inputJte = generatedDir.resolve("com/acme/web/ui/Input.jte");
        assertTrue(Files.isRegularFile(fieldJte), "expected " + fieldJte + " to exist");
        assertTrue(Files.isRegularFile(labelJte), "expected " + labelJte + " to exist");
        assertTrue(Files.isRegularFile(inputJte), "expected " + inputJte + " to exist");

        // --- Step 4: render with the real gg.jte engine (3.1.12, same
        // version pinned in suko-core). Compiling is never enough
        // (project convention since subprojeto 1) — assert on the actual
        // rendered HTML structure: Label's <label> and Input's <input>
        // nested inside Field's own <div class="space-y-1">.
        String html = JteRenderSupport.renderFromDirectory(generatedDir, "com/acme/web/ui/Field", Map.of(
                "id", "email",
                "name", "email",
                "label", "Email",
                "type", "email",
                "required", true,
                "placeholder", "you@example.com"));

        assertTrue(html.contains("<div class=\"space-y-1\">"),
                "expected Field's own wrapping <div>: " + html);
        assertTrue(html.contains("<label>Email</label>"),
                "expected Label's rendered <label>: " + html);
        assertTrue(html.contains("<input"), "expected Input's rendered <input>: " + html);
        assertTrue(html.contains("id=\"email\""), "Input's id attribute must reflect the param: " + html);
        assertTrue(html.contains("name=\"email\""), "Input's name attribute must reflect the param: " + html);
        assertTrue(html.contains("type=\"email\""), "Input's type attribute must reflect the param: " + html);

        // --- Step 5: sukoWatch must mirror the same package structure as
        // sukoCompile, for the exact same source tree (the end-to-end
        // verification Task 5 was for: watch mode compiling via
        // SukoProjectCompiler, mirroring packages, not writing flat to the
        // output root).
        //
        // Running the actual `sukoWatch` Gradle task under TestKit is not
        // feasible here: SukoWatchTask.watch() is an intentionally
        // infinite loop (WatchService.take(), broken only by Ctrl+C or
        // thread interruption) with no single-shot/test mode of its own,
        // and GradleRunner.build() blocks until the invoked task
        // completes — there is no supported way to hand TestKit a timeout
        // or to cleanly kill the build mid-flight without risking a
        // hanging CI job. suko-gradle-plugin's OWN test suite
        // (SukoWatchTaskE2ETest) already established the pragmatic
        // substitute for exactly this reason: invoke the task's real
        // production compileAll(Path, Path) method directly — the exact
        // code path watch() calls on every relevant filesystem event
        // (including its one guaranteed, non-looping call: the "initial
        // compilation" before the watch loop is even entered) — instead
        // of driving the infinite loop itself. compileAll was made public
        // (was package-private) for this task specifically, so this
        // cross-module test can call the same production method rather
        // than reimplementing or duplicating its logic.
        Path watchSourceDir = consumerProjectDir.resolve("src/main/suko");
        Path watchOutputDir = consumerProjectDir.resolve("build/generated-src/suko-watch");
        newWatchTask().compileAll(watchSourceDir, watchOutputDir);

        Path watchFieldJte = watchOutputDir.resolve("com/acme/web/ui/Field.jte");
        Path watchLabelJte = watchOutputDir.resolve("com/acme/web/ui/Label.jte");
        Path watchInputJte = watchOutputDir.resolve("com/acme/web/ui/Input.jte");
        assertTrue(Files.isRegularFile(watchFieldJte), "expected " + watchFieldJte + " to exist");
        assertTrue(Files.isRegularFile(watchLabelJte), "expected " + watchLabelJte + " to exist");
        assertTrue(Files.isRegularFile(watchInputJte), "expected " + watchInputJte + " to exist");

        assertEquals(Files.readString(fieldJte), Files.readString(watchFieldJte),
                "sukoWatch must mirror the same output as sukoCompile for Field.jte");
        assertEquals(Files.readString(labelJte), Files.readString(watchLabelJte),
                "sukoWatch must mirror the same output as sukoCompile for Label.jte");
        assertEquals(Files.readString(inputJte), Files.readString(watchInputJte),
                "sukoWatch must mirror the same output as sukoCompile for Input.jte");
    }

    private static String reasonOf(Lockfile lockfile, String componentName) {
        return lockfile.components().stream()
                .filter(c -> c.name().equals(componentName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("component \"" + componentName + "\" not found in lockfile"))
                .reason();
    }

    /**
     * Builds the {@code List<File>} {@link GradleRunner#withPluginClasspath(List)}
     * needs, from the {@code pluginUnderTestRuntime} configuration resolved
     * in {@code suko-cli/build.gradle.kts} (suko-gradle-plugin's own
     * runtime classpath — main output plus every transitive runtime
     * dependency) and handed to the test JVM as a system property. See
     * that file's comment on {@code pluginUnderTestRuntime} for why a bare
     * {@code withPluginClasspath()} does not work from this module.
     */
    private static List<File> pluginUnderTestClasspath() {
        String classpath = System.getProperty("suko.pluginUnderTestClasspath");
        assertNotNull(classpath, "system property suko.pluginUnderTestClasspath was not set — "
                + "see suko-cli/build.gradle.kts's Test task configuration");
        List<File> files = new ArrayList<>();
        for (String entry : classpath.split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                files.add(new File(entry));
            }
        }
        assertFalse(files.isEmpty(), "suko.pluginUnderTestClasspath resolved to an empty classpath");
        return files;
    }

    private static SukoWatchTask newWatchTask() {
        Project project = ProjectBuilder.builder().build();
        return project.getTasks().create("sukoWatch", SukoWatchTask.class);
    }

    private static InputStream emptyStdin() {
        return new ByteArrayInputStream(new byte[0]);
    }

    private static PrintStream printStream(ByteArrayOutputStream target) {
        return new PrintStream(target, true, StandardCharsets.UTF_8);
    }
}
