package io.suko.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 13b no editor: as palavras state/derived na completion e o REACTIVE_UNUSED como Hint. */
class ReactiveLspTest {

    @Test
    void bodyCompletionOffersStateAndDerived(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.open("R.sk", "reactive component R() {\n  \n}\n");
        List<CompletionItem> items = t.server.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(t.uri("R.sk")), new Position(1, 2))).get().getRight().getItems();
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("state", "derived", "if", "for")), labels.toString());
    }

    @Test
    void reactiveUnusedIsAHintInTheEditor(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.open("R.sk", "reactive component R() {\n  <p>x</p>\n}\n");
        t.scheduler.fire();
        Diagnostic hint = t.client.lastFor(t.uri("R.sk")).getDiagnostics().stream()
            .filter(d -> "REACTIVE_UNUSED".equals(d.getCode().getLeft())).findFirst().orElseThrow();
        assertEquals(DiagnosticSeverity.Hint, hint.getSeverity());
    }
}
