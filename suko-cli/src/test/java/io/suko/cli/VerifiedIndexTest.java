package io.suko.cli;

import io.suko.registry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedIndexTest {

    static final String BASE = "https://reg.example/";
    final KeyPair keys = RegistrySignature.generateKeyPair();
    final ByteArrayOutputStream warnings = new ByteArrayOutputStream();

    TrustedKeys trusted() {
        return TrustedKeys.of(List.of(new TrustedKeys.Key("k1", BASE, keys.getPublic())));
    }

    /** Escreve registry.json (+ .sig opcional) e devolve a source. */
    RegistrySource registry(Path dir, String registryId, String ref, String issuedAt, String expires,
                            String registryVersion, boolean sign) throws Exception {
        RegistryIndex index = new RegistryIndex(RegistryIndex.SCHEMA_VERSION, registryVersion, "com.acme",
            registryId, ref, issuedAt, expires, List.of());
        return write(dir, RegistryJson.indexBytes(index), sign);
    }

    RegistrySource write(Path dir, byte[] bytes, boolean sign) throws IOException {
        Files.write(dir.resolve("registry.json"), bytes);
        Files.deleteIfExists(dir.resolve("registry.json.sig"));
        if (sign) {
            Files.write(dir.resolve("registry.json.sig"), RegistrySignature.sign(bytes, keys.getPrivate(), "k1"));
        }
        return new FileSystemRegistrySource(dir);
    }

    VerifiedIndex.Result load(RegistrySource s, Optional<Lockfile> lock, boolean allowUnsigned, boolean allowDowngrade) {
        return VerifiedIndex.load(s, BASE, "v1", lock, allowUnsigned, allowDowngrade, trusted(), new PrintStream(warnings));
    }

    static Lockfile lockWith(boolean signed, String issuedAt, String registryVersion) {
        return new Lockfile(1, new Lockfile.Registry(BASE, "v1", registryVersion, signed, signed ? "k1" : null, issuedAt),
            "com.acme", "src", List.of());
    }

    void assertCode(String code, Runnable r) {
        CliException e = assertThrows(CliException.class, r::run);
        assertTrue(e.getMessage().startsWith(code), e.getMessage());
    }

    @Test
    void signedIndexLoads(@TempDir Path d) throws Exception {
        var r = load(registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true), Optional.empty(), false, false);
        assertTrue(r.signed());
        assertEquals("k1", r.keyId());
    }

    @Test
    void unsignedIsRefusedEvenWhenLocalUnlessExplicitlyAllowed(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", false);
        assertCode("REGISTRY_UNSIGNED", () -> load(s, Optional.empty(), false, false));
        var r = load(s, Optional.empty(), true, false);
        assertFalse(r.signed());
        assertTrue(warnings.toString().contains("sem assinatura"), warnings.toString());
    }

    @Test
    void allowUnsignedIsRefusedForRemoteRegistries() {
        RegistrySource remote = new RegistrySource() {
            public byte[] resolve(String p) { throw new UnsupportedOperationException(); }
            public String base() { return BASE; }
        };
        assertCode("REGISTRY_UNSIGNED", () -> VerifiedIndex.load(remote, BASE, "v1", Optional.empty(), true, false, trusted(), new PrintStream(warnings)));
    }

    @Test
    void isLocalRecognisesFilesystemAndLoopback(@TempDir Path d) {
        assertTrue(VerifiedIndex.isLocal(new FileSystemRegistrySource(d), d.toString()));
        assertTrue(VerifiedIndex.isLocal(null, "http://localhost:8080/"));
        assertTrue(VerifiedIndex.isLocal(null, "http://127.0.0.1/"));
        assertTrue(VerifiedIndex.isLocal(null, "http://[::1]:9/"));
        assertFalse(VerifiedIndex.isLocal(null, "https://reg.example/"));
        assertFalse(VerifiedIndex.isLocal(null, "http://localhost.evil.example/"));
    }

    @Test
    void tamperedIndexFailsTheSignature(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        Files.writeString(d.resolve("registry.json"), Files.readString(d.resolve("registry.json")).replace("0.2.0", "9.9.9"));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> load(s, Optional.empty(), false, false));
    }

    @Test
    void signatureFromAKeyBoundToAnotherRegistryIsRejected(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        TrustedKeys other = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", "https://other.example/", keys.getPublic())));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> VerifiedIndex.load(s, BASE, "v1", Optional.empty(), false, false, other, new PrintStream(warnings)));
    }

    @Test
    void registryIdAndRefMustMatchTheConfiguration(@TempDir Path d) throws Exception {
        // (O plano construía o registry dentro do lambda com catch (Exception) -> RuntimeException, o que embrulhava
        // a própria CliException esperada; o registry é construído fora do lambda, asserções iguais.)
        var otherId = registry(d, "https://evil.example/", "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        assertCode("REGISTRY_MISMATCH", () -> load(otherId, Optional.empty(), false, false));
        var otherRef = registry(d, BASE, "v9", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        assertCode("REGISTRY_MISMATCH", () -> load(otherRef, Optional.empty(), false, false));
    }

    @Test
    void expiredIndexIsRefused(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", "0.2.0", true);
        assertCode("REGISTRY_EXPIRED", () -> load(s, Optional.empty(), false, false));
        var ok = registry(d, BASE, "v1", Instant.now().toString(), Instant.now().plusSeconds(3600).toString(), "0.2.0", true);
        assertTrue(load(ok, Optional.empty(), false, false).signed());
    }

    @Test
    void rollbackToAnOlderSignedIndexIsRefusedUnlessDowngradeIsExplicit(@TempDir Path d) throws Exception {
        var older = registry(d, BASE, "v1", "2026-01-01T00:00:00Z", null, "0.1.0", true);
        Lockfile lock = lockWith(true, "2026-10-05T00:00:00Z", "0.2.0");
        assertCode("REGISTRY_ROLLBACK", () -> load(older, Optional.of(lock), false, false));
        assertTrue(load(older, Optional.of(lock), false, true).signed());
    }

    @Test
    void sameIssuedAtButLowerVersionIsAlsoARollback(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.1.0", true);
        assertCode("REGISTRY_ROLLBACK", () -> load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), false, false));
    }

    @Test
    void aRegistrySeenSignedCannotBeStrippedEvenWithAllowUnsigned(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-06T00:00:00Z", null, "0.3.0", false);
        assertCode("REGISTRY_UNSIGNED", () -> load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), true, false));
    }

    @Test
    void lockfileFromAnotherRegistryDoesNotTriggerRollback(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-01-01T00:00:00Z", null, "0.1.0", true);
        Lockfile other = new Lockfile(1, new Lockfile.Registry("https://other.example/", "v1", "9.9.9", true, "k1", "2030-01-01T00:00:00Z"),
            "com.acme", "src", List.of());
        assertTrue(load(s, Optional.of(other), false, false).signed());
    }

    @Test
    void manifestWithAWrongHashIsRefusedByTheResolver(@TempDir Path d) throws Exception {
        // Índice assinado cuja entrada aponta para um manifesto adulterado (hashes coerentes dentro do manifesto, mas
        // o manifestSha256 do índice assinado não bate): tem de falhar ANTES do parse.
        ComponentManifest m = new ComponentManifest(RegistryIndex.SCHEMA_VERSION, "button", "0.2.0", "d", "action", "com.acme", "ui",
            "Button", List.of(new ComponentFile("src/Button.sk", "Button.sk", "00")), List.of(), List.of());
        Files.createDirectories(d.resolve("components"));
        byte[] good = RegistryJson.manifestBytes(m);
        RegistryIndex index = new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "com.acme", BASE, "v1", "2026-10-05T00:00:00Z", null,
            List.of(new RegistryIndex.Entry("button", "0.2.0", "d", "action", "components/button.json", RegistryJson.sha256Hex(good))));
        Files.write(d.resolve("components/button.json"), RegistryJson.manifestBytes(
            new ComponentManifest(RegistryIndex.SCHEMA_VERSION, "button", "0.2.0", "d", "action", "com.acme", "ui",
                "Button", List.of(new ComponentFile("src/Button.sk", "Button.sk", "11")), List.of(), List.of())));
        var source = new FileSystemRegistrySource(d);
        CliException e = assertThrows(CliException.class, () -> Resolver.resolve(index, source, List.of("button")));
        assertTrue(e.getMessage().startsWith("REGISTRY_MANIFEST_HASH"), e.getMessage());
    }

    // --- Rulings R-A/R-B/R-C (Task 11 dispatch): canonical id, strict order, fail-closed defaults ---

    @Test
    void manifestHashIsCheckedBeforeAnyParse(@TempDir Path d) throws Exception {
        // Bytes que nem sequer são JSON: se o parse corresse primeiro, o erro seria de JSON, não de hash.
        Files.createDirectories(d.resolve("components"));
        Files.writeString(d.resolve("components/button.json"), "<<not json>>");
        RegistryIndex index = new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "com.acme", BASE, "v1", "2026-10-05T00:00:00Z", null,
            List.of(new RegistryIndex.Entry("button", "0.2.0", "d", "action", "components/button.json", "a".repeat(64))));
        CliException e = assertThrows(CliException.class, () -> Resolver.resolve(index, new FileSystemRegistrySource(d), List.of("button")));
        assertTrue(e.getMessage().startsWith("REGISTRY_MANIFEST_HASH"), e.getMessage());
    }

    @Test
    void verifyUsesTheConfiguredRegistryIdNeverTheOneInsideTheIndex(@TempDir Path d) throws Exception {
        // A chave confiável está ligada a evil.example e o índice diz ser de evil.example; mas o utilizador
        // configurou BASE. Se a verificação usasse o registryId do próprio índice, isto passaria.
        var s = registry(d, "https://evil.example/", "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        TrustedKeys evil = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", "https://evil.example/", keys.getPublic())));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> VerifiedIndex.load(s, BASE, "v1", Optional.empty(), false, false, evil, new PrintStream(warnings)));
    }

    @Test
    void canonicalIdMapsTheOfficialTemplateToTheOfficialRegistryId() {
        String official = String.format(VerifiedIndex.OFFICIAL_BASE_TEMPLATE, "v0.2.0");
        assertEquals(VerifiedIndex.OFFICIAL_REGISTRY_ID, VerifiedIndex.canonicalId(official, "v0.2.0"));
        assertEquals(VerifiedIndex.OFFICIAL_REGISTRY_ID, VerifiedIndex.canonicalId(official.substring(0, official.length() - 1), "v0.2.0"));
        // Ref pedida diferente da que está no URL: não é a forma oficial, fica o próprio base.
        assertEquals(official, VerifiedIndex.canonicalId(official, "v0.3.0"));
        assertEquals("https://x.example/r/", VerifiedIndex.canonicalId("https://x.example/r", "v1"));
        assertEquals(BASE, VerifiedIndex.canonicalId(BASE, "v1"));
    }

    @Test
    void officialBaseVerifiesAgainstTheOfficialRegistryId(@TempDir Path d) throws Exception {
        String base = String.format(VerifiedIndex.OFFICIAL_BASE_TEMPLATE, "v0.2.0");
        var s = registry(d, VerifiedIndex.OFFICIAL_REGISTRY_ID, "v0.2.0", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        TrustedKeys officialKey = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", VerifiedIndex.OFFICIAL_REGISTRY_ID, keys.getPublic())));
        var r = VerifiedIndex.load(s, base, "v0.2.0", Optional.empty(), false, false, officialKey, new PrintStream(warnings));
        assertTrue(r.signed());
        // A mesma chave ligada ao URL concreto (não canónico) não serve.
        TrustedKeys boundToUrl = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", base, keys.getPublic())));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> VerifiedIndex.load(s, base, "v0.2.0", Optional.empty(), false, false, boundToUrl, new PrintStream(warnings)));
    }

    @Test
    void rollbackAcrossRefsOfTheOfficialRegistryIsDetected(@TempDir Path d) throws Exception {
        String newer = String.format(VerifiedIndex.OFFICIAL_BASE_TEMPLATE, "v0.3.0");
        String older = String.format(VerifiedIndex.OFFICIAL_BASE_TEMPLATE, "v0.2.0");
        var s = registry(d, VerifiedIndex.OFFICIAL_REGISTRY_ID, "v0.2.0", "2026-09-01T00:00:00Z", null, "0.2.0", true);
        TrustedKeys officialKey = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", VerifiedIndex.OFFICIAL_REGISTRY_ID, keys.getPublic())));
        Lockfile lock = new Lockfile(1, new Lockfile.Registry(newer, "v0.3.0", "0.3.0", true, "k1", "2026-10-01T00:00:00Z"),
            "com.acme", "src", List.of());
        assertCode("REGISTRY_ROLLBACK", () -> VerifiedIndex.load(s, older, "v0.2.0", Optional.of(lock), false, false, officialKey, new PrintStream(warnings)));
    }

    @Test
    void anHttpsRegistryWithNoConfiguredKeyFailsClosed() {
        // R6: a lista oficial de chaves embutidas está vazia. Sem chave configurada, nada remoto passa.
        String base = String.format(VerifiedIndex.OFFICIAL_BASE_TEMPLATE, "v0.2.0");
        byte[] index = RegistryJson.indexBytes(new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "io.suko",
            VerifiedIndex.OFFICIAL_REGISTRY_ID, "v0.2.0", "2026-10-05T00:00:00Z", null, List.of()));
        assertTrue(Trust.keys().forRegistry(VerifiedIndex.OFFICIAL_REGISTRY_ID).isEmpty());
        RegistrySource signed = remote(base, Map.of("registry.json", index,
            "registry.json.sig", RegistrySignature.sign(index, keys.getPrivate(), "k1")));
        CliException e = assertThrows(CliException.class, () -> VerifiedIndex.load(signed, base, "v0.2.0", Optional.empty(),
            false, false, TrustedKeys.empty(), new PrintStream(warnings)));
        assertTrue(e.getMessage().startsWith("REGISTRY_BAD_SIGNATURE"), e.getMessage());
        assertTrue(e.getMessage().contains("publicKeys"), "should say how to configure a key: " + e.getMessage());

        RegistrySource unsigned = remote(base, Map.of("registry.json", index));
        assertCode("REGISTRY_UNSIGNED", () -> VerifiedIndex.load(unsigned, base, "v0.2.0", Optional.empty(),
            false, false, TrustedKeys.empty(), new PrintStream(warnings)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 1, 3})
    void onlySchemaVersionTwoIsAccepted(int schemaVersion, @TempDir Path d) throws Exception {
        byte[] bytes = RegistryJson.indexBytes(new RegistryIndex(schemaVersion, "0.2.0", "com.acme",
            BASE, "v1", "2026-10-05T00:00:00Z", null, List.of()));
        var signed = write(d, bytes, true);
        assertCode("REGISTRY_INVALID", () -> load(signed, Optional.empty(), false, false));
        var unsigned = write(d, bytes, false);
        assertCode("REGISTRY_INVALID", () -> load(unsigned, Optional.empty(), true, false));
    }

    @Test
    void unparseableDatesFailClosed(@TempDir Path d) throws Exception {
        var badIssued = registry(d, BASE, "v1", "ontem", null, "0.2.0", true);
        assertCode("REGISTRY_INVALID", () -> load(badIssued, Optional.empty(), false, false));
        var badExpires = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", "amanhã", "0.2.0", true);
        assertCode("REGISTRY_INVALID", () -> load(badExpires, Optional.empty(), false, false));
    }

    @Test
    void anExpiredUnsignedLocalIndexIsAlsoRefused(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", "0.2.0", false);
        assertCode("REGISTRY_EXPIRED", () -> load(s, Optional.empty(), true, false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.json", "components/../../escape.json", "/etc/passwd", "\\\\host\\share.json",
        "C:/x.json", "https://evil.example/x.json", "file:///etc/passwd", "components//x.json", "components/%2e%2e/x.json",
        "components\\x.json", "./components/x.json", "components/x.json?a", ""})
    void manifestPathsThatCouldEscapeTheRegistryAreRefused(String path, @TempDir Path d) throws Exception {
        byte[] bytes = RegistryJson.indexBytes(new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "com.acme",
            BASE, "v1", "2026-10-05T00:00:00Z", null,
            List.of(new RegistryIndex.Entry("x", "0.1.0", "d", "c", path, "a".repeat(64)))));
        var s = write(d, bytes, true);
        assertCode("REGISTRY_INVALID", () -> load(s, Optional.empty(), false, false));
    }

    static List<String> badHashes() {
        return List.of("", "abc", "A".repeat(64), "g".repeat(64), "a".repeat(63), "a".repeat(65), " " + "a".repeat(63));
    }

    @ParameterizedTest
    @MethodSource("badHashes")
    void manifestSha256MustBeSixtyFourLowercaseHex(String sha, @TempDir Path d) throws Exception {
        byte[] bytes = RegistryJson.indexBytes(new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "com.acme",
            BASE, "v1", "2026-10-05T00:00:00Z", null,
            List.of(new RegistryIndex.Entry("x", "0.1.0", "d", "c", "components/x.json", sha))));
        var s = write(d, bytes, true);
        assertCode("REGISTRY_MANIFEST_HASH", () -> load(s, Optional.empty(), false, false));
    }

    @Test
    void aLowerRegistryVersionWithANewerIssuedAtIsStillARollback(@TempDir Path d) throws Exception {
        // Spec M6: "registryVersion/issuedAt inferior ao registado" — qualquer dos dois a descer é rollback.
        var s = registry(d, BASE, "v1", "2026-11-01T00:00:00Z", null, "0.1.0", true);
        assertCode("REGISTRY_ROLLBACK", () -> load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), false, false));
    }

    @Test
    void newerIndexPassesTheRollbackCheck(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-06T00:00:00Z", null, "0.2.1", true);
        assertTrue(load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), false, false).signed());
    }

    @Test
    void versionComparisonOrdersPreReleasesBeforeTheFinalRelease() {
        assertTrue(VerifiedIndex.compareVersions("0.2.0-rc1", "0.2.0") < 0);
        assertTrue(VerifiedIndex.compareVersions("0.10.0", "0.9.9") > 0);
        assertEquals(0, VerifiedIndex.compareVersions("1.0", "1.0.0"));
        assertTrue(VerifiedIndex.compareVersions("0.2.0-rc.2", "0.2.0-rc.10") < 0);
    }

    // --- Fix round 1: network paths are not local; filesystem ids are normalised; namespaced resource ---

    @ParameterizedTest
    @ValueSource(strings = {"\\\\host\\share\\reg", "//host/share/reg", "\\\\?\\UNC\\host\\share", "/\\host/share",
        "file://host/share/reg", "smb://host/share/reg"})
    void networkAndNonHttpSchemeRootsAreNeverLocal(String base, @TempDir Path d) throws Exception {
        assertFalse(VerifiedIndex.isLocal(new FileSystemRegistrySource(d), base), base);
        assertFalse(VerifiedIndex.isLocal(null, base), base);
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", false);
        CliException e = assertThrows(CliException.class, () -> VerifiedIndex.load(s, base, "v1", Optional.empty(),
            true, false, trusted(), new PrintStream(warnings)));
        assertTrue(e.getMessage().startsWith("REGISTRY_UNSIGNED") && e.getMessage().contains("--allow-unsigned"), e.getMessage());
    }

    @Test
    void differentSpellingsOfTheSameFilesystemRegistryHaveTheSameCanonicalId(@TempDir Path d) throws Exception {
        Path cwd = Path.of("").toAbsolutePath();
        Path reg = Files.createDirectories(d.resolve("reg"));
        String relative = cwd.relativize(reg).toString();
        String expected = reg.toAbsolutePath().normalize() + "/";
        for (String spelling : List.of(reg.toString(), relative, "./" + relative, relative + "/../reg", reg + "/")) {
            assertEquals(expected, VerifiedIndex.canonicalId(spelling, "v1"), spelling);
        }
        assertEquals(reg.toAbsolutePath().normalize().toString(), VerifiedIndex.normalizeBase("./" + relative));
        assertEquals(BASE, VerifiedIndex.normalizeBase(BASE));
    }

    @Test
    void embeddedTrustedKeysLiveUnderTheCliNamespace() {
        assertEquals("/io/suko/cli/trusted-keys.json", Trust.RESOURCE);
        assertNotNull(Trust.class.getResource(Trust.RESOURCE), "the embedded trusted-keys resource must be on the classpath");
        assertNull(Trust.class.getResource("/trusted-keys.json"), "the old un-namespaced path must be gone");
    }

    private static RegistrySource remote(String base, Map<String, byte[]> files) {
        return new RegistrySource() {
            public byte[] resolve(String p) throws IOException {
                byte[] b = files.get(p);
                if (b == null) {
                    throw new NoSuchFileException(p);
                }
                return b;
            }
            public String base() { return base; }
        };
    }
}
