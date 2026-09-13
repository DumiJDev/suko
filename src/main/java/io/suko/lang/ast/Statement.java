package io.suko.lang.ast;

import java.util.List;

public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation {

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
}
