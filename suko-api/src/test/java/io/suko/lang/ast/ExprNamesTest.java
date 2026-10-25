package io.suko.lang.ast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExprNamesTest {

    static final SourceSpan S = SourceSpan.NONE;

    static Expr.PrimaryExpr p(String text) {
        return new Expr.PrimaryExpr(text, S);
    }

    @Test
    void literalsAreNotNames() {
        for (String literal : new String[] {"42", "true", "false", "null"}) {
            assertEquals(Set.of(), ExprNames.of(p(literal)), literal);
        }
        assertTrue(ExprNames.isIdentifier("_x1"));
        assertFalse(ExprNames.isIdentifier("1x"));
        assertFalse(ExprNames.isIdentifier("null"));
    }

    @Test
    void namesOfOperatorsCallsAndStringParts() {
        Expr sum = new Expr.BinaryExpr(p("a"), "+", new Expr.CallExpr(new Expr.AccessExpr(p("items"), "size", S), List.of(), S), S);
        assertEquals(List.of("a", "items"), List.copyOf(ExprNames.of(sum)));
        Expr string = new Expr.StringLiteralExpr(List.of(new Expr.StringPart.Literal("x"),
            new Expr.StringPart.SimpleInterp("n"), new Expr.StringPart.Interp(p("m"))), S);
        assertEquals(Set.of("n", "m"), ExprNames.of(string));
    }

    @Test
    void memberNamesAreNotVariables() {
        assertEquals(Set.of("t"), ExprNames.of(new Expr.AccessExpr(p("t"), "title", S)));
        assertEquals(Set.of("d"), ExprNames.of(new Expr.NewExpr("Todo", List.of(p("d")), S)));
    }

    @Test
    void lambdaParametersAreBound() {
        Expr body = new Expr.AssignExpr(p("draft"), "=", new Expr.CallExpr(new Expr.AccessExpr(p("e"), "value", S), List.of(), S), S);
        assertEquals(Set.of("draft"), ExprNames.of(new Expr.LambdaExpr(List.of("e"), List.of(body), false, S)));
        assertEquals(Set.of("n"), ExprNames.of(new Expr.UpdateExpr(p("n"), "++", true, S)));
    }
}