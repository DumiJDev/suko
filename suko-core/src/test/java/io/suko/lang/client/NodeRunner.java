package io.suko.lang.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Corre um módulo ES no Node ({@code node --input-type=module}, lido do stdin) e devolve o stdout. Sem
 * {@code node} o teste FALHA (spec 13b, "Testes": um teste saltado em silêncio foi a lacuna (i) do item 14).
 * {@code SUKO_NODE} escolhe outro executável.
 */
final class NodeRunner {

    private NodeRunner() {
    }

    static String node() {
        String explicit = System.getenv("SUKO_NODE");
        return explicit == null || explicit.isBlank() ? "node" : explicit;
    }

    static String run(String module) throws Exception {
        return run(node(), module);
    }

    static String run(String node, String module) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(node, "--input-type=module").redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AssertionError("o Node não arranca ('" + node + "'): os testes do cliente da 13b precisam do Node 18+"
                + " no PATH (ou SUKO_NODE). " + e.getMessage(), e);
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write(module.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("o Node não terminou em 120 s");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("o Node saiu com " + process.exitValue() + ":\n" + out + "\n--- módulo ---\n" + module);
        }
        return out;
    }

    /** URL {@code data:} para importar um módulo sem ficheiros temporários. */
    static String dataUrl(String js) {
        return "data:text/javascript;base64," + Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
    }
}
