package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;
import io.suko.lang.semantic.SemanticChecker;
import io.suko.lang.symbol.SymbolTable;
import io.suko.lang.diagnostic.SukoErrorListener;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

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
    public CompileResult compile() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();

        // 1. Parse → AST via ANTLR
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));

        // Captura erros de parse com SukoErrorListener
        SukoErrorListener parseErrorListener = new SukoErrorListener(diagnostics, fileName);
        parser.removeErrorListeners();
        parser.addErrorListener(parseErrorListener);

        // Parse tree - armazena o contexto para reutilizar
        SukoParser.CompilationUnitContext compilationUnitCtx = parser.compilationUnit();

        // Se houve erros de parse, já retorna falha
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        // 2. AST → SukoAstBuilder (reutiliza o mesmo contexto de parse)
        SukoAstBuilder astBuilder = new SukoAstBuilder(sukoSource);
        SukoFile sukoFile;
        try {
            sukoFile = astBuilder.build(compilationUnitCtx);
        } catch (IllegalStateException e) {
            diagnostics.add(new SukoDiagnostic(
                Severity.ERROR,
                "Erro ao construir AST: " + e.getMessage(),
                "AST_BUILDER_ERROR",
                fileName,
                new SourceSpan(0, 0, 0, 0)
            ));
            return CompileResult.failure(diagnostics);
        }

        // 3. Tabela de símbolos + Validador semântico
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker semanticChecker = new SemanticChecker(symbolTable, diagnostics, fileName);
        semanticChecker.check(sukoFile);

        // Se houve erros semânticos, retorna falha
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }

        // 4. JTE Emit com source maps
        Map<String, String> jteSources = new LinkedHashMap<>();

        JteEmitter emitter = new JteEmitter(sukoFile.components());
        for (ComponentDecl component : sukoFile.components()) {
            JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
            jteSources.put(component.name() + ".jte", result.jteSource());
        }

        return CompileResult.success(jteSources);
    }
}