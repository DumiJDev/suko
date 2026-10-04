package io.suko.ext;

import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.ProjectView;

import java.util.Map;

/**
 * O que um alvo precisa para emitir um componente: o ficheiro, o projeto só
 * de leitura, os componentes importados (resolvidos como o compilador os
 * resolve) e o prefixo de package do ficheiro (ex.: {@code "ui."}).
 */
public record EmitContext(SukoFile file, ProjectView project,
                          Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix) {
}
