package io.suko.lang.client;

import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientScope;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** O JS exato que o tradutor escreve para cada forma do subconjunto (o contrato R17 com o runtime do plano 2). */
class JsTranslatorTest {

    static final ClientScope SCOPE = scope(List.of(
        state("int", "count", "0"), state("String", "draft", "\"\""), state("List<String>", "items", "null"),
        state("double", "ratio", "0.0"), state("Integer", "maybe", "null"), derived("int", "remaining", "0"),
        param("String", "name", "null"), param("int", "a", "0"), param("int", "b", "0"),
        param("List<Integer>", "nums", "null"), param("Map<String, String>", "labels", "null"),
        param("Todo", "todo", "null"), param("Product", "product", "null"), local("String", "let", "null")));

    static final JsTranslator T = new JsTranslator(SCOPE, TYPES);

    static String js(String source) {
        JsTranslator.Result r = T.expression(expr(source));
        return assertInstanceOf(JsTranslator.Ok.class, r, () -> source + " -> " + r).js();
    }

    static ClientProblem problem(String source) {
        JsTranslator.Result r = T.expression(expr(source));
        return assertInstanceOf(JsTranslator.Err.class, r, () -> source + " -> " + r).problem();
    }

    @Test
    void namesAndLiterals() {
        assertEquals("$s.count", js("count"));
        assertEquals("$s.remaining", js("remaining"));
        assertEquals("$p.name", js("name"));
        assertEquals("$_let", js("let"));
        assertEquals("(-5)", js("-5"));
        assertEquals("null", js("null"));
        assertEquals("\"a\\u0026b\\u0027c\"", js("\"a&b'c\""));
    }

    @Test
    void intArithmeticIsThirtyTwoBit() {
        assertEquals("(($s.count + 1) | 0)", js("count + 1"));
        assertEquals("$suko.idiv($p.a, $p.b)", js("a / b"));
        assertEquals("$suko.imod($p.a, $p.b)", js("a % b"));
        assertEquals("Math.imul($p.a, $p.b)", js("a * b"));
        assertEquals("((-$p.a) | 0)", js("-a"));
        assertEquals("($s.ratio * 2)", js("ratio * 2"));
        assertEquals("(($suko.nn($s.maybe) + 1) | 0)", js("maybe + 1"));
        assertEquals("Math.max($p.a, $s.ratio)", js("Math.max(a, ratio)"));
    }

    @Test
    void concatenationFollowsJava() {
        assertEquals("(\"\" + \"Ol\\u00e1 \" + $p.name)", js("\"Olá \" + name"));
        assertEquals("(\"\" + $p.name + $suko.dcat($s.ratio))", js("name + ratio"));
        assertEquals("(\"\" + $p.name + \"!\")", js("\"$name!\""));
        assertEquals("($p.name == null ? \"\" : \"\" + $p.name)", js("\"${name}\""));
    }

    @Test
    void methodsOfTheClosedList() {
        assertEquals("$suko.equals($p.name, \"x\")", js("name.equals(\"x\")"));
        assertEquals("($p.name == null)", js("name == null"));
        assertEquals("(($s.items).length > 0)", js("items.size() > 0"));
        assertEquals("(!(($s.items).length === 0))", js("!items.isEmpty()"));
        assertEquals("$suko.get($s.items, 0)", js("items.get(0)"));
        assertEquals("(($suko.nn($suko.get($p.nums, 0)) + 1) | 0)", js("nums.get(0) + 1"));
        assertEquals("($p.nums).includes(3)", js("nums.contains(3)"));
        assertEquals("$suko.mget($p.labels, \"k\")", js("labels.get(\"k\")"));
        assertEquals("$suko.field($p.todo, \"title\")", js("todo.title()"));
        assertEquals("$suko.rec([\"title\", \"done\"], [$s.draft, false])", js("new Todo(draft, false)"));
        assertEquals("($_let).length", js("let.length()"));
        assertEquals("(($s.count > 0) ? \"sim\" : \"n\\u00e3o\")", js("count > 0 ? \"sim\" : \"não\""));
    }

    @Test
    void textAndJavaStringConversions() {
        assertEquals("String($s.count)", ((JsTranslator.Ok) T.text(expr("count"))).js());
        assertEquals("$suko.dstr($s.ratio)", ((JsTranslator.Ok) T.text(expr("ratio"))).js());
        assertEquals("$suko.text($p.name)", ((JsTranslator.Ok) T.text(expr("name"))).js());
        assertEquals("$suko.text($s.maybe)", ((JsTranslator.Ok) T.text(expr("maybe"))).js());
        assertInstanceOf(JsTranslator.Err.class, T.text(expr("items")));
        assertEquals("$suko.dstr($s.ratio)", ((JsTranslator.Ok) T.javaString(expr("ratio"))).js());
        assertEquals("$p.name", ((JsTranslator.Ok) T.javaString(expr("name"))).js());
        assertEquals("$suko.sval($s.maybe)", ((JsTranslator.Ok) T.javaString(expr("maybe"))).js());
        assertEquals("String($s.count)", ((JsTranslator.Ok) T.javaString(expr("count"))).js());
    }

    @Test
    void typesComeOut() {
        assertEquals(ClientType.Scalar.DOUBLE, ((JsTranslator.Ok) T.expression(expr("a + ratio"))).type());
        assertEquals(new ClientType.Boxed(ClientType.Scalar.INT), ((JsTranslator.Ok) T.expression(expr("nums.get(0)"))).type());
        assertEquals(ClientType.Scalar.STRING, ((JsTranslator.Ok) T.expression(expr("labels.get(\"k\")"))).type());
    }

    @Test
    void outsideTheSubsetIsClientUnsupportedAtTheExactSpot() {
        assertTrue(problem("name == draft").message().contains("equals"));
        assertTrue(problem("name?.length()").message().contains("?."));
        assertTrue(problem("name ?: \"x\"").message().contains("?:"));
        assertTrue(problem("items.add(\"x\")").message().contains("instrução"));
        assertTrue(problem("x + 1").message().contains("'x'"));
        assertTrue(problem("name.substring(1)").message().contains("lista fechada"));
        assertTrue(problem("new Unknown()").message().contains("não é conhecido"));
        assertTrue(problem("product.price()").message().contains("'Product'"));
        assertTrue(problem("nums.contains(ratio)").message().contains("contains"));
        assertTrue(problem("todo.done").message().contains("done()"));
        assertTrue(problem("010").message().contains("octal"));
        assertTrue(problem("2147483648").message().contains("não cabe"));
        assertTrue(problem("Card()").message().contains("componente"));
        assertTrue(problem("count > 0 ? 1 : \"x\"").message().contains("ternário"));
        assertEquals("CLIENT_UNSUPPORTED", problem("name?.length()").code());
        assertEquals("RESERVED_NAME", problem("trustedUrl(name)").code());
        ClientProblem p = problem("count + name.substring(1)");
        assertEquals(expr("count + name.substring(1)").span().startIndex() + "count + ".length(), p.span().startIndex(),
            "o problema aponta para a subexpressão, não para a expressão inteira");
    }
}
