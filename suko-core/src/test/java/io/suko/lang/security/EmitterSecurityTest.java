package io.suko.lang.security;

import io.suko.ext.SecurityOptions;
import io.suko.lang.JteCompiler;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmitterSecurityTest {

    static final String PKG = SecurityOptions.DEFAULT.generatedPackage();

    /** Compila um único componente e devolve o .jte do alvo jte. */
    static String jte(String sk) {
        var result = new JteCompiler("T.sk", sk, ExtensionRegistry.defaults(), List.of("jte"), SecurityOptions.DEFAULT).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        return result.generatedJteSources().values().iterator().next();
    }

    @Test
    void dynamicUrlAttributeGoesThroughSukoSafe() {
        String out = jte("component A(String url) { <a href=${url}>x</a> }");
        assertTrue(out.contains("href=\"${" + PKG + ".SukoSafe.url(url)}\""), out);
    }

    @Test
    void interpolatedLiteralUrlIsWrappedAsAWhole() {
        String out = jte("component A(String id) { <a href=\"/p/${id}\">x</a> }");
        assertTrue(out.contains(PKG + ".SukoSafe.url(\"/p/\" + (id)"), out);   // valor inteiro, nunca por partes
    }

    @Test
    void staticLiteralUrlIsUntouched() {
        String out = jte("component A() { <a href=\"/carrinho\">x</a> }");
        assertFalse(out.contains("SukoSafe"), out);
    }

    @Test
    void srcsetPingAndImageContexts() {
        String out = jte("component A(String s, String p) { <img src=${s} srcset=${s}/> <a href=\"/\" ping=${p}>x</a> }");
        assertTrue(out.contains("SukoSafe.url(s)"), out);          // sem imageDataTypes, img usa url()
        assertTrue(out.contains("SukoSafe.srcset(s)"), out);
        assertTrue(out.contains("SukoSafe.ping(p)"), out);
    }

    @Test
    void imageContextUsesImageUrlOnlyWhenConfigured() {
        SecurityOptions o = SecurityOptions.DEFAULT.withImageDataTypes(java.util.Set.of("png"));
        var result = new JteCompiler("T.sk", "component A(String s) { <img src=${s} srcset=${s}/> }",
            ExtensionRegistry.defaults(), List.of("jte"), o).compile();
        String out = result.generatedJteSources().values().iterator().next();
        assertTrue(out.contains("SukoSafe.imageUrl(s)") && out.contains("SukoSafe.imageSrcset(s)"), out);
    }

    @Test
    void noopenerIsAddedForTargetBlank() {
        assertTrue(jte("component A(String u) { <a href=${u} target=\"_blank\">x</a> }").contains("rel=\"${\"noopener\"}\""));
        assertTrue(jte("component A() { <a href=\"/x\" target=\"_blank\" rel=\"nofollow\">x</a> }").contains("rel=\"${\"nofollow noopener\"}\""));
        assertTrue(jte("component A(String r) { <a href=\"/x\" target=\"_blank\" rel=${r}>x</a> }").contains("SukoSafe.rel(r)"));
        assertFalse(jte("component A() { <a href=\"/x\" target=\"_self\">x</a> }").contains("noopener"));
        assertFalse(jte("component A() { <a href=\"/x\" target=\"_blank\" rel=\"opener\">x</a> }").contains("noopener"));
    }

    @Test
    void originConstantInterpolationsUsePathSegment() {
        String out = jte("component A(String id) { <iframe src=\"https://www.youtube.com/embed/${id}\"></iframe> }");
        assertTrue(out.contains("https://www.youtube.com/embed/\" + " + PKG + ".SukoSafe.pathSegment(id)"), out);
    }

    @Test
    void styleDeclarationsUseCssValue() {
        String out = jte("component A(int p) { <div style=\"--pct: ${p}%; color: red\">x</div> }");
        assertTrue(out.contains(PKG + ".SukoSafe.cssValue(p)"), out);
    }

    @Test
    void trustedUrlSkipsTheCheckAndTrustedStyleToo() {
        String url = jte("component A(String u) { <a href=${trustedUrl(u)}>x</a> }");
        assertTrue(url.contains("href=\"${u}\"") && !url.contains("SukoSafe"), url);
        String style = jte("component A(String s) { <div style=${trustedStyle(s)}>x</div> }");
        assertTrue(style.contains("style=\"${s}\"") && !style.contains("SukoSafe"), style);
    }

    @Test
    void atSignsInTextAreInert() {
        String out = jte("component A() { <p>mail a@b.com e @if(true) x</p> }");
        assertTrue(out.contains("a${\"@\"}b.com") && out.contains("${\"@\"}if(true)"), out);
    }

    @Test
    void emittedTemplatesNeverContainUnsafe() {
        String out = jte("component A(String u, String x) { <a href=${u} title=${x}>${x}</a> }");
        assertFalse(out.contains("$unsafe") || out.contains("@raw"), out);
    }

    @Test
    void nullUrlOmitsTheAttribute() throws Exception {
        String html = RenderHarness.render("component A(String url) { <a href=${url}>x</a> }", "A", Map.of("url", RenderHarness.NULL));
        assertFalse(html.contains("href"), html);
    }

    @Test
    void javascriptUrlIsReplacedAfterRendering() throws Exception {
        String html = RenderHarness.render("component A(String url) { <a href=${url}>x</a> }", "A", Map.of("url", "java\tscript:alert(1)"));
        assertTrue(html.contains("href=\"about:invalid#suko-blocked\""), html);
    }

    @Test
    void atSignDirectivesAreLiteralTextAndEmailsSurvive() throws Exception {
        String html = RenderHarness.render("component A() { <p>a@b.com @if(true) X @endif</p> }", "A", Map.of());
        assertTrue(html.contains("a@b.com @if(true) X @endif"), html);
    }

    /** Só o alvo e o vocabulário JTE, sem o HtmlSecurityChecker: este teste fixa o que o EMISSOR faz
     *  com usos indevidos de trusted*, que o checker (HtmlSecurityCheckerTest) recusa antes. */
    static String jteWithoutChecker(String sk) {
        var ext = new io.suko.ext.SukoExtension() {
            public String id() { return "io.suko.jte"; }
            public int apiVersion() { return io.suko.ext.ExtensionApi.VERSION; }
            public void register(io.suko.ext.ExtensionContext ctx) {
                ctx.target(new io.suko.jte.JteTarget());
                ctx.vocabulary(new io.suko.jte.HtmlVocabulary());
            }
        };
        var result = new JteCompiler("T.sk", sk, ExtensionRegistry.of(List.of(ext)), List.of("jte"), SecurityOptions.DEFAULT).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        return result.generatedJteSources().values().iterator().next();
    }

    @Test
    void trustedFunctionsAreOnlyHonouredOnTheirOwnAttributes() {
        assertFalse(jteWithoutChecker("component A(String u) { <a href=${trustedUrl(u)}>x</a> }").contains("SukoSafe"));
        String onclick = jteWithoutChecker("component A(String u) { <a href=\"/\" onclick=${trustedUrl(u)}>x</a> }");
        assertTrue(onclick.contains("onclick=\"${trustedUrl(u)}\""), onclick);
        String style = jteWithoutChecker("component A(String s) { <div style=${trustedStyle(s)}>x</div> }");
        assertTrue(style.contains("style=\"${s}\"") && !style.contains("SukoSafe"), style);
        String styleOnHref = jteWithoutChecker("component A(String s) { <a href=${trustedStyle(s)}>x</a> }");
        assertTrue(styleOnHref.contains("SukoSafe.url(trustedStyle(s))"), styleOnHref);
        String urlOnStyle = jteWithoutChecker("component A(String s) { <div style=${trustedUrl(s)}>x</div> }");
        assertTrue(urlOnStyle.contains("style=\"${trustedUrl(s)}\""), urlOnStyle);
        String html = jteWithoutChecker("component A(String s) { <a href=${trustedHtml(s)}>x</a> <p title=${trustedHtml(s)}>y</p> }");
        assertTrue(html.contains("SukoSafe.url(trustedHtml(s))") && html.contains("title=\"${trustedHtml(s)}\""), html);
    }

    @Test
    void booleanRelWithBlankTargetBecomesNoopener() {
        String out = jte("component A() { <a href=\"/x\" target=\"_blank\" rel>x</a> }");
        assertTrue(out.contains("rel=\"${\"noopener\"}\"") && !out.contains("SukoSafe"), out);
    }
}
