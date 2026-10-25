package io.suko.jte.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsTextTest {

    @Test
    void stringLiteralsAreAsciiAndSafeInsideHtml() {
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\\u003c\\u00e9\\u0026\\u0027\"", JsText.string("a\"b\\c\n\u2028<é&'"));
        assertEquals("\"\\ud800x\"", JsText.string("\ud800x"), "surrogate isolado sobrevive");
        assertEquals("\"\\u0000\\u001f\\u007f\"", JsText.string("\u0000\u001f\u007f"));
    }

    @Test
    void reservedAndGlobalNamesAreRenamed() {
        for (String name : new String[] {"arguments", "eval", "await", "yield", "let", "delete", "typeof",
                                         "undefined", "NaN", "window", "document", "Math", "self"}) {
            assertEquals("$_" + name, JsText.identifier(name), name);
        }
        assertEquals("count", JsText.identifier("count"));
        assertEquals("_x", JsText.identifier("_x"));
    }

    @Test
    void javaEscapesFromSukoLiterals() {
        assertEquals("a\n$\"\\ \t", JsText.unescapeJava("a\\n\\u0024\\\"\\\\\\s\\t"));
        assertThrows(IllegalArgumentException.class, () -> JsText.unescapeJava("\\q"));
        assertThrows(IllegalArgumentException.class, () -> JsText.unescapeJava("\\u12"));
    }
}
