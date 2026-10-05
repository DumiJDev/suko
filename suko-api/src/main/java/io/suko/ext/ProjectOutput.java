package io.suko.ext;

/** Um ficheiro produzido ao nível do projeto (não por componente), ex.: a SukoSafe.java do alvo JTE. */
public record ProjectOutput(Kind kind, String relativePath, String source) {
    public enum Kind {
        /** Vai para a raiz dos templates (como os .jte). */
        TEMPLATE,
        /** Vai para o diretório de fontes Java geradas. */
        JAVA_SOURCE,
        /** Vai para os recursos estáticos. */
        RESOURCE
    }
}
