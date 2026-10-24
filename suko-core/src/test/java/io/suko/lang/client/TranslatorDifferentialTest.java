package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expressões do cliente: o JS traduzido dá no Node exatamente o que o Java dá (R16). */
class TranslatorDifferentialTest {

    static final List<Var> VARS = List.of(
        param("int", "a", "7"), param("int", "b", "2"), param("int", "z", "0"),
        param("int", "big", "2147483647"), param("int", "min", "-2147483648"),
        param("double", "d", "1.0"), param("double", "nd", "-0.5"),
        param("String", "s", "\" \\u0001abc\\u00a0 \""), param("String", "ns", "null"),
        param("Integer", "bi", "null"), param("Integer", "bj", "5"),
        param("List<String>", "xs", "new ArrayList<>(List.of(\"a\", \"b\"))"),
        param("Map<String, String>", "m", "new HashMap<>(Map.of(\"k\", \"v\"))"),
        param("Todo", "t", "new Todo(\"x\", true)"),
        state("int", "count", "3"), local("String", "let", "\"kw\""));

    static final List<String> EXPRESSIONS = List.of(
        // int de 32 bits, divisão inteira e por zero
        "a / b", "-a / b", "a % b", "-a % b", "a / z", "a % z", "big + 1", "min - 1", "big * 2", "min / -1", "-min",
        "Math.abs(min)", "count * count",
        // double, -0.0 e promoção
        "d / (z * -1)", "nd * 0", "a + d", "a / 2 * d", "Math.max(a, b)", "Math.min(d, nd)", "a > b ? a : d",
        // concatenação e texto de double
        "1 + 2 + s", "s + 1 + 2", "ns + 1", "\"x\" + ns", "\"x\" + d", "\"x\" + nd * 0", "\"v=\" + d / 3",
        "\"\" + d * 10000000", "\"\" + d / 10000", "let + count",
        // String
        "s.trim()", "s.trim().length()", "s.length()", "s.isEmpty()", "ns.length()", "ns.trim()", "ns == null",
        "s.equals(\" \")", "ns.equals(\"x\")", "s.contains(\"abc\")", "s.contains(ns)", "s.toUpperCase()",
        "a > b ? \"sim\" : ns",
        // List, Map, records
        "xs.get(1)", "xs.get(5)", "xs.get(-1)", "xs.size()", "xs.contains(\"b\")", "xs.isEmpty()",
        "m.get(\"k\")", "m.get(\"nope\")", "m.containsKey(\"k\")", "m.size()",
        "t.title()", "t.done()", "new Todo(\"y\", false).title()",
        // wrappers e igualdade
        "bi == null", "bi + 1", "bj + 1", "bj == 5", "a == 7", "d == 1", "count > 2 && !(a < b)");

    @Test
    void everyExpressionGivesTheSameResultInJavaAndInNode() throws Exception {
        List<Outcome> outcomes = run(EXPRESSIONS.stream().map(e -> expression(e, VARS)).toList());
        for (Outcome o : outcomes) {
            assertEquals(o.java(), o.js(), o.c().source());
        }
    }
}
