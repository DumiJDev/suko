package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.Hashes;
import io.suko.cli.LockEntry;
import io.suko.cli.Lockfile;
import io.suko.cli.NamespaceRewriter;
import io.suko.cli.ProjectConfig;
import io.suko.cli.Reconciler;
import io.suko.cli.RegistrySources;
import io.suko.cli.ResolutionPlan;
import io.suko.cli.Resolver;
import io.suko.registry.ComponentFile;
import io.suko.registry.ComponentManifest;
import io.suko.registry.ExternalRequirement;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code suko add}: installs one or more components (and the transitive
 * closure of their {@code dependsOn}) into this project.
 * <p>
 * The eight steps below are the mitigation of risk R1 in the subprojeto 8
 * plan and their order is <strong>not negotiable</strong>: config; index +
 * dependency closure ({@link Resolver}); fetch every file and verify its
 * {@code sha256} against the manifest (before any rewriting — this is the
 * only place the manifest's hash is ever compared against real bytes);
 * rewrite every file's namespace in memory ({@link NamespaceRewriter});
 * compute destinations and reconcile against the existing lockfile
 * ({@link Reconciler}) — a single unresolved conflict aborts before a
 * single byte is written; write files; write the lockfile last; print the
 * aggregated {@code externalRequirements}. Every step before "write files"
 * operates purely in memory, which is what makes the whole operation
 * atomic: any failure in fetching, hash verification, or reconciliation
 * throws a {@link CliException} before the first {@code Files.write} call,
 * so a failure partway through never leaves a partial install on disk or a
 * stale lockfile.
 * </p>
 */
public final class AddCommand {

    public void run(Args args, PrintStream out, Path projectDir) {
        if (args.positionals().isEmpty()) {
            throw new CliException(
                    "suko add requires at least one component name, e.g. `suko add button`. "
                            + "Run `suko list` to see available components.");
        }

        // Step 1: config.
        ProjectConfig config = ProjectConfig.resolve(args, projectDir);
        String registryBase = config.registry().base();
        if (registryBase == null) {
            throw new CliException(
                    "No registry configured. Pass --registry <path|url>, or run `suko init` to create a suko.json.");
        }
        String registryRef = config.registry().ref() != null ? config.registry().ref() : InitCommand.DEFAULT_REGISTRY_REF;

        RegistrySource source = RegistrySources.resolve(registryBase);

        // Step 2: index + dependency closure.
        RegistryIndex index = loadIndex(source, registryBase);
        ResolutionPlan plan = Resolver.resolve(index, source, args.positionals());

        // Step 3: fetch every file and verify its sha256 against the
        // manifest — the ONLY time the manifest's hash is compared against
        // real bytes, since these bytes are still pre-rewrite.
        List<FetchedFile> fetched = fetchAndVerify(plan, source);

        // Step 4: rewrite every file's namespace, entirely in memory.
        List<RewrittenFile> rewritten = rewriteAll(fetched, config);

        // Step 5: compute destinations and reconcile against the lockfile.
        Path sourceRootAbsolute = projectDir.resolve(config.sourceRoot()).toAbsolutePath().normalize();
        Optional<Lockfile> existingLockfile = Lockfile.load(projectDir);
        List<PlannedFile> plannedFiles = classifyAll(rewritten, config, sourceRootAbsolute, existingLockfile);

        List<String> unresolved = new ArrayList<>();
        for (PlannedFile planned : plannedFiles) {
            if ((planned.action == Reconciler.Action.CONFLICT || planned.action == Reconciler.Action.REFUSE_UNOWNED)
                    && !args.force()) {
                unresolved.add(describeUnresolved(planned));
            }
        }
        if (!unresolved.isEmpty()) {
            throw new CliException(
                    "Aborting `suko add` before writing anything — the following file(s) need attention:\n"
                            + String.join("\n", unresolved)
                            + "\n\nInspect with `suko diff`, or pass --force to overwrite.");
        }

        printPlan(out, plannedFiles, args.dryRun());

        if (args.dryRun()) {
            out.println();
            out.println("(dry run: nothing written)");
            return;
        }

        // Step 6: write files.
        for (PlannedFile planned : plannedFiles) {
            if (planned.write) {
                writeFile(planned.diskPath, planned.finalBytes);
            }
        }

        // Step 7: write the lockfile last.
        Lockfile newLockfile = buildLockfile(config, index, registryBase, registryRef, plan, plannedFiles, existingLockfile);
        newLockfile.write(projectDir);
        out.println();
        out.println("Wrote " + projectDir.resolve(Lockfile.FILE_NAME));

        // Step 8: print external requirements, never act on them.
        printExternalRequirements(out, plan);
    }

    // --- Step 1 helpers ---

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

    // --- Step 3 ---

    private record FetchedFile(ComponentManifest manifest, ComponentFile manifestFile, byte[] rawBytes) {
    }

    private List<FetchedFile> fetchAndVerify(ResolutionPlan plan, RegistrySource source) {
        List<FetchedFile> result = new ArrayList<>();
        for (ResolutionPlan.Resolved resolved : plan.components()) {
            ComponentManifest manifest = resolved.manifest();
            for (ComponentFile manifestFile : manifest.files()) {
                byte[] rawBytes;
                try {
                    rawBytes = source.resolve(manifestFile.path());
                } catch (IOException e) {
                    throw new CliException(
                            "Could not fetch \"" + manifestFile.path() + "\" for component \"" + manifest.name()
                                    + "\": " + e.getMessage() + ". Aborting `suko add` — nothing was written.");
                }
                String actualSha256 = Hashes.sha256OfRaw(rawBytes);
                if (!actualSha256.equals(manifestFile.sha256())) {
                    throw new CliException(
                            "Content fetched for \"" + manifestFile.path() + "\" (component \"" + manifest.name()
                                    + "\") does not match the sha256 recorded in its manifest (expected "
                                    + manifestFile.sha256() + ", got " + actualSha256
                                    + "). Aborting `suko add` — nothing was written. This registry may be "
                                    + "corrupted, or the transport modified the content in transit.");
                }
                result.add(new FetchedFile(manifest, manifestFile, rawBytes));
            }
        }
        return result;
    }

    // --- Step 4 ---

    private record RewrittenFile(ComponentManifest manifest, ComponentFile manifestFile, byte[] rewrittenBytes) {
    }

    private List<RewrittenFile> rewriteAll(List<FetchedFile> fetched, ProjectConfig config) {
        List<RewrittenFile> result = new ArrayList<>(fetched.size());
        for (FetchedFile file : fetched) {
            byte[] rewrittenBytes = NamespaceRewriter.rewrite(
                    file.rawBytes(), file.manifestFile().path(), file.manifest().basePackage(), config.basePackage());
            result.add(new RewrittenFile(file.manifest(), file.manifestFile(), rewrittenBytes));
        }
        return result;
    }

    // --- Step 5 ---

    private record PlannedFile(ComponentManifest manifest, ComponentFile manifestFile, String lockTarget,
                                Path diskPath, Reconciler.Action action, boolean write, byte[] finalBytes) {
    }

    private List<PlannedFile> classifyAll(List<RewrittenFile> rewritten, ProjectConfig config,
            Path sourceRootAbsolute, Optional<Lockfile> existingLockfile) {
        String basePackageFolder = config.basePackage().replace('.', '/');

        List<PlannedFile> result = new ArrayList<>(rewritten.size());
        for (RewrittenFile file : rewritten) {
            ComponentManifest manifest = file.manifest();
            ComponentFile manifestFile = file.manifestFile();

            String lockTarget = basePackageFolder + "/" + manifestFile.target();
            Path diskPath = sourceRootAbsolute.resolve(lockTarget).normalize();
            if (!diskPath.startsWith(sourceRootAbsolute)) {
                // Defense in depth, mirroring FileSystemRegistrySource's own
                // containment check against its base, in the opposite
                // direction: a manifest target that (once combined with
                // basePackage) escapes sourceRoot must never be written,
                // however that target got here (hand-edited registry,
                // future bug in the generator, ...).
                throw new CliException(
                        "Component \"" + manifest.name() + "\" declares a file target \"" + manifestFile.target()
                                + "\" that would resolve outside of sourceRoot \"" + sourceRootAbsolute
                                + "\". Refusing to write it.");
            }

            Optional<LockEntry.FileEntry> lockEntry = existingLockfile.flatMap(lf -> lf.components().stream()
                    .filter(c -> c.name().equals(manifest.name()))
                    .flatMap(c -> c.files().stream())
                    .filter(f -> f.target().equals(lockTarget))
                    .findFirst());

            Optional<byte[]> diskBytes = readDiskBytes(diskPath);

            Reconciler.Action action = Reconciler.classify(diskBytes, lockEntry, manifestFile);

            boolean write = switch (action) {
                case REINSTALL, OVERWRITE -> true;
                case NO_OP, KEEP_LOCAL_EDIT -> false;
                // CONFLICT/REFUSE_UNOWNED without --force already aborted
                // the whole command before this loop's caller inspects
                // `write`; with --force they are treated as an authorized
                // overwrite.
                case CONFLICT, REFUSE_UNOWNED -> true;
            };
            byte[] finalBytes = write ? file.rewrittenBytes() : diskBytes.orElse(file.rewrittenBytes());

            result.add(new PlannedFile(manifest, manifestFile, lockTarget, diskPath, action, write, finalBytes));
        }
        return result;
    }

    private Optional<byte[]> readDiskBytes(Path diskPath) {
        if (!Files.isRegularFile(diskPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllBytes(diskPath));
        } catch (IOException e) {
            throw new CliException("Could not read " + diskPath + ": " + e.getMessage());
        }
    }

    private String describeUnresolved(PlannedFile planned) {
        String reason = planned.action == Reconciler.Action.CONFLICT
                ? "edited locally AND upstream changed (conflict)"
                : "exists on disk but is not tracked by suko.lock.json (not installed by suko add)";
        return "  " + planned.diskPath + " — " + reason;
    }

    // --- Step 6 ---

    private void writeFile(Path diskPath, byte[] bytes) {
        try {
            Path parent = diskPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            // NamespaceRewriter always produces LF-only bytes (D8); written
            // as-is, with no further translation.
            Files.write(diskPath, bytes);
        } catch (IOException e) {
            throw new CliException("Could not write " + diskPath + ": " + e.getMessage());
        }
    }

    // --- Step 7 ---

    private Lockfile buildLockfile(ProjectConfig config, RegistryIndex index, String registryBase, String registryRef,
            ResolutionPlan plan, List<PlannedFile> plannedFiles, Optional<Lockfile> existingLockfile) {

        Map<String, LockEntry> existingByName = new LinkedHashMap<>();
        existingLockfile.ifPresent(lf -> lf.components().forEach(c -> existingByName.put(c.name(), c)));

        Map<String, List<LockEntry.FileEntry>> filesByComponent = new LinkedHashMap<>();
        for (PlannedFile planned : plannedFiles) {
            filesByComponent.computeIfAbsent(planned.manifest.name(), n -> new ArrayList<>())
                    .add(new LockEntry.FileEntry(planned.lockTarget, planned.manifestFile.sha256(),
                            Hashes.sha256OfNormalized(planned.finalBytes)));
        }

        Map<String, LockEntry> componentsByName = new LinkedHashMap<>(existingByName);
        for (ResolutionPlan.Resolved resolved : plan.components()) {
            ComponentManifest manifest = resolved.manifest();
            LockEntry existing = existingByName.get(manifest.name());
            boolean alreadyDirect = existing != null && LockEntry.REASON_DIRECT.equals(existing.reason());
            String reason = (resolved.direct() || alreadyDirect) ? LockEntry.REASON_DIRECT : LockEntry.REASON_TRANSITIVE;
            List<LockEntry.FileEntry> files = filesByComponent.getOrDefault(manifest.name(), List.of());
            componentsByName.put(manifest.name(), new LockEntry(manifest.name(), manifest.version(), reason, files));
        }

        return new Lockfile(Lockfile.SCHEMA_VERSION, new Lockfile.Registry(registryBase, registryRef, index.registryVersion()),
                config.basePackage(), config.sourceRoot(), new ArrayList<>(componentsByName.values()));
    }

    // --- Printing (steps 5/6 plan, and step 8) ---

    private void printPlan(PrintStream out, List<PlannedFile> plannedFiles, boolean dryRun) {
        out.println(dryRun ? "Plan (dry run — nothing will be written):" : "Plan:");
        for (PlannedFile planned : plannedFiles) {
            out.println("  " + planned.manifest.name() + ": " + planned.manifestFile.target() + " -> "
                    + planned.diskPath + " (" + actionLabel(planned.action) + ")");
        }
    }

    private String actionLabel(Reconciler.Action action) {
        return switch (action) {
            case REINSTALL -> "install";
            case OVERWRITE -> "update (upstream changed)";
            case NO_OP -> "up to date";
            case KEEP_LOCAL_EDIT -> "kept (edited locally, upstream unchanged)";
            case CONFLICT -> "overwrite (--force: edited locally AND upstream changed)";
            case REFUSE_UNOWNED -> "overwrite (--force: was not tracked)";
        };
    }

    private void printExternalRequirements(PrintStream out, ResolutionPlan plan) {
        Set<String> seen = new LinkedHashSet<>();
        List<ExternalRequirement> aggregated = new ArrayList<>();
        for (ResolutionPlan.Resolved resolved : plan.components()) {
            for (ExternalRequirement requirement : resolved.manifest().externalRequirements()) {
                String key = requirement.kind() + ":" + requirement.id();
                if (seen.add(key)) {
                    aggregated.add(requirement);
                }
            }
        }
        if (aggregated.isEmpty()) {
            return;
        }
        out.println();
        out.println("External requirements (not installed automatically — see the suko-cli README):");
        for (ExternalRequirement requirement : aggregated) {
            out.println("  " + requirement.kind() + " " + requirement.id() + " " + requirement.versionRange());
        }
    }
}
