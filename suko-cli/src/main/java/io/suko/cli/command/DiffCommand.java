package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.Hashes;
import io.suko.cli.LockEntry;
import io.suko.cli.Lockfile;
import io.suko.cli.NamespaceRewriter;
import io.suko.cli.ProjectConfig;
import io.suko.cli.RegistrySources;
import io.suko.cli.TextDiff;
import io.suko.registry.ComponentFile;
import io.suko.registry.ComponentManifest;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistryJsonException;
import io.suko.registry.RegistrySource;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code suko diff}: shows the difference between the local copy of an
 * installed component's file(s) and its <strong>upstream, post-rewrite</strong>
 * source — never the raw upstream (spec: comparing against the raw file
 * would make the {@code package}/{@code import} lines alone show up as a
 * diff on every single file, which is the same C2-shaped mistake as
 * comparing hashes across the rewrite boundary, just in a different
 * disguise).
 * <p>
 * With no positional argument, diffs every component currently in
 * {@code suko.lock.json}; with one or more names, diffs only those (each
 * must already be installed — {@code suko diff} never fetches a component
 * that is not tracked yet, that is what {@code suko add} is for).
 * </p>
 * <p>
 * Returns {@code 0} if every installed file matches its rewritten upstream
 * exactly, {@code 1} if at least one differs (the conventional Unix
 * {@code diff} exit code split, distinct from {@link CliException}'s
 * {@code 2} for a usage/config error).
 * </p>
 */
public final class DiffCommand {

    public int run(Args args, PrintStream out, Path projectDir) {
        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow(() -> new CliException(
                "No " + Lockfile.FILE_NAME + " found in " + projectDir
                        + " — nothing is installed yet. Run `suko add <name>` first."));

        List<LockEntry> targets = resolveTargets(args, lockfile);

        ProjectConfig config = ProjectConfig.resolve(args, projectDir);
        String registryBase = config.registry().base();
        if (registryBase == null) {
            throw new CliException(
                    "No registry configured. Pass --registry <path|url>, or run `suko init` to create a suko.json.");
        }
        RegistrySource source = RegistrySources.resolve(registryBase);
        RegistryIndex index = loadIndex(source, registryBase);
        Map<String, RegistryIndex.Entry> entriesByName = new LinkedHashMap<>();
        for (RegistryIndex.Entry entry : index.components()) {
            entriesByName.put(entry.name(), entry);
        }

        Path sourceRootAbsolute = projectDir.resolve(config.sourceRoot()).toAbsolutePath().normalize();
        String basePackageFolder = config.basePackage().replace('.', '/');

        boolean anyDifference = false;
        for (LockEntry lockEntryComponent : targets) {
            RegistryIndex.Entry indexEntry = entriesByName.get(lockEntryComponent.name());
            if (indexEntry == null) {
                out.println(lockEntryComponent.name()
                        + ": no longer present in the configured registry — cannot diff (skipping)");
                anyDifference = true;
                continue;
            }
            ComponentManifest manifest = loadManifest(source, indexEntry);
            for (ComponentFile manifestFile : manifest.files()) {
                String lockTarget = basePackageFolder + "/" + manifestFile.target();
                Path diskPath = sourceRootAbsolute.resolve(lockTarget).normalize();

                byte[] rawUpstream = fetchAndVerify(source, manifestFile, lockEntryComponent.name());
                byte[] rewrittenUpstream = NamespaceRewriter.rewrite(
                        rawUpstream, manifestFile.path(), manifest.basePackage(), config.basePackage());

                if (!Files.isRegularFile(diskPath)) {
                    out.println(diskPath + ": not found on disk (never written, or deleted since install)");
                    anyDifference = true;
                    continue;
                }
                byte[] diskBytes = readDiskBytes(diskPath);
                List<String> diffLines = TextDiff.diff(
                        new String(diskBytes, StandardCharsets.UTF_8),
                        new String(rewrittenUpstream, StandardCharsets.UTF_8));
                if (diffLines.isEmpty()) {
                    continue;
                }
                anyDifference = true;
                out.println("--- " + diskPath + " (local)");
                out.println("+++ " + diskPath + " (upstream, rewritten)");
                for (String line : diffLines) {
                    out.println(line);
                }
            }
        }
        return anyDifference ? 1 : 0;
    }

    private List<LockEntry> resolveTargets(Args args, Lockfile lockfile) {
        if (args.positionals().isEmpty()) {
            return lockfile.components();
        }
        List<LockEntry> targets = new ArrayList<>();
        for (String name : args.positionals()) {
            Optional<LockEntry> entry = lockfile.components().stream()
                    .filter(c -> c.name().equals(name))
                    .findFirst();
            if (entry.isEmpty()) {
                throw new CliException(
                        "Component \"" + name + "\" is not installed (no entry in " + Lockfile.FILE_NAME
                                + "). Run `suko add " + name + "` to install it first.");
            }
            targets.add(entry.get());
        }
        return targets;
    }

    private RegistryIndex loadIndex(RegistrySource source, String registryBase) {
        byte[] indexBytes;
        try {
            indexBytes = source.resolve("registry.json");
        } catch (IOException e) {
            throw new CliException("Could not read registry.json from \"" + registryBase + "\": " + e.getMessage());
        }
        try {
            return RegistryJson.readIndex(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException("Could not parse registry.json from \"" + registryBase + "\": " + e.getMessage());
        }
    }

    private ComponentManifest loadManifest(RegistrySource source, RegistryIndex.Entry entry) {
        byte[] bytes;
        try {
            bytes = source.resolve(entry.manifest());
        } catch (IOException e) {
            throw new CliException(
                    "Could not read manifest for \"" + entry.name() + "\" (" + entry.manifest() + "): " + e.getMessage());
        }
        try {
            return RegistryJson.readManifest(new String(bytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException(
                    "Could not parse manifest for \"" + entry.name() + "\" (" + entry.manifest() + "): " + e.getMessage());
        }
    }

    private byte[] fetchAndVerify(RegistrySource source, ComponentFile manifestFile, String componentName) {
        byte[] rawBytes;
        try {
            rawBytes = source.resolve(manifestFile.path());
        } catch (IOException e) {
            throw new CliException(
                    "Could not fetch \"" + manifestFile.path() + "\" for component \"" + componentName + "\": "
                            + e.getMessage());
        }
        String actualSha256 = Hashes.sha256OfRaw(rawBytes);
        if (!actualSha256.equals(manifestFile.sha256())) {
            throw new CliException(
                    "Content fetched for \"" + manifestFile.path() + "\" (component \"" + componentName
                            + "\") does not match the sha256 recorded in its manifest (expected "
                            + manifestFile.sha256() + ", got " + actualSha256
                            + "). This registry may be corrupted, or the transport modified the content in transit.");
        }
        return rawBytes;
    }

    private byte[] readDiskBytes(Path diskPath) {
        try {
            return Files.readAllBytes(diskPath);
        } catch (IOException e) {
            throw new CliException("Could not read " + diskPath + ": " + e.getMessage());
        }
    }
}
