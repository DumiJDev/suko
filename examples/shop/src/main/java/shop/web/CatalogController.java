package shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import shop.catalog.CatalogRepository;
import shop.view.Views;

import java.util.List;
import java.util.Map;

@Controller
class CatalogController {

    private static final int PAGE_SIZE = 12;

    private final CatalogRepository repo;

    CatalogController(CatalogRepository repo) {
        this.repo = repo;
    }

    @GetMapping("/")
    String home(Model m) {
        common(m, "Suko Shop");
        m.addAttribute("products", Views.products(repo.products(null, PAGE_SIZE)));
        return "shop/HomePage";
    }

    @GetMapping("/c/{slug}")
    String category(@PathVariable String slug, Model m) {
        Map<String, String> category = repo.category(slug).map(Views::category).orElseThrow(CatalogController::notFound);
        common(m, category.get("name") + " · Suko Shop");
        m.addAttribute("category", category);
        m.addAttribute("products", Views.products(repo.products(Long.valueOf(category.get("id")), PAGE_SIZE)));
        return "shop/CategoryPage";
    }

    /** {@code id} não numérico: {@code MethodArgumentTypeMismatchException} → 400 (tratamento do Spring). */
    @GetMapping("/p/{id}")
    String product(@PathVariable long id, Model m) {
        Map<String, String> product = repo.product(id).map(Views::product).orElseThrow(CatalogController::notFound);
        common(m, product.get("name") + " · Suko Shop");
        m.addAttribute("product", product);
        m.addAttribute("reviews", Views.reviews(repo.reviews(id)));
        return "shop/ProductPage";
    }

    @GetMapping("/search")
    String search(@RequestParam(name = "q", required = false) String q, Model m) {
        String query = CatalogRepository.truncate(q == null ? "" : q.strip());
        common(m, "Pesquisa · Suko Shop");
        m.addAttribute("q", query);
        List<Map<String, String>> products = query.isEmpty() ? List.of() : Views.products(repo.search(query, PAGE_SIZE));
        m.addAttribute("products", products);
        return "shop/SearchPage";
    }

    @ExceptionHandler(ResponseStatusException.class)
    ModelAndView notFoundPage(ResponseStatusException e) {
        ModelAndView mv = new ModelAndView("shop/NotFoundPage", HttpStatus.valueOf(e.getStatusCode().value()));
        mv.addObject("title", "Página não encontrada · Suko Shop");
        mv.addObject("cartCount", 0);
        mv.addObject("categories", Views.categories(repo.categories()));
        return mv;
    }

    private void common(Model m, String title) {
        m.addAttribute("title", title);
        m.addAttribute("categories", Views.categories(repo.categories()));
        m.addAttribute("cartCount", 0);   // substituído pelo carrinho na Task 13
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
}
