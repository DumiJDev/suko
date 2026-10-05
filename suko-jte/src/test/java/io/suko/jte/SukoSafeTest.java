package io.suko.jte;

import io.suko.ext.SecurityOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SukoSafeTest {

    static final String BLOCKED = "about:invalid#suko-blocked";
    static CompiledSukoSafe safe;

    @BeforeAll
    static void compile() throws Exception {
        safe = new CompiledSukoSafe(SecurityOptions.DEFAULT);
    }

    @AfterAll
    static void close() throws Exception {
        safe.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)", " javascript:alert(1)", "JAVASCRIPT:alert(1)", "JaVaScRiPt:alert(1)",
        "java\tscript:alert(1)", "java\nscript:alert(1)", "\u0001javascript:alert(1)", "\u0000javascript:alert(1)",
        "java\u0000script:alert(1)", "javascript\n:alert(1)", "ｊａｖａｓｃｒｉｐｔ:alert(1)",
        "vbscript:msgbox(1)", "data:text/html,<script>alert(1)</script>", "blob:https://x/abc", "file:///etc/passwd",
        "  \t javascript:alert(1)"
        // "javascript&#58;alert(1)x:y" fica de fora: o '#' precede o ':', logo é URL relativo inofensivo (WHATWG).
    })
    void hostileSchemesAreBlocked(String value) throws Exception {
        assertEquals(BLOCKED, safe.url(value), value);
    }

    @Test
    void entityEncodedSchemeIsInertNotDecoded() throws Exception {
        // Sem ':' real: é um caminho relativo inofensivo; o valor sai intacto (nunca se decodificam entidades).
        assertEquals("javascript&#58;alert(1)", safe.url("javascript&#58;alert(1)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/a?b=c#d", "http://example.com", "mailto:a@b.com", "tel:+244123", "HTTPS://EXAMPLE.COM",
        "/carrinho", "relative/path", "../up", "#frag", "?q=1", "//cdn.example.com/x.js", "/\\evil.example",
        "\\\\evil.example", "https:evil", ""
    })
    void allowedValuesComeBackUnchanged(String value) throws Exception {
        assertEquals(value, safe.url(value));
    }

    @Test
    void nullStaysNullSoTheAttributeIsOmitted() throws Exception {
        assertNull(safe.url(null));
    }

    @Test
    void controlCharactersInsideAnAllowedUrlAreKept() throws Exception {
        String v = "https://example.com/a\tb";
        assertEquals(v, safe.url(v));
    }

    @Test
    void imageDataIsBlockedByDefaultAndAllowedWhenConfigured() throws Exception {
        assertEquals(BLOCKED, safe.call("imageUrl", "data:image/png;base64,AAAA"));
        try (CompiledSukoSafe png = new CompiledSukoSafe(SecurityOptions.DEFAULT.withImageDataTypes(Set.of("png")))) {
            assertEquals("data:image/png;base64,AAAA", png.call("imageUrl", "data:image/png;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:image/svg+xml;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:image/gif;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:text/html,x"));
            assertEquals(BLOCKED, png.url("data:image/png;base64,AAAA")); // url() nunca aceita data:
        }
    }

    @Test
    void customSchemesAreHonoured() throws Exception {
        try (CompiledSukoSafe c = new CompiledSukoSafe(SecurityOptions.DEFAULT.withUrlSchemes(Set.of("https", "sms")))) {
            assertEquals("sms:+1", c.url("sms:+1"));
            assertEquals(BLOCKED, c.url("http://example.com"));
        }
    }

    @Test
    void srcsetKeepsOnlyAllowedCandidates() throws Exception {
        assertEquals("/a.png 1x, https://x.com/b.png 2x",
            safe.call("srcset", "/a.png 1x, javascript:alert(1) 1.5x, https://x.com/b.png 2x"));
        assertEquals("", safe.call("srcset", "data:image/png;base64,AAA 1x"));
        assertEquals("/a.png, /b.png", safe.call("srcset", "/a.png,, /b.png,"));
        assertEquals("/a.png 100w", safe.call("srcset", "/a.png 100w, javascript:x 200w".replace("javascript:x 200w", "javascript:x 200w")));
        assertNull(safe.call("srcset", null));
    }

    @Test
    void srcsetDescriptorsMayContainParenthesesWithCommas() throws Exception {
        assertEquals("/a.png image-set(1x, 2x)", safe.call("srcset", "/a.png image-set(1x, 2x)"));
    }

    @Test
    void pingValidatesEachUrl() throws Exception {
        assertEquals("https://a.example/p /q", safe.call("ping", "https://a.example/p javascript:x /q"));
        assertNull(safe.call("ping", null));
    }

    @Test
    void pathSegmentCannotChangeOriginOrInjectQuery() throws Exception {
        assertEquals("abc-123_x.y~z", safe.call("pathSegment", "abc-123_x.y~z"));
        assertEquals("a%2Fb%3Fc%23d%25e%5Cf", safe.call("pathSegment", "a/b?c#d%e\\f"));
        assertEquals("%0A%00", safe.call("pathSegment", "\n\u0000"));
        assertEquals("%C3%A9", safe.call("pathSegment", "é"));
        assertEquals("", safe.call("pathSegment", ".."));
        assertEquals("", safe.call("pathSegment", "."));
        assertEquals("", safe.call("pathSegment", null));
    }

    @Test
    void cssValueIsAnAllowlist() throws Exception {
        assertEquals("42", safe.call("cssValue", "42"));
        assertEquals("-1.5em", safe.call("cssValue", "-1.5em"));
        assertEquals("#fff", safe.call("cssValue", "#fff"));
        assertEquals("#1A2b3C", safe.call("cssValue", "#1A2b3C"));
        assertEquals("red", safe.call("cssValue", "red"));
        assertEquals("space-between", safe.call("cssValue", "space-between"));
        assertEquals("unset", safe.call("cssValue", "red; background: url(javascript:x)"));
        assertEquals("unset", safe.call("cssValue", "expression(alert(1))"));
        assertEquals("unset", safe.call("cssValue", "url(x)"));
        assertEquals("unset", safe.call("cssValue", "1px solid"));
        assertEquals("unset", safe.call("cssValue", null));
    }

    @Test
    void relAddsNoopenerUnlessOptedOut() throws Exception {
        assertEquals("noopener", safe.call("rel", null));
        assertEquals("noopener", safe.call("rel", "  "));
        assertEquals("nofollow noopener", safe.call("rel", "nofollow"));
        assertEquals("nofollow noopener", safe.call("rel", "nofollow noopener"));
        assertEquals("opener", safe.call("rel", "opener"));
    }
}
