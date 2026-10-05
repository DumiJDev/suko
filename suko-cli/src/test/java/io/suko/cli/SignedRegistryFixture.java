package io.suko.cli;

import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistrySignature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyPair;
import java.util.List;
import java.util.stream.Stream;

/**
 * Test helper: a copy of a real registry (registry.json, components/, src/) whose index is re-issued for the copy's own
 * canonical id and a given ref, and signed with a key pair generated for the test. The committed registry is unsigned
 * (R7), so this is how tests reach the signed code paths end to end.
 */
public final class SignedRegistryFixture {

    public static final String KEY_ID = "test-key";

    private final KeyPair keys;
    private final Path dir;

    private SignedRegistryFixture(Path dir, KeyPair keys) {
        this.dir = dir;
        this.keys = keys;
    }

    /** Copies {@code source}'s registry files into {@code target} (no signing yet), with a fresh key pair. */
    public static SignedRegistryFixture copyOf(Path source, Path target) {
        return copyOf(source, target, RegistrySignature.generateKeyPair());
    }

    /** Like {@link #copyOf(Path, Path)}, but signing with the same key pair as {@code sameKeysAs}. */
    public static SignedRegistryFixture copyOf(Path source, Path target, SignedRegistryFixture sameKeysAs) {
        return copyOf(source, target, sameKeysAs.keys);
    }

    private static SignedRegistryFixture copyOf(Path source, Path target, KeyPair keys) {
        for (String part : List.of("registry.json", "components", "src")) {
            copyTree(source.resolve(part), target.resolve(part));
        }
        return new SignedRegistryFixture(target, keys);
    }

    /** One-shot: copy, re-issue for {@code ref}, sign; returns the base64 public key. */
    public static String signedCopy(Path source, Path target, String ref) {
        SignedRegistryFixture f = copyOf(source, target);
        f.reissue(ref, null, null);
        return f.publicKeyBase64();
    }

    public Path dir() {
        return dir;
    }

    public String publicKeyBase64() {
        return RegistrySignature.publicKeyBase64(keys.getPublic());
    }

    /**
     * Rewrites registry.json with this copy's canonical id, {@code ref} and (when not {@code null}) {@code issuedAt} /
     * {@code registryVersion}, then writes a matching registry.json.sig.
     */
    public void reissue(String ref, String issuedAt, String registryVersion) {
        try {
            RegistryIndex old = RegistryJson.readIndex(Files.readString(dir.resolve("registry.json"), StandardCharsets.UTF_8));
            RegistryIndex index = new RegistryIndex(old.schemaVersion(),
                registryVersion != null ? registryVersion : old.registryVersion(), old.basePackage(),
                VerifiedIndex.canonicalId(dir.toString(), ref), ref,
                issuedAt != null ? issuedAt : old.issuedAt(), old.expires(), old.components());
            byte[] bytes = RegistryJson.indexBytes(index);
            Files.write(dir.resolve("registry.json"), bytes);
            Files.write(dir.resolve("registry.json.sig"), RegistrySignature.sign(bytes, keys.getPrivate(), KEY_ID));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A suko.json pointing at {@code registry} with {@code publicKey} configured for it. */
    public static String sukoJson(Path registry, String ref, String basePackage, String publicKey) {
        return new ProjectConfig(ProjectConfig.SCHEMA_VERSION, ProjectConfig.DEFAULT_SOURCE_ROOT, basePackage,
            new ProjectConfig.Registry(registry.toString(), ref, List.of(new ProjectConfig.PublicKey(KEY_ID, publicKey))))
            .toJson();
    }

    private static void copyTree(Path source, Path target) {
        if (!Files.exists(source)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(source)) {
            paths.forEach(p -> {
                try {
                    Path destination = target.resolve(source.relativize(p).toString());
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(p, destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
