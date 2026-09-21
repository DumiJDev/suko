package io.suko.cli;

import io.suko.registry.ComponentFile;

import java.util.Optional;

/**
 * The reconciliation matrix of spec D7, used by {@code suko update} and by
 * {@code suko add} on a component that is already installed.
 * <p>
 * {@link #classify} is a <strong>pure function</strong>: it takes the bytes
 * found on disk (or their absence), the file's existing {@link
 * LockEntry.FileEntry} in the lockfile (or its absence), and the {@link
 * ComponentFile} that describes this file in the freshly fetched manifest,
 * and returns one of the six {@link Action} outcomes. No I/O happens in
 * this class — that is deliberate, so the six cases of the matrix can each
 * be exercised by a single unit test with no temp directories or file
 * trees involved.
 * </p>
 * <p>
 * <strong>Never compares {@code manifestFile.sha256()} against the disk.</strong>
 * The manifest's hash is of the file <em>before</em> namespace rewriting; the
 * bytes on disk are <em>after</em> rewriting. Those two are never equal for
 * a real component (the {@code package}/{@code import} lines differ), so
 * comparing them directly would misclassify every single installed file as
 * "edited locally". The only hash ever compared against the disk is
 * {@code lockEntry.localSha256()} — see {@code Reconciler}'s own tests, in
 * particular {@code ReconcilerTest#neverComparesManifestHashAgainstDisk}.
 * </p>
 */
public final class Reconciler {

    private Reconciler() {
    }

    /** One of the six outcomes of the reconciliation matrix (spec D7). */
    public enum Action {
        /** Disk matches what was last written, and upstream has not changed. Nothing to do. */
        NO_OP,
        /** Disk matches what was last written, but upstream changed. Safe to overwrite: nothing local would be lost. */
        OVERWRITE,
        /** Disk was edited locally and upstream has not changed. Leave it alone; report "edited locally". */
        KEEP_LOCAL_EDIT,
        /** Disk was edited locally AND upstream changed. Refuse; suggest {@code suko diff}; require {@code --force}. */
        CONFLICT,
        /** The file does not exist on disk (deleted by the user, or a fresh install). (Re)install it. */
        REINSTALL,
        /** The file exists on disk but has no lockfile entry: consumer-owned. Refuse without {@code --force}. */
        REFUSE_UNOWNED
    }

    /**
     * Classifies one file against the reconciliation matrix.
     *
     * @param diskBytes    the file's current bytes, or {@link Optional#empty()}
     *                     if it does not exist on disk
     * @param lockEntry    this file's existing entry in the lockfile, or
     *                     {@link Optional#empty()} if the lockfile has none
     *                     for this target (never installed before, or an
     *                     unrelated file the CLI does not own)
     * @param manifestFile the file's description in the freshly fetched
     *                     manifest — used only for
     *                     {@code manifestFile.sha256()}, compared against
     *                     {@code lockEntry.upstreamSha256()} (never against
     *                     the disk)
     */
    public static Action classify(Optional<byte[]> diskBytes, Optional<LockEntry.FileEntry> lockEntry,
            ComponentFile manifestFile) {
        if (diskBytes.isEmpty()) {
            return Action.REINSTALL;
        }
        if (lockEntry.isEmpty()) {
            return Action.REFUSE_UNOWNED;
        }

        String diskHash = Hashes.sha256OfNormalized(diskBytes.get());
        boolean matchesLocal = diskHash.equals(lockEntry.get().localSha256());
        boolean upstreamChanged = !manifestFile.sha256().equals(lockEntry.get().upstreamSha256());

        if (matchesLocal) {
            return upstreamChanged ? Action.OVERWRITE : Action.NO_OP;
        }
        return upstreamChanged ? Action.CONFLICT : Action.KEEP_LOCAL_EDIT;
    }
}
