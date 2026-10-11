package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Perfil {@code lab}: páginas que usam as proteções do subprojeto 14 como devem ser usadas, com
 * entradas hostis. Os testes provam a proteção concreta de cada página (o pentest tenta contorná-las).
 */
// Base H2 própria: o contexto do perfil lab corre na mesma JVM que o contexto por omissão, e o
// jdbc:h2:mem:shop (DB_CLOSE_DELAY=-1) partilhado faria o schema.sql correr duas vezes.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:shop-lab;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("lab")
class LabPagesTest {

    static final String BLOCKED = "about:invalid#suko-blocked";

    /** Esquemas que nunca podem sobreviver num atributo de URL. */
    static final List<String> HOSTILE_URLS = List.of(
        "javascript:alert(1)",
        "  JaVaScRiPt:alert(1)",
        "java\tscript:alert(1)",
        "java\nscript:alert(1)",
        "\u0001javascript:alert(1)",
        "vbscript:msgbox(1)",
        "data:text/html,<script>alert(1)</script>",
        "data:image/png;base64,iVBORw0KGgo=",
        "blob:https://example.com/x");

    /** URLs relativos ao esquema (pentest ronda 1, F1): outro anfitrião; bloqueados por omissão (allowSchemeRelative=false). */
    static final List<String> SCHEME_RELATIVE_URLS = List.of(
        "//evil.example/x",
        "/\\evil.example",
        "\\/evil.example",
        "\\\\evil.example",
        "  //evil.example/x",
        "\t/\\evil.example",
        "/\t/evil.example");

    static final Set<String> URL_ATTRIBUTES = Set.of("href", "src", "action", "formaction", "srcset", "imagesrcset",
        "ping", "poster", "cite", "data");

    @Autowired MockMvc mvc;

