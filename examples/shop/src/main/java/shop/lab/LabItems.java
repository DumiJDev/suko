package shop.lab;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Valores guardados do lab (só no perfil {@code lab}): uma lista em memória, semeada com valores hostis,
 * que as páginas do lab põem em atributos de URL, srcset, style, ping e no embed. A aplicação guarda o
 * texto tal como veio (só com limite de tamanho); quem o neutraliza é o Suko, no template.
 */
@Component
@Profile("lab")
public class LabItems {

    public static final int VALUE_MAX = 500;
    public static final int MAX_ITEMS = 100;

    static final List<String> SEED = List.of(
        "javascript:alert('lab-1')",
        "  JaVaScRiPt:alert('lab-2')",
        "java\tscript:alert('lab-3')",
        "java\nscript:alert('lab-4')",
        "\u0001javascript:alert('lab-5')",
        "javascript&colon;alert('lab-6')",
        "&#106;avascript:alert('lab-7')",
        "data:text/html,<script>alert('lab-8')</script>",
        "data:image/png;base64,iVBORw0KGgo=",
        "data:image/svg+xml,<svg onload=alert('lab-9')>",
        "vbscript:msgbox('lab-10')",
        "blob:https://example.com/lab-11",
        "//evil.example/lab-12",
        "/\\evil.example/lab-13",
        "javascript:alert('lab-14') 1x, /img/produto.svg 2x",
        "/img/produto.svg 1x, data:image/svg+xml,x 2x",
        "50;background:url(javascript:alert('lab-15'))",
        "expression(alert('lab-16'))",
        "../../watch?x=1#y",
        "\"><img src=x onerror=alert('lab-17')>",
        "https://example.com/ok",
        "/img/produto.svg",
        "mailto:lab@example.com",
        "10px");

    private final List<String> values = new ArrayList<>(SEED);

    /** Cópia, como {@code Map<String,String>} (o que os componentes Suko recebem). */
    public synchronized List<Map<String, String>> all() {
        List<Map<String, String>> out = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("id", String.valueOf(i + 1));
            m.put("value", values.get(i));
            out.add(m);
        }
        return out;
    }

    /** {@code false} se a lista já está cheia. */
    public synchronized boolean add(String value) {
        if (values.size() >= MAX_ITEMS) {
            return false;
        }
        values.add(value);
        return true;
    }
}
