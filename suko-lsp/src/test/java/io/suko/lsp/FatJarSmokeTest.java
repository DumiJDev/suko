package io.suko.lsp;

import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.Launcher;
import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageServer;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** O jar que a extensão VSCode embute arranca, fala LSP por stdio e sai limpo. */
class FatJarSmokeTest {

    @Test
    void fatJarAnswersInitializeAndExitsCleanlyAfterShutdown() throws Exception {
        String jar = System.getProperty("suko.lsp.fatJar");
        assertNotNull(jar, "propriedade suko.lsp.fatJar não definida");
        assertTrue(new File(jar).isFile(), jar);

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        Process process = new ProcessBuilder(javaBin, "-jar", jar)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        try {
            Launcher<LanguageServer> launcher = LSPLauncher.createClientLauncher(
                new NoOpClient(), process.getInputStream(), process.getOutputStream());
            launcher.startListening();
            LanguageServer server = launcher.getRemoteProxy();

            InitializeResult result = server.initialize(new InitializeParams()).get(20, TimeUnit.SECONDS);

            assertEquals("suko-lsp", result.getServerInfo().getName());
            assertEquals(TextDocumentSyncKind.Full, result.getCapabilities().getTextDocumentSync().getLeft());

            server.shutdown().get(10, TimeUnit.SECONDS);
            server.exit();
            assertTrue(process.waitFor(10, TimeUnit.SECONDS), "o server devia terminar depois de exit");
            assertEquals(0, process.exitValue());
        } finally {
            process.destroyForcibly();
        }
    }

    private static final class NoOpClient implements LanguageClient {
        @Override
        public void telemetryEvent(Object object) {
        }

        @Override
        public void publishDiagnostics(org.eclipse.lsp4j.PublishDiagnosticsParams diagnostics) {
        }

        @Override
        public void showMessage(org.eclipse.lsp4j.MessageParams messageParams) {
        }

        @Override
        public java.util.concurrent.CompletableFuture<org.eclipse.lsp4j.MessageActionItem> showMessageRequest(
            org.eclipse.lsp4j.ShowMessageRequestParams requestParams) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public void logMessage(org.eclipse.lsp4j.MessageParams message) {
        }
    }
}
