package io.suko.lang.project;

import io.suko.ext.ProjectEmitContext;
import io.suko.ext.ProjectOutput;
import io.suko.ext.SecurityOptions;
import io.suko.ext.Target;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ext.ExtensionFailures;
import io.suko.lang.ext.VocabularyChecker;
import io.suko.lang.ext.ExtensionRegistry;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orquestra a Fase 1 (ProjectIndex.build) + a Fase 2 (JteCompiler por
 * ficheiro, com o índice injetado) — não é um fork de JteCompiler, reusa-o.
 * Cada .jte gerado é escrito numa chave que espelha a pasta do ficheiro
 * .sk de origem (ex. "ui/NavLink.jte"), fechando o bug de
 * TemplateNotFoundException em nomes compostos documentado no
 * ARCHITECTURE.md (gg.jte já lê o ponto de @template.ui.NavLink(...)
 * como separador de path — só faltava o .jte existir nessa subpasta).
 */
public class SukoProjectCompiler {

    private final ExtensionRegistry registry;
    private final List<String> targets;
    private final SecurityOptions options;

    public SukoProjectCompiler() {
        this(ExtensionRegistry.defaults(), List.of("jte"));
    }

    public SukoProjectCompiler(ExtensionRegistry registry, List<String> targets) {
        this(registry, targets, SecurityOptions.DEFAULT);
    }

    public SukoProjectCompiler(ExtensionRegistry registry, List<String> targets, SecurityOptions options) {
        this.options = options;
        this.registry = registry;
        this.targets = JteCompiler.normalizeTargets(targets);
    }

    public record ProjectCompileResult(
        boolean success,
        Map<Path, DiagnosticCollector> diagnosticsByFile,
        Map<Path, String> generatedJteSources,
        List<SukoDiagnostic> projectDiagnostics,
        List<Output> projectOutputs
    ) {
        /** Ficheiro de projeto emitido por um alvo (ex.: a SukoSafe.java do alvo JTE). */
        public record Output(String targetId, ProjectOutput.Kind kind, Path relativePath, String source) {
        }

        public ProjectCompileResult(boolean success, Map<Path, DiagnosticCollector> diagnosticsByFile,
                                    Map<Path, String> generatedJteSources, List<SukoDiagnostic> projectDiagnostics) {
            this(success, diagnosticsByFile, generatedJteSources, projectDiagnostics, List.of());
        }

        public ProjectCompileResult(boolean success, Map<Path, DiagnosticCollector> diagnosticsByFile,
                                    Map<Path, String> generatedJteSources) {
            this(success, diagnosticsByFile, generatedJteSources, List.of());
        }
    }

