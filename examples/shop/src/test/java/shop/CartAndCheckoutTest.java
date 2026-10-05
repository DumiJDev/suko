package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CartAndCheckoutTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;

    private ResultActions add(MockHttpSession s, String id, String quantity) throws Exception {
        return mvc.perform(post("/cart/add").session(s).with(csrf()).param("id", id).param("quantity", quantity));
    }

    private Document page(MockHttpSession s, String url) throws Exception {
        return Jsoup.parse(mvc.perform(get(url).session(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private int cartCount(MockHttpSession s) throws Exception {
        return Integer.parseInt(page(s, "/").select(".cart-count").text().strip());
    }

    private int stock(long id) {
        return jdbc.sql("select stock from product where id = :id").param("id", id).query(Integer.class).single();
    }

    private int priceCents(long id) {
        return jdbc.sql("select price_cents from product where id = :id").param("id", id).query(Integer.class).single();
    }

    private long orderCount() {
        return jdbc.sql("select count(*) from orders").query(Long.class).single();
    }

    private ResultActions checkout(MockHttpSession s, String name, String email, String address) throws Exception {
        return mvc.perform(post("/checkout").session(s).with(csrf())
            .param("name", name).param("email", email).param("address", address));
    }

    @Test
    void addingIncreasesTheCounterAndTheLineIsShownInTheCart() throws Exception {
        MockHttpSession s = new MockHttpSession();
        assertEquals(0, cartCount(s));
        add(s, "2", "2").andExpect(status().is3xxRedirection());
        assertEquals(2, cartCount(s));
        add(s, "3", "1").andExpect(status().is3xxRedirection());
        assertEquals(3, cartCount(s));
        Document cart = page(s, "/cart");
        assertEquals(2, cart.select(".cart-line").size());
        // cada linha tem um formulário de remoção com o id escondido e o par CSRF
        for (var form : cart.select(".cart-line form[action=/cart/remove]")) {
            assertEquals("post", form.attr("method"));
            assertFalse(form.select("input[type=hidden][name=id]").attr("value").isEmpty());
            assertFalse(form.select("input[type=hidden][name=_csrf]").attr("value").isEmpty());
        }
        assertEquals(2, cart.select(".cart-line form[action=/cart/remove]").size());
    }

    @Test
    void forgedPriceIsIgnoredTheTotalComesFromTheDatabase() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/cart/add").session(s).with(csrf())
                .param("id", "8").param("quantity", "3").param("priceCents", "1").param("price", "1"))
            .andExpect(status().is3xxRedirection());
        Document cart = page(s, "/cart");
        int expected = priceCents(8) * 3;
        assertEquals(String.valueOf(expected), cart.select(".cart-total").attr("data-cents"));
    }

    @Test
    void quantityOutsideBoundsIs400AndUnknownProductIs404() throws Exception {
        MockHttpSession s = new MockHttpSession();
        for (String q : new String[] {"0", "-1", "21", "99999999999", "abc", "", "1.5"}) {
            add(s, "2", q).andExpect(status().isBadRequest());
        }
        add(s, "999999", "1").andExpect(status().isNotFound());
        add(s, "abc", "1").andExpect(status().isBadRequest());
        assertEquals(0, cartCount(s));
    }

    @Test
    void lineQuantityNeverExceeds20() throws Exception {
        MockHttpSession s = new MockHttpSession();
        add(s, "3", "15").andExpect(status().is3xxRedirection());
        add(s, "3", "15").andExpect(status().is3xxRedirection());
        assertEquals(20, cartCount(s));
    }

    @Test
    void cartHoldsAtMost50Lines() {
        shop.cart.Cart cart = new shop.cart.Cart();
        for (long id = 1; id <= shop.cart.Cart.MAX_LINES; id++) {
            cart.add(id, 1);
        }
        assertThrows(shop.cart.CartLimitException.class, () -> cart.add(1000L, 1));
        cart.add(1L, 1);   // uma linha existente continua a poder crescer
        assertEquals(shop.cart.Cart.MAX_LINES, cart.lines().size());
    }

    @Test
    void removeDropsTheLine() throws Exception {
        MockHttpSession s = new MockHttpSession();
        add(s, "2", "1");
        add(s, "3", "1");
        mvc.perform(post("/cart/remove").session(s).with(csrf()).param("id", "2")).andExpect(status().is3xxRedirection());
        assertEquals(1, cartCount(s));
        assertEquals(1, page(s, "/cart").select(".cart-line").size());
    }

    @Test
    void cartsAreIsolatedPerSession() throws Exception {
        MockHttpSession a = new MockHttpSession();
        MockHttpSession b = new MockHttpSession();
        add(a, "2", "4");
        assertEquals(0, cartCount(b));
        assertTrue(page(b, "/cart").select(".cart-line").isEmpty());
    }

    @Test
    void invalidCheckoutShowsErrorsAndCreatesNoOrder() throws Exception {
        MockHttpSession s = new MockHttpSession();
        add(s, "10", "1");
        long before = orderCount();
        int stockBefore = stock(10);
        for (String[] f : new String[][] {
            {"", "a@b.pt", "Rua"},
            {"n".repeat(121), "a@b.pt", "Rua"},
            {"Nome", "semarroba", "Rua"},
            {"Nome", "@", "Rua"},
            {"Nome", "a@" + "b".repeat(199), "Rua"},
            {"Nome", "a@b.pt", ""},
            {"Nome", "a@b.pt", "r".repeat(401)},
        }) {
            String html = checkout(s, f[0], f[1], f[2]).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(Jsoup.parse(html).select(".error").isEmpty(), String.join("|", f));
        }
        assertEquals(before, orderCount());
        assertEquals(stockBefore, stock(10));
        assertEquals(1, cartCount(s));
    }

    @Test
    void checkoutWithEmptyCartCreatesNoOrder() throws Exception {
        MockHttpSession s = new MockHttpSession();
        long before = orderCount();
        String html = checkout(s, "Nome", "a@b.pt", "Rua").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(Jsoup.parse(html).select(".error").isEmpty());
        assertEquals(before, orderCount());
    }

    @Test
    void validCheckoutCreatesTheOrderLowersStockEmptiesCartAndConfirmationIsPerSession() throws Exception {
        MockHttpSession s = new MockHttpSession();
        add(s, "6", "2");
        add(s, "7", "1");
        int stock6 = stock(6);
        int stock7 = stock(7);
        long before = orderCount();
        Document form = page(s, "/checkout");
        assertFalse(form.select("form[action=/checkout] input[type=hidden][name=_csrf]").attr("value").isEmpty());

        MvcResult r = checkout(s, "Rita <b>Silva</b>", "rita@exemplo.pt", "Rua Direita, 1").andExpect(status().is3xxRedirection()).andReturn();
        String location = r.getResponse().getRedirectedUrl();
        assertNotNull(location);
        assertTrue(location.matches("/orders/\\d+/confirmation"), location);
        long id = Long.parseLong(location.split("/")[2]);

        assertEquals(before + 1, orderCount());
        assertEquals(stock6 - 2, stock(6));
        assertEquals(stock7 - 1, stock(7));
        assertEquals(0, cartCount(s));
        int expected = priceCents(6) * 2 + priceCents(7);
        assertEquals(expected, jdbc.sql("select total_cents from orders where id = :id").param("id", id).query(Integer.class).single());
        assertEquals(2L, jdbc.sql("select count(*) from order_line where order_id = :id").param("id", id).query(Long.class).single());

        Document confirmation = page(s, location);
        assertTrue(confirmation.text().contains("Rita <b>Silva</b>"), "o nome aparece como texto");
        assertTrue(confirmation.select("b").isEmpty());
        assertEquals(String.valueOf(expected), confirmation.select(".order-total").attr("data-cents"));

        // outra sessão (ou nenhuma) não abre a confirmação: nunca IDOR
        mvc.perform(get(location).session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(get(location)).andExpect(status().isNotFound());
        mvc.perform(get("/orders/" + (id + 1000) + "/confirmation").session(s)).andExpect(status().isNotFound());
    }

    @Test
    void checkoutFailsCleanlyWhenStockIsInsufficientAndRollsBack() throws Exception {
        MockHttpSession s = new MockHttpSession();
        add(s, "11", "1");
        add(s, "9", "20");   // o produto 9 tem pouco stock
        int stock11 = stock(11);
        int stock9 = stock(9);
        assertTrue(stock9 < 20);
        long before = orderCount();
        String html = checkout(s, "Nome", "a@b.pt", "Rua").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(Jsoup.parse(html).select(".error").isEmpty());
        assertEquals(before, orderCount());
        assertEquals(stock11, stock(11), "a transação desfaz a descida de stock já feita");
        assertEquals(stock9, stock(9));
    }

    @Test
    void stateChangingPostsWithoutCsrfAreForbidden() throws Exception {
        MockHttpSession s = new MockHttpSession();
        mvc.perform(post("/cart/add").session(s).param("id", "2").param("quantity", "1")).andExpect(status().isForbidden());
        mvc.perform(post("/cart/remove").session(s).param("id", "2")).andExpect(status().isForbidden());
        mvc.perform(post("/checkout").session(s).param("name", "a").param("email", "a@b").param("address", "r"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/cart/add").session(s).with(csrf().useInvalidToken()).param("id", "2").param("quantity", "1"))
            .andExpect(status().isForbidden());
        assertEquals(0, cartCount(s));
    }
}
