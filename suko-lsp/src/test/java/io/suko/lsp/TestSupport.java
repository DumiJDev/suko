package io.suko.lsp;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.MessageActionItem;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.ShowMessageRequestParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentItem;
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Server + cliente em memória, sem processo nem relógio real. */
final class TestSupport {

    static final class RecordingClient implements LanguageClient {
        final List<PublishDiagnosticsParams> published = new ArrayList<>();

        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(PublishDiagnosticsParams diagnostics) {
            published.add(diagnostics);
        }

        @Override
        public void showMessage(MessageParams messageParams) {
        }

        @Override
        public CompletableFuture<MessageActionItem> showMessageRequest(ShowMessageRequestParams requestParams) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(MessageParams message) {
        }

        /** Última publicação para este ficheiro, ou {@code null}. */
        PublishDiagnosticsParams lastFor(String uri) {
            PublishDiagnosticsParams last = null;
            for (PublishDiagnosticsParams p : published) {
                if (p.getUri().equals(uri)) {
                    last = p;
                }
            }
            return last;
        }
    }

    /** Executa o que foi agendado só quando o teste manda; um novo agendamento não cancela sozinho. */
    static final class ManualScheduler implements Scheduler {
        private final List<Entry> entries = new ArrayList<>();

        private static final class Entry {
            final Runnable task;
            boolean cancelled;
            long delay;

            Entry(Runnable task, long delay) {
                this.task = task;
                this.delay = delay;
            }
        }

        @Override
        public Cancellable schedule(Runnable task, long delayMillis) {
            Entry entry = new Entry(task, delayMillis);
            entries.add(entry);
            return () -> entry.cancelled = true;
        }

        /** Dispara tudo o que não foi cancelado (equivale a passar 250 ms sem novas alterações). */
        void fire() {
            List<Entry> due = new ArrayList<>(entries);
            entries.clear();
            due.stream().filter(e -> !e.cancelled).forEach(e -> e.task.run());
        }

        long lastDelay() {
            return entries.get(entries.size() - 1).delay;
        }
    }

    final Path folder;
    final Path root;
    final RecordingClient client = new RecordingClient();
    final ManualScheduler scheduler = new ManualScheduler();
    final SukoLanguageServer server;
    private int version = 1;

    TestSupport(Path folder) throws IOException {
        this.folder = folder;
        this.root = Files.createDirectories(folder.resolve("src/main/suko"));
        this.server = new SukoLanguageServer(scheduler, DiagnosticsService.DEBOUNCE_MILLIS);
        server.connect(client);
        InitializeParams params = new InitializeParams();
        params.setWorkspaceFolders(List.of(new WorkspaceFolder(folder.toUri().toString(), "ws")));
        server.initialize(params).join();
    }

    Path write(String relative, String text) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    String uri(String relative) {
        return root.resolve(relative).toUri().toString();
    }

    void open(String relative, String text) {
        server.getTextDocumentService().didOpen(new DidOpenTextDocumentParams(
            new TextDocumentItem(uri(relative), "suko", version++, text)));
    }

    void change(String relative, String text) {
        server.getTextDocumentService().didChange(new DidChangeTextDocumentParams(
            new VersionedTextDocumentIdentifier(uri(relative), version++),
            List.of(new TextDocumentContentChangeEvent(text))));
    }

    void close(String relative) {
        server.getTextDocumentService().didClose(new DidCloseTextDocumentParams(
            new TextDocumentIdentifier(uri(relative))));
    }
}
