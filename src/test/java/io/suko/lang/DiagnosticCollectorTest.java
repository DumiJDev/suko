package io.suko.lang;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DiagnosticCollectorTest {

    @Test
    void startsEmpty() {
        DiagnosticCollector collector = new DiagnosticCollector();
        assertFalse(collector.hasErrors());
        assertTrue(collector.getDiagnostics().isEmpty());
        assertTrue(collector.getErrors().isEmpty());
    }

    @Test
    void addErrorSetsHasErrors() {
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR, "msg", "CODE", "file.sk",
                new SourceSpan(1, 1, 0, 5)));
        assertTrue(collector.hasErrors());
        assertEquals(1, collector.getErrors().size());
        assertEquals(1, collector.getDiagnostics().size());
    }

    @Test
    void warningDoesNotSetHasErrors() {
        DiagnosticCollector collector = new DiagnosticCollector();
        collector.add(new SukoDiagnostic(SukoDiagnostic.Severity.WARNING, "msg", "CODE", "file.sk",
                new SourceSpan(1, 1, 0, 5)));
        assertFalse(collector.hasErrors());
        assertEquals(1, collector.getDiagnostics().size());
        assertTrue(collector.getErrors().isEmpty());
    }
}