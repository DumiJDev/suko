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
        if (ctx.htmlElement() != null) {
            return buildHtmlElement(ctx.htmlElement());
        }
        if (ctx.interpolation() != null) {
            return new Statement.Interpolation(buildExpr(ctx.interpolation().expression()), spanOf(ctx.interpolation()));
        }
        if (ctx.textRun() != null) {
            return new Statement.TextRun(textOf(ctx.textRun()), spanOf(ctx.textRun()));
        }
        throw new IllegalStateException("templateStatement ainda não suportado nesta tarefa: " + ctx.getText());
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
        if (ctx instanceof SukoParser.PrimaryExprContext c) {
            SukoParser.PrimaryContext primary = c.primary();
            if (primary.stringLiteral() != null) {
                return buildStringLiteral(primary.stringLiteral());
            }
            return new Expr.PrimaryExpr(primary.getText(), spanOf(primary));
        }
        throw new IllegalStateException("Tipo de expressão ainda não suportado nesta tarefa: " + ctx.getClass());
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
     * ficam nem em canal escondido. Um espaço logo antes de `{` ou `<` (ex.:
     * "Hello, {name}!") não pertence a NENHUM token: fica fora do intervalo
     * [start,stop] do textRun (que termina no último token visível, a
     * vírgula) e fora do próximo nó (que começa em `{`). O ctx.getStop() por
     * si só perde esse espaço — reproduzido em teste: o .jte gerado sem esta
     * correção era "Hello,${name}!" (sem espaço). A correção fica só do lado
     * Java: estende o fim do intervalo capturado sobre espaço em branco cru
     * imediatamente a seguir ao último token do textRun, até ao primeiro
     * carácter não-espaço (que será sempre o início de outro token/regra,
     * nunca outro textRun órfão, porque texto puramente espaço entre tags não
     * gera nó nenhum). Não requer mudança na gramática. */
    private String textOf(SukoParser.TextRunContext ctx) {
        int start = ctx.getStart().getStartIndex();
        int stop = ctx.getStop().getStopIndex();
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
