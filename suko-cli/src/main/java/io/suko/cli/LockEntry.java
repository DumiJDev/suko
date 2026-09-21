package io.suko.cli;

import java.util.List;

/**
 * One installed component's entry in {@code suko.lock.json} (spec D7).
 *
 * @param name    the component's registry name
 * @param version the installed version, as recorded in its manifest
 * @param reason  {@link #REASON_DIRECT} if the user explicitly requested
 *                this component, {@link #REASON_TRANSITIVE} if it is only
 *                present because another installed component's
 *                {@code dependsOn} pulled it in. A component installed as
 *                {@code transitive} and later requested explicitly is
 *                upgraded to {@code direct} — this lockfile has no memory
 *                of "used to be transitive".
 * @param files   this component's files as actually written into the
 *                consumer's project; may be empty (a component that
 *                contributes nothing but a {@code dependsOn} edge).
 */
public record LockEntry(String name, String version, String reason, List<FileEntry> files) {

    public static final String REASON_DIRECT = "direct";
    public static final String REASON_TRANSITIVE = "transitive";

    /**
     * One file belonging to a {@link LockEntry}, with its two hashes
     * (spec D7 — never conflate these, see {@link Reconciler}).
     *
     * @param target         path of the written file, relative to the
     *                       consumer project's root (matches
     *                       {@code io.suko.registry.ComponentFile#target()}
     *                       after the source root is prefixed at write time)
     * @param upstreamSha256 the {@code files[].sha256} of the manifest this
     *                       file came from — identifies which upstream
     *                       revision is installed. This is <strong>never</strong>
     *                       compared against the file on disk.
     * @param localSha256    the hash of the bytes actually written to disk,
     *                       i.e. after namespace rewriting and line-ending
     *                       normalization for comparison (spec D8). This is
     *                       the only hash ever compared against the disk.
     */
    public record FileEntry(String target, String upstreamSha256, String localSha256) {
    }
}
