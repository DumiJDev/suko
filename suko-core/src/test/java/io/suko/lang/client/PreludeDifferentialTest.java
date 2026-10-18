package io.suko.lang.client;

import io.suko.jte.client.ClientPrelude;
import io.suko.jte.client.JsText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** As funções do prelude.js dão, no Node, exatamente o que o Java dá (o Java é o oráculo, nunca valores à mão). */
class PreludeDifferentialTest {

    static final String IMPORT = "import * as $suko from \"" + NodeRunner.dataUrl(ClientPrelude.source()) + "\";\n";

    @Test
    void preludeHasNoDomOrGlobalAccess() {
        String js = ClientPrelude.source();
        for (String forbidden : new String[] {"document", "window", "innerHTML", "eval(", "Function(", "globalThis",
                                              "localStorage", "fetch("}) {
            assertFalse(js.contains(forbidden), forbidden);
        }
    }

    @Test
    void doubleToStringMatchesJava() throws Exception {
        List<Double> values = new ArrayList<>(List.of(0.0, -0.0, 1.0, -1.0, 0.1, 0.3, 0.1 + 0.2, 100.0, 1e7, 9999999.0,
            9999999.999, 1e-3, 0.00099, 1e23, 2e22, 1e21, 123456789.0, 1.5e300, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.MIN_NORMAL, 1.0E-5, 2.0, 0.5, 1 / 3.0, 2 / 3.0, 4.35, 1e-7, 123.456, 1e16, 9007199254740993.0,
            Math.PI, Math.E, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Random random = new Random(42);
        for (int i = 0; i < 20000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            if (!Double.isNaN(d)) {
                values.add(d);
            }
            values.add(random.nextDouble() * Math.pow(10, random.nextInt(20) - 8));
        }
        String hex = values.stream().map(d -> "\"" + Long.toHexString(Double.doubleToRawLongBits(d)) + "\"")
            .collect(Collectors.joining(","));
        String module = IMPORT + """
            const dv = new DataView(new ArrayBuffer(8));
            for (const h of [%s]) {
              dv.setBigUint64(0, BigInt("0x" + h));
              console.log($suko.dstr(dv.getFloat64(0)));
            }
            """.formatted(hex);
        List<String> js = NodeRunner.run(module).lines().toList();
        assertEquals(values.size(), js.size());
        for (int i = 0; i < values.size(); i++) {
            assertEquals(Double.toString(values.get(i)), js.get(i), "Double.toString(" + values.get(i) + ")");
        }
    }

    @Test
    void trimMatchesJavaNotJavaScript() throws Exception {
        List<String> values = List.of(" a ", "\u0001a\u0001", "\u00a0a\u00a0", "\ufeffa", "\u2028a\u2028", "\t\n a b \r",
            "", " ", "\u0000x\u001f", "a\u0020\u0021");
        String module = IMPORT + "for (const s of [" + values.stream().map(JsText::string).collect(Collectors.joining(","))
            + "]) { const t = $suko.trim(s); console.log(Array.from({length: t.length}, (_, i) => t.charCodeAt(i).toString(16)).join(\".\")); }\n";
        List<String> js = NodeRunner.run(module).lines().toList();
        for (int i = 0; i < values.size(); i++) {
            String expected = values.get(i).trim().chars().mapToObj(Integer::toHexString).collect(Collectors.joining("."));
            assertEquals(expected, js.get(i), "trim de #" + i);
        }
    }

    @Test
    void intDivisionAndRemainderMatchJava() throws Exception {
        int[][] pairs = {{7, 2}, {-7, 2}, {7, -2}, {-7, -2}, {0, 5}, {-4, 2}, {5, 0}, {Integer.MIN_VALUE, -1},
                         {Integer.MAX_VALUE, 1}, {Integer.MIN_VALUE, 7}};
        StringBuilder module = new StringBuilder(IMPORT);
        module.append("const r = (f) => { try { return String(f()); } catch (e) { return e.constructor.name; } };\n");
        for (int[] p : pairs) {
            module.append("console.log(r(() => $suko.idiv(").append(p[0]).append(", ").append(p[1]).append(")) + \" \" + r(() => $suko.imod(")
                .append(p[0]).append(", ").append(p[1]).append(")));\n");
        }
        List<String> js = NodeRunner.run(module.toString()).lines().toList();
        for (int i = 0; i < pairs.length; i++) {
            int a = pairs[i][0];
            int b = pairs[i][1];
            String div = b == 0 ? "RangeError" : String.valueOf(a / b);
            String mod = b == 0 ? "RangeError" : String.valueOf(a % b);
            assertEquals(div + " " + mod, js.get(i), a + " / " + b);
        }
    }
}
