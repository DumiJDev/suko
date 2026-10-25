package io.suko.lang.ast;

import java.util.List;

public sealed interface Expr permits
    Expr.PrimaryExpr, Expr.StringLiteralExpr, Expr.AccessExpr, Expr.CallExpr,
    Expr.NotExpr, Expr.UnaryMinusExpr, Expr.BinaryExpr, Expr.TernaryExpr, Expr.ParenExpr,
    Expr.SafeAccessExpr, Expr.ElvisExpr,
    Expr.LambdaExpr, Expr.AssignExpr, Expr.UpdateExpr, Expr.NewExpr {

    SourceSpan span();

    /** Render this expression as a compact, human-readable string for documentation. */
    default String pretty() {
        return switch (this) {
            case PrimaryExpr p -> p.text();
            case StringLiteralExpr s -> "\"" + pretty(s.parts()) + "\"";
            case AccessExpr a -> a.target().pretty() + "." + a.memberName();
            case CallExpr c -> c.callee().pretty() + "(" + String.join(", ", c.args().stream().map(Expr::pretty).toList()) + ")";
            case NotExpr n -> "!" + n.operand().pretty();
            case UnaryMinusExpr u -> "-" + u.operand().pretty();
            case BinaryExpr b -> b.left().pretty() + " " + b.operator() + " " + b.right().pretty();
            case TernaryExpr t -> t.condition().pretty() + " ? " + t.whenTrue().pretty() + " : " + t.whenFalse().pretty();
            case ParenExpr p -> "(" + p.inner().pretty() + ")";
            case SafeAccessExpr s -> s.target().pretty() + "?." + s.memberName();
            case ElvisExpr e -> e.left().pretty() + " ?: " + e.right().pretty();
            case LambdaExpr l -> (l.params().size() == 1 ? l.params().get(0) : "(" + String.join(", ", l.params()) + ")")
                + " -> " + (l.block()
                    ? "{ " + String.join(" ", l.body().stream().map(x -> x.pretty() + ";").toList()) + " }"
                    : l.body().get(0).pretty());
            case AssignExpr a -> a.target().pretty() + " " + a.operator() + " " + a.value().pretty();
            case UpdateExpr u -> u.prefix() ? u.operator() + u.target().pretty() : u.target().pretty() + u.operator();
            case NewExpr n -> "new " + n.typeName() + "(" + String.join(", ", n.args().stream().map(Expr::pretty).toList()) + ")";
        };
    }

    /** Render a list of StringParts as a compact, human-readable string. */
    static String pretty(List<StringPart> parts) {
        StringBuilder sb = new StringBuilder();
        for (StringPart p : parts) {
            switch (p) {
                case StringPart.Literal l -> sb.append(l.javaEscapedText());
                case StringPart.Interp i -> sb.append("${").append(i.expr().pretty()).append("}");
                case StringPart.SimpleInterp s -> sb.append("$").append(s.identifier());
            }
        }
        return sb.toString();
    }

    /** Identificador, inteiro, booleano ou "null" — texto literal recuperado do fonte. */
    record PrimaryExpr(String text, SourceSpan span) implements Expr {
    }

    record StringLiteralExpr(List<StringPart> parts, SourceSpan span) implements Expr {
    }

    sealed interface StringPart permits StringPart.Literal, StringPart.Interp, StringPart.SimpleInterp {
        record Literal(String javaEscapedText) implements StringPart {
        }

        /** "${expr}" */
        record Interp(Expr expr) implements StringPart {
        }

        /** "$identificador" */
        record SimpleInterp(String identifier) implements StringPart {
        }
    }

    record AccessExpr(Expr target, String memberName, SourceSpan span) implements Expr {
    }

    record CallExpr(Expr callee, List<Expr> args, SourceSpan span) implements Expr {
    }

    record NotExpr(Expr operand, SourceSpan span) implements Expr {
    }

    record UnaryMinusExpr(Expr operand, SourceSpan span) implements Expr {
    }

    /** Cobre Mul/Add/Rel/Eq/And/Or — todos com a mesma forma (operador binário Java válido). */
    record BinaryExpr(Expr left, String operator, Expr right, SourceSpan span) implements Expr {
    }

    record TernaryExpr(Expr condition, Expr whenTrue, Expr whenFalse, SourceSpan span) implements Expr {
    }

    record ParenExpr(Expr inner, SourceSpan span) implements Expr {
    }

    record SafeAccessExpr(Expr target, String memberName, SourceSpan span) implements Expr {
    }

    record ElvisExpr(Expr left, Expr right, SourceSpan span) implements Expr {
    }

    /** {@code () -> corpo}, {@code e -> corpo}, {@code (e) -> { a; b; }} (13b). Só como valor de {@code on:} ou
     * argumento de uma chamada de componente. {@code body} tem uma expressão por instrução; {@code block}
     * indica que foi escrita com chavetas.
     */
    record LambdaExpr(List<String> params, List<Expr> body, boolean block, SourceSpan span) implements Expr {
    }

    /** {@code alvo = valor} / {@code alvo += valor} — só como instrução do corpo de uma lambda (13b). */
    record AssignExpr(Expr target, String operator, Expr value, SourceSpan span) implements Expr {
    }

    /** {@code ++alvo}, {@code alvo--}, ... — só como instrução do corpo de uma lambda (13b). */
    record UpdateExpr(Expr target, String operator, boolean prefix, SourceSpan span) implements Expr {
    }

    /** {@code new Tipo(args)}: Java tal como está no servidor; no cliente só records (13b). */
    record NewExpr(String typeName, List<Expr> args, SourceSpan span) implements Expr {
    }
}