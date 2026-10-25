package io.suko.lsp;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeverityMappingTest {

    private static DiagnosticSeverity lsp(SukoDiagnostic.Severity severity) {
        PositionMapper mapper = new PositionMapper("reactive component A() {}\n");
        SukoDiagnostic d = new SukoDiagnostic(severity, "m", "C", "A.sk", new SourceSpan(1, 0, 0, 7));
        return DiagnosticsService.toLsp(d, mapper).getSeverity();
    }

    @Test
    void everyCompilerSeverityHasAnLspSeverity() {
        assertEquals(DiagnosticSeverity.Error, lsp(SukoDiagnostic.Severity.ERROR));
        assertEquals(DiagnosticSeverity.Warning, lsp(SukoDiagnostic.Severity.WARNING));
        assertEquals(DiagnosticSeverity.Information, lsp(SukoDiagnostic.Severity.INFO));
        assertEquals(DiagnosticSeverity.Hint, lsp(SukoDiagnostic.Severity.HINT));
    }
}