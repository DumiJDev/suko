package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.ProjectConfig;
import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.HttpRegistrySource;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistryJsonException;
import io.suko.registry.RegistrySource;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * {@code suko list}: reads {@code registry.json} (via {@link RegistrySource}
 * + {@link RegistryJson#readIndex(String)}) and prints name, version,
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

        RegistrySource source = resolveSource(registryBase);

        byte[] indexBytes;
        try {
            indexBytes = source.resolve("registry.json");
        } catch (IOException e) {
            throw new CliException("Could not read registry.json from \"" + registryBase + "\": " + e.getMessage());
        }

        RegistryIndex index;
        try {
            index = RegistryJson.readIndex(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException("Could not parse registry.json from \"" + registryBase + "\": " + e.getMessage());
        }

        printTable(out, index.components());
    }

    private RegistrySource resolveSource(String base) {
        if (base.startsWith("http://") || base.startsWith("https://")) {
            return new HttpRegistrySource(base);
        }
        return new FileSystemRegistrySource(Path.of(base));
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
