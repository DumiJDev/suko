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
 * {@code suko update}: reapplies the reconciliation matrix (spec D7) to
 * already-installed components, refreshing files that were not edited
 * locally against whatever the registry currently has.
 * <p>
 * Unlike {@code suko add}, this command never installs a component that is
 * not already in {@code suko.lock.json}: with no positional argument it
 * only walks the lockfile's {@code direct} entries and whatever their
 * <em>current</em> {@code dependsOn} still requires (transitives no longer
 * required by any direct component are left on disk untouched — deleting
 * them is out of scope — but reported as orphaned). With one or more
 * names, only those components (and whatever their current {@code
 * dependsOn} pulls in) are updated; each must already be an entry in the
 * lockfile.
 * </p>
 * <p>
 * <strong>No three-way merge, under any circumstance</strong> (subprojeto 8
 * plan, this task's binding decision): a file that was edited locally AND
 * whose upstream changed ({@link Reconciler.Action#CONFLICT}) — or a file
 * that exists on disk with no lockfile entry ({@link
 * Reconciler.Action#REFUSE_UNOWNED}) — aborts the whole command, before a
 * single byte is written, unless {@code --force} is given; with {@code
 * --force} it is overwritten outright, never merged.
 * </p>
 */
public final class UpdateCommand {

    public void run(Args args, PrintStream out, Path projectDir) {
        Lockfile lockfile = Lockfile.load(projectDir).orElseThrow(() -> new CliException(
                "No " + Lockfile.FILE_NAME + " found in " + projectDir
                        + " — nothing is installed yet. Run `suko add <name>` first."));

        boolean wholeProject = args.positionals().isEmpty();
        List<String> requestedNames = wholeProject ? directNames(lockfile) : args.positionals();
        for (String name : requestedNames) {
            if (lockfile.components().stream().noneMatch(c -> c.name().equals(name))) {
                throw new CliException(
                        "Component \"" + name + "\" is not installed (no entry in " + Lockfile.FILE_NAME
                                + "). Run `suko add " + name + "` first — `suko update` only refreshes what is "
                                + "already installed.");
            }
        }
        if (requestedNames.isEmpty()) {
            out.println("Nothing to update: " + Lockfile.FILE_NAME + " has no \"direct\" components.");
            return;
        }

        ProjectConfig config = ProjectConfig.resolve(args, projectDir);
        String registryBase = config.registry().base();
        if (registryBase == null) {
            throw new CliException(
                    "No registry configured. Pass --registry <path|url>, or run `suko init` to create a suko.json.");
        }
        String registryRef = config.registry().ref() != null ? config.registry().ref() : lockfile.registry().ref();

        RegistrySource source = RegistrySources.resolve(registryBase);
        RegistryIndex index = loadIndex(source, registryBase);
        ResolutionPlan plan = Resolver.resolve(index, source, requestedNames);

        List<FetchedFile> fetched = fetchAndVerify(plan, source);
        List<RewrittenFile> rewritten = rewriteAll(fetched, config);

        Path sourceRootAbsolute = projectDir.resolve(config.sourceRoot()).toAbsolutePath().normalize();
        List<PlannedFile> plannedFiles = classifyAll(rewritten, config, sourceRootAbsolute, lockfile);

        List<String> unresolved = new ArrayList<>();
        for (PlannedFile planned : plannedFiles) {
            if ((planned.action == Reconciler.Action.CONFLICT || planned.action == Reconciler.Action.REFUSE_UNOWNED)
                    && !args.force()) {
                unresolved.add(describeUnresolved(planned));
            }
        }
        if (!unresolved.isEmpty()) {
            throw new CliException(
                    "Aborting `suko update` before writing anything — the following file(s) need attention:\n"
                            + String.join("\n", unresolved)
                            + "\n\nInspect with `suko diff`, or pass --force to overwrite.");
        }

        printPlan(out, plannedFiles, args.dryRun());
        Set<String> resolvedNames = new LinkedHashSet<>();
        for (ResolutionPlan.Resolved resolved : plan.components()) {
            resolvedNames.add(resolved.manifest().name());
        }
        List<String> orphans = wholeProject ? orphanedTransitives(lockfile, resolvedNames) : List.of();
        printOrphans(out, orphans);

        if (args.dryRun()) {
            out.println();
            out.println("(dry run: nothing written)");
            return;
        }

        for (PlannedFile planned : plannedFiles) {
            if (planned.write) {
                writeFile(planned.diskPath, planned.finalBytes);
            }
        }

        Lockfile newLockfile = buildLockfile(config, index, registryBase, registryRef, plan, plannedFiles, lockfile);
        newLockfile.write(projectDir);
        out.println();
        out.println("Wrote " + projectDir.resolve(Lockfile.FILE_NAME));
    }

    private List<String> directNames(Lockfile lockfile) {
        List<String> names = new ArrayList<>();
        for (LockEntry entry : lockfile.components()) {
            if (LockEntry.REASON_DIRECT.equals(entry.reason())) {
                names.add(entry.name());
            }
        }
        return names;
    }

    /**
     * Components that were {@code transitive} in the old lockfile but are
     * not required by any of the resolved (current) direct components'
     * {@code dependsOn} graph. Reported, never deleted — removing files is
     * out of scope for this task.
     */
    private List<String> orphanedTransitives(Lockfile oldLockfile, Set<String> resolvedNames) {
        List<String> orphans = new ArrayList<>();
        for (LockEntry entry : oldLockfile.components()) {
            if (LockEntry.REASON_TRANSITIVE.equals(entry.reason()) && !resolvedNames.contains(entry.name())) {
                orphans.add(entry.name());
            }
        }
        return orphans;
    }

    // --- index/manifest loading (same shape as AddCommand/DiffCommand) ---

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

    // --- fetch + verify (pre-rewrite sha256, same as AddCommand's step 3) ---

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
                                    + "\": " + e.getMessage() + ". Aborting `suko update` — nothing was written.");
                }
                String actualSha256 = Hashes.sha256OfRaw(rawBytes);
                if (!actualSha256.equals(manifestFile.sha256())) {
                    throw new CliException(
                            "Content fetched for \"" + manifestFile.path() + "\" (component \"" + manifest.name()
                                    + "\") does not match the sha256 recorded in its manifest (expected "
                                    + manifestFile.sha256() + ", got " + actualSha256
                                    + "). Aborting `suko update` — nothing was written. This registry may be "
                                    + "corrupted, or the transport modified the content in transit.");
                }
                result.add(new FetchedFile(manifest, manifestFile, rawBytes));
            }
        }
        return result;
    }

    // --- rewrite (same as AddCommand's step 4) ---

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

    // --- reconciliation (same matrix as AddCommand's step 5, against the existing lockfile) ---

    private record PlannedFile(ComponentManifest manifest, ComponentFile manifestFile, String lockTarget,
                                Path diskPath, Reconciler.Action action, boolean write, byte[] finalBytes) {
    }

    private List<PlannedFile> classifyAll(List<RewrittenFile> rewritten, ProjectConfig config,
            Path sourceRootAbsolute, Lockfile existingLockfile) {
        String basePackageFolder = config.basePackage().replace('.', '/');

        List<PlannedFile> result = new ArrayList<>(rewritten.size());
        for (RewrittenFile file : rewritten) {
            ComponentManifest manifest = file.manifest();
            ComponentFile manifestFile = file.manifestFile();

            String lockTarget = basePackageFolder + "/" + manifestFile.target();
            Path diskPath = sourceRootAbsolute.resolve(lockTarget).normalize();
            if (!diskPath.startsWith(sourceRootAbsolute)) {
                throw new CliException(
                        "Component \"" + manifest.name() + "\" declares a file target \"" + manifestFile.target()
                                + "\" that would resolve outside of sourceRoot \"" + sourceRootAbsolute
                                + "\". Refusing to write it.");
            }

            Optional<LockEntry.FileEntry> lockEntry = existingLockfile.components().stream()
                    .filter(c -> c.name().equals(manifest.name()))
                    .flatMap(c -> c.files().stream())
                    .filter(f -> f.target().equals(lockTarget))
                    .findFirst();

            Optional<byte[]> diskBytes = readDiskBytes(diskPath);

            Reconciler.Action action = Reconciler.classify(diskBytes, lockEntry, manifestFile);

            boolean write = switch (action) {
                case REINSTALL, OVERWRITE -> true;
                case NO_OP, KEEP_LOCAL_EDIT -> false;
                // CONFLICT/REFUSE_UNOWNED without --force already aborted
                // the whole command before this loop's caller inspects
                // `write`; with --force they are an authorized overwrite —
                // never a merge, see this class's own javadoc.
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
                : "exists on disk but is not tracked by suko.lock.json (not installed by suko add/update)";
        return "  " + planned.diskPath + " — " + reason;
    }

    // --- write ---

    private void writeFile(Path diskPath, byte[] bytes) {
        try {
            Path parent = diskPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(diskPath, bytes);
        } catch (IOException e) {
            throw new CliException("Could not write " + diskPath + ": " + e.getMessage());
        }
    }

    // --- lockfile (merges resolved components into the existing one; every
    // untouched entry — including an orphaned transitive — is carried over
    // unchanged, never dropped) ---

    private Lockfile buildLockfile(ProjectConfig config, RegistryIndex index, String registryBase, String registryRef,
            ResolutionPlan plan, List<PlannedFile> plannedFiles, Lockfile existingLockfile) {

        Map<String, LockEntry> componentsByName = new LinkedHashMap<>();
        for (LockEntry entry : existingLockfile.components()) {
            componentsByName.put(entry.name(), entry);
        }

        Map<String, List<LockEntry.FileEntry>> filesByComponent = new LinkedHashMap<>();
        for (PlannedFile planned : plannedFiles) {
            filesByComponent.computeIfAbsent(planned.manifest.name(), n -> new ArrayList<>())
                    .add(new LockEntry.FileEntry(planned.lockTarget, planned.manifestFile.sha256(),
                            Hashes.sha256OfNormalized(planned.finalBytes)));
        }

        for (ResolutionPlan.Resolved resolved : plan.components()) {
            ComponentManifest manifest = resolved.manifest();
            LockEntry existing = componentsByName.get(manifest.name());
            boolean alreadyDirect = existing != null && LockEntry.REASON_DIRECT.equals(existing.reason());
            String reason = (resolved.direct() || alreadyDirect) ? LockEntry.REASON_DIRECT : LockEntry.REASON_TRANSITIVE;
            List<LockEntry.FileEntry> files = filesByComponent.getOrDefault(manifest.name(), List.of());
            componentsByName.put(manifest.name(), new LockEntry(manifest.name(), manifest.version(), reason, files));
        }

        return new Lockfile(Lockfile.SCHEMA_VERSION, new Lockfile.Registry(registryBase, registryRef, index.registryVersion()),
                config.basePackage(), config.sourceRoot(), new ArrayList<>(componentsByName.values()));
    }

    // --- printing ---

    private void printPlan(PrintStream out, List<PlannedFile> plannedFiles, boolean dryRun) {
        out.println(dryRun ? "Plan (dry run — nothing will be written):" : "Plan:");
        for (PlannedFile planned : plannedFiles) {
            out.println("  " + planned.manifest.name() + ": " + planned.manifestFile.target() + " -> "
                    + planned.diskPath + " (" + actionLabel(planned.action) + ")");
        }
    }

    private void printOrphans(PrintStream out, List<String> orphans) {
        if (orphans.isEmpty()) {
            return;
        }
        out.println();
        out.println("Orphaned (no longer required by any direct component, left on disk untouched):");
        for (String name : orphans) {
            out.println("  " + name);
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
}
