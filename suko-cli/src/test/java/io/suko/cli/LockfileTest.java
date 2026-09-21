package io.suko.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class LockfileTest {

    private static Lockfile sampleLockfile() {
        return new Lockfile(1,
                new Lockfile.Registry("https://raw.githubusercontent.com/acme/suko/v0.2.0/suko-components/", "v0.2.0", "0.1.0"),
                "com.acme.web", "src/main/suko",
                List.of(
                        new LockEntry("label", "0.1.0", LockEntry.REASON_TRANSITIVE, List.of()),
                        new LockEntry("field", "0.1.0", LockEntry.REASON_DIRECT, List.of(
                                new LockEntry.FileEntry("com/acme/web/ui/Field.sk", "f5e1861e", "9b2c41aa")))));
    }

    @Test
    void writesThenReadsBackIdenticalLockfile(@TempDir Path projectDir) {
        Lockfile lockfile = sampleLockfile();

        lockfile.write(projectDir);

        Optional<Lockfile> read = Lockfile.load(projectDir);
        assertTrue(read.isPresent());
        assertEquals(lockfile, read.get());
    }

    @Test
    void componentsAreSortedAlphabeticallyRegardlessOfConstructionOrder() {
        Lockfile lockfile = sampleLockfile();

        assertEquals(List.of("field", "label"),
                lockfile.components().stream().map(LockEntry::name).toList());
    }

    @Test
    void generatedJsonHasNoTimestampOrOtherMachineDerivedField(@TempDir Path projectDir) {
        Lockfile lockfile = sampleLockfile();

        String json = lockfile.toJson();

        assertFalse(json.toLowerCase().contains("timestamp"), "lockfile must never contain a timestamp field: " + json);
        assertFalse(json.toLowerCase().contains("generatedat"), "lockfile must never contain a generatedAt field: " + json);
        assertFalse(json.toLowerCase().contains("createdat"), "lockfile must never contain a createdAt field: " + json);
        assertFalse(json.toLowerCase().contains("updatedat"), "lockfile must never contain an updatedAt field: " + json);
    }

    @Test
    void writtenFileMatchesD7ShapeAndUsesLf(@TempDir Path projectDir) throws IOException {
        Lockfile lockfile = sampleLockfile();

        lockfile.write(projectDir);

        String written = Files.readString(projectDir.resolve(Lockfile.FILE_NAME), StandardCharsets.UTF_8);
        assertFalse(written.contains("\r\n"), "must be written with LF only, found CRLF: " + written);
        assertTrue(written.contains("\"schemaVersion\": 1"));
        assertTrue(written.contains("\"basePackage\": \"com.acme.web\""));
        assertTrue(written.contains("\"sourceRoot\": \"src/main/suko\""));
        assertTrue(written.contains("\"registryVersion\": \"0.1.0\""));
        assertTrue(written.contains("\"upstreamSha256\": \"f5e1861e\""));
        assertTrue(written.contains("\"localSha256\": \"9b2c41aa\""));
        assertTrue(written.contains("\"reason\": \"direct\""));
        assertTrue(written.contains("\"reason\": \"transitive\""));
    }

    @Test
    void loadReturnsEmptyWhenNoLockfileExists(@TempDir Path projectDir) {
        assertTrue(Lockfile.load(projectDir).isEmpty());
    }

    @Test
    void unsupportedFutureSchemaVersionIsRejectedNamingBothVersions(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(Lockfile.FILE_NAME), """
                {
                  "schemaVersion": 99,
                  "registry": { "base": "b", "ref": "r", "registryVersion": "1.0.0" },
                  "basePackage": "com.acme.web",
                  "sourceRoot": "src/main/suko",
                  "components": []
                }
                """, StandardCharsets.UTF_8);

        CliException e = assertThrows(CliException.class, () -> Lockfile.load(projectDir));

        assertTrue(e.getMessage().contains("99"));
        assertTrue(e.getMessage().contains(String.valueOf(Lockfile.SCHEMA_VERSION)));
    }

    @Test
    void malformedJsonProducesReadableMessageNotAStackTrace(@TempDir Path projectDir) throws IOException {
        Files.writeString(projectDir.resolve(Lockfile.FILE_NAME), "{ not valid json", StandardCharsets.UTF_8);

        CliException e = assertThrows(CliException.class, () -> Lockfile.load(projectDir));

        assertNotNull(e.getMessage());
        assertFalse(e.getMessage().isBlank());
    }

    @Test
    void componentWithNoFilesRoundTripsWithEmptyFilesArray(@TempDir Path projectDir) {
        Lockfile lockfile = new Lockfile(1,
                new Lockfile.Registry("base", "ref", "0.1.0"), "com.acme.web", "src/main/suko",
                List.of(new LockEntry("label", "0.1.0", LockEntry.REASON_TRANSITIVE, List.of())));

        lockfile.write(projectDir);

        Optional<Lockfile> read = Lockfile.load(projectDir);
        assertTrue(read.isPresent());
        assertTrue(read.get().components().get(0).files().isEmpty());
    }
}
