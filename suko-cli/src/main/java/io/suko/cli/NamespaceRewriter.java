package io.suko.cli;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the {@code package}/{@code import} lines of a {@code .sk} source
 * file from one base package to another, byte for byte otherwise.
 * <p>
 * This is deliberately <b>not</b> a Suko parser: {@code suko-cli} does not
 * depend on {@code suko-core} (see the module's {@code build.gradle.kts}),
 * so there is no grammar available here. Instead this class is anchored
 * line by line: a line is only touched if, once its leading/trailing
 * whitespace is stripped away, it is exactly a {@code package} or
 * {@code import} declaration whose target starts — literally, as a dotted
 * prefix — with {@code fromBasePackage}. Every other line, including one
 * that merely contains the substring {@code fromBasePackage} inside HTML
 * text or an attribute value, passes through completely unread.
 * </p>
 * <p>
 * This is, per the subprojeto 8 plan, "the most dangerous task in the
 * plan": a naive global string replace would silently corrupt HTML text
 * and attribute values that happen to contain the old package name as a
 * substring — the file would still compile and render, just wrong. The
 * line-anchoring above is what rules that failure mode out.
 * </p>
 */
public final class NamespaceRewriter {

    private NamespaceRewriter() {
    }

    /** Placeholder file name used by the 3-arg overload, which has none to report. */
    private static final String NO_FILE_NAME = "<source>";

    // Anchors a whole line (after line-splitting, so no \r or \n ever
    // reaches this pattern) as:
    //   (leading ws)(package|import)(ws+)(dotted Java identifier)(ws*)(;...)
    // Group 4, the target, is required to be a syntactically plausible
    // dotted package/type reference — anything else (a wildcard import, a
    // line that merely starts with the word "package" inside some other
    // construct, ...) simply fails to match and is left untouched.
    private static final Pattern PACKAGE_OR_IMPORT = Pattern.compile(
            "^(\\s*)(package|import)(\\s+)"
                    + "([A-Za-z_$][A-Za-z0-9_$]*(?:\\.[A-Za-z_$][A-Za-z0-9_$]*)*)"
                    + "(\\s*)(;.*)$");

    private static final Pattern LINE_TERMINATOR = Pattern.compile("\r\n|\r|\n");

    /**
     * Rewrites {@code source} in place, without a file name to report in
     * error messages. Prefer {@link #rewrite(byte[], String, String, String)}
     * whenever a file name is available so that an abort names the
     * offending file, as required by the plan.
     */
    public static byte[] rewrite(byte[] source, String fromBasePackage, String toBasePackage) {
        return rewrite(source, NO_FILE_NAME, fromBasePackage, toBasePackage);
    }

    /**
     * Rewrites {@code source}, a {@code .sk} file's raw bytes, replacing
     * the {@code fromBasePackage} prefix of its {@code package} line and of
     * any {@code import} lines that share that prefix with
     * {@code toBasePackage}. Every other byte is preserved, including
     * occurrences of {@code fromBasePackage} inside HTML text or attribute
     * values.
     *
     * @param fileName used only to name the offending file in the
     *                  {@link CliException} thrown when the file's
     *                  {@code package} line does not start with
     *                  {@code fromBasePackage}
     * @throws CliException if {@code toBasePackage} is not a valid
     *                       dot-separated sequence of Java identifiers, or
     *                       if the file's {@code package} line does not
     *                       start with {@code fromBasePackage} (this file
     *                       is refused rather than rewritten "best effort")
     */
    public static byte[] rewrite(byte[] source, String fileName, String fromBasePackage, String toBasePackage) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(fromBasePackage, "fromBasePackage");
        Objects.requireNonNull(toBasePackage, "toBasePackage");

        // The resulting package (every touched line ends up starting with
        // toBasePackage) is validated once, up front, rather than
        // per-line: reject before touching a single byte.
        ProjectConfig.validateBasePackage(toBasePackage);

        String text = new String(source, StandardCharsets.UTF_8);
        // -1 limit keeps a trailing empty element when the file ends with
        // a line terminator, which is how we tell "ends with newline"
        // apart from "does not" when reassembling below.
        String[] lines = LINE_TERMINATOR.split(text, -1);

        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            boolean isLast = i == lines.length - 1;
            out.append(rewriteLine(lines[i], fileName, fromBasePackage, toBasePackage));
            if (!isLast) {
                // D8: entrada com LF sai com LF; entrada com CRLF sai com
                // LF — always LF, never re-derived from the input's own
                // terminator.
                out.append('\n');
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String rewriteLine(String line, String fileName, String fromBasePackage, String toBasePackage) {
        Matcher m = PACKAGE_OR_IMPORT.matcher(line);
        if (!m.matches()) {
            return line;
        }
        String keyword = m.group(2);
        String target = m.group(4);

        if (startsWithBasePackage(target, fromBasePackage)) {
            String suffix = target.substring(fromBasePackage.length());
            String rewrittenTarget = toBasePackage + suffix;
            return m.group(1) + keyword + m.group(3) + rewrittenTarget + m.group(5) + m.group(6);
        }

        if ("package".equals(keyword)) {
            throw new CliException(
                    "File \"" + fileName + "\": its package declaration \"" + line.strip()
                            + "\" does not start with the expected base package \"" + fromBasePackage
                            + "\" — refusing to rewrite a file that may not belong to this base package.");
        }

        // An import outside fromBasePackage (case C6, e.g. java.util.List)
        // is left exactly as-is.
        return line;
    }

    private static boolean startsWithBasePackage(String target, String basePackage) {
        return target.equals(basePackage) || target.startsWith(basePackage + ".");
    }
}
