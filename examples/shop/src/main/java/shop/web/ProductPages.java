package shop.web;

import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import shop.catalog.ReviewRepository;
import shop.view.Views;

import java.util.Map;

/** Modelo da página de produto, partilhado pela leitura ({@code GET}) e pela avaliação inválida ({@code POST}). */
@Component
class ProductPages {

    private final ReviewRepository reviews;

    ProductPages(ReviewRepository reviews) {
        this.reviews = reviews;
    }

    String render(Model m, long id, Map<String, String> product, Map<String, String> form, Map<String, String> errors) {
        m.addAttribute("title", product.get("name") + " · Suko Shop");
        m.addAttribute("product", product);
        m.addAttribute("reviews", Views.reviews(reviews.forProduct(id)));
        m.addAttribute("form", form);
        m.addAttribute("errors", errors);
        return "shop/ProductPage";
    }
}
