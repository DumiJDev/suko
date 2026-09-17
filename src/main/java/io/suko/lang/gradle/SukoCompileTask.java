package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        try {
            List<Path> skFiles = findSkFiles(sourceDir);
            if (skFiles.isEmpty()) {
                getLogger().lifecycle("No .sk files found in {}", sourceDir);
                return;
            }

            for (Path skFile : skFiles) {
                compileSingleFile(skFile, outputDir);
            }
        } catch (RuntimeException e) {
            getLogger().error("Error processing SkFiles: {}", e.getMessage());
        }
    }

    private void compileSingleFile(Path skFile, Path outputDir) {
        try {
            String source = Files.readString(skFile);
            String fileName = skFile.getFileName().toString();
            JteCompiler compiler = new JteCompiler(fileName, source);
            JteCompiler.CompileResult result = compiler.compile();

            if (result.success()) {
                getLogger().lifecycle("Compiled: {}", fileName);
                for (var entry : result.generatedJteSources().entrySet()) {
                    Path jteFile = outputDir.resolve(entry.getKey());
                    Files.writeString(jteFile, entry.getValue());
                }
            } else {
                printDiagnostics(result.diagnostics(), skFile.toString());
            }
        } catch (IOException e) {
            getLogger().error("Failed to read {}: {}", skFile, e.getMessage());
        }
    }

    private List<Path> findSkFiles(Path sourceDir) {
        try (var stream = Files.list(sourceDir)) {
            return stream
                .filter(f -> f.toFile().isFile() && f.toString().endsWith(".sk"))
                .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list source directory: " + sourceDir, e);
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