package io.suko.cli.command;

import io.suko.cli.Args;
import io.suko.cli.CliException;
import io.suko.cli.ProjectConfig;
import io.suko.cli.Version;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/**
 * {@code suko init}: interactively creates this project's {@code suko.json}
 * (spec D6).
 * <p>
 * Every field except {@code basePackage} has a visible built-in default
 * (shown in the prompt, accepted on an empty line). {@code basePackage}
 * is <strong>always</strong> asked, with no default at all: guessing it
 * from folder structure would fail silently, and the wrong guess would
 * only surface much later as a confusing package/folder-mismatch error
 * from the compiler. {@code --yes} accepts every other default without
 * prompting, but still requires {@code --base-package} to have been
 * passed — it cannot invent one.
 * </p>
 */
public final class InitCommand {

    /**
     * The CLI's own version, prefixed as a tag (spec D5). Also used by
     * {@link AddCommand} as the fallback registry ref for a {@code
     * suko.json} written before this default existed (no {@code ref} at
     * all in the {@code registry} object).
     */
    static final String DEFAULT_REGISTRY_REF = "v" + Version.current();
    static final String DEFAULT_REGISTRY_BASE_TEMPLATE =
            "https://raw.githubusercontent.com/suko-lang/suko/%s/suko-components/";

    public void run(Args args, InputStream in, PrintStream out, PrintStream err, Path projectDir) {
        Path configFile = projectDir.resolve(ProjectConfig.FILE_NAME);
        if (Files.exists(configFile) && !args.force()) {
            throw new CliException(
                    configFile + " already exists. Pass --force to overwrite it.");
        }

        Scanner scanner = new Scanner(in);

        String sourceRoot = promptOrDefault(scanner, out, args.yes(),
                "Source root", args.sourceRoot(), ProjectConfig.DEFAULT_SOURCE_ROOT);

        String basePackage = promptBasePackage(scanner, out, args);
        ProjectConfig.validateBasePackage(basePackage);

        // D5: the registry ref defaults to the CLI's own version tag
        // ("v" + Version.current()), not a hardcoded branch name — so a
        // project created with a released CLI points at the matching
        // registry snapshot by default. When Version.current() is only
        // the fallback (no jar manifest to read a real version from, e.g.
        // running from an IDE's exploded classes directory), that would
        // be a wrong default masquerading as a real one, so this is said
        // out loud on stderr instead of silently written into suko.json.
        if (Version.isFallback() && args.registryRef() == null) {
            err.println("Warning: could not determine the suko CLI's own version (running outside a built jar); "
                    + "defaulting the registry ref to \"" + DEFAULT_REGISTRY_REF + "\", which is almost certainly "
                    + "not a real release tag. Pass --registry-ref <tag> explicitly to avoid this.");
        }
        String registryRef = promptOrDefault(scanner, out, args.yes(),
                "Registry ref (tag)", args.registryRef(), DEFAULT_REGISTRY_REF);

        String defaultRegistryBase = String.format(DEFAULT_REGISTRY_BASE_TEMPLATE, registryRef);
        String registryBase = promptOrDefault(scanner, out, args.yes(),
                "Registry base (path or URL)", args.registryBase(), defaultRegistryBase);

        ProjectConfig config = new ProjectConfig(ProjectConfig.SCHEMA_VERSION, sourceRoot, basePackage,
                new ProjectConfig.Registry(registryBase, registryRef));
        config.write(projectDir);

        out.println("Wrote " + configFile);
    }

    private String promptBasePackage(Scanner scanner, PrintStream out, Args args) {
        if (args.yes()) {
            if (args.basePackage() == null) {
                throw new CliException(
                        "--yes was given but --base-package was not. basePackage cannot be guessed from the "
                                + "folder structure (a wrong guess would only surface much later as a confusing "
                                + "package/folder-mismatch error from the compiler), so it must be passed "
                                + "explicitly: suko init --yes --base-package <pkg>");
            }
            return args.basePackage();
        }

        out.println("Base package: no default is guessed from folder structure — a wrong guess would only "
                + "surface much later as a confusing compiler error, so it's always asked explicitly.");
        String prompt = args.basePackage() != null
                ? "Base package [" + args.basePackage() + "]: "
                : "Base package (e.g. com.acme.web, required): ";
        out.print(prompt);
        out.flush();
        String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        String value = line.isEmpty() ? args.basePackage() : line;
        if (value == null || value.isBlank()) {
            throw new CliException("basePackage is required and was not provided.");
        }
        return value;
    }

    private String promptOrDefault(Scanner scanner, PrintStream out, boolean yes, String label,
            String flagValue, String builtinDefault) {
        String effectiveDefault = flagValue != null ? flagValue : builtinDefault;
        if (yes) {
            return effectiveDefault;
        }
        out.print(label + " [" + effectiveDefault + "]: ");
        out.flush();
        String line = scanner.hasNextLine() ? scanner.nextLine().trim() : "";
        return line.isEmpty() ? effectiveDefault : line;
    }
}
