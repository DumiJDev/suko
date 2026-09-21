package io.suko.cli;

import io.suko.cli.command.InitCommand;
import io.suko.cli.command.ListCommand;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Path;

/**
 * Entry point. Parses argv via {@link Args}, dispatches to the matching
 * command, and turns any {@link CliException} into a clean message on
 * stderr plus its exit code — never a raw stack trace for a user-caused
 * problem (bad flag, missing config, invalid basePackage, ...).
 */
public final class Main {

    public static void main(String[] argv) {
        int exitCode = run(argv, System.in, System.out, System.err, Path.of("."));
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    /**
     * Testable entry point: takes explicit streams and a project directory
     * instead of touching {@code System.in}/{@code System.out}/{@code
     * System.err}/the JVM's actual working directory, and returns an exit
     * code instead of calling {@code System.exit} (which would kill the
     * test JVM).
     */
    static int run(String[] argv, InputStream in, PrintStream out, PrintStream err, Path projectDir) {
        Args args;
        try {
            args = Args.parse(argv);
        } catch (CliException e) {
            err.println(e.getMessage());
            return e.exitCode();
        }

        if (args.help() && args.command() == null) {
            out.print(Args.USAGE);
            return 0;
        }

        if (args.help()) {
            // Per-command --help short-circuits before any real work: an
            // interactive `suko init --help` must not block on stdin, and
            // `suko list --help` must not touch the registry.
            out.print(Args.COMMAND_HELP.get(args.command()));
            return 0;
        }

        try {
            switch (args.command()) {
                case "init" -> new InitCommand().run(args, in, out, err, projectDir);
                case "list" -> new ListCommand().run(args, out, projectDir);
                case "add", "diff", "update" -> {
                    err.println("`suko " + args.command() + "` is not implemented yet.");
                    return 1;
                }
                default -> throw new IllegalStateException("Args.parse should never return an unknown command: " + args.command());
            }
        } catch (CliException e) {
            err.println(e.getMessage());
            return e.exitCode();
        }
        return 0;
    }
}
