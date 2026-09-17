package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.PathMatcher;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.List;

/**
 * Task for watch mode: recompiles .sk files when they change.
 */
public class SukoWatchTask extends SukoBaseTask {

    @Input
    public String getSourceDir() {
        return getExtension().getSourceDir().get();
    }

    @Input
    public String getOutputDir() {
        return getExtension().getOutputDir().get();
    }

    @TaskAction
    public void watch() {
        Path sourceDir = getExtension().getSourceDirAsPath();
        Path outputDir = getExtension().getOutputDirAsPath();

        try {
            Files.createDirectories(outputDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create output directory: " + outputDir, e);
        }

        getLogger().lifecycle("Starting watch mode for Suko in {}", sourceDir);
        getLogger().lifecycle("Press Ctrl+C to stop.");

        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            sourceDir.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            // Initial compilation
            compileAll(sourceDir, outputDir);

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path child = sourceDir.resolve(fileName);

                    if (Files.isDirectory(child) || !fileName.toString().endsWith(".sk")) {
                        continue;
                    }

                    getLogger().lifecycle("Change detected: {} - {}", kind.name(), fileName);
                    if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                        String jteFileName = fileName.toString().replaceFirst("\\.sk$", ".jte");
                        Path jteFile = outputDir.resolve(jteFileName);
                        if (Files.exists(jteFile)) {
                            Files.delete(jteFile);
                            getLogger().lifecycle("Removed: {}", jteFileName);
                        }
                    } else {
                        compileSingleFile(child, outputDir);
                    }
                }

                boolean valid = key.reset();
                if (!valid) {
                    break;
                }
            }
        } catch (IOException e) {
            getLogger().error("Error in watch service: {}", e.getMessage());
        }
    }

    private void compileAll(Path sourceDir, Path outputDir) throws IOException {
        List<Path> skFiles = findSkFiles(sourceDir);
        for (Path skFile : skFiles) {
            compileSingleFile(skFile, outputDir);
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