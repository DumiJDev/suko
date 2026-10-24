package io.suko.lang.security;

import io.suko.ext.*;
import io.suko.jte.HtmlSecurityRules;
import io.suko.jte.ReactiveHtmlChecker;
import io.suko.jte.client.ClientTypes;
import io.suko.jte.client.JavaTypes;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As regras da 13b no alvo JTE: CLIENT_UNSUPPORTED, TYPE_NOT_INFERRED, CLIENT_UNSAFE_SINK, BIND_TARGET de
 * elemento/tipo, PROP_*, trusted* só no servidor (Q6) e um diagnóstico por sítio. Até ao plano 2 todo o reactive
 * component dá também REACTIVE_UNSUPPORTED (R1), que estes testes ignoram.
 */
class ReactiveHtmlCheckerTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics().stream()
            .filter(d -> !d.code().equals("REACTIVE_UNSUPPORTED")).toList();
    }

    static Set<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    static long count(String sk, String code) {
        return diagnostics(sk).stream().filter(d -> d.code().equals(code)).count();
    }

    static final String EXAMPLES = """
        reactive component TodoList(List<String> initial, String placeholder) {
          state var items = initial;
          state var draft = "";
          state boolean showDone = true;
          derived var remaining = items.size();
          <section class="todos">
            <input bind:value=${draft} placeholder="${placeholder}" />
            <button on:click=${() -> { items.add(draft); draft = ""; }}>Adicionar</button>
            <label><input type="checkbox" bind:checked=${showDone} /> Mostrar feitos</label>
            if (showDone) {
              <ul>for (var t : items) { <li>${t}</li> }</ul>
            }
            <p>${remaining} itens</p>
          </section>
        }

        reactive component Stepper(int value, Consumer<Integer> onChange) {
          <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
        }

        reactive component Counter(int start) {
          state var count = start;
          Stepper(value = count, onChange = v -> count = v)
          <button on:click=${() -> count = 0}>reset</button>
        }
        """;

    @Test
    void theSpecExamplesWithoutRecordsOnlyWaitForPlanTwo() {
        assertEquals(List.of(), diagnostics(EXAMPLES));
        assertTrue(new JteCompiler("T.sk", EXAMPLES).compile().diagnostics().getDiagnostics().stream()
            .allMatch(d -> d.code().equals("REACTIVE_UNSUPPORTED")));
    }

    @Test
    void clientUnsupportedPointsAtTheSubexpressionAndOnlyInClientCode() {
        String sk = "reactive component R(String s) { state boolean open = false; if (open) { <p>${s ?: \"x\"}</p> } }";
        SukoDiagnostic d = diagnostics(sk).stream().filter(x -> x.code().equals("CLIENT_UNSUPPORTED")).findFirst().orElseThrow();
        assertEquals(sk.indexOf("s ?:"), d.span().startColumn());
        assertTrue(d.message().contains("?:"), d.message());
        String server = "reactive component R(String s) { state boolean open = false; <p>${s ?: \"x\"}</p>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(Set.of(), codes(server), "no render do servidor o ?: é Java");
    }

    @Test
    void simpleComponentInsideAStateBlock() {
        String sk = "component Card() { <b>c</b> } reactive component R() { state boolean open = false; if (open) { Card() } }";
        SukoDiagnostic d = diagnostics(sk).get(0);
        assertEquals("CLIENT_UNSUPPORTED", d.code());
        assertTrue(d.message().contains("'Card' não é reativo"), d.message());
    }

    @Test
    void typeNotInferred() {
        assertEquals(Set.of("TYPE_NOT_INFERRED"), codes("reactive component R() { state var x = null; <p>${x}</p> }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>1</script>", "<style>p</style>", "<iframe></iframe>", "<frame/>", "<object></object>",
        "<embed/>", "<base/>", "<meta/>", "<link/>", "<template>x</template>", "<svg><animate/></svg>", "<svg><set/></svg>",
        "<svg><animateMotion/></svg>", "<svg><animateTransform/></svg>", "<svg><foreignObject>x</foreignObject></svg>",
        "<svg><script>1</script></svg>", "<noscript>x</noscript>", "<fencedframe></fencedframe>", "<portal></portal>",
        "<applet></applet>", "<frameset></frameset>"})
    void dangerousElementsAreNeverCreatedOnTheClient(String element) {
        String client = "reactive component R() { state boolean open = false; if (open) { " + element + " } }";
        assertEquals(1, count(client, "CLIENT_UNSAFE_SINK"), client + " -> " + diagnostics(client));
        String server = "reactive component R() { state boolean open = false; " + element
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(0, count(server, "CLIENT_UNSAFE_SINK"), server + " -> " + diagnostics(server));
    }

    @Test
    void oneDiagnosticPerSite() {
        String script = "reactive component R(String x) { state boolean open = false; if (open) { <script>${x}</script> } }";
        assertEquals(Set.of("UNSAFE_SINK"), codes(script), diagnostics(script).toString());
        String origin = "reactive component R() { state String page = \"a\"; <iframe src=\"https://x.example/${page}\"></iframe> }";
        assertEquals(List.of("CLIENT_UNSAFE_SINK"), diagnostics(origin).stream().map(SukoDiagnostic::code).toList(),
            "a origem constante é aceite no servidor (14) mas o cliente não a implementa");
        String bind = "reactive component R() { state String s = \"\"; derived String d = s; <div bind:value=${d}></div> }";
        assertEquals(1, count(bind, "BIND_TARGET"), diagnostics(bind).toString());
    }

    /** R10 (S2): só os pares declarados se fundem; um ERROR que envolve o sítio não esconde o que está dentro. */
    @Test
    void anEnclosingErrorDoesNotHideTheProblemsInsideIt() {
        String upper = "reactive component R() { state boolean open = false; <MAIN> if (open) { <iframe></iframe> } </MAIN>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(1, count(upper, "UPPERCASE_NAME"), diagnostics(upper).toString());
        assertEquals(1, count(upper, "CLIENT_UNSAFE_SINK"), diagnostics(upper).toString());
        // o corpo de <script> é texto cru (o <iframe> não é elemento); <foreignObject> também é CONTENT_SINK mas tem filhos HTML
        String script = "reactive component R(String x) { state boolean open = false;"
            + " <svg><foreignObject> if (open) { <iframe></iframe> } ${x}</foreignObject></svg> <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(1, count(script, "UNSAFE_SINK"), diagnostics(script).toString());
        assertEquals(1, count(script, "CLIENT_UNSAFE_SINK"), diagnostics(script).toString());
        // sentido inverso: o UNSAFE_SINK de um FILHO não esconde o CLIENT_UNSAFE_SINK do elemento exterior
        String child = "reactive component R(String x) { state boolean open = false;"
            + " if (open) { <iframe><script>${x}</script></iframe> } <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(1, count(child, "UNSAFE_SINK"), diagnostics(child).toString());
        assertEquals(1, count(child, "CLIENT_UNSAFE_SINK"), diagnostics(child).toString());
        // CLIENT_UNSUPPORTED dentro de um elemento com UNSAFE_SINK de conteúdo não fica escondido
        String unsupported = "reactive component R(String x) { state boolean open = false;"
            + " <svg><foreignObject> if (open) { <p>${x ?: \"y\"}</p> } ${x}</foreignObject></svg> <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(1, count(unsupported, "UNSAFE_SINK"), diagnostics(unsupported).toString());
        assertEquals(1, count(unsupported, "CLIENT_UNSUPPORTED"), diagnostics(unsupported).toString());
    }

    @Test
    void twoDistinctSinksGiveTwoDiagnosticsEvenWhenTheirSpansAreAdjacent() {
        String apart = "reactive component R(String x) { state String s = \"\"; state boolean open = false;"
            + " <b onclick=${x} on:click=${() -> open = !open}>t</b> if (open) { <iframe></iframe> } }";
        assertEquals(1, count(apart, "UNSAFE_SINK"), diagnostics(apart).toString());
        assertEquals(1, count(apart, "CLIENT_UNSAFE_SINK"), diagnostics(apart).toString());
        String adjacent = "reactive component R(String x) { state String s = \"\"; <script>${x}</script><iframe title=${s}></iframe>"
            + " <b on:click=${() -> s = \"y\"}>t</b> }";
        assertEquals(1, count(adjacent, "UNSAFE_SINK"), diagnostics(adjacent).toString());
        assertEquals(1, count(adjacent, "CLIENT_UNSAFE_SINK"), diagnostics(adjacent).toString());
    }

    @Test
    void propDiagnosticsAreNeverSuppressedByAnotherError() {
        String sk = "reactive component R(long v, String x) { state var copy = v; <script>${x}</script> }";
        assertEquals(1, count(sk, "PROP_NOT_SERIALIZABLE"), diagnostics(sk).toString());
        assertEquals(1, count(sk, "UNSAFE_SINK"), diagnostics(sk).toString());
    }

    /** R27 (S8): uma só constante, sem cópia que se desvie do 14. */
    @Test
    void theForbiddenElementsAreOneConstantThatIncludesTheRulesOf14() {
        Set<String> forbidden = HtmlSecurityRules.CLIENT_FORBIDDEN_ELEMENTS;
        assertTrue(forbidden.containsAll(HtmlSecurityRules.CONTENT_SINKS), forbidden.toString());
        assertTrue(forbidden.containsAll(HtmlSecurityRules.ANIMATION), forbidden.toString());
        assertTrue(forbidden.containsAll(Set.of("script", "style", "iframe", "frame", "object", "embed", "base", "meta", "link",
            "template", "animate", "set", "animatemotion", "animatetransform", "foreignobject",
            "noscript", "fencedframe", "portal", "applet", "frameset")), forbidden.toString());
    }

    /** R20/S8: o elemento proibido está no render do servidor (14 aceita), mas um atributo seu é atualizado no cliente. */
    @ParameterizedTest
    @ValueSource(strings = {"<iframe src=\"https://h/${s}\"></iframe>", "<script title=${s}>1</script>",
        "<style title=${s}>p</style>", "<frame title=${s}/>", "<object title=${s}></object>", "<embed title=${s}/>",
        "<base title=${s}/>", "<meta title=${s}/>", "<link title=${s}/>", "<template title=${s}>x</template>",
        "<noscript title=${s}>x</noscript>", "<svg><animate title=${s}/></svg>", "<svg><set title=${s}/></svg>",
        "<svg><animateMotion title=${s}/></svg>", "<svg><animateTransform title=${s}/></svg>",
        "<svg><foreignObject title=${s}>x</foreignObject></svg>", "<fencedframe title=${s}></fencedframe>",
        "<portal title=${s}></portal>", "<applet title=${s}></applet>", "<frameset title=${s}></frameset>"})
    void aForbiddenElementNeverGetsAClientUpdatedAttribute(String element) {
        String sk = "reactive component R() { state String s = \"\"; " + element + " <b on:click=${() -> s = \"x\"}>t</b> }";
        assertEquals(1, count(sk, "CLIENT_UNSAFE_SINK"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<div bind:value=${s}></div>", "<input bind:innerHTML=${s}/>", "<input bind:textContent=${s}/>",
        "<select bind:checked=${b}></select>", "<input type=\"checkbox\" bind:checked=${s}/>", "<input bind:value=${list}/>",
    })
    void bindTargetElementNameAndType(String element) {
        String sk = "reactive component R() { state String s = \"\"; state boolean b = false; state int n = 0;"
            + " state List<String> list = null; " + element + " }";
        assertEquals(1, count(sk, "BIND_TARGET"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<input bind:value=${s}/>", "<textarea bind:value=${s}></textarea>", "<select bind:value=${n}></select>",
        "<input type=\"checkbox\" bind:checked=${b}/>",
    })
    void validBinds(String element) {
        String sk = "reactive component R() { state String s = \"\"; state boolean b = false; state int n = 0; " + element + " }";
        assertEquals(Set.of(), codes(sk), diagnostics(sk).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "char", "float", "Object", "int[]", "Product", "Map<Integer, String>"})
    void propsThatCannotBeSerialized(String type) {
        String sk = "reactive component R(" + type + " v) { state var copy = v; <p>x</p> }";
        assertEquals(List.of("PROP_NOT_SERIALIZABLE"), diagnostics(sk).stream().map(SukoDiagnostic::code).toList(),
            sk + " -> " + diagnostics(sk));
    }

    @Test
    void paramsReadOnlyByTheServerAreNotSerialized() {
        String sk = "reactive component R(long v) { state int n = 0; <p>${v}</p> <b on:click=${() -> n++}>${n}</b> }";
        assertEquals(Set.of(), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void sensitiveNamesAndMapKeys() {
        String sk = "reactive component R(String authToken, String passwordHint, String title, Map<String, String> labels) {"
            + " state var a = authToken; state var b = passwordHint; state var c = title; state var l = labels; <p>x</p> }";
        List<SukoDiagnostic> d = diagnostics(sk);
        assertEquals(2, d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).count(), d.toString());
        assertTrue(d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).allMatch(x -> x.severity() == SukoDiagnostic.Severity.WARNING));
        SukoDiagnostic keys = d.stream().filter(x -> x.code().equals("PROP_MAP_KEYS")).findFirst().orElseThrow();
        assertEquals(SukoDiagnostic.Severity.INFO, keys.severity());
    }

    static long sensitiveCount(String param) {
        return count("reactive component R(String " + param + ") { state var copy = " + param + "; <p>x</p> }", "PROP_SENSITIVE");
    }

    /** R28 (S9): casa por token do nome, incluindo os nomes que faltavam na lista da spec. */
    @ParameterizedTest
    @ValueSource(strings = {"csrf", "csrfToken", "nonce", "otp", "pin", "cvv", "salt", "bearerValue", "refreshToken",
        "api_key", "APIKey", "privateKey", "userSessions", "credentials", "passwordHint"})
    void sensitiveNamesMatchByToken(String param) {
        assertEquals(1, sensitiveCount(param), param);
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "authors", "authority", "authorName", "pinned", "spinner", "tokenizer", "salted", "title"})
    void similarLookingNamesAreNotSensitive(String param) {
        assertEquals(0, sensitiveCount(param), param);
    }

    /** Extensão de teste: o checker com formas de records conhecidas, num alvo com o vocabulário html. */
    static final class RecordsExtension implements SukoExtension {
        public String id() { return "io.suko.test.records"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "records"; }
                public String componentType() { return "java.lang.Object"; }
                public Set<String> vocabularies() { return Set.of("html"); }
                public Set<String> capabilities() { return Set.of(Target.CAPABILITY_REACTIVE); }
                public Emitted emit(ComponentDecl c, EmitContext e) { return new Emitted(c.name() + ".txt", "", List.of()); }
            });
            JavaTypes types = name -> name.equals("Account")
                ? Optional.of(List.of(new JavaTypes.Field("name", ClientTypes.parse("String")),
                    new JavaTypes.Field("sessionId", ClientTypes.parse("String"))))
                : Optional.empty();
            ctx.checker(new ReactiveHtmlChecker(types));
        }
    }

    @Test
    void sensitiveRecordComponents() {
        var result = new JteCompiler("T.sk", "reactive component R(Account acc) { state var a = acc; <p>${a.name()}</p> }",
            ExtensionRegistry.of(List.of(new RecordsExtension())), List.of("records")).compile();
        List<SukoDiagnostic> d = result.diagnostics().getDiagnostics();
        SukoDiagnostic w = d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).findFirst().orElseThrow(() -> new AssertionError(d));
        assertTrue(w.message().contains("Account.sessionId"), w.message());
        assertTrue(result.success(), d.toString());
    }

    @Test
    void trustedIsOnlyForTheServerRender() {
        String inBlock = "reactive component R(String u) { state boolean open = false; if (open) { <a href=${trustedUrl(u)}>x</a> } }";
        assertEquals(Set.of("RESERVED_NAME"), codes(inBlock), diagnostics(inBlock).toString());
        String updated = "reactive component R() { state String target = \"/a\"; <a href=${trustedUrl(target)}>x</a> }";
        assertEquals(Set.of("RESERVED_NAME"), codes(updated), diagnostics(updated).toString());
        String server = "reactive component R(String u) { state boolean open = false; <a href=${trustedUrl(u)}>x</a>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(Set.of("TRUSTED_URL"), codes(server), diagnostics(server).toString());
        String handler = "reactive component R() { state String s = \"\"; <b on:click=${() -> s = trustedUrl(s)}>t</b> }";
        assertEquals(1, count(handler, "RESERVED_NAME"), diagnostics(handler).toString());
    }

    /**
     * Gate do plano 2 (R29, S1). Hoje os parâmetros de Child são constantes (vista de raiz de ilha, R18), por isso o
     * href é TRUSTED_URL (aceite, só no servidor). Com o modo "filho reativo" um pai que muda 'target' faz o cliente
     * reescrever o href sem passar pelo porte do SukoSafe: tem de ser RESERVED_NAME. O plano 2 remove o @Disabled
     * na mesma task que junta o modo filho e faz este teste passar.
     */
    @Test
    @Disabled("plano 2: modo filho reativo (R29)")
    void trustedOnAParamOfAReactiveChildIsReservedOnceChildModeExists() {
        String sk = "reactive component Child(String u) { <a href=${trustedUrl(u)}>x</a> }"
            + " reactive component Parent() { state String target = \"/a\"; Child(u = target)"
            + " <b on:click=${() -> target = \"/b\"}>t</b> }";
        assertEquals(Set.of("RESERVED_NAME"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void htmlEventAttributesAreStillUnsafeInsideAReactiveComponent() {
        String sk = "reactive component R(String h) { state int n = 0; <b onclick=${h} on:click=${() -> n++}>t</b> }";
        assertEquals(Set.of("UNSAFE_SINK"), codes(sk), diagnostics(sk).toString());
    }
}
