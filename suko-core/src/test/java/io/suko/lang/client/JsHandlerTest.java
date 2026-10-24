package io.suko.lang.client;

import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static io.suko.lang.client.JsTranslatorTest.T;
import static org.junit.jupiter.api.Assertions.*;

/** O JS dos handlers de on: e das lambdas para filhos reativos: mutações de state seguidas de $u("nome"). */
class JsHandlerTest {

    static String on(String lambda) {
        JsTranslator.Result r = T.eventHandler(lambda(lambda));
        return assertInstanceOf(JsTranslator.Ok.class, r, () -> lambda + " -> " + r).js();
    }

    static String onProblem(String lambda) {
        JsTranslator.Result r = T.eventHandler(lambda(lambda));
        return assertInstanceOf(JsTranslator.Err.class, r, () -> lambda + " -> " + r).problem().message();
    }

    @Test
    void stateMutations() {
        assertEquals("() => { $s.count = 0; $u(\"count\"); }", on("() -> count = 0"));
        assertEquals("() => { $s.count = (($s.count + 1) | 0); $u(\"count\"); }", on("() -> count++"));
        assertEquals("() => { $s.count = (($s.count - 1) | 0); $u(\"count\"); }", on("() -> --count"));
        assertEquals("() => { $s.draft = (\"\" + $s.draft + \"!\"); $u(\"draft\"); }", on("() -> draft += \"!\""));
        assertEquals("() => { $s.ratio = ($s.ratio + $s.count); $u(\"ratio\"); }", on("() -> ratio += count"));
        assertEquals("() => { $s.maybe = (($suko.nn($s.maybe) + 1) | 0); $u(\"maybe\"); }", on("() -> maybe++"));
    }

    @Test
    void listMutationsOnState() {
        assertEquals("() => { $s.items.push($s.draft); $u(\"items\"); $s.draft = \"\"; $u(\"draft\"); }",
            on("() -> { items.add(draft); draft = \"\"; }"));
        assertEquals("() => { $suko.removeAt($s.items, 0); $u(\"items\"); }", on("() -> items.remove(0)"));
        assertEquals("() => { $suko.setAt($s.items, 0, \"z\"); $u(\"items\"); }", on("() -> items.set(0, \"z\")"));
        assertEquals("() => { $suko.clear($s.items); $u(\"items\"); }", on("() -> items.clear()"));
    }

    @Test
    void theEventParameterIsOptional() {
        assertEquals("(e) => { $s.draft = $suko.evValue(e); $u(\"draft\"); }", on("e -> draft = e.value()"));
        assertEquals("(e) => { $suko.evPrevent(e); }", on("e -> e.preventDefault()"));
        assertEquals("(ev) => { }", on("ev -> { }"));
    }

    @Test
    void callbacksForReactiveChildren() {
        JsTranslator.Result r = T.callback(lambda("v -> count = v"), List.of(new ClientType.Boxed(ClientType.Scalar.INT)));
        assertEquals("(v) => { $s.count = $suko.nn(v); $u(\"count\"); }", ((JsTranslator.Ok) r).js());
        assertEquals("() => { $s.count = 0; $u(\"count\"); }",
            ((JsTranslator.Ok) T.callback(lambda("() -> count = 0"), List.of())).js());
        JsTranslator.Result wrong = T.callback(lambda("() -> count = 0"), List.of(ClientType.Scalar.STRING));
        assertTrue(((JsTranslator.Err) wrong).problem().message().contains("espera 1"));
    }

    @Test
    void onlyStateIsWritable() {
        assertTrue(onProblem("() -> name = \"x\"").contains("parâmetro"));
        assertTrue(onProblem("() -> remaining = 1").contains("derived"));
        assertTrue(onProblem("() -> nums.add(1)").contains("state"));
        assertTrue(onProblem("() -> count += ratio").contains("estreita"));
        assertTrue(onProblem("() -> count + 1").contains("não é uma instrução"));
        assertTrue(onProblem("(a) -> count = 1").contains("outro nome"));
        assertTrue(onProblem("() -> items.remove(maybe)").contains("índice int"));
        assertTrue(onProblem("() -> draft = count").contains("não é atribuível"));
    }
}
