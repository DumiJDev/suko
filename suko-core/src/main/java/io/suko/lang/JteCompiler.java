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