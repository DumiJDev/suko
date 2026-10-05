package shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import shop.cart.Cart;
import shop.cart.CartLimitException;
import shop.cart.CartPricing;
import shop.catalog.CatalogRepository;
import shop.view.Views;

/**
 * Carrinho em sessão. Só se leem {@code id} e {@code quantity}: um {@code priceCents} (ou qualquer outro
 * campo) forjado no POST é simplesmente ignorado — o preço vem sempre da base de dados.
 */
@Controller
class CartController {

    private final Cart cart;
    private final CartPricing pricing;
    private final CatalogRepository catalog;

    CartController(Cart cart, CartPricing pricing, CatalogRepository catalog) {
        this.cart = cart;
        this.pricing = pricing;
        this.catalog = catalog;
    }

    @GetMapping("/cart")
    String show(Model m) {
        CartPricing.Priced priced = pricing.price(cart.lines());
        m.addAttribute("title", "Carrinho · Suko Shop");
        m.addAttribute("lines", priced.lines());
        m.addAttribute("total", Views.price(priced.totalCents()));
        m.addAttribute("totalCents", String.valueOf(priced.totalCents()));
        return "shop/CartPage";
    }

    /** Quantidade fora de 1..20 (ou não numérica) → 400; produto inexistente → 404; 51.ª linha → 400. */
    @PostMapping("/cart/add")
    String add(@RequestParam(defaultValue = "") String id, @RequestParam(defaultValue = "") String quantity) {
        long productId = Forms.id(id);
        Integer q = Forms.intBetween(quantity, Cart.MIN_QUANTITY, Cart.MAX_QUANTITY);
        if (q == null) {
            throw Forms.badRequest();
        }
        if (catalog.product(productId).isEmpty()) {
            throw Forms.notFound();
        }
        try {
            cart.add(productId, q);
        } catch (CartLimitException e) {
            throw Forms.badRequest();
        }
        return "redirect:/cart";
    }

    @PostMapping("/cart/remove")
    String remove(@RequestParam(defaultValue = "") String id) {
        cart.remove(Forms.id(id));
        return "redirect:/cart";
    }
}
