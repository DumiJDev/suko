package io.suko.cli;

import io.suko.registry.ComponentFile;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ReconcilerTest {

    private static final String TARGET = "com/acme/web/ui/Field.sk";

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    // --- The six rows of the reconciliation matrix (spec D7) ---

    @Test
    void diskMatchesLocalAndUpstreamUnchangedIsNoOp() {
        byte[] disk = bytes("package com.acme.web.ui;\n<div>field</div>\n");
        String localHash = Hashes.sha256OfNormalized(disk);
        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, "upstream-v1", localHash);
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v1");

        Reconciler.Action action = Reconciler.classify(Optional.of(disk), Optional.of(lockEntry), manifestFile);

        assertEquals(Reconciler.Action.NO_OP, action);
    }

    @Test
    void diskMatchesLocalButUpstreamChangedOverwritesSilently() {
        byte[] disk = bytes("package com.acme.web.ui;\n<div>field</div>\n");
        String localHash = Hashes.sha256OfNormalized(disk);
        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, "upstream-v1", localHash);
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v2");

        Reconciler.Action action = Reconciler.classify(Optional.of(disk), Optional.of(lockEntry), manifestFile);

        assertEquals(Reconciler.Action.OVERWRITE, action);
    }

    @Test
    void diskEditedLocallyAndUpstreamUnchangedIsLeftUntouched() {
        byte[] disk = bytes("package com.acme.web.ui;\n<div>edited by consumer</div>\n");
        String localHashAtInstallTime = Hashes.sha256OfNormalized(bytes("package com.acme.web.ui;\n<div>field</div>\n"));
        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, "upstream-v1", localHashAtInstallTime);
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v1");

        Reconciler.Action action = Reconciler.classify(Optional.of(disk), Optional.of(lockEntry), manifestFile);

        assertEquals(Reconciler.Action.KEEP_LOCAL_EDIT, action);
    }

    @Test
    void diskEditedLocallyAndUpstreamChangedIsAConflict() {
        byte[] disk = bytes("package com.acme.web.ui;\n<div>edited by consumer</div>\n");
        String localHashAtInstallTime = Hashes.sha256OfNormalized(bytes("package com.acme.web.ui;\n<div>field</div>\n"));
        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, "upstream-v1", localHashAtInstallTime);
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v2");

        Reconciler.Action action = Reconciler.classify(Optional.of(disk), Optional.of(lockEntry), manifestFile);

        assertEquals(Reconciler.Action.CONFLICT, action);
    }

    @Test
    void fileMissingFromDiskIsReinstalledRegardlessOfLockEntry() {
        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, "upstream-v1", "some-local-hash");
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v1");

        Reconciler.Action action = Reconciler.classify(Optional.empty(), Optional.of(lockEntry), manifestFile);

        assertEquals(Reconciler.Action.REINSTALL, action);
    }

    @Test
    void fileMissingFromDiskAndNeverInstalledBeforeIsAlsoReinstall() {
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v1");

        Reconciler.Action action = Reconciler.classify(Optional.empty(), Optional.empty(), manifestFile);

        assertEquals(Reconciler.Action.REINSTALL, action);
    }

    @Test
    void fileExistsOnDiskWithNoLockEntryIsConsumerOwnedAndRefused() {
        byte[] disk = bytes("this file was never written by the CLI");
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, "upstream-v1");

        Reconciler.Action action = Reconciler.classify(Optional.of(disk), Optional.empty(), manifestFile);

        assertEquals(Reconciler.Action.REFUSE_UNOWNED, action);
    }

    // --- The expensive failure mode this task exists to prevent ---

    /**
     * If someone ever "simplifies" the two hashes into one and starts
     * comparing the manifest's hash straight against the disk, this test
     * goes red: the manifest's {@code sha256} is always of the
     * <strong>pre-rewrite</strong> file, while the disk holds the
     * <strong>post-rewrite</strong> bytes, so they never match for a real
     * component — and a naive single-hash comparison would misclassify
     * every freshly installed file as "edited locally".
     */
    @Test
    void manifestHashIsNeverComparedAgainstDiskBytesAfterNamespaceRewrite() {
        byte[] upstreamBytes = bytes(
                "package com.suko.registry.ui;\nimport com.suko.registry.ui.Label;\n<div>field</div>\n");
        String upstreamSha256 = Hashes.sha256OfNormalized(upstreamBytes);

        byte[] rewrittenBytes = NamespaceRewriter.rewrite(
                upstreamBytes, "Field.sk", "com.suko.registry.ui", "com.acme.web.ui");
        String localSha256 = Hashes.sha256OfNormalized(rewrittenBytes);

        // The failure mode this test closes: the two hashes must differ,
        // because the bytes they were computed from differ (namespace
        // rewriting changed the package/import lines).
        assertNotEquals(upstreamSha256, localSha256,
                "upstream (pre-rewrite) and local (post-rewrite) hashes must differ after a real rewrite; "
                        + "if they are equal, something stopped rewriting the namespace, or someone collapsed "
                        + "the two hash fields into one upstream of this test");

        LockEntry.FileEntry lockEntry = new LockEntry.FileEntry(TARGET, upstreamSha256, localSha256);
        ComponentFile manifestFile = new ComponentFile("Field.sk", TARGET, upstreamSha256);

        Reconciler.Action action = Reconciler.classify(Optional.of(rewrittenBytes), Optional.of(lockEntry), manifestFile);

        // Correctly classified as untouched because rewrittenBytes hashes
        // to localSha256 — never because it happens to hash to
        // upstreamSha256, which it must not.
        assertEquals(Reconciler.Action.NO_OP, action);
    }
}
