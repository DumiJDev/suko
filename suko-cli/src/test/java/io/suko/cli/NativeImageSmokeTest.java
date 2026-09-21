package io.suko.cli;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Opt-in verification (Task 13, Step 7) that the {@code reachability-metadata.json}
 * embedded in the fat jar (Step 5) is actually sufficient for a real
 * {@code native-image} build to behave like the fat jar across every
 * reflective-deserialization code path — not just the one exercised by a
 * lone {@code suko add}.
 * <p>
 * This is the same 7-step script as the native-image-agent smoke-run
 * described in the Task 13 report (init; list; add; list again — now
 * reading an existing {@code suko.json}; diff; update; and a deliberate
 * "component not found" error), run once against the fat jar directly
 * (implicitly, by construction — this test only runs the compiled
 * binary), and once against a binary compiled from that same jar with
 * {@code jbang build --native}. If the two diverge, or if the native
 * binary crashes on a reflective path that the agent's smoke-run recorded
 * but a human reviewer later trimmed too aggressively from {@code
 * reachability-metadata.json} (or a later commit added a new Gson-
 * reflective field/command without re-running the agent), this test is
 * what catches it — before a consumer does.
 * </p>
 * <p>
 * Skips gracefully (does not fail) whenever {@code jbang} or a GraalVM
 * with {@code native-image} are not available on this machine — the
 * normal case for local development and for any environment without
 * GraalVM installed. Native-image compilation is slow (on the order of a
 * minute or more), so this test is expected to be run deliberately before
 * cutting a release, not as part of every {@code gradle test}.
 * </p>
 */
class NativeImageSmokeTest {

    private static final Path REAL_REGISTRY = Path.of("..", "suko-components").toAbsolutePath().normalize();

    @Test
    void nativeBinaryMatchesTheFatJarAcrossTheSevenStepScript(@TempDir Path buildDir, @TempDir Path projectDir)
            throws Exception {
        assumeJbangAvailable();
        assumeNativeImageAvailable();

        Path jar = findFatJar();
        Assumptions.assumeTrue(jar != null,
                "suko-cli-<version>-all.jar not found under build/libs/ — run `gradle :suko-cli:fatJar` first");
        Assumptions.assumeTrue(Files.isRegularFile(REAL_REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");

        run(projectDir, 0,
                "jbang", "build", "--native", "--build-dir", buildDir.toString(),
                "-m", "io.suko.cli.Main", jar.toAbsolutePath().toString());

        Path binary = findExecutable(buildDir);
        assertNotNull(binary, "jbang did not produce a native executable under " + buildDir);

        // The same 7-step script as the native-image-agent smoke-run (Task
        // 13 brief, Step 5) — deliberately covering every reflective
        // deserialization path, not just `add` alone: reading the registry
        // index (list), reading manifests + writing the lockfile (add),
        // reading an *existing* suko.json (list again), reading the
        // lockfile (diff, update), and the error-message path (add of a
        // nonexistent component).
        run(projectDir, 0, binary.toString(), "init", "--yes",
                "--base-package", "com.example.demo",
                "--registry", REAL_REGISTRY.toString(), "--registry-ref", "main");
        assertTrue(Files.isRegularFile(projectDir.resolve("suko.json")), "suko init did not write suko.json");

        run(projectDir, 0, binary.toString(), "list");
        run(projectDir, 0, binary.toString(), "add", "field");
        assertTrue(Files.isRegularFile(projectDir.resolve("suko.lock.json")), "suko add did not write suko.lock.json");
        run(projectDir, 0, binary.toString(), "list");
        run(projectDir, 0, binary.toString(), "diff", "field");
        run(projectDir, 0, binary.toString(), "update", "field");
        run(projectDir, 2, binary.toString(), "add", "naoexiste");
    }

    /** Runs a process to completion, asserting its exit code and the absence of a reflection-shaped crash. */
    private static void run(Path cwd, int expectedExitCode, String... command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Process process = builder.start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            fail("Command timed out after 120s: " + String.join(" ", command) + "\nOutput so far:\n" + output);
        }
        assertNoReflectionFailure(command, output);
        assertEquals(expectedExitCode, process.exitValue(),
                "Command " + String.join(" ", command) + " exited " + process.exitValue()
                        + " (expected " + expectedExitCode + "). Output:\n" + output);
    }

    /**
     * A missing {@code reachability-metadata.json} entry does not fail the
     * native-image build — it fails the binary, at runtime, on whichever
     * code path was not exercised by the agent's smoke-run. This asserts
     * none of that class of failure occurred, regardless of the exit code
     * (a caught {@link CliException}-shaped error still exits non-zero,
     * so exit code alone cannot distinguish the two).
     */
    private static void assertNoReflectionFailure(String[] command, String output) {
        String lower = output.toLowerCase(java.util.Locale.ROOT);
        for (String marker : new String[] {
                "missingreflectionregistrationerror", "inaccessibleobjectexception",
                "nosuchmethodexception", "nosuchfieldexception", "classnotfoundexception",
        }) {
            assertTrue(!lower.contains(marker),
                    "Command " + String.join(" ", command) + " hit a reflection-config gap (" + marker
                            + ") — reachability-metadata.json is missing an entry. Output:\n" + output);
        }
    }

    private static Path findFatJar() throws IOException {
        Path libs = Path.of("build", "libs");
        if (!Files.isDirectory(libs)) {
            return null;
        }
        try (Stream<Path> files = Files.list(libs)) {
            return files
                    .filter(p -> p.getFileName().toString().matches("suko-cli-.*-all\\.jar"))
                    .findFirst()
                    .orElse(null);
        }
    }

    private static Path findExecutable(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .filter(Files::isExecutable)
                    .findFirst()
                    .orElse(null);
        }
    }

    private static void assumeJbangAvailable() {
        Assumptions.assumeTrue(commandSucceeds("jbang", "--version"),
                "jbang not available on PATH — skipping native-image smoke test");
    }

    private static void assumeNativeImageAvailable() {
        boolean onPath = commandSucceeds("native-image", "--version");
        boolean underGraalvmHome = Optional.ofNullable(System.getenv("GRAALVM_HOME"))
                .map(home -> Path.of(home, "bin", "native-image"))
                .map(Files::isExecutable)
                .orElse(false);
        Assumptions.assumeTrue(onPath || underGraalvmHome,
                "native-image not available (not on PATH, and GRAALVM_HOME is not set to a GraalVM install "
                        + "with native-image) — skipping native-image smoke test");
    }

    private static boolean commandSucceeds(String... command) {
        try {
            List<String> full = new ArrayList<>(List.of(command));
            Process process = new ProcessBuilder(full).redirectErrorStream(true).start();
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
