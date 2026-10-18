package io.suko.lang;

import io.suko.lang.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Traduz o ParseTree do ANTLR para o AST tipado do Suko. Tradução
 * puramente estrutural — sem verificação semântica (isso é o
 * subprojeto 2/3). A gramática já garante a forma da árvore, por isso o
 * único "erro" possível aqui é um bug interno (forma inesperada de
 * parse tree), sinalizado como IllegalStateException.
 */
public class SukoAstBuilder {

    private final String source;
    private final boolean tolerant;
    private final boolean sourceHasSurrogates;

    /**
     * Erro de forma detetado ao construir o AST (13b): prefixo reservado com maiúsculas, espaços à volta de
     * ':', lambda fora de on:, state/derived fora do sítio. O caminho estrito (JteCompiler) reporta-o como
     * PARSE_ERROR com a posição real; os caminhos silenciosos (ProjectIndex, RegistryGenerator) e o
     * tolerante (LSP) ignoram-no, como já ignoram os erros do ANTLR.
     */
    public record SyntaxIssue(String message, SourceSpan span) {
    }

    private final List<SyntaxIssue> syntaxIssues = new ArrayList<>();

    public List<SyntaxIssue> syntaxIssues() {
        return List.copyOf(syntaxIssues);
    }

    private void issue(String message, SourceSpan span) {
        syntaxIssues.add(new SyntaxIssue(message, span));
    }

    public SukoAstBuilder(String source) {
        this(source, false);
    }

    private SukoAstBuilder(String source, boolean tolerant) {
        this.source = source;
        this.tolerant = tolerant;
        this.sourceHasSurrogates = source.chars().anyMatch(c -> Character.isSurrogate((char) c));
    }

    /**
     * Modo tolerante, só para o language server (ver {@link TolerantParser}):
     * numa árvore de recuperação de erros ANTLR, uma falha ao construir uma
     * instrução, um parâmetro ou um componente descarta apenas essa subárvore
     * em vez de derrubar o ficheiro inteiro. Nunca é o caminho que reporta
     * diagnósticos — esses vêm sempre do modo estrito, idêntico ao do build.
     */
    static SukoAstBuilder tolerant(String source) {
        return new SukoAstBuilder(source, true);
    }

    public SukoFile build(SukoParser.CompilationUnitContext ctx) {
        Optional<String> packageName = ctx.packageDecl() == null
            ? Optional.empty()
            : Optional.of(ctx.packageDecl().qualifiedName().getText());
        Optional<SourceSpan> packageSpan = ctx.packageDecl() == null
            ? Optional.empty()
            : Optional.of(spanOf(ctx.packageDecl()));

        List<ImportDecl> imports = new ArrayList<>();
        for (SukoParser.ImportDeclContext importCtx : ctx.importDecl()) {
            Optional<String> alias = importCtx.Identifier() == null
                ? Optional.empty()
                : Optional.of(importCtx.Identifier().getText());
            imports.add(new ImportDecl(importCtx.qualifiedName().getText(), alias, spanOf(importCtx)));
        }

        List<ComponentDecl> components = new ArrayList<>();
        for (SukoParser.ComponentDeclContext componentCtx : ctx.componentDecl()) {
            if (!tolerant) {
                components.add(buildComponent(componentCtx));
                continue;
            }
            ComponentDecl component = buildComponentTolerant(componentCtx);
            if (component != null) {
                components.add(component);
            }
        }

        return new SukoFile(packageName, packageSpan, imports, components);
    }

