package shop.order;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * Checkout numa só transação: preços lidos da base de dados (nunca do cliente), stock descido com
 * guarda ({@code stock >= quantidade}), encomenda e linhas gravadas. Qualquer falha desfaz tudo.
 */
@Service
public class OrderService {

    /** Um produto do carrinho já não existe ou não tem stock suficiente. */
    public static class CheckoutException extends RuntimeException {
        public CheckoutException(String message) {
            super(message);
        }
    }

    private final OrderRepository orders;

    public OrderService(OrderRepository orders) {
        this.orders = orders;
    }

    @Transactional
    public long placeOrder(Map<Long, Integer> lines, String name, String email, String address) {
        if (lines.isEmpty()) {
            throw new CheckoutException("O carrinho está vazio.");
        }
        long total = 0;
        Map<Long, Integer> prices = new java.util.LinkedHashMap<>();
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            int price = orders.priceCents(line.getKey())
                .orElseThrow(() -> new CheckoutException("Um dos produtos do carrinho já não existe."));
            if (!orders.decrementStock(line.getKey(), line.getValue())) {
                throw new CheckoutException("Não há stock suficiente para um dos produtos do carrinho.");
            }
            prices.put(line.getKey(), price);
            total += (long) price * line.getValue();
        }
        long id = orders.insertOrder(name, email, address, Math.toIntExact(total));
        for (Map.Entry<Long, Integer> line : lines.entrySet()) {
            orders.insertLine(id, line.getKey(), line.getValue(), prices.get(line.getKey()));
        }
        return id;
    }
}
