package io.suko.lang;

import io.suko.lang.ast.*;

/**
 * Visitor sobre o AST que produz o texto de um template .jte por
 * ComponentDecl. Tradução próxima de 1:1 — expressões e statements sem
 * necessidade de transformação são apenas reconstruídos textualmente.
 */
public class JteEmitter {

    public String emit(ComponentDecl component) {
        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param)).append('\n');
        }
        out.append('\n');
        for (Statement statement : component.body()) {
            emitStatement(statement, out);
        }
        return out.toString();
    }

    private String jteParamDeclaration(Param param) {
        return switch (param) {
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name();
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name();
            case Param.SlotParam p -> "java.util.List<gg.jte.Content> " + p.name();
        };
    }

    private String javaType(Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(javaType(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(type.arrayDimensions()));
        return sb.toString();
    }

    private void emitStatement(Statement statement, StringBuilder out) {
        switch (statement) {
            case Statement.TextRun textRun -> out.append(textRun.text());
            case Statement.Interpolation interpolation ->
                out.append("${").append(emitExpr(interpolation.expr())).append('}');
            case Statement.HtmlElement element -> emitHtmlElement(element, out);
        }
    }

    private void emitHtmlElement(Statement.HtmlElement element, StringBuilder out) {
        out.append('<').append(element.tagName());
        for (Statement.Attribute attribute : element.attributes()) {
            out.append(' ').append(attribute.name()).append("=\"")
                .append("${").append(emitExpr(attribute.value())).append('}').append('"');
        }
        if (element.selfClosing()) {
            out.append("/>");
            return;
        }
        out.append('>');
        for (Statement child : element.children()) {
            emitStatement(child, out);
        }
        out.append("</").append(element.tagName()).append('>');
    }

    String emitExpr(Expr expr) {
        return switch (expr) {
            case Expr.PrimaryExpr primary -> primary.text();
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral);
            case Expr.AccessExpr access -> emitExpr(access.target()) + "." + access.memberName();
            case Expr.CallExpr call -> emitExpr(call.callee()) + "(" + emitArgs(call.args()) + ")";
            case Expr.NotExpr not -> "!" + emitExpr(not.operand());
            case Expr.UnaryMinusExpr unaryMinus -> "-" + emitExpr(unaryMinus.operand());
            case Expr.BinaryExpr binary ->
                emitExpr(binary.left()) + " " + binary.operator() + " " + emitExpr(binary.right());
            case Expr.TernaryExpr ternary -> emitExpr(ternary.condition()) + " ? "
                + emitExpr(ternary.whenTrue()) + " : " + emitExpr(ternary.whenFalse());
            case Expr.ParenExpr paren -> "(" + emitExpr(paren.inner()) + ")";
        };
    }

    private String emitArgs(java.util.List<Expr> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpr(args.get(i)));
        }
        return sb.toString();
    }

    private String emitStringLiteral(Expr.StringLiteralExpr stringLiteral) {
        StringBuilder sb = new StringBuilder("\"");
        for (Expr.StringPart part : stringLiteral.parts()) {
            if (part instanceof Expr.StringPart.Literal literal) {
                sb.append(literal.javaEscapedText());
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
