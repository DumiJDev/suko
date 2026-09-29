package io.suko.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.util.concurrent.CompletableFuture;

/**
 * Language server do Suko. Toda a lógica de linguagem vive aqui (e no
 * {@code suko-core}); os clientes de editor são finos, para o IntelliJ (11b)
 * poder reutilizar o server sem duplicação.
 */
public class SukoLanguageServer implements LanguageServer, LanguageClientAware {

    private LanguageClient client;
    private boolean shutdownRequested;

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();
        // Sincronização Full (spec do 11a): o server recebe o texto inteiro a cada alteração.
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        return CompletableFuture.completedFuture(
            new InitializeResult(capabilities, new ServerInfo("suko-lsp", Version.get())));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        shutdownRequested = true;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Convenção do protocolo: 0 se o shutdown foi pedido antes, 1 caso contrário.
        System.exit(shutdownRequested ? 0 : 1);
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new NoOpTextDocumentService();
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new NoOpWorkspaceService();
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    LanguageClient client() {
        return client;
    }
}
