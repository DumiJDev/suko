package io.suko.lang.gradle;

import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.SukoProjectCompiler;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystems;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.stream.Stream;

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
            registerRecursively(sourceDir, watchService);

            // Initial compilation
            compileAll(sourceDir, outputDir);

            while (!Thread.currentThread().isInterrupted()) {
                WatchKey key;
                try {
                    key = watchService.take();
                } catch (InterruptedException e) {
                    break;
                }

                // O evento chega associado ao WatchKey do diretório que o
                // disparou (key.watchable()) — com registo recursivo isso já
                // não é necessariamente sourceDir, pode ser qualquer
                // subdiretório de pacote (ex. io/demo/ui). O nome no evento
                // (ev.context()) é sempre relativo a esse diretório, nunca à
                // raiz.
                Path watchedDir = (Path) key.watchable();
                boolean relevantChange = false;

                for (WatchEvent<?> event : key.pollEvents()) {
                    WatchEvent.Kind<?> kind = event.kind();

                    if (kind == StandardWatchEventKinds.OVERFLOW) {
                        continue;
                    }

                    WatchEvent<Path> ev = (WatchEvent<Path>) event;
                    Path fileName = ev.context();
                    Path child = watchedDir.resolve(fileName);

                    if (Files.isDirectory(child) || !fileName.toString().endsWith(".sk")) {
                        continue;
                    }

                    getLogger().lifecycle("Change detected: {} - {}", kind.name(), fileName);
                    relevantChange = true;
                }

                // Decisão vinculante D13: a recompilação é TOTAL do source
                // root inteiro a cada evento relevante, deliberadamente — sem
                // semântica incremental. Isto contorna por completo o que o
                // subprojeto 5 adiou (o que reindexar, quando, e o que fazer
                // quando muda um ficheiro que outros importam): não há
                // pergunta difícil de cache/reindexação parcial a resolver
                // se a resposta for sempre "recompila tudo". É uma
                // característica de desempenho declarada, não uma limitação
                // escondida. (Nota: um .sk apagado não remove o .jte já
                // escrito anteriormente — limpeza de outputs órfãos fica
                // fora do escopo desta tarefa.)
                if (relevantChange) {
                    compileAll(sourceDir, outputDir);
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

    private void registerRecursively(Path root, WatchService watchService) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path dir : walk.filter(Files::isDirectory).toList()) {
                dir.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
            }
        }
    }

    /**
     * Compila o source root inteiro via {@link SukoProjectCompiler} (Fase 1
     * ProjectIndex.build + Fase 2 JteCompiler por ficheiro com o índice
     * injetado) e escreve cada entrada em {@code outputDir.resolve(entry.getKey())}
     * — o mesmo padrão que {@code SukoCompileTask} já usa, já que as chaves
     * do resultado trazem o subcaminho do pacote (ex. "ui/NavLink.jte").
     * Package-private para ser testável diretamente, sem correr o loop
     * infinito do WatchService.
     * <p>
     * Public (not package-private) since Task 14 of the subprojeto 8 plan:
     * {@code FullCycleTest}, in {@code suko-cli}'s test sourceSet — a
     * different module — needs to invoke the exact same production method
     * the real {@code sukoWatch} task action delegates to, to verify watch
     * mode's mirrored-package output without running the task's actual
     * infinite {@code WatchService} loop under Gradle TestKit (which has no
     * built-in single-shot mode and would otherwise hang the test
     * indefinitely). This keeps the "closest faithful substitute" honest:
     * it is the real method, not a reimplementation, only invoked directly
     * instead of through the loop that normally calls it forever.
     * </p>
     */
    public void compileAll(Path sourceDir, Path outputDir) {
        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceDir);

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