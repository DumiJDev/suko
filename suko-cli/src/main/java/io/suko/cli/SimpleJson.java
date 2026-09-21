package io.suko.cli;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tiny, purpose-built JSON reader/writer for {@code suko.json}.
 * <p>
 * {@code suko-cli} deliberately depends only on {@code suko-registry} with
 * {@code implementation} (not {@code api}) visibility, which keeps Gson
 * (used internally by {@link io.suko.registry.RegistryJson}) off this
 * module's compile classpath — see {@code suko-cli/build.gradle.kts}. Since
 * {@code suko.json}'s shape is fixed and small (a handful of string fields
 * plus one nested object, D6), a hand-rolled parser here is a better trade
 * than adding a Gson dependency of our own, which the subprojeto 8 plan's
 * Global Constraints rule out ("Dependências novas: nenhuma").
 * </p>
 * <p>
 * This is <strong>not</strong> a general-purpose JSON library: it supports
 * exactly what {@code suko.json} needs — objects, strings (with the common
 * escapes), and numbers — and nothing else (no arrays, no booleans, no
 * {@code null}). If a future schema version needs more, reconsider this
 * decision rather than growing this class into a second JSON library.
 * </p>
 */
final class SimpleJson {

    private SimpleJson() {
    }

    static final class JsonSyntaxException extends RuntimeException {
        JsonSyntaxException(String message) {
            super(message);
        }
    }

    /** Parses a JSON object into a {@link Map} (String/Number/Map values). */
    static Object parse(String json) {
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new JsonSyntaxException("Unexpected trailing content at position " + parser.pos);
        }
        return value;
    }

    /** Renders {@code value} as a double-quoted JSON string literal, with common escapes. */
    static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException("cannot quote a null value as a JSON string");
        }
        StringBuilder sb = new StringBuilder();
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private static final class Parser {
        private final String json;
        private int pos;

        Parser(String json) {
            this.json = json;
            this.pos = 0;
        }

        boolean atEnd() {
            return pos >= json.length();
        }

        void skipWhitespace() {
            while (pos < json.length() && Character.isWhitespace(json.charAt(pos))) {
                pos++;
            }
        }

        Object parseValue() {
            if (atEnd()) {
                throw new JsonSyntaxException("Unexpected end of input at position " + pos);
            }
            char c = json.charAt(pos);
            if (c == '{') {
                return parseObject();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == '-' || Character.isDigit(c)) {
                return parseNumber();
            }
            throw new JsonSyntaxException("Unexpected character '" + c + "' at position " + pos);
        }

        Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> result = new LinkedHashMap<>();
            skipWhitespace();
            if (peekIs('}')) {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (!peekIs('"')) {
                    throw new JsonSyntaxException("Expected a string key at position " + pos);
                }
                String key = parseString();
                skipWhitespace();
                expect(':');
                skipWhitespace();
                Object value = parseValue();
                result.put(key, value);
                skipWhitespace();
                if (peekIs(',')) {
                    pos++;
                    continue;
                }
                if (peekIs('}')) {
                    pos++;
                    break;
                }
                throw new JsonSyntaxException("Expected ',' or '}' at position " + pos);
            }
            return result;
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new JsonSyntaxException("Unterminated string starting before position " + pos);
                }
                char c = json.charAt(pos++);
                if (c == '"') {
                    break;
                }
                if (c == '\\') {
                    if (atEnd()) {
                        throw new JsonSyntaxException("Unterminated escape sequence at position " + pos);
                    }
                    char escaped = json.charAt(pos++);
                    switch (escaped) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'u' -> {
                            if (pos + 4 > json.length()) {
                                throw new JsonSyntaxException("Truncated unicode escape at position " + pos);
                            }
                            String hex = json.substring(pos, pos + 4);
                            pos += 4;
                            sb.append((char) Integer.parseInt(hex, 16));
                        }
                        default -> throw new JsonSyntaxException("Unknown escape '\\" + escaped + "' at position " + pos);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        Number parseNumber() {
            int start = pos;
            if (peekIs('-')) {
                pos++;
            }
            while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
                pos++;
            }
            boolean isDouble = false;
            if (pos < json.length() && json.charAt(pos) == '.') {
                isDouble = true;
                pos++;
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
                    pos++;
                }
            }
            if (pos < json.length() && (json.charAt(pos) == 'e' || json.charAt(pos) == 'E')) {
                isDouble = true;
                pos++;
                if (pos < json.length() && (json.charAt(pos) == '+' || json.charAt(pos) == '-')) {
                    pos++;
                }
                while (pos < json.length() && Character.isDigit(json.charAt(pos))) {
                    pos++;
                }
            }
            String text = json.substring(start, pos);
            if (text.isEmpty() || "-".equals(text)) {
                throw new JsonSyntaxException("Invalid number at position " + start);
            }
            return isDouble ? Double.parseDouble(text) : Long.parseLong(text);
        }

        boolean peekIs(char c) {
            return !atEnd() && json.charAt(pos) == c;
        }

        void expect(char c) {
            if (!peekIs(c)) {
                throw new JsonSyntaxException("Expected '" + c + "' at position " + pos);
            }
            pos++;
        }
    }
}
