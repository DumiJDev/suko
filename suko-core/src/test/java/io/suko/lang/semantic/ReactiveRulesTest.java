package io.suko.lang.semantic;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Regras de linguagem da 13b que não dependem do alvo (core). */
class ReactiveRulesTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics();
    }

    static List<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).toList();
    }

    static long count(String sk, String code) {
        return codes(sk).stream().filter(code::equals).count();
    }

    static final String CARD = "component Card(Runnable onX) { <b>card</b> }\n";
    static final String STEPPER = """
        reactive component Stepper(int value, Consumer<Integer> onChange) {
          <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
        }
        """;

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { state int n = 0; <p>x</p> }",
        "component A() { derived int n = 1; <p>x</p> }",
        "component A() { <b on:click=${() -> go()}>x</b> }",
        "component A(String s) { <input bind:value=${s}/> }",
        CARD + "component A() { Card(onX = () -> go()) }",
    })
    void reactiveSyntaxInASimpleComponentIsOnlyReactiveRequired(String sk) {
        assertEquals(List.of("REACTIVE_REQUIRED"), codes(sk), diagnostics(sk).toString());
        assertTrue(diagnostics(sk).get(0).message().contains("reactive component A"), diagnostics(sk).toString());
    }

    @Test
    void lambdaFromASimpleComponentToAReactiveOneCrossesTheBoundary() {
        String sk = STEPPER + "component A() { Stepper(value = 1, onChange = v -> go(v)) }";
        assertEquals(1, count(sk, "LAMBDA_ACROSS_BOUNDARY"), diagnostics(sk).toString());
        assertEquals(0, count(sk, "REACTIVE_REQUIRED"));
    }

    @Test
    void lambdaFromAReactiveComponentToASimpleOneCrossesTheBoundary() {
        String sk = CARD + "reactive component R() { state int n = 0; Card(onX = () -> n++) <b>${n}</b> }";
        assertEquals(1, count(sk, "LAMBDA_ACROSS_BOUNDARY"), diagnostics(sk).toString());
    }

    @Test
    void lambdasBetweenReactiveComponentsAreFine() {
        String sk = STEPPER + "reactive component C() { state int n = 0; Stepper(value = n, onChange = v -> n = v) }";
        assertEquals(Set.of("REACTIVE_UNSUPPORTED"), Set.copyOf(codes(sk)), diagnostics(sk).toString());
    }

    @Test
    void reactiveComponentWithoutReactivityGetsAHint() {
        SukoDiagnostic hint = diagnostics("reactive component R() { <p>x</p> }").stream()
            .filter(d -> d.code().equals("REACTIVE_UNUSED")).findFirst().orElseThrow();
        assertEquals(SukoDiagnostic.Severity.HINT, hint.severity());
        assertTrue(hint.message().contains("component simples"), hint.message());
    }

    @Test
    void forwardingACallbackIsNotUnused() {
        String sk = STEPPER + "reactive component Mid(Consumer<Integer> onChange) { Stepper(value = 1, onChange = v -> onChange.accept(v)) }";
        assertEquals(0, count(sk, "REACTIVE_UNUSED"), diagnostics(sk).toString());
    }

    @Test
    void derivedCycleIsReportedOncePerCycle() {
        String sk = "reactive component R() { derived int a = b + 1; derived int b = a + 1; derived int c = a; <p>${c}</p> }";
        List<SukoDiagnostic> cycles = diagnostics(sk).stream().filter(d -> d.code().equals("DERIVED_CYCLE")).toList();
        assertEquals(1, cycles.size(), diagnostics(sk).toString());
        assertTrue(cycles.get(0).message().contains("a → b → a"), cycles.get(0).message());
        assertEquals(1, count("reactive component R() { derived int x = x + 1; <p>${x}</p> }", "DERIVED_CYCLE"));
        assertEquals(0, count("reactive component R() { state int s = 1; derived int a = s + 1; derived int b = a + 1; <p>${b}</p> }", "DERIVED_CYCLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reactive component R() { state String s = \"\"; derived String d = s; <input bind:value=${d}/> }",
        "reactive component R(String p) { state String s = \"\"; <input bind:value=${p}/> }",
        "reactive component R(Form f) { state String s = \"\"; <input bind:value=${f.name}/> }",
    })
    void bindNeedsAPlainState(String sk) {
        assertEquals(1, count(sk, "BIND_TARGET"), diagnostics(sk).toString());
    }

    @Test
    void bindToAStateIsFine() {
        assertEquals(0, count("reactive component R() { state String s = \"\"; <input bind:value=${s}/> }", "BIND_TARGET"));
    }

    @Test
    void doctypeIsForTheLayoutNotForAReactiveComponent() {
        SukoDiagnostic d = diagnostics("reactive component R() { <!DOCTYPE html> <p>x</p> }").stream()
            .filter(x -> x.code().equals("INVALID_DOCTYPE")).findFirst().orElseThrow();
        assertTrue(d.message().contains("<!DOCTYPE html>") && d.message().contains("layout"), d.message());
    }

    @Test
    void jteDoesNotSupportReactiveComponentsUntilPlanTwo() {
        String sk = "reactive component Counter(int start) { state var count = start; <button on:click=${() -> count++}>${count}</button> }";
        List<SukoDiagnostic> d = diagnostics(sk);
        assertEquals(Set.of("REACTIVE_UNSUPPORTED"), Set.copyOf(codes(sk)), d.toString());
        assertTrue(d.get(0).message().contains("'jte'") && d.get(0).message().contains("Counter"), d.get(0).message());
        assertEquals(1, d.get(0).span().startLine());
        assertEquals(sk.indexOf("Counter"), d.get(0).span().startColumn());
    }

    /** Alvo de teste com a capacidade reactive. */
    static final class ReactiveExtension implements SukoExtension {
        final boolean throwing;

        ReactiveExtension(boolean throwing) {
            this.throwing = throwing;
        }

        public String id() { return "io.suko.test.reactive"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "reactive-test"; }
                public String componentType() { return "java.lang.Object"; }
                public Set<String> vocabularies() { return Set.of(); }
                public Set<String> capabilities() {
                    if (throwing) {
                        throw new IllegalStateException("capacidades partidas");
                    }
                    return Set.of(Target.CAPABILITY_REACTIVE);
                }
                public Emitted emit(ComponentDecl c, EmitContext e) { return new Emitted(c.name() + ".txt", "", List.of()); }
            });
        }
    }

    @Test
    void aTargetWithTheCapabilityAcceptsReactiveComponents() {
        var registry = ExtensionRegistry.of(List.of(new ReactiveExtension(false)));
        var result = new JteCompiler("T.sk", "reactive component R() { state int n = 0; <p>${n}</p> }",
            registry, List.of("reactive-test")).compile();
        assertTrue(result.success(), result.diagnostics().toString());
    }

    @Test
    void capabilitiesThatThrowBecomeExtensionFailed() {
        var registry = ExtensionRegistry.of(List.of(new ReactiveExtension(true)));
        var result = new JteCompiler("T.sk", "reactive component R() { state int n = 0; <p>${n}</p> }",
            registry, List.of("reactive-test")).compile();
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("EXTENSION_FAILED")
            && d.message().contains("io.suko.test.reactive")), result.diagnostics().toString());
    }

    @Test
    void theBoundaryIsCheckedAcrossFiles() {
        var analysis = new SukoProjectCompiler().analyze(SukoSources.of(Path.of("/virtual"), Map.of(
            Path.of("ui/Stepper.sk"), "package ui;\n\npublic " + STEPPER,
            Path.of("Page.sk"), "import ui.Stepper;\n\ncomponent Page() {\n  Stepper(value = 1, onChange = v -> go(v))\n}\n")));
        var page = analysis.files().get(Path.of("Page.sk")).diagnostics().getDiagnostics();
        assertTrue(page.stream().anyMatch(d -> d.code().equals("LAMBDA_ACROSS_BOUNDARY")), page.toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reactive component A() { state int x = 0; state int x = 1; <p>${x}</p> }",
        "reactive component A() { state int x = 0; derived int x = 1; <p>${x}</p> }",
        "reactive component A() { derived int x = 0; derived int x = 1; <p>${x}</p> }",
        "reactive component A(int x) { state int x = 0; <p>${x}</p> }",
        "reactive component A(int x) { derived int x = 0; <p>${x}</p> }",
    })
    void duplicateNamesAreRejected(String sk) {
        assertEquals(1, count(sk, "DUPLICATE_NAME"), diagnostics(sk).toString());
        SukoDiagnostic d = diagnostics(sk).stream().filter(x -> x.code().equals("DUPLICATE_NAME")).findFirst().orElseThrow();
        assertEquals(SukoDiagnostic.Severity.ERROR, d.severity());
        assertTrue(d.message().contains("'x'"), d.message());
    }

    @ParameterizedTest
    @ValueSource(strings = {"__proto__", "constructor", "prototype"})
    void prototypeNamesAreReserved(String name) {
        for (String sk : List.of(
                "reactive component A() { state int " + name + " = 0; <p>x</p> }",
                "reactive component A() { derived int " + name + " = 0; <p>x</p> }")) {
            assertEquals(1, count(sk, "RESERVED_NAME"), diagnostics(sk).toString());
        }
    }

    @Test
    void distinctNamesAreNotDuplicates() {
        assertEquals(0, count("reactive component A(int a) { state int x = a; derived int y = x; <p>${y}</p> }", "DUPLICATE_NAME"));
    }
}
