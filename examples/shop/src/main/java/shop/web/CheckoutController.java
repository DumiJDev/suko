package shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import shop.cart.Cart;
import shop.cart.CartPricing;
import shop.order.OrderRepository;
import shop.order.OrderService;
import shop.order.PlacedOrders;
import shop.view.Views;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checkout: validação no servidor, total recalculado na transação de {@link OrderService}, e a
 * confirmação só abre para encomendas feitas nesta sessão ({@link PlacedOrders}).
 */
@Controller
class CheckoutController {

    static final int NAME_MAX = 120;
    static final int EMAIL_MIN = 3;
    static final int EMAIL_MAX = 200;
    static final int ADDRESS_MAX = 400;

    private final Cart cart;
    private final CartPricing pricing;
    private final OrderService orderService;
    private final OrderRepository orders;
    private final PlacedOrders placedOrders;

    CheckoutController(Cart cart, CartPricing pricing, OrderService orderService, OrderRepository orders,
                       PlacedOrders placedOrders) {
        this.cart = cart;
        this.pricing = pricing;
        this.orderService = orderService;
        this.orders = orders;
        this.placedOrders = placedOrders;
    }

    @GetMapping("/checkout")
    String form(Model m) {
        return page(m, form("", "", ""), Map.of());
    }

    @PostMapping("/checkout")
    String place(@RequestParam(defaultValue = "") String name,
                 @RequestParam(defaultValue = "") String email,
                 @RequestParam(defaultValue = "") String address,
                 Model m) {
        String n = Forms.clean(name);
        String e = Forms.clean(email);
        String a = Forms.clean(address);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!Forms.lengthBetween(n, 1, NAME_MAX)) {
            errors.put("name", "O nome tem de ter entre 1 e " + NAME_MAX + " caracteres.");
        }
        if (!Forms.lengthBetween(e, EMAIL_MIN, EMAIL_MAX) || e.indexOf('@') < 0 || e.chars().anyMatch(Character::isISOControl)) {
            errors.put("email", "Indique um e-mail válido (até " + EMAIL_MAX + " caracteres).");
        }
        if (!Forms.lengthBetween(a, 1, ADDRESS_MAX)) {
            errors.put("address", "A morada tem de ter entre 1 e " + ADDRESS_MAX + " caracteres.");
        }
        Map<Long, Integer> lines = cart.lines();
        if (lines.isEmpty()) {
            errors.put("general", "O carrinho está vazio.");
        }
        if (errors.isEmpty()) {
            try {
                long id = orderService.placeOrder(lines, n, e, a);
                placedOrders.add(id);
                cart.clear();
                return "redirect:/orders/" + id + "/confirmation";
            } catch (OrderService.CheckoutException ex) {
                errors.put("general", ex.getMessage());
            }
        }
        return page(m, form(name, email, address), errors);
    }

    @GetMapping("/orders/{id}/confirmation")
    String confirmation(@PathVariable long id, Model m) {
        if (!placedOrders.contains(id)) {
            throw Forms.notFound();   // encomenda de outra sessão (ou inexistente): 404, nunca 403 que confirme que existe
        }
        Map<String, Object> row = orders.order(id).orElseThrow(Forms::notFound);
        int totalCents = ((Number) Views.value(row, "total_cents")).intValue();
        Map<String, String> order = new LinkedHashMap<>();
        order.put("id", Views.text(row, "id"));
        order.put("name", Views.text(row, "name"));
        order.put("email", Views.text(row, "email"));
        order.put("address", Views.text(row, "address"));
        order.put("total", Views.price(totalCents));
        order.put("totalCents", String.valueOf(totalCents));
        List<Map<String, String>> lines = orders.lines(id).stream().map(l -> {
            int quantity = ((Number) Views.value(l, "quantity")).intValue();
            int price = ((Number) Views.value(l, "price_cents")).intValue();
            Map<String, String> line = new LinkedHashMap<>();
            line.put("name", Views.text(l, "name"));
            line.put("quantity", String.valueOf(quantity));
            line.put("lineTotal", Views.price((long) quantity * price));
            return line;
        }).toList();
        m.addAttribute("title", "Encomenda confirmada · Suko Shop");
        m.addAttribute("order", order);
        m.addAttribute("lines", lines);
        return "shop/ConfirmationPage";
    }

    private String page(Model m, Map<String, String> form, Map<String, String> errors) {
        CartPricing.Priced priced = pricing.price(cart.lines());
        m.addAttribute("title", "Finalizar compra · Suko Shop");
        m.addAttribute("lines", priced.lines());
        m.addAttribute("total", Views.price(priced.totalCents()));
        m.addAttribute("totalCents", String.valueOf(priced.totalCents()));
        m.addAttribute("form", form);
        m.addAttribute("errors", errors);
        return "shop/CheckoutPage";
    }

    private static Map<String, String> form(String name, String email, String address) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", name);
        f.put("email", email);
        f.put("address", address);
        return f;
    }
}
