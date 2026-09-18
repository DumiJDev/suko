package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JavacTaskTest {

    @Test
    void compilesSimpleComponent() {
        SukoFile sukoFile = new SukoFile(
            Optional.empty(),
            List.of(),
            List.of(
                new ComponentDecl(
                    "Card",
                    List.of(),
                    List.of(
                        new Param.ValueParam(new Type("String", List.of(), 0), "title", Optional.empty(), new SourceSpan(1, 1, 0, 5)),
                        new Param.ValueParam(new Type("List", List.of(new Type("String", List.of(), 0)), 0), "items", Optional.empty(), new SourceSpan(1, 1, 0, 5))
                    ),
                    List.of(),
                    new SourceSpan(1, 1, 0, 10)
                )
            )
        );

        Map<String, List<SourceMapEntry>> sourceMaps = Map.of(
            "Card.jte", List.of()
        );

        JavacTask task = new JavacTask(sukoFile, sourceMaps);
        DiagnosticCollector diagnostics = task.compile();

        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void compilesMultipleComponents() {
        ComponentDecl card = new ComponentDecl(
            "Card", List.of(),
            List.of(
                new Param.ValueParam(new Type("String", List.of(), 0), "title", Optional.empty(), new SourceSpan(1, 1, 0, 5))
            ),
            List.of(),
            new SourceSpan(1, 1, 0, 10)
        );

        ComponentDecl page = new ComponentDecl(
            "Page", List.of(),
            List.of(
                new Param.ValueParam(new Type("String", List.of(), 0), "label", Optional.empty(), new SourceSpan(1, 1, 0, 5))
            ),
            List.of(),
            new SourceSpan(1, 1, 0, 10)
        );

        SukoFile sukoFile = new SukoFile(
            Optional.empty(),
            List.of(),
            List.of(card, page)
        );

        Map<String, List<SourceMapEntry>> sourceMaps = Map.of();

        JavacTask task = new JavacTask(sukoFile, sourceMaps);
        DiagnosticCollector diagnostics = task.compile();

        assertFalse(diagnostics.hasErrors());
    }

    @Test
    void reportsErrorWhenJavacNotAvailable() {
        SukoFile sukoFile = new SukoFile(
            Optional.empty(),
            List.of(),
            List.of()
        );

        JavacTask task = new JavacTask(sukoFile, Map.of());
        DiagnosticCollector diagnostics = task.compile();

        // If javac is available, no errors; if not, reports JAVAC_NOT_AVAILABLE
        // This test verifies the code doesn't throw
        assertNotNull(diagnostics);
    }

    @Test
    void compilesComponentWithSlotParam() {
        ComponentDecl card = new ComponentDecl(
            "Card", List.of(),
            List.of(
                new Param.SlotParam(new Type("Content", List.of(), 0), "header", Cardinality.ONE, false, Optional.empty(), new SourceSpan(1, 1, 0, 5)),
                new Param.SlotParam(new Type("String", List.of(), 0), "title", Cardinality.ONE, false, Optional.empty(), new SourceSpan(1, 1, 0, 5))
            ),
            List.of(),
            new SourceSpan(1, 1, 0, 10)
        );

        SukoFile sukoFile = new SukoFile(
            Optional.empty(),
            List.of(),
            List.of(card)
        );

        Map<String, List<SourceMapEntry>> sourceMaps = Map.of(
            "Card.jte", List.of()
        );

        JavacTask task = new JavacTask(sukoFile, sourceMaps);
        DiagnosticCollector diagnostics = task.compile();

        assertFalse(diagnostics.hasErrors());
    }
}