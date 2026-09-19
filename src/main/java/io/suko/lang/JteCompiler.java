package io.suko.lang;

import io.suko.lang.ast.*;
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

    public JteCompiler(String fileName, String sukoSource) {
        this.fileName = fileName;
        this.sukoSource = sukoSource;
    }

    public record CompileResult(
        boolean success,
        DiagnosticCollector diagnostics,
        Map<String, String> generatedJteSources
    ) {
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
                new SourceSpan(0, 0, 0, 0)
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
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        return emitAll(sukoFile, new JteEmitter(sukoFile.components()));
    }

    /** Consciente de projeto (subprojeto 5): resolve import/visibilidade/
     * nome-composto contra o ProjectIndex de todo o projeto, não só deste
     * ficheiro. Usado por SukoProjectCompiler (Fase 2). */
    public CompileResult compile(ProjectIndex projectIndex, Path fileRelativePath) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SukoFile sukoFile = parseAndBuild(diagnostics);
        if (sukoFile == null) {
            return CompileResult.failure(diagnostics);
        }

        SymbolTable symbolTable = new SymbolTable();
        new SemanticChecker(symbolTable, diagnostics, fileName, projectIndex, fileRelativePath).check(sukoFile);
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        Map<String, ProjectIndexEntry> importedByShortName = projectIndex.resolveImports(sukoFile.imports());
        // TAREFA 8 (subprojeto 5, achado de aceitação — ver comentário do
        // campo currentPackagePrefix em JteEmitter): o prefixo tem de vir do
        // `package` DECLARADO no próprio ficheiro (mesma fonte que
        // ProjectIndex.build usa para construir qualifiedName), não do
        // fileRelativePath diretamente — ambos coincidem em projetos
        // válidos (é exatamente o que PACKAGE_DIRECTORY_MISMATCH garante),
        // mas packageName() é a fonte de verdade semântica.
        String currentPackagePrefix = sukoFile.packageName().map(p -> p + ".").orElse("");
        return emitAll(sukoFile, new JteEmitter(sukoFile.components(), importedByShortName, currentPackagePrefix));
    }

    private CompileResult emitAll(SukoFile sukoFile, JteEmitter emitter) {
        Map<String, String> jteSources = new LinkedHashMap<>();
        for (ComponentDecl component : sukoFile.components()) {
            JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
            jteSources.put(component.name() + ".jte", result.jteSource());
        }
        return CompileResult.success(jteSources);
    }
}