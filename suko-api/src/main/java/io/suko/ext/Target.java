package io.suko.ext;

import io.suko.lang.ast.ComponentDecl;

import java.util.Set;

/** Um alvo de compilação: transforma cada componente num ficheiro de output. */
public interface Target {
    /** Ex.: {@code "jte"}. É o nome usado em {@code targets}. */
    String id();

    /** O tipo Java que {@code Component} representa neste alvo (gancho do item 12), ex.: {@code "gg.jte.Content"}. */
    String componentType();

    /** Ids dos vocabulários de tags aceites por este alvo, ex.: {@code {"html"}}. */
    Set<String> vocabularies();

    Emitted emit(ComponentDecl component, EmitContext ctx);
}
