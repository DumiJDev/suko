package io.suko.lang.security;

import io.suko.ext.SecurityOptions;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** S7 da revisão de segurança da 13b: lacunas de vocabulário e de testes dos nomes com ':' e de htmx/Alpine. */
class PrefixedAttributeS7Test {

    static List<SukoDiagnostic> diagnostics(String sk, SecurityOptions options) {
        return new JteCompiler("T.sk", sk, ExtensionRegistry.defaults(), List.of("jte"), options).compile()
            .diagnostics().getDiagnostics();
    }

    static Set<String> codes(String sk, SecurityOptions options) {
        return diagnostics(sk, options).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    static Set<String> codes(String sk) {
        return codes(sk, SecurityOptions.DEFAULT);
    }

    /** Dinâmico em atributos que o htmx/Alpine avaliam como código: nunca passa, qualquer que seja a grafia. */
    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <a Hx-On:Click=${x}>a</a> }",
        "component A(String x) { <a HX-ON:CLICK=${x}>a</a> }",
        "component A(String x) { <a hx-on--click=${x}>a</a> }",
        "component A(String x) { <a data-hx-on--click=${x}>a</a> }",
        "component A(String x) { <a Data-Hx-On:Click=${x}>a</a> }",
        "component A(String x) { <a X-On:Click=${x}>a</a> }",
    })
    void dynamicClientCodeAttributesNeverPass(String sk) {
        Set<String> codes = codes(sk);
        assertTrue(codes.contains("UNSAFE_SINK") || codes.contains("PARSE_ERROR"), sk + " -> " + codes);
    }

    @Test
    void xlinkHrefOnAScriptIsRefused() {
        String sk = "component A(String u) { <svg><script xlink:href=${u}></script></svg> }";
        assertTrue(codes(sk).contains("UNSAFE_SINK"), codes(sk).toString());
    }

    @Test
    void mixedCaseXlinkHrefGoesThroughSukoSafeUrl() {
        String sk = "component A(String u) { <svg><use Xlink:Href=${u}/></svg> }";
        var result = new JteCompiler("A.sk", sk).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        String jte = result.generatedJteSources().get("A.jte");
        assertTrue(jte.contains("SukoSafe.url(u)"), jte);
    }

    /** Namespaces XML: inertes, só documentam que compilam sem diagnósticos. */
    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <svg xmlns:xlink=\"http://www.w3.org/1999/xlink\"><use xlink:href=\"#a\"/></svg> }",
        "component A() { <pre xml:space=\"preserve\">a</pre> }",
    })
    void xmlNamespaceAttributesAreInert(String sk) {
        assertEquals(Set.of(), codes(sk), sk);
    }

    /** `hx-on::evento` tem dois ':' — erro de sintaxe, não aceite em silêncio (limitação documentada). */
    @Test
    void doubleColonHtmxFormIsASyntaxError() {
        String sk = "component A() { <a hx-on::after-request=\"go()\">a</a> }";
        assertTrue(codes(sk).contains("PARSE_ERROR"), codes(sk).toString());
    }

    /** Com CSP estrita, os atributos literais que o framework avalia como código avisam (CSP_INLINE). */
    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <a hx-on:click=\"go()\">a</a> }",
        "component A() { <a data-hx-on:click=\"go()\">a</a> }",
        "component A() { <div x-data=\"{ open: false }\">a</div> }",
        "component A() { <div data-x-data=\"{ open: false }\">a</div> }",
        "component A() { <a x-on:click=\"open = false\">a</a> }",
        "component A() { <a onclick=\"go()\">a</a> }",
    })
    void strictCspWarnsOnLiteralClientCodeAttributes(String sk) {
        SecurityOptions strict = SecurityOptions.DEFAULT.withStrictCsp(true);
        assertTrue(codes(sk, strict).contains("CSP_INLINE"), sk + " -> " + diagnostics(sk, strict));
        assertFalse(codes(sk).contains("CSP_INLINE"), "sem strictCsp não há aviso: " + sk);
    }
}
