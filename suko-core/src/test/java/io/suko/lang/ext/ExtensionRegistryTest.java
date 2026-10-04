package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {

    record Ext(String id, int apiVersion, List<Object> contributions) implements SukoExtension {
        public void register(ExtensionContext ctx) {
            for (Object c : contributions) {
                if (c instanceof Target t) ctx.target(t);
                if (c instanceof Vocabulary v) ctx.vocabulary(v);
                if (c instanceof Checker k) ctx.checker(k);
            }
        }
    }

    static Target target(String id) {
        return new Target() {
            public String id() { return id; }
            public String componentType() { return "x.Component"; }
            public Set<String> vocabularies() { return Set.of(); }
            public Emitted emit(ComponentDecl c, EmitContext ctx) { return new Emitted(c.name(), "", List.of()); }
        };
    }

    static Checker checker(String id) {
        return new Checker() {
            public String id() { return id; }
            public void check(SukoFile file, CheckContext ctx) { }
        };
    }

    @Test
    void registersContributionsAndOrdersCheckersByExtensionId() {
        var registry = ExtensionRegistry.of(List.of(
            new Ext("b.ext", 1, List.of(target("b"), checker("b-check"))),
            new Ext("a.ext", 1, List.of(checker("a-check")))));
        assertEquals(Set.of("b"), registry.targetIds());
        assertEquals(List.of("a-check", "b-check"), registry.checkers().stream().map(Checker::id).toList());
        assertEquals("b.ext", registry.ownerOf(registry.target("b").orElseThrow()));
        assertTrue(registry.loadDiagnostics().isEmpty());
    }

    @Test
    void duplicateTargetIdIsAConflictAndTheFirstByExtensionIdWins() {
        var registry = ExtensionRegistry.of(List.of(
            new Ext("z.ext", 1, List.of(target("demo"))),
            new Ext("a.ext", 1, List.of(target("demo")))));
        assertEquals("a.ext", registry.ownerOf(registry.target("demo").orElseThrow()));
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_CONFLICT", d.code());
        assertTrue(d.message().contains("a.ext") && d.message().contains("z.ext"), d.message());
    }

    @Test
    void wrongApiVersionIsRejectedWithBothVersionsInTheMessage() {
        var registry = ExtensionRegistry.of(List.of(new Ext("old.ext", 99, List.of(target("old")))));
        assertTrue(registry.target("old").isEmpty());
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_API_MISMATCH", d.code());
        assertTrue(d.message().contains("old.ext") && d.message().contains("99")
            && d.message().contains(String.valueOf(ExtensionApi.VERSION)), d.message());
    }

    @Test
    void registerThatThrowsBecomesExtensionFailed() {
        SukoExtension broken = new SukoExtension() {
            public String id() { return "broken.ext"; }
            public int apiVersion() { return ExtensionApi.VERSION; }
            public void register(ExtensionContext ctx) { throw new IllegalStateException("boom"); }
        };
        var registry = ExtensionRegistry.of(List.of(broken));
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_FAILED", d.code());
        assertTrue(d.message().contains("broken.ext") && d.message().contains("boom"), d.message());
    }

    @Test
    void vocabularyLookupByIdAndEmptyProjectView() {
        Vocabulary v = new Vocabulary() {
            public String id() { return "demo"; }
            public boolean open() { return false; }
            public Optional<TagSpec> tag(String name) { return Optional.empty(); }
        };
        var registry = ExtensionRegistry.of(List.of(new Ext("a.ext", 1, List.of(v))));
        assertSame(v, registry.vocabulary("demo").orElseThrow());
        assertTrue(io.suko.lang.project.ProjectView.EMPTY.entries().isEmpty());
    }

    @Test
    void throwingOrBlankIdBecomesExtensionFailedAndOthersStillLoad() {
        SukoExtension throwing = new SukoExtension() {
            public String id() { throw new IllegalStateException("no id"); }
            public int apiVersion() { return 1; }
            public void register(ExtensionContext ctx) { }
        };
        SukoExtension nullId = new SukoExtension() {
            public String id() { return null; }
            public int apiVersion() { return 1; }
            public void register(ExtensionContext ctx) { }
        };
        var registry = ExtensionRegistry.of(List.of(throwing, nullId,
            new Ext("ok.ext", 1, List.of(target("ok")))));
        assertEquals(2, registry.loadDiagnostics().size());
        assertTrue(registry.loadDiagnostics().stream().allMatch(d -> d.code().equals("EXTENSION_FAILED")));
        assertTrue(registry.target("ok").isPresent());
    }

    @Test
    void linkageErrorInRegisterBecomesExtensionFailed() {
        SukoExtension broken = new SukoExtension() {
            public String id() { return "linkage.ext"; }
            public int apiVersion() { return ExtensionApi.VERSION; }
            public void register(ExtensionContext ctx) { throw new NoClassDefFoundError("x"); }
        };
        var registry = ExtensionRegistry.of(List.of(broken));
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_FAILED", d.code());
        assertTrue(d.message().contains("linkage.ext") && d.message().contains("x"), d.message());
    }

    @Test
    void serviceLoaderFailureBecomesExtensionFailed(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        java.nio.file.Path services = dir.resolve("META-INF/services");
        java.nio.file.Files.createDirectories(services);
        java.nio.file.Files.writeString(services.resolve("io.suko.ext.SukoExtension"), "does.not.Exist\n");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},
                ExtensionRegistryTest.class.getClassLoader())) {
            var registry = ExtensionRegistry.load(loader);
            assertTrue(registry.loadDiagnostics().stream().anyMatch(d -> d.code().equals("EXTENSION_FAILED")),
                registry.loadDiagnostics().toString());
        }
    }
}
