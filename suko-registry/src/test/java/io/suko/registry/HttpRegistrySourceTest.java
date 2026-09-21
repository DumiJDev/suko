package io.suko.registry;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link HttpRegistrySource} against a real {@link HttpServer}
 * (JDK-provided, no new test dependency) bound to {@code localhost}. Every
 * case here mirrors a containment guarantee that {@link
 * FileSystemRegistrySourceTest}-equivalent coverage in {@link
 * RegistrySourceTest} already proves for the filesystem transport: this is
 * the network transport's turn.
 */
class HttpRegistrySourceTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    private HttpServer startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        s.createContext("/", handler);
        s.setExecutor(null);
        s.start();
        this.server = s;
        return s;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    @Test
    void resolvesRelativePathUnderBaseAndReturnsExactBytes() throws IOException {
        byte[] expected = "{\"name\":\"button\"}".getBytes(StandardCharsets.UTF_8);
        HttpServer s = startServer(exchange -> {
            if ("/components/button.json".equals(exchange.getRequestURI().getPath())) {
                respond(exchange, 200, expected);
            } else {
                respond(exchange, 404, new byte[0]);
            }
        });

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        byte[] content = source.resolve("components/button.json");

        assertEquals(new String(expected, StandardCharsets.UTF_8), new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsAbsolutePathWithoutContactingServer() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        assertThrows(IllegalArgumentException.class, () -> source.resolve("/etc/passwd"));
        assertEquals(0, requests.get(), "an absolute path must be rejected lexically, before any network request");
    }

    @Test
    void rejectsPathTraversalEscapingBaseWithoutContactingServer() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer s = startServer(exchange -> {
            requests.incrementAndGet();
            respond(exchange, 200, new byte[0]);
        });

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s) + "components/", true);

        assertThrows(IllegalArgumentException.class, () -> source.resolve("../../secret.txt"));
        assertEquals(0, requests.get(), "a \"../\" that escapes the base must be rejected lexically, before any network request");
    }

    @Test
    void rejectsHttpBaseWithoutAllowInsecureFlag() throws IOException {
        HttpServer s = startServer(exchange -> respond(exchange, 200, new byte[0]));

        assertThrows(IllegalArgumentException.class, () -> new HttpRegistrySource(baseUrl(s), false));
    }

    @Test
    void acceptsHttpBaseWithAllowInsecureFlag() throws IOException {
        byte[] expected = "ok".getBytes(StandardCharsets.UTF_8);
        HttpServer s = startServer(exchange -> respond(exchange, 200, expected));

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        byte[] content = source.resolve("file.txt");

        assertEquals("ok", new String(content, StandardCharsets.UTF_8));
    }

    @Test
    void rejectsRedirectToDifferentPathOnSameHost() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "/moved/file.txt");
            respond(exchange, 302, new byte[0]);
        });

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        IOException ex = assertThrows(IOException.class, () -> source.resolve("file.txt"));
        assertTrue(ex.getMessage().contains("302"), "message should carry the HTTP status: " + ex.getMessage());
    }

    @Test
    void rejectsRedirectToDifferentHost() throws IOException {
        HttpServer s = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", "http://example.invalid/file.txt");
            respond(exchange, 302, new byte[0]);
        });

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        assertThrows(IOException.class, () -> source.resolve("file.txt"));
    }

    @Test
    void notFoundProducesReadableMessageWithUrlAndCode() throws IOException {
        HttpServer s = startServer(exchange -> respond(exchange, 404, new byte[0]));

        HttpRegistrySource source = new HttpRegistrySource(baseUrl(s), true);

        IOException ex = assertThrows(IOException.class, () -> source.resolve("missing.json"));
        assertTrue(ex.getMessage().contains("404"), "message should contain the status code: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("missing.json"), "message should contain the URL: " + ex.getMessage());
    }

    @Test
    void timeoutIsRespectedWhenServerDoesNotRespond() throws IOException {
        HttpServer s = startServer(exchange -> {
            try {
                Thread.sleep(2_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, new byte[0]);
        });

        HttpRegistrySource source = new HttpRegistrySource(
                baseUrl(s), true, Duration.ofMillis(150), Duration.ofMillis(150), 1024L * 1024);

        long start = System.nanoTime();
        assertThrows(IOException.class, () -> source.resolve("slow.json"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 1_500, "the read timeout should fire well before the server's 2s sleep: took " + elapsedMillis + "ms");
    }

    @Test
    void oversizedResponseIsRejected() throws IOException {
        byte[] tooLarge = new byte[1024];
        HttpServer s = startServer(exchange -> respond(exchange, 200, tooLarge));

        HttpRegistrySource source = new HttpRegistrySource(
                baseUrl(s), true, Duration.ofSeconds(5), Duration.ofSeconds(5), 100L);

        IOException ex = assertThrows(IOException.class, () -> source.resolve("big.json"));
        assertTrue(ex.getMessage().toLowerCase().contains("size") || ex.getMessage().toLowerCase().contains("large"),
                "message should mention the size limit: " + ex.getMessage());
    }

    private static String baseUrl(HttpServer s) {
        return "http://localhost:" + s.getAddress().getPort() + "/";
    }
}
