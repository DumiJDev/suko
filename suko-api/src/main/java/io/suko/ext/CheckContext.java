package io.suko.ext;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectView;

public record CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics,
                           SecurityOptions options, java.util.Set<String> activeVocabularies) {

    /** Construtor do 13a: opções por omissão, vocabulários ativos desconhecidos. */
    public CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics) {
        this(fileName, project, diagnostics, SecurityOptions.DEFAULT, java.util.Set.of());
    }

    public void report(SukoDiagnostic.Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, fileName, span));
    }
}
