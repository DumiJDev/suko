package io.suko.lang.ast;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Os nomes (variáveis) que uma expressão lê, por ordem, sem os parâmetros das lambdas que os ligam nem os
 * nomes de membro ({@code t.title} lê {@code t}). Partilhado pelo core (grafo de {@code derived},
 * {@code DERIVED_CYCLE}) e pelos alvos (o que depende de state), para os dois lerem o mesmo grafo (13b).
 */
public final class ExprNames {

    private ExprNames() {
    }

    public static Set<String> of(Expr expr) {
        Set<String> out = new LinkedHashSet<>();
        collect(expr, Set.of(), out);
        return out;
    }

    /** Identificador Suko ({@code [A-Za-z_][A-Za-z0-9_]*}) que não é um literal ({@code true}, {@code false}, {@code null}). */
    public static boolean isIdentifier(String text) {
        if (text.isEmpty() || text.equals("true") || text.equals("false") || text.equals("null")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
            if (!(letter || (i > 0 && c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static void collect(Expr e, Set<String> bound, Set<String> out) {
        switch (e) {
            case Expr.PrimaryExpr p -> {
                if (isIdentifier(p.text()) && !bound.contains(p.text())) {
                    out.add(p.text());
                }
            }
            case Expr.StringLiteralExpr s -> {
                for (Expr.StringPart part : s.parts()) {
                    switch (part) {
                        case Expr.StringPart.Literal l -> { }
                        case Expr.StringPart.Interp i -> collect(i.expr(), bound, out);
                        case Expr.StringPart.SimpleInterp si -> {
                            if (!bound.contains(si.identifier())) {
                                out.add(si.identifier());
                            }
                        }
                    }
                }
            }
            case Expr.AccessExpr a -> collect(a.target(), bound, out);
            case Expr.SafeAccessExpr a -> collect(a.target(), bound, out);
            case Expr.CallExpr c -> {
                collect(c.callee(), bound, out);
                c.args().forEach(x -> collect(x, bound, out));
            }
            case Expr.NotExpr n -> collect(n.operand(), bound, out);
            case Expr.UnaryMinusExpr u -> collect(u.operand(), bound, out);
            case Expr.BinaryExpr b -> {
                collect(b.left(), bound, out);
                collect(b.right(), bound, out);
            }
            case Expr.TernaryExpr t -> {
                collect(t.condition(), bound, out);
                collect(t.whenTrue(), bound, out);
                collect(t.whenFalse(), bound, out);
            }
            case Expr.ParenExpr p -> collect(p.inner(), bound, out);
            case Expr.ElvisExpr x -> {
                collect(x.left(), bound, out);
                collect(x.right(), bound, out);
            }
            case Expr.LambdaExpr l -> {
                Set<String> inner = new HashSet<>(bound);
                inner.addAll(l.params());
                l.body().forEach(x -> collect(x, inner, out));
            }
            case Expr.AssignExpr a -> {
                collect(a.target(), bound, out);
                collect(a.value(), bound, out);
            }
            case Expr.UpdateExpr u -> collect(u.target(), bound, out);
            case Expr.NewExpr n -> n.args().forEach(x -> collect(x, bound, out));
        }
    }
}