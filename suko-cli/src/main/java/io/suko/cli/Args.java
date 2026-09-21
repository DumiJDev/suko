package io.suko.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Hand-rolled command-line argument parser (no picocli, no third-party
 * parsing library — see the Global Constraints of the subprojeto 8 plan).
 * There are five commands and a handful of global flags; a real parsing
 * library would be more machinery than the problem needs.
 * <p>
 * Global flags are recognized in <em>any</em> position — before the
 * command, after it, or interleaved with the command's own positional
 * arguments (e.g. {@code suko --yes init} and {@code suko init --yes} are
 * equivalent).
 * </p>
 */
public final class Args {

    /** The five commands the CLI recognizes, in help-listing order. */
    public static final List<String> COMMANDS = List.of("init", "list", "add", "diff", "update");

    public static final String USAGE = """
            Usage: suko <command> [options]

            Commands:
              init     Create this project's suko.json (interactive)
              list     List the components available in the registry
              add      Install components and their dependencies
              diff     Show the difference between a local file and its upstream source
              update   Reapply upstream updates to already-installed components

            Global options:
              --registry <path|url>   Registry base (overrides suko.json)
              --registry-ref <tag>    Registry tag/ref to use
              --source-root <path>    Root folder for .sk files (default: src/main/suko)
              --base-package <pkg>    Java/Suko base package for this project
              --force                 Overwrite files/config that would otherwise be left alone
              --dry-run               Show what would happen without writing anything
              --yes                   Accept defaults without prompting
              --help, -h              Show this help
            """;

    /**
     * Per-command help, shown by {@code suko <command> --help} instead of
     * running the command (brief Step 3: "ambos [Main e Args] com --help
     * por comando"). Keyed by command name; every entry in {@link
     * #COMMANDS} has one, including the three not implemented yet by this
     * task, so {@code suko add --help} explains itself instead of either
     * running the (nonexistent) command or falling back to the generic
     * top-level usage.
     */
    public static final Map<String, String> COMMAND_HELP = Map.of(
            "init", """
                    Usage: suko init [options]

                    Create this project's suko.json (interactive). Every option below has a
                    visible default that is used if you just press enter, except
                    --base-package, which has none and is always asked (guessing it from
                    folder structure would fail silently and only surface much later as a
                    compiler PACKAGE_DIRECTORY_MISMATCH).

                    Options:
                      --source-root <path>    Root folder for .sk files (default: src/main/suko)
                      --base-package <pkg>    Java/Suko base package (no default; always asked)
                      --registry <path|url>   Registry base to record in suko.json
                      --registry-ref <tag>    Registry tag/ref to record in suko.json
                      --force                 Overwrite an existing suko.json
                      --yes                   Accept every other default without prompting (still
                                               requires --base-package to be given)
                      --help, -h              Show this help
                    """,
            "list", """
                    Usage: suko list [options]

                    List the components available in the registry: name, version, category
                    and description, column-aligned.

                    Options:
                      --registry <path|url>   Registry base (overrides suko.json)
                      --registry-ref <tag>    Registry tag/ref to use
                      --help, -h              Show this help
                    """,
            "add", """
                    Usage: suko add <name>... [options]

                    Install one or more components, and the transitive closure of their
                    dependencies, into this project.

                    Not implemented yet.

                    Options:
                      --registry <path|url>   Registry base (overrides suko.json)
                      --registry-ref <tag>    Registry tag/ref to use
                      --force                 Overwrite locally-edited files
                      --dry-run               Show what would happen without writing anything
                      --help, -h              Show this help
                    """,
            "diff", """
                    Usage: suko diff [<name>] [options]

                    Show the difference between a local file and its upstream source
                    (after namespace rewriting), for one component or all installed ones.

                    Not implemented yet.

                    Options:
                      --help, -h              Show this help
                    """,
            "update", """
                    Usage: suko update [<name>] [options]

                    Reapply the reconciliation matrix (spec D7) to one component or all
                    installed ones, updating files that were not edited locally.

                    Not implemented yet.

                    Options:
                      --force                 Overwrite locally-edited files too
                      --dry-run               Show what would happen without writing anything
                      --help, -h              Show this help
                    """);

