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

import static org.junit.jupiter.api.Assertions.*;

class HtmlSecurityCheckerTest {

    /** Alvo sem vocabulários: o checker de HTML não deve correr. */
    static final class PlainExtension implements io.suko.ext.SukoExtension {
        public String id() { return "io.suko.test.plain"; }
        public int apiVersion() { return io.suko.ext.ExtensionApi.VERSION; }
        public void register(io.suko.ext.ExtensionContext ctx) {
            ctx.target(new io.suko.ext.Target() {
                public String id() { return "plain"; }
                public String componentType() { return "java.lang.Object"; }
                public Set<String> vocabularies() { return Set.of(); }
                public io.suko.ext.Emitted emit(io.suko.lang.ast.ComponentDecl c, io.suko.ext.EmitContext e) {
                    return new io.suko.ext.Emitted("A.txt", "", List.of());
                }
            });
        }
    }

    static List<SukoDiagnostic> diagnostics(String sk) {
        return diagnostics(sk, SecurityOptions.DEFAULT);
    }

    static List<SukoDiagnostic> diagnostics(String sk, SecurityOptions options) {
        return new JteCompiler("T.sk", sk, ExtensionRegistry.defaults(), List.of("jte"), options)
            .compile().diagnostics().getDiagnostics();
    }

    static boolean has(List<SukoDiagnostic> d, String code) {
        return d.stream().anyMatch(x -> x.code().equals(code));
    }

    static void assertSink(String sk) {
        List<SukoDiagnostic> d = diagnostics(sk);
        assertTrue(has(d, "UNSAFE_SINK"), sk + " -> " + d);
    }

    static void assertClean(String sk) {
        List<SukoDiagnostic> d = diagnostics(sk);
        assertFalse(has(d, "UNSAFE_SINK"), sk + " -> " + d);
        assertFalse(has(d, "PARSE_ERROR"), sk + " -> " + d);   // sem isto um caso que nem faz parse passava
    }

