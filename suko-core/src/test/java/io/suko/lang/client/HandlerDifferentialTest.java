package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Handlers: depois de correr as mesmas instruções, o state é igual em Java e no Node (também quando lança). */
class HandlerDifferentialTest {

    static final List<Var> VARS = List.of(
        state("int", "count", "0"), state("List<String>", "items", "new ArrayList<>(List.of(\"a\"))"),
        state("String", "label", "\"x\""), state("double", "total", "0.5"), state("Integer", "n", "null"),
        param("int", "step", "5"));

    static final List<String> HANDLERS = List.of(
        "count++; count += step;",
        "count = 2147483647; count++;",
        "count--; count--;",
        "items.add(\"b\"); items.remove(0);",
        "items.set(0, \"z\");",
        "count++; items.remove(3);",
        "items.clear(); items.add(label);",
        "label += 1; label += total;",
        "total += count; total += step;",
        "n++;",
        "label = null; label += \"a\";",
        "count = step * 2; count = count % 3;",
        "items.add(label.trim()); count = items.size();");

    @Test
    void stateAfterTheHandlerIsTheSameInJavaAndInNode() throws Exception {
        List<Outcome> outcomes = run(HANDLERS.stream().map(h -> handler(h, VARS)).toList());
        for (Outcome o : outcomes) {
            assertEquals(o.java(), o.js(), o.c().source());
        }
    }
}
