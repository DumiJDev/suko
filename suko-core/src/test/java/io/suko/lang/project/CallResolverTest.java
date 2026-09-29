package io.suko.lang.project;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.CallResolver.ImportKind;
import io.suko.lang.project.CallResolver.Resolution;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CallResolverTest {

    private ProjectIndex index;

    @BeforeEach
    void setUp() {
        index = ProjectIndex.build(SukoSources.of(Path.of("/root"), Map.of(
            Path.of("ui/Badge.sk"), "package ui;\npublic component Badge() { <b>b</b> }\n",
            Path.of("ui/Secret.sk"), "package ui;\ncomponent Secret() { <i>s</i> }\n",
            Path.of("other/Badge.sk"), "package other;\npublic component Badge() { <u>b</u> }\n")));
    }

    private CallResolver resolverFor(String source) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(source))));
        SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());
        Map<String, ComponentDecl> local = new LinkedHashMap<>();
        file.components().forEach(c -> local.put(c.name(), c));
        return new CallResolver(local::get, index, file.imports());
    }

    @Test
    void resolvesLocalBeforeAnythingElse() {
        CallResolver resolver = resolverFor("import ui.Badge;\ncomponent Badge() { <p>local</p> }\n");
        assertInstanceOf(Resolution.Local.class, resolver.resolve("Badge"));
    }

    @Test
    void resolvesImportedShortNameAndAlias() {
        CallResolver resolver = resolverFor("import ui.Badge;\nimport other.Badge as OtherBadge;\ncomponent Home() { <p>x</p> }\n");
        assertEquals("ui.Badge", ((Resolution.Project) resolver.resolve("Badge")).entry().qualifiedName());
        assertEquals("other.Badge", ((Resolution.Project) resolver.resolve("OtherBadge")).entry().qualifiedName());
    }

    @Test
    void resolvesQualifiedNamesWithoutImport() {
        CallResolver resolver = resolverFor("component Home() { <p>x</p> }\n");
        assertEquals("ui.Badge", ((Resolution.Project) resolver.resolve("ui.Badge")).entry().qualifiedName());
    }

    @Test
    void privateComponentIsNotVisibleQualifiedAndReportedAtImportWhenImported() {
        CallResolver qualified = resolverFor("component Home() { <p>x</p> }\n");
        Resolution.NotVisible direct = (Resolution.NotVisible) qualified.resolve("ui.Secret");
        assertFalse(direct.reportedAtImport());

        CallResolver imported = resolverFor("import ui.Secret;\ncomponent Home() { <p>x</p> }\n");
        assertEquals(ImportKind.NOT_VISIBLE, imported.imports().get(0).kind());
        assertTrue(((Resolution.NotVisible) imported.resolve("Secret")).reportedAtImport());
    }

    @Test
    void unknownNamesAndImportProblems() {
        CallResolver resolver = resolverFor("import ui.Nope;\nimport ui.Badge;\nimport other.Badge;\ncomponent Home() { <p>x</p> }\n");
        assertInstanceOf(Resolution.NotFound.class, resolver.resolve("Missing"));
        assertInstanceOf(Resolution.NotFound.class, resolver.resolve("ui.Missing"));
        assertEquals(ImportKind.NOT_FOUND, resolver.imports().get(0).kind());
        assertEquals(ImportKind.OK, resolver.imports().get(1).kind());
        assertEquals(ImportKind.AMBIGUOUS, resolver.imports().get(2).kind());
    }

    @Test
    void withoutProjectOnlyLocalNamesResolve() {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(
            "component A() { <p>x</p> }\n"))));
        SukoFile file = new SukoAstBuilder("component A() { <p>x</p> }\n").build(parser.compilationUnit());
        CallResolver resolver = new CallResolver(n -> "A".equals(n) ? file.components().get(0) : null, null, file.imports());
        assertInstanceOf(Resolution.Local.class, resolver.resolve("A"));
        assertInstanceOf(Resolution.NotFound.class, resolver.resolve("ui.Badge"));
    }
}
