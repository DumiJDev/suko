package io.suko.lsp;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * O que há sob o cursor: percorre o AST (instruções, blocos de slot,
 * expressões onde uma chamada de componente pode aparecer como valor) e devolve
 * o nome de componente, de argumento, de slot ou o import que contém o índice.
 * Trabalha em índices de code points, os mesmos dos {@link SourceSpan}.
 */
final class AstQuery {

    sealed interface Cursor {
        SourceSpan span();

        /** Nome (possivelmente qualificado) de um componente chamado. */
        record CallName(String name, SourceSpan span) implements Cursor {
        }

        /** {@code title} em {@code Card(title = ...)}. */
        record ArgName(String callName, String argName, SourceSpan span) implements Cursor {
        }

        /** {@code header} em {@code Card() { header { ... } }}. */
        record SlotName(String callName, String slotName, SourceSpan span) implements Cursor {
        }

        record ImportName(ImportDecl decl) implements Cursor {
            @Override
            public SourceSpan span() {
                return decl.span();
            }
        }
    }

    private AstQuery() {
    }

    static Optional<Cursor> at(SukoFile file, int codePointIndex) {
        for (Cursor cursor : cursors(file)) {
            SourceSpan span = cursor.span();
            if (!span.isNone() && span.startIndex() <= codePointIndex && codePointIndex <= span.endIndex()) {
                return Optional.of(cursor);
            }
        }
        return Optional.empty();
    }

    /** Todos os pontos navegáveis do ficheiro, na ordem do fonte. */
    static List<Cursor> cursors(SukoFile file) {
        List<Cursor> out = new ArrayList<>();
        for (ImportDecl imp : file.imports()) {
            out.add(new Cursor.ImportName(imp));
        }
        for (ComponentDecl component : file.components()) {
            statements(component.body(), out);
        }
        return out;
    }

    private static void statements(List<Statement> statements, List<Cursor> out) {
        for (Statement statement : statements) {
            statement(statement, out);
        }
    }

    private static void statement(Statement statement, List<Cursor> out) {
        switch (statement) {
            case Statement.ComponentCallStmt call -> {
                out.add(new Cursor.CallName(call.componentName(), call.nameSpan()));
                for (Statement.Arg arg : call.args()) {
                    arg.name().ifPresent(name -> out.add(new Cursor.ArgName(call.componentName(), name, arg.span())));
                    expr(arg.value(), out);
                }
                for (Statement.SlotFill fill : call.slotFills()) {
                    // `children` implícito não tem nome escrito no fonte: nameSpan é NONE
                    if (!fill.nameSpan().isNone()) {
                        out.add(new Cursor.SlotName(call.componentName(), fill.paramName(), fill.nameSpan()));
                    }
                    statements(fill.body(), out);
                }
            }
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    expr(attribute.value(), out);
                }
                statements(element.children(), out);
            }
            case Statement.Interpolation interpolation -> expr(interpolation.expr(), out);
            case Statement.VarDecl varDecl -> expr(varDecl.value(), out);
            case Statement.IfStmt ifStmt -> {
                expr(ifStmt.condition(), out);
                statements(ifStmt.thenBranch(), out);
                statements(ifStmt.elseBranch(), out);
            }
            case Statement.ForStmt forStmt -> {
                expr(forStmt.iterable(), out);
                statements(forStmt.body(), out);
            }
            case Statement.SwitchStmt switchStmt -> {
                expr(switchStmt.subject(), out);
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    expr(switchCase.matchValue(), out);
                    statements(switchCase.body(), out);
                }
                statements(switchStmt.defaultCase(), out);
            }
            case Statement.TextRun textRun -> {
            }
        }
    }

    private static void expr(Expr expr, List<Cursor> out) {
        if (expr == null) {
            return;
        }
        switch (expr) {
            case Expr.CallExpr call -> {
                // Componente usado como valor: `var c = Card(...)`. Só nomes simples
                // com inicial maiúscula (a convenção que o SemanticChecker também usa).
                if (call.callee() instanceof Expr.PrimaryExpr callee && looksLikeComponent(callee.text())) {
                    out.add(new Cursor.CallName(callee.text(), callee.span()));
                } else {
                    expr(call.callee(), out);
                }
                call.args().forEach(arg -> expr(arg, out));
            }
            case Expr.StringLiteralExpr string -> {
                for (Expr.StringPart part : string.parts()) {
                    if (part instanceof Expr.StringPart.Interp interp) {
                        expr(interp.expr(), out);
                    }
                }
            }
            case Expr.AccessExpr access -> expr(access.target(), out);
            case Expr.SafeAccessExpr access -> expr(access.target(), out);
            case Expr.NotExpr not -> expr(not.operand(), out);
            case Expr.UnaryMinusExpr minus -> expr(minus.operand(), out);
            case Expr.BinaryExpr binary -> {
                expr(binary.left(), out);
                expr(binary.right(), out);
            }
            case Expr.TernaryExpr ternary -> {
                expr(ternary.condition(), out);
                expr(ternary.whenTrue(), out);
                expr(ternary.whenFalse(), out);
            }
            case Expr.ParenExpr paren -> expr(paren.inner(), out);
            case Expr.ElvisExpr elvis -> {
                expr(elvis.left(), out);
                expr(elvis.right(), out);
            }
            case Expr.PrimaryExpr primary -> {
            }
        }
    }

    static boolean looksLikeComponent(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }
}
