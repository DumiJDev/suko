package io.suko.jte;

import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HtmlSecurityRulesTest {

    static final SourceSpan S = SourceSpan.NONE;
    static final SecurityOptions O = SecurityOptions.DEFAULT;

    static Expr lit(String text) {
        return new Expr.StringLiteralExpr(List.of(new Expr.StringPart.Literal(text)), S);
    }

    static Expr interp(String... parts) { // alterna literal / identificador (índices ímpares = $ident)
        java.util.ArrayList<Expr.StringPart> list = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            list.add(i % 2 == 0 ? new Expr.StringPart.Literal(parts[i]) : new Expr.StringPart.SimpleInterp(parts[i]));
        }
        return new Expr.StringLiteralExpr(list, S);
    }

    static Expr id(String name) {
        return new Expr.PrimaryExpr(name, S);
    }

    @Test
    void literalsAreStringsWithoutInterpolationAndBooleanAttributes() {
        assertTrue(HtmlSecurityRules.isLiteral(lit("/carrinho")));
        assertTrue(HtmlSecurityRules.isLiteral(new Expr.PrimaryExpr("true", S)));
        assertFalse(HtmlSecurityRules.isLiteral(interp("/p/", "id")));
        assertFalse(HtmlSecurityRules.isLiteral(id("url")));
    }

    @Test
    void urlAttributesAreCaseInsensitiveAndCoverTheSpecList() {
        for (String a : new String[] {"href", "HREF", "src", "action", "formaction", "poster", "cite", "background",
            "manifest", "longdesc", "usemap", "codebase", "itemtype", "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete"}) {
            assertTrue(HtmlSecurityRules.isUrlAttribute("div", a, O), a);
        }
        assertTrue(HtmlSecurityRules.isUrlAttribute("object", "data", O));
        assertFalse(HtmlSecurityRules.isUrlAttribute("div", "data", O));   // `data` só em <object>
        assertFalse(HtmlSecurityRules.isUrlAttribute("a", "title", O));
        assertTrue(HtmlSecurityRules.isUrlAttribute("a", "data-href", O.withUrlAttributes(java.util.Set.of("data-href"))));
        assertTrue(HtmlSecurityRules.isSrcset("srcset"));
        assertTrue(HtmlSecurityRules.isSrcset("imagesrcset"));
        assertTrue(HtmlSecurityRules.isPing("ping"));
        assertTrue(HtmlSecurityRules.isImageContext("img", "src"));
        assertTrue(HtmlSecurityRules.isImageContext("source", "srcset"));
        assertFalse(HtmlSecurityRules.isImageContext("a", "href"));
    }

    @Test
    void originConstantNeedsAFixedSchemeHostAndSlash() {
        assertTrue(HtmlSecurityRules.originConstant("iframe", "src", interp("https://www.youtube.com/embed/", "id"), O));
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://www.youtube.com", "id"), O));   // sem '/'
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://", "host", "/x"), O));          // host dinâmico
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://user@evil.example/", "id"), O)); // '@' no host
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("javascript://x/", "id"), O));
        assertFalse(HtmlSecurityRules.originConstant("div", "id", interp("https://a.example/", "id"), O));            // atributo não aplicável
    }

    @Test
    void styleDeclarationsAcceptOnlyDynamicValuesAsWholeValues() {
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("--pct: ", "p", "%")).isPresent());
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("width: ", "w", "px; color: red")).isPresent());
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("width: -", "w", "")).isPresent());
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("background: url(", "u", ")")).isPresent());
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("", "css", "")).isPresent());                  // valor sem propriedade
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("width: 1", "w", "x y")).isPresent());          // mistura
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("width: ", "a", ", ", "b", "")).isPresent());   // dois por declaração
    }

    @Test
    void noopenerAppliesToTargetsThatCanOpenAnotherContext() {
        Statement.HtmlElement a = el("a", new Statement.Attribute("target", lit("_blank"), false, S));
        Statement.HtmlElement self = el("a", new Statement.Attribute("target", lit("_self"), false, S));
        Statement.HtmlElement dynamic = el("form", new Statement.Attribute("target", id("t"), false, S));
        Statement.HtmlElement none = el("a");
        assertTrue(HtmlSecurityRules.needsNoopener(a));
        assertFalse(HtmlSecurityRules.needsNoopener(self));
        assertTrue(HtmlSecurityRules.needsNoopener(dynamic));
        assertFalse(HtmlSecurityRules.needsNoopener(none));
        assertFalse(HtmlSecurityRules.needsNoopener(el("div", new Statement.Attribute("target", lit("_blank"), false, S))));
    }

    @Test
    void trustedArgumentRecognisesTheReservedFunctions() {
        Expr call = new Expr.CallExpr(id("trustedUrl"), List.of(id("x")), S);
        assertEquals(Optional.of(id("x")), HtmlSecurityRules.trustedArgument(call, "trustedUrl"));
        assertTrue(HtmlSecurityRules.trustedArgument(call, "trustedStyle").isEmpty());
        assertTrue(HtmlSecurityRules.trustedArgument(id("x"), "trustedUrl").isEmpty());
    }

    @Test
    void jteSyntaxInTextIsMadeInert() {
        assertEquals("a${\"@\"}b.com", HtmlSecurityRules.neutralizeJteSyntax("a@b.com"));
        assertEquals("${\"@\"}if(true){X}${\"@\"}endif", HtmlSecurityRules.neutralizeJteSyntax("@if(true){X}@endif"));
        assertEquals("sem arrobas", HtmlSecurityRules.neutralizeJteSyntax("sem arrobas"));
    }

    private static Statement.HtmlElement el(String tag, Statement.Attribute... attrs) {
        return new Statement.HtmlElement(tag, List.of(attrs), List.of(), false, S);
    }
}
