package io.suko.lang.semantic;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.symbol.SymbolTable;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Verificador semântico para o Subprojeto 2 — Verificador Suko.
 * Percorre o AST e verifica:
 * - Componentes chamados existem
 * - Slot fills correspondem a slots declarados
 * - Cardinalidade de slots é respeitada
 * - Slots obrigatórios são preenchidos
 */
public class SemanticChecker {

    private final SymbolTable symbolTable;
    private final DiagnosticCollector diagnostics;
    private final String sourceFile;
    private final ProjectIndex projectIndex;
    private final Path fileRelativePath;
    private Map<String, ProjectIndexEntry> currentImportedByShortName = Map.of();
    /** Nomes curtos cujo import resolveu para um componente NÃO public: o
     * COMPONENT_NOT_VISIBLE já foi reportado na linha do import, por isso as
     * chamadas a estes nomes não reportam nada (revisão final, achado G). */
    private final Set<String> nonVisibleImportedNames = new HashSet<>();

    public SemanticChecker(SymbolTable symbolTable, DiagnosticCollector diagnostics, String sourceFile) {
        this(symbolTable, diagnostics, sourceFile, null, null);
    }

    public SemanticChecker(SymbolTable symbolTable, DiagnosticCollector diagnostics, String sourceFile,
                            ProjectIndex projectIndex, Path fileRelativePath) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.sourceFile = sourceFile;
        this.projectIndex = projectIndex;
        this.fileRelativePath = fileRelativePath;
    }

    /** Executa a verificação semântica completa em um SukoFile. */
    public void check(SukoFile sukoFile) {
        registerComponents(sukoFile);
        nonVisibleImportedNames.clear();
        currentImportedByShortName = projectIndex == null ? Map.of() : checkImportsAndBuildAliasMap(sukoFile);
        if (projectIndex != null) {
            checkPackageDirectoryMismatch(sukoFile);
        }
        for (ComponentDecl component : sukoFile.components()) {
            checkComponent(component);
        }
    }

    private Map<String, ProjectIndexEntry> checkImportsAndBuildAliasMap(SukoFile sukoFile) {
        Map<String, ProjectIndexEntry> byShortName = new HashMap<>();
        for (ImportDecl imp : sukoFile.imports()) {
            var found = projectIndex.resolveQualified(imp.qualifiedName());
            if (found.isEmpty()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import não encontrado: '" + imp.qualifiedName() + "'",
                        "IMPORT_NOT_FOUND",
                        sourceFile,
                        imp.span()
                ));
                continue;
            }
            ProjectIndexEntry entry = found.get();
            if (!entry.isPublic()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + imp.qualifiedName() + "' não é public — não pode ser importado",
                        "COMPONENT_NOT_VISIBLE",
                        sourceFile,
                        imp.span()
                ));
                // REVISÃO FINAL (achado G): não inserir a entrada não-public no
                // mapa de aliases. Já reportámos COMPONENT_NOT_VISIBLE aqui, na
                // linha do import — deixá-la no mapa fazia o mesmo problema
                // disparar OUTRA VEZ em cada chamada. O nome curto fica
                // registado em `nonVisibleImportedNames` para que a chamada
                // também não caia num COMPONENT_NOT_FOUND espúrio: exatamente
                // um diagnóstico por problema real.
                nonVisibleImportedNames.add(imp.alias().orElse(entry.simpleName()));
                continue;
            }
            String key = imp.alias().orElse(entry.simpleName());
            if (imp.alias().isEmpty() && byShortName.containsKey(key)) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import ambíguo: '" + key + "' já foi importado de outro pacote — use 'as' para desambiguar",
                        "AMBIGUOUS_IMPORT",
                        sourceFile,
                        imp.span()
                ));
            } else {
                byShortName.put(key, entry);
            }
        }
        return byShortName;
    }

    private void checkPackageDirectoryMismatch(SukoFile sukoFile) {
        if (sukoFile.packageName().isEmpty()) {
            return;
        }
        Path expectedDir = ProjectIndex.packageToRelativeDir(sukoFile.packageName().get());
        Path actualDir = fileRelativePath.getParent() == null ? Path.of("") : fileRelativePath.getParent();
        if (!expectedDir.equals(actualDir)) {
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "package " + sukoFile.packageName().get() + " não corresponde à pasta do ficheiro ('" + actualDir + "')",
                    "PACKAGE_DIRECTORY_MISMATCH",
                    sourceFile,
                    // Revisão final, achado F: posição real da declaração
                    // `package` (antes era sempre 0:0). O fallback só existe
                    // para SukoFiles construídos à mão (testes de unidade).
                    sukoFile.packageSpan().orElseGet(() -> new SourceSpan(0, 0, 0, 0))
            ));
        }
    }

    private void registerComponents(SukoFile sukoFile) {
        for (ComponentDecl component : sukoFile.components()) {
            symbolTable.register(component);
        }
    }

    private void checkComponent(ComponentDecl component) {
        Map<String, Param.SlotParam> declaredSlots = new HashMap<>();
        for (Param param : component.params()) {
            if ("children".equals(param.name()) && !(param instanceof Param.SlotParam)) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "'children' é um nome reservado: o parâmetro tem de ser Component ou List<Component>",
                        "RESERVED_CHILDREN_NAME",
                        sourceFile,
                        paramSpan(param)
                ));
            }
            if (param instanceof Param.SlotParam slotParam) {
                declaredSlots.put(slotParam.name(), slotParam);
            }
        }
        for (Statement statement : component.body()) {
            checkStatement(statement, declaredSlots);
        }
        checkSyntaxSurface(component);
    }

    private static final java.util.regex.Pattern BARE_BRACE_IDENT =
            java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)\\}");

    /** Deteção de `{ident}` mal-escrito dentro de uma string literal, onde
     * `ident` coincide com um parâmetro real do componente atual — quase
     * certamente o autor queria `${ident}` (interpolação), mas `{...}`
     * dentro de uma string é, por design, texto literal (ver ARCHITECTURE.md:
     * Alpine.js `x-data="{ open: false }"`, custom properties CSS, JS
     * inline). */
    /** Diagnósticos de SINTAXE sobre o corpo de um componente
     * (`BARE_BRACE_IN_STRING`, `VAR_DECL_NOT_PARSED`, e os de forma legada
     * do subprojeto 9). Renomeado de `checkBareBraceInStrings` quando
     * deixou de ser só sobre chavetas em strings. */
    private void checkSyntaxSurface(ComponentDecl component) {
        java.util.Set<String> paramNames = component.params().stream()
                .map(Param::name).collect(java.util.stream.Collectors.toSet());
        checkSyntaxInStatements(component.body(), paramNames);
    }

    private void checkSyntaxInStatements(List<Statement> statements, java.util.Set<String> paramNames) {
        for (Statement statement : statements) {
            checkSyntaxInStatement(statement, paramNames);
        }
    }

    // EXAUSTIVO SOBRE AS 8 VARIANTES DE `Statement`, sem `default` — de
    // propósito. A versão anterior tinha `default -> {}` e não descia a
    // if/for/switch, e nenhum percurso do checker descia a corpos de slot
    // fill: Alert.sk, Badge.sk e Button.sk têm o corpo INTEIRO dentro de um
    // switch, logo o BARE_BRACE_IN_STRING nunca os via. Sem `default`, uma
    // variante nova de Statement passa a ser erro de compilação aqui, em
    // vez de um buraco silencioso.
    private void checkSyntaxInStatement(Statement statement, java.util.Set<String> paramNames) {
        switch (statement) {
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    checkBareBraceInExpr(attribute.value(), paramNames, attribute.span());
                }
                checkSyntaxInStatements(element.children(), paramNames);
            }
            case Statement.Interpolation interpolation ->
                checkBareBraceInExpr(interpolation.expr(), paramNames, interpolation.span());
            case Statement.TextRun textRun -> checkSwallowedVarDecl(textRun);
            case Statement.VarDecl varDecl ->
                checkBareBraceInExpr(varDecl.value(), paramNames, varDecl.span());
            case Statement.IfStmt ifStmt -> {
                checkBareBraceInExpr(ifStmt.condition(), paramNames, ifStmt.span());
                checkSyntaxInStatements(ifStmt.thenBranch(), paramNames);
                checkSyntaxInStatements(ifStmt.elseBranch(), paramNames);
            }
            case Statement.ForStmt forStmt -> {
                checkBareBraceInExpr(forStmt.iterable(), paramNames, forStmt.span());
                checkSyntaxInStatements(forStmt.body(), paramNames);
            }
            case Statement.SwitchStmt switchStmt -> {
                checkBareBraceInExpr(switchStmt.subject(), paramNames, switchStmt.span());
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    checkBareBraceInExpr(switchCase.matchValue(), paramNames, switchStmt.span());
                    checkSyntaxInStatements(switchCase.body(), paramNames);
                }
                checkSyntaxInStatements(switchStmt.defaultCase(), paramNames);
            }
            case Statement.ComponentCallStmt call -> {
                for (Statement.Arg arg : call.args()) {
                    checkBareBraceInExpr(arg.value(), paramNames, call.span());
                }
                for (Statement.SlotFill fill : call.slotFills()) {
                    checkSyntaxInStatements(fill.body(), paramNames);
                }
            }
        }
    }

    private static final java.util.regex.Pattern SWALLOWED_VAR_DECL =
            java.util.regex.Pattern.compile("\\bvar\\s+[a-zA-Z_][a-zA-Z0-9_]*\\s*=\\s*([A-Za-z_][a-zA-Z0-9_]*)\\s*\\(");

    /** Heurística (revisão final do subprojeto 6, achado 5): uma declaração
     * `var` bem formada NUNCA chega ao AST como texto — vira
     * `Statement.VarDecl`. Quando o texto literal de um `textRun` contém
     * `var x = Componente(`, é quase certo que o autor escreveu a forma
     * inválida `var c = Card() { ... };` (chamada de componente como VALOR
     * com um bloco de slot, que a gramática não suporta): o `textRun` guloso
     * engole `var c = Card() ` como texto solto e o `{ ... }` vira uma
     * interpolação comum. Sem este aviso, o resultado é HTML corrompido em
     * silêncio (ou um erro de compilação do .jte, se a variável for lida
     * depois). Só dispara quando o nome chamado é um componente REAL
     * conhecido, para não marcar JavaScript inline legítimo. */
    private void checkSwallowedVarDecl(Statement.TextRun textRun) {
        var matcher = SWALLOWED_VAR_DECL.matcher(textRun.text());
        while (matcher.find()) {
            String calleeName = matcher.group(1);
            if (symbolTable.lookup(calleeName) == null) {
                continue;
            }
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.WARNING,
                    "Declaração 'var' não reconhecida — foi lida como texto literal. Uma chamada de componente usada como VALOR "
                            + "não pode levar um bloco de slot: escreva 'var x = " + calleeName + "(...);' sem '{ ... }' "
                            + "(ver ARCHITECTURE.md, limitações conhecidas)",
                    "VAR_DECL_NOT_PARSED",
                    sourceFile,
                    textRun.span()
            ));
        }
    }

    private void checkBareBraceInExpr(Expr expr, java.util.Set<String> paramNames, SourceSpan span) {
        if (!(expr instanceof Expr.StringLiteralExpr stringLiteral)) {
            return;
        }
        for (Expr.StringPart part : stringLiteral.parts()) {
            if (!(part instanceof Expr.StringPart.Literal literal)) {
                continue;
            }
            var matcher = BARE_BRACE_IDENT.matcher(literal.javaEscapedText());
            while (matcher.find()) {
                String ident = matcher.group(1);
                if (paramNames.contains(ident)) {
                    diagnostics.add(new SukoDiagnostic(
                            SukoDiagnostic.Severity.ERROR,
                            "'{" + ident + "}' dentro de uma string é texto literal — use '${" + ident + "}' para interpolar",
                            "BARE_BRACE_IN_STRING",
                            sourceFile,
                            span
                    ));
                }
            }
        }
    }

    private SourceSpan paramSpan(Param param) {
        return switch (param) {
            case Param.ValueParam p -> p.span();
            case Param.SlotParam p -> p.span();
        };
    }

    private void checkStatement(Statement statement, Map<String, Param.SlotParam> currentScopeSlots) {
        switch (statement) {
            case Statement.ComponentCallStmt call -> checkComponentCall(call, currentScopeSlots);
            case Statement.VarDecl varDecl -> checkExprForComponentCalls(varDecl.value());
            case Statement.Interpolation interpolation -> checkExprForComponentCalls(interpolation.expr());
            case Statement.IfStmt ifStmt -> {
                checkExprForComponentCalls(ifStmt.condition());
                checkStatementList(ifStmt.thenBranch(), currentScopeSlots);
                checkStatementList(ifStmt.elseBranch(), currentScopeSlots);
            }
            case Statement.ForStmt forStmt -> checkStatementList(forStmt.body(), currentScopeSlots);
            case Statement.SwitchStmt switchStmt -> {
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    checkStatementList(switchCase.body(), currentScopeSlots);
                }
                checkStatementList(switchStmt.defaultCase(), currentScopeSlots);
            }
            // R2 (subprojeto 7): sem este caso, os children() de uma tag nunca
            // eram percorridos — e como quase toda a chamada real em Suko é
            // escrita dentro de uma tag, COMPONENT_NOT_FOUND /
            // COMPONENT_NOT_VISIBLE / SLOT_NOT_FOUND / CARDINALITY_VIOLATION
            // eram trivialmente contornáveis. Percurso simétrico ao que
            // checkSyntaxInStatement já fazia ao lado.
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    checkExprForComponentCalls(attribute.value());
                }
                checkStatementList(element.children(), currentScopeSlots);
            }
            default -> {}
        }
    }

    /** Só desce o suficiente para achar CallExpr(callee=PrimaryExpr) top-level
     * dentro de ternários/parênteses — mesma regra estrutural do JteEmitter
     * (tarefa 5): não é uma resolução de tipos completa. */
    private void checkExprForComponentCalls(Expr expr) {
        switch (expr) {
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p -> {
                ComponentDecl target = symbolTable.lookup(p.text());
                if (target == null) {
                    ProjectIndexEntry resolved = resolveViaProject(p.text());
                    // `nonVisibleImportedNames`: COMPONENT_NOT_VISIBLE já foi
                    // reportado na linha do import (achado G) — não repetir,
                    // nem trocar por um COMPONENT_NOT_FOUND espúrio.
                    if (resolved == null && !nonVisibleImportedNames.contains(p.text())
                            && looksLikeComponentName(p.text())) {
                        diagnostics.add(new SukoDiagnostic(
                                SukoDiagnostic.Severity.ERROR,
                                "Componente '" + p.text() + "' não encontrado",
                                "COMPONENT_NOT_FOUND",
                                sourceFile,
                                call.span()
                        ));
                    } else if (resolved != null && !resolved.isPublic()) {
                        diagnostics.add(new SukoDiagnostic(
                                SukoDiagnostic.Severity.ERROR,
                                "Componente '" + p.text() + "' não é public",
                                "COMPONENT_NOT_VISIBLE",
                                sourceFile,
                                call.span()
                        ));
                    }
                }
            }
            case Expr.TernaryExpr ternary -> {
                checkExprForComponentCalls(ternary.condition());
                checkExprForComponentCalls(ternary.whenTrue());
                checkExprForComponentCalls(ternary.whenFalse());
            }
            case Expr.ParenExpr paren -> checkExprForComponentCalls(paren.inner());
            default -> {}
        }
    }

    /** Distingue "provável chamada de componente" de uma chamada de método/
     * função Java qualquer: convenção do projeto (ver ComponentDecl) é nome de
     * componente começar por maiúscula — mesma convenção já usada em todos os
     * exemplos e specs. Evita falso positivo em `toUpperCase()` etc. */
    private boolean looksLikeComponentName(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private void checkStatementList(List<Statement> statements, Map<String, Param.SlotParam> currentScopeSlots) {
        for (Statement statement : statements) {
            checkStatement(statement, currentScopeSlots);
        }
    }

    private ProjectIndexEntry resolveViaProject(String name) {
        if (projectIndex == null) {
            return null;
        }
        if (name.contains(".")) {
            return projectIndex.resolveQualified(name).orElse(null);
        }
        return currentImportedByShortName.get(name);
    }

    private void checkComponentCall(Statement.ComponentCallStmt call, Map<String, Param.SlotParam> currentScopeSlots) {
        ComponentDecl calledComponent = symbolTable.lookup(call.componentName());
        if (calledComponent == null) {
            ProjectIndexEntry resolved = resolveViaProject(call.componentName());
            if (resolved == null && nonVisibleImportedNames.contains(call.componentName())) {
                // COMPONENT_NOT_VISIBLE já reportado na linha do import (achado G)
                return;
            }
            if (resolved == null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + call.componentName() + "' não encontrado",
                        "COMPONENT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
                return;
            }
            if (!resolved.isPublic()) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + call.componentName() + "' não é public",
                        "COMPONENT_NOT_VISIBLE",
                        sourceFile,
                        call.span()
                ));
            }
            // Resolvido via projeto: a Fase 1 só indexa a assinatura (ver
            // ProjectIndex), não os slots — verificação de slot fills
            // cross-ficheiro não é feita aqui (limitação aceite, Tarefa 9).
            return;
        }

        Map<String, Param.SlotParam> calledSlots = new HashMap<>();
        for (Param param : calledComponent.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                calledSlots.put(slotParam.name(), slotParam);
            }
        }

        Map<String, List<Statement.SlotFill>> fillsBySlot = new HashMap<>();
        for (Statement.SlotFill fill : call.slotFills()) {
            fillsBySlot.computeIfAbsent(fill.paramName(), k -> new ArrayList<>()).add(fill);
        }

        for (Map.Entry<String, List<Statement.SlotFill>> entry : fillsBySlot.entrySet()) {
            String slotName = entry.getKey();
            List<Statement.SlotFill> fills = entry.getValue();

            Param.SlotParam declaredSlot = calledSlots.get(slotName);
            if (declaredSlot == null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' não encontrado no componente '" + call.componentName() + "'",
                        "SLOT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
                continue;
            }

            if (declaredSlot.cardinality() == Cardinality.ONE && fills.size() > 1) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' é obrigatório (cardinality ONE) mas recebeu " + fills.size() + " fills",
                        "CARDINALITY_VIOLATION",
                        sourceFile,
                        call.span()
                ));
            }
        }

        for (Map.Entry<String, Param.SlotParam> entry : calledSlots.entrySet()) {
            String slotName = entry.getKey();
            Param.SlotParam slotParam = entry.getValue();

            boolean hasFills = fillsBySlot.containsKey(slotName);
            int fillCount = hasFills ? fillsBySlot.get(slotName).size() : 0;

            boolean isRequired = !slotParam.defaultValue().isPresent() && slotParam.cardinality() == Cardinality.ONE;

            if (isRequired && fillCount == 0) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot obrigatório '" + slotName + "' não foi preenchido no componente '" + call.componentName() + "'",
                        "REQUIRED_SLOT_MISSING",
                        sourceFile,
                        call.span()
                ));
            }
        }
    }
}