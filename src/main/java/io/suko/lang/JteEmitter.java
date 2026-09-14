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
            case Statement.IfStmt ifStmt -> emitIfStmt(ifStmt, out);
            case Statement.ForStmt forStmt -> emitForStmt(forStmt, out);
            case Statement.SwitchStmt switchStmt -> emitSwitchStmt(switchStmt, out);
            case Statement.ComponentCallStmt call -> emitComponentCall(call, out);
        }
    }

    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out) {
        out.append("@template.").append(call.componentName()).append('(');
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) out.append(", ");
            Statement.Arg arg = call.args().get(i);
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value()));
        }
        out.append(")\n");
    }

    private void emitSwitchStmt(Statement.SwitchStmt switchStmt, StringBuilder out) {
        String subject = emitExpr(switchStmt.subject());
        boolean first = true;
        for (Statement.SwitchCase switchCase : switchStmt.cases()) {
            out.append(first ? "@if(" : "@elseif(").append(subject).append(".equals(")
                .append(emitExpr(switchCase.matchValue())).append("))\n");
            for (Statement statement : switchCase.body()) {
                emitStatement(statement, out);
            }
            out.append('\n');
            first = false;
        }
        if (!switchStmt.defaultCase().isEmpty()) {
            out.append("@else\n");
            for (Statement statement : switchStmt.defaultCase()) {
                emitStatement(statement, out);
            }
            out.append('\n');
        }
        out.append("@endif\n");
    }

    private void emitForStmt(Statement.ForStmt forStmt, StringBuilder out) {
        out.append("@for(").append(javaType(forStmt.itemType())).append(' ').append(forStmt.itemName())
            .append(" : ").append(emitExpr(forStmt.iterable())).append(")\n");
        for (Statement statement : forStmt.body()) {
            emitStatement(statement, out);
        }
        out.append("\n@endfor\n");
    }

    private void emitIfStmt(Statement.IfStmt ifStmt, StringBuilder out) {
        out.append("@if(").append(emitExpr(ifStmt.condition())).append(")\n");
        for (Statement statement : ifStmt.thenBranch()) {
            emitStatement(statement, out);
        }
        if (!ifStmt.elseBranch().isEmpty()) {
            out.append("\n@else\n");
            for (Statement statement : ifStmt.elseBranch()) {
                emitStatement(statement, out);
            }
        }
        out.append("\n@endif\n");
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
            // DESVIO DO BRIEF: quando o callee de uma chamada é ele próprio um
            // SafeAccessExpr (ex.: `label?.length()`), a gramática produz
            // CallExpr(callee=SafeAccessExpr(target, member), args) — a chamada
            // "()" tem de aplicar-se DENTRO do ramo não-nulo do ternário de
            // desaçucaramento, não ao resultado do ternário já fechado. Emitir
            // cada nó independentemente e concatenar "()" a seguir (como o
            // brief propunha literalmente) produziria
            // "(alvo == null ? null : alvo.membro)()", que não compila (não se
            // pode invocar o resultado de um ternário) e, mesmo que compilasse,
            // não protegeria a própria chamada do método contra NPE. Por isso
            // este caso é tratado aqui, como uma forma só sua, antes do caso
            // genérico de CallExpr.
            case Expr.CallExpr call when call.callee() instanceof Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target());
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName()
                    + "(" + emitArgs(call.args()) + "))";
            }
            case Expr.CallExpr call -> emitExpr(call.callee()) + "(" + emitArgs(call.args()) + ")";
            case Expr.NotExpr not -> "!" + emitExpr(not.operand());
            case Expr.UnaryMinusExpr unaryMinus -> "-" + emitExpr(unaryMinus.operand());
            case Expr.BinaryExpr binary ->
                emitExpr(binary.left()) + " " + binary.operator() + " " + emitExpr(binary.right());
            case Expr.TernaryExpr ternary -> emitExpr(ternary.condition()) + " ? "
                + emitExpr(ternary.whenTrue()) + " : " + emitExpr(ternary.whenFalse());
            case Expr.ParenExpr paren -> "(" + emitExpr(paren.inner()) + ")";
            case Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target());
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName() + ")";
            }
            case Expr.ElvisExpr elvis -> {
                String left = emitExpr(elvis.left());
                yield "(" + left + " == null ? " + emitExpr(elvis.right()) + " : " + left + ")";
            }
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
