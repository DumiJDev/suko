package io.suko.lang.ast;

import java.util.List;

public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span, boolean isPublic,
                             SourceSpan nameSpan) {
    /** Sem posição própria do nome (AST construído à mão): usa o span da declaração. */
    public ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                         List<Statement> body, SourceSpan span, boolean isPublic) {
        this(name, typeParameters, params, body, span, isPublic, span);
    }
}
