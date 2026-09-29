package io.suko.lang.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import io.suko.lang.ast.Cardinality;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexTest {

    @Test
    void indexesPublicAndPrivateComponentsAcrossPackages(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="${href}">link</a>
            }
            """);
        Files.writeString(sourceRoot.resolve("Home.sk"), """
            component Home() {
              <div>x</div>
            }
            """);

        ProjectIndex index = ProjectIndex.build(sourceRoot);

        ProjectIndexEntry navLink = index.resolveQualified("ui.NavLink").orElseThrow();
        assertEquals("NavLink", navLink.simpleName());
        assertTrue(navLink.isPublic());
        assertEquals(1, navLink.paramCount());
        assertEquals(uiDir.resolve("NavLink.sk"), navLink.sourceFile());

        assertTrue(index.resolveQualified("Home").isPresent(), "componente sem package fica no pacote raiz");
        assertTrue(index.resolveQualified("ui.DoesNotExist").isEmpty());
    }

    @Test
    void entryExposesParametersAndSpans(@TempDir Path sourceRoot) throws IOException {
        String source = """
            public component Card(String title, Component header, List<Component> items,
                                  Function<String, Component> row, Component footer = null, int size = 3) {
              <div>x</div>
            }
            """;
        Files.writeString(sourceRoot.resolve("Card.sk"), source);

        ProjectIndexEntry card = ProjectIndex.build(sourceRoot).resolveQualified("Card").orElseThrow();

        var params = card.params();
        assertEquals(6, card.paramCount());
        assertParam(params.get(0), "title", "String", false, Optional.empty(), Optional.empty());
        assertParam(params.get(1), "header", "Component", true, Optional.of(Cardinality.ONE), Optional.empty());
        assertParam(params.get(2), "items", "List<Component>", true, Optional.of(Cardinality.MANY), Optional.empty());
        assertParam(params.get(3), "row", "Function<String, Component>", true, Optional.of(Cardinality.ONE), Optional.empty());
        assertParam(params.get(4), "footer", "Component", true, Optional.of(Cardinality.ONE), Optional.of("null"));
        assertParam(params.get(5), "size", "int", false, Optional.empty(), Optional.of("3"));
        assertTrue(params.get(1).requiredSlot());
        assertFalse(params.get(4).requiredSlot(), "slot com default não é obrigatório");
        assertFalse(params.get(0).requiredSlot());

        assertEquals("Card", source.substring(card.nameSpan().startIndex(), card.nameSpan().endIndex() + 1));
        assertTrue(source.substring(card.declarationSpan().startIndex(), card.declarationSpan().endIndex() + 1)
            .startsWith("public component Card"));
    }

    private static void assertParam(ParamInfo p, String name, String type, boolean slot,
                                    Optional<Cardinality> cardinality, Optional<String> defaultText) {
        assertEquals(name, p.name());
        assertEquals(type, p.type());
        assertEquals(slot, p.slot());
        assertEquals(cardinality, p.cardinality());
        assertEquals(defaultText, p.defaultText());
    }

    @Test
    void resolveImportsBuildsShortNameAndAliasMap(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            public component Badge(String text) {
              <span>${text}</span>
            }
            """);

        ProjectIndex index = ProjectIndex.build(sourceRoot);

        io.suko.lang.ast.ImportDecl aliased = new io.suko.lang.ast.ImportDecl(
            "ui.Badge", java.util.Optional.of("Pill"), new io.suko.lang.ast.SourceSpan(0, 0, 0, 0));

        Map<String, ProjectIndexEntry> resolved = index.resolveImports(java.util.List.of(aliased));

        assertEquals("ui.Badge", resolved.get("Pill").qualifiedName());
        assertNull(resolved.get("Badge"), "sem alias explícito, a chave é sempre o alias — não o nome curto também");

        // Revisão final, achado H: o caminho SEM alias
        // (imp.alias().orElse(entry.simpleName())) não estava coberto.
        io.suko.lang.ast.ImportDecl unaliased = new io.suko.lang.ast.ImportDecl(
            "ui.Badge", java.util.Optional.empty(), new io.suko.lang.ast.SourceSpan(0, 0, 0, 0));

        Map<String, ProjectIndexEntry> resolvedUnaliased = index.resolveImports(java.util.List.of(unaliased));

        assertEquals("ui.Badge", resolvedUnaliased.get("Badge").qualifiedName(),
            "sem alias, a chave é o nome simples do componente");
        assertNull(resolvedUnaliased.get("Pill"));
    }

    @Test
    void packageToRelativeDirMapsDotsToPathSegments() {
        assertEquals(Path.of("foo", "bar"), ProjectIndex.packageToRelativeDir("foo.bar"));
        assertEquals(Path.of("ui"), ProjectIndex.packageToRelativeDir("ui"));
    }
}
