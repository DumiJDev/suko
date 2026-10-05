package shop.catalog;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Leitura do catálogo. Todas as consultas usam parâmetros nomeados — nunca concatenação de SQL.
 */
@Repository
public class CatalogRepository {

    /** Comprimento máximo do termo de pesquisa, aplicado antes de qualquer outra coisa. */
    public static final int MAX_QUERY_LENGTH = 80;

    private static final String PRODUCT_COLUMNS =
        "p.id, p.name, p.description, p.price_cents, p.image_url, p.stock, c.slug as category_slug";

    private final JdbcClient jdbc;

    public CatalogRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> categories() {
        return jdbc.sql("select id, slug, name from category order by id").query().listOfRows();
    }

    public Optional<Map<String, Object>> category(String slug) {
        return jdbc.sql("select id, slug, name from category where slug = :slug")
            .param("slug", slug)
            .query().listOfRows().stream().findFirst();
    }

    /** Produtos de uma categoria (ou de todas, com {@code categoryId == null}). */
    public List<Map<String, Object>> products(Long categoryId, int limit) {
        if (categoryId == null) {
            return jdbc.sql("select " + PRODUCT_COLUMNS + " from product p join category c on c.id = p.category_id"
                    + " order by p.id limit :limit")
                .param("limit", limit)
                .query().listOfRows();
        }
        return jdbc.sql("select " + PRODUCT_COLUMNS + " from product p join category c on c.id = p.category_id"
                + " where p.category_id = :categoryId order by p.id limit :limit")
            .param("categoryId", categoryId)
            .param("limit", limit)
            .query().listOfRows();
    }

    public Optional<Map<String, Object>> product(long id) {
        return jdbc.sql("select " + PRODUCT_COLUMNS + " from product p join category c on c.id = p.category_id"
                + " where p.id = :id")
            .param("id", id)
            .query().listOfRows().stream().findFirst();
    }

    /** Pesquisa por nome (sem distinguir maiúsculas); {@code %}, {@code _} e {@code \} do input são literais. */
    public List<Map<String, Object>> search(String q, int limit) {
        String term = truncate(q).toLowerCase(Locale.ROOT);
        String pattern = "%" + escapeLike(term) + "%";
        return jdbc.sql("select " + PRODUCT_COLUMNS + " from product p join category c on c.id = p.category_id"
                + " where lower(p.name) like :q escape '\\' order by p.id limit :limit")
            .param("q", pattern)
            .param("limit", limit)
            .query().listOfRows();
    }

    /** Aplica o limite de {@value #MAX_QUERY_LENGTH} caracteres (sem partir pares substitutos). */
    public static String truncate(String q) {
        if (q == null) {
            return "";
        }
        if (q.codePointCount(0, q.length()) <= MAX_QUERY_LENGTH) {
            return q;
        }
        return q.substring(0, q.offsetByCodePoints(0, MAX_QUERY_LENGTH));
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
