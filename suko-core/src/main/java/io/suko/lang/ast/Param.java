package io.suko.lang.ast;

import java.util.Optional;

public sealed interface Param permits Param.ValueParam, Param.SlotParam {

    String name();

    /** `nameSpan` cobre só o identificador do parâmetro (`span` cobre `Tipo nome = default`). */
    record ValueParam(Type type, String name, Optional<Expr> defaultValue, SourceSpan span,
                      SourceSpan nameSpan) implements Param {
        /** AST construído à mão: sem posição própria do nome, usa o span do parâmetro. */
        public ValueParam(Type type, String name, Optional<Expr> defaultValue, SourceSpan span) {
            this(type, name, defaultValue, span, span);
        }
    }

    record SlotParam(Type elementType, String name, Cardinality cardinality, boolean renderProp,
                      Optional<Expr> defaultValue, SourceSpan span, SourceSpan nameSpan) implements Param {
        public SlotParam(Type elementType, String name, Cardinality cardinality, boolean renderProp,
                         Optional<Expr> defaultValue, SourceSpan span) {
            this(elementType, name, cardinality, renderProp, defaultValue, span, span);
        }
    }
}
