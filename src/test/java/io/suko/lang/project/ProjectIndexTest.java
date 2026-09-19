package io.suko.lang.project;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProjectIndexTest {

    @Test
    void indexesPublicAndPrivateComponentsAcrossPackages(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("NavLink.sk"), """
            package ui;

            public component NavLink(String href) {
              <a href="{href}">link</a>
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
    void resolveImportsBuildsShortNameAndAliasMap(@TempDir Path sourceRoot) throws IOException {
        Path uiDir = sourceRoot.resolve("ui");
        Files.createDirectories(uiDir);
        Files.writeString(uiDir.resolve("Badge.sk"), """
            package ui;

            public component Badge(String text) {
              <span>{text}</span>
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
