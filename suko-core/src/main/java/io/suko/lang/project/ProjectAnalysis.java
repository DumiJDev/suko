package io.suko.lang.project;

import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;

import java.nio.file.Path;
import java.util.Map;

/**
 * Resultado da verificação sem emissão ({@link SukoProjectCompiler#analyze}):
 * por ficheiro (caminho relativo ao source root), o AST — {@code null} quando
 * o ficheiro não faz parse — e os diagnósticos, iguais aos do {@code sukoCompile}.
 */
public record ProjectAnalysis(ProjectIndex index, Map<Path, FileAnalysis> files) {

    public record FileAnalysis(SukoFile ast, DiagnosticCollector diagnostics) {
    }
}
