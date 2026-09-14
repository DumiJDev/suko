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

    public SukoAstBuilder(String source) {
        this.source = source;
    }

    public SukoFile build(SukoParser.CompilationUnitContext ctx) {
        Optional<String> packageName = ctx.packageDecl() == null
            ? Optional.empty()
            : Optional.of(ctx.packageDecl().qualifiedName().getText());

        List<String> imports = new ArrayList<>();
        for (SukoParser.ImportDeclContext importCtx : ctx.importDecl()) {
            imports.add(importCtx.qualifiedName().getText());
        }

        List<ComponentDecl> components = new ArrayList<>();
        for (SukoParser.ComponentDeclContext componentCtx : ctx.componentDecl()) {
            components.add(buildComponent(componentCtx));
        }

        return new SukoFile(packageName, imports, components);
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

        List<Statement> body = buildStatements(ctx.templateBlock().templateStatement());

        return new ComponentDecl(ctx.Identifier().getText(), typeParameters, params, body, spanOf(ctx));
    }

    private Param buildParam(SukoParser.ParamContext ctx) {
        Type type = buildType(ctx.type());
        String name = ctx.Identifier().getText();
        Optional<Expr> defaultValue = ctx.expression() == null
            ? Optional.empty()
            : Optional.of(buildExpr(ctx.expression()));

        if (type.isSlot()) {
            Type elementType = type.typeArguments().isEmpty()
                ? new Type("Object", List.of(), 0)
                : type.typeArguments().get(0);
            return new Param.SlotParam(elementType, name, Cardinality.ONE, defaultValue, spanOf(ctx));
        }

        return new Param.ValueParam(type, name, defaultValue, spanOf(ctx));
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

    List<Statement> buildStatements(List<SukoParser.TemplateStatementContext> ctxs) {
        List<Statement> statements = new ArrayList<>();
        for (SukoParser.TemplateStatementContext stmtCtx : ctxs) {
            statements.add(buildStatement(stmtCtx));
        }
        return statements;
    }

    Statement buildStatement(SukoParser.TemplateStatementContext ctx) {
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
            return new Statement.Interpolation(buildExpr(ctx.interpolation().expression()), spanOf(ctx.interpolation()));
        }
        if (ctx.textRun() != null) {
            return new Statement.TextRun(textOf(ctx.textRun()), spanOf(ctx.textRun()));
        }
        throw new IllegalStateException("templateStatement ainda não suportado: " + ctx.getText());
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

    private Statement.ForStmt buildForStmt(SukoParser.ForStmtContext ctx) {
        return new Statement.ForStmt(
            buildType(ctx.type()),
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
        return List.of(new Statement.Interpolation(buildExpr(exprCtx), spanOf(exprCtx)));
    }

    private Statement.HtmlElement buildHtmlElement(SukoParser.HtmlElementContext ctx) {
        if (ctx instanceof SukoParser.SelfClosingElementContext c) {
            return new Statement.HtmlElement(c.htmlName().getText(), buildAttributes(c.attribute()),
                List.of(), true, spanOf(ctx));
        }
        if (ctx instanceof SukoParser.OpenElementContext c) {
            return new Statement.HtmlElement(c.htmlName(0).getText(), buildAttributes(c.attribute()),
                buildStatements(c.templateStatement()), false, spanOf(ctx));
        }
        if (ctx instanceof SukoParser.VoidElementContext c) {
            return new Statement.HtmlElement(c.htmlName().getText(), buildAttributes(c.attribute()),
                List.of(), true, spanOf(ctx));
        }
        throw new IllegalStateException("Tipo de htmlElement desconhecido: " + ctx.getClass());
    }

    private List<Statement.Attribute> buildAttributes(List<SukoParser.AttributeContext> ctxs) {
        List<Statement.Attribute> attributes = new ArrayList<>();
        for (SukoParser.AttributeContext attrCtx : ctxs) {
            String name = attrCtx.htmlName().getText();
            Expr value = attrCtx.stringLiteral() != null
                ? buildStringLiteral(attrCtx.stringLiteral())
                : attrCtx.expression() != null
                    ? buildExpr(attrCtx.expression())
                    : new Expr.PrimaryExpr("true", spanOf(attrCtx));
            attributes.add(new Statement.Attribute(name, value, spanOf(attrCtx)));
        }
        return attributes;
    }

    Expr buildExpr(SukoParser.ExpressionContext ctx) {
        return switch (ctx) {
            case SukoParser.PrimaryExprContext c -> buildPrimary(c.primary());
            case SukoParser.AccessExprContext c ->
                new Expr.AccessExpr(buildExpr(c.expression()), c.Identifier().getText(), spanOf(c));
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
                new Expr.SafeAccessExpr(buildExpr(c.expression()), c.Identifier().getText(), spanOf(c));
            case SukoParser.ElvisExprContext c ->
                new Expr.ElvisExpr(buildExpr(c.expression(0)), buildExpr(c.expression(1)), spanOf(c));
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
        StringBuilder javaEscaped = new StringBuilder();
        for (SukoParser.StringPartContext partCtx : ctx.stringPart()) {
            javaEscaped.append(partCtx.getText());
        }
        List<Expr.StringPart> parts = List.of(new Expr.StringPart.Literal(javaEscaped.toString()));
        return new Expr.StringLiteralExpr(parts, spanOf(ctx));
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
        int start = ctx.getStart().getStartIndex();
        int stop = ctx.getStop().getStopIndex();
        while (start - 1 >= 0 && isSkippedWhitespace(source.charAt(start - 1))) {
            start--;
        }
        while (stop + 1 < source.length() && isSkippedWhitespace(source.charAt(stop + 1))) {
            stop++;
        }
        return source.substring(start, stop + 1);
    }

    private boolean isSkippedWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\r' || c == '\n';
    }

    private SourceSpan spanOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
        return new SourceSpan(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            ctx.getStart().getStartIndex(),
            ctx.getStop().getStopIndex());
    }
}
