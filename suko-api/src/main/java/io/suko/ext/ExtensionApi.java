package io.suko.ext;

/**
 * Versão major da API de extensões. O core só aceita extensões compiladas contra o mesmo major.
 *
 * <p>2 (13b): {@code Statement.StateDecl}/{@code DerivedDecl}, {@code Statement.Directive} em
 * {@code HtmlElement.directives()}, {@code Expr.LambdaExpr}/{@code AssignExpr}/{@code UpdateExpr}/{@code NewExpr},
 * {@code ComponentDecl.reactive()}, {@code ProjectIndexEntry.reactive()}, {@code Severity.HINT},
 * {@code Target.capabilities()} e {@code ExprNames}.
 */
public final class ExtensionApi {
    public static final int VERSION = 2;

    private ExtensionApi() {
    }
}