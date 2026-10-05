package io.suko.lang.gradle;

import io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Escreve as saídas ao nível do projeto (SukoSafe.java, ...) e limpa as que o
 * Suko emitiu numa execução anterior e já não emite (ex.: mudou o
 * {@code generatedPackage}). A limpeza só apaga ficheiros listados num
 * manifesto próprio ({@code build/suko/java-outputs.txt}), por isso é segura
 * mesmo quando o utilizador aponta {@code generatedJavaDir} para uma pasta de
 * fontes que também tem código escrito à mão.
 */
final class SukoProjectOutputs {

    private SukoProjectOutputs() {
    }

    static void write(ProjectCompileResult result, Path outputDir, SukoExtension extension) {
        Path javaDir = extension == null ? outputDir.resolveSibling("suko-java") : extension.getGeneratedJavaDirAsPath();
        Path manifest = extension == null ? outputDir.resolveSibling("suko-java-outputs.txt")
            : extension.getSecurityAuditPath().resolveSibling("java-outputs.txt");

        io.suko.lang.project.ProjectOutputWriter.write(result, outputDir, javaDir, manifest);
    }

    static void deleteAudit(SukoExtension extension) {
        if (extension == null) {
            return;
        }
        try {
            Files.deleteIfExists(extension.getSecurityAuditPath());
        } catch (IOException e) {
            throw new RuntimeException("Failed to delete stale security audit", e);
        }
    }
}
