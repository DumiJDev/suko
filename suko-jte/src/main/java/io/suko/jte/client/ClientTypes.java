package io.suko.jte.client;

import io.suko.lang.ast.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.suko.jte.client.ClientType.Scalar.BOOLEAN;
import static io.suko.jte.client.ClientType.Scalar.DOUBLE;
import static io.suko.jte.client.ClientType.Scalar.INT;
import static io.suko.jte.client.ClientType.Scalar.STRING;

/** Tipos Suko/Java → {@link ClientType}, e as regras de atribuição e de tipo comum do subconjunto. */
public final class ClientTypes {

    private ClientTypes() {
    }

    public static ClientType of(Type type, JavaTypes types) {
        return of(type, types, new HashSet<>());
    }

    private static ClientType of(Type type, JavaTypes types, Set<String> visiting) {
        String text = text(type);
        if (type.arrayDimensions() > 0) {
            return new ClientType.Unsupported(text, "arrays não fazem parte do subconjunto do cliente (use List)");
        }
        List<Type> args = type.typeArguments();
        switch (type.name()) {
            case "int", "short", "byte" -> {
                return INT;
            }
            case "double" -> {
                return DOUBLE;
            }
            case "boolean" -> {
                return BOOLEAN;
            }
            case "String" -> {
                return STRING;
            }
            case "Integer", "Short", "Byte" -> {
                return new ClientType.Boxed(INT);
            }
            case "Double" -> {
                return new ClientType.Boxed(DOUBLE);
            }
            case "Boolean" -> {
                return new ClientType.Boxed(BOOLEAN);
            }
            case "long", "Long", "char", "Character" -> {
                return new ClientType.Unsupported(text,
                    "long e char ficam fora da v1 (um number de JS perde precisão acima de 2^53)");
            }
            case "float", "Float" -> {
                return new ClientType.Unsupported(text, "float fica fora do cliente (aritmética de 32 bits): use double");
            }
            case "List" -> {
                if (args.size() == 1) {
                    ClientType element = boxed(of(args.get(0), types, visiting));
                    return element instanceof ClientType.Unsupported u ? u : new ClientType.ListOf(element);
                }
            }
            case "Map" -> {
                if (args.size() == 2 && isPlainString(args.get(0))) {
                    ClientType value = boxed(of(args.get(1), types, visiting));
                    return value instanceof ClientType.Unsupported u ? u : new ClientType.MapOf(value);
                }
                return new ClientType.Unsupported(text, "no cliente só Map<String, V> é suportado");
            }
            case "Consumer" -> {
                if (args.size() == 1) {
                    ClientType param = boxed(of(args.get(0), types, visiting));
                    return param instanceof ClientType.Unsupported u ? u : new ClientType.Callback(List.of(param));
                }
            }
            case "Runnable" -> {
                if (args.isEmpty()) {
                    return new ClientType.Callback(List.of());
                }
            }
            case "Object", "Number", "Component", "Content", "Function", "Supplier", "BiConsumer", "Set",
                 "Optional", "var" -> {
                return new ClientType.Unsupported(text, "tipo fora do subconjunto do cliente");
            }
            default -> {
                if (!args.isEmpty()) {
                    return new ClientType.Unsupported(text, "tipos genéricos de Java não fazem parte do subconjunto do cliente");
                }
                Optional<List<JavaTypes.Field>> fields = types.record(type.name());
                if (fields.isEmpty()) {
                    return new ClientType.Named(type.name());
                }
                if (!visiting.add(type.name())) {
                    return new ClientType.Unsupported(text, "records recursivos não são suportados no cliente");
                }
                List<ClientType.RecordOf.Field> out = new ArrayList<>();
                for (JavaTypes.Field f : fields.get()) {
                    ClientType fieldType = of(f.type(), types, visiting);
                    if (fieldType instanceof ClientType.Unsupported u) {
                        visiting.remove(type.name());
                        return new ClientType.Unsupported(text, "o componente '" + f.name() + "' (" + u.javaType() + "): " + u.reason());
                    }
                    out.add(new ClientType.RecordOf.Field(f.name(), fieldType));
                }
                visiting.remove(type.name());
                return new ClientType.RecordOf(type.name(), List.copyOf(out));
            }
        }
        return new ClientType.Unsupported(text, "tipo fora do subconjunto do cliente");
    }

