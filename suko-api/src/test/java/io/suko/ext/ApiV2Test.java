package io.suko.ext;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectIndexEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ApiV2Test {

    static final SourceSpan S = SourceSpan.NONE;

    static Expr.PrimaryExpr p(String text) {
        return new Expr.PrimaryExpr(text, S);
    }

    @Test
    void versionIsTwo() {
        assertEquals(2, ExtensionApi.VERSION);
    }

    @Test
    void hintIsTheLastSeverity() {
        assertEquals(List.of(SukoDiagnostic.Severity.ERROR, SukoDiagnostic.Severity.WARNING,
            SukoDiagnostic.Severity.INFO, SukoDiagnostic.Severity.HINT), List.of(SukoDiagnostic.Severity.values()));
    }

    @Test
    void targetsHaveNoCapabilitiesByDefault() {
        Target target = new Target() {
            public String id() { return "t"; }
            public String componentType() { return "x.C"; }
            public Set<String> vocabularies() { return Set.of(); }
            public Emitted emit(ComponentDecl c, EmitContext ctx) { return new Emitted("x", "", List.of()); }
        };
        assertEquals(Set.of(), target.capabilities());
        assertEquals("reactive", Target.CAPABILITY_REACTIVE);
    }

    @Test
    void oldConstructorsStillWorkAndMeanNotReactive() {
        assertFalse(new ComponentDecl("A", List.of(), List.of(), List.of(), S, false).reactive());
        assertFalse(new ComponentDecl("A", List.of(), List.of(), List.of(), S, false, S).reactive());
        assertTrue(new Statement.HtmlElement("p", List.of(), List.of(), false, S).directives().isEmpty());
        assertFalse(new ProjectIndexEntry("a.B", "B", Path.of("B.sk"), true, List.of(), S, S).reactive());
    }

    @Test
    void newNodesPrettyPrintLikeTheSource() {
        Expr inc = new Expr.UpdateExpr(p("n"), "++", false, S);
        assertEquals("() -> n++", new Expr.LambdaExpr(List.of(), List.of(inc), false, S).pretty());
        Expr assign = new Expr.AssignExpr(p("d"), "=", p("e"), S);
        Expr add = new Expr.AssignExpr(p("n"), "+=", p("1"), S);
        assertEquals("e -> { d = e; n += 1; }", new Expr.LambdaExpr(List.of("e"), List.of(assign, add), true, S).pretty());
        assertEquals("--n", new Expr.UpdateExpr(p("n"), "--", true, S).pretty());
        assertEquals("new Todo(a, false)", new Expr.NewExpr("Todo", List.of(p("a"), p("false")), S).pretty());
    }

    @Test
    void directivesAreNotAttributes() {
        Statement.Directive on = new Statement.Directive(Statement.Directive.Kind.ON, "click",
            new Expr.LambdaExpr(List.of(), List.of(), true, S), S, S);
        Statement.HtmlElement button = new Statement.HtmlElement("button", List.of(), List.of(), false, S, List.of(on));
        assertTrue(button.attributes().isEmpty());
        assertEquals("click", button.directives().get(0).name());
    }
}