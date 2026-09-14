package io.suko.lang.ast;

import java.util.List;

public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation,
        Statement.IfStmt, Statement.ForStmt, Statement.SwitchStmt, Statement.ComponentCallStmt {

    SourceSpan span();

    record HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                        boolean selfClosing, SourceSpan span) implements Statement {
    }

    record Attribute(String name, Expr value, SourceSpan span) {
    }

    record TextRun(String text, SourceSpan span) implements Statement {
    }

    record Interpolation(Expr expr, SourceSpan span) implements Statement {
    }

    record IfStmt(Expr condition, List<Statement> thenBranch, List<Statement> elseBranch,
                  SourceSpan span) implements Statement {
    }

    record ForStmt(Type itemType, String itemName, Expr iterable, List<Statement> body,
                   SourceSpan span) implements Statement {
    }

    // `defaultCase` vazio = `default` ausente; a checagem de exaustividade é
    // semântica, fora deste subprojeto.
    record SwitchStmt(Expr subject, List<SwitchCase> cases, List<Statement> defaultCase,
                      SourceSpan span) implements Statement {
    }

    record SwitchCase(Expr matchValue, List<Statement> body) {
    }

    record ComponentCallStmt(String componentName, List<Arg> args, SourceSpan span) implements Statement {
    }

    record Arg(java.util.Optional<String> name, Expr value) {
    }
}