    /** Problemas do registo e alvos/vocabulários pedidos que não existem. */
    public List<SukoDiagnostic> projectDiagnostics() {
        List<SukoDiagnostic> out = new ArrayList<>(registry.loadDiagnostics());
        for (String id : targets) {
            var target = registry.target(id);
            if (target.isEmpty()) {
                out.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "O alvo '" + id + "' não é fornecido por nenhuma extensão; disponíveis: "
                        + String.join(", ", new java.util.TreeSet<>(registry.targetIds())),
                    "TARGET_NOT_FOUND", null, io.suko.lang.ast.SourceSpan.NONE));
                continue;
            }
            java.util.Set<String> vocabularyIds;
            try {
                vocabularyIds = new java.util.TreeSet<>(target.get().vocabularies());
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                out.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "A extensão '" + registry.ownerOf(target.get()) + "' falhou ao listar os vocabulários do alvo '"
                        + id + "': " + io.suko.lang.ext.VocabularyChecker.describe(e),
                    "EXTENSION_FAILED", null, io.suko.lang.ast.SourceSpan.NONE));
                continue;
            }
            for (String vocabulary : vocabularyIds) {
                if (registry.vocabulary(vocabulary).isEmpty()) {
                    out.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                        "O alvo '" + id + "' usa o vocabulário '" + vocabulary + "', que nenhuma extensão fornece",
                        "VOCABULARY_NOT_FOUND", null, io.suko.lang.ast.SourceSpan.NONE));
                }
            }
        }
        return out;
    }

    public ProjectCompileResult compile(Path sourceRoot) {
        return compile(SukoSources.fromDirectory(sourceRoot));
    }

    /** Só verificação (parse + índice + semântica), sem {@code JteEmitter}:
     * o que o language server precisa. Os diagnósticos são os mesmos de
     * {@link #compile(SukoSources)} — incluindo DUPLICATE_COMPONENT. */
    public ProjectAnalysis analyze(SukoSources sources) {
        ProjectIndex index = ProjectIndex.build(sources);
        Map<Path, List<SukoDiagnostic>> duplicateDiagnostics = duplicateDiagnostics(index, sources.root());

        Map<Path, ProjectAnalysis.FileAnalysis> files = new LinkedHashMap<>();
        for (Map.Entry<Path, String> entry : sources.files().entrySet()) {
            Path relative = entry.getKey();
            JteCompiler.Analysis analysis =
                new JteCompiler(relative.toString(), entry.getValue(), registry, targets, options).analyze(index, relative);
            for (SukoDiagnostic duplicate : duplicateDiagnostics.getOrDefault(relative, List.of())) {
                analysis.diagnostics().add(duplicate);
            }
            files.put(relative, new ProjectAnalysis.FileAnalysis(analysis.ast(), analysis.diagnostics()));
        }
        return new ProjectAnalysis(index, files);
    }

    public ProjectCompileResult compile(SukoSources sources) {
        ProjectIndex index = ProjectIndex.build(sources);
        Map<Path, List<SukoDiagnostic>> duplicateDiagnostics = duplicateDiagnostics(index, sources.root());

        Map<Path, DiagnosticCollector> diagnosticsByFile = new LinkedHashMap<>();
        Map<Path, String> generatedJteSources = new LinkedHashMap<>();
        List<SukoDiagnostic> project = projectDiagnostics();
        boolean success = duplicateDiagnostics.isEmpty()
            && project.stream().noneMatch(d -> d.severity() == SukoDiagnostic.Severity.ERROR);

        for (Map.Entry<Path, String> sourceEntry : sources.files().entrySet()) {
            Path relative = sourceEntry.getKey();
            JteCompiler compiler = new JteCompiler(relative.toString(), sourceEntry.getValue(), registry, targets, options);
            JteCompiler.CompileResult result = compiler.compile(index, relative);
            DiagnosticCollector fileDiagnostics = result.diagnostics();
            for (SukoDiagnostic duplicate : duplicateDiagnostics.getOrDefault(relative, List.of())) {
                fileDiagnostics.add(duplicate);
            }
            diagnosticsByFile.put(relative, fileDiagnostics);

            if (!result.success() || fileDiagnostics.hasErrors()) {
                success = false;
                continue;
            }

            Path outputSubDir = relative.getParent() == null ? Path.of("") : relative.getParent();
            boolean multi = targets.size() > 1;
            for (var targetEntry : result.generatedByTarget().entrySet()) {
                Path base = multi ? Path.of(targetEntry.getKey()).resolve(outputSubDir) : outputSubDir;
                for (var entry : targetEntry.getValue().entrySet()) {
                    generatedJteSources.put(base.resolve(entry.getKey()), entry.getValue());
                }
            }
        }

        List<ProjectCompileResult.Output> projectOutputs = new ArrayList<>();
        if (success) {
            for (String targetId : targets) {
                Target target = registry.target(targetId).orElse(null);
                if (target == null) continue;
                try {
                    for (ProjectOutput out : target.emitProject(new ProjectEmitContext(index, options))) {
                        projectOutputs.add(new ProjectCompileResult.Output(
                            targetId, out.kind(), Path.of(out.relativePath()), out.source()));
                    }
                } catch (Throwable e) {
                    ExtensionFailures.rethrowFatal(e);
                    project = withExtra(project, new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                        "A extensão '" + registry.ownerOf(target) + "' falhou ao emitir os ficheiros do projeto para o alvo '"
                            + targetId + "': " + VocabularyChecker.describe(e), "EXTENSION_FAILED", null, SourceSpan.NONE));
                    success = false;
                }
            }
        }
        return new ProjectCompileResult(success, diagnosticsByFile, generatedJteSources, project, projectOutputs);
    }

    private static List<SukoDiagnostic> withExtra(List<SukoDiagnostic> diagnostics, SukoDiagnostic extra) {
        List<SukoDiagnostic> copy = new ArrayList<>(diagnostics);
        copy.add(extra);
        return copy;
    }

    // REVISÃO FINAL (achado C): colisões de nome qualificado detetadas na
    // Fase 1 são reportadas nos DOIS ficheiros envolvidos, antes de qualquer
    // emissão. Sem isto, o segundo componente sobrescrevia o primeiro em
    // silêncio (índice E mapa de .jte gerados) com success=true.
    private static Map<Path, List<SukoDiagnostic>> duplicateDiagnostics(ProjectIndex index, Path sourceRoot) {
        Map<Path, List<SukoDiagnostic>> duplicateDiagnostics = new LinkedHashMap<>();
        for (ProjectIndex.DuplicateComponent duplicate : index.duplicates()) {
            Path first = sourceRoot.relativize(duplicate.firstFile());
            Path second = sourceRoot.relativize(duplicate.secondFile());
            String message = "Componente duplicado: '" + duplicate.qualifiedName()
                + "' está declarado em '" + first + "' e em '" + second + "'";
            duplicateDiagnostics.computeIfAbsent(first, k -> new ArrayList<>()).add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR, message, "DUPLICATE_COMPONENT",
                first.toString(), duplicate.firstSpan()));
            duplicateDiagnostics.computeIfAbsent(second, k -> new ArrayList<>()).add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR, message, "DUPLICATE_COMPONENT",
                second.toString(), duplicate.secondSpan()));
        }
        return duplicateDiagnostics;
    }
}
