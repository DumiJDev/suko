package io.suko.lang.project;

import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

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
        ProjectIndex index = ProjectIndex.build(sourceRoot);

        List<Path> skFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            skFiles = walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Map<Path, DiagnosticCollector> diagnosticsByFile = new LinkedHashMap<>();
        Map<Path, String> generatedJteSources = new LinkedHashMap<>();
        boolean success = true;

        // REVISÃO FINAL (achado C): colisões de nome qualificado detetadas na
        // Fase 1 são reportadas nos DOIS ficheiros envolvidos, antes de
        // qualquer emissão. Sem isto, o segundo componente sobrescrevia o
        // primeiro em silêncio (índice E mapa de .jte gerados) com
        // success=true e zero diagnósticos — perda de código silenciosa.
        Map<Path, List<SukoDiagnostic>> duplicateDiagnostics = new LinkedHashMap<>();
        for (ProjectIndex.DuplicateComponent duplicate : index.duplicates()) {
            success = false;
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

        for (Path skFile : skFiles) {
            Path relative = sourceRoot.relativize(skFile);
            String source;
            try {
                source = Files.readString(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            JteCompiler compiler = new JteCompiler(relative.toString(), source);
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
}
