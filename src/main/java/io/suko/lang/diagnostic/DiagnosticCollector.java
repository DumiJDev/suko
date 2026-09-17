package io.suko.lang.diagnostic;

import java.util.ArrayList;
import java.util.List;

public class DiagnosticCollector {

    private final List<SukoDiagnostic> diagnostics = new ArrayList<>();

    public void add(SukoDiagnostic diagnostic) {
        diagnostics.add(diagnostic);
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(d -> d.severity() == SukoDiagnostic.Severity.ERROR);
    }

    public List<SukoDiagnostic> getDiagnostics() {
        return List.copyOf(diagnostics);
    }

    public List<SukoDiagnostic> getErrors() {
        return diagnostics.stream()
                .filter(d -> d.severity() == SukoDiagnostic.Severity.ERROR)
                .toList();
    }

    @Override
    public String toString() {
        if (diagnostics.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (SukoDiagnostic diagnostic : diagnostics) {
            out.append(diagnostic).append(System.lineSeparator());
        }
        return out.toString();
    }
}