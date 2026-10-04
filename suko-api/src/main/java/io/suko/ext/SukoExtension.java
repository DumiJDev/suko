package io.suko.ext;

/** Uma extensão de compile-time, descoberta por {@code ServiceLoader}. Corre só no build e no LSP. */
public interface SukoExtension {
    /** Identificador único e estável, ex.: {@code "io.suko.jte"}. */
    String id();

    /** Major da API contra a qual foi compilada; normalmente {@link ExtensionApi#VERSION}. */
    int apiVersion();

    void register(ExtensionContext ctx);
}
