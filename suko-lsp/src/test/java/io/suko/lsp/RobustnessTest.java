package io.suko.lsp;

import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Um pedido que rebenta não derruba o server: resposta vazia, log, e o pedido seguinte funciona. */
class RobustnessTest {

    @AfterEach
    void resetLog() {
        Requests.logTo(null);
    }

    @Test
    void aHandlerThatThrowsGivesAnEmptyAnswerAndIsLogged() {
        List<String> logged = new ArrayList<>();
        Requests.logTo(new TestSupport.RecordingClient() {
            @Override
            public void logMessage(MessageParams message) {
                logged.add(message.getMessage());
            }
        });

        String answer = Requests.guarded("teste", "vazio", () -> {
            throw new IllegalStateException("boom");
        });

        assertEquals("vazio", answer);
        assertEquals(1, logged.size());
        assertTrue(logged.get(0).contains("teste") && logged.get(0).contains("boom"), logged.get(0));
    }

    @Test
    void unsavedDocumentsWithANonFileUriAreIgnoredWithoutErrors(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        String untitled = "untitled:Untitled-1";

        t.server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(untitled, "suko", 1, "component A() {}")));
        var definition = t.server.getTextDocumentService().definition(new DefinitionParams(
            new TextDocumentIdentifier(untitled), new Position(0, 0))).get();
        var hover = t.server.getTextDocumentService().hover(new HoverParams(
            new TextDocumentIdentifier(untitled), new Position(0, 0))).get();
        var completion = t.server.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(untitled), new Position(0, 0))).get();
        t.server.getWorkspaceService().didChangeWatchedFiles(new DidChangeWatchedFilesParams(
            List.of(new FileEvent(untitled, FileChangeType.Changed))));
        t.scheduler.fire();

        assertTrue(definition.getLeft().isEmpty());
        assertNull(hover);
        assertTrue(completion.getRight().getItems().isEmpty());
        assertTrue(t.client.published.isEmpty());
    }

    @Test
    void theServerKeepsAnsweringAfterMalformedRequests(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        String source = "component Badge(String text) {\n  <b>${text}</b>\n}\n\ncomponent Home() {\n  Badge(text = \"x\")\n}\n";
        t.open("Home.sk", source);

        // uma sequência de disparates seguida de um pedido válido
        for (String uri : new String[] {"", "not a uri", "file:///", "http://example.com/a.sk", "file:///nao/existe/X.sk"}) {
            assertDoesNotThrow(() -> t.server.getTextDocumentService().hover(new HoverParams(
                new TextDocumentIdentifier(uri), new Position(-5, 9999))).get(), uri);
            assertDoesNotThrow(() -> t.server.getTextDocumentService().completion(new CompletionParams(
                new TextDocumentIdentifier(uri), new Position(9999, -1))).get(), uri);
        }
        assertDoesNotThrow(() -> t.server.getWorkspaceService().didChangeConfiguration(new DidChangeConfigurationParams("lixo")));
        assertDoesNotThrow(() -> t.server.getTextDocumentService().didChange(
            new org.eclipse.lsp4j.DidChangeTextDocumentParams(
                new org.eclipse.lsp4j.VersionedTextDocumentIdentifier(t.uri("Home.sk"), 9), List.of())));

        var definition = t.server.getTextDocumentService().definition(new DefinitionParams(
            new TextDocumentIdentifier(t.uri("Home.sk")), TestSupport.at(source, "Badge(text", 0, 1))).get();
        assertEquals(1, definition.getLeft().size());
    }

    @Test
    void anExceptionInsideTheDebouncedVerificationIsLoggedNotPropagated(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        List<String> logged = new ArrayList<>();
        Requests.logTo(new TestSupport.RecordingClient() {
            @Override
            public void logMessage(MessageParams message) {
                logged.add(message.getMessage());
            }
        });
        // um cliente cuja publicação falha
        t.server.connect(new TestSupport.RecordingClient() {
            @Override
            public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {
                throw new IllegalStateException("cliente partido");
            }
        });
        Requests.logTo(new TestSupport.RecordingClient() {
            @Override
            public void logMessage(MessageParams message) {
                logged.add(message.getMessage());
            }
        });
        t.open("A.sk", "component A() { <p>x</p> }\n");

        assertDoesNotThrow(t.scheduler::fire);

        assertTrue(logged.stream().anyMatch(m -> m.contains("cliente partido")), logged.toString());
        // e o server continua vivo: a próxima alteração volta a ser verificada
        int runsBefore = t.server.diagnostics().runs();
        t.change("A.sk", "component A() { <p>y</p> }\n");
        t.scheduler.fire();
        assertEquals(runsBefore + 1, t.server.diagnostics().runs());
    }
}
