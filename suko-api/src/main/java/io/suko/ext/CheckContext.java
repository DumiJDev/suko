package io.suko.ext;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectView;

public record CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics) {

    public void report(SukoDiagnostic.Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, fileName, span));
    }
}
