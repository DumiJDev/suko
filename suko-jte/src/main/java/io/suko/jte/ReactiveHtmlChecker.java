package io.suko.jte;

import io.suko.ext.CheckContext;
import io.suko.ext.Checker;
import io.suko.jte.client.Callees;
import io.suko.jte.client.ClientModel;
import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JavaTypes;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.suko.jte.HtmlSecurityRules.CLIENT_FORBIDDEN_ELEMENTS;
import static io.suko.jte.HtmlSecurityRules.lower;

/**
 * As regras da 13b que dependem do alvo JTE (o cliente é JS): CLIENT_UNSUPPORTED e TYPE_NOT_INFERRED (o que o
 * {@link ClientModel} encontra), CLIENT_UNSAFE_SINK, BIND_TARGET de nome/elemento/tipo e PROP_NOT_SERIALIZABLE,
 * PROP_SENSITIVE e PROP_MAP_KEYS. Corre depois do {@link HtmlSecurityChecker} (o JteExtension regista-o a seguir)
 * e só omite um ERROR quando o span se sobrepõe ao de um diagnóstico do par declarado em {@link #SUPPRESSED_BY}
 * (um diagnóstico por sítio, R10 revisto): um ERROR que envolve o sítio não esconde o que está dentro.
 */
public final class ReactiveHtmlChecker implements Checker {

    /** R10: o código de um ERROR nosso -> os códigos de diagnósticos já presentes que o fundem. Ausente = nunca omitido. */
    static final Map<String, Set<String>> SUPPRESSED_BY = Map.of(
        "CLIENT_UNSAFE_SINK", Set.of("UNSAFE_SINK"),
        "BIND_TARGET", Set.of("BIND_TARGET"),
        "CLIENT_UNSUPPORTED", Set.of("RESERVED_NAME", "UNSAFE_SINK"),
        "RESERVED_NAME", Set.of("RESERVED_NAME"));

    /** Spec 13b, "Parâmetros que atravessam a fronteira" (R28: a lista da spec mais os oito do fim), por tokens do nome. */
    static final Set<String> SENSITIVE_NAMES = Set.of("password", "passwd", "pwd", "secret", "token", "apikey",
        "privatekey", "credential", "hash", "session", "cookie", "jwt", "auth", "ssn",
        "csrf", "nonce", "otp", "pin", "cvv", "salt", "bearer", "refresh");

