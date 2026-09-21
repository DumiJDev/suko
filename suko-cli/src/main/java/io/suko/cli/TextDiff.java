package io.suko.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A minimal, hand-rolled line diff (no third-party library — see the Global
 * Constraints of the subprojeto 8 plan) between two whole-file texts,
 * used by {@code suko diff} to show what changed between a local file and
 * its upstream source (after namespace rewriting — see the caller, never
 * this class, for that rule).
 * <p>
 * This is deliberately <strong>not</strong> an optimal LCS/Myers diff: it
 * only needs to be readable and deterministic, not minimal. The algorithm
 * is the simplest one that is still correct for the common case (a change
 * confined to one region of the file): strip the common prefix and the
 * common suffix, and report everything left in the middle as wholly
 * removed (from the left side) followed by wholly added (from the right
 * side). A change that moves a line without touching its neighbors will
 * therefore show up as one removal plus one addition rather than "no
 * change" — an LCS diff would do better there, but that is a cosmetic
 * difference, not a correctness one; the plan's own words for this class
 * are "não precisa de ser LCS ótimo".
 * </p>
 */
public final class TextDiff {

    private TextDiff() {
    }

    private static final Pattern LINE_TERMINATOR = Pattern.compile("\r\n|\r|\n");

    /**
     * Diffs {@code left} against {@code right}, both whole-file texts
     * (already decoded), line by line.
     *
     * @return an empty list if {@code left} and {@code right} are the same
     *         text; otherwise a list of lines each prefixed with
     *         {@code "-"} (present only in {@code left}, i.e. removed) or
     *         {@code "+"} (present only in {@code right}, i.e. added), in
     *         that order — every {@code "-"} line before every {@code "+"}
     *         line, matching a classic unified-diff hunk with no context
     *         lines
     */
    public static List<String> diff(String left, String right) {
        if (left.equals(right)) {
            return List.of();
        }

        String[] a = splitLines(left);
        String[] b = splitLines(right);

        int prefix = commonPrefixLength(a, b);
        int suffix = commonSuffixLength(a, b, prefix);

        List<String> result = new ArrayList<>();
        for (int i = prefix; i < a.length - suffix; i++) {
            result.add("-" + a[i]);
        }
        for (int i = prefix; i < b.length - suffix; i++) {
            result.add("+" + b[i]);
        }
        return result;
    }

    private static String[] splitLines(String text) {
        // -1 limit keeps a trailing empty element when the text ends with
        // a line terminator, mirroring NamespaceRewriter's own splitting so
        // "differs only in a trailing newline" is not silently swallowed.
        return LINE_TERMINATOR.split(text, -1);
    }

    private static int commonPrefixLength(String[] a, String[] b) {
        int max = Math.min(a.length, b.length);
        int i = 0;
        while (i < max && a[i].equals(b[i])) {
            i++;
        }
        return i;
    }

    private static int commonSuffixLength(String[] a, String[] b, int prefix) {
        int max = Math.min(a.length, b.length) - prefix;
        int i = 0;
        while (i < max && a[a.length - 1 - i].equals(b[b.length - 1 - i])) {
            i++;
        }
        return i;
    }
}
