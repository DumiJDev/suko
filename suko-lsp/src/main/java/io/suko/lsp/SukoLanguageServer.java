package io.suko.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.ServerInfo;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.WorkspaceFolder;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Language server do Suko. Toda a lógica de linguagem vive aqui (e no
 * {@code suko-core}); os clientes de editor são finos, para o IntelliJ (11b)
 * poder reutilizar o server sem duplicação.
 */
public class SukoLanguageServer implements LanguageServer, LanguageClientAware {

    private final Workspace workspace = new Workspace();
    private final DocumentUris uris = new DocumentUris();
    private final DiagnosticsService diagnostics;
    private final SukoTextDocumentService textDocuments;
    private final SukoWorkspaceService workspaceService;

    private volatile LanguageClient client;
    private List<Path> folders = List.of();
    private String sourceRootSetting = "";
    private boolean shutdownRequested;
    private final java.util.Set<String> loggedNotices = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public SukoLanguageServer() {
        this(new Scheduler.Threaded(), DiagnosticsService.DEBOUNCE_MILLIS);
    }

    SukoLanguageServer(Scheduler scheduler, long debounceMillis) {
        this.diagnostics = new DiagnosticsService(() -> client, scheduler, uris, debounceMillis);
        this.textDocuments = new SukoTextDocumentService(workspace, uris, diagnostics);
        this.workspaceService = new SukoWorkspaceService(workspace, diagnostics, this::sourceRootSettingChanged,
            this::logNotices);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        folders = workspaceFolders(params);
        sourceRootSetting = SukoWorkspaceService.sourceRootFrom(params.getInitializationOptions());
        if (sourceRootSetting == null) {
            sourceRootSetting = "";
        }
        workspace.configure(folders, sourceRootSetting, SukoWorkspaceService.trustedFrom(params.getInitializationOptions()));
        logNotices();

        ServerCapabilities capabilities = new ServerCapabilities();
        // Sincronização Full (spec do 11a): o server recebe o texto inteiro a cada alteração.
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);
        capabilities.setDefinitionProvider(true);
        capabilities.setHoverProvider(true);
        capabilities.setCompletionProvider(new org.eclipse.lsp4j.CompletionOptions(false, List.of(".")));
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
        return textDocuments;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return workspaceService;
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
        Requests.logTo(client);
    }

    Workspace workspace() {
        return workspace;
    }

    DiagnosticsService diagnostics() {
        return diagnostics;
    }

    private void sourceRootSettingChanged(String value) {
        sourceRootSetting = value;
        workspace.configure(folders, value);
        logNotices();
    }

    /** Cada aviso de extensões vai uma única vez para o log do cliente. */
    private synchronized void logNotices() {
        LanguageClient target = client;
        if (target == null) {
            return;
        }
        java.util.Set<String> active = new java.util.LinkedHashSet<>();
        for (Project project : workspace.projects()) {
            project.notice().ifPresent(active::add);
        }
        // só os avisos ativos ficam lembrados: um problema corrigido e que volte é reenviado
        loggedNotices.retainAll(active);
        for (String notice : active) {
            if (loggedNotices.add(notice)) {
                target.logMessage(new MessageParams(MessageType.Warning, notice));
            }
        }
    }

    private static List<Path> workspaceFolders(InitializeParams params) {
        List<Path> result = new ArrayList<>();
        List<WorkspaceFolder> given = params.getWorkspaceFolders();
        if (given != null) {
            for (WorkspaceFolder folder : given) {
                Workspace.tryPathOf(folder.getUri()).ifPresent(result::add);
            }
        }
        if (result.isEmpty() && params.getRootUri() != null) {
            Workspace.tryPathOf(params.getRootUri()).ifPresent(result::add);
        }
        return result;
    }
}
