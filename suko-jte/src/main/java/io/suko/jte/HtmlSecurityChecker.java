package io.suko.jte;

import io.suko.ext.CheckContext;
import io.suko.ext.Checker;
import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.suko.jte.HtmlSecurityRules.isLiteral;
import static io.suko.jte.HtmlSecurityRules.lower;

/**
 * M2/M3 do subprojeto 14: recusa valores dinâmicos em sinks perigosos,
 * avisa do que a CSP estrita não aceita e reserva os nomes {@code trusted*}.
 * Corre para qualquer alvo que aceite o vocabulário {@code html}.
 */
public final class HtmlSecurityChecker implements Checker {

    private static final Set<String> RESERVED = Set.of("trustedUrl", "trustedStyle", "trustedHtml");
    private static final Set<String> CONTENT_SINKS = Set.of("script", "style", "noscript", "foreignobject");
    private static final Set<String> ANIMATION = Set.of("animate", "set", "animatemotion", "animatetransform");
    private static final Set<String> ANIMATION_ATTRS = Set.of("attributename", "values", "to", "from", "by");

    @Override
    public String id() {
        return "html-security";
    }

    @Override
    public void check(SukoFile file, CheckContext ctx) {
        if (!ctx.activeVocabularies().contains("html")) {
            return;
        }
        for (ComponentDecl component : file.components()) {
            reserved(component.name(), component.nameSpan(), "componente", ctx);
            Set<String> slots = new HashSet<>();
            for (Param p : component.params()) {
                SourceSpan span = p instanceof Param.ValueParam v ? v.nameSpan() : ((Param.SlotParam) p).nameSpan();
                reserved(p.name(), span, "parâmetro", ctx);
                if (p instanceof Param.SlotParam) {
                    slots.add(p.name());
                }
            }
            for (Param p : component.params()) {
                if (p instanceof Param.ValueParam v && v.defaultValue().isPresent()) {
                    scan(v.defaultValue().get(), ctx);
                }
                if (p instanceof Param.SlotParam sp && sp.defaultValue().isPresent()) {
                    scan(sp.defaultValue().get(), ctx);
                }
            }
            walk(component.body(), slots, ctx);
        }
    }

