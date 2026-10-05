package io.suko.ext;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Configuração de segurança do projeto (subprojeto 14), lida do build
 * (Gradle {@code suko { security { ... } }}, Maven {@code <security>}) e
 * entregue às extensões nos contextos de emissão e de verificação.
 * Os conjuntos são ordenados para o código gerado ser determinístico.
 */
public record SecurityOptions(String generatedPackage, Set<String> urlSchemes, Set<String> imageDataTypes,
                              boolean strictCsp, Set<String> codeAttributes, Set<String> urlAttributes) {

    public static final Set<String> DEFAULT_URL_SCHEMES = sorted(Set.of("http", "https", "mailto", "tel"));
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "vbscript", "data", "blob", "filesystem");
    private static final Set<String> RASTER_IMAGE_TYPES = Set.of("png", "gif", "jpeg", "webp", "avif");
    private static final Pattern SCHEME = Pattern.compile("[a-z][a-z0-9+.-]*");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const", "continue",
        "default", "do", "double", "else", "enum", "extends", "final", "finally", "float", "for", "goto", "if",
        "implements", "import", "instanceof", "int", "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
        "throw", "throws", "transient", "try", "void", "volatile", "while", "true", "false", "null");

    public static final SecurityOptions DEFAULT = new SecurityOptions(
        "io.suko.generated", DEFAULT_URL_SCHEMES, Set.of(), false, Set.of(), Set.of());

    public SecurityOptions {
        if (generatedPackage == null || !PACKAGE.matcher(generatedPackage).matches()) {
            throw new IllegalArgumentException("generatedPackage inválido (esperado um package Java): " + generatedPackage);
        }
        for (String segment : generatedPackage.split("\\.")) {
            if (JAVA_KEYWORDS.contains(segment)) {
                throw new IllegalArgumentException("generatedPackage: '" + segment
                    + "' é uma palavra reservada do Java e não pode ser um segmento de package: " + generatedPackage);
            }
        }
        urlSchemes = sorted(lower(urlSchemes));
        if (urlSchemes.isEmpty()) {
            throw new IllegalArgumentException("urlSchemes não pode ser vazio");
        }
        for (String scheme : urlSchemes) {
            if (!SCHEME.matcher(scheme).matches()) {
                throw new IllegalArgumentException("urlSchemes: esquema inválido (esperado [a-z][a-z0-9+.-]*): '" + scheme + "'");
            }
            if (FORBIDDEN_SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("O esquema '" + scheme
                    + "' nunca pode ser permitido em urlSchemes (javascript, vbscript, data, blob, filesystem)");
            }
        }
        imageDataTypes = sorted(lower(imageDataTypes));
        for (String type : imageDataTypes) {
            if (!RASTER_IMAGE_TYPES.contains(type)) {
                throw new IllegalArgumentException("imageDataTypes só aceita " + new TreeSet<>(RASTER_IMAGE_TYPES)
                    + ", recebeu '" + type + "'");
            }
        }
        codeAttributes = sorted(lower(codeAttributes));
        urlAttributes = sorted(lower(urlAttributes));
    }

    public SecurityOptions withGeneratedPackage(String value) {
        return new SecurityOptions(value, urlSchemes, imageDataTypes, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withUrlSchemes(Set<String> value) {
        return new SecurityOptions(generatedPackage, value, imageDataTypes, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withImageDataTypes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, value, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withStrictCsp(boolean value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, value, codeAttributes, urlAttributes);
    }

    public SecurityOptions withCodeAttributes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, strictCsp, value, urlAttributes);
    }

    public SecurityOptions withUrlAttributes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, strictCsp, codeAttributes, value);
    }

    /** Troca {@code [^A-Za-z0-9_]} por {@code _} e prefixa {@code _} se começar por dígito, ficar vazio ou for uma palavra reservada do Java. */
    public static String sanitizePackageSegment(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "_" + s;
        }
        if (JAVA_KEYWORDS.contains(s)) {
            s = "_" + s;
        }
        return s;
    }

    private static Set<String> lower(Set<String> in) {
        TreeSet<String> out = new TreeSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Set<String> sorted(Set<String> in) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(in));
    }
}
