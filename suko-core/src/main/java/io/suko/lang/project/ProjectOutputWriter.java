package io.suko.lang.project;

import io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Escreve as saídas ao nível do projeto (SukoSafe.java, ...) e limpa as que o
 * Suko emitiu numa execução anterior e já não emite (ex.: mudou o
 * {@code generatedPackage}). A limpeza só apaga ficheiros listados num
 * manifesto próprio, por isso é segura mesmo quando o utilizador aponta o
 * diretório Java para uma pasta de fontes que também tem código escrito à mão.
 * Partilhado pelos plugins Gradle e Maven.
 */
public final class ProjectOutputWriter {

    private ProjectOutputWriter() {
    }

    /**
     * @param javaDir  destino dos {@code JAVA_SOURCE}
     * @param outputDir destino dos {@code TEMPLATE}/{@code RESOURCE}
     * @param manifest ficheiro onde se lembram os Java emitidos (para limpar os obsoletos)
     */
    public static void write(ProjectCompileResult result, Path outputDir, Path javaDir, Path manifest) {
        Set<Path> emittedJava = new LinkedHashSet<>();
        for (var out : result.projectOutputs()) {
            if (out.kind() == io.suko.ext.ProjectOutput.Kind.JAVA_SOURCE) {
                emittedJava.add(javaDir.resolve(out.relativePath()).normalize());
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
                throw new java.io.UncheckedIOException("Failed to write " + file, e);
            }
        }

        try {
            Files.createDirectories(manifest.toAbsolutePath().getParent());
            Files.write(manifest, emittedJava.stream().map(p -> p.toAbsolutePath().toString()).toList());
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to write " + manifest, e);
        }
    }

    private static void removeStale(Path manifest, Set<Path> keep, Path javaDir) {
        if (!Files.isRegularFile(manifest)) {
            return;
        }
        try {
            Set<Path> keepAbs = new LinkedHashSet<>();
            keep.forEach(p -> keepAbs.add(p.toAbsolutePath().normalize()));
            Path root = javaDir.toAbsolutePath().normalize();
            // Sem raiz real (ainda não existe) não há nada que o manifesto possa legitimamente apagar.
            Path realRoot = Files.isDirectory(root) ? root.toRealPath() : null;
            if (realRoot == null) {
                return;
            }
            for (String line : Files.readAllLines(manifest)) {
                if (line.isBlank()) {
                    continue;
                }
                Path raw = Path.of(line);
                if (!raw.isAbsolute()) {
                    continue;
                }
                Path old = raw.normalize();
                if (keepAbs.contains(old) || old.equals(root) || !old.startsWith(root)) {
                    continue;
                }
                if (!Files.isRegularFile(old, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                // Ancestrais simbólicos podem fazer um caminho "dentro" da raiz apontar para fora dela.
                if (!old.getParent().toRealPath().startsWith(realRoot)) {
                    continue;
                }
                Files.delete(old);
                Path dir = old.getParent();
                while (dir != null && dir.startsWith(root) && !dir.equals(root) && isEmptyDir(dir)) {
                    Files.delete(dir);
                    dir = dir.getParent();
                }
            }
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to clean stale generated sources listed in " + manifest, e);
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.findAny().isEmpty();
        }
    }
}
