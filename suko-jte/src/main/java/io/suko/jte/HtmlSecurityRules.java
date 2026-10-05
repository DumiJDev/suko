package io.suko.jte;

import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regras de segurança HTML partilhadas pelo emissor (que embrulha/sanitiza) e
 * pelo {@code HtmlSecurityChecker} (que recusa). Uma só fonte de verdade
 * (subprojeto 14). Nomes de elemento e atributo comparam-se em minúsculas.
 */
public final class HtmlSecurityRules {

    public static final Set<String> LOADING_LINK_RELS =
        Set.of("stylesheet", "import", "preload", "modulepreload", "icon", "manifest");

    private static final Set<String> URL_ATTRIBUTES = Set.of(
        "href", "xlink:href", "src", "action", "formaction", "poster", "cite", "background",
        "manifest", "longdesc", "usemap", "codebase", "itemtype",
        "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete");

    private static final Pattern ORIGIN_PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://[^/?#\\\\@]+/.*", Pattern.DOTALL);
    private static final Pattern STYLE_STATIC =
        Pattern.compile("\\s*(?:--[A-Za-z0-9_-]+|[A-Za-z-]+)\\s*:\\s*[^;\\u0001{}()\"'\\\\]*");
    private static final Pattern STYLE_DYNAMIC =
        Pattern.compile("\\s*(?:--[A-Za-z0-9_-]+|[A-Za-z-]+)\\s*:\\s*[-+]?\\u0001(?:%|[A-Za-z]{1,4})?\\s*");

    private HtmlSecurityRules() {
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Literal = string sem interpolação, ou atributo booleano sem valor. */
    public static boolean isLiteral(Expr e) {
        if (e instanceof Expr.StringLiteralExpr s) {
            return s.parts().stream().allMatch(p -> p instanceof Expr.StringPart.Literal);
        }
        return e instanceof Expr.PrimaryExpr p && p.text().equals("true");
    }

    /** {@code trustedUrl(x)} / {@code trustedStyle(x)} / {@code trustedHtml(x)} → {@code x}. */
    public static Optional<Expr> trustedArgument(Expr value, String functionName) {
        if (value instanceof Expr.CallExpr call
            && call.callee() instanceof Expr.PrimaryExpr p && p.text().equals(functionName)
            && call.args().size() == 1) {
            return Optional.of(call.args().get(0));
        }
        return Optional.empty();
    }

    public static boolean isUrlAttribute(String tag, String attr, SecurityOptions options) {
        String t = lower(tag);
        String a = lower(attr);
        if (a.equals("data")) {
            return t.equals("object");
        }
        return URL_ATTRIBUTES.contains(a) || options.urlAttributes().contains(a);
    }

    public static boolean isSrcset(String attr) {
        String a = lower(attr);
        return a.equals("srcset") || a.equals("imagesrcset");
    }

    public static boolean isPing(String attr) {
        return lower(attr).equals("ping");
    }

    public static boolean isImageContext(String tag, String attr) {
        String t = lower(tag);
        String a = lower(attr);
        return (t.equals("img") || t.equals("source")) && (a.equals("src") || a.equals("srcset"));
    }

    /** Origem constante: esquema+host fixos e '/' no prefixo literal; só o resto é dinâmico. */
    public static boolean originConstant(String tag, String attr, Expr value, SecurityOptions options) {
        String t = lower(tag);
        String a = lower(attr);
        boolean applicable = ((t.equals("iframe") || t.equals("frame") || t.equals("embed") || t.equals("script")) && a.equals("src"))
            || (t.equals("object") && a.equals("data"))
            || (t.equals("link") && a.equals("href"));
        if (!applicable || !(value instanceof Expr.StringLiteralExpr s) || s.parts().isEmpty()
            || !(s.parts().get(0) instanceof Expr.StringPart.Literal first)) {
            return false;
        }
        Matcher m = ORIGIN_PREFIX.matcher(first.javaEscapedText());
        return m.matches() && options.urlSchemes().contains(lower(m.group(1)))
            && (lower(m.group(1)).equals("http") || lower(m.group(1)).equals("https"));
    }

    public record StyleChunk(String literal, Expr interpolation) {
    }

    /**
     * Declarações CSS cujos valores dinâmicos são o valor inteiro (com sinal e
     * unidade opcionais). Devolve os pedaços literal/interpolação, ou vazio se
     * a forma não for aceite.
     */
    public static Optional<List<StyleChunk>> styleDeclarations(Expr value) {
        if (!(value instanceof Expr.StringLiteralExpr s)) {
            return Optional.empty();
        }
        StringBuilder skeleton = new StringBuilder();
        for (Expr.StringPart p : s.parts()) {
            switch (p) {
                case Expr.StringPart.Literal l -> skeleton.append(l.javaEscapedText());
                case Expr.StringPart.Interp i -> skeleton.append('\u0001');
                case Expr.StringPart.SimpleInterp si -> skeleton.append('\u0001');
            }
        }
        for (String declaration : skeleton.toString().split(";", -1)) {
            if (declaration.isBlank()) {
                continue;
            }
            boolean dynamic = declaration.indexOf('\u0001') >= 0;
            Pattern pattern = dynamic ? STYLE_DYNAMIC : STYLE_STATIC;
            if (!pattern.matcher(declaration).matches()) {
                return Optional.empty();
            }
        }
        List<StyleChunk> chunks = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (Expr.StringPart p : s.parts()) {
            switch (p) {
                case Expr.StringPart.Literal l -> literal.append(l.javaEscapedText());
                case Expr.StringPart.Interp i -> {
                    chunks.add(new StyleChunk(literal.toString(), i.expr()));
                    literal.setLength(0);
                }
                case Expr.StringPart.SimpleInterp si -> {
                    chunks.add(new StyleChunk(literal.toString(), new Expr.PrimaryExpr(si.identifier(), s.span())));
                    literal.setLength(0);
                }
            }
        }
        chunks.add(new StyleChunk(literal.toString(), null));
        return Optional.of(chunks);
    }

    /** {@code <a>/<area>/<form>} com {@code target} que pode abrir outro contexto. */
    public static boolean needsNoopener(Statement.HtmlElement el) {
        String t = lower(el.tagName());
        if (!(t.equals("a") || t.equals("area") || t.equals("form"))) {
            return false;
        }
        for (Statement.Attribute a : el.attributes()) {
            if (lower(a.name()).equals("target")) {
                if (!isLiteral(a.value())) {
                    return true;
                }
                String text = a.value() instanceof Expr.StringLiteralExpr s
                    ? Expr.pretty(s.parts()).toLowerCase(Locale.ROOT) : "";
                return !(text.equals("_self") || text.equals("_parent") || text.equals("_top"));
            }
        }
        return false;
    }

    /** Cada {@code @} do texto vira {@code ${"@"}}: o JTE nunca o lê como diretiva. */
    public static String neutralizeJteSyntax(String text) {
        return text.indexOf('@') < 0 ? text : text.replace("@", "${\"@\"}");
    }
}
