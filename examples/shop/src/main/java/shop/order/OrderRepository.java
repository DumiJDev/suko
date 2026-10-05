package shop.order;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Encomendas: só parâmetros nomeados. As escritas correm dentro da transação de {@link OrderService}. */
@Repository
public class OrderRepository {

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Preço atual do produto, em cêntimos (vazio se o produto não existe). */
    public Optional<Integer> priceCents(long productId) {
        return jdbc.sql("select price_cents from product where id = :id")
            .param("id", productId)
            .query(Integer.class).optional();
    }

    /** Desce o stock só se chegar; devolve {@code false} (nada alterado) quando não chega. */
    public boolean decrementStock(long productId, int quantity) {
        return jdbc.sql("update product set stock = stock - :quantity where id = :id and stock >= :quantity")
            .param("quantity", quantity)
            .param("id", productId)
            .update() == 1;
    }

    public long insertOrder(String name, String email, String address, int totalCents) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("insert into orders (name, email, address, total_cents) values (:name, :email, :address, :total)")
            .param("name", name)
            .param("email", email)
            .param("address", address)
            .param("total", totalCents)
            .update(keys, "id");
        return Objects.requireNonNull(keys.getKey(), "id gerado").longValue();
    }

    public void insertLine(long orderId, long productId, int quantity, int priceCents) {
        jdbc.sql("insert into order_line (order_id, product_id, quantity, price_cents)"
                + " values (:orderId, :productId, :quantity, :price)")
            .param("orderId", orderId)
            .param("productId", productId)
            .param("quantity", quantity)
            .param("price", priceCents)
            .update();
    }

    public Optional<Map<String, Object>> order(long id) {
        return jdbc.sql("select id, name, email, address, total_cents from orders where id = :id")
            .param("id", id)
            .query().listOfRows().stream().findFirst();
    }

    public List<Map<String, Object>> lines(long orderId) {
        return jdbc.sql("select l.product_id, p.name, l.quantity, l.price_cents from order_line l"
                + " join product p on p.id = l.product_id where l.order_id = :orderId order by l.product_id")
            .param("orderId", orderId)
            .query().listOfRows();
    }
}
