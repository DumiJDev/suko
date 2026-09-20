package io.suko.registry;

/**
 * Represents a file reference in a component manifest.
 * <p>
 * IMPORTANT: Both {@code path} and {@code target} are <strong>always relative</strong>.
 * {@code path} is relative to the base of the registry (URL or directory);
 * {@code target} is relative to the destination package in the consuming project.
 * An absolute path or a complete URL here breaks the local fallback mechanism in D1.
 * </p>
 */
public record ComponentFile(String path, String target, String sha256) { }
