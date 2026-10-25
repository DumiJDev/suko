package io.suko.jte.client;

import java.util.List;

/**
 * Tipo de um valor no código do cliente (o subconjunto de Java que a 13b traduz para JS). Os primitivos
 * ({@code INT}, {@code DOUBLE}, {@code BOOLEAN}) nunca são null; {@code STRING}, {@link Boxed} e os tipos de
 * referência podem ser.
 */
public sealed interface ClientType {

    enum Scalar implements ClientType { INT, DOUBLE, BOOLEAN, STRING }

    /**
     * {@code NULL}: o literal {@code null}. {@code VOID}: uma chamada sem valor ({@code e.preventDefault()}).
     * {@code EVENT}: o parâmetro de um handler de {@code on:}. {@code UNKNOWN}: tipo de algo que já tem um
     * problema reportado noutro sítio — quem o lê não gera um segundo diagnóstico.
     */
    enum Special implements ClientType { NULL, VOID, EVENT, UNKNOWN }

    /** {@code Integer}, {@code Double}, {@code Boolean}. */
    record Boxed(Scalar of) implements ClientType {
    }

    record ListOf(ClientType element) implements ClientType {
    }

    /** {@code Map<String, V>} (só chaves String; no cliente é um {@code Map} de JS). */
    record MapOf(ClientType value) implements ClientType {
    }

    /** Record Java com forma conhecida (componentes por ordem de declaração). */
    record RecordOf(String name, List<Field> fields) implements ClientType {
        public record Field(String name, ClientType type) {
        }
    }

    /** Tipo Java cuja forma o compilador não conhece (record, enum ou classe vindos de Java; R13). */
    record Named(String name) implements ClientType {
    }

    /** {@code Runnable} (sem parâmetros) ou {@code Consumer<T>} (um). */
    record Callback(List<ClientType> params) implements ClientType {
    }

    /** Fora do subconjunto do cliente; {@code reason} vai para a mensagem. */
    record Unsupported(String javaType, String reason) implements ClientType {
    }

    default boolean nullable() {
        return switch (this) {
            case Scalar s -> s == Scalar.STRING;
            case Special s -> s == Special.NULL || s == Special.UNKNOWN;
            case Boxed b -> true;
            case ListOf l -> true;
            case MapOf m -> true;
            case RecordOf r -> true;
            case Named n -> true;
            case Callback c -> true;
            case Unsupported u -> true;
        };
    }

    /** Como o autor o escreveria em Java (para mensagens). */
    default String java() {
        return switch (this) {
            case Scalar s -> switch (s) {
                case INT -> "int";
                case DOUBLE -> "double";
                case BOOLEAN -> "boolean";
                case STRING -> "String";
            };
            case Special s -> switch (s) {
                case NULL -> "null";
                case VOID -> "void";
                case EVENT -> "evento";
                case UNKNOWN -> "?";
            };
            case Boxed b -> switch (b.of()) {
                case INT -> "Integer";
                case DOUBLE -> "Double";
                case BOOLEAN -> "Boolean";
                case STRING -> "String";
            };
            case ListOf l -> "List<" + l.element().java() + ">";
            case MapOf m -> "Map<String, " + m.value().java() + ">";
            case RecordOf r -> r.name();
            case Named n -> n.name();
            case Callback c -> c.params().isEmpty() ? "Runnable" : "Consumer<" + c.params().get(0).java() + ">";
            case Unsupported u -> u.javaType();
        };
    }
}
