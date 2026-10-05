package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O corpus XSS do core (o mesmo da Task 8) contra a loja real: cada payload entra pela pesquisa,
 * por uma avaliação e pelo checkout, e a página seguinte é analisada com jsoup.
 */
@SpringBootTest
@AutoConfigureMockMvc
class XssCorpusShopTest {

    /** Produto reservado ao corpus (as avaliações acumulam-se aqui, não nos produtos dos outros testes). */
    static final long CORPUS_PRODUCT = 12;

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    static List<String> payloads() throws IOException {
        List<String> payloads = Files.readString(Path.of("../../suko-core/src/test/resources/security/xss-corpus.txt"),
                StandardCharsets.UTF_8).lines()
            .filter(l -> !l.isBlank())
            .map(XssCorpusShopTest::decode)
            .toList();
        assertTrue(payloads.size() >= 60, "corpus demasiado pequeno: " + payloads.size());
        return payloads;
    }

    /** Igual ao {@code XssCorpusTest.decode} do suko-core. */
    static String decode(String line) {
        return line.replace("\\t", "\t").replace("\\n", "\n").replace("\\u0000", "\u0000")
            .replace("\\u0001", "\u0001").replace("\\u2028", " ");
    }

    @BeforeEach
    void enoughStockForTheCheckouts() {
        // o corpus faz uma encomenda por payload; o stock do produto usado é reposto (só dados de teste)
        jdbc.sql("update product set stock = 1000 where id = :id").param("id", CORPUS_PRODUCT).update();
    }

    @ParameterizedTest(name = "pesquisa: {0}")
    @MethodSource("payloads")
    void searchQuery(String payload) throws Exception {
        String html = mvc.perform(get("/search").param("q", payload)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertInert(html, payload);
    }

    @ParameterizedTest(name = "avaliação: {0}")
    @MethodSource("payloads")
    void review(String payload) throws Exception {
        mvc.perform(post("/p/" + CORPUS_PRODUCT + "/reviews").with(csrf())
                .param("author", payload).param("body", payload).param("rating", "3").param("website", payload))
            .andExpect(status().is3xxRedirection());
        String html = mvc.perform(get("/p/" + CORPUS_PRODUCT)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertInert(html, payload);
    }

    @ParameterizedTest(name = "avaliação inválida (re-render): {0}")
    @MethodSource("payloads")
    void invalidReviewEcho(String payload) throws Exception {
        String html = mvc.perform(post("/p/" + CORPUS_PRODUCT + "/reviews").with(csrf())
                .param("author", payload).param("body", payload).param("rating", "99").param("website", payload))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertInert(html, payload);
    }

    @ParameterizedTest(name = "checkout: {0}")
    @MethodSource("payloads")
    void checkout(String payload) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/cart/add").session(s).with(csrf()).param("id", String.valueOf(CORPUS_PRODUCT)).param("quantity", "1"))
            .andExpect(status().is3xxRedirection());
        MvcResult r = mvc.perform(post("/checkout").session(s).with(csrf())
                .param("name", payload).param("email", "corpus@exemplo.pt").param("address", payload))
            .andExpect(status().is3xxRedirection()).andReturn();
        String html = mvc.perform(get(r.getResponse().getRedirectedUrl()).session(s)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertInert(html, payload);
    }

    @ParameterizedTest(name = "checkout inválido (re-render): {0}")
    @MethodSource("payloads")
    void invalidCheckoutEcho(String payload) throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/cart/add").session(s).with(csrf()).param("id", String.valueOf(CORPUS_PRODUCT)).param("quantity", "1"))
            .andExpect(status().is3xxRedirection());
        String html = mvc.perform(post("/checkout").session(s).with(csrf())
                .param("name", payload).param("email", "sem-arroba").param("address", payload))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertInert(html, payload);
    }

    static void assertInert(String html, String payload) {
        Document doc = Jsoup.parse(html);
        assertTrue(doc.select("script").isEmpty(), "script injetado por: " + payload);
        for (Element e : doc.getAllElements()) {
            e.attributes().forEach(a -> assertFalse(a.getKey().toLowerCase(Locale.ROOT).startsWith("on"),
                "atributo " + a.getKey() + " injetado por: " + payload));
        }
        for (Element e : doc.select("[href],[src],[action],[formaction]")) {
            for (String attr : List.of("href", "src", "action", "formaction")) {
                if (e.hasAttr(attr)) {
                    String v = e.attr(attr).replaceAll("[\\t\\n\\r]", "").stripLeading().toLowerCase(Locale.ROOT);
                    assertFalse(v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("data:"),
                        attr + " perigoso para: " + payload + " -> " + e.attr(attr));
                }
            }
        }
        assertEquals(0, doc.select("iframe").size(), "iframe injetado por: " + payload);
        assertTrue(doc.select("object, embed, base, meta[http-equiv], svg, math").isEmpty(), "elemento ativo injetado por: " + payload);
    }
}
