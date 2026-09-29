package io.suko.lang.semantic;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.CallResolver;
import io.suko.lang.project.ParamInfo;
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
    /** Resolve chamadas com as regras do compilador (ver {@link CallResolver}),
     * partilhado com o language server; reconstruído a cada {@link #check}. */
    private CallResolver resolver;

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
        resolver = new CallResolver(symbolTable::lookup, projectIndex, sukoFile.imports());
        reportImportProblems();
        if (projectIndex != null) {
            checkPackageDirectoryMismatch(sukoFile);
        }
        for (ComponentDecl component : sukoFile.components()) {
            checkComponent(component);
        }
    }

    private void reportImportProblems() {
        for (CallResolver.ImportStatus status : resolver.imports()) {
            ImportDecl imp = status.decl();
            switch (status.kind()) {
                case OK -> {
                }
                case NOT_FOUND -> diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import não encontrado: '" + imp.qualifiedName() + "'",
                        "IMPORT_NOT_FOUND",
                        sourceFile,
                        imp.span()
                ));
                // REVISÃO FINAL (achado G): o resolver não põe a entrada não-public
                // no mapa de aliases. O COMPONENT_NOT_VISIBLE é reportado aqui, na
                // linha do import, e as chamadas a esse nome não o repetem
                // (Resolution.NotVisible.reportedAtImport): exatamente um
                // diagnóstico por problema real.
                case NOT_VISIBLE -> diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + imp.qualifiedName() + "' não é public — não pode ser importado",
                        "COMPONENT_NOT_VISIBLE",
                        sourceFile,
                        imp.span()
                ));
                case AMBIGUOUS -> diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Import ambíguo: '" + imp.alias().orElse(status.entry().simpleName())
                                + "' já foi importado de outro pacote — use 'as' para desambiguar",
                        "AMBIGUOUS_IMPORT",
                        sourceFile,
                        imp.span()
                ));
            }
        }
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
                    sukoFile.packageSpan().orElseGet(() -> SourceSpan.NONE)
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
     * inline). Depois do subprojeto 9 (sintaxe de interpolação unificada), a
     * chaveta nua não interpola em posição nenhuma — não só dentro de
     * strings. */
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
                    checkLegacyBraceAttribute(attribute);
                    checkBareBraceInExpr(attribute.value(), paramNames, attribute.span());
                }
                checkSyntaxInStatements(element.children(), paramNames);
            }
            case Statement.Interpolation interpolation -> {
                checkLegacyBraceInterpolation(interpolation);
                checkBareBraceInExpr(interpolation.expr(), paramNames, interpolation.span());
            }
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

    /** Subprojeto 9 (D1/D5): a chaveta nua deixou de interpolar em todas as
     * posições. A gramática continua a aceitá-la de propósito — removê-la
     * faria o ANTLR recuperar em silêncio nos caminhos de parse sem error
     * listener (ProjectIndex, RegistryGenerator), e no segundo isso é um
     * manifesto gerado a partir de uma árvore truncada. Quem fecha a porta é
     * este erro, com a correção literal na mensagem. */
    private void checkLegacyBraceInterpolation(Statement.Interpolation interpolation) {
        if (!interpolation.legacyBraceForm()) {
            return;
        }
        String shown = shownExprText(interpolation.expr());
        diagnostics.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                "'{" + shown + "}' já não interpola — escreva '${" + shown + "}'",
                "LEGACY_BRACE_INTERPOLATION",
                sourceFile,
                interpolation.span()
        ));
    }

    private void checkLegacyBraceAttribute(Statement.Attribute attribute) {
        if (!attribute.legacyBraceForm()) {
            return;
        }
        String shown = shownExprText(attribute.value());
        diagnostics.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                "'" + attribute.name() + "={" + shown + "}' já não interpola — escreva '"
                        + attribute.name() + "=${" + shown + "}'",
                "LEGACY_BRACE_ATTRIBUTE",
                sourceFile,
                attribute.span()
        ));
    }

    /** Texto da expressão para a mensagem. Um identificador simples — que é
     * a forma de praticamente toda a interpolação real (`{children}`,
     * `{label}`, `{title}`) — sai literal, o que dá ao autor a linha exata
     * para escrever. Para uma expressão composta não há representação textual
     * no AST (só `Expr` tipado), e reconstruí-la aqui seria um segundo
     * pretty-printer a divergir do JteEmitter: usa-se um marcador genérico,
     * e o `SourceSpan` do diagnóstico aponta para a posição exata. */
    private String shownExprText(Expr expr) {
        return expr instanceof Expr.PrimaryExpr primary ? primary.text() : "expr";
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
                switch (resolver.resolve(p.text())) {
                    case CallResolver.Resolution.NotFound notFound -> {
                        if (looksLikeComponentName(p.text())) {
                            diagnostics.add(new SukoDiagnostic(
                                    SukoDiagnostic.Severity.ERROR,
                                    "Componente '" + p.text() + "' não encontrado",
                                    "COMPONENT_NOT_FOUND",
                                    sourceFile,
                                    call.span()
                            ));
                        }
                    }
                    case CallResolver.Resolution.NotVisible notVisible -> {
                        if (!notVisible.reportedAtImport()) {
                            diagnostics.add(new SukoDiagnostic(
                                    SukoDiagnostic.Severity.ERROR,
                                    "Componente '" + p.text() + "' não é public",
                                    "COMPONENT_NOT_VISIBLE",
                                    sourceFile,
                                    call.span()
                            ));
                        }
                    }
                    case CallResolver.Resolution.Local local -> { }
                    case CallResolver.Resolution.Project project -> { }
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

    private void checkComponentCall(Statement.ComponentCallStmt call, Map<String, Param.SlotParam> currentScopeSlots) {
        List<ParamInfo> calledParams;
        switch (resolver.resolve(call.componentName())) {
            case CallResolver.Resolution.Local local ->
                calledParams = local.decl().params().stream().map(ParamInfo::of).toList();
            case CallResolver.Resolution.NotFound notFound -> {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Componente '" + call.componentName() + "' não encontrado",
                        "COMPONENT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
                return;
            }
            case CallResolver.Resolution.NotVisible notVisible -> {
                if (!notVisible.reportedAtImport()) {
                    diagnostics.add(new SukoDiagnostic(
                            SukoDiagnostic.Severity.ERROR,
                            "Componente '" + call.componentName() + "' não é public",
                            "COMPONENT_NOT_VISIBLE",
                            sourceFile,
                            call.span()
                    ));
                }
                return;
            }
            // D3 (subprojeto 11a): o índice do projeto já guarda os parâmetros,
            // por isso as mesmas regras de slots/parâmetros valem para
            // componentes de outros ficheiros.
            case CallResolver.Resolution.Project project -> calledParams = project.entry().params();
        }

        checkArgumentNames(call, calledParams);
        checkSlotFills(call, calledParams);
    }

    /** Candidato mais parecido com {@code name} (Levenshtein, sem distinguir maiúsculas), para "quis dizer …?". */
    static Optional<String> closest(String name, Collection<String> candidates) {
        String lower = name.toLowerCase(Locale.ROOT);
        int limit = Math.max(1, name.length() / 3);
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int distance = levenshtein(lower, candidate.toLowerCase(Locale.ROOT));
            if (distance <= limit && distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    /** " — quis dizer 'x'?" se houver um candidato parecido; senão a lista de válidos. */
    private static String suggestionOrList(String name, Collection<String> valid, String plural, String none) {
        if (valid.isEmpty()) {
            return " (" + none + ")";
        }
        return closest(name, valid).map(c -> " — quis dizer '" + c + "'?")
            .orElseGet(() -> " — " + plural + ": " + String.join(", ", valid));
    }

    /** Distância de edição com transposição de vizinhos a custar 1 (`titel` → `title`). */
    private static int levenshtein(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= b.length(); j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    /** PARAM_NOT_FOUND: um argumento com nome que não é parâmetro do componente
     * chamado. Antes do 11a isto só falhava quando o gg.jte compilava o template. */
    private void checkArgumentNames(Statement.ComponentCallStmt call, List<ParamInfo> calledParams) {
        Set<String> paramNames = new LinkedHashSet<>();
        calledParams.forEach(p -> paramNames.add(p.name()));
        for (Statement.Arg arg : call.args()) {
            if (arg.name().isEmpty() || paramNames.contains(arg.name().get())) {
                continue;
            }
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "Parâmetro '" + arg.name().get() + "' não encontrado no componente '" + call.componentName()
                            + "'" + suggestionOrList(arg.name().get(), paramNames, "parâmetros", "não tem parâmetros"),
                    "PARAM_NOT_FOUND",
                    sourceFile,
                    arg.span().isNone() ? call.span() : arg.span()
            ));
        }
    }

    private void checkSlotFills(Statement.ComponentCallStmt call, List<ParamInfo> calledParams) {
        Map<String, ParamInfo> calledSlots = new LinkedHashMap<>();
        for (ParamInfo param : calledParams) {
            if (param.slot()) {
                calledSlots.put(param.name(), param);
            }
        }

        Map<String, List<Statement.SlotFill>> fillsBySlot = new LinkedHashMap<>();
        for (Statement.SlotFill fill : call.slotFills()) {
            fillsBySlot.computeIfAbsent(fill.paramName(), k -> new ArrayList<>()).add(fill);
        }

        // Um slot também pode ser preenchido por um argumento nomeado
        // (`Card(header = h)`, com `h` um Component — subprojeto 6): o JteEmitter
        // passa-o tal como está, por isso conta como preenchimento.
        Map<String, Statement.Arg> slotArgs = new LinkedHashMap<>();
        for (Statement.Arg arg : call.args()) {
            if (arg.name().isPresent() && calledSlots.containsKey(arg.name().get())) {
                slotArgs.putIfAbsent(arg.name().get(), arg);
            }
        }

        for (Map.Entry<String, List<Statement.SlotFill>> entry : fillsBySlot.entrySet()) {
            String slotName = entry.getKey();
            List<Statement.SlotFill> fills = entry.getValue();

            ParamInfo declaredSlot = calledSlots.get(slotName);
            if (declaredSlot == null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' não encontrado no componente '" + call.componentName() + "'"
                                + suggestionOrList(slotName, calledSlots.keySet(), "slots", "não tem slots"),
                        "SLOT_NOT_FOUND",
                        sourceFile,
                        fillSpan(fills.get(0), call)
                ));
                continue;
            }

            if (declaredSlot.cardinality().orElse(Cardinality.ONE) == Cardinality.ONE && fills.size() > 1) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' aceita um só bloco mas recebeu " + fills.size() + " — deixe apenas um",
                        "CARDINALITY_VIOLATION",
                        sourceFile,
                        fillSpan(fills.get(1), call)
                ));
            }

            Statement.Arg duplicate = slotArgs.get(slotName);
            if (duplicate != null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' foi passado como argumento ('" + slotName + " = …') e também como bloco ('"
                                + slotName + " { … }') — use só um",
                        "CARDINALITY_VIOLATION",
                        sourceFile,
                        duplicate.span().isNone() ? call.span() : duplicate.span()
                ));
            }
        }

        for (ParamInfo slot : calledSlots.values()) {
            if (slot.requiredSlot() && !fillsBySlot.containsKey(slot.name()) && !slotArgs.containsKey(slot.name())) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot obrigatório '" + slot.name() + "' não foi preenchido no componente '" + call.componentName() + "'",
                        "REQUIRED_SLOT_MISSING",
                        sourceFile,
                        call.nameSpan().isNone() ? call.span() : call.nameSpan()
                ));
            }
        }
    }

    /** O nome do slot preenchido, não a chamada inteira (que inclui os corpos dos slots). */
    private static SourceSpan fillSpan(Statement.SlotFill fill, Statement.ComponentCallStmt call) {
        return fill.nameSpan().isNone() ? call.span() : fill.nameSpan();
    }
}