package shop.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import shop.catalog.CatalogRepository;
import shop.catalog.ReviewRepository;
import shop.view.Views;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Avaliações: texto livre de utilizadores, guardado tal como escrito. O {@code website} é só texto
 * com limite de tamanho — a aplicação não valida o esquema de propósito: quem bloqueia
 * {@code javascript:} e companhia no {@code href} é o Suko ({@code SukoSafe.url}).
 */
@Controller
class ReviewController {

    static final int AUTHOR_MAX = 80;
    static final int BODY_MAX = 2000;
    static final int WEBSITE_MAX = 200;

    private final CatalogRepository catalog;
    private final ReviewRepository reviews;
    private final ProductPages productPages;

    ReviewController(CatalogRepository catalog, ReviewRepository reviews, ProductPages productPages) {
        this.catalog = catalog;
        this.reviews = reviews;
        this.productPages = productPages;
    }

    static Map<String, String> emptyForm() {
        return form("", "", "5", "");
    }

    private static Map<String, String> form(String author, String body, String rating, String website) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("author", author);
        f.put("body", body);
        f.put("rating", rating);
        f.put("website", website);
        return f;
    }

    /** Só estes quatro campos são lidos (sem binding de objetos: nada de mass assignment). */
    @PostMapping("/p/{id}/reviews")
    String add(@PathVariable long id,
               @RequestParam(defaultValue = "") String author,
               @RequestParam(defaultValue = "") String body,
               @RequestParam(defaultValue = "") String rating,
               @RequestParam(defaultValue = "") String website,
               Model m) {
        Map<String, String> product = catalog.product(id).map(Views::product).orElseThrow(Forms::notFound);

        String a = Forms.clean(author);
        String b = Forms.clean(body);
        String w = Forms.clean(website);
        Integer r = Forms.intBetween(rating, 1, 5);
        Map<String, String> errors = new LinkedHashMap<>();
        if (!Forms.lengthBetween(a, 1, AUTHOR_MAX)) {
            errors.put("author", "O nome tem de ter entre 1 e " + AUTHOR_MAX + " caracteres.");
        }
        if (!Forms.lengthBetween(b, 1, BODY_MAX)) {
            errors.put("body", "A avaliação tem de ter entre 1 e " + BODY_MAX + " caracteres.");
        }
        if (r == null) {
            errors.put("rating", "A classificação é um número de 1 a 5.");
        }
        if (!Forms.lengthBetween(w, 0, WEBSITE_MAX)) {
            errors.put("website", "O site tem no máximo " + WEBSITE_MAX + " caracteres.");
        }
        if (!errors.isEmpty()) {
            // re-render (200) com o que o utilizador escreveu; o escape é do template
            return productPages.render(m, id, product, form(author, body, rating, website), errors);
        }
        reviews.add(id, a, b, r, w);
        return "redirect:/p/" + id;
    }
}