    /** Elementos de coleções e parâmetros de tipo são referências em Java: {@code List<int>} lê-se {@code List<Integer>}. */
    private static ClientType boxed(ClientType t) {
        return t instanceof ClientType.Scalar s && s != STRING ? new ClientType.Boxed(s) : t;
    }

    private static boolean isPlainString(Type t) {
        return t.name().equals("String") && t.typeArguments().isEmpty() && t.arrayDimensions() == 0;
    }

    public static String text(Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(text(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        return sb.append("[]".repeat(type.arrayDimensions())).toString();
    }

    /** O texto de um tipo como o {@code ParamInfo.type()} o guarda (ex.: {@code Map<String, List<Integer>>}). */
    public static Type parse(String text) {
        String s = text.replace(" ", "");
        int[] pos = {0};
        Type type = parse(s, pos);
        if (pos[0] != s.length()) {
            throw new IllegalArgumentException("tipo mal formado: " + text);
        }
        return type;
    }

    private static Type parse(String s, int[] pos) {
        int start = pos[0];
        while (pos[0] < s.length() && (Character.isLetterOrDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '_'
                || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        String name = s.substring(start, pos[0]);
        List<Type> args = new ArrayList<>();
        if (pos[0] < s.length() && s.charAt(pos[0]) == '<') {
            pos[0]++;
            args.add(parse(s, pos));
            while (pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                pos[0]++;
                args.add(parse(s, pos));
            }
            if (pos[0] >= s.length() || s.charAt(pos[0]) != '>') {
                throw new IllegalArgumentException("tipo mal formado: " + s);
            }
            pos[0]++;
        }
        int dims = 0;
        while (pos[0] + 1 < s.length() && s.charAt(pos[0]) == '[' && s.charAt(pos[0] + 1) == ']') {
            dims++;
            pos[0] += 2;
        }
        return new Type(name, List.copyOf(args), dims);
    }

    /** Atribuição de Java (com boxing/unboxing e alargamento int → double); as coleções são invariantes. */
    public static boolean assignable(ClientType from, ClientType to) {
        if (from.equals(to)) {
            return true;
        }
        if (from == ClientType.Special.NULL) {
            return to.nullable() && !(to instanceof ClientType.Unsupported) && to != ClientType.Special.UNKNOWN;
        }
        if (to == INT) {
            return from.equals(new ClientType.Boxed(INT));
        }
        if (to == DOUBLE) {
            return from == INT || from.equals(new ClientType.Boxed(INT)) || from.equals(new ClientType.Boxed(DOUBLE));
        }
        if (to == BOOLEAN) {
            return from.equals(new ClientType.Boxed(BOOLEAN));
        }
        if (to instanceof ClientType.Boxed b) {
            return from == b.of();
        }
        return false;
    }

    /** O tipo de {@code c ? a : b} (promoção numérica de Java), ou {@code null} se não há tipo comum no cliente. */
    public static ClientType unify(ClientType a, ClientType b) {
        if (a.equals(b)) {
            return a;
        }
        if (a == ClientType.Special.NULL) {
            return nullableOf(b);
        }
        if (b == ClientType.Special.NULL) {
            return nullableOf(a);
        }
        ClientType.Scalar pa = primitive(a);
        ClientType.Scalar pb = primitive(b);
        if (pa != null && pb != null) {
            boolean na = pa == INT || pa == DOUBLE;
            boolean nb = pb == INT || pb == DOUBLE;
            if (na && nb) {
                return pa == INT && pb == INT ? INT : DOUBLE;
            }
            if (pa == BOOLEAN && pb == BOOLEAN) {
                return BOOLEAN;
            }
        }
        return null;
    }

    /** INT/DOUBLE/BOOLEAN, também por detrás de um {@link ClientType.Boxed}; {@code null} para o resto. */
    public static ClientType.Scalar primitive(ClientType t) {
        if (t instanceof ClientType.Scalar s && s != STRING) {
            return s;
        }
        if (t instanceof ClientType.Boxed b && b.of() != STRING) {
            return b.of();
        }
        return null;
    }

    private static ClientType nullableOf(ClientType t) {
        if (t instanceof ClientType.Scalar s && s != STRING) {
            return new ClientType.Boxed(s);
        }
        return t.nullable() && !(t instanceof ClientType.Unsupported) ? t : null;
    }
}
