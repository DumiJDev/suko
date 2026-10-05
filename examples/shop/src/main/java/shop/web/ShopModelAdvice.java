package shop.web;

import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.ui.Model;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.ModelAndView;
import shop.cart.Cart;
import shop.catalog.CatalogRepository;
import shop.view.Views;

/**
 * Modelo comum a todas as páginas (categorias, contador do carrinho, par CSRF) e páginas de erro.
 *
 * <p>O par CSRF vem do {@link CsrfToken} do Spring Security (repositório de sessão por omissão) e
 * entra nos formulários como {@code <input type="hidden" name=${csrfName} value=${csrfToken}>}.
 * Num redirect o modelo não vai para a URL (Spring 6 ignora o modelo por omissão nos redirects).
 */
@ControllerAdvice
class ShopModelAdvice {

    private final CatalogRepository catalog;
    private final Cart cart;

    ShopModelAdvice(CatalogRepository catalog, Cart cart) {
        this.catalog = catalog;
        this.cart = cart;
    }

    @ModelAttribute
    void common(Model m, @Nullable CsrfToken csrf) {
        m.addAttribute("categories", Views.categories(catalog.categories()));
        m.addAttribute("cartCount", cart.count());
        m.addAttribute("csrfName", csrf == null ? "" : csrf.getParameterName());
        m.addAttribute("csrfToken", csrf == null ? "" : csrf.getToken());
    }

    @ExceptionHandler(ResponseStatusException.class)
    ModelAndView statusPage(ResponseStatusException e) {
        HttpStatus status = HttpStatus.valueOf(e.getStatusCode().value());
        return status == HttpStatus.NOT_FOUND ? notFound() : errorPage(status);
    }

    /** {@code /p/abc}, quantidades não numéricas, parâmetros em falta: 400 com página própria, sem detalhes. */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ModelAndView badRequest(Exception e) {
        return errorPage(HttpStatus.BAD_REQUEST);
    }

    private ModelAndView notFound() {
        return page("shop/NotFoundPage", HttpStatus.NOT_FOUND, "Página não encontrada · Suko Shop");
    }

    private ModelAndView errorPage(HttpStatus status) {
        ModelAndView mv = page("shop/ErrorPage", status, "Pedido inválido · Suko Shop");
        mv.addObject("message", status.is4xxClientError()
            ? "O pedido não é válido. Verifique os dados e tente de novo."
            : "Ocorreu um erro. Tente de novo mais tarde.");
        return mv;
    }

    /** Os {@code @ModelAttribute} não correm para os {@code @ExceptionHandler}: o modelo comum é montado aqui. */
    private ModelAndView page(String view, HttpStatus status, String title) {
        ModelAndView mv = new ModelAndView(view, status);
        mv.addObject("title", title);
        mv.addObject("categories", Views.categories(catalog.categories()));
        mv.addObject("cartCount", cart.count());
        return mv;
    }
}
