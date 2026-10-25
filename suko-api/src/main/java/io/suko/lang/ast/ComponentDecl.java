package io.suko.lang.ast;

import java.util.List;

/** {@code reactive} (13b): o componente foi declarado com {@code reactive component}. */
public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span, boolean isPublic,
                             SourceSpan nameSpan, boolean reactive) {
    /** Componente simples (antes da 13b e AST construído à mão). */
    public ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                         List<Statement> body, SourceSpan span, boolean isPublic,
                         SourceSpan nameSpan) {
        this(name, typeParameters, params, body, span, isPublic, nameSpan, false);
    }

    /** Sem posição própria do nome (AST construído à mão): usa o span da declaração. */
    public ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                         List<Statement> body, SourceSpan span, boolean isPublic) {
        this(name, typeParameters, params, body, span, isPublic, span, false);
    }
}