    // ---- um teste por entrada da M2 (e o negativo estático) -----------------

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <script>${x}</script> }",
        "component A(String x) { <style>${x}</style> }",
        "component A(String x) { <noscript>${x}</noscript> }",
        "component A(String x) { <noscript><p>${x}</p></noscript> }",
        "component A(String x) { <svg><foreignObject>${x}</foreignObject></svg> }",
        "component A(Component children) { <script>${children}</script> }",
        "component A(Component children) { <style>${children}</style> }",
        "component A(Component children) { <noscript>${children}</noscript> }",
        "component A(String x) { <button onclick=${x}>b</button> }",
        "component A(String x) { <button onClick=${x}>b</button> }",
        "component A(String x) { <button ONCLICK=${x}>b</button> }",
        "component A(String x) { <iframe srcdoc=${x}></iframe> }",
        "component A(String x) { <div style=${x}>b</div> }",
        "component A(String x) { <div style=\"background: url(${x})\">b</div> }",
        "component A(String x) { <script src=${x}></script> }",
        "component A(String x) { <script type=${x}>1</script> }",
        "component A(String x) { <svg><script href=${x}></script></svg> }",
        "component A(String x) { <link rel=${x} href=\"/a.css\"> }",
        "component A(String x) { <link rel=\"stylesheet\" href=${x}> }",
        "component A(String x) { <link href=${x}> }",
        "component A(String x) { <meta http-equiv=${x} content=\"1\"> }",
        "component A(String x) { <meta charset=${x}> }",
        "component A(String x) { <meta name=${x} content=\"c\"> }",
        "component A(String x) { <meta http-equiv=\"refresh\" content=${x}> }",
        "component A(String x) { <meta name=\"referrer\" content=${x}> }",
        "component A(String x) { <svg><animate attributeName=${x} values=\"1\"/></svg> }",
        "component A(String x) { <svg><animate attributeName=\"href\" values=${x}/></svg> }",
        "component A(String x) { <svg><set attributeName=\"href\" to=${x}/></svg> }",
        "component A(String x) { <svg><animateMotion from=${x}/></svg> }",
        "component A(String x) { <svg><animateTransform by=${x}/></svg> }",
        "component A(String x) { <base href=${x}> }",
        "component A(String x) { <iframe src=${x}></iframe> }",
        "component A(String x) { <frame src=${x}></frame> }",
        "component A(String x) { <object data=${x}></object> }",
        "component A(String x) { <embed src=${x}> }",
        "component A(String x) { <div x-data=${x}>b</div> }",
        "component A(String x) { <div x-html=${x}>b</div> }",
        "component A(String x) { <div hx-on-click=${x}>b</div> }",
        "component A(String x) { <div hx-on=${x}>b</div> }",
        "component A(String x) { <button onclick=\"f('${x}')\">b</button> }",
        "component A(String x) { <svg><script>${x}</script></svg> }"
    })
    void dynamicValueInASinkIsRejected(String sk) {
        assertSink(sk);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <script>var a = 1;</script> }",
        "component A() { <style>p</style> }",
        "component A() { <noscript><p>sem js</p></noscript> }",
        "component A() { <button onclick=\"go()\">b</button> }",
        "component A() { <iframe srcdoc=\"hello\"></iframe> }",
        "component A() { <div style=\"color: red\">b</div> }",
        "component A() { <script src=\"/app.js\"></script> }",
        "component A() { <link rel=\"stylesheet\" href=\"/a.css\"> }",
        "component A() { <meta charset=\"utf-8\"> }",
        "component A() { <base href=\"/\"> }",
        "component A() { <iframe src=\"https://www.youtube.com/embed/x\"></iframe> }",
        "component A() { <div x-data=\"{ open: false }\" x-show=\"open\">b</div> }",
        "component A(String nonce) { <script nonce=${nonce}>var a = 1;</script> }",
        "component A(String h) { <script src=\"/a.js\" integrity=${h}></script> }",
        "component A(String x) { <link rel=\"canonical\" href=${x}> }",
        "component A(String x) { <div title=${x} class=${x} id=${x} data-x=${x}>b</div> }",
        "component A(String x) { <a href=${x}>l</a> }"
    })
    void staticOrHarmlessUsesAreAccepted(String sk) {
        assertClean(sk);
    }

    @Test
    void originConstantExceptionAcceptsSegmentedEmbeds() {
        assertClean("component A(String id) { <iframe src=\"https://www.youtube.com/embed/${id}\"></iframe> }");
        assertSink("component A(String id) { <iframe src=\"https://${id}/embed\"></iframe> }");
        assertSink("component A(String id) { <iframe src=\"${id}/embed\"></iframe> }");
    }

    @Test
    void styleDeclarationExceptionAcceptsWholeValues() {
        assertClean("component A(int p) { <div style=\"--pct: ${p}%\">b</div> }");
        assertClean("component A(String w) { <div style=\"width: ${w}px; color: red\">b</div> }");
        assertSink("component A(String c) { <div style=\"${c}\">b</div> }");
    }

    @Test
    void urlAttributeCannotTakeAComponent() {
        assertSink("component A(Component children) { <a href=${children}>l</a> }");
    }

    @Test
    void configurableCodeAttributesExtendTheList() {
        SecurityOptions o = SecurityOptions.DEFAULT.withCodeAttributes(Set.of("data-eval"));
        assertTrue(has(diagnostics("component A(String x) { <div data-eval=${x}>b</div> }", o), "UNSAFE_SINK"));
        assertFalse(has(diagnostics("component A(String x) { <div data-eval=${x}>b</div> }"), "UNSAFE_SINK"));
    }

    // ---- trusted* ---------------------------------------------------------------

    @Test
    void trustedUrlIsInfoAndAllowedInUrlAndEmbedAttributes() {
        List<SukoDiagnostic> d = diagnostics("component A(String u) { <a href=${trustedUrl(u)}>l</a> <iframe src=${trustedUrl(u)}></iframe> }");
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
        assertEquals(2, d.stream().filter(x -> x.code().equals("TRUSTED_URL")).count());
        assertTrue(d.stream().filter(x -> x.code().equals("TRUSTED_URL")).allMatch(x -> x.severity() == SukoDiagnostic.Severity.INFO));
        assertTrue(d.stream().anyMatch(x -> x.message().endsWith(": u")), d.toString());
    }

    @Test
    void trustedStyleIsInfo() {
        List<SukoDiagnostic> d = diagnostics("component A(String s) { <div style=${trustedStyle(s)}>b</div> }");
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
        assertTrue(has(d, "TRUSTED_STYLE"));
    }

    @Test
    void trustedFunctionsInTheWrongPlaceAreRejected() {
        assertTrue(has(diagnostics("component A(String u) { <div title=${trustedUrl(u)}>b</div> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String u) { <a href=${trustedStyle(u)}>b</a> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String u) { <div title=${trustedHtml(u)}>b</div> }"), "RESERVED_NAME"));
    }

    @Test
    void reservedNamesCannotBeDeclared() {
        assertTrue(has(diagnostics("component trustedUrl() { <p>x</p> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String trustedStyle) { <p>x</p> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A() { var trustedHtml = \"x\"; <p>x</p> }"), "RESERVED_NAME"));
    }

    // ---- nomes em maiúsculas ----------------------------------------------------

    @Test
    void allUppercaseTagAndAttributeNamesAreRejected() {
        assertTrue(has(diagnostics("component A() { <DIV>x</DIV> }"), "UPPERCASE_NAME"));
        assertTrue(has(diagnostics("component A() { <div CLASS=\"a\">x</div> }"), "UPPERCASE_NAME"));
        assertFalse(has(diagnostics("component A() { <div class=\"a\">x</div> }"), "UPPERCASE_NAME"));
        assertFalse(has(diagnostics("component A() { <svg viewBox=\"0 0 1 1\"><foreignObject></foreignObject></svg> }"), "UPPERCASE_NAME"));
    }

    // ---- lint de CSP estrita ----------------------------------------------------

    @Test
    void strictCspLintWarnsOnInlineThings() {
        SecurityOptions o = SecurityOptions.DEFAULT.withStrictCsp(true);
        for (String sk : new String[] {
            "component A() { <div style=\"color: red\">b</div> }",
            "component A() { <style>p</style> }",
            "component A() { <button onclick=\"go()\">b</button> }",
            "component A() { <script>var a = 1;</script> }",
            "component A() { <a href=\"javascript:void(0)\">b</a> }",
            "component A() { <div x-data=\"{}\">b</div> }"}) {
            List<SukoDiagnostic> d = diagnostics(sk, o);
            assertTrue(d.stream().anyMatch(x -> x.code().equals("CSP_INLINE") && x.severity() == SukoDiagnostic.Severity.WARNING), sk + " -> " + d);
        }
        assertFalse(has(diagnostics("component A() { <div style=\"color: red\">b</div> }"), "CSP_INLINE"));
        assertFalse(has(diagnostics("component A() { <script src=\"/a.js\"></script> }", o), "CSP_INLINE"));
        assertFalse(has(diagnostics("component A(String n) { <script nonce=${n}>1</script> }", o), "CSP_INLINE"));
    }

    @Test
    void checkerOnlyRunsWhenTheHtmlVocabularyIsActive() {
        // Sem alvo com vocabulário html o checker não tem nada a dizer (lista de alvos vazia vale ["jte"]).
        var registry = ExtensionRegistry.of(List.of(new io.suko.jte.JteExtension(), new PlainExtension()));
        var d = new JteCompiler("T.sk", "component A(String x) { <script>${x}</script> }",
            registry, List.of("plain"), SecurityOptions.DEFAULT).compile().diagnostics().getDiagnostics();
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
    }

    // ---- extras da revisão da Task 3 ---------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String s) { <div style=${s}>b</div> }",
        "component A(String s) { <div style=\"${s}\">b</div> }",
        "component A(String u) { <div style=\"background: url(${u})\">b</div> }",
        "component A(String u) { <iframe srcdoc=${u}></iframe> }",
        "component A(String u) { <meta http-equiv=\"refresh\" content=${u}> }",
        "component A(String u) { <style>${u}</style> }",
        "component A(String u) { <script>if (true) { ${u} }</script> }",
        "component A(String u) { <script>@if(true) { ${u} }</script> }"
    })
    void fallThroughFormsTheEmitterDoesNotProtectAreRejected(String sk) {
        assertSink(sk);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <div data-hx-on-click=${x}>b</div> }",
        "component A(String x) { <div data-hx-on=${x}>b</div> }",
        "component A(String x) { <div data-x-data=${x}>b</div> }",
        "component A(String x) { <div data-x-html=${x}>b</div> }",
        "component A(String x) { <div hx-vals=${x}>b</div> }",
        "component A(String x) { <div hx-headers=${x}>b</div> }",
        "component A(String x) { <div hx-trigger=${x}>b</div> }",
        "component A(String x) { <div data-hx-vals=${x}>b</div> }",
        "component A(String x) { <div data-hx-headers=\"${x}\">b</div> }",
        "component A(String x) { <div data-hx-trigger=${x}>b</div> }",
        "component A(String x) { <div hx-vars=${x}>b</div> }",
        "component A(String x) { <div data-hx-vars=${x}>b</div> }",
        "component A(String x) { <div HX-VARS=${x}>b</div> }",
        "component A(String x) { <div hx-request=${x}>b</div> }",
        "component A(String x) { <link rel=\"stylesheet\" rel=\"canonical\" href=${x}> }",
        "component A(String x) { <meta name=\"referrer\" name=\"viewport\" content=${x}> }"
    })
    void hardeningDataPrefixHtmxEvaluatedAttributesAndDuplicateAttributes(String sk) {
        assertSink(sk);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <div hx-vals=\"abc\" hx-headers=\"abc\" hx-trigger=\"click\">b</div> }",
        "component A() { <div data-hx-vals=\"abc\" data-hx-trigger=\"load\">b</div> }",
        "component A() { <div hx-vars=\"a:1\" hx-request=\"abc\">b</div> }",
        "component A(String x) { <div data-title=${x} data-hx-x=${x}>b</div> }"
    })
    void hardeningLiteralHtmxAttributesAreAccepted(String sk) {
        assertClean(sk);
    }

    @Test
    void scriptAndStyleContentThroughControlFlowIsRejected() {
        assertSink("component A(String u, boolean b) { <script>if (b) { <p>${u}</p> }</script> }");
        assertSink("component A(String u) { <style>var c = u; ${c}</style> }");
        assertSink("component A(String[] xs) { <script>for (String x : xs) { ${x} }</script> }");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String u) { <a href=\"${trustedUrl(u)}\">l</a> }",
        "component A(String u) { <p>${trustedUrl(u)}</p> }",
        "component A(String u) { <p>${trustedStyle(u)}</p> }",
        "component A(String u) { <p>${trustedHtml(u)}</p> }",
        "component A(String u) { <a rel=${trustedUrl(u)} href=\"/x\">l</a> }",
        "component A(String u) { <div style=${trustedUrl(u)}>b</div> }",
        "component A(String u) { <a href=${trustedHtml(u)}>b</a> }",
        "component A(String u) { <div style=${trustedHtml(u)}>b</div> }",
        "component A(String u) { <div data=${trustedUrl(u)}>b</div> }",
        "component A(String u) { <a href=${trustedUrl(u, u)}>b</a> }",
        "component A(String u) { <a href=${trustedUrl(trustedUrl(u))}>b</a> }",
        "component A(String u) { var v = trustedUrl(u); <p>x</p> }",
        "component A(String u) { <div title=\"x${trustedStyle(u)}\">b</div> }"
    })
    void trustedMisuseIsAReservedNameErrorNotAJavacError(String sk) {
        List<SukoDiagnostic> d = diagnostics(sk);
        assertTrue(d.stream().anyMatch(x -> x.code().equals("RESERVED_NAME") && x.severity() == SukoDiagnostic.Severity.ERROR),
            sk + " -> " + d);
    }

    @Test
    void trustedUrlMatchesTheEmitterOnSrcsetPingAndObjectData() {
        for (String sk : new String[] {
            "component A(String u) { <img srcset=${trustedUrl(u)}> }",
            "component A(String u) { <a href=\"/x\" ping=${trustedUrl(u)}>l</a> }",
            "component A(String u) { <object data=${trustedUrl(u)}></object> }"}) {
            List<SukoDiagnostic> d = diagnostics(sk);
            assertTrue(has(d, "TRUSTED_URL"), sk + " -> " + d);
            assertFalse(has(d, "RESERVED_NAME"), sk + " -> " + d);
        }
    }

    @Test
    void legacyThreeArgCheckContextStillRunsTheChecker() {
        String sk = "component A(String x) { <button onclick=${x}>b</button> }";
        var parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(
            new io.suko.lang.SukoLexer(org.antlr.v4.runtime.CharStreams.fromString(sk))));
        var ast = new io.suko.lang.SukoAstBuilder(sk).build(parser.compilationUnit());
        var collector = new io.suko.lang.diagnostic.DiagnosticCollector();
        var legacy = new io.suko.ext.CheckContext("T.sk", io.suko.lang.project.ProjectView.EMPTY, collector);
        new io.suko.jte.HtmlSecurityChecker().check(ast, legacy);
        assertTrue(has(collector.getDiagnostics(), "UNSAFE_SINK"), collector.getDiagnostics().toString());
    }
}
