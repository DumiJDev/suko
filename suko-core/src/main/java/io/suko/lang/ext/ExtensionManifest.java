package io.suko.lang.ext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * O ficheiro que o build escreve (build/suko/extensions.json ou
 * target/suko/extensions.json) para o LSP saber que extensões o projeto usa.
 * Fica no diretório de build e não no suko.json: tem caminhos absolutos da
 * máquina (spec do 13a, D4).
 */
public final class ExtensionManifest {

    static final String SERVICE = "META-INF/services/io.suko.ext.SukoExtension";
    static final Set<String> BUILT_IN_PROVIDERS = Set.of("io.suko.jte.JteExtension");

    private ExtensionManifest() {
    }

    public static void write(Path file, List<Path> extensionJars, List<String> targets) {
        String json = "{\"classpath\":" + array(extensionJars.stream().map(Path::toString).toList())
            + ",\"targets\":" + array(targets) + "}";
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Jars do classloader que declaram extensões, sem o JTE embutido (o LSP já o traz). */
    public static List<Path> extensionJars(ClassLoader loader) {
        List<Path> jars = new ArrayList<>();
        try {
            for (URL url : Collections.list(loader.getResources(SERVICE))) {
                if (!"jar".equals(url.getProtocol())) {
                    continue; // diretórios de classes (testes, IDE): não vão para o manifesto
                }
                Set<String> providers = providersOf(url);
                if (BUILT_IN_PROVIDERS.containsAll(providers)) {
                    continue;
                }
                JarURLConnection connection = (JarURLConnection) url.openConnection();
                jars.add(Path.of(connection.getJarFileURL().toURI()));
            }
        } catch (IOException | java.net.URISyntaxException e) {
            throw new IllegalStateException("Não foi possível listar as extensões do classpath", e);
        }
        return jars;
    }

    private static Set<String> providersOf(URL url) throws IOException {
        try (var in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                .map(l -> l.replaceAll("#.*", "").trim()).filter(l -> !l.isEmpty())
                .collect(Collectors.toSet());
        }
    }

    private static String array(List<String> values) {
        return values.stream().map(ExtensionManifest::quote).collect(Collectors.joining(",", "[", "]"));
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
                }
            }
        }
        return b.append('"').toString();
    }
}
