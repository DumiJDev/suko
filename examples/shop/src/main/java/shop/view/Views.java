package shop.view;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Achata linhas SQL para {@code Map<String,String>}: os parâmetros dos componentes Suko só aceitam
 * tipos de biblioteca (a gramática ainda não importa tipos Java — lacuna fechada pelo item 12).
 */
public final class Views {

    private Views() {
    }

    public static List<Map<String, String>> categories(List<Map<String, Object>> rows) {
        return rows.stream().map(Views::category).toList();
    }

    public static Map<String, String> category(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", text(row, "id"));
        m.put("slug", text(row, "slug"));
        m.put("name", text(row, "name"));
        return m;
    }

    public static List<Map<String, String>> products(List<Map<String, Object>> rows) {
        return rows.stream().map(Views::product).toList();
    }

    public static Map<String, String> product(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", text(row, "id"));
        m.put("name", text(row, "name"));
        m.put("description", text(row, "description"));
        m.put("price", price(((Number) value(row, "price_cents")).intValue()));
        m.put("imageUrl", text(row, "image_url"));
        m.put("categorySlug", text(row, "category_slug"));
        m.put("stock", text(row, "stock"));
        return m;
    }

    public static List<Map<String, String>> reviews(List<Map<String, Object>> rows) {
        return rows.stream().map(Views::review).toList();
    }

    public static Map<String, String> review(Map<String, Object> row) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("id", text(row, "id"));
        m.put("author", text(row, "author"));
        m.put("body", text(row, "body"));
        m.put("rating", text(row, "rating"));
        m.put("website", text(row, "website"));
        return m;
    }

    /** {@code 1250} → {@code "12,50 €"}. */
    public static String price(int cents) {
        return (cents / 100) + "," + String.format("%02d", cents % 100) + " €";
    }

    /** O JDBC do H2 devolve as colunas em maiúsculas; procura sem distinguir. */
    private static Object value(Map<String, Object> row, String column) {
        if (row.containsKey(column)) {
            return row.get(column);
        }
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static String text(Map<String, Object> row, String column) {
        Object v = value(row, column);
        return v == null ? "" : v.toString();
    }
}
