package io.suko.ext;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityOptionsTest {

    @Test
    void defaultsAreTheSpecAllowlist() {
        SecurityOptions o = SecurityOptions.DEFAULT;
        assertEquals(Set.of("http", "https", "mailto", "tel"), o.urlSchemes());
        assertTrue(o.imageDataTypes().isEmpty());
        assertFalse(o.strictCsp());
        assertEquals("io.suko.generated", o.generatedPackage());
    }

    @Test
    void urlAttributesCannotOverlapSinkAttributes() {
        for (String bad : new String[] {"style", "srcdoc", "onclick", "ONERROR", "x-data", "hx-on", "hx-on:click",
                                        "data-x-html", "data-hx-on-click", ":href", "@click"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecurityOptions.DEFAULT.withUrlAttributes(Set.of("data-url", bad)), bad);
            assertTrue(e.getMessage().toLowerCase().contains(bad.toLowerCase()), e.getMessage());
        }
        assertThrows(IllegalArgumentException.class,
            () -> SecurityOptions.DEFAULT.withCodeAttributes(Set.of("meu-attr")).withUrlAttributes(Set.of("MEU-ATTR")));
        assertThrows(IllegalArgumentException.class,
            () -> SecurityOptions.DEFAULT.withUrlAttributes(Set.of("meu-attr")).withCodeAttributes(Set.of("meu-attr")));
        assertEquals(Set.of("data-url", "hx-get"),
            SecurityOptions.DEFAULT.withUrlAttributes(Set.of("data-url", "hx-get")).urlAttributes());
    }

    @Test
    void forbiddenSchemesAreABuildError() {
        for (String bad : new String[] {"javascript", "VBScript", "data", "blob", "filesystem"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecurityOptions.DEFAULT.withUrlSchemes(Set.of("https", bad)));
            assertTrue(e.getMessage().toLowerCase().contains(bad.toLowerCase()), e.getMessage());
        }
    }

    @Test
    void schemesAreNormalisedToLowerCaseAndSorted() {
        SecurityOptions o = SecurityOptions.DEFAULT.withUrlSchemes(Set.of("HTTPS", "Mailto"));
        assertEquals(java.util.List.of("https", "mailto"), java.util.List.copyOf(o.urlSchemes()));
    }

    @Test
    void imageDataTypesAreLimitedToRasterFormats() {
        assertEquals(Set.of("png", "webp"),
            SecurityOptions.DEFAULT.withImageDataTypes(Set.of("png", "webp")).imageDataTypes());
        assertThrows(IllegalArgumentException.class,
            () -> SecurityOptions.DEFAULT.withImageDataTypes(Set.of("svg+xml")));
    }

    @Test
    void schemesMustBeValidUrlSchemeSyntax() {
        for (String bad : new String[] {"1abc", "a b", "a\"b", "a;b", "", "-x", "h\u00e9"}) {
            assertThrows(IllegalArgumentException.class,
                () -> SecurityOptions.DEFAULT.withUrlSchemes(Set.of("https", bad)), bad);
        }
        assertEquals(Set.of("git+ssh", "x-y.z1"), SecurityOptions.DEFAULT.withUrlSchemes(Set.of("GIT+SSH", "x-y.z1")).urlSchemes());
    }

    @Test
    void generatedPackageMustBeAJavaPackage() {
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("a-b.c"));
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("1abc"));
        assertEquals("com.acme.shop", SecurityOptions.DEFAULT.withGeneratedPackage("com.acme.shop").generatedPackage());
    }

    @Test
    void sanitizePackageSegment() {
        assertEquals("suko_shop", SecurityOptions.sanitizePackageSegment("suko-shop"));
        assertEquals("_9lives", SecurityOptions.sanitizePackageSegment("9lives"));
        assertEquals("_project", SecurityOptions.sanitizePackageSegment(""));
        assertEquals("_class", SecurityOptions.sanitizePackageSegment("class"));
        assertEquals("_default", SecurityOptions.sanitizePackageSegment("default"));
        assertEquals("classes", SecurityOptions.sanitizePackageSegment("classes"));
    }

    @Test
    void keywordPackageSegmentsAreRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SecurityOptions.DEFAULT.withGeneratedPackage("com.acme.default.ui"));
        assertTrue(e.getMessage().contains("default"), e.getMessage());
    }

    @Test
    void attributeListsAreLowerCased() {
        SecurityOptions o = SecurityOptions.DEFAULT.withCodeAttributes(Set.of("Data-Eval")).withUrlAttributes(Set.of("Data-Href"));
        assertEquals(Set.of("data-eval"), o.codeAttributes());
        assertEquals(Set.of("data-href"), o.urlAttributes());
    }

    @Test
    void emptyPackageSegmentSanitizesToAValidIdentifier() {
        assertEquals("_project", SecurityOptions.sanitizePackageSegment(""));
        assertEquals("_project", SecurityOptions.sanitizePackageSegment(null));
        assertEquals("_project", SecurityOptions.sanitizePackageSegment("_"));
        assertEquals("_1a", SecurityOptions.sanitizePackageSegment("1a"));
    }

    @Test
    void underscoreOnlyPackageSegmentIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("com._"));
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("_"));
        assertEquals("a._b", SecurityOptions.DEFAULT.withGeneratedPackage("a._b").generatedPackage());
    }
}