    private static final Pattern NAME_TOKENS = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_$-]+");

    private final JavaTypes types;

    public ReactiveHtmlChecker() {
        this(JavaTypes.NONE);
    }

    /** Com formas de records conhecidas (testes; em produção é {@link JavaTypes#NONE}, R13). */
    public ReactiveHtmlChecker(JavaTypes types) {
        this.types = types;
    }

    @Override
    public String id() {
        return "html-reactive";
    }

    @Override
    public void check(SukoFile file, CheckContext ctx) {
        if (!ctx.activeVocabularies().contains("html")) {
            return;
        }
        Callees callees = new Callees(file, ctx.project());
        for (ComponentDecl component : file.components()) {
            if (!component.reactive()) {
                continue;
            }
            ClientModel model = ClientModel.of(component, callees, types);
            for (ClientProblem p : model.problems()) {
                report(ctx, Severity.ERROR, p.code(), p.message(), p.span());
            }
            sinks(model, ctx);
            binds(model, ctx);
            props(model, ctx);
        }
    }

    private void sinks(ClientModel model, CheckContext ctx) {
        for (Statement.HtmlElement e : model.clientElements()) {
            if (CLIENT_FORBIDDEN_ELEMENTS.contains(lower(e.tagName()))) {
                report(ctx, Severity.ERROR, "CLIENT_UNSAFE_SINK", "<" + e.tagName() + "> não pode ser criado no cliente"
                    + " (só o render do servidor o emite); tire-o do bloco que depende de state", e.span());
            }
        }
        for (ClientModel.ClientAttribute ca : model.clientAttributes()) {
            if (model.isClientCreated(ca.element())) {
                continue; // o próprio elemento já é verificado acima
            }
            if (CLIENT_FORBIDDEN_ELEMENTS.contains(lower(ca.element().tagName()))) {
                report(ctx, Severity.ERROR, "CLIENT_UNSAFE_SINK", "o atributo '" + ca.attribute().name() + "' de <"
                    + ca.element().tagName() + "> não pode ser atualizado no cliente", ca.attribute().span());
            }
        }
    }

    private void binds(ClientModel model, CheckContext ctx) {
        for (ClientModel.Site site : model.sites()) {
            if (site.kind() != ClientModel.Site.Kind.BIND) {
                continue;
            }
            Statement.Directive d = (Statement.Directive) site.node();
            if (!(d.value() instanceof Expr.PrimaryExpr p) || !model.stateTypes().containsKey(p.text())) {
                continue; // não é um state simples: é o BIND_TARGET do core
            }
            ClientType type = model.stateTypes().get(p.text());
            if (type == ClientType.Special.UNKNOWN) {
                continue;
            }
            String tag = lower(site.element().tagName());
            String problem = switch (d.name()) {
                case "value" -> !(tag.equals("input") || tag.equals("textarea") || tag.equals("select"))
                    ? "bind:value só existe em <input>, <textarea> e <select>"
                    : type == ClientType.Scalar.STRING || type == ClientType.Scalar.INT || type == ClientType.Scalar.DOUBLE
                        ? null : "bind:value precisa de um state String, int ou double (recebeu " + type.java() + ")";
                case "checked" -> !tag.equals("input")
                    ? "bind:checked só existe em <input>"
                    : type == ClientType.Scalar.BOOLEAN ? null : "bind:checked precisa de um state boolean (recebeu " + type.java() + ")";
                default -> "bind:" + d.name() + " não existe: só bind:value e bind:checked (lista fechada)";
            };
            if (problem != null) {
                report(ctx, Severity.ERROR, "BIND_TARGET", problem, d.span());
            }
        }
    }

    private void props(ClientModel model, CheckContext ctx) {
        for (String name : model.clientReadParams()) {
            Param.ValueParam param = model.param(name).orElseThrow();
            ClientType type = model.paramTypes().get(name);
            if (type instanceof ClientType.Callback) {
                continue; // callbacks nunca vão no JSON (vêm de um pai reativo)
            }
            Optional<String> why = notSerializable(type);
            if (why.isPresent()) {
                report(ctx, Severity.ERROR, "PROP_NOT_SERIALIZABLE", "o parâmetro '" + name + "' (" + type.java()
                    + ") é lido no cliente e tem de ir em data-suko-props, mas " + why.get(), param.nameSpan());
                continue;
            }
            sensitive(name, "o parâmetro '" + name + "'", param.nameSpan(), ctx);
            sensitiveFields(type, param.nameSpan(), ctx);
            if (containsMap(type)) {
                report(ctx, Severity.INFO, "PROP_MAP_KEYS", "as chaves do Map em '" + name + "' ficam visíveis no HTML"
                    + " (data-suko-props)", param.nameSpan());
            }
        }
    }

    private static Optional<String> notSerializable(ClientType t) {
        return switch (t) {
            case ClientType.Scalar s -> Optional.empty();
            case ClientType.Boxed b -> Optional.empty();
            case ClientType.ListOf l -> notSerializable(l.element());
            case ClientType.MapOf m -> notSerializable(m.value());
            case ClientType.RecordOf r -> r.fields().stream().map(f -> notSerializable(f.type()))
                .flatMap(Optional::stream).findFirst();
            case ClientType.Named n -> Optional.of("o tipo Java '" + n.name() + "' não é conhecido do compilador (records e"
                + " enums vêm de Java; R13)");
            case ClientType.Unsupported u -> Optional.of(u.reason());
            case ClientType.Callback c -> Optional.of("uma lambda não vai em JSON");
            case ClientType.Special s -> Optional.of("o tipo não é conhecido");
        };
    }

    private void sensitive(String name, String label, SourceSpan span, CheckContext ctx) {
        sensitiveWord(name).ifPresent(word -> report(ctx, Severity.WARNING, "PROP_SENSITIVE", label
            + " parece um dado sensível ('" + word + "') e fica visível no HTML em data-suko-props; não o leia no cliente", span));
    }

    /** R28: por tokens ('author' não casa 'auth'; 'csrfToken' casa); shortcut: 'accesstoken' é um só token e não casa, WARNING apenas. */
    static Optional<String> sensitiveWord(String name) {
        String[] tokens = NAME_TOKENS.split(name);
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].toLowerCase(Locale.ROOT);
            if (SENSITIVE_NAMES.contains(t) || (t.endsWith("s") && SENSITIVE_NAMES.contains(t.substring(0, t.length() - 1)))) {
                return Optional.of(t);
            }
            if (i + 1 < tokens.length && SENSITIVE_NAMES.contains(t + tokens[i + 1].toLowerCase(Locale.ROOT))) {
                return Optional.of(t + tokens[i + 1].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private void sensitiveFields(ClientType t, SourceSpan span, CheckContext ctx) {
        switch (t) {
            case ClientType.RecordOf r -> {
                for (ClientType.RecordOf.Field f : r.fields()) {
                    sensitive(f.name(), "o componente '" + r.name() + "." + f.name() + "'", span, ctx);
                    sensitiveFields(f.type(), span, ctx);
                }
            }
            case ClientType.ListOf l -> sensitiveFields(l.element(), span, ctx);
            case ClientType.MapOf m -> sensitiveFields(m.value(), span, ctx);
            default -> { }
        }
    }

    private static boolean containsMap(ClientType t) {
        return switch (t) {
            case ClientType.MapOf m -> true;
            case ClientType.ListOf l -> containsMap(l.element());
            case ClientType.RecordOf r -> r.fields().stream().anyMatch(f -> containsMap(f.type()));
            default -> false;
        };
    }

    /** R10: um ERROR nosso só é omitido por um diagnóstico do par declarado (o core e o html-security correm antes). */
    private static void report(CheckContext ctx, Severity severity, String code, String message, SourceSpan span) {
        if (code == null) {
            return;
        }
        if (severity == Severity.ERROR && suppressed(ctx, code, span)) {
            return;
        }
        ctx.report(severity, code, message, span);
    }

    /**
     * Um diagnóstico por sítio, sem esconder o que está dentro ou fora do sítio. Funde-se quando o span do diagnóstico
     * existente (do par declarado) é IGUAL ao nosso; excepções: o mesmo código (RESERVED_NAME/BIND_TARGET, spans pequenos
     * de atributo/diretiva, por sobreposição) e CLIENT_UNSUPPORTED (span de uma subexpressão), que o RESERVED_NAME do
     * atributo que a contém funde, e o UNSAFE_SINK também, salvo o de conteúdo de um elemento (esse envolve o elemento
     * inteiro e é outro problema). O endIndex do SourceSpan é inclusivo.
     */
    private static boolean suppressed(CheckContext ctx, String code, SourceSpan span) {
        Set<String> codes = SUPPRESSED_BY.getOrDefault(code, Set.of());
        if (codes.isEmpty() || span == null || span.isNone() || span.startIndex() < 0) {
            return false;
        }
        for (SukoDiagnostic d : ctx.diagnostics().getDiagnostics()) {
            SourceSpan o = d.span();
            if (!codes.contains(d.code()) || o == null || o.isNone() || o.startIndex() < 0
                    || !Objects.equals(d.sourceFile(), ctx.fileName())) {
                continue;
            }
            boolean equal = o.startIndex() == span.startIndex() && o.endIndex() == span.endIndex();
            boolean overlap = o.startIndex() <= span.endIndex() && span.startIndex() <= o.endIndex();
            boolean encloses = o.startIndex() <= span.startIndex() && o.endIndex() >= span.endIndex();
            boolean hit = equal
                || (d.code().equals(code) && overlap)
                || (code.equals("CLIENT_UNSUPPORTED") && encloses
                    && (d.code().equals("RESERVED_NAME") || !d.message().startsWith(HtmlSecurityRules.DYNAMIC_CONTENT_MESSAGE_PREFIX)));
            if (hit) {
                return true;
            }
        }
        return false;
    }
}
