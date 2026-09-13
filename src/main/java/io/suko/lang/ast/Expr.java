package io.suko.lang.ast;

import java.util.List;

public sealed interface Expr permits
    Expr.PrimaryExpr, Expr.StringLiteralExpr, Expr.AccessExpr, Expr.CallExpr,
    Expr.NotExpr, Expr.UnaryMinusExpr, Expr.BinaryExpr, Expr.TernaryExpr, Expr.ParenExpr,
    Expr.SafeAccessExpr, Expr.ElvisExpr {

    SourceSpan span();

    /** Identificador, inteiro, booleano ou "null" — texto literal recuperado do fonte. */
    record PrimaryExpr(String text, SourceSpan span) implements Expr {
    }

    record StringLiteralExpr(List<StringPart> parts, SourceSpan span) implements Expr {
    }

    sealed interface StringPart permits StringPart.Literal {
        record Literal(String javaEscapedText) implements StringPart {
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
}
