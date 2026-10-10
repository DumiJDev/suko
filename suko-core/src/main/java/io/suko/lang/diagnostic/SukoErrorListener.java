package io.suko.lang.diagnostic;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class SukoErrorListener extends BaseErrorListener {

    private final DiagnosticCollector collector;
    private final String sourceFile;

    public SukoErrorListener(DiagnosticCollector collector, String sourceFile) {
        this.collector = collector;
        this.sourceFile = sourceFile;
    }

    @Override
    public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                            int line, int charPositionInLine,
                            String msg, RecognitionException e) {
        if (offendingSymbol instanceof org.antlr.v4.runtime.Token t
                && (t.getType() == io.suko.lang.SukoLexer.DOCTYPE || t.getType() == io.suko.lang.SukoLexer.INVALID_DECL)) {
            collector.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "só se aceita <!DOCTYPE html>, e apenas como primeiro item do corpo de um componente"
                            + " (encontrado: " + t.getText().replaceAll("\\s+", " ") + ")",
                    "INVALID_DOCTYPE",
                    sourceFile,
                    new io.suko.lang.ast.SourceSpan(line, charPositionInLine, -1, -1)
            ));
            return;
        }
        collector.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                msg,
                "PARSE_ERROR",
                sourceFile,
                new io.suko.lang.ast.SourceSpan(line, charPositionInLine, -1, -1)
        ));
    }
}