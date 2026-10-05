package shop.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Avaliações: guardadas tal como escritas (o escape é do template), sempre com parâmetros nomeados. */
@Repository
public class ReviewRepository {

    private final JdbcClient jdbc;

    public ReviewRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** As mais recentes primeiro. */
    public List<Map<String, Object>> forProduct(long productId) {
        return jdbc.sql("select id, author, body, rating, website from review where product_id = :productId order by id desc")
            .param("productId", productId)
            .query().listOfRows();
    }

    /** {@code website} vazio é guardado como {@code null}. */
    public void add(long productId, String author, String body, int rating, String website) {
        jdbc.sql("insert into review (product_id, author, body, rating, website)"
                + " values (:productId, :author, :body, :rating, :website)")
            .param("productId", productId)
            .param("author", author)
            .param("body", body)
            .param("rating", rating)
            .param("website", website == null || website.isEmpty() ? null : website)
            .update();
    }
}
