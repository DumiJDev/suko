package shop;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersTest {

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
        + "object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'";

    @Autowired MockMvc mvc;
    @Autowired ApplicationContext context;
    @Autowired Environment env;

    @Test
    void securityHeadersHaveTheExactValues() throws Exception {
        for (String url : List.of("/", "/p/1", "/search?q=x", "/cart", "/checkout", "/c/nao-existe")) {
            MockHttpServletResponse r = mvc.perform(get(url)).andReturn().getResponse();
            assertEquals(CSP, r.getHeader("Content-Security-Policy"), url);
            assertEquals("strict-origin-when-cross-origin", r.getHeader("Referrer-Policy"), url);
            assertEquals("nosniff", r.getHeader("X-Content-Type-Options"), url);
            assertEquals("DENY", r.getHeader("X-Frame-Options"), url);
            assertEquals("camera=(), microphone=(), geolocation=(), payment=(), usb=()", r.getHeader("Permissions-Policy"), url);
        }
    }

    @Test
    void h2ConsoleIsOff() throws Exception {
        mvc.perform(get("/h2-console")).andExpect(status().isNotFound());
        mvc.perform(get("/h2-console/")).andExpect(status().isNotFound());
    }

    @Test
    void noDefaultUserExists() {
        // Sem UserDetailsService próprio, o Spring Boot criava o utilizador "user" com uma password
        // gerada (e escrevia-a no log). A loja não tem rotas autenticadas: nenhum utilizador.
        UserDetailsService users = context.getBean(UserDetailsService.class);
        assertThrows(UsernameNotFoundException.class, () -> users.loadUserByUsername("user"));
    }

    @Test
    void errorResponsesNeverIncludeInternals() {
        assertEquals("never", env.getProperty("server.error.include-message"));
        assertEquals("never", env.getProperty("server.error.include-stacktrace"));
        assertEquals("false", env.getProperty("server.error.include-exception"));
        assertEquals("never", env.getProperty("server.error.include-binding-errors"));
    }

    @Test
    void sessionCookieIsHttpOnlyAndSameSite() {
        assertEquals("true", env.getProperty("server.servlet.session.cookie.http-only"));
        assertEquals("lax", env.getProperty("server.servlet.session.cookie.same-site"));
        assertEquals("cookie", env.getProperty("server.servlet.session.tracking-modes"));
    }

    @Test
    void templatesArePrecompiledNotCompiledAtRuntime() throws Exception {
        assertEquals("true", env.getProperty("gg.jte.use-precompiled-templates"));
        assertEquals("false", env.getProperty("gg.jte.development-mode"));
        // gerado pelo plugin gg.jte.gradle a partir do .jte que o sukoCompile escreveu
        assertNotNull(Class.forName("gg.jte.generated.precompiled.shop.JteHomePageGenerated"));
    }

    @Test
    void securityAuditOfTheShopIsEmpty() throws IOException {
        // A loja não usa trustedUrl/trustedStyle/trustedHtml: o relatório do sukoCompile sai vazio.
        String audit = Files.readString(Path.of("build/suko/security-audit.json"));
        assertTrue(audit.replaceAll("\\s", "").contains("\"entries\":[]"), audit);
    }

    @Test
    void shopSourcesHaveNoInlineStyleScriptHandlersOrEscapeHatches() throws IOException {
        Pattern forbidden = Pattern.compile("style=|<script|\\son[a-z]+=|trustedUrl|trustedStyle|trustedHtml|\\$unsafe",
            Pattern.CASE_INSENSITIVE);
        List<Path> files;
        try (Stream<Path> s = Files.walk(Path.of("src/main/suko"))) {
            files = s.filter(p -> p.toString().endsWith(".sk")).toList();
        }
        assertTrue(files.size() >= 15, "componentes da loja: " + files);
        for (Path f : files) {
            String text = Files.readString(f);
            var m = forbidden.matcher(text);
            if (m.find()) {
                fail(f + " contém " + m.group());
            }
        }
    }
}
