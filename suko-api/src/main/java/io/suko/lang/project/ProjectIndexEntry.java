package io.suko.lang.project;

import io.suko.lang.ast.SourceSpan;

import java.nio.file.Path;
import java.util.List;

/**
 * Assinatura indexada de um componente (Fase 1): o suficiente para resolver
 * e validar chamadas vindas de outros ficheiros. {@code declarationSpan} é o
 * da declaração inteira e {@code nameSpan} o do nome, ambos em
 * {@code sourceFile}. {@code reactive} (13b): declarado com {@code reactive component}.
 */
public record ProjectIndexEntry(
    String qualifiedName,
    String simpleName,
    Path sourceFile,
    boolean isPublic,
    List<ParamInfo> params,
    SourceSpan declarationSpan,
    SourceSpan nameSpan,
    boolean reactive
) {
    /** Componente simples (antes da 13b). */
    public ProjectIndexEntry(String qualifiedName, String simpleName, Path sourceFile, boolean isPublic,
                             List<ParamInfo> params, SourceSpan declarationSpan, SourceSpan nameSpan) {
        this(qualifiedName, simpleName, sourceFile, isPublic, params, declarationSpan, nameSpan, false);
    }

    public int paramCount() {
        return params.size();
    }
}
