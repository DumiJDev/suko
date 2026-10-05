package shop;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tomcat real (não MockMvc): sem {@code server.tomcat.use-relative-redirects=true} o {@code Location}
 * de um redirect é absoluto e construído a partir do cabeçalho {@code Host} do pedido.
 * Base de dados própria: este contexto é outro (porta real) e voltaria a correr o schema.sql na mesma.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.datasource.url=jdbc:h2:mem:shop-redirects;DB_CLOSE_DELAY=-1")
class RelativeRedirectsTest {

    @LocalServerPort int port;

    @Test
    void redirectLocationIgnoresAHostileHostHeader() throws IOException {
        String page = raw("GET /p/2 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        Matcher cookie = Pattern.compile("(?i)Set-Cookie: (JSESSIONID=[^;\\r\\n]+)").matcher(page);
        Matcher token = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(page);
        assertTrue(cookie.find() && token.find(), page);

        String body = "_csrf=" + java.net.URLEncoder.encode(token.group(1), StandardCharsets.UTF_8) + "&id=2&quantity=1";
        String response = raw("POST /cart/add HTTP/1.1\r\nHost: evil.example\r\nConnection: close\r\n"
            + "Cookie: " + cookie.group(1) + "\r\n"
            + "Content-Type: application/x-www-form-urlencoded\r\n"
            + "Content-Length: " + body.length() + "\r\n\r\n" + body);
        assertTrue(response.startsWith("HTTP/1.1 302"), response);
        Matcher location = Pattern.compile("(?i)\\r\\nLocation: ([^\\r\\n]*)").matcher(response);
        assertTrue(location.find(), response);
        assertEquals("/cart", location.group(1));
    }

    private String raw(String request) throws IOException {
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(request.getBytes(StandardCharsets.UTF_8));
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
