package io.suko.lang.ast;

import java.util.List;
import java.util.Optional;

/**
 * Um ficheiro .sk completo.
 *
 * <p>{@code packageSpan} (revisão final do subprojeto 5, achado F) guarda a
 * posição real da declaração {@code package}, para que
 * PACKAGE_DIRECTORY_MISMATCH aponte para a linha certa em vez de 0:0 —
 * mesmo tratamento que {@link ImportDecl} já tem desde a Tarefa 2. Está
 * vazio quando o ficheiro não declara package (ou quando o SukoFile é
 * construído à mão, p.ex. em testes de unidade, pelo construtor de 3
 * argumentos).
 */
public record SukoFile(Optional<String> packageName, Optional<SourceSpan> packageSpan,
                       List<ImportDecl> imports, List<ComponentDecl> components) {

    public SukoFile(Optional<String> packageName, List<ImportDecl> imports, List<ComponentDecl> components) {
        this(packageName, Optional.empty(), imports, components);
    }
}
