package io.suko.jte;

import io.suko.ext.SecurityOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/** Produz o fonte Java de {@code SukoSafe} para um projeto (só JDK; ver SukoSafe.java.template). */
public final class SukoSafeSource {

    private static final String TEMPLATE = load();

    private SukoSafeSource() {
    }

    public static String generate(SecurityOptions options) {
        return TEMPLATE
            .replace("@@PACKAGE@@", options.generatedPackage())
            .replace("@@URL_SCHEMES@@", literals(options.urlSchemes()))
            .replace("@@IMAGE_DATA_TYPES@@", literals(options.imageDataTypes()));
    }

    /** Ex.: {@code io/suko/generated/SukoSafe.java}. */
    public static String relativePath(SecurityOptions options) {
        return options.generatedPackage().replace('.', '/') + "/SukoSafe.java";
    }

    private static String literals(Set<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
    }

    private static String load() {
        try (InputStream in = SukoSafeSource.class.getResourceAsStream("SukoSafe.java.template")) {
            if (in == null) {
                throw new IllegalStateException("SukoSafe.java.template não encontrado no classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