    private void walk(List<Statement> statements, Set<String> slots, CheckContext ctx) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.HtmlElement e -> {
                    element(e, slots, ctx);
                    walk(e.children(), slots, ctx);
                }
                case Statement.IfStmt i -> {
                    scan(i.condition(), ctx);
                    walk(i.thenBranch(), slots, ctx);
                    walk(i.elseBranch(), slots, ctx);
                }
                case Statement.ForStmt f -> {
                    scan(f.iterable(), ctx);
                    walk(f.body(), slots, ctx);
                }
                case Statement.SwitchStmt sw -> {
                    scan(sw.subject(), ctx);
                    for (Statement.SwitchCase c : sw.cases()) {
                        scan(c.matchValue(), ctx);
                        walk(c.body(), slots, ctx);
                    }
                    walk(sw.defaultCase(), slots, ctx);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.Arg arg : call.args()) {
                        scan(arg.value(), ctx);
                    }
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), slots, ctx);
                    }
                }
                case Statement.VarDecl v -> {
                    reserved(v.name(), v.span(), "variável", ctx);
                    scan(v.value(), ctx);
                }
                case Statement.Interpolation i -> scan(i.expr(), ctx);
                case Statement.TextRun t -> { }
            }
        }
    }

    /**
     * Qualquer chamada {@code trusted*(...)} que chegue aqui está fora do único sítio
     * onde o emissor a honra (valor direto de um atributo próprio): sem este erro
     * viraria uma chamada a um método inexistente no javac.
     */
    private void scan(Expr e, CheckContext ctx) {
        switch (e) {
            case Expr.PrimaryExpr p -> { }
            case Expr.StringLiteralExpr s -> {
                for (Expr.StringPart part : s.parts()) {
                    if (part instanceof Expr.StringPart.Interp i) {
                        scan(i.expr(), ctx);
                    }
                }
            }
            case Expr.AccessExpr a -> scan(a.target(), ctx);
            case Expr.CallExpr c -> {
                if (c.callee() instanceof Expr.PrimaryExpr p && RESERVED.contains(p.text())) {
                    ctx.report(Severity.ERROR, "RESERVED_NAME",
                        p.text() + "(...) só é válido como valor direto de um atributo "
                            + (p.text().equals("trustedUrl") ? "de URL (href=${trustedUrl(x)}, sem aspas, um só argumento)"
                            : p.text().equals("trustedStyle") ? "style (style=${trustedStyle(x)}, sem aspas, um só argumento)"
                            : "- trustedHtml ainda não existe nesta versão"), c.span());
                }
                scan(c.callee(), ctx);
                c.args().forEach(x -> scan(x, ctx));
            }
            case Expr.NotExpr n -> scan(n.operand(), ctx);
            case Expr.UnaryMinusExpr u -> scan(u.operand(), ctx);
            case Expr.BinaryExpr b -> {
                scan(b.left(), ctx);
                scan(b.right(), ctx);
            }
            case Expr.TernaryExpr t -> {
                scan(t.condition(), ctx);
                scan(t.whenTrue(), ctx);
                scan(t.whenFalse(), ctx);
            }
            case Expr.ParenExpr p -> scan(p.inner(), ctx);
            case Expr.SafeAccessExpr s -> scan(s.target(), ctx);
            case Expr.ElvisExpr x -> {
                scan(x.left(), ctx);
                scan(x.right(), ctx);
            }
        }
    }

    private void reserved(String name, SourceSpan span, String what, CheckContext ctx) {
        if (RESERVED.contains(name)) {
            ctx.report(Severity.ERROR, "RESERVED_NAME",
                "'" + name + "' é um nome reservado do Suko (" + what + "); não pode ser declarado", span);
        }
    }

    private void element(Statement.HtmlElement e, Set<String> slots, CheckContext ctx) {
        SecurityOptions options = ctx.options();
        String tag = lower(e.tagName());
        uppercase(e.tagName(), e.span(), "elemento", ctx);

        Map<String, Statement.Attribute> attrs = new LinkedHashMap<>();
        for (Statement.Attribute a : e.attributes()) {
            attrs.putIfAbsent(lower(a.name()), a);   // o browser usa a PRIMEIRA ocorrência
        }

        if (CONTENT_SINKS.contains(tag) && hasDynamicContent(e.children())) {
            ctx.report(Severity.ERROR, "UNSAFE_SINK",
                "Conteúdo dinâmico dentro de <" + e.tagName() + "> é executado ou reinterpretado pelo browser; use texto literal"
                    + (tag.equals("script") || tag.equals("style") ? " e passe os dados por atributos data-*" : ""), e.span());
        }
        if (options.strictCsp()) {
            // com nonce a CSP estrita aceita o bloco inline; só os sem nonce (nem src) são avisados
            if (!attrs.containsKey("nonce")
                && (tag.equals("style") || (tag.equals("script") && !attrs.containsKey("src")))) {
                ctx.report(Severity.WARNING, "CSP_INLINE",
                    "<" + e.tagName() + "> inline não é compatível com uma CSP estrita (script-src 'self' / style-src 'self')", e.span());
            }
        }

        for (Statement.Attribute a : e.attributes()) {
            String name = lower(a.name());
            uppercase(a.name(), a.span(), "atributo", ctx);
            Expr v = a.value();

            if (trusted(a, tag, name, options, ctx)) {
                continue;
            }
            scan(v, ctx);
            if (isLiteral(v)) {
                strictCspLiteral(tag, name, v, a, ctx);
                continue;
            }
            if (slotValue(v, slots) && HtmlSecurityRules.isUrlAttribute(tag, name, options)) {
                ctx.report(Severity.ERROR, "UNSAFE_SINK",
                    "Um parâmetro Component (slot) não pode ser o valor do atributo de URL '" + a.name() + "'", a.span());
                continue;
            }
            if (options.strictCsp() && name.equals("style")) {
                ctx.report(Severity.WARNING, "CSP_INLINE",
                    "O atributo 'style' não é compatível com uma CSP estrita (inline/eval)", a.span());
            }
            String reason = sinkReason(tag, name, v, attrs, options);
            if (reason != null) {
                ctx.report(Severity.ERROR, "UNSAFE_SINK",
                    reason + " em <" + e.tagName() + " " + a.name() + "=...>. Use um valor literal"
                        + (HtmlSecurityRules.isUrlAttribute(tag, name, options) || name.equals("src") || name.equals("data")
                            ? " ou ${trustedUrl(...)} (fica registado em security-audit.json)" : ""), a.span());
            }
        }
    }

    /** Devolve {@code true} se o valor é uma chamada {@code trusted*} (já tratada, bem ou mal). */
    private boolean trusted(Statement.Attribute a, String tag, String name, SecurityOptions options, CheckContext ctx) {
        for (String fn : RESERVED) {
            var arg = HtmlSecurityRules.trustedArgument(a.value(), fn);
            if (arg.isEmpty()) {
                continue;
            }
            // Mesma condição que o JteEmitter.attributeValue usa para honrar a chamada.
            boolean ok = switch (fn) {
                case "trustedUrl" -> HtmlSecurityRules.isUrlAttribute(tag, name, options)
                    || HtmlSecurityRules.isSrcset(name) || HtmlSecurityRules.isPing(name);
                case "trustedStyle" -> name.equals("style");
                default -> false;
            };
            scan(arg.get(), ctx);
            if (!ok) {
                ctx.report(Severity.ERROR, "RESERVED_NAME",
                    fn + "(...) não é válido no atributo '" + a.name() + "'"
                        + (fn.equals("trustedHtml") ? " (trustedHtml ainda não existe nesta versão)"
                        : fn.equals("trustedUrl") ? " (só vale em atributos de URL, srcset e ping)"
                        : " (só vale no atributo style)"), a.span());
            } else {
                if (fn.equals("trustedStyle") && options.strictCsp()) {
                    ctx.report(Severity.WARNING, "CSP_INLINE",
                        "O atributo 'style' não é compatível com uma CSP estrita (inline/eval)", a.span());
                }
                ctx.report(Severity.INFO, fn.equals("trustedUrl") ? "TRUSTED_URL" : "TRUSTED_STYLE",
                    "Uso de " + fn + "(...) dispensa a verificação de "
                        + (fn.equals("trustedUrl") ? "URL" : "estilo") + ": " + arg.get().pretty(), a.span());
            }
            return true;
        }
        return false;
    }

    private boolean slotValue(Expr v, Set<String> slots) {
        if (v instanceof Expr.PrimaryExpr p) {
            return slots.contains(p.text());
        }
        return v instanceof Expr.CallExpr c && c.callee() instanceof Expr.PrimaryExpr p && slots.contains(p.text());
    }

    private void uppercase(String name, SourceSpan span, String what, CheckContext ctx) {
        boolean hasLetter = name.chars().anyMatch(Character::isLetter);
        if (hasLetter && name.equals(name.toUpperCase(Locale.ROOT))) {
            ctx.report(Severity.ERROR, "UPPERCASE_NAME",
                "O " + what + " '" + name + "' está todo em maiúsculas; o JTE (OwaspHtmlPolicy) recusa esses nomes", span);
        }
    }

    private void strictCspLiteral(String tag, String name, Expr v, Statement.Attribute a, CheckContext ctx) {
        if (!ctx.options().strictCsp()) {
            return;
        }
        String text = v instanceof Expr.StringLiteralExpr s ? Expr.pretty(s.parts()).trim().toLowerCase(Locale.ROOT) : "";
        boolean inline = name.equals("style") || name.startsWith("on") || name.startsWith("x-")
            || (HtmlSecurityRules.isUrlAttribute(tag, name, ctx.options()) && text.replaceAll("[\\t\\n\\r]", "").startsWith("javascript:"));
        if (inline) {
            ctx.report(Severity.WARNING, "CSP_INLINE",
                "O atributo '" + a.name() + "' não é compatível com uma CSP estrita (inline/eval)", a.span());
        }
    }

    private boolean hasDynamicContent(List<Statement> statements) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.Interpolation i -> {
                    return true;
                }
                case Statement.ComponentCallStmt c -> {
                    return true;
                }
                case Statement.HtmlElement e -> {
                    if (hasDynamicContent(e.children())) {
                        return true;
                    }
                }
                case Statement.IfStmt i -> {
                    if (hasDynamicContent(i.thenBranch()) || hasDynamicContent(i.elseBranch())) {
                        return true;
                    }
                }
                case Statement.ForStmt f -> {
                    if (hasDynamicContent(f.body())) {
                        return true;
                    }
                }
                case Statement.SwitchStmt sw -> {
                    for (Statement.SwitchCase c : sw.cases()) {
                        if (hasDynamicContent(c.body())) {
                            return true;
                        }
                    }
                    if (hasDynamicContent(sw.defaultCase())) {
                        return true;
                    }
                }
                case Statement.TextRun t -> { }
                case Statement.VarDecl v -> { }
            }
        }
        return false;
    }

    /** Motivo pelo qual um valor DINÂMICO é recusado neste atributo, ou {@code null}. */
    private String sinkReason(String tag, String attr, Expr v, Map<String, Statement.Attribute> attrs, SecurityOptions options) {
        boolean origin = HtmlSecurityRules.originConstant(tag, attr, v, options);
        if (attr.startsWith("on")) {
            return "Atributo de evento com valor dinâmico (o escape do JTE não impede a execução)";
        }
        if (attr.equals("srcdoc")) {
            return "srcdoc interpreta o valor como HTML";
        }
        if (attr.equals("style")) {
            return HtmlSecurityRules.styleDeclarations(v).isPresent() ? null
                : "style dinâmico só é aceite na forma 'propriedade: ${valor}'";
        }
        // htmx/Alpine aceitam o prefixo data- (data-hx-on-click, data-x-data)
        String code = attr.startsWith("data-") ? attr.substring(5) : attr;
        if (code.equals("hx-vals") || code.equals("hx-headers") || code.equals("hx-trigger")) {
            return "O atributo '" + attr + "' pode ser avaliado como JavaScript pelo htmx (js:/javascript:/filtros)";
        }
        if (code.startsWith("x-") || code.startsWith("hx-on") || attr.startsWith(":") || attr.startsWith("@")
            || options.codeAttributes().contains(attr)) {
            return "O atributo '" + attr + "' é avaliado como código pelo framework do cliente";
        }
        switch (tag) {
            case "script":
                if (attr.equals("src") && origin) {
                    return null;
                }
                if (attr.equals("src") || attr.equals("href") || attr.equals("xlink:href") || attr.equals("type")) {
                    return "O atributo '" + attr + "' de <script> carrega ou define código";
                }
                break;
            case "link":
                if (attr.equals("rel")) {
                    return "O rel de <link> decide o que o browser carrega";
                }
                if (attr.equals("href") && relLoads(attrs) && !origin) {
                    return "O href de um <link> que carrega recursos";
                }
                break;
            case "meta":
                if (attr.equals("http-equiv") || attr.equals("charset") || attr.equals("name")) {
                    return "O atributo '" + attr + "' de <meta> altera o comportamento da página";
                }
                if (attr.equals("content") && (attrs.containsKey("http-equiv") || metaReferrer(attrs))) {
                    return "O content de <meta http-equiv/referrer> altera cabeçalhos ou redireciona";
                }
                break;
            case "base":
                if (attr.equals("href")) {
                    return "<base href> redireciona todos os URLs relativos da página";
                }
                break;
            case "iframe":
            case "frame":
            case "embed":
                if (attr.equals("src") && !origin) {
                    return "Conteúdo embebido com origem dinâmica";
                }
                break;
            case "object":
                if (attr.equals("data") && !origin) {
                    return "Conteúdo embebido com origem dinâmica";
                }
                break;
            default:
                if (ANIMATION.contains(tag) && ANIMATION_ATTRS.contains(attr)) {
                    return "A animação SVG pode redefinir um atributo (ex.: href) com um valor dinâmico";
                }
        }
        return null;
    }

    private boolean relLoads(Map<String, Statement.Attribute> attrs) {
        Statement.Attribute rel = attrs.get("rel");
        if (rel == null || !(rel.value() instanceof Expr.StringLiteralExpr s) || !isLiteral(s)) {
            return true;
        }
        for (String token : Expr.pretty(s.parts()).toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (HtmlSecurityRules.LOADING_LINK_RELS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean metaReferrer(Map<String, Statement.Attribute> attrs) {
        for (String key : new String[] {"name", "property"}) {
            Statement.Attribute a = attrs.get(key);
            if (a != null && a.value() instanceof Expr.StringLiteralExpr s && isLiteral(s)
                && Expr.pretty(s.parts()).trim().equalsIgnoreCase("referrer")) {
                return true;
            }
        }
        return false;
    }
}
