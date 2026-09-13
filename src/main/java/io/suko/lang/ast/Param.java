package io.suko.lang.ast;

import java.util.Optional;

public sealed interface Param permits Param.ValueParam, Param.SlotParam {

    String name();

    record ValueParam(Type type, String name, Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }

    record SlotParam(Type elementType, String name, Cardinality cardinality,
                      Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }
}
