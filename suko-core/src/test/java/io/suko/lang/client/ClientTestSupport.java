package io.suko.lang.client;

import io.suko.jte.client.ClientPrelude;
import io.suko.jte.client.ClientScope;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.ClientTypes;
import io.suko.jte.client.JavaTypes;
import io.suko.jte.client.JsText;
import io.suko.jte.client.JsTranslator;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Apoio dos testes do tradutor (13b): parse de expressões e lambdas Suko pelo parser real, âmbitos com tipos, e
 * o harness diferencial — cada caso é escrito uma vez (o texto é Suko e Java ao mesmo tempo), corre compilado
 * pelo javac e traduzido no Node, e as duas codificações têm de ser iguais. O Java é o oráculo.
 */
final class ClientTestSupport {

    private ClientTestSupport() {
    }

    /** O record dos testes, com a mesma forma no Java gerado ({@code SukoDiff.Todo}) e no tradutor. */
    static final JavaTypes TYPES = name -> name.equals("Todo")
        ? Optional.of(List.of(new JavaTypes.Field("title", ClientTypes.parse("String")),
            new JavaTypes.Field("done", ClientTypes.parse("boolean"))))
        : Optional.empty();

    /** Uma variável de um caso: nome, tipo (Suko = Java), o que é no cliente e o inicializador em Java. */
    record Var(String name, String type, ClientScope.Kind kind, String javaInit) {
    }

