package io.suko.lang.project;

import io.suko.lang.JteCompiler;
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

    public record ProjectCompileResult(
        boolean success,
        Map<Path, DiagnosticCollector> diagnosticsByFile,
        Map<Path, String> generatedJteSources
    ) {
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
                new JteCompiler(relative.toString(), entry.getValue()).analyze(index, relative);
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
        boolean success = duplicateDiagnostics.isEmpty();

        for (Map.Entry<Path, String> sourceEntry : sources.files().entrySet()) {
            Path relative = sourceEntry.getKey();
            JteCompiler compiler = new JteCompiler(relative.toString(), sourceEntry.getValue());
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
            for (var entry : result.generatedJteSources().entrySet()) {
                generatedJteSources.put(outputSubDir.resolve(entry.getKey()), entry.getValue());
            }
        }

        return new ProjectCompileResult(success, diagnosticsByFile, generatedJteSources);
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
