package io.suko.lang.security;

import gg.jte.TemplateEngine;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.output.StringOutput;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.support.SukoSafeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13b, Q4: `prefixo:nome` em atributos. Os ramos `:` do HtmlSecurityChecker passam a ser código vivo;
 * `on:`/`bind:` são sintaxe Suko e nunca chegam ao checker como atributo `on*`.
 */
class PrefixedAttributeSecurityTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics();
    }

    static Set<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <a hx-on:click=${x}>a</a> }",
        "component A(String x) { <a x-on:click=${x}>a</a> }",
        "component A(String x) { <a data-hx-on:click=${x}>a</a> }",
        "component A(String x) { <a hx-on:click=\"${x}\">a</a> }",
        "component A(String v) { <a online:x=${v}>a</a> }",
        "component A(String x) { <button onclick=${x}>b</button> }",
        "reactive component A(String x) { state int n = 0; <button onclick=${x} on:click=${() -> n++}>b</button> }",
    })
    void dynamicCodeAttributesWithAColonAreUnsafeSinks(String sk) {
        assertTrue(codes(sk).contains("UNSAFE_SINK"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <a hx-on:click=\"go()\">a</a> }",
        "component A() { <a x-on:click=\"open = false\">a</a> }",
        "component A(String u) { <svg><use xlink:href=${u}/></svg> }",
        "component A() { <svg><use xlink:href=\"#icon\"/></svg> }",
    })
    void literalCodeAttributesAndDynamicXlinkHrefCompile(String sk) {
        assertEquals(Set.of(), codes(sk), sk + " -> " + diagnostics(sk));
    }

    @Test
    void onAndBindAreNotHtmlEventAttributes() {
        String sk = "reactive component A() { state String s = \"\"; state int n = 0;"
            + " <button on:click=${() -> n++}>b</button> <input bind:value=${s}/> }";
        assertFalse(codes(sk).contains("UNSAFE_SINK"), diagnostics(sk).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <button on:click=${() -> go()}>b</button> }",
        "component A(String s) { <input bind:value=${s}/> }",
    })
    void outsideAReactiveComponentTheyAreOnlyReactiveRequired(String sk) {
        assertEquals(Set.of("REACTIVE_REQUIRED"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void uppercaseReservedPrefixIsOnlyASyntaxError() {
        String sk = "reactive component A() { state int n = 0; <button On:click=${() -> n++}>b</button> }";
        assertEquals(Set.of("PARSE_ERROR"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void atSignIsStillASyntaxError() {
        assertEquals(Set.of("PARSE_ERROR"), codes("component A() { <a @click=\"go()\">a</a> }"));
    }

    @Test
    void dynamicXlinkHrefGoesThroughSukoSafeUrl() throws Exception {
        String sk = "component A(String u) { <svg><use xlink:href=${u}/></svg> }";
        String jte = new JteCompiler("A.sk", sk).compile().generatedJteSources().get("A.jte");
        assertTrue(jte.contains("xlink:href=\"${io.suko.generated.SukoSafe.url(u)}\""), jte);

        String html = render(sk, Map.of("u", "javascript:alert(1)"), false);
        assertTrue(html.contains("xlink:href=\"about:invalid#suko-blocked\""), html);
        assertTrue(render(sk, Map.of("u", "#icon"), false).contains("xlink:href=\"#icon\""));
    }

    @Test
    void namesWithAColonPassTheOwaspHtmlPolicyOfTheRealEngine() throws Exception {
        String sk = "component A(String u) { <svg><use xlink:href=${u}/></svg> <a hx-on:click=\"go()\">a</a> }";
        String html = render(sk, Map.of("u", "#icon"), true);
        assertTrue(html.contains("xlink:href=\"#icon\"") && html.contains("hx-on:click=\"go()\""), html);
    }

    /** Compila com o JteCompiler (SukoSafe por omissão) e renderiza A.jte com o motor real. */
    private static String render(String sk, Map<String, Object> params, boolean owaspPolicy) throws Exception {
        var result = new JteCompiler("A.sk", sk).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        Path dir = Files.createTempDirectory("suko-prefixed");
        for (var e : result.generatedJteSources().entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
        TemplateEngine engine = SukoSafeSupport.engine(dir);
        if (owaspPolicy) {
            engine.setHtmlPolicy(new OwaspHtmlPolicy());
        }
        StringOutput out = new StringOutput();
        engine.render("A.jte", new HashMap<>(params), out);
        return out.toString();
    }
}
