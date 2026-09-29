package io.suko.lsp;

import java.util.function.Supplier;

/**
 * Isolamento de pedidos: uma excepção num handler vira log + resposta vazia,
 * nunca a queda do server nem um erro de protocolo no editor.
 */
final class Requests {

    private Requests() {
    }

    static <T> T guarded(String what, T fallback, Supplier<T> body) {
        try {
            return body.get();
        } catch (RuntimeException | StackOverflowError e) {
            System.err.println("suko-lsp: " + what + " falhou: " + e);
            return fallback;
        }
    }
}
