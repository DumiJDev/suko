package shop.order;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

/**
 * Ids das encomendas feitas nesta sessão: a página de confirmação só abre para estes
 * (o id na URL sozinho não chega — sem IDOR).
 */
@Component
@SessionScope
public class PlacedOrders implements Serializable {

    private final Set<Long> ids = new HashSet<>();

    public synchronized void add(long id) {
        ids.add(id);
    }

    public synchronized boolean contains(long id) {
        return ids.contains(id);
    }
}
