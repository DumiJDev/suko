package shop.cart;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Carrinho guardado na sessão HTTP: só ids de produto e quantidades — nunca preços (vêm sempre da
 * base de dados). Cada sessão tem o seu (bean {@code @SessionScope}), por isso não há id de carrinho
 * que um cliente possa trocar (sem IDOR).
 */
@Component
@SessionScope
public class Cart implements Serializable {

    public static final int MIN_QUANTITY = 1;
    public static final int MAX_QUANTITY = 20;
    public static final int MAX_LINES = 50;

    private final LinkedHashMap<Long, Integer> lines = new LinkedHashMap<>();

    /**
     * Acrescenta {@code quantity} unidades; a linha nunca passa de {@value #MAX_QUANTITY}.
     *
     * @throws IllegalArgumentException quantidade fora de {@value #MIN_QUANTITY}..{@value #MAX_QUANTITY}
     * @throws CartLimitException       já há {@value #MAX_LINES} linhas e o produto é novo
     */
    public synchronized void add(long productId, int quantity) {
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("quantidade fora de " + MIN_QUANTITY + ".." + MAX_QUANTITY);
        }
        Integer current = lines.get(productId);
        if (current == null && lines.size() >= MAX_LINES) {
            throw new CartLimitException();
        }
        lines.put(productId, Math.min(MAX_QUANTITY, (current == null ? 0 : current) + quantity));
    }

    public synchronized void remove(long productId) {
        lines.remove(productId);
    }

    public synchronized void clear() {
        lines.clear();
    }

    /** Cópia (ordem de inserção) — quem a recebe não altera o carrinho. */
    public synchronized Map<Long, Integer> lines() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(lines));
    }

    /** Número total de unidades (o contador do cabeçalho). */
    public synchronized int count() {
        return lines.values().stream().mapToInt(Integer::intValue).sum();
    }
}
