package io.suko.lang.ast;

import java.util.List;

public sealed interface Expr permits Expr.PrimaryExpr, Expr.StringLiteralExpr {

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
}
