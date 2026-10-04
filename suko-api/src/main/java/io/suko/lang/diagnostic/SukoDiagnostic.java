package io.suko.lang.diagnostic;

import io.suko.lang.ast.SourceSpan;

public record SukoDiagnostic(
        Severity severity,
        String message,
        String code,
        String sourceFile,
        SourceSpan span
) {

    public enum Severity {
        ERROR, WARNING
    }

    @Override
    public String toString() {
        String location = sourceFile != null ? sourceFile + ":" : "";
        if (span != null) {
            location += span.startLine() + ":" + span.startColumn();
        }
        return "[" + severity + "] " + location + " " + code + ": " + message;
    }
}