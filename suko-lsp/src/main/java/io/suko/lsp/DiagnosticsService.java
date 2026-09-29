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
    /** Último conjunto publicado por projecto: URI → diagnósticos (para não repetir e para limpar). */
    private final Map<Project, Map<String, List<Diagnostic>>> published = new IdentityHashMap<>();
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
        } catch (RuntimeException e) {
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
        Map<String, List<Diagnostic>> next = convert(project, snapshot);

        Map<String, List<Diagnostic>> previous;
        synchronized (this) {
            previous = published.getOrDefault(project, Map.of());
            published.put(project, next);
        }
        for (Map.Entry<String, List<Diagnostic>> entry : next.entrySet()) {
            if (!entry.getValue().equals(previous.get(entry.getKey()))) {
                target.publishDiagnostics(new PublishDiagnosticsParams(entry.getKey(), entry.getValue()));
            }
        }
        for (String uri : previous.keySet()) {
            if (!next.containsKey(uri)) {
                target.publishDiagnostics(new PublishDiagnosticsParams(uri, List.of()));
            }
        }
    }

    private Map<String, List<Diagnostic>> convert(Project project, Project.Snapshot snapshot) {
        Map<String, List<Diagnostic>> result = new HashMap<>();
        ProjectAnalysis analysis = snapshot.analysis();
        for (Map.Entry<Path, ProjectAnalysis.FileAnalysis> file : analysis.files().entrySet()) {
            Path relative = file.getKey();
            PositionMapper mapper = new PositionMapper(snapshot.sources().files().get(relative));
            List<Diagnostic> diagnostics = new ArrayList<>();
            for (SukoDiagnostic d : file.getValue().diagnostics().getDiagnostics()) {
                diagnostics.add(toLsp(d, mapper));
            }
            result.put(uris.uriOf(project.root().resolve(relative)), diagnostics);
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
