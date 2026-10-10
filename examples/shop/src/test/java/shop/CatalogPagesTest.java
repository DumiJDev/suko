package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogPagesTest {

    @Autowired MockMvc mvc;

    private Document page(String url) throws Exception {
        // URI (não String): get(String) trata o texto como URI template e volta a codificar o '%'
        // ("caf%C3%A9" chegava ao controlador literalmente, e os payloads hostis nunca eram decodificados).
        String html = mvc.perform(get(java.net.URI.create(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }

    @Test
    void homeListsProductsAndCategories() throws Exception {
        Document d = page("/");
        assertTrue(d.select(".product-card").size() >= 8);
        assertEquals(3, d.select("nav a[href^=/c/]").size());
        assertFalse(d.title().isBlank());
    }

    @Test
    void pagesStartWithDoctypeAndAvoidQuirksMode() throws Exception {
        for (String url : new String[] {"/", "/p/1", "/cart"}) {
            String html = mvc.perform(get(java.net.URI.create(url))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            assertTrue(html.startsWith("<!DOCTYPE html>"), url + ": " + html.substring(0, Math.min(60, html.length())));
            assertEquals(Document.QuirksMode.noQuirks, Jsoup.parse(html).quirksMode(), url);
        }
    }

    @Test
    void categoryListsOnlyItsProducts() throws Exception {
        Document d = page("/c/livros");
        assertFalse(d.select(".product-card").isEmpty());
        assertTrue(d.select("h1").text().toLowerCase().contains("livros"));
    }

    @Test
    void unknownCategoryAndProductAre404() throws Exception {
        mvc.perform(get("/c/nao-existe")).andExpect(status().isNotFound());
        mvc.perform(get("/p/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/p/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void productPageEscapesDescriptionMarkup() throws Exception {
        // o produto semeado com "<b>negrito?</b>" na descrição: o texto aparece, o elemento <b> não
        Document d = page("/p/1");
        String bodyText = d.select(".description").text();
        assertTrue(d.select(".description b").isEmpty(), d.select(".description").html());
        assertTrue(bodyText.contains("negrito?") || d.select(".description").html().contains("&lt;b&gt;"), bodyText);
    }

    @Test
    void searchFindsByNameAndEchoesTheQueryEscaped() throws Exception {
        Document d = page("/search?q=caf%C3%A9");
        assertFalse(d.select(".product-card").isEmpty());
        assertEquals("café", d.select("input[name=q]").attr("value"));
    }

    @Test
    void searchHandlesHostileInputWithoutErrors() throws Exception {
        for (String q : new String[] {"%27%20OR%201%3D1%20--", "%22%3E%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E", "%25", "", "a".repeat(500)}) {
            Document d = page("/search?q=" + q);
            assertTrue(d.select("img[onerror]").isEmpty());
            assertTrue(d.select("script").isEmpty());
        }
    }

    @Test
    void sqlAndLikeMetacharactersMatchNothingAndAreEchoedAsText() throws Exception {
        // ' OR 1=1 --, % e _ são texto literal: nem injeção nem curinga do LIKE (que apanharia tudo).
        String[][] cases = {{"%27%20OR%201%3D1%20--", "' OR 1=1 --"}, {"%25", "%"}, {"_", "_"}, {"%25%25", "%%"}, {"__", "__"}};
        for (String[] c : cases) {
            Document d = page("/search?q=" + c[0]);
            assertEquals(0, d.select(".product-card").size(), c[1]);
            assertEquals(c[1], d.select("input[name=q]").attr("value"), c[1]);
            assertTrue(d.select(".empty").text().contains(c[1]), d.select(".empty").outerHtml());
            assertTrue(d.select(".empty *").isEmpty(), "o termo é texto, não markup");
        }
    }

    @Test
    void longQueryIsEchoedCutTo80Characters() throws Exception {
        Document d = page("/search?q=" + "a".repeat(500));
        assertEquals("a".repeat(80), d.select("input[name=q]").attr("value"));
        assertFalse(d.html().contains("a".repeat(81)));
    }

    @Test
    void emptySearchShowsAHelpfulMessageNotAnError() throws Exception {
        assertTrue(page("/search?q=zzzznaoexiste").select(".empty").text().length() > 0);
    }
}
