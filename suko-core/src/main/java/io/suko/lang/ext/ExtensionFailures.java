package io.suko.lang.ext;

/**
 * Regra única para falhas de código de extensões: tudo é contido (vira diagnóstico), exceto
 * erros fatais da JVM (OutOfMemoryError, InternalError...). {@link StackOverflowError} conta
 * como falha da extensão (recursão infinita), não como fatal.
 */
public final class ExtensionFailures {

    private ExtensionFailures() {
    }

    public static void rethrowFatal(Throwable e) {
        if (e instanceof VirtualMachineError && !(e instanceof StackOverflowError)) {
            throw (VirtualMachineError) e;
        }
    }
}
