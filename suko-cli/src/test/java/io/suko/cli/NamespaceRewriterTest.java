package io.suko.cli;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NamespaceRewriterTest {

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    @Test
    void rewritesPackageDeclaration() {
        byte[] source = utf8("package io.suko.ui;\n");

        byte[] result = NamespaceRewriter.rewrite(source, "io.suko", "com.acme.web");

        assertEquals("package com.acme.web.ui;\n", utf8(result));
    }

    @Test
    void rewritesImportDeclaration() {
        byte[] source = utf8("import io.suko.ui.Label;\n");

        byte[] result = NamespaceRewriter.rewrite(source, "io.suko", "com.acme.web");

        assertEquals("import com.acme.web.ui.Label;\n", utf8(result));
    }

    @Test
    void restOfFileIsByteForByteIdentical() {
        String source =
                "package io.suko.ui;\n"
                        + "\n"
                        + "import io.suko.ui.Input;\n"
                        + "import io.suko.ui.Label;\n"
                        + "\n"
                        + "public component Field(String id) {\n"
                        + "  <div class=\"space-y-1\">\n"
                        + "    Label(text = id)\n"
                        + "  </div>\n"
                        + "}\n";

        byte[] result = NamespaceRewriter.rewrite(utf8(source), "io.suko", "com.acme.web");

        String expected =
                "package com.acme.web.ui;\n"
                        + "\n"
                        + "import com.acme.web.ui.Input;\n"
                        + "import com.acme.web.ui.Label;\n"
                        + "\n"
                        + "public component Field(String id) {\n"
                        + "  <div class=\"space-y-1\">\n"
                        + "    Label(text = id)\n"
                        + "  </div>\n"
                        + "}\n";
        assertArrayEquals(utf8(expected), result);
    }

    @Test
    void doesNotTouchOldPackageNameInsideHtmlTextOrAttribute() throws IOException {
        Path fixture = Path.of("src/test/resources/rewrite-fixtures/html-noise.sk");
        byte[] source = Files.readAllBytes(fixture);

        byte[] result = NamespaceRewriter.rewrite(source, "io.suko", "com.acme.web");
        String rewritten = utf8(result);

        assertTrue(rewritten.contains("package com.acme.web.ui;"),
                "package line must be rewritten:\n" + rewritten);
        assertTrue(rewritten.contains("import com.acme.web.ui.Label;"),
                "import line must be rewritten:\n" + rewritten);
        assertTrue(rewritten.contains("data-note=\"io.suko.ui is not a namespace here, just text\""),
                "attribute value must be untouched:\n" + rewritten);
        assertTrue(rewritten.contains("io.suko.ui also appears here, as plain HTML text, untouched"),
                "HTML text must be untouched:\n" + rewritten);
    }

    @Test
    void leavesImportOutsideBasePackageIntact() {
        String source =
                "package io.suko.ui;\n"
                        + "import java.util.List;\n";

        byte[] result = NamespaceRewriter.rewrite(utf8(source), "io.suko", "com.acme.web");

        assertEquals(
                "package com.acme.web.ui;\n"
                        + "import java.util.List;\n",
                utf8(result));
    }

    @Test
    void preservesIndentationAndSpacingAroundDeclarations() {
        String source =
                "  package   io.suko.ui   ;  \n"
                        + "\t\timport\tio.suko.ui.Label;\n";

        byte[] result = NamespaceRewriter.rewrite(utf8(source), "io.suko", "com.acme.web");

        assertEquals(
                "  package   com.acme.web.ui   ;  \n"
                        + "\t\timport\tcom.acme.web.ui.Label;\n",
                utf8(result));
    }

    @Test
    void abortsWithMessageNamingTheFileWhenPackageDoesNotMatchFromBasePackage() {
        byte[] source = utf8("package com.other.thing;\n");

        CliException ex = assertThrows(CliException.class,
                () -> NamespaceRewriter.rewrite(source, "Weird.sk", "io.suko", "com.acme.web"));

        assertTrue(ex.getMessage().contains("Weird.sk"),
                "message must name the offending file: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("com.other.thing"),
                "message must show the offending package line: " + ex.getMessage());
    }

    @Test
    void rejectsInvalidToBasePackageReservedWordSegment() {
        byte[] source = utf8("package io.suko.ui;\n");

        assertThrows(CliException.class,
                () -> NamespaceRewriter.rewrite(source, "io.suko", "com.class.web"));
    }

    @Test
    void rejectsInvalidToBasePackageSegmentStartingWithDigit() {
        byte[] source = utf8("package io.suko.ui;\n");

        assertThrows(CliException.class,
                () -> NamespaceRewriter.rewrite(source, "io.suko", "com.9acme.web"));
    }

    @Test
    void rejectsInvalidToBasePackageEmptyString() {
        byte[] source = utf8("package io.suko.ui;\n");

        assertThrows(CliException.class,
                () -> NamespaceRewriter.rewrite(source, "io.suko", ""));
    }

    @Test
    void rejectsInvalidToBasePackageDoubleDot() {
        byte[] source = utf8("package io.suko.ui;\n");

        assertThrows(CliException.class,
                () -> NamespaceRewriter.rewrite(source, "io.suko", "com..web"));
    }

    @Test
    void lfInputStaysLf() {
        byte[] source = utf8("package io.suko.ui;\n\npublic component X() {\n}\n");

        byte[] result = NamespaceRewriter.rewrite(source, "io.suko", "com.acme.web");

        assertEquals("package com.acme.web.ui;\n\npublic component X() {\n}\n", utf8(result));
        assertTrue(!utf8(result).contains("\r"), "must not contain any CR");
    }

    @Test
    void crlfInputComesOutLf() {
        byte[] source = utf8("package io.suko.ui;\r\n\r\npublic component X() {\r\n}\r\n");

        byte[] result = NamespaceRewriter.rewrite(source, "io.suko", "com.acme.web");

        assertEquals("package com.acme.web.ui;\n\npublic component X() {\n}\n", utf8(result));
    }

    // --- Step 4: proof over the real content ------------------------------

    @Test
    void rewritesTheEightRealComponentsChangingOnlyPackageAndImportLines() throws IOException {
        Path componentsDir = Path.of("../suko-components/src/main/suko/io/suko/ui");
        assertTrue(Files.isDirectory(componentsDir),
                "expected suko-components sources at " + componentsDir.toAbsolutePath());

        List<Path> skFiles;
        try (Stream<Path> stream = Files.list(componentsDir)) {
            skFiles = stream.filter(p -> p.toString().endsWith(".sk")).sorted().toList();
        }
        assertEquals(8, skFiles.size(), "expected exactly 8 real .sk components, found: " + skFiles);

        for (Path file : skFiles) {
            byte[] original = Files.readAllBytes(file);
            byte[] rewritten = NamespaceRewriter.rewrite(original, file.getFileName().toString(),
                    "io.suko", "com.acme.web");

            List<String> originalLines = splitLines(utf8(original));
            List<String> rewrittenLines = splitLines(utf8(rewritten));

            assertEquals(originalLines.size(), rewrittenLines.size(),
                    "line count must be unchanged for " + file);

            for (int i = 0; i < originalLines.size(); i++) {
                String o = originalLines.get(i);
                String r = rewrittenLines.get(i);
                if (o.equals(r)) {
                    continue;
                }
                String trimmed = o.strip();
                assertTrue(trimmed.startsWith("package ") || trimmed.startsWith("import "),
                        "unexpected change on a non package/import line in " + file + " line " + (i + 1)
                                + ":\n  original:  " + o + "\n  rewritten: " + r);
            }
        }
    }

    private static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                lines.add(text.substring(start, i));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }
}
