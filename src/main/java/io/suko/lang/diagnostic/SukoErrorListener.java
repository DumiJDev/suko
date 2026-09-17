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
        collector.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                msg,
                "PARSE_ERROR",
                sourceFile,
                new io.suko.lang.ast.SourceSpan(line, charPositionInLine, -1, -1)
        ));
    }
}