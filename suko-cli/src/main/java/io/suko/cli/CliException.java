package io.suko.cli;

/**
 * A user-facing CLI error: the message is meant to be printed to stderr
 * as-is, never as a stack trace. {@link Main} is the only place that
 * catches this exception at the top level.
 * <p>
 * Every place in this module that reports a problem the user caused
 * (bad flag, missing {@code suko.json}, invalid {@code basePackage}, a
 * registry that could not be read, ...) throws this, with a message that
 * says what went wrong and, whenever there is one, the concrete next
 * step (e.g. "run `suko init`", "pass --base-package <pkg>").
 * </p>
 */
public final class CliException extends RuntimeException {

    /** Conventional non-zero exit code for a usage/config error. */
    public static final int DEFAULT_EXIT_CODE = 2;

    private final int exitCode;

    public CliException(String message) {
        this(message, DEFAULT_EXIT_CODE);
    }

    public CliException(String message, int exitCode) {
        super(message);
        this.exitCode = exitCode;
    }

    public int exitCode() {
        return exitCode;
    }
}
