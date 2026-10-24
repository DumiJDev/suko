package io.suko.jte.client;

import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Type;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.suko.jte.client.ClientType.Scalar.BOOLEAN;
import static io.suko.jte.client.ClientType.Scalar.DOUBLE;
import static io.suko.jte.client.ClientType.Scalar.INT;
import static io.suko.jte.client.ClientType.Scalar.STRING;
import static io.suko.jte.client.ClientType.Special.EVENT;
import static io.suko.jte.client.ClientType.Special.NULL;
import static io.suko.jte.client.ClientType.Special.UNKNOWN;
import static io.suko.jte.client.ClientType.Special.VOID;

/**
 * Tradutor do subconjunto de Java do cliente (13b) para JS: tipa cada expressão e devolve o JS, ou o primeiro
 * problema com a localização exata (um diagnóstico por sítio). Puro: sem I/O nem DOM. O JS segue o contrato
 * com o runtime do plano 2 (R17): {@code $s} (state e derived), {@code $p} (props), {@code $u("nome")} depois
 * de cada mutação de um state e {@code $suko} (as funções de {@code prelude.js}). A semântica garantida e as
 * recusas estão no R16 do plano 1 e na secção "Expressões no cliente" da spec.
 */
public final class JsTranslator {

    /** Resultado de uma tradução: JS e tipo, ou o problema. */
    public sealed interface Result {
    }

    public record Ok(String js, ClientType type) implements Result {
    }

    public record Err(ClientProblem problem) implements Result {
    }

    public static final String CLIENT_UNSUPPORTED = "CLIENT_UNSUPPORTED";

    private static final Set<String> TRUSTED = Set.of("trustedUrl", "trustedStyle", "trustedHtml");

    /** Interrompe a tradução de uma expressão no primeiro problema. */
    private static final class Stop extends RuntimeException {
        private final ClientProblem problem;

