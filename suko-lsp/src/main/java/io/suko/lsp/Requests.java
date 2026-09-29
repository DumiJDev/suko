package io.suko.lsp;

import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.services.LanguageClient;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Isolamento de pedidos: uma excepção num handler vira log + resposta vazia,
 * nunca a queda do server nem um erro de protocolo no editor. O log vai para
 * o stderr (o stdout é o canal do protocolo) e, se houver cliente, para o
 * canal de output da extensão via {@code window/logMessage}.
 */
final class Requests {

    private static volatile Consumer<String> sink = message -> { };

    private Requests() {
    }

    /** Liga o log ao cliente ligado; os testes podem passar {@code null} para só usar o stderr. */
    static void logTo(LanguageClient client) {
        sink = client == null
            ? message -> { }
            : message -> client.logMessage(new MessageParams(MessageType.Error, message));
    }

    static <T> T guarded(String what, T fallback, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException | StackOverflowError e) {
            log(what, e);
            return fallback;
        }
    }

    static void guardedRun(String what, Runnable body) {
        guarded(what, null, () -> {
            body.run();
            return null;
        });
    }

    static void log(String what, Throwable e) {
        String message = "suko-lsp: " + what + " falhou: " + e;
        System.err.println(message);
        try {
            sink.accept(message);
        } catch (RuntimeException ignored) {
            // o canal de log não pode, ele próprio, derrubar o server
        }
    }
}
