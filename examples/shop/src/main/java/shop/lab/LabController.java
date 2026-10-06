package shop.lab;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Páginas do lab (alvo do pentest do subprojeto 14). <b>Só existem com o perfil {@code lab}</b>: sem
 * ele, todo o {@code /lab/*} dá 404 ({@code LabProfileOffTest}). Nunca ligar este perfil em produção.
 *
 * <p>Cada página recebe texto hostil (query string e {@link LabItems}) e usa a proteção do Suko como
 * deve ser usada: não há vulnerabilidades deliberadas. A aplicação só limita o tamanho
 * ({@value #INPUT_MAX} caracteres; acima disso 400) e passa o texto tal como veio ao template. O
 * modelo comum (categorias, carrinho, CSRF) e as páginas de erro vêm de {@code shop.web.ShopModelAdvice}.
 */
@Controller
@Profile("lab")
@RequestMapping("/lab")
class LabController {

    static final int INPUT_MAX = 500;

    /**
     * A allowlist do único {@code trustedUrl(...)} da loja ({@code LabTrustedPage}). A chave vem do
     * pedido; o URL vem sempre daqui. Chave desconhecida (ou ausente) → {@code /}.
     */
    static final Map<String, String> SITES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("repo", "https://github.com/DumiJDev/suko");
        m.put("exemplo", "https://example.com/");
        m.put("loja", "/");
        SITES = java.util.Collections.unmodifiableMap(m);
    }

    private final LabItems items;

    LabController(LabItems items) {
        this.items = items;
    }

    @GetMapping({"", "/"})
    String index(Model m) {
        m.addAttribute("title", "Lab · Suko Shop");
        m.addAttribute("items", items.all());
        return "shop/lab/LabIndexPage";
    }

    /** Acrescenta um valor aos itens guardados (CSRF obrigatório; 1 a {@value LabItems#VALUE_MAX} caracteres). */
    @PostMapping("/items")
    String add(@RequestParam(defaultValue = "") String value) {
        if (value.isEmpty() || value.length() > LabItems.VALUE_MAX || !items.add(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return "redirect:/lab";
    }

    @GetMapping("/links")
    String links(@RequestParam(required = false) String u, Model m) {
        page(m, "Links").addAttribute("u", capped(u));
        return "shop/lab/LabLinksPage";
    }

    @GetMapping("/srcset")
    String srcset(@RequestParam(required = false) String s, Model m) {
        page(m, "srcset").addAttribute("s", capped(s));
        return "shop/lab/LabSrcsetPage";
    }

    @GetMapping("/style")
    String style(@RequestParam(required = false) String pct, @RequestParam(required = false) String w, Model m) {
        page(m, "style").addAttribute("pct", capped(pct)).addAttribute("w", capped(w));
        return "shop/lab/LabStylePage";
    }

    @GetMapping("/embed")
    String embed(@RequestParam(required = false) String v, Model m) {
        page(m, "embed").addAttribute("v", capped(v));
        return "shop/lab/LabEmbedPage";
    }

    @GetMapping("/rel")
    String rel(@RequestParam(required = false) String rel, @RequestParam(required = false) String t,
               @RequestParam(required = false) String p, Model m) {
        page(m, "rel e ping").addAttribute("rel", capped(rel)).addAttribute("t", capped(t)).addAttribute("p", capped(p));
        return "shop/lab/LabRelPage";
    }

    @GetMapping("/trusted")
    String trusted(@RequestParam(required = false) String site, Model m) {
        String key = capped(site);
        m.addAttribute("title", "trustedUrl · Lab · Suko Shop");
        m.addAttribute("site", key);
        m.addAttribute("target", SITES.getOrDefault(key, "/"));
        m.addAttribute("sites", List.copyOf(SITES.keySet()));
        return "shop/lab/LabTrustedPage";
    }

    private Model page(Model m, String name) {
        m.addAttribute("title", name + " · Lab · Suko Shop");
        m.addAttribute("items", items.all());
        return m;
    }

    /** {@code null} → {@code ""}; mais de {@value #INPUT_MAX} caracteres → 400. O texto não é alterado. */
    private static String capped(String s) {
        if (s == null) {
            return "";
        }
        if (s.length() > INPUT_MAX) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        return s;
    }
}
