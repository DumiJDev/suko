package io.suko.lsp;

final class Version {
    private Version() {
    }

    /** Versão do manifesto do jar; "dev" fora de um jar (testes, IDE). */
    static String get() {
        String v = Version.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }
}
