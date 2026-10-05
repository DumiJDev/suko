package shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import shop.catalog.CatalogRepository;
import shop.view.Views;

import java.util.List;
import java.util.Map;

@Controller
class CatalogController {

    private static final int PAGE_SIZE = 12;

    private final CatalogRepository repo;
    private final ProductPages productPages;

    CatalogController(CatalogRepository repo, ProductPages productPages) {
        this.repo = repo;
        this.productPages = productPages;
    }

    @GetMapping("/")
    String home(Model m) {
        m.addAttribute("title", "Suko Shop");
        m.addAttribute("products", Views.products(repo.products(null, PAGE_SIZE)));
        return "shop/HomePage";
    }

    @GetMapping("/c/{slug}")
    String category(@PathVariable String slug, Model m) {
        Map<String, String> category = repo.category(slug).map(Views::category).orElseThrow(Forms::notFound);
        m.addAttribute("title", category.get("name") + " · Suko Shop");
        m.addAttribute("category", category);
        m.addAttribute("products", Views.products(repo.products(Long.valueOf(category.get("id")), PAGE_SIZE)));
        return "shop/CategoryPage";
    }

    /** {@code id} não numérico: {@code MethodArgumentTypeMismatchException} → 400 ({@link ShopModelAdvice}). */
    @GetMapping("/p/{id}")
    String product(@PathVariable long id, Model m) {
        Map<String, String> product = repo.product(id).map(Views::product).orElseThrow(Forms::notFound);
        return productPages.render(m, id, product, ReviewController.emptyForm(), Map.of());
    }

    @GetMapping("/search")
    String search(@RequestParam(name = "q", required = false) String q, Model m) {
        String query = CatalogRepository.truncate(q == null ? "" : q.strip());
        m.addAttribute("title", "Pesquisa · Suko Shop");
        m.addAttribute("q", query);
        List<Map<String, String>> products = query.isEmpty() ? List.of() : Views.products(repo.search(query, PAGE_SIZE));
        m.addAttribute("products", products);
        return "shop/SearchPage";
    }
}
