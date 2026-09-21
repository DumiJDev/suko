package io.suko.cli;

/**
 * The CLI's own version, used by {@code suko init} as the default registry
 * tag/ref (spec D5: "a tag por omissão do registry deriva da versão da
 * CLI").
 * <p>
 * Read from the {@code Implementation-Version} entry of this class's own
 * jar manifest ({@link Package#getImplementationVersion()}), which Task 13
 * sets on both {@code tasks.jar} and the fat jar (see
 * {@code suko-cli/build.gradle.kts}, following the same
 * {@code attributes(...)} pattern already used by {@code suko-maven-plugin}).
 * </p>
 * <p>
 * When the CLI is <strong>not</strong> running from a jar at all — e.g. in
 * a test, or from an IDE's exploded classes directory — there is no
 * manifest to read, and {@link Package#getImplementationVersion()} returns
 * {@code null}. Rather than silently defaulting to a plausible-looking but
 * wrong tag (which a caller could easily mistake for a real release and
 * write into a committed {@code suko.json}), {@link #current()} falls back
 * to {@link #FALLBACK} and {@link #isFallback()} reports that the fallback
 * is in effect, so callers can say so out loud instead of staying silent
 * about it.
 * </p>
 */
public final class Version {

    /**
     * Used only when no {@code Implementation-Version} manifest entry is
     * present (i.e. not running from a built jar). Deliberately not a
     * value that could pass for a real release tag.
     */
    public static final String FALLBACK = "0.0.0-unreleased";

    private static final String VALUE;
    private static final boolean FALLBACK_IN_EFFECT;

    static {
        String implementationVersion = Version.class.getPackage().getImplementationVersion();
        if (implementationVersion != null && !implementationVersion.isBlank()) {
            VALUE = implementationVersion;
            FALLBACK_IN_EFFECT = false;
        } else {
            VALUE = FALLBACK;
            FALLBACK_IN_EFFECT = true;
        }
    }

    private Version() {
    }

    /**
     * The CLI's own version: the jar's {@code Implementation-Version} when
     * running from a built jar, or {@link #FALLBACK} otherwise.
     */
    public static String current() {
        return VALUE;
    }

    /**
     * True when {@link #current()} is returning {@link #FALLBACK} because
     * no jar manifest was available to read a real version from.
     */
    public static boolean isFallback() {
        return FALLBACK_IN_EFFECT;
    }
}
