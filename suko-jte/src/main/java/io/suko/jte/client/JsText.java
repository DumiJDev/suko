package io.suko.jte.client;

import java.util.Locale;
import java.util.Set;

/** Texto JS seguro: literais de string só em ASCII e nomes de variáveis que não colidem com JS. */
public final class JsText {

    /** Palavras reservadas de JS e globais que o código gerado ou o browser usam (spec 13b, "Tradução Java → JS"). */
    private static final Set<String> RESERVED = Set.of(
        "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
        "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import",
        "in", "instanceof", "interface", "let", "new", "null", "package", "private", "protected", "public",
        "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
        "with", "yield", "async", "of", "get", "set",
        "arguments", "eval", "undefined", "NaN", "Infinity", "globalThis", "window", "self", "document", "top",
        "parent", "frames", "location", "name", "Math", "Object", "Array", "String", "Number", "Boolean", "JSON",
        "Map", "Symbol", "Function", "Reflect", "Proxy", "Promise", "console");

    private JsText() {
    }

    /**
     * Literal de string JS com todo o carácter fora do ASCII imprimível (e {@code < > & '}) escrito como
     * {@code \\uXXXX}: o ficheiro fica ASCII, um surrogate isolado sobrevive e o texto nunca fecha um
     * {@code <script>} nem forma {@code <!--}.
     */
    public static String string(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e || c == '<' || c == '>' || c == '&' || c == '\'') {
                        sb.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Nome de uma variável local no JS gerado: {@code $_nome} se colide com JS (um identificador Suko nunca tem {@code $}). */
    public static String identifier(String name) {
        return RESERVED.contains(name) ? "$_" + name : name;
    }

    /**
     * O valor real de um literal Suko ({@code StringPart.Literal.javaEscapedText()}): os escapes de Java que o
     * construtor do AST deixa passar ({@code \\n}, {@code \\"}, {@code \\u0024} vindo de {@code \\$}, ...).
     *
     * @throws IllegalArgumentException escape que o cliente não suporta
     */
    public static String unescapeJava(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                throw new IllegalArgumentException("'\\' no fim do literal");
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 's' -> sb.append(' ');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        throw new IllegalArgumentException("escape \\u incompleto");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("o escape '\\" + n + "' não é suportado no cliente");
            }
        }
        return sb.toString();
    }
}
