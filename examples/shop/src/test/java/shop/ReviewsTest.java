package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ReviewsTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private ResultActions postReview(long productId, String author, String body, int rating, String website) throws Exception {
        return postReview(productId, author, body, String.valueOf(rating), website);
    }

    private ResultActions postReview(long productId, String author, String body, String rating, String website) throws Exception {
        return mvc.perform(post("/p/" + productId + "/reviews").with(csrf())
            .param("author", author).param("body", body).param("rating", rating).param("website", website));
    }

    private Document page(String url) throws Exception {
        return Jsoup.parse(mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private long reviewCount(long productId) {
        return jdbc.sql("select count(*) from review where product_id = :id").param("id", productId).query(Long.class).single();
    }

    @Test
    void hostileReviewIsStoredAndRenderedAsInertText() throws Exception {
        postReview(1, "<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", 5, "javascript:alert(document.cookie)")
            .andExpect(status().is3xxRedirection());
        Document d = Jsoup.parse(mvc.perform(get("/p/1")).andReturn().getResponse().getContentAsString());
        assertTrue(d.select("script").isEmpty());
        assertTrue(d.select("img[onerror]").isEmpty());
        assertTrue(d.text().contains("<script>alert(1)</script>"));      // visível como texto
        assertEquals("about:invalid#suko-blocked", d.select(".review a.website").attr("href"));   // M1 na prática
        assertTrue(d.text().contains("\"><img src=x onerror=alert(1)>"), "o corpo hostil aparece como texto");
    }

    @Test
    void invalidReviewReRendersWithErrorsAndTheEscapedInput() throws Exception {
        String html = postReview(1, "", "x".repeat(2001), 9, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Document d = Jsoup.parse(html);
        assertFalse(d.select(".error").isEmpty());
        assertFalse(html.contains("x".repeat(2001)) && !html.contains("&"), "o corpo longo não pode ser reescrito sem escape");
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/p/1/reviews").param("author", "a").param("body", "b").param("rating", "5")).andExpect(status().isForbidden());
    }

    @Test
    void validReviewRedirectsToTheProductWithoutLeakingTheModelIntoTheUrl() throws Exception {
        long before = reviewCount(2);
        postReview(2, "Eva", "Bom livro.", 4, "").andExpect(redirectedUrl("/p/2"));
        assertEquals(before + 1, reviewCount(2));
        Document d = page("/p/2");
        assertTrue(d.select(".review .author").eachText().contains("Eva"));
        // website vazio é guardado como ausente: não há link
        assertTrue(d.select(".review").stream().filter(r -> r.select(".author").text().equals("Eva"))
            .allMatch(r -> r.select("a.website").isEmpty()));
    }

    @Test
    void invalidReviewEchoesTheHostileInputEscapedInTheForm() throws Exception {
        String hostile = "\"><script>alert(1)</script>";
        String html = postReview(1, hostile, "", 5, hostile).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Document d = Jsoup.parse(html);
        assertTrue(d.select("script").isEmpty(), html);
        assertEquals(hostile, d.select(".review-form input[name=author]").attr("value"));
        assertEquals(hostile, d.select(".review-form input[name=website]").attr("value"));
        assertFalse(d.select(".review-form .error").isEmpty());
        // o formulário re-renderizado traz o par CSRF
        assertFalse(d.select(".review-form input[type=hidden][name=_csrf]").attr("value").isEmpty());
    }

    @Test
    void lengthAndRatingLimitsAreEnforcedAndNothingIsStored() throws Exception {
        long before = reviewCount(3);
        postReview(3, "a".repeat(81), "ok", 5, "").andExpect(status().isOk());
        postReview(3, "a", "b".repeat(2001), 5, "").andExpect(status().isOk());
        postReview(3, "a", "ok", 0, "").andExpect(status().isOk());
        postReview(3, "a", "ok", 6, "").andExpect(status().isOk());
        postReview(3, "a", "ok", "abc", "").andExpect(status().isOk());
        postReview(3, "a", "ok", 5, "w".repeat(201)).andExpect(status().isOk());
        postReview(3, "   ", "ok", 5, "").andExpect(status().isOk());
        assertEquals(before, reviewCount(3));
        // os limites exatos passam
        postReview(3, "a".repeat(80), "b".repeat(2000), 1, "w".repeat(200)).andExpect(status().is3xxRedirection());
        assertEquals(before + 1, reviewCount(3));
    }

    @Test
    void reviewForUnknownProductIs404() throws Exception {
        postReview(999999, "a", "b", 5, "").andExpect(status().isNotFound());
    }

    @Test
    void hostileSeededWebsiteIsBlockedEndToEnd() throws Exception {
        // data.sql semeia uma avaliação no produto 4 com website "javascript:alert('semente')":
        // a aplicação não valida o esquema, quem o bloqueia é a SukoSafe gerada pelo Suko.
        Document d = page("/p/4");
        assertEquals(1, d.select(".review a.website").size());
        assertEquals("about:invalid#suko-blocked", d.select(".review a.website").attr("href"));
        assertTrue(d.select("[href^=javascript]").isEmpty());
    }
}
