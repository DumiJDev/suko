package io.suko.lsp;

import org.eclipse.lsp4j.launch.LSPLauncher;
import org.eclipse.lsp4j.services.LanguageClient;

import java.io.InputStream;
import java.io.PrintStream;

/** Ponto de entrada: fala LSP por stdio. O stdout é o canal do protocolo — nada mais lhe pode escrever. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        InputStream in = System.in;
        PrintStream protocolOut = System.out;
        // Qualquer System.out.println perdido (nosso ou de uma dependência) corromperia o protocolo.
        System.setOut(System.err);

        SukoLanguageServer server = new SukoLanguageServer();
        var launcher = LSPLauncher.createServerLauncher(server, in, protocolOut);
        LanguageClient client = launcher.getRemoteProxy();
        server.connect(client);
        launcher.startListening().get();
    }
}
