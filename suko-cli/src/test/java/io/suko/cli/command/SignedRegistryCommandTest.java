package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.Lockfile;
import io.suko.cli.SignedRegistryFixture;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The commands end to end against a <em>signed</em> copy of the real registry (subprojeto 14, M6): the lockfile
 * records {@code signed}/{@code keyId}/{@code issuedAt}; a later strip of the signature is refused even with
 * {@code --allow-unsigned}; an older re-issued index is a rollback unless {@code --allow-downgrade}.
 */
class SignedRegistryCommandTest {

    private static final Path REAL_REGISTRY = Path.of("..", "suko-components");

    private SignedRegistryFixture signedRegistry(Path tempDir) throws IOException {
        Assumptions.assumeTrue(Files.isRegularFile(REAL_REGISTRY.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping");
        SignedRegistryFixture f = SignedRegistryFixture.copyOf(REAL_REGISTRY, tempDir.resolve("registry"));
        f.reissue("main", "2026-10-05T00:00:00Z", "0.2.0");
        return f;
    }

    private static Path project(Path tempDir, SignedRegistryFixture registry) throws IOException {
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));
        Files.writeString(projectDir.resolve("suko.json"),
                SignedRegistryFixture.sukoJson(registry.dir(), "main", "com.acme.web", registry.publicKeyBase64()));
        return projectDir;
    }

    private static String run(Path projectDir, String... argv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream stream = new PrintStream(out, true, StandardCharsets.UTF_8);
        Args args = Args.parse(argv);
        switch (args.command()) {
            case "add" -> new AddCommand().run(args, stream, projectDir);
            case "update" -> new UpdateCommand().run(args, stream, projectDir);
            case "diff" -> new DiffCommand().run(args, stream, projectDir);
            case "list" -> new ListCommand().run(args, stream, projectDir);
            default -> throw new IllegalArgumentException(args.command());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void assertCode(String code, Runnable r) {
        CliException e = assertThrows(CliException.class, r::run);
        assertTrue(e.getMessage().startsWith(code), e.getMessage());
    }

    @Test
    void signedRegistryInstallsWithoutAnyFlagAndRecordsTheVerification(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = project(tempDir, registry);

        run(projectDir, "list");
        run(projectDir, "add", "label");

        Lockfile.Registry recorded = Lockfile.load(projectDir).orElseThrow().registry();
        assertTrue(recorded.signed());
        assertEquals(SignedRegistryFixture.KEY_ID, recorded.keyId());
        assertEquals("2026-10-05T00:00:00Z", recorded.issuedAt());
        String json = Files.readString(projectDir.resolve(Lockfile.FILE_NAME));
        assertTrue(json.contains("\"signed\": true"), json);
    }

    @Test
    void theSameRegistryWithoutTheConfiguredKeyIsRefused(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = Files.createDirectories(tempDir.resolve("project"));
        // No suko.json keys: signed, but by nobody this CLI trusts.
        assertCode("REGISTRY_BAD_SIGNATURE", () -> run(projectDir, "add", "label",
                "--registry", registry.dir().toString(), "--registry-ref", "main", "--base-package", "com.acme.web"));
        assertFalse(Files.exists(projectDir.resolve(Lockfile.FILE_NAME)));
    }

    @Test
    void strippingTheSignatureAfterwardsIsRefusedEvenWithAllowUnsigned(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = project(tempDir, registry);
        run(projectDir, "add", "label");
        String lockBefore = Files.readString(projectDir.resolve(Lockfile.FILE_NAME));

        Files.delete(registry.dir().resolve("registry.json.sig"));

        assertCode("REGISTRY_UNSIGNED", () -> run(projectDir, "update", "--allow-unsigned"));
        assertCode("REGISTRY_UNSIGNED", () -> run(projectDir, "add", "button", "--allow-unsigned"));
        assertCode("REGISTRY_UNSIGNED", () -> run(projectDir, "diff", "--allow-unsigned"));
        assertEquals(lockBefore, Files.readString(projectDir.resolve(Lockfile.FILE_NAME)), "nothing may be rewritten");
    }

    @Test
    void anOlderReissuedIndexIsARollbackUnlessDowngradeIsExplicit(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = project(tempDir, registry);
        run(projectDir, "add", "label");

        registry.reissue("main", "2026-01-01T00:00:00Z", "0.1.0");

        assertCode("REGISTRY_ROLLBACK", () -> run(projectDir, "update"));
        run(projectDir, "update", "--allow-downgrade");
        assertEquals("2026-01-01T00:00:00Z", Lockfile.load(projectDir).orElseThrow().registry().issuedAt());
    }

    @Test
    void aWrongRefIsAMismatch(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = project(tempDir, registry);
        assertCode("REGISTRY_MISMATCH", () -> run(projectDir, "add", "label", "--registry-ref", "v9.9.9"));
    }

    @Test
    void keysFromSukoJsonDoNotFollowARegistryFlagPointingElsewhere(@TempDir Path tempDir) throws IOException {
        SignedRegistryFixture registry = signedRegistry(tempDir);
        Path projectDir = project(tempDir, registry);
        // A second copy, signed by the SAME key but at another location: the key in suko.json is bound to the
        // first registry only.
        SignedRegistryFixture other = SignedRegistryFixture.copyOf(REAL_REGISTRY, tempDir.resolve("other"), registry);
        other.reissue("main", null, null);
        assertCode("REGISTRY_BAD_SIGNATURE", () -> run(projectDir, "list", "--registry", other.dir().toString()));
    }
}