    private Document page(MockHttpServletRequestBuilder request) throws Exception {
        return Jsoup.parse(mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Nenhum atributo de URL da página leva um esquema executável (depois de normalizar como o browser). */
    static void noExecutableUrls(Document d) {
        for (Element e : d.getAllElements()) {
            for (var a : e.attributes()) {
                if (!URL_ATTRIBUTES.contains(a.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String key = a.getKey().toLowerCase(Locale.ROOT);
                // srcset/ping são listas: o espaço separa URL e descritores (java<TAB>script:x em srcset é o
                // URL "java" com o descritor inválido "script:x", que o browser descarta). Os outros são um URL só,
                // e o parser de URLs tira TAB/LF/CR em qualquer posição e C0/espaço nas pontas.
                List<String> urls = key.equals("srcset") || key.equals("imagesrcset") || key.equals("ping")
                    ? Arrays.asList(a.getValue().split("[ \\t\\n\\r\\f,]+"))
                    : List.of(a.getValue());
                for (String url : urls) {
                    String v = url.replaceAll("[\\u0000-\\u0020]", "").toLowerCase(Locale.ROOT);
                    assertFalse(v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("data:text"),
                        e.tagName() + " " + a.getKey() + "=" + a.getValue());
                    // F1: nenhum URL relativo ao esquema (// /\ \/ \\ — o browser trata '\' como '/').
                    assertFalse(v.matches("^[/\\\\][/\\\\].*"), e.tagName() + " " + a.getKey() + "=" + a.getValue());
                }
            }
        }
    }

    @Test
    void indexLinksToEveryLabPage() throws Exception {
        Document d = page(get("/lab"));
        List<String> hrefs = d.select("a.lab-page").eachAttr("href");
        for (String p : List.of("/lab/links", "/lab/srcset", "/lab/style", "/lab/embed", "/lab/rel", "/lab/trusted")) {
            assertTrue(hrefs.contains(p), p + " em " + hrefs);
        }
        assertFalse(d.select("form.lab-add input[type=hidden]").isEmpty(), "o formulário leva o token CSRF");
    }

    @Test
    void labPagesCarryTheShopSecurityHeaders() throws Exception {
        for (String url : List.of("/lab", "/lab/links?u=x", "/lab/srcset?s=x", "/lab/style?pct=1&w=1px", "/lab/embed?v=x",
            "/lab/rel?rel=x", "/lab/trusted?site=x")) {
            MockHttpServletResponse r = mvc.perform(get(java.net.URI.create(url))).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals(SecurityHeadersTest.CSP, r.getHeader("Content-Security-Policy"), url);
            assertEquals("strict-origin-when-cross-origin", r.getHeader("Referrer-Policy"), url);
            assertEquals("nosniff", r.getHeader("X-Content-Type-Options"), url);
            assertEquals("DENY", r.getHeader("X-Frame-Options"), url);
            assertNotNull(r.getHeader("Permissions-Policy"), url);
        }
    }

    @Test
    void linksBlockHostileSchemesInHrefSrcActionAndFormaction() throws Exception {
        for (String u : HOSTILE_URLS) {
            Document d = page(get("/lab/links").param("u", u));
            assertEquals(BLOCKED, d.select("a.lab-href").attr("href"), u);
            assertEquals(BLOCKED, d.select("img.lab-src").attr("src"), u);
            assertEquals(BLOCKED, d.select("form.lab-action").attr("action"), u);
            assertEquals(BLOCKED, d.select("button.lab-formaction").attr("formaction"), u);
            noExecutableUrls(d);
        }
    }

    @Test
    void linksKeepAllowedAndRelativeUrls() throws Exception {
        for (String u : List.of("https://example.com/x", "/p/1", "mailto:a@example.com", "javascript&colon;alert(1)")) {
            Document d = page(get("/lab/links").param("u", u));
            assertEquals(u, d.select("a.lab-href").attr("href"), u);
        }
    }

    @Test
    void schemeRelativeUrlsAreBlockedInHrefSrcActionAndFormaction() throws Exception {
        for (String u : SCHEME_RELATIVE_URLS) {
            Document d = page(get("/lab/links").param("u", u));
            assertEquals(BLOCKED, d.select("a.lab-href").attr("href"), u);
            assertEquals(BLOCKED, d.select("img.lab-src").attr("src"), u);
            assertEquals(BLOCKED, d.select("form.lab-action").attr("action"), u);
            assertEquals(BLOCKED, d.select("button.lab-formaction").attr("formaction"), u);
            noExecutableUrls(d);
        }
        // Um só '/' (ou '//' fora do início) continua a ser um caminho da própria origem.
        for (String u : List.of("/a/b?c=d//e", "/p//1")) {
            assertEquals(u, page(get("/lab/links").param("u", u)).select("a.lab-href").attr("href"), u);
        }
    }

    @Test
    void schemeRelativeUrlsAreDroppedFromSrcsetAndPing() throws Exception {
        for (String u : SCHEME_RELATIVE_URLS) {
            Document d = page(get("/lab/srcset").param("s", u.strip() + " 1x, /img/produto.svg 2x"));
            assertEquals("/img/produto.svg 2x", d.select("img.lab-srcset").attr("srcset"), u);
            assertEquals("/img/produto.svg 2x", d.select("picture source.lab-source").attr("srcset"), u);
            assertEquals(BLOCKED, page(get("/lab/srcset").param("s", u)).select("img.lab-img-src").attr("src"), u);
            noExecutableUrls(d);
        }
        Document r = page(get("/lab/rel").param("p", "//evil.example/p /local /\\evil.example \\\\evil.example"));
        assertEquals("/local", r.select("a.lab-ping").attr("ping"));
        noExecutableUrls(r);
    }

    @Test
    void srcsetDropsCandidatesWithInvalidDescriptors() throws Exception {
        // F2: java<TAB>script:x é o URL "java" com o descritor inválido "script:x" — o candidato cai inteiro.
        Document d = page(get("/lab/srcset").param("s", "java\tscript:alert(1) 1x, /img/produto.svg 2x"));
        assertEquals("/img/produto.svg 2x", d.select("img.lab-srcset").attr("srcset"));
        assertEquals("/img/produto.svg 2x", d.select("picture source.lab-source").attr("srcset"));
        assertEquals("/img/produto.svg 1.5x", page(get("/lab/srcset").param("s", "/img/produto.svg 1.5x, /x.svg foo"))
            .select("img.lab-srcset").attr("srcset"));
    }

    @Test
    void storedHostileItemsAreBlockedOnTheLinksPage() throws Exception {
        Document d = page(get("/lab/links"));
        List<String> hrefs = d.select("a.lab-item").eachAttr("href");
        assertTrue(hrefs.size() >= 8, hrefs.toString());
        assertTrue(hrefs.contains(BLOCKED), hrefs.toString());
        noExecutableUrls(d);
    }

    @Test
    void inputsOverTheCapAreRejected() throws Exception {
        mvc.perform(get("/lab/links").param("u", "a".repeat(501))).andExpect(status().isBadRequest());
        mvc.perform(get("/lab/links").param("u", "a".repeat(500))).andExpect(status().isOk());
    }

    @Test
    void srcsetDropsBlockedCandidatesOnImgAndSource() throws Exception {
        Document d = page(get("/lab/srcset").param("s", "javascript:alert(1) 1x, /img/produto.svg 2x"));
        assertEquals("/img/produto.svg 2x", d.select("img.lab-srcset").attr("srcset"));
        assertEquals("/img/produto.svg 2x", d.select("picture source.lab-source").attr("srcset"));
        noExecutableUrls(d);

        // imageDataTypes fica vazio na loja: data:image/* também é bloqueado em img/source
        Document data = page(get("/lab/srcset").param("s", "data:image/png;base64,iVBORw0KGgo= 1x"));
        assertEquals("", data.select("img.lab-srcset").attr("srcset"));
        assertEquals(BLOCKED, page(get("/lab/srcset").param("s", "data:image/png;base64,iVBORw0KGgo="))
            .select("img.lab-img-src").attr("src"));
        noExecutableUrls(page(get("/lab/srcset")));
    }

    @Test
    void styleValuesAreReducedToSafeCssValues() throws Exception {
        Document ok = page(get("/lab/style").param("pct", "50").param("w", "10px"));
        assertEquals("--pct: 50%", ok.select(".lab-pct").attr("style"));
        assertEquals("width: 10px", ok.select(".lab-width").attr("style"));
        for (String hostile : List.of("50;background:url(javascript:alert(1))", "expression(alert(1))", "red;x:y",
            "url(//evil.example/x)", "\"><script>alert(1)</script>", "1px}body{display:none")) {
            Document d = page(get("/lab/style").param("pct", hostile).param("w", hostile));
            assertEquals("--pct: unset%", d.select(".lab-pct").attr("style"), hostile);
            assertEquals("width: unset", d.select(".lab-width").attr("style"), hostile);
            assertTrue(d.select("script").isEmpty(), hostile);
        }
        for (String style : page(get("/lab/style")).select(".lab-item-style").eachAttr("style")) {
            assertFalse(style.contains("(") || style.contains(";") || style.contains("}"), style);
        }
    }

    @Test
    void embedStaysOnTheYoutubeOriginWithAPercentEncodedId() throws Exception {
        String prefix = "https://www.youtube.com/embed/";
        assertEquals(prefix + "dQw4w9WgXcQ", page(get("/lab/embed").param("v", "dQw4w9WgXcQ")).select("iframe.lab-embed").attr("src"));
        // '=' é seguro num segmento (pchar da RFC 3986); '/', '?', '#' e '\' são codificados
        assertEquals(prefix + "..%2F..%2Fwatch%3Fx=1%23y",
            page(get("/lab/embed").param("v", "../../watch?x=1#y")).select("iframe.lab-embed").attr("src"));
        assertEquals(prefix + "%252f", page(get("/lab/embed").param("v", "%2f")).select("iframe.lab-embed").attr("src"));
        assertEquals(prefix + "%22%3E%3Cscript%3E", page(get("/lab/embed").param("v", "\"><script>")).select("iframe.lab-embed").attr("src"));
        assertEquals(prefix, page(get("/lab/embed").param("v", "..")).select("iframe.lab-embed").attr("src"));
        assertEquals(prefix + "%5C%5Cevil.example", page(get("/lab/embed").param("v", "\\\\evil.example")).select("iframe.lab-embed").attr("src"));
        Document d = page(get("/lab/embed"));
        for (String src : d.select("iframe").eachAttr("src")) {
            assertTrue(src.startsWith(prefix), src);
            assertFalse(src.substring(prefix.length()).matches(".*[/?#\\\\\"<>].*"), src);
        }
    }

    @Test
    void targetBlankLinksAlwaysCarryNoopener() throws Exception {
        for (String rel : List.of("", "opener", "noreferrer", "NOOPENER", "opener noreferrer", " \t", "nofollow\u000bopener")) {
            for (String t : List.of("_blank", "x", "_self", "")) {
                Document d = page(get("/lab/rel").param("rel", rel).param("t", t));
                for (Element a : d.select("a[target]")) {
                    String target = a.attr("target");
                    if (target.equals("_self") || target.equals("_parent") || target.equals("_top")) {
                        continue;
                    }
                    List<String> tokens = Arrays.asList(a.attr("rel").toLowerCase(Locale.ROOT).trim().split("[ \\t\\n\\r\\f]+"));
                    assertTrue(tokens.contains("noopener"), "rel=" + rel + " t=" + t + ": " + a.outerHtml());
                }
                assertFalse(d.select("a.lab-rel").isEmpty());
            }
        }
    }

    @Test
    void pingDropsBlockedUrls() throws Exception {
        Document d = page(get("/lab/rel").param("p", "javascript:alert(1) https://example.com/p /local data:text/html,x"));
        assertEquals("https://example.com/p /local", d.select("a.lab-ping").attr("ping"));
        noExecutableUrls(d);
        noExecutableUrls(page(get("/lab/rel")));
    }

    @Test
    void trustedUrlOnlyEverRendersAllowlistValues() throws Exception {
        Set<String> allowlist = Set.of("/", "https://github.com/DumiJDev/suko", "https://example.com/");
        assertEquals("https://github.com/DumiJDev/suko", page(get("/lab/trusted").param("site", "repo")).select("a.lab-trusted").attr("href"));
        for (String site : List.of("javascript:alert(1)", "https://evil.example/", "REPO", "repo ", "__proto__", "", "../repo")) {
            assertEquals("/", page(get("/lab/trusted").param("site", site)).select("a.lab-trusted").attr("href"), site);
        }
        assertEquals("/", page(get("/lab/trusted")).select("a.lab-trusted").attr("href"));
        for (String site : List.of("repo", "exemplo", "loja", "x")) {
            Document d = page(get("/lab/trusted").param("site", site));
            assertTrue(allowlist.contains(d.select("a.lab-trusted").attr("href")), site);
            noExecutableUrls(d);
        }
    }

    @Test
    void addingAnItemNeedsCsrfAndHonoursTheCap() throws Exception {
        mvc.perform(post("/lab/items").param("value", "x")).andExpect(status().isForbidden());
        mvc.perform(post("/lab/items").with(csrf()).param("value", "a".repeat(501))).andExpect(status().isBadRequest());
        mvc.perform(post("/lab/items").with(csrf()).param("value", "")).andExpect(status().isBadRequest());
        String marker = "javascript:alert('lab-teste')";
        mvc.perform(post("/lab/items").with(csrf()).param("value", marker)).andExpect(redirectedUrl("/lab"));
        assertTrue(page(get("/lab")).select(".lab-item-value").eachText().contains(marker));
        Document links = page(get("/lab/links"));
        noExecutableUrls(links);
    }
}