    static Var state(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.STATE, javaInit);
    }

    static Var derived(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.DERIVED, javaInit);
    }

    static Var param(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.PARAM, javaInit);
    }

    static Var local(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.LOCAL, javaInit);
    }

    static ClientScope scope(List<Var> vars) {
        ClientScope s = ClientScope.EMPTY;
        for (Var v : vars) {
            s = s.with(v.name(), v.kind(), ClientTypes.of(ClientTypes.parse(v.type()), TYPES));
        }
        return s;
    }

    static ComponentDecl parse(String sk) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(sk))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(sk);
        SukoFile file = builder.build(parser.compilationUnit());
        if (parser.getNumberOfSyntaxErrors() > 0 || !builder.syntaxIssues().isEmpty()) {
            throw new IllegalArgumentException("não faz parse: " + sk + " " + builder.syntaxIssues());
        }
        return file.components().get(0);
    }

    /** Uma expressão Suko, pelo parser real. */
    static Expr expr(String source) {
        return ((Statement.Interpolation) parse("component T() { ${" + source + "} }").body().get(0)).expr();
    }

    /** Uma lambda Suko, pelo parser real (como valor de on:click). */
    static Expr.LambdaExpr lambda(String source) {
        Statement.HtmlElement b = (Statement.HtmlElement) parse("reactive component T() { <b on:click=${" + source + "}></b> }")
            .body().get(0);
        return (Expr.LambdaExpr) b.directives().get(0).value();
    }

    // ------------------------------------------------------------------ harness diferencial

    /** Um caso: as variáveis e o texto de uma expressão, ou as instruções de um handler (sem chavetas). */
    record Case(List<Var> vars, String source, boolean handler) {
    }

    static Case expression(String source, List<Var> vars) {
        return new Case(vars, source, false);
    }

    static Case handler(String statements, List<Var> vars) {
        return new Case(vars, statements, true);
    }

    /** Uma linha por caso: o que o Java deu e o que o JS deu (variáveis no fim e o resultado, codificados). */
    record Outcome(Case c, String java, String js) {
    }

    static final String JS_HELPERS = """
        class $Thrown { constructor(n) { this.n = n; } }
        function $run(f) {
          try { return f(); }
          catch (e) { return new $Thrown(e instanceof TypeError ? "TypeError" : e instanceof RangeError ? "RangeError" : "Error:" + e); }
        }
        function $dbl(h) { const v = new DataView(new ArrayBuffer(8)); v.setBigUint64(0, BigInt("0x" + h)); return v.getFloat64(0); }
        function $enc(v, t) {
          if (v instanceof $Thrown) return "throw:" + v.n;
          if (v === null || v === undefined) return "null";
          switch (t[0]) {
            case "i": case "b": return String(v);
            case "d": { if (Number.isNaN(v)) return "NaN"; const x = new DataView(new ArrayBuffer(8)); x.setFloat64(0, v); return x.getBigUint64(0).toString(16); }
            case "s": return "s:" + Array.from({length: v.length}, (_, i) => v.charCodeAt(i).toString(16)).join(".");
            case "l": return "[" + v.map(x => $enc(x, t.slice(1))).join(",") + "]";
            case "m": return "{" + [...v.keys()].sort().map(k => $enc(k, "s") + "=" + $enc(v.get(k), t.slice(1))).join(",") + "}";
            case "r": return "R";
          }
          return "?" + t;
        }
        """;

    static List<Outcome> run(List<Case> cases) throws Exception {
        StringBuilder java = new StringBuilder("import java.util.*;\npublic class SukoDiff {\n")
            .append("  public record Todo(String title, boolean done) {}\n");
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            StringBuilder decls = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (Var v : c.vars()) {
                decls.append("    ").append(v.type()).append(' ').append(v.name()).append(" = ").append(v.javaInit()).append(";\n");
                names.append(v.name()).append(", ");
            }
            java.append("  public static Object[] i").append(i).append("() {\n").append(decls)
                .append("    return new Object[] {").append(names).append("};\n  }\n");
            java.append("  public static Object[] c").append(i).append("() {\n").append(decls)
                .append("    Object $r = null;\n    try {\n      ")
                .append(c.handler() ? c.source() : "$r = (" + c.source() + ");")
                .append("\n    } catch (RuntimeException $x) {\n      $r = new Object[] {\"throw\", $x.getClass().getName()};\n    }\n")
                .append("    return new Object[] {").append(names).append("$r};\n  }\n");
        }
        String source = java.append("}\n").toString();
        Path out = Files.createTempDirectory("suko-diff");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///SukoDiff.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        StringWriter errors = new StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            if (!javac.getTask(errors, fm, null, List.of("-d", out.toString(), "--release", "21", "-nowarn"), null, List.of(file)).call()) {
                throw new AssertionError("o Java dos casos não compila:\n" + errors + "\n" + source);
            }
        }

        List<String> javaLines = new ArrayList<>();
        StringBuilder js = new StringBuilder("import * as $suko from \"" + NodeRunner.dataUrl(ClientPrelude.source()) + "\";\n")
            .append(JS_HELPERS);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()})) {
            Class<?> type = loader.loadClass("SukoDiff");
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                ClientScope scope = scope(c.vars());
                Object[] initial = (Object[]) type.getMethod("i" + i).invoke(null);
                Object[] fin = (Object[]) type.getMethod("c" + i).invoke(null);

                JsTranslator translator = new JsTranslator(scope, TYPES);
                JsTranslator.Result result = c.handler()
                    ? translator.eventHandler(lambda("() -> { " + c.source() + " }"))
                    : translator.expression(expr(c.source()));
                if (!(result instanceof JsTranslator.Ok ok)) {
                    throw new AssertionError("não traduz: " + c.source() + " -> " + ((JsTranslator.Err) result).problem());
                }
                ClientType resultType = c.handler() ? ClientType.Scalar.STRING : ok.type();

                List<String> javaEnc = new ArrayList<>();
                List<String> jsEnc = new ArrayList<>();
                js.append("{\n  const $s = Object.create(null);\n  const $p = Object.create(null);\n  const $u = (n) => {};\n");
                for (int k = 0; k < c.vars().size(); k++) {
                    Var v = c.vars().get(k);
                    ClientType t = scope.lookup(v.name()).orElseThrow().type();
                    String access = JsTranslator.access(v.name(), v.kind());
                    js.append(v.kind() == ClientScope.Kind.LOCAL ? "  const " + access : "  " + access)
                        .append(" = ").append(literal(initial[k], t)).append(";\n");
                    javaEnc.add(encode(fin[k], t));
                    jsEnc.add("$enc(" + access + ", \"" + tag(t) + "\")");
                }
                js.append(c.handler()
                    ? "  const $r = $run(() => { (" + ok.js() + ")(); return null; });\n"
                    : "  const $r = $run(() => " + ok.js() + ");\n");
                javaEnc.add(encode(fin[c.vars().size()], resultType));
                jsEnc.add("$enc($r, \"" + tag(resultType) + "\")");
                js.append("  console.log([").append(String.join(", ", jsEnc)).append("].join(\"\\t\"));\n}\n");
                javaLines.add(String.join("\t", javaEnc));
            }
        }
        List<String> jsLines = NodeRunner.run(js.toString()).lines().toList();
        if (jsLines.size() != cases.size()) {
            throw new AssertionError("o Node devolveu " + jsLines.size() + " linhas para " + cases.size() + " casos:\n" + jsLines);
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            outcomes.add(new Outcome(cases.get(i), javaLines.get(i), jsLines.get(i)));
        }
        return outcomes;
    }

    static String tag(ClientType t) {
        return switch (t) {
            case ClientType.Scalar s -> switch (s) {
                case INT -> "i";
                case DOUBLE -> "d";
                case BOOLEAN -> "b";
                case STRING -> "s";
            };
            case ClientType.Boxed b -> tag(b.of());
            case ClientType.ListOf l -> "l" + tag(l.element());
            case ClientType.MapOf m -> "m" + tag(m.value());
            default -> "r";
        };
    }

    /** A mesma codificação que {@code $enc} faz em JS. */
    static String encode(Object v, ClientType t) throws Exception {
        if (v instanceof Object[] a && a.length == 2 && "throw".equals(a[0])) {
            return "throw:" + jsError((String) a[1]);
        }
        if (v == null) {
            return "null";
        }
        String tag = tag(t);
        return switch (tag.charAt(0)) {
            case 'i', 'b' -> v.toString();
            case 'd' -> Double.isNaN((Double) v) ? "NaN" : Long.toHexString(Double.doubleToRawLongBits((Double) v));
            case 's' -> "s:" + ((String) v).chars().mapToObj(Integer::toHexString).collect(Collectors.joining("."));
            case 'l' -> {
                ClientType element = ((ClientType.ListOf) t).element();
                List<String> parts = new ArrayList<>();
                for (Object x : (List<?>) v) {
                    parts.add(encode(x, element));
                }
                yield "[" + String.join(",", parts) + "]";
            }
            case 'm' -> {
                ClientType value = ((ClientType.MapOf) t).value();
                List<String> parts = new ArrayList<>();
                for (Map.Entry<?, ?> e : new TreeMap<>((Map<?, ?>) v).entrySet()) {
                    parts.add(encode(e.getKey(), ClientType.Scalar.STRING) + "=" + encode(e.getValue(), value));
                }
                yield "{" + String.join(",", parts) + "}";
            }
            default -> "R";
        };
    }

    /** A exceção de Java e o erro de JS que a spec faz corresponder (NPE → TypeError; aritmética e índices → RangeError). */
    static String jsError(String javaClass) throws Exception {
        Class<?> type = Class.forName(javaClass);
        if (NullPointerException.class.isAssignableFrom(type)) {
            return "TypeError";
        }
        if (ArithmeticException.class.isAssignableFrom(type) || IndexOutOfBoundsException.class.isAssignableFrom(type)) {
            return "RangeError";
        }
        return "Error:" + type.getSimpleName();
    }

    /** O valor inicial de Java como literal JS (o mesmo valor nos dois lados). */
    static String literal(Object v, ClientType t) throws Exception {
        if (v == null) {
            return "null";
        }
        return switch (t) {
            case ClientType.Scalar s when s == ClientType.Scalar.DOUBLE -> dbl((Double) v);
            case ClientType.Scalar s when s == ClientType.Scalar.STRING -> JsText.string((String) v);
            case ClientType.Scalar s -> v.toString();
            case ClientType.Boxed b -> literal(v, b.of());
            case ClientType.ListOf l -> {
                List<String> parts = new ArrayList<>();
                for (Object x : (List<?>) v) {
                    parts.add(literal(x, l.element()));
                }
                yield "[" + String.join(", ", parts) + "]";
            }
            case ClientType.MapOf m -> {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    parts.add("[" + JsText.string((String) e.getKey()) + ", " + literal(e.getValue(), m.value()) + "]");
                }
                yield "new Map([" + String.join(", ", parts) + "])";
            }
            case ClientType.RecordOf r -> {
                List<String> names = new ArrayList<>();
                List<String> values = new ArrayList<>();
                RecordComponent[] components = v.getClass().getRecordComponents();
                for (int i = 0; i < components.length; i++) {
                    names.add(JsText.string(components[i].getName()));
                    values.add(literal(components[i].getAccessor().invoke(v), r.fields().get(i).type()));
                }
                yield "$suko.rec([" + String.join(", ", names) + "], [" + String.join(", ", values) + "])";
            }
            default -> throw new IllegalArgumentException("sem literal para " + t);
        };
    }

    private static String dbl(Double d) {
        return d.isNaN() ? "NaN" : "$dbl(\"" + Long.toHexString(Double.doubleToRawLongBits(d)) + "\")";
    }
}
