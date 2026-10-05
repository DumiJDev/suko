package shop.cart;

import org.springframework.stereotype.Component;
import shop.catalog.CatalogRepository;
import shop.view.Views;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Preços do carrinho calculados no servidor, a partir da base de dados. */
@Component
public class CartPricing {

    /** Linhas prontas para os componentes e o total em cêntimos. */
    public record Priced(List<Map<String, String>> lines, long totalCents) {
    }

    private final CatalogRepository catalog;

    public CartPricing(CatalogRepository catalog) {
        this.catalog = catalog;
    }

    public Priced price(Map<Long, Integer> cartLines) {
        List<Map<String, String>> out = new ArrayList<>();
        long total = 0;
        for (Map.Entry<Long, Integer> e : cartLines.entrySet()) {
            var row = catalog.product(e.getKey());
            if (row.isEmpty()) {
                continue;   // produto que deixou de existir: não entra no total
            }
            Map<String, String> product = Views.product(row.get());
            int unit = ((Number) Views.value(row.get(), "price_cents")).intValue();
            long lineTotal = (long) unit * e.getValue();
            total += lineTotal;
            Map<String, String> line = new LinkedHashMap<>();
            line.put("id", product.get("id"));
            line.put("name", product.get("name"));
            line.put("quantity", String.valueOf(e.getValue()));
            line.put("unitPrice", product.get("price"));
            line.put("lineTotal", Views.price(lineTotal));
            out.add(line);
        }
        return new Priced(List.copyOf(out), total);
    }
}
