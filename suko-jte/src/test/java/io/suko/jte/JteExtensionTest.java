package io.suko.jte;

import io.suko.ext.EmitContext;
import io.suko.ext.Emitted;
import io.suko.ext.ExtensionApi;
import io.suko.ext.SukoExtension;
import io.suko.lang.JteEmitter;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.*;

class JteExtensionTest {

    @Test
    void isDiscoverableByServiceLoader() {
        List<String> ids = ServiceLoader.load(SukoExtension.class).stream()
            .map(p -> p.get().id()).toList();
        assertTrue(ids.contains("io.suko.jte"), ids.toString());
    }

    @Test
    void targetDeclaresComponentTypeAndHtmlVocabulary() {
        JteTarget target = new JteTarget();
        assertEquals("jte", target.id());
        assertEquals("gg.jte.Content", target.componentType());
        assertEquals(java.util.Set.of("html"), target.vocabularies());
        assertTrue(new HtmlVocabulary().open());
        assertEquals(ExtensionApi.VERSION, new JteExtension().apiVersion());
    }

    @Test
    void emitIsExactlyWhatTheEmitterProduces() {
        ComponentDecl hello = new ComponentDecl("Hello", List.of(), List.of(), List.of(),
            io.suko.lang.ast.SourceSpan.NONE, false);
        SukoFile file = new SukoFile(java.util.Optional.empty(), List.of(), List.of(hello));
        Emitted emitted = new JteTarget().emit(hello, new EmitContext(file, ProjectView.EMPTY, Map.of(), ""));
        var expected = new JteEmitter(file.components(), Map.of(), "").emitWithSourceMap(hello);
        assertEquals("Hello.jte", emitted.relativePath());
        assertEquals(expected.jteSource(), emitted.source());
        assertEquals(expected.sourceMap(), emitted.sourceMap());
    }
}
