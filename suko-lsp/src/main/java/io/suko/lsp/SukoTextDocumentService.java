package io.suko.lsp;

import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.services.TextDocumentService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(
            DefinitionParams params) {
        List<Location> result = Requests.guarded("definition", List.of(), () ->
            DocumentContext.of(workspace, uris, params.getTextDocument().getUri())
                .map(ctx -> DefinitionService.definition(ctx, params.getPosition()))
                .orElse(List.of()));
        return CompletableFuture.completedFuture(Either.forLeft(result));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        Hover result = Requests.guarded("hover", null, () ->
            DocumentContext.of(workspace, uris, params.getTextDocument().getUri())
                .map(ctx -> HoverService.hover(ctx, params.getPosition()))
                .orElse(null));
        return CompletableFuture.completedFuture(result);
    }

    private void update(String uri, String text) {
        Path file = Workspace.pathOf(uri);
        workspace.projectFor(file).ifPresent(project -> {
            project.put(project.root().relativize(file), text);
            diagnostics.request(project);
        });
    }
}
