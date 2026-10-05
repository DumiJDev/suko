package io.suko.lang;

import io.suko.ext.*;
import io.suko.lang.ast.*;
import io.suko.lang.ext.ExtensionRegistry;
import io.suko.lang.ext.VocabularyChecker;
import io.suko.lang.project.ProjectView;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.semantic.SemanticChecker;
import io.suko.lang.symbol.SymbolTable;
import io.suko.lang.diagnostic.SukoErrorListener;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public class JteCompiler {

    private final String fileName;
    private final String sukoSource;
    private final ExtensionRegistry registry;
    private final List<String> targets;
    private final SecurityOptions options;

    public JteCompiler(String fileName, String sukoSource) {
        this(fileName, sukoSource, ExtensionRegistry.defaults(), List.of("jte"));
    }

    /** Sem duplicados (ordem preservada); lista vazia vale ["jte"]. Partilhado por Gradle, Maven e LSP. */
    public static List<String> normalizeTargets(List<String> requested) {
        List<String> distinct = requested.stream().distinct().toList();
        return distinct.isEmpty() ? List.of("jte") : distinct;
    }

    public JteCompiler(String fileName, String sukoSource, ExtensionRegistry registry, List<String> targets) {
        this(fileName, sukoSource, registry, targets, SecurityOptions.DEFAULT);
    }

    public JteCompiler(String fileName, String sukoSource, ExtensionRegistry registry, List<String> targets,
                       SecurityOptions options) {
        this.options = options;
        this.fileName = fileName;
        this.sukoSource = sukoSource;
        this.registry = registry;
        this.targets = normalizeTargets(targets);
    }

    public record CompileResult(
        boolean success,
        DiagnosticCollector diagnostics,
        Map<String, String> generatedJteSources,
        Map<String, Map<String, String>> generatedByTarget
    ) {
        public CompileResult(boolean success, DiagnosticCollector diagnostics, Map<String, String> generatedJteSources) {
            this(success, diagnostics, generatedJteSources, Map.of());
        }

        public static CompileResult success(Map<String, String> jteSources) {
            return new CompileResult(true, new DiagnosticCollector(), jteSources);
        }

        public static CompileResult failure(DiagnosticCollector diagnostics) {
            return new CompileResult(false, diagnostics, Map.of());
        }
    }

    /**
     * Executa o pipeline completo de compilação Suko.
     * <ol>
     *   <li>Parse → AST via ANTLR</li>
     *   <li>Diagnóstico de erros de parse (SukoErrorListener)</li>
     *   <li>Análise semântica (SymbolTable + SemanticChecker)</li>
     *   <li>JTE Emit (JteEmitter)</li>
     *   <li>Se houver erros em qualquer etapa: abortar</li>
     *   <li>Senão: return CompileResult.success com sources .jte gerados</li>
     * </ol>
     */
    private SukoFile parseAndBuild(DiagnosticCollector diagnostics) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));

        SukoErrorListener parseErrorListener = new SukoErrorListener(diagnostics, fileName);
        parser.removeErrorListeners();
        parser.addErrorListener(parseErrorListener);

        SukoParser.CompilationUnitContext compilationUnitCtx = parser.compilationUnit();
        if (diagnostics.hasErrors()) {
            return null;
        }

        try {
            return new SukoAstBuilder(sukoSource).build(compilationUnitCtx);
        } catch (IllegalStateException e) {
            diagnostics.add(new SukoDiagnostic(
                Severity.ERROR,
                "Erro ao construir AST: " + e.getMessage(),
                "AST_BUILDER_ERROR",
                fileName,
                SourceSpan.NONE
            ));
            return null;
        }
    }

    public CompileResult compile() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SukoFile sukoFile = parseAndBuild(diagnostics);
        if (sukoFile == null) {
            return CompileResult.failure(diagnostics);
        }

        SymbolTable symbolTable = new SymbolTable();
        new SemanticChecker(symbolTable, diagnostics, fileName).check(sukoFile);
        runExtensionChecks(sukoFile, ProjectView.EMPTY, diagnostics);
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        for (String targetId : targets) {
            if (registry.target(targetId).isEmpty()) {
                diagnostics.add(new SukoDiagnostic(Severity.ERROR,
                    "O alvo '" + targetId + "' não é fornecido por nenhuma extensão; disponíveis: "
                        + String.join(", ", new java.util.TreeSet<>(registry.targetIds())),
                    "TARGET_NOT_FOUND", fileName, SourceSpan.NONE));
            }
        }
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }
        return emitAll(sukoFile, ProjectView.EMPTY, Map.of(), "", diagnostics);
    }

    /** Parse + verificação semântica consciente de projeto, sem emissão.
     * {@code ast} é {@code null} quando o ficheiro não faz parse; os
     * diagnósticos são exatamente os que {@link #compile(ProjectIndex, Path)}
     * reporta (o language server usa esta entrada, o build usa aquela). */
    public record Analysis(SukoFile ast, DiagnosticCollector diagnostics) {
    }

    public Analysis analyze(ProjectIndex projectIndex, Path fileRelativePath) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SukoFile sukoFile = parseAndBuild(diagnostics);
        if (sukoFile == null) {
            return new Analysis(null, diagnostics);
        }
        SymbolTable symbolTable = new SymbolTable();
        new SemanticChecker(symbolTable, diagnostics, fileName, projectIndex, fileRelativePath).check(sukoFile);
        runExtensionChecks(sukoFile, projectIndex, diagnostics);
        return new Analysis(sukoFile, diagnostics);
    }

    /** Consciente de projeto (subprojeto 5): resolve import/visibilidade/
     * nome-composto contra o ProjectIndex de todo o projeto, não só deste
     * ficheiro. Usado por SukoProjectCompiler (Fase 2). */
    public CompileResult compile(ProjectIndex projectIndex, Path fileRelativePath) {
        Analysis analysis = analyze(projectIndex, fileRelativePath);
        DiagnosticCollector diagnostics = analysis.diagnostics();
        SukoFile sukoFile = analysis.ast();
        if (sukoFile == null || diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        Map<String, ProjectIndexEntry> importedByShortName = projectIndex.resolveImports(sukoFile.imports());
        // REVISÃO FINAL (subprojeto 5, achado B): o prefixo vem da PASTA
        // relativa do ficheiro — a mesma fonte que ProjectIndex.build usa
        // para construir o qualifiedName e a mesma que o SukoProjectCompiler
        // usa para escolher a subpasta do .jte gerado. Usar o `package`
        // DECLARADO (como na Tarefa 8) divergia da pasta real exatamente no
        // caso de um ficheiro SEM `package` dentro de uma subpasta: o .jte
        // ia para sub/X.jte mas a chamada emitida era @template.X(...),
        // resolvida pelo gg.jte na raiz -> TemplateNotFoundException com
        // success=true e zero diagnósticos. Para um ficheiro com `package`
        // correto o valor é idêntico (PACKAGE_DIRECTORY_MISMATCH garante-o).
        String currentPackagePrefix = ProjectIndex.relativeDirToPackagePrefix(
            fileRelativePath.getParent() == null ? Path.of("") : fileRelativePath.getParent());
        return emitAll(sukoFile, projectIndex, importedByShortName, currentPackagePrefix, diagnostics);
    }

    private void runExtensionChecks(SukoFile sukoFile, ProjectView project, DiagnosticCollector diagnostics) {
        List<Target> resolved = targets.stream().flatMap(id -> registry.target(id).stream()).toList();
        VocabularyChecker.check(sukoFile, fileName, resolved, registry, diagnostics);
        java.util.Set<String> active = new java.util.TreeSet<>();
        for (Target t : resolved) {
            try {
                active.addAll(t.vocabularies());
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                // VocabularyChecker.check já reporta o EXTENSION_FAILED deste alvo
            }
        }
        CheckContext checkContext = new CheckContext(fileName, project, diagnostics, options,
            java.util.Collections.unmodifiableSet(active));
        for (Checker checker : registry.checkers()) {
            try {
                checker.check(sukoFile, checkContext);
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                diagnostics.add(new SukoDiagnostic(Severity.ERROR,
                    "A extensão '" + registry.ownerOf(checker) + "' falhou em " + fileName + " (" + checker.id()
                        + "): " + VocabularyChecker.describe(e), "EXTENSION_FAILED", fileName, SourceSpan.NONE));
            }
        }
    }

    private CompileResult emitAll(SukoFile sukoFile, ProjectView project,
                                  Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix,
                                  DiagnosticCollector diagnostics) {
        Map<String, Map<String, String>> byTarget = new LinkedHashMap<>();
        EmitContext ctx = new EmitContext(sukoFile, project, importedByShortName, packagePrefix, options);
        for (String targetId : targets) {
            Target target = registry.target(targetId).orElse(null);
            if (target == null) {
                continue; // TARGET_NOT_FOUND é diagnóstico de projeto (SukoProjectCompiler)
            }
            Map<String, String> outputs = new LinkedHashMap<>();
            for (ComponentDecl component : sukoFile.components()) {
                try {
                    Emitted emitted = target.emit(component, ctx);
                    outputs.put(emitted.relativePath(), emitted.source());
                } catch (Throwable e) {
                    io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                    diagnostics.add(new SukoDiagnostic(Severity.ERROR,
                        "A extensão '" + registry.ownerOf(target) + "' falhou ao emitir " + component.name()
                            + " para o alvo '" + targetId + "': " + VocabularyChecker.describe(e),
                        "EXTENSION_FAILED", fileName, component.span()));
                }
            }
            byTarget.put(targetId, outputs);
        }
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }
        Map<String, String> first = byTarget.isEmpty() ? Map.of() : byTarget.values().iterator().next();
        return new CompileResult(true, diagnostics, first, byTarget);
    }
}
