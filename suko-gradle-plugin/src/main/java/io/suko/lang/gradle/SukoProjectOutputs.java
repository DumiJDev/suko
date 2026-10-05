package io.suko.lang.gradle;

import io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

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

        Set<Path> emittedJava = new LinkedHashSet<>();
        for (var out : result.projectOutputs()) {
            Path base = switch (out.kind()) {
                case JAVA_SOURCE -> javaDir;
                case TEMPLATE, RESOURCE -> outputDir;
            };
            Path file = base.resolve(out.relativePath()).normalize();
            if (out.kind() == io.suko.ext.ProjectOutput.Kind.JAVA_SOURCE) {
                emittedJava.add(file);
            }
        }
        removeStale(manifest, emittedJava, javaDir);

        for (var out : result.projectOutputs()) {
            Path base = switch (out.kind()) {
                case JAVA_SOURCE -> javaDir;
                case TEMPLATE, RESOURCE -> outputDir;
            };
            Path file = base.resolve(out.relativePath());
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, out.source());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + file, e);
            }
        }

        try {
            Files.createDirectories(manifest.toAbsolutePath().getParent());
            Files.write(manifest, emittedJava.stream().map(p -> p.toAbsolutePath().toString()).toList());
        } catch (IOException e) {
            throw new RuntimeException("Failed to write " + manifest, e);
        }
    }

    private static void removeStale(Path manifest, Set<Path> keep, Path javaDir) {
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        try {
            Set<Path> keepAbs = new LinkedHashSet<>();
            keep.forEach(p -> keepAbs.add(p.toAbsolutePath()));
            Path root = javaDir.toAbsolutePath().normalize();
            for (String line : Files.readAllLines(manifest)) {
                if (line.isBlank()) {
                    continue;
                }
                Path old = Path.of(line).normalize();
                if (keepAbs.contains(old) || !old.startsWith(root)) {
                    continue;
                }
                Files.deleteIfExists(old);
                Path dir = old.getParent();
                while (dir != null && !dir.equals(root) && isEmptyDir(dir)) {
                    Files.delete(dir);
                    dir = dir.getParent();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to clean stale generated sources listed in " + manifest, e);
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
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
