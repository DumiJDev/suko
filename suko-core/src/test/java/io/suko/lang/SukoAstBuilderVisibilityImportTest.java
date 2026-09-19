package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SukoAstBuilderVisibilityImportTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void readsPublicModifierAndDefaultsToFalse() {
        SukoFile file = parse("""
            public component NavLink() {
              <a>x</a>
            }
            component Helper() {
              <span>x</span>
            }
            """);

        ComponentDecl navLink = file.components().get(0);
        ComponentDecl helper = file.components().get(1);

        assertTrue(navLink.isPublic(), "NavLink foi declarado public");
        assertFalse(helper.isPublic(), "Helper não tem modificador — devia ser file-private por omissão");
    }

    @Test
    void readsImportsWithAndWithoutAlias() {
        SukoFile file = parse("""
            package app;

            import ui.NavLink;
            import ui.Badge as Pill;

            component Home() {
              <div>x</div>
            }
            """);

        assertEquals("app", file.packageName().orElseThrow());
        assertEquals(2, file.imports().size());

        ImportDecl first = file.imports().get(0);
        assertEquals("ui.NavLink", first.qualifiedName());
        assertTrue(first.alias().isEmpty());

        ImportDecl second = file.imports().get(1);
        assertEquals("ui.Badge", second.qualifiedName());
        assertEquals("Pill", second.alias().orElseThrow());
    }
}
