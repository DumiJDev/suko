package io.suko.jte.client;

import io.suko.lang.ast.SourceSpan;

/**
 * Um problema numa expressão do cliente, com a localização exata. {@code code} é {@code CLIENT_UNSUPPORTED},
 * {@code TYPE_NOT_INFERRED} ou {@code RESERVED_NAME}; {@code null} quando é consequência de um problema já
 * reportado noutro sítio (não gera diagnóstico: um por sítio).
 */
public record ClientProblem(String code, String message, SourceSpan span) {

    public boolean silent() {
        return code == null;
    }
}
