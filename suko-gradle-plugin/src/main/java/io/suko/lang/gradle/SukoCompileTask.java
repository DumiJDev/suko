package io.suko.lang.gradle;

import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Task for compiling .sk files to .jte using JteCompiler.
 */
public class SukoCompileTask extends SukoBaseTask {

    @Input
    public String getSourceDir() {
        return getExtension().getSourceDir().get();
    }

    @Input
    public String getOutputDir() {
        return getExtension().getOutputDir().get();
    }

    @TaskAction
    public void compile() {
        Path sourceDir = getExtension().getSourceDirAsPath();
        Path outputDir = getExtension().getOutputDirAsPath();

        // Revisão final (achado J): restaurado o tratamento amigável de
        // "sem fontes", perdido quando esta task passou a usar o
        // SukoProjectCompiler — sem isto, um sourceDir inexistente rebentava
        // com um UncheckedIOException de Files.walk. O lado Maven
        // (SukoCompileMojo) sempre teve o equivalente.
        String skipReason = noSourcesMessage(sourceDir);
        if (skipReason != null) {
            getLogger().lifecycle(skipReason);
            return;
        }

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
            new io.suko.lang.project.SukoProjectCompiler().compile(sourceDir);

        for (var entry : result.generatedJteSources().entrySet()) {
            Path jteFile = outputDir.resolve(entry.getKey());
            try {
                Files.createDirectories(jteFile.getParent());
                Files.writeString(jteFile, entry.getValue());
                getLogger().lifecycle("Compiled: {}", entry.getKey());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + jteFile, e);
            }
        }

        for (var fileEntry : result.diagnosticsByFile().entrySet()) {
            printDiagnostics(fileEntry.getValue(), fileEntry.getKey().toString());
        }

        if (!result.success()) {
            throw new RuntimeException("Suko compilation failed — see diagnostics above");
        }
    }

    /**
     * Devolve a mensagem amigável a mostrar quando não há nada para compilar,
     * ou {@code null} quando a compilação deve prosseguir. Varre recursivamente
     * (tal como o SukoProjectCompiler, que usa Files.walk) — package-private
     * para ser testável sem instanciar um Project do Gradle.
     */
    static String noSourcesMessage(Path sourceDir) {
        if (!Files.isDirectory(sourceDir)) {
            return "No .sk files found in " + sourceDir + " (directory does not exist)";
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(sourceDir)) {
            boolean hasSkFiles = walk.anyMatch(p -> Files.isRegularFile(p) && p.toString().endsWith(".sk"));
            return hasSkFiles ? null : "No .sk files found in " + sourceDir;
        } catch (IOException e) {
            throw new RuntimeException("Failed to scan source directory: " + sourceDir, e);
        }
    }

    private void printDiagnostics(DiagnosticCollector diagnostics, String fileName) {
        for (SukoDiagnostic diag : diagnostics.getErrors()) {
            String location = diag.span() != null
                ? fileName + ":" + diag.span().startLine() + ":" + diag.span().startColumn()
                : fileName;
            getLogger().error("[ERROR] {} - {}: {}", location, diag.code(), diag.message());
        }
        for (SukoDiagnostic diag : diagnostics.getDiagnostics()) {
            if (diag.severity() == SukoDiagnostic.Severity.WARNING) {
                String location = diag.span() != null
                    ? fileName + ":" + diag.span().startLine() + ":" + diag.span().startColumn()
                    : fileName;
                getLogger().warn("[WARNING] {} - {}: {}", location, diag.code(), diag.message());
            }
        }
    }
}