        Stop(ClientProblem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    private record Typed(String js, ClientType type) {
    }

    private final ClientScope scope;
    private final JavaTypes types;

    public JsTranslator(ClientScope scope, JavaTypes types) {
        this.scope = scope;
        this.types = types;
    }

    /** O valor da expressão. */
    public Result expression(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            return new Ok(t.js(), t.type());
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** O texto de um nó, como o JTE o escreveria: null → "", double com String.valueOf. */
    public Result text(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            String js = switch (t.type()) {
                case ClientType.Scalar s when s == INT || s == BOOLEAN -> "String(" + t.js() + ")";
                case ClientType.Scalar s when s == DOUBLE -> "$suko.dstr(" + t.js() + ")";
                case ClientType.Scalar s -> "$suko.text(" + t.js() + ")";
                case ClientType.Boxed b when b.of() == DOUBLE -> "$suko.dtext(" + t.js() + ")";
                case ClientType.Boxed b -> "$suko.text(" + t.js() + ")";
                case ClientType.Special s when s == NULL -> "\"\"";
                default -> throw unsupported(e.span(), "não há texto para " + t.type().java() + " no cliente");
            };
            return new Ok(js, STRING);
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** {@code String.valueOf} de Java, mas null fica null: a entrada dos portes do SukoSafe (url, cssValue, ...). */
    public Result javaString(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            String js = switch (t.type()) {
                case ClientType.Scalar s when s == INT || s == BOOLEAN -> "String(" + t.js() + ")";
                case ClientType.Scalar s when s == DOUBLE -> "$suko.dstr(" + t.js() + ")";
                case ClientType.Scalar s -> t.js();
                case ClientType.Boxed b when b.of() == DOUBLE -> "$suko.dval(" + t.js() + ")";
                case ClientType.Boxed b -> "$suko.sval(" + t.js() + ")";
                case ClientType.Special s when s == NULL -> "null";
                default -> throw unsupported(e.span(), "não há conversão para texto de " + t.type().java() + " no cliente");
            };
            return new Ok(js, STRING);
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** Como o JS gerado lê um nome (o contrato com o runtime do plano 2, R17). */
    public static String access(String name, ClientScope.Kind kind) {
        return switch (kind) {
            case STATE, DERIVED -> "$s." + name;
            case PARAM -> "$p." + name;
            case LOCAL -> JsText.identifier(name);
        };
    }

    // ------------------------------------------------------------------ expressões

    private Typed expr(Expr e, ClientScope s) {
        return switch (e) {
            case Expr.PrimaryExpr p -> primary(p, s);
            case Expr.StringLiteralExpr str -> stringLiteral(str, s);
            case Expr.ParenExpr p -> {
                Typed t = expr(p.inner(), s);
                yield new Typed("(" + t.js() + ")", t.type());
            }
            case Expr.NotExpr n -> new Typed("(!" + bool(value(expr(n.operand(), s), n.operand().span()), n.operand().span()).js() + ")", BOOLEAN);
            case Expr.UnaryMinusExpr u -> negate(u, s);
            case Expr.BinaryExpr b -> binary(b, s);
            case Expr.TernaryExpr t -> ternary(t, s);
            case Expr.CallExpr c -> call(c, s);
            case Expr.NewExpr n -> newRecord(n, s);
            case Expr.AccessExpr a -> throw unsupported(a.span(), "'" + a.memberName() + "' sem parênteses é um acesso a campo;"
                + " no cliente um record lê-se com o acessor: " + a.memberName() + "()");
            case Expr.SafeAccessExpr a -> throw unsupported(a.span(), "'?.' só existe no servidor; no cliente use o ternário"
                + " (x == null ? ... : ...)");
            case Expr.ElvisExpr x -> throw unsupported(x.span(), "'?:' só existe no servidor; no cliente use o ternário");
            case Expr.LambdaExpr l -> throw unsupported(l.span(), "uma lambda só é aceite como valor de on:evento ou como"
                + " argumento de um componente reativo");
            case Expr.AssignExpr a -> throw unsupported(a.span(), "uma atribuição só é aceite como instrução de um handler");
            case Expr.UpdateExpr u -> throw unsupported(u.span(), "'" + u.operator() + "' só é aceite como instrução de um handler");
        };
    }

    private Typed primary(Expr.PrimaryExpr p, ClientScope s) {
        String text = p.text();
        if (text.equals("true") || text.equals("false")) {
            return new Typed(text, BOOLEAN);
        }
        if (text.equals("null")) {
            return new Typed("null", NULL);
        }
        if (!text.isEmpty() && Character.isDigit(text.charAt(0))) {
            return new Typed(intLiteral(text, false, p.span()), INT);
        }
        return name(text, p.span(), s);
    }

    private static String intLiteral(String digits, boolean negative, SourceSpan span) {
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw unsupported(span, "o literal " + digits + " é octal em Java; escreva-o sem o zero à esquerda");
        }
        BigInteger value = new BigInteger(digits);
        BigInteger limit = BigInteger.valueOf(negative ? 2147483648L : Integer.MAX_VALUE);
        if (value.compareTo(limit) > 0) {
            throw unsupported(span, "o literal " + digits + " não cabe num int (long fica fora do cliente)");
        }
        return value.signum() == 0 ? "0" : (negative ? "-" : "") + value;
    }

    private Typed name(String name, SourceSpan span, ClientScope s) {
        ClientScope.Binding b = s.lookup(name).orElseThrow(() -> unsupported(span,
            "'" + name + "' não é um state, derived, parâmetro nem variável do cliente"));
        if (b.type() == UNKNOWN) {
            throw silent(span);
        }
        if (b.type() instanceof ClientType.Unsupported u) {
            throw unsupported(span, "'" + name + "' (" + u.javaType() + "): " + u.reason());
        }
        return new Typed(access(name, b.kind()), b.type());
    }

    private Typed stringLiteral(Expr.StringLiteralExpr str, ClientScope s) {
        List<Expr.StringPart> parts = str.parts();
        // Paridade com o JteEmitter: uma string que é só a interpolação de um parâmetro de referência sai
        // "" quando ele é null (e não "null").
        if (parts.size() == 1 && !(parts.get(0) instanceof Expr.StringPart.Literal)) {
            String id = switch (parts.get(0)) {
                case Expr.StringPart.SimpleInterp si -> si.identifier();
                case Expr.StringPart.Interp i when i.expr() instanceof Expr.PrimaryExpr p -> p.text();
                default -> null;
            };
            Optional<ClientScope.Binding> b = id == null ? Optional.empty() : s.lookup(id);
            if (b.isPresent() && b.get().kind() == ClientScope.Kind.PARAM && plainReference(b.get().type())) {
                Typed v = name(id, str.span(), s);
                return new Typed("(" + v.js() + " == null ? \"\" : \"\" + " + concatOperand(v, str.span()) + ")", STRING);
            }
        }
        List<String> pieces = new ArrayList<>();
        for (Expr.StringPart part : parts) {
            switch (part) {
                case Expr.StringPart.Literal l -> pieces.add(JsText.string(unescape(l.javaEscapedText(), str.span())));
                case Expr.StringPart.Interp i ->
                    pieces.add(concatOperand(value(expr(i.expr(), s), i.expr().span()), i.expr().span()));
                case Expr.StringPart.SimpleInterp si -> pieces.add(concatOperand(name(si.identifier(), str.span(), s), str.span()));
            }
        }
        if (pieces.isEmpty()) {
            return new Typed("\"\"", STRING);
        }
        if (parts.size() == 1 && parts.get(0) instanceof Expr.StringPart.Literal) {
            return new Typed(pieces.get(0), STRING);
        }
        return new Typed("(\"\" + " + String.join(" + ", pieces) + ")", STRING);
    }

    private static boolean plainReference(ClientType t) {
        return t == STRING || t instanceof ClientType.Boxed || t instanceof ClientType.Named || t instanceof ClientType.RecordOf;
    }

    private static String unescape(String javaEscaped, SourceSpan span) {
        try {
            return JsText.unescapeJava(javaEscaped);
        } catch (IllegalArgumentException e) {
            throw unsupported(span, e.getMessage());
        }
    }

    private Typed negate(Expr.UnaryMinusExpr u, ClientScope s) {
        if (u.operand() instanceof Expr.PrimaryExpr p && !p.text().isEmpty() && Character.isDigit(p.text().charAt(0))) {
            return new Typed("(" + intLiteral(p.text(), true, p.span()) + ")", INT);
        }
        Typed t = number(value(expr(u.operand(), s), u.operand().span()), u.operand().span());
        return t.type() == INT
            ? new Typed("((-" + t.js() + ") | 0)", INT)
            : new Typed("(-" + t.js() + ")", DOUBLE);
    }

    private Typed binary(Expr.BinaryExpr b, ClientScope s) {
        Typed l = value(expr(b.left(), s), b.left().span());
        Typed r = value(expr(b.right(), s), b.right().span());
        String op = b.operator();
        return switch (op) {
            case "&&", "||" -> new Typed("(" + bool(l, b.left().span()).js() + " " + op + " "
                + bool(r, b.right().span()).js() + ")", BOOLEAN);
            case "==", "!=" -> equality(l, r, op, b);
            case "<", "<=", ">", ">=" -> new Typed("(" + number(l, b.left().span()).js() + " " + op + " "
                + number(r, b.right().span()).js() + ")", BOOLEAN);
            case "+" -> l.type() == STRING || r.type() == STRING
                ? new Typed("(\"\" + " + concatOperand(l, b.left().span()) + " + " + concatOperand(r, b.right().span()) + ")", STRING)
                : arithmetic(l, r, op, b);
            case "-", "*", "/", "%" -> arithmetic(l, r, op, b);
            default -> throw unsupported(b.span(), "o operador '" + op + "' não faz parte do subconjunto do cliente");
        };
    }

    /** int com aritmética de 32 bits como em Java (R16); double como em IEEE 754, igual nos dois. */
    private static Typed arithmetic(Typed l, Typed r, String op, Expr.BinaryExpr b) {
        Typed a = number(l, b.left().span());
        Typed c = number(r, b.right().span());
        if (a.type() == INT && c.type() == INT) {
            return switch (op) {
                case "/" -> new Typed("$suko.idiv(" + a.js() + ", " + c.js() + ")", INT);
                case "%" -> new Typed("$suko.imod(" + a.js() + ", " + c.js() + ")", INT);
                case "*" -> new Typed("Math.imul(" + a.js() + ", " + c.js() + ")", INT);
                default -> new Typed("((" + a.js() + " " + op + " " + c.js() + ") | 0)", INT);
            };
        }
        return new Typed("(" + a.js() + " " + op + " " + c.js() + ")", DOUBLE);
    }

    private static Typed equality(Typed l, Typed r, String op, Expr.BinaryExpr b) {
        boolean eq = op.equals("==");
        if (l.type() == NULL || r.type() == NULL) {
            Typed other = l.type() == NULL ? r : l;
            if (other.type() == NULL) {
                return new Typed(eq ? "true" : "false", BOOLEAN);
            }
            if (!other.type().nullable()) {
                throw unsupported(b.span(), "um " + other.type().java() + " nunca é null");
            }
            return new Typed("(" + other.js() + (eq ? " == null" : " != null") + ")", BOOLEAN);
        }
        if (l.type() == STRING && r.type() == STRING) {
            throw unsupported(b.span(), "'" + op + "' entre Strings compara referências em Java; use a.equals(b)");
        }
        ClientType.Scalar pl = ClientTypes.primitive(l.type());
        ClientType.Scalar pr = ClientTypes.primitive(r.type());
        boolean bothBoxed = l.type() instanceof ClientType.Boxed && r.type() instanceof ClientType.Boxed;
        boolean numbers = (pl == INT || pl == DOUBLE) && (pr == INT || pr == DOUBLE);
        boolean booleans = pl == BOOLEAN && pr == BOOLEAN;
        if (!bothBoxed && (numbers || booleans)) {
            return new Typed("(" + unbox(l).js() + (eq ? " === " : " !== ") + unbox(r).js() + ")", BOOLEAN);
        }
        throw unsupported(b.span(), "'" + op + "' entre " + l.type().java() + " e " + r.type().java()
            + " compara referências; no cliente compare valores primitivos, use equals() em Strings ou compare com null");
    }

    private Typed ternary(Expr.TernaryExpr t, ClientScope s) {
        Typed c = bool(value(expr(t.condition(), s), t.condition().span()), t.condition().span());
        Typed a = value(expr(t.whenTrue(), s), t.whenTrue().span());
        Typed b = value(expr(t.whenFalse(), s), t.whenFalse().span());
        ClientType type = ClientTypes.unify(a.type(), b.type());
        if (type == null) {
            throw unsupported(t.span(), "os ramos do ternário não têm um tipo comum no cliente (" + a.type().java()
                + " e " + b.type().java() + ")");
        }
        return new Typed("(" + c.js() + " ? " + coerce(a, type) + " : " + coerce(b, type) + ")", type);
    }

    private Typed call(Expr.CallExpr c, ClientScope s) {
        if (c.callee() instanceof Expr.PrimaryExpr p) {
            if (TRUSTED.contains(p.text())) {
                throw new Stop(new ClientProblem("RESERVED_NAME", p.text() + "(...) só é válido no render do servidor:"
                    + " no cliente o valor passa sempre pelo porte do SukoSafe", c.span()));
            }
            throw unsupported(c.span(), "'" + p.text() + "(...)' não é uma chamada permitida no cliente"
                + (!p.text().isEmpty() && Character.isUpperCase(p.text().charAt(0))
                    ? " (um componente usado como valor só existe no servidor)" : ""));
        }
        if (!(c.callee() instanceof Expr.AccessExpr a)) {
            expr(c.callee(), s); // `x?.m()` cai aqui: a mensagem certa é a do `?.`
            throw unsupported(c.span(), "chamada fora do subconjunto do cliente");
        }
        String m = a.memberName();
        if (a.target() instanceof Expr.PrimaryExpr t && t.text().equals("Math") && s.lookup("Math").isEmpty()) {
            return math(m, args(c, s), c);
        }
        Typed receiver = value(expr(a.target(), s), a.target().span());
        List<Typed> args = args(c, s);
        ClientType type = receiver.type();
        if (type == STRING) {
            return stringMethod(receiver, m, args, c);
        }
        if (type instanceof ClientType.ListOf l) {
            return listMethod(receiver, l, m, args, c);
        }
        if (type instanceof ClientType.MapOf map) {
            return mapMethod(receiver, map, m, args, c);
        }
        if (type instanceof ClientType.RecordOf r) {
            return accessor(receiver, r, m, args, c);
        }
        if (type == EVENT) {
            return eventMethod(receiver, m, args, c);
        }
        if (type instanceof ClientType.Callback cb) {
            return invoke(receiver, cb, m, args, c);
        }
        if (type instanceof ClientType.Named n) {
            throw unsupported(c.span(), "o tipo Java '" + n.name() + "' não é conhecido do compilador (records e enums vêm"
                + " de Java); '" + m + "()' não pode ser usado no cliente");
        }
        throw unsupported(c.span(), "'" + m + "()' não existe em " + type.java() + " no subconjunto do cliente");
    }

    private List<Typed> args(Expr.CallExpr c, ClientScope s) {
        List<Typed> out = new ArrayList<>();
        for (Expr arg : c.args()) {
            out.add(value(expr(arg, s), arg.span()));
        }
        return out;
    }

    private static void arity(List<Typed> args, int n, Expr.CallExpr c, String m) {
        if (args.size() != n) {
            throw unsupported(c.span(), m + "() recebe " + n + " argumento(s), não " + args.size());
        }
    }

    private static Typed stringMethod(Typed r, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "length" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").length", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").length === 0)", BOOLEAN);
            }
            case "trim" -> {
                arity(a, 0, c, m);
                return new Typed("$suko.trim(" + r.js() + ")", STRING);
            }
            case "toUpperCase", "toLowerCase" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ")." + m + "()", STRING);
            }
            case "contains", "startsWith", "endsWith" -> {
                arity(a, 1, c, m);
                Typed x = a.get(0);
                if (x.type() != STRING && x.type() != NULL) {
                    throw unsupported(c.args().get(0).span(), m + "() espera uma String, não " + x.type().java());
                }
                return new Typed("$suko." + m + "(" + r.js() + ", " + x.js() + ")", BOOLEAN);
            }
            case "equals" -> {
                arity(a, 1, c, m);
                return new Typed("$suko.equals(" + r.js() + ", " + a.get(0).js() + ")", BOOLEAN);
            }
            default -> throw unsupported(c.span(), "String." + m + "() não faz parte do subconjunto do cliente (lista"
                + " fechada: length, isEmpty, trim, toUpperCase, toLowerCase, contains, startsWith, endsWith, equals)");
        }
    }

    private static Typed listMethod(Typed r, ClientType.ListOf l, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "size" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").length", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").length === 0)", BOOLEAN);
            }
            case "get" -> {
                arity(a, 1, c, m);
                return new Typed("$suko.get(" + r.js() + ", " + index(a.get(0), c.args().get(0).span()).js() + ")", l.element());
            }
            case "contains" -> {
                arity(a, 1, c, m);
                if (!containsMatches(l.element(), a.get(0).type())) {
                    throw unsupported(c.span(), "List.contains() só é suportado em listas de String, Integer ou Boolean,"
                        + " com um argumento do mesmo tipo (o equals de records e de doubles difere em JS)");
                }
                return new Typed("(" + r.js() + ").includes(" + a.get(0).js() + ")", BOOLEAN);
            }
            case "add", "remove", "set", "clear" -> throw unsupported(c.span(), "List." + m + "() altera a lista: só como"
                + " instrução de um handler, sobre um state");
            default -> throw unsupported(c.span(), "List." + m + "() não faz parte do subconjunto do cliente (size, isEmpty,"
                + " get, contains; add/remove(int)/set/clear em handlers)");
        }
    }

    private static boolean containsMatches(ClientType element, ClientType arg) {
        if (element == STRING) {
            return arg == STRING || arg == NULL;
        }
        if (element instanceof ClientType.Boxed b && (b.of() == INT || b.of() == BOOLEAN)) {
            return arg == NULL || arg == b.of() || arg.equals(b);
        }
        return false;
    }

    /** R14 (confirmado): Map de só leitura, chaves String. */
    private static Typed mapMethod(Typed r, ClientType.MapOf map, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "get", "containsKey" -> {
                arity(a, 1, c, m);
                Typed key = a.get(0);
                if (key.type() != STRING && key.type() != NULL) {
                    throw unsupported(c.args().get(0).span(), "as chaves de um Map no cliente são String, não " + key.type().java());
                }
                return m.equals("get")
                    ? new Typed("$suko.mget(" + r.js() + ", " + key.js() + ")", map.value())
                    : new Typed("$suko.mhas(" + r.js() + ", " + key.js() + ")", BOOLEAN);
            }
            case "size" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").size", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").size === 0)", BOOLEAN);
            }
            default -> throw unsupported(c.span(), "Map." + m + "() não faz parte do subconjunto do cliente (get,"
                + " containsKey, size, isEmpty)");
        }
    }

    private static Typed accessor(Typed r, ClientType.RecordOf record, String m, List<Typed> a, Expr.CallExpr c) {
        arity(a, 0, c, m);
        for (ClientType.RecordOf.Field f : record.fields()) {
            if (f.name().equals(m)) {
                return new Typed("$suko.field(" + r.js() + ", " + JsText.string(m) + ")", f.type());
            }
        }
        throw unsupported(c.span(), "o record " + record.name() + " não tem o componente '" + m + "'");
    }

    private static Typed eventMethod(Typed e, String m, List<Typed> a, Expr.CallExpr c) {
        arity(a, 0, c, m);
        return switch (m) {
            case "value" -> new Typed("$suko.evValue(" + e.js() + ")", STRING);
            case "checked" -> new Typed("$suko.evChecked(" + e.js() + ")", BOOLEAN);
            case "key" -> new Typed("$suko.evKey(" + e.js() + ")", STRING);
            case "preventDefault" -> new Typed("$suko.evPrevent(" + e.js() + ")", VOID);
            default -> throw unsupported(c.span(), "o evento só tem value(), checked(), key() e preventDefault() no cliente");
        };
    }

    private static Typed invoke(Typed r, ClientType.Callback cb, String m, List<Typed> a, Expr.CallExpr c) {
        String expected = cb.params().isEmpty() ? "run" : "accept";
        if (!m.equals(expected)) {
            throw unsupported(c.span(), cb.params().isEmpty() ? "um Runnable chama-se com run()" : "um Consumer chama-se com accept(valor)");
        }
        arity(a, cb.params().size(), c, m);
        List<String> js = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            ClientType to = cb.params().get(i);
            if (!ClientTypes.assignable(a.get(i).type(), to)) {
                throw unsupported(c.args().get(i).span(), a.get(i).type().java() + " não é atribuível a " + to.java());
            }
            js.add(coerce(a.get(i), to));
        }
        return new Typed("(" + r.js() + ")(" + String.join(", ", js) + ")", VOID);
    }

    private static Typed math(String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "min", "max" -> {
                arity(a, 2, c, "Math." + m);
                Typed x = number(a.get(0), c.args().get(0).span());
                Typed y = number(a.get(1), c.args().get(1).span());
                ClientType t = x.type() == INT && y.type() == INT ? INT : DOUBLE;
                return new Typed("Math." + m + "(" + x.js() + ", " + y.js() + ")", t);
            }
            case "abs" -> {
                arity(a, 1, c, "Math.abs");
                Typed x = number(a.get(0), c.args().get(0).span());
                return x.type() == INT
                    ? new Typed("(Math.abs(" + x.js() + ") | 0)", INT)
                    : new Typed("Math.abs(" + x.js() + ")", DOUBLE);
            }
            default -> throw unsupported(c.span(), "Math." + m + "() não faz parte do subconjunto do cliente (min, max, abs)");
        }
    }

    private Typed newRecord(Expr.NewExpr n, ClientScope s) {
        ClientType t = ClientTypes.of(new Type(n.typeName(), List.of(), 0), types);
        if (!(t instanceof ClientType.RecordOf r)) {
            throw unsupported(n.span(), "new " + n.typeName() + "(...): no cliente só se criam records conhecidos do compilador"
                + (t instanceof ClientType.Named ? " (o tipo Java '" + n.typeName() + "' não é conhecido: records vêm de Java)" : ""));
        }
        if (n.args().size() != r.fields().size()) {
            throw unsupported(n.span(), "o record " + r.name() + " tem " + r.fields().size() + " componente(s), não " + n.args().size());
        }
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < n.args().size(); i++) {
            ClientType.RecordOf.Field f = r.fields().get(i);
            Typed v = value(expr(n.args().get(i), s), n.args().get(i).span());
            if (!ClientTypes.assignable(v.type(), f.type())) {
                throw unsupported(n.args().get(i).span(), f.name() + ": " + v.type().java() + " não é atribuível a " + f.type().java());
            }
            names.add(JsText.string(f.name()));
            values.add(coerce(v, f.type()));
        }
        return new Typed("$suko.rec([" + String.join(", ", names) + "], [" + String.join(", ", values) + "])", r);
    }

    // ------------------------------------------------------------------ handlers (Task 7)

    private static final Set<String> LIST_MUTATIONS = Set.of("add", "remove", "set", "clear");

    /** Handler de {@code on:evento}: sem parâmetro (D3: o evento é assumido e ignorado) ou com um, o evento. */
    public Result eventHandler(Expr.LambdaExpr lambda) {
        if (lambda.params().size() > 1) {
            return new Err(new ClientProblem(CLIENT_UNSUPPORTED, "um handler de evento recebe no máximo um parâmetro (o evento)",
                lambda.span()));
        }
        return function(lambda, lambda.params().isEmpty() ? List.of() : List.of(EVENT));
    }

    /** Lambda passada a um componente reativo: {@code Runnable} (sem parâmetros) ou {@code Consumer<T>} (um). */
    public Result callback(Expr.LambdaExpr lambda, List<ClientType> params) {
        if (lambda.params().size() != params.size()) {
            return new Err(new ClientProblem(CLIENT_UNSUPPORTED, "a lambda tem " + lambda.params().size()
                + " parâmetro(s), mas o parâmetro do componente espera " + params.size(), lambda.span()));
        }
        return function(lambda, params);
    }

    private Result function(Expr.LambdaExpr lambda, List<ClientType> paramTypes) {
        try {
            ClientScope inner = scope;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < lambda.params().size(); i++) {
                String p = lambda.params().get(i);
                if (scope.lookup(p).isPresent()) {
                    throw unsupported(lambda.span(), "o parâmetro '" + p + "' tem o nome de um state, derived, parâmetro"
                        + " ou variável do componente; use outro nome");
                }
                inner = inner.with(p, ClientScope.Kind.LOCAL, paramTypes.get(i));
                names.add(JsText.identifier(p));
            }
            StringBuilder body = new StringBuilder();
            for (Expr statement : lambda.body()) {
                body.append(statement(statement, inner));
            }
            return new Ok("(" + String.join(", ", names) + ") => {" + body + " }", new ClientType.Callback(paramTypes));
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    private String statement(Expr st, ClientScope s) {
        return switch (st) {
            case Expr.AssignExpr a -> assign(a, s);
            case Expr.UpdateExpr u -> update(u, s);
            case Expr.CallExpr c -> callStatement(c, s);
            default -> throw unsupported(st.span(), "não é uma instrução de um handler (atribuição a um state, ++/--/+=,"
                + " mutação de uma List em state, chamada de um callback ou e.preventDefault())");
        };
    }

    private String assign(Expr.AssignExpr a, ClientScope s) {
        String name = stateTarget(a.target(), s);
        ClientType type = s.lookup(name).orElseThrow().type();
        Typed v = value(expr(a.value(), s), a.value().span());
        String js;
        if (a.operator().equals("=")) {
            if (!ClientTypes.assignable(v.type(), type)) {
                throw unsupported(a.value().span(), v.type().java() + " não é atribuível a '" + name + "' (" + type.java() + ")");
            }
            js = coerce(v, type);
        } else if (type == STRING) {
            js = "(\"\" + $s." + name + " + " + concatOperand(v, a.value().span()) + ")";
        } else {
            Typed current = number(new Typed("$s." + name, type), a.target().span());
            Typed add = number(v, a.value().span());
            if (current.type() == INT && add.type() == DOUBLE) {
                throw unsupported(a.span(), "'" + name + " += double' estreita o resultado para int em Java; o cliente não"
                    + " o faz — use um state double");
            }
            js = current.type() == INT
                ? "((" + current.js() + " + " + add.js() + ") | 0)"
                : "(" + current.js() + " + " + add.js() + ")";
        }
        return " $s." + name + " = " + js + "; $u(" + JsText.string(name) + ");";
    }

    private String update(Expr.UpdateExpr u, ClientScope s) {
        String name = stateTarget(u.target(), s);
        Typed current = number(new Typed("$s." + name, s.lookup(name).orElseThrow().type()), u.target().span());
        String op = u.operator().equals("++") ? " + 1" : " - 1";
        String js = current.type() == INT ? "((" + current.js() + op + ") | 0)" : "(" + current.js() + op + ")";
        return " $s." + name + " = " + js + "; $u(" + JsText.string(name) + ");";
    }

    private static String stateTarget(Expr target, ClientScope s) {
        if (!(target instanceof Expr.PrimaryExpr p) || !io.suko.lang.ast.ExprNames.isIdentifier(p.text())) {
            throw unsupported(target.span(), "só um state pode ser alterado: escreva o nome do state (não a.b nem uma expressão)");
        }
        ClientScope.Binding b = s.lookup(p.text()).orElseThrow(() -> unsupported(target.span(),
            "'" + p.text() + "' não é um state deste componente"));
        if (b.type() == UNKNOWN) {
            throw silent(target.span());
        }
        return switch (b.kind()) {
            case STATE -> p.text();
            case DERIVED -> throw unsupported(target.span(), "'" + p.text() + "' é derived e não é atribuível");
            case PARAM -> throw unsupported(target.span(), "'" + p.text() + "' é um parâmetro; no cliente os parâmetros"
                + " são só de leitura (use um state)");
            case LOCAL -> throw unsupported(target.span(), "'" + p.text() + "' é uma variável local; só um state pode ser alterado");
        };
    }

    private String callStatement(Expr.CallExpr c, ClientScope s) {
        if (c.callee() instanceof Expr.AccessExpr a && LIST_MUTATIONS.contains(a.memberName())) {
            if (a.target() instanceof Expr.PrimaryExpr t
                    && s.lookup(t.text()).map(b -> b.type() instanceof ClientType.ListOf).orElse(false)) {
                return listMutation(t, a.memberName(), c, s);
            }
            Typed receiver = value(expr(a.target(), s), a.target().span());
            if (receiver.type() instanceof ClientType.ListOf) {
                throw unsupported(c.span(), "só uma List guardada num state pode ser alterada (a alteração não seria propagada)");
            }
        }
        return " " + expr(c, s).js() + ";";
    }

    private String listMutation(Expr.PrimaryExpr target, String m, Expr.CallExpr c, ClientScope s) {
        ClientScope.Binding b = s.lookup(target.text()).orElseThrow();
        if (b.kind() != ClientScope.Kind.STATE) {
            throw unsupported(c.span(), "só uma List guardada num state pode ser alterada (a alteração de '" + target.text()
                + "' não seria propagada)");
        }
        ClientType element = ((ClientType.ListOf) b.type()).element();
        String list = "$s." + target.text();
        List<Typed> a = args(c, s);
        String js = switch (m) {
            case "add" -> {
                if (a.size() != 1) {
                    throw unsupported(c.span(), "List.add(i, x) fica fora da v1: use add(x)");
                }
                yield list + ".push(" + element(a.get(0), element, c.args().get(0).span()) + ")";
            }
            case "remove" -> {
                arity(a, 1, c, m);
                if (a.get(0).type() != INT) {
                    throw unsupported(c.args().get(0).span(), "remove() só com um índice int (remove(Object) fica fora da v1)");
                }
                yield "$suko.removeAt(" + list + ", " + a.get(0).js() + ")";
            }
            case "set" -> {
                arity(a, 2, c, m);
                yield "$suko.setAt(" + list + ", " + index(a.get(0), c.args().get(0).span()).js() + ", "
                    + element(a.get(1), element, c.args().get(1).span()) + ")";
            }
            default -> {
                arity(a, 0, c, m);
                yield "$suko.clear(" + list + ")";
            }
        };
        return " " + js + "; $u(" + JsText.string(target.text()) + ");";
    }

    private static String element(Typed v, ClientType element, SourceSpan span) {
        if (!ClientTypes.assignable(v.type(), element)) {
            throw unsupported(span, v.type().java() + " não é atribuível a um elemento " + element.java());
        }
        return coerce(v, element);
    }

    // ------------------------------------------------------------------ apoio

    private static Typed value(Typed t, SourceSpan span) {
        if (t.type() == VOID) {
            throw unsupported(span, "esta chamada não devolve um valor");
        }
        return t;
    }

    private static Typed number(Typed t, SourceSpan span) {
        if (t.type() == INT || t.type() == DOUBLE) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && (b.of() == INT || b.of() == DOUBLE)) {
            return new Typed("$suko.nn(" + t.js() + ")", b.of());
        }
        throw unsupported(span, "esperava um número (int ou double), não " + t.type().java());
    }

    private static Typed bool(Typed t, SourceSpan span) {
        if (t.type() == BOOLEAN) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && b.of() == BOOLEAN) {
            return new Typed("$suko.nn(" + t.js() + ")", BOOLEAN);
        }
        throw unsupported(span, "esperava um boolean, não " + t.type().java());
    }

    private static Typed index(Typed t, SourceSpan span) {
        if (t.type() == INT) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && b.of() == INT) {
            return new Typed("$suko.nn(" + t.js() + ")", INT);
        }
        throw unsupported(span, "um índice tem de ser int, não " + t.type().java());
    }

    private static Typed unbox(Typed t) {
        return t.type() instanceof ClientType.Boxed b ? new Typed("$suko.nn(" + t.js() + ")", b.of()) : t;
    }

    /** O JS de {@code v} para um destino do tipo {@code to}: um Boxed num primitivo passa por {@code nn} (NPE de Java). */
    private static String coerce(Typed v, ClientType to) {
        return to instanceof ClientType.Scalar && to != STRING && v.type() instanceof ClientType.Boxed
            ? "$suko.nn(" + v.js() + ")"
            : v.js();
    }

    /** Um operando de concatenação de Java: null → "null"; um double com o algoritmo do Double.toString. */
    private static String concatOperand(Typed t, SourceSpan span) {
        ClientType type = t.type();
        if (type == DOUBLE || (type instanceof ClientType.Boxed b && b.of() == DOUBLE)) {
            return "$suko.dcat(" + t.js() + ")";
        }
        if (type == STRING || type == INT || type == BOOLEAN || type == NULL || type instanceof ClientType.Boxed) {
            return t.js();
        }
        throw unsupported(span, "não há conversão para texto de " + type.java() + " no cliente (o toString de Java não é reproduzido)");
    }

    private static Stop unsupported(SourceSpan span, String message) {
        return new Stop(new ClientProblem(CLIENT_UNSUPPORTED, message, span));
    }

    /** Consequência de um problema já reportado noutro sítio: não gera diagnóstico. */
    private static Stop silent(SourceSpan span) {
        return new Stop(new ClientProblem(null, "consequência de um problema anterior", span));
    }
}
