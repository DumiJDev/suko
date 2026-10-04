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
}
