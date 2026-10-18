package io.suko.lang;

import io.suko.lang.ast.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S6 (revisão de segurança da 13b): quem constrói o AST sem ler {@code syntaxIssues()} (ProjectIndex, LSP,
 * extensões de terceiros) nunca pode fazer o emissor largar em silêncio uma diretiva ou um handler.
 */
class JteEmitterFailClosedTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(int n) { <b on:click=${() -> n++}>x</b> }",
        "component A(String s) { <input bind:value=${s}/> }",
    })
    void directivesAreNeverEmittedSilently(String source) {
        ComponentDecl component = SukoReactiveSyntaxTest.parse(source).components().get(0);
        assertThrows(IllegalStateException.class, () -> new JteEmitter().emit(component), source);
    }

    @Test
    void lambdaAssignmentAndUpdateAsAttributeValueAreRejected() {
        SourceSpan s = SourceSpan.NONE;
        Expr n = new Expr.PrimaryExpr("n", s);
        List<Expr> values = List.of(
            new Expr.LambdaExpr(List.of(), List.of(n), false, s),
            new Expr.AssignExpr(n, "=", new Expr.PrimaryExpr("1", s), s),
            new Expr.UpdateExpr(n, "++", false, s));
        for (Expr value : values) {
            Statement.HtmlElement el = new Statement.HtmlElement("a", List.of(new Statement.Attribute("title", value, false, s)),
                List.of(), true, s);
            ComponentDecl component = new ComponentDecl("A", List.of(), List.of(), List.of(el), s, false, s);
            assertThrows(IllegalStateException.class, () -> new JteEmitter().emit(component), value.toString());
        }
    }
}
