package io.suko.lsp;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.nio.file.Path;
import java.util.List;

/** Sincronização Full: cada alteração traz o texto inteiro e vira o buffer do documento. */
final class SukoTextDocumentService implements TextDocumentService {

    private final Workspace workspace;
    private final DocumentUris uris;
    private final DiagnosticsService diagnostics;

    SukoTextDocumentService(Workspace workspace, DocumentUris uris, DiagnosticsService diagnostics) {
        this.workspace = workspace;
        this.uris = uris;
        this.diagnostics = diagnostics;
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        uris.opened(uri);
        update(uri, params.getTextDocument().getText());
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        List<TextDocumentContentChangeEvent> changes = params.getContentChanges();
        if (changes.isEmpty()) {
            return;
        }
        // Full: a última alteração é o documento inteiro.
        update(params.getTextDocument().getUri(), changes.get(changes.size() - 1).getText());
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        Path file = Workspace.pathOf(uri);
        workspace.projectFor(file).ifPresent(project -> {
            project.close(project.root().relativize(file));
            diagnostics.request(project);
        });
        uris.closed(uri);
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // O disco só é relido por didChangeWatchedFiles; o buffer aberto já é a verdade.
    }

    private void update(String uri, String text) {
        Path file = Workspace.pathOf(uri);
        workspace.projectFor(file).ifPresent(project -> {
            project.put(project.root().relativize(file), text);
            diagnostics.request(project);
        });
    }
}
