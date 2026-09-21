package io.suko.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Under {@code gradle test}, {@link Version} runs from the exploded
 * {@code build/classes} directory, not from a jar — so there is no
 * {@code Implementation-Version} manifest entry to read, and the fallback
 * path (Step 2 of Task 13's brief) is exactly what this test exercises.
 * The "running from the built jar" side of the same behavior is verified
 * separately by {@code NativeImageSmokeTest} (and manually, per the Task
 * 13 report), which actually runs {@code java -jar} against the fat jar
 * and checks that {@code suko init} defaults its registry ref to a real
 * {@code Implementation-Version}, not the fallback.
 */
class VersionTest {

    @Test
    void fallsBackAndSaysSoWhenNotRunningFromABuiltJar() {
        assertTrue(Version.isFallback(), "expected no jar manifest to be visible from the test classpath");
        assertEquals(Version.FALLBACK, Version.current());
        assertFalse(Version.FALLBACK.matches("\\d+\\.\\d+\\.\\d+"),
                "the fallback must not look like a plausible real release tag");
    }
}
