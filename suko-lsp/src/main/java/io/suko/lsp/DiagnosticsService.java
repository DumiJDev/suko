package io.suko.lsp;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectAnalysis;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.services.LanguageClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Reverifica um projecto depois de uma pausa (debounce) e publica os
 * diagnósticos por ficheiro. Códigos, mensagens e severidades são exactamente
 * os do {@code sukoCompile} — a única tradução é a de posições
 * ({@link PositionMapper}). Só volta a publicar um ficheiro se os seus
 * diagnósticos mudaram, e limpa os dos ficheiros que deixaram de existir.
 */
final class DiagnosticsService {

    static final long DEBOUNCE_MILLIS = 250;

    private final Supplier<LanguageClient> client;
    private final Scheduler scheduler;
    private final DocumentUris uris;
    private final long debounceMillis;

    private final Map<Project, Scheduler.Cancellable> pending = new IdentityHashMap<>();
    /**
     * Último conjunto publicado por projecto, chaveado pelo {@code Path} normalizado e não pela
     * string do URI: o mesmo ficheiro alterna entre o URI do cliente (aberto) e o do {@code Path}
     * (fechado), que só diferem na escrita (`c%3A` vs `C:`, `%28` vs `(`) — com o URI como chave,
     * abrir um ficheiro limpava os diagnósticos do outro.
     */
    private final Map<Project, Map<Path, Published>> published = new IdentityHashMap<>();

    private record Published(String uri, List<Diagnostic> diagnostics) {
    }
    private int runs;

    DiagnosticsService(Supplier<LanguageClient> client, Scheduler scheduler, DocumentUris uris, long debounceMillis) {
        this.client = client;
        this.scheduler = scheduler;
        this.uris = uris;
        this.debounceMillis = debounceMillis;
    }

    /** Agenda uma reverificação; um novo pedido antes de disparar substitui o anterior. */
    synchronized void request(Project project) {
        Scheduler.Cancellable previous = pending.remove(project);
        if (previous != null) {
            previous.cancel();
        }
        pending.put(project, scheduler.schedule(() -> run(project), debounceMillis));
    }

    /** Número de verificações efectivamente executadas (para os testes do debounce). */
    synchronized int runs() {
        return runs;
    }

    private void run(Project project) {
        synchronized (this) {
            pending.remove(project);
            runs++;
        }
        try {
            publish(project);
        } catch (RuntimeException | StackOverflowError e) {
            // Uma verificação que falha não pode derrubar o server; os diagnósticos
            // anteriores ficam como estavam até à próxima alteração.
            Requests.log("verificação de " + project.root(), e);
        }
    }

    private void publish(Project project) {
        LanguageClient target = client.get();
        if (target == null) {
            return;
        }
        Project.Snapshot snapshot = project.snapshot();
        Map<Path, Published> next = convert(project, snapshot);

        Map<Path, Published> previous;
        synchronized (this) {
            previous = published.getOrDefault(project, Map.of());
            published.put(project, next);
        }
        for (Map.Entry<Path, Published> entry : next.entrySet()) {
            if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                target.publishDiagnostics(new PublishDiagnosticsParams(
                    entry.getValue().uri(), entry.getValue().diagnostics()));
            }
        }
        // Só se limpa o que deixou de existir: um path que continua em `next` nunca leva `[]`
        // para o URI antigo (o cliente normalizaria os dois para o mesmo recurso).
        for (Map.Entry<Path, Published> entry : previous.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                target.publishDiagnostics(new PublishDiagnosticsParams(entry.getValue().uri(), List.of()));
            }
        }
    }

    private Map<Path, Published> convert(Project project, Project.Snapshot snapshot) {
        Map<Path, Published> result = new HashMap<>();
        ProjectAnalysis analysis = snapshot.analysis();
        for (Map.Entry<Path, ProjectAnalysis.FileAnalysis> file : analysis.files().entrySet()) {
            Path relative = file.getKey();
            PositionMapper mapper = new PositionMapper(snapshot.sources().files().get(relative));
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (SukoDiagnostic d : file.getValue().diagnostics().getDiagnostics()) {
                diagnostics.add(toLsp(d, mapper));
            }
            Path absolute = project.root().resolve(relative).toAbsolutePath().normalize();
            result.put(absolute, new Published(uris.uriOf(absolute), diagnostics));
        }
        return result;
    }

    static Diagnostic toLsp(SukoDiagnostic d, PositionMapper mapper) {
        SourceSpan span = d.span();
        Diagnostic out = new Diagnostic(mapper.rangeOf(span), d.message(), severity(d.severity()), "suko");
        out.setCode(d.code());
        return out;
    }

    private static DiagnosticSeverity severity(SukoDiagnostic.Severity severity) {
        return switch (severity) {
            case ERROR -> DiagnosticSeverity.Error;
            case WARNING -> DiagnosticSeverity.Warning;
        };
    }
}
