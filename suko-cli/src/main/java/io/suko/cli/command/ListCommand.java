package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.ProjectConfig;
import io.suko.cli.RegistrySources;
import io.suko.cli.VerifiedIndex;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistrySource;
import io.suko.registry.TrustedKeys;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code suko list}: reads and verifies {@code registry.json} (via
 * {@link VerifiedIndex}) and prints name, version,
 * category and description, column-aligned.
 */
public final class ListCommand {

    public void run(Args args, PrintStream out, Path projectDir) {
        Optional<ProjectConfig> fileConfig = ProjectConfig.load(projectDir);

        String registryBase = args.registryBase() != null ? args.registryBase()
                : fileConfig.map(c -> c.registry() != null ? c.registry().base() : null).orElse(null);

        if (registryBase == null) {
            throw new CliException(
                    "No registry configured. Pass --registry <path|url>, or run `suko init` to create a suko.json.");
        }

        String registryRef = args.registryRef() != null ? args.registryRef()
                : fileConfig.map(c -> c.registry() != null ? c.registry().ref() : null)
                        .orElse(InitCommand.DEFAULT_REGISTRY_REF);
        // Keys from suko.json only count for suko.json's own registry (same
        // rule as ProjectConfig.resolve): --registry elsewhere drops them.
        TrustedKeys configuredKeys = fileConfig
                .filter(c -> c.registry() != null && registryBase.equals(c.registry().base()))
                .map(c -> c.registry().trustedKeys())
                .orElse(TrustedKeys.empty());

        RegistrySource source = RegistrySources.resolve(registryBase);
        // `suko list` writes nothing and has no lockfile to protect, hence no
        // rollback/anti-strip state (Optional.empty()); signature, identity
        // and expiry are still verified like every other command.
        RegistryIndex index = VerifiedIndex.load(source, registryBase, registryRef, Optional.empty(),
                args.allowUnsigned(), args.allowDowngrade(), configuredKeys, System.err).index();

        printTable(out, index.components());
    }


    private void printTable(PrintStream out, List<RegistryIndex.Entry> components) {
        if (components.isEmpty()) {
            out.println("(no components in this registry)");
            return;
        }

        int nameWidth = "NAME".length();
        int versionWidth = "VERSION".length();
        int categoryWidth = "CATEGORY".length();
        for (RegistryIndex.Entry entry : components) {
            nameWidth = Math.max(nameWidth, entry.name().length());
            versionWidth = Math.max(versionWidth, entry.version().length());
            categoryWidth = Math.max(categoryWidth, entry.category().length());
        }

        String format = "%-" + nameWidth + "s  %-" + versionWidth + "s  %-" + categoryWidth + "s  %s";
        out.println(String.format(format, "NAME", "VERSION", "CATEGORY", "DESCRIPTION"));
        for (RegistryIndex.Entry entry : components) {
            out.println(String.format(format, entry.name(), entry.version(), entry.category(), entry.description()));
        }
    }
}