    private final String command;
    private final List<String> positionals;
    private final String registryBase;
    private final String registryRef;
    private final String sourceRoot;
    private final String basePackage;
    private final boolean force;
    private final boolean dryRun;
    private final boolean yes;
    private final boolean help;

    private Args(String command, List<String> positionals, String registryBase, String registryRef,
            String sourceRoot, String basePackage, boolean force, boolean dryRun, boolean yes, boolean help) {
        this.command = command;
        this.positionals = Collections.unmodifiableList(positionals);
        this.registryBase = registryBase;
        this.registryRef = registryRef;
        this.sourceRoot = sourceRoot;
        this.basePackage = basePackage;
        this.force = force;
        this.dryRun = dryRun;
        this.yes = yes;
        this.help = help;
    }

    public String command() {
        return command;
    }

    public List<String> positionals() {
        return positionals;
    }

    public String registryBase() {
        return registryBase;
    }

    public String registryRef() {
        return registryRef;
    }

    public String sourceRoot() {
        return sourceRoot;
    }

    public String basePackage() {
        return basePackage;
    }

    public boolean force() {
        return force;
    }

    public boolean dryRun() {
        return dryRun;
    }

    public boolean yes() {
        return yes;
    }

    public boolean help() {
        return help;
    }

    /**
     * Parses the raw process arguments.
     *
     * @throws CliException if an unknown command or flag is given, if a
     *                       flag that needs a value does not have one, or
     *                       if no command was given at all (and {@code
     *                       --help} was not requested either)
     */
    public static Args parse(String[] argv) {
        String command = null;
        List<String> positionals = new ArrayList<>();
        String registryBase = null;
        String registryRef = null;
        String sourceRoot = null;
        String basePackage = null;
        boolean force = false;
        boolean dryRun = false;
        boolean yes = false;
        boolean help = false;

        int i = 0;
        while (i < argv.length) {
            String token = argv[i];
            switch (token) {
                case "--help", "-h" -> {
                    help = true;
                    i++;
                }
                case "--force" -> {
                    force = true;
                    i++;
                }
                case "--dry-run" -> {
                    dryRun = true;
                    i++;
                }
                case "--yes" -> {
                    yes = true;
                    i++;
                }
                case "--registry" -> {
                    registryBase = requireValue(argv, i, token, "a registry path or URL", "--registry https://raw.githubusercontent.com/<owner>/suko/<tag>/suko-components/");
                    i += 2;
                }
                case "--registry-ref" -> {
                    registryRef = requireValue(argv, i, token, "a registry tag", "--registry-ref v0.2.0");
                    i += 2;
                }
                case "--source-root" -> {
                    sourceRoot = requireValue(argv, i, token, "a folder path", "--source-root src/main/suko");
                    i += 2;
                }
                case "--base-package" -> {
                    basePackage = requireValue(argv, i, token, "a Java package name", "--base-package com.acme.web");
                    i += 2;
                }
                default -> {
                    if (token.startsWith("--")) {
                        throw new CliException(
                                "Unknown option \"" + token + "\".\n\n" + USAGE);
                    }
                    if (command == null) {
                        command = token;
                    } else {
                        positionals.add(token);
                    }
                    i++;
                }
            }
        }

        if (command == null) {
            if (help) {
                return new Args(null, positionals, registryBase, registryRef, sourceRoot, basePackage,
                        force, dryRun, yes, true);
            }
            throw new CliException("No command given.\n\n" + USAGE);
        }

        if (!COMMANDS.contains(command)) {
            throw new CliException(
                    "Unknown command \"" + command + "\". Valid commands are: "
                            + String.join(", ", COMMANDS) + ".\n\n" + USAGE);
        }

        return new Args(command, positionals, registryBase, registryRef, sourceRoot, basePackage,
                force, dryRun, yes, help);
    }

    private static String requireValue(String[] argv, int i, String flag, String kind, String example) {
        if (i + 1 >= argv.length) {
            throw new CliException(
                    flag + " requires a value (" + kind + "), e.g. " + example);
        }
        return argv[i + 1];
    }
}