    /** Sem `templateBlock` (`component B( {`) ou com nome em falta: o que der para
     * aproveitar (nome, parâmetros válidos, corpo válido) fica; sem nome, nada. */
    private ComponentDecl buildComponentTolerant(SukoParser.ComponentDeclContext ctx) {
        if (ctx.Identifier() == null || ctx.Identifier().getSymbol().getStartIndex() < 0) {
            return null;
        }
        try {
            List<String> typeParameters = new ArrayList<>();
            try {
                if (ctx.typeParameters() != null) {
                    for (SukoParser.TypeParameterContext tp : ctx.typeParameters().typeParameter()) {
                        if (tp.Identifier() != null && tp.Identifier().getSymbol().getStartIndex() >= 0) {
                            typeParameters.add(tp.Identifier().getText());
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // parâmetros de tipo partidos: seguem sem eles
            }
            List<Param> params = new ArrayList<>();
            if (ctx.paramList() != null) {
                for (SukoParser.ParamContext paramCtx : ctx.paramList().param()) {
                    try {
                        if (paramCtx.type() != null && paramCtx.Identifier() != null
                                && paramCtx.Identifier().getSymbol().getStartIndex() >= 0) {
                            params.add(buildParam(paramCtx));
                        }
                    } catch (RuntimeException ignored) {
                        // este parâmetro está partido: descartado, os outros ficam
                    }
                }
            }
            List<Statement> body = ctx.componentBody() == null
                ? List.of()
                : buildComponentBody(ctx.componentBody());
            SourceSpan span;
            try {
                span = spanOf(ctx);
            } catch (RuntimeException e) {
                span = spanOf(ctx.getStart());
            }
            return new ComponentDecl(ctx.Identifier().getText(), typeParameters, params, body, span,
                ctx.PUBLIC() != null, spanOf(ctx.Identifier().getSymbol()), ctx.REACTIVE() != null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ComponentDecl buildComponent(SukoParser.ComponentDeclContext ctx) {
        List<String> typeParameters = new ArrayList<>();
        if (ctx.typeParameters() != null) {
            for (SukoParser.TypeParameterContext tp : ctx.typeParameters().typeParameter()) {
                typeParameters.add(tp.Identifier().getText());
            }
        }

        List<Param> params = new ArrayList<>();
        if (ctx.paramList() != null) {
            for (SukoParser.ParamContext paramCtx : ctx.paramList().param()) {
                params.add(buildParam(paramCtx));
            }
        }

        List<Statement> body = buildComponentBody(ctx.componentBody());

        return new ComponentDecl(ctx.Identifier().getText(), typeParameters, params, body, spanOf(ctx),
            ctx.PUBLIC() != null, spanOf(ctx.Identifier().getSymbol()), ctx.REACTIVE() != null);
    }

    private static final Type CONTENT_ELEMENT_TYPE = new Type("Object", List.of(), 0);

    private Param buildParam(SukoParser.ParamContext ctx) {
        Type type = buildType(ctx.type());
        String name = ctx.Identifier().getText();
        Optional<Expr> defaultValue = ctx.expression() == null
            ? Optional.empty()
            : Optional.of(buildExpr(ctx.expression()));

        return tryBuildSlotParam(type, name, defaultValue, ctx)
            .map(Param.class::cast)
            .orElseGet(() -> new Param.ValueParam(type, name, defaultValue, spanOf(ctx), spanOf(ctx.Identifier().getSymbol())));
    }

    private Optional<Param.SlotParam> tryBuildSlotParam(Type type, String name, Optional<Expr> defaultValue,
            SukoParser.ParamContext ctx) {
        if (isComponent(type)) {
            return Optional.of(new Param.SlotParam(CONTENT_ELEMENT_TYPE, name, Cardinality.ONE, false, defaultValue, spanOf(ctx),
                spanOf(ctx.Identifier().getSymbol())));
        }
        if (isRenderProp(type)) {
            return Optional.of(new Param.SlotParam(type.typeArguments().get(0), name, Cardinality.ONE, true, defaultValue, spanOf(ctx),
                spanOf(ctx.Identifier().getSymbol())));
        }
        if ("List".equals(type.name()) && type.typeArguments().size() == 1) {
            Type inner = type.typeArguments().get(0);
            if (isComponent(inner)) {
                return Optional.of(new Param.SlotParam(CONTENT_ELEMENT_TYPE, name, Cardinality.MANY, false, defaultValue, spanOf(ctx),
                spanOf(ctx.Identifier().getSymbol())));
            }
            if (isRenderProp(inner)) {
                return Optional.of(new Param.SlotParam(inner.typeArguments().get(0), name, Cardinality.MANY, true, defaultValue, spanOf(ctx),
                spanOf(ctx.Identifier().getSymbol())));
            }
        }
        return Optional.empty();
    }

    private boolean isComponent(Type type) {
        return "Component".equals(type.name()) && type.typeArguments().isEmpty();
    }

    private boolean isRenderProp(Type type) {
        return "Function".equals(type.name())
            && type.typeArguments().size() == 2
            && isComponent(type.typeArguments().get(1));
    }

    private Type buildType(SukoParser.TypeContext ctx) {
        List<Type> typeArguments = new ArrayList<>();
        if (ctx.typeArguments() != null) {
            for (SukoParser.TypeContext argCtx : ctx.typeArguments().type()) {
                typeArguments.add(buildType(argCtx));
            }
        }
        int arrayDimensions = ctx.arrayMarker().size();
        return new Type(ctx.Identifier().getText(), typeArguments, arrayDimensions);
    }

    /** O doctype (único `<!DOCTYPE html>` aceite, só como 1.º item — ver `componentBody`)
     * fica no AST como o primeiro TextRun do corpo, com o texto canónico
     * independentemente de maiúsculas/espaço no fonte; sem nó novo no AST. */
    private List<Statement> buildComponentBody(SukoParser.ComponentBodyContext ctx) {
        List<Statement> statements = new ArrayList<>();
        org.antlr.v4.runtime.tree.TerminalNode doctype = ctx.DOCTYPE();
        if (doctype != null && doctype.getSymbol().getStartIndex() >= 0) {
            statements.add(new Statement.TextRun("<!DOCTYPE html>", spanOf(doctype.getSymbol())));
        }
        statements.addAll(buildStatements(ctx.templateStatement()));
        return statements;
    }

    List<Statement> buildStatements(List<SukoParser.TemplateStatementContext> ctxs) {
        List<Statement> statements = new ArrayList<>();
        for (SukoParser.TemplateStatementContext stmtCtx : ctxs) {
            if (!tolerant) {
                statements.add(buildStatement(stmtCtx));
                continue;
            }
            try {
                statements.add(buildStatement(stmtCtx));
            } catch (RuntimeException e) {
                // modo tolerante: só esta subárvore é descartada
            }
        }
        return statements;
    }

    Statement buildStatement(SukoParser.TemplateStatementContext ctx) {
        if (ctx.reactiveDecl() != null) {
            return buildReactiveDecl(ctx);
        }
        if (ctx.componentCall() != null) {
            return buildComponentCallStmt(ctx.componentCall());
        }
        if (ctx.forStmt() != null) {
            return buildForStmt(ctx.forStmt());
        }
        if (ctx.ifStmt() != null) {
            return buildIfStmt(ctx.ifStmt());
        }
        if (ctx.switchStmt() != null) {
            return buildSwitchStmt(ctx.switchStmt());
        }
        if (ctx.htmlElement() != null) {
            return buildHtmlElement(ctx.htmlElement());
        }
        if (ctx.interpolation() != null) {
            return new Statement.Interpolation(
                buildExpr(ctx.interpolation().expression()),
                ctx.interpolation().legacy != null,
                spanOf(ctx.interpolation()));
        }
        if (ctx.textRun() != null) {
            return new Statement.TextRun(textOf(ctx.textRun()), spanOf(ctx.textRun()));
        }
        if (ctx.varDecl() != null) {
            SukoParser.VarDeclContext varDeclCtx = ctx.varDecl();
            return new Statement.VarDecl(
                varDeclCtx.Identifier().getText(),
                buildExpr(varDeclCtx.expression()),
                spanOf(varDeclCtx));
        }
        throw new IllegalStateException("templateStatement ainda não suportado: " + ctx.getText());
    }

    // `slotBlock`, quando presente, é ignorado nesta tarefa — tratado nas
    // tarefas 16-18.
    //
    // `qualifiedName` aceita sintaticamente nomes com ponto (`ui.NavLink(...)`),
    // mas `ctx.qualifiedName().getText()` copia esse texto tal como escrito para
    // `componentName`, e `JteEmitter.emitComponentCall` emite literalmente
    // "@template." + componentName. O gg.jte trata pontos em `@template.` como
    // separadores de caminho (`ui/NavLink.jte`), enquanto o compilador escreve
    // cada componente num `.jte` plano nomeado por `ComponentDecl.name()` (que é
    // um `Identifier` único, nunca composto) — uma chamada com nome composto
    // falha em tempo de render com `TemplateNotFoundException`, não em tempo de
    // build. Não há verificação nem teste para este caso; fica para a análise
    // semântica (subprojetos 2-3) rejeitar nomes de chamada compostos, ou para
    // o emitter aprender a resolver sub-pacotes, se isso vier a ser suportado.
    private Statement.ComponentCallStmt buildComponentCallStmt(SukoParser.ComponentCallContext ctx) {
        List<Statement.Arg> args = new ArrayList<>();
        if (ctx.componentArgList() != null) {
            for (SukoParser.ComponentArgContext argCtx : ctx.componentArgList().componentArg()) {
                Optional<String> name = argCtx.Identifier() == null
                    ? Optional.empty()
                    : Optional.of(argCtx.Identifier().getText());
                Expr value = argCtx.lambda() != null ? buildLambda(argCtx.lambda()) : buildExpr(argCtx.expression());
                SourceSpan argSpan = argCtx.Identifier() != null
                    ? spanOf(argCtx.Identifier().getSymbol())
                    : argCtx.lambda() != null ? spanOf(argCtx.lambda()) : spanOf(argCtx.expression());
                args.add(new Statement.Arg(name, value, argSpan));
            }
        }
        List<Statement.SlotFill> slotFills = new ArrayList<>();
        if (ctx.slotBlock() != null) {
            for (SukoParser.NamedSlotContext slotCtx : ctx.slotBlock().namedSlot()) {
                String paramName = slotCtx.Identifier(0).getText();
                Optional<String> lambdaParamName = slotCtx.Identifier().size() > 1
                    ? Optional.of(slotCtx.Identifier(1).getText())
                    : Optional.empty();
                // Desvio do brief: NamedSlotContext não tem método templateBlock() —
                // a regra `namedSlot` embute `templateStatement*` diretamente (sem
                // envolver num `templateBlock`, ao contrário de outras regras como
                // `componentDecl` ou `ifStatement`). Confirmado lendo o parser gerado
                // (build/generated-src/antlr/main/io/suko/lang/SukoParser.java).
                List<Statement> body = buildStatements(slotCtx.templateStatement());
                slotFills.add(new Statement.SlotFill(paramName, lambdaParamName, body,
                    spanOf(slotCtx.Identifier(0).getSymbol())));
            }

            // Children implícitos (subprojeto 6): templateStatement soltos direto
            // dentro do slotBlock (fora de qualquer namedSlot) sintetizam um
            // SlotFill("children", ...) — o autor da chamada nunca escreve
            // "children { ... }" explicitamente. Ordem preservada (ANTLR devolve
            // ctx.slotBlock().templateStatement() na ordem de aparição no fonte).
            List<SukoParser.TemplateStatementContext> looseStatements = ctx.slotBlock().templateStatement();
            if (!looseStatements.isEmpty()) {
                slotFills.add(new Statement.SlotFill("children", Optional.empty(), buildStatements(looseStatements)));
            }
        }

        return new Statement.ComponentCallStmt(ctx.qualifiedName().getText(), args, slotFills, spanOf(ctx),
            spanOf(ctx.qualifiedName()));
    }

    private Statement.IfStmt buildIfStmt(SukoParser.IfStmtContext ctx) {
        Expr condition = buildExpr(ctx.expression());
        List<Statement> thenBranch = buildStatements(ctx.templateBlock(0).templateStatement());

        List<Statement> elseBranch;
        if (ctx.ifStmt() != null) {
            elseBranch = List.of(buildIfStmt(ctx.ifStmt()));
        } else if (ctx.templateBlock().size() > 1) {
            elseBranch = buildStatements(ctx.templateBlock(1).templateStatement());
        } else {
            elseBranch = List.of();
        }

        return new Statement.IfStmt(condition, thenBranch, elseBranch, spanOf(ctx));
    }

    private Statement buildReactiveDecl(SukoParser.TemplateStatementContext stmtCtx) {
        SukoParser.ReactiveDeclContext ctx = stmtCtx.reactiveDecl();
        Optional<Type> type = ctx.VAR() != null ? Optional.empty() : Optional.of(buildType(ctx.type()));
        String name = ctx.Identifier().getText();
        Expr value = buildExpr(ctx.expression());
        SourceSpan span = spanOf(ctx);
        SourceSpan nameSpan = spanOf(ctx.Identifier().getSymbol());
        boolean state = ctx.kind.getType() == SukoParser.STATE;
        if (!atTopOfReactiveBody(stmtCtx)) {
            issue("'" + (state ? "state" : "derived") + "' só pode aparecer no topo do corpo de um reactive component,"
                + " antes do markup", span);
        }
        return state
            ? new Statement.StateDecl(type, name, value, span, nameSpan)
            : new Statement.DerivedDecl(type, name, value, span, nameSpan);
    }

    /** Num component que não é reativo devolve true: aí quem fala é o REACTIVE_REQUIRED do verificador. */
    private static boolean atTopOfReactiveBody(SukoParser.TemplateStatementContext stmtCtx) {
        org.antlr.v4.runtime.ParserRuleContext p = stmtCtx;
        while (p != null && !(p instanceof SukoParser.ComponentDeclContext)) {
            p = p.getParent();
        }
        if (!(p instanceof SukoParser.ComponentDeclContext component) || component.REACTIVE() == null) {
            return true;
        }
        if (!(stmtCtx.getParent() instanceof SukoParser.ComponentBodyContext body)) {
            return false;
        }
        for (SukoParser.TemplateStatementContext sibling : body.templateStatement()) {
            if (sibling == stmtCtx) {
                return true;
            }
            if (sibling.reactiveDecl() == null) {
                return false;
            }
        }
        return true;
    }

    private Statement.ForStmt buildForStmt(SukoParser.ForStmtContext ctx) {
        Type itemType = ctx.VAR() != null ? new Type("var", List.of(), 0) : buildType(ctx.type());
        return new Statement.ForStmt(
            itemType,
            ctx.Identifier().getText(),
            buildExpr(ctx.expression()),
            buildStatements(ctx.templateBlock().templateStatement()),
            spanOf(ctx));
    }

    private Statement.SwitchStmt buildSwitchStmt(SukoParser.SwitchStmtContext ctx) {
        Expr subject = buildExpr(ctx.expression());

        List<Statement.SwitchCase> cases = new ArrayList<>();
        for (SukoParser.SwitchCaseContext caseCtx : ctx.switchCase()) {
            // DESVIO DO BRIEF: `caseCtx.expression()` (sem índice) não compila —
            // switchCase referencia `expression` duas vezes na gramática
            // (`CASE expression ARROW (templateBlock | expression SEMI)`), por
            // isso o ANTLR gera `List<ExpressionContext> expression()` em vez de
            // `ExpressionContext expression()` (confirmado no parser gerado,
            // SukoParser.java, SwitchCaseContext). O brief assumia a forma de
            // acessor de uma regra com uma só ocorrência de `expression`
            // (como switchStmt/defaultCase, onde `expression()` sem índice é de
            // facto um ExpressionContext único). Corrigido para
            // `caseCtx.expression(0)`, que é o valor do case (a primeira
            // ocorrência); a segunda, `caseCtx.expression(1)`, é a
            // expressão-corpo do ramo `-> expression;`, já usada corretamente
            // pelo brief mais abaixo.
            cases.add(new Statement.SwitchCase(buildExpr(caseCtx.expression(0)), buildCaseBody(
                caseCtx.templateBlock(), caseCtx.expression(1))));
        }

        List<Statement> defaultCase = ctx.defaultCase() == null
            ? List.of()
            : buildCaseBody(ctx.defaultCase().templateBlock(), ctx.defaultCase().expression());

        return new Statement.SwitchStmt(subject, cases, defaultCase, spanOf(ctx));
    }

    private List<Statement> buildCaseBody(SukoParser.TemplateBlockContext blockCtx, SukoParser.ExpressionContext exprCtx) {
        if (blockCtx != null) {
            return buildStatements(blockCtx.templateStatement());
        }
        // "case X -> expression;" — trata a expressão como uma única interpolação.
        return List.of(new Statement.Interpolation(buildExpr(exprCtx), false, spanOf(exprCtx)));
    }

    private Statement.HtmlElement buildHtmlElement(SukoParser.HtmlElementContext ctx) {
        if (ctx instanceof SukoParser.SelfClosingElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName().getText(), parts.attributes(),
                List.of(), true, spanOf(ctx), parts.directives());
        }
        if (ctx instanceof SukoParser.OpenElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName(0).getText(), parts.attributes(),
                buildStatements(c.templateStatement()), false, spanOf(ctx), parts.directives());
        }
        if (ctx instanceof SukoParser.VoidElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName().getText(), parts.attributes(),
                List.of(), true, spanOf(ctx), parts.directives());
        }
        throw new IllegalStateException("Tipo de htmlElement desconhecido: " + ctx.getClass());
    }

    private record AttributeParts(List<Statement.Attribute> attributes, List<Statement.Directive> directives) {
    }

    /** Atributos HTML e diretivas da 13b (`on:`/`bind:`, só com o prefixo exatamente em minúsculas). */
    private AttributeParts buildAttributes(List<SukoParser.AttributeContext> ctxs) {
        List<Statement.Attribute> attributes = new ArrayList<>();
        List<Statement.Directive> directives = new ArrayList<>();
        for (SukoParser.AttributeContext attrCtx : ctxs) {
            SukoParser.AttrNameContext nameCtx = attrCtx.attrName();
            String prefix = nameCtx.htmlName(0).getText();
            boolean prefixed = nameCtx.COLON() != null;
            String local = prefixed ? nameCtx.htmlName(1).getText() : null;
            String name = prefixed ? prefix + ":" + local : prefix;
            SourceSpan span = spanOf(attrCtx);
            SourceSpan nameSpan = spanOf(nameCtx);
            if (prefixed && !adjacent(nameCtx)) {
                issue("'" + name + "': um nome de atributo com ':' escreve-se sem espaços", nameSpan);
            }
            if (prefixed && (prefix.equals("on") || prefix.equals("bind"))) {
                directives.add(buildDirective(attrCtx, prefix.equals("on"), local, span, nameSpan));
                continue;
            }
            if (prefixed && (prefix.equalsIgnoreCase("on") || prefix.equalsIgnoreCase("bind"))) {
                String lower = prefix.toLowerCase(java.util.Locale.ROOT);
                issue("'" + prefix + ":' é um prefixo reservado do Suko e escreve-se em minúsculas: " + lower + ":" + local,
                    nameSpan);
            }
            boolean lambda = attrCtx.lambdaInterpolation() != null;
            if (lambda) {
                issue("uma lambda só é aceite como valor de on:evento (ex.: on:click=${() -> ...})"
                    + " ou como argumento de um componente reativo", span);
            }
            Expr value = attrCtx.stringLiteral() != null
                ? buildStringLiteral(attrCtx.stringLiteral())
                : attrCtx.interpolation() != null
                    ? buildExpr(attrCtx.interpolation().expression())
                    : lambda
                        ? buildLambda(attrCtx.lambdaInterpolation().lambda())
                        : new Expr.PrimaryExpr("true", spanOf(attrCtx));
            boolean legacyBraceForm = attrCtx.interpolation() != null
                && attrCtx.interpolation().legacy != null;
            attributes.add(new Statement.Attribute(name, value, legacyBraceForm, span));
        }
        return new AttributeParts(attributes, directives);
    }

    /** `prefixo:nome` só com os três pedaços colados: o lexer descarta o espaço, por isso comparam-se posições. */
    private static boolean adjacent(SukoParser.AttrNameContext ctx) {
        int prefixEnd = ctx.htmlName(0).getStop().getStopIndex();
        int colon = ctx.COLON().getSymbol().getStartIndex();
        int localStart = ctx.htmlName(1).getStart().getStartIndex();
        return colon == prefixEnd + 1 && localStart == colon + 1;
    }

    private Statement.Directive buildDirective(SukoParser.AttributeContext attrCtx, boolean on, String local,
                                               SourceSpan span, SourceSpan nameSpan) {
        Expr value;
        if (on && attrCtx.lambdaInterpolation() != null) {
            value = buildLambda(attrCtx.lambdaInterpolation().lambda());
        } else if (!on && attrCtx.interpolation() != null && attrCtx.interpolation().legacy == null) {
            value = buildExpr(attrCtx.interpolation().expression());
        } else {
            issue(on
                ? "on:" + local + " espera uma lambda: on:" + local + "=${() -> ...} (sem aspas)"
                : "bind:" + local + " espera o nome de um state: bind:" + local + "=${nome} (sem aspas)", span);
            value = new Expr.PrimaryExpr("null", span);
        }
        return new Statement.Directive(on ? Statement.Directive.Kind.ON : Statement.Directive.Kind.BIND,
            local, value, span, nameSpan);
    }

    private Expr.LambdaExpr buildLambda(SukoParser.LambdaContext ctx) {
        List<String> params = ctx.lambdaParams().Identifier() == null
            ? List.of()
            : List.of(ctx.lambdaParams().Identifier().getText());
        SukoParser.LambdaBodyContext body = ctx.lambdaBody();
        List<Expr> statements = new ArrayList<>();
        for (SukoParser.ClientStatementContext st : body.clientStatement()) {
            statements.add(buildClientStatement(st));
        }
        return new Expr.LambdaExpr(params, statements, body.LBRACE() != null, spanOf(ctx));
    }

    private Expr buildClientStatement(SukoParser.ClientStatementContext ctx) {
        return switch (ctx) {
            case SukoParser.AssignStatementContext c -> new Expr.AssignExpr(
                buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.PrefixUpdateStatementContext c ->
                new Expr.UpdateExpr(buildExpr(c.expression()), c.op.getText(), true, spanOf(c));
            case SukoParser.PostfixUpdateStatementContext c ->
                new Expr.UpdateExpr(buildExpr(c.expression()), c.op.getText(), false, spanOf(c));
            case SukoParser.ExpressionStatementContext c -> buildExpr(c.expression());
            default -> throw new IllegalStateException("instrução de lambda desconhecida: " + ctx.getText());
        };
    }

    Expr buildExpr(SukoParser.ExpressionContext ctx) {
        return switch (ctx) {
            case SukoParser.PrimaryExprContext c -> buildPrimary(c.primary());
            case SukoParser.AccessExprContext c ->
                new Expr.AccessExpr(buildExpr(c.expression()), c.memberName().getText(), spanOf(c));
            case SukoParser.CallExprContext c -> new Expr.CallExpr(
                buildExpr(c.expression()), buildArgs(c.argList()), spanOf(c));
            case SukoParser.NotExprContext c -> new Expr.NotExpr(buildExpr(c.expression()), spanOf(c));
            case SukoParser.UnaryMinusExprContext c ->
                new Expr.UnaryMinusExpr(buildExpr(c.expression()), spanOf(c));
            case SukoParser.MulExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.AddExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.RelExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.EqExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.AndExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), "&&", buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.OrExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), "||", buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.TernaryExprContext c -> new Expr.TernaryExpr(
                buildExpr(c.expression(0)), buildExpr(c.expression(1)), buildExpr(c.expression(2)), spanOf(c));
            case SukoParser.ParenExprContext c -> new Expr.ParenExpr(buildExpr(c.expression()), spanOf(c));
            case SukoParser.SafeAccessExprContext c ->
                new Expr.SafeAccessExpr(buildExpr(c.expression()), c.memberName().getText(), spanOf(c));
            case SukoParser.ElvisExprContext c ->
                new Expr.ElvisExpr(buildExpr(c.expression(0)), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.NewExprContext c -> {
                if (c.argList() != null) {
                    for (SukoParser.ArgContext a : c.argList().arg()) {
                        if (a.Identifier() != null) {
                            issue("new " + c.Identifier().getText() + "(...) não aceita argumentos com nome", spanOf(a));
                        }
                    }
                }
                yield new Expr.NewExpr(c.Identifier().getText(), buildArgs(c.argList()), spanOf(c));
            }
            default -> throw new IllegalStateException(
                "Tipo de expressão ainda não suportado: " + ctx.getClass());
        };
    }

    private Expr buildPrimary(SukoParser.PrimaryContext ctx) {
        if (ctx.stringLiteral() != null) {
            return buildStringLiteral(ctx.stringLiteral());
        }
        return new Expr.PrimaryExpr(ctx.getText(), spanOf(ctx));
    }

    private List<Expr> buildArgs(SukoParser.ArgListContext ctx) {
        List<Expr> args = new ArrayList<>();
        if (ctx != null) {
            for (SukoParser.ArgContext argCtx : ctx.arg()) {
                args.add(buildExpr(argCtx.expression()));
            }
        }
        return args;
    }

    private Expr.StringLiteralExpr buildStringLiteral(SukoParser.StringLiteralContext ctx) {
        List<Expr.StringPart> parts = new ArrayList<>();
        StringBuilder literalRun = new StringBuilder();
        for (SukoParser.StringPartContext partCtx : ctx.stringPart()) {
            if (partCtx.interpolation() != null) {
                flushLiteral(parts, literalRun);
                parts.add(new Expr.StringPart.Interp(buildExpr(partCtx.interpolation().expression())));
            } else if (partCtx.SIMPLE_INTERP_START() != null) {
                flushLiteral(parts, literalRun);
                // "$nome" — remove o "$" inicial do texto do token.
                parts.add(new Expr.StringPart.SimpleInterp(partCtx.getText().substring(1)));
            } else if (partCtx.STRING_ESCAPE() != null) {
                literalRun.append(javaEscapeOf(partCtx.getText()));
            } else {
                literalRun.append(partCtx.getText());
            }
        }
        flushLiteral(parts, literalRun);
        return new Expr.StringLiteralExpr(parts, spanOf(ctx));
    }

    private void flushLiteral(List<Expr.StringPart> parts, StringBuilder literalRun) {
        if (literalRun.length() > 0) {
            parts.add(new Expr.StringPart.Literal(literalRun.toString()));
            literalRun.setLength(0);
        }
    }

    /** Um escape Suko é sempre `\` + 1 caractere (token STRING_ESCAPE). O
     * texto acumulado aqui vai parar DENTRO de um literal de string Java
     * emitido no `.jte`, por isso tem de ser um escape que o javac aceite.
     *
     * `\$` não é escape Java válido ("illegal escape character") — e é a
     * única forma de escrever um `$` literal desde que `${...}` passou a
     * ser o único sigilo de interpolação em todas as posições (subprojeto
     * 9, D1/D6). Traduz-se para a sequência de 6 caracteres `\u0024`, e
     * NÃO para um `$` cru: um `$` cru volta a aparecer no texto do `.jte`,
     * onde um `${` seguinte seria lido pelo próprio gg.jte como início de
     * interpolação; `\u0024` nunca forma um `${` no `.jte`, e o javac
     * resolve-o para `$` no pré-processamento de escapes unicode, antes
     * de lexar.
     *
     * Fora de uma string, não há forma de escrever um `${` literal — essa
     * limitação já está documentada na spec e não muda aqui.
     *
     * Qualquer outro escape passa intacto: `\"`, `\\`, `\n`, `\t`, `\r`,
     * `\b`, `\f`, `\s` já são válidos em Java, e traduzi-los aqui só
     * arriscaria mudar-lhes o significado. */
    private String javaEscapeOf(String sukoEscape) {
        if (sukoEscape.equals("\\$")) {
            return "\\u0024";
        }
        return sukoEscape;
    }

    /** Recupera o texto literal de um textRun pela posição de carácter no fonte
     * (não por concatenação de tokens), preservando espaçamento exatamente como
     * no .sk original — ver ARCHITECTURE.md.
     *
     * DESVIO DO BRIEF (mínimo, necessário para preservar o próprio objetivo do
     * brief): o lexer descarta espaço/tab/quebra de linha via `WS -> skip`
     * (SukoLexer.g4), o que os remove inteiramente do stream de tokens — não
     * ficam nem em canal escondido. Um espaço adjacente a `{`, `}`, `<` ou `>`
     * (ex.: "Hello, {name}!" ou "{name} !") não pertence a NENHUM token: fica
     * fora do intervalo [start,stop] do textRun de um dos dois lados — quer no
     * fim (espaço logo antes de `{`/`<` seguinte, que o ctx.getStop() do
     * textRun não alcança porque termina no último token visível) quer no
     * início (espaço logo depois de `}`/`>` anterior, que o ctx.getStart() do
     * textRun não alcança porque começa no primeiro token visível). Ambos os
     * lados foram reproduzidos em teste: sem a correção, "Hello, {name}!"
     * emitia "Hello,${name}!" (falta o espaço à direita da vírgula) e
     * "{name} !" emitia "${name}!" (falta o espaço à esquerda de "!"). A
     * correção fica só do lado Java: estende o início e o fim do intervalo
     * capturado sobre espaço em branco cru imediatamente adjacente ao
     * textRun, até ao primeiro carácter não-espaço de cada lado (que será
     * sempre o fim/início de outro token/regra, nunca outro textRun órfão,
     * porque texto puramente espaço entre tags não gera nó nenhum — não há
     * risco de dupla contagem). Não requer mudança na gramática. */
    private String textOf(SukoParser.TextRunContext ctx) {
        // Os índices do ANTLR contam code points; String indexa em chars UTF-16.
        // Sem esta conversão um emoji antes do texto deslocava o corte (o
        // último carácter de "😀 ola mundo" perdia-se no .jte gerado).
        int start = charOffset(ctx.getStart().getStartIndex());
        int end = charOffset(ctx.getStop().getStopIndex() + 1); // exclusivo
        while (start > 0 && isSkippedWhitespace(source.charAt(start - 1))) {
            start--;
        }
        while (end < source.length() && isSkippedWhitespace(source.charAt(end))) {
            end++;
        }
        return source.substring(start, end);
    }

    private int charOffset(int codePointIndex) {
        if (!sourceHasSurrogates) {
            return codePointIndex;
        }
        return source.offsetByCodePoints(0, codePointIndex);
    }

    private boolean isSkippedWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private SourceSpan spanOf(org.antlr.v4.runtime.Token token) {
        return new SourceSpan(token.getLine(), token.getCharPositionInLine(),
            token.getStartIndex(), token.getStopIndex());
    }

    private SourceSpan spanOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
        return new SourceSpan(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            ctx.getStart().getStartIndex(),
            ctx.getStop().getStopIndex());
    }
}
