package io.suko.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.suko.lang.ext.ExtensionFailures;
import io.suko.lang.ext.ExtensionRegistry;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Extensões de um projeto no editor. Lê o manifesto que o build escreveu e só
 * carrega código de terceiros num workspace confiável (spec do 13a).
 *
 * <p>Segurança: com {@code trusted == false} o manifesto nem sequer é aberto —
 * só se verifica que existe — e nenhum jar externo é tocado. Com confiança, cada
 * entrada do classpath tem de ser um caminho absoluto, para um ficheiro regular
 * terminado em {@code .jar}; o resto é ignorado com aviso (nunca diretórios,
 * URLs, nem caminhos relativos).
 */
final class ProjectExtensions {

    /** {@code closer} fecha o classloader das extensões quando o projeto é recarregado/descartado. */
    record Loaded(ExtensionRegistry registry, List<String> targets, Optional<String> notice,
                  AutoCloseable closer) {

        Loaded(ExtensionRegistry registry, List<String> targets, Optional<String> notice) {
            this(registry, targets, notice, () -> {
            });
        }

        void close() {
            try {
                closer.close();
            } catch (Exception e) {
                Requests.log("fecho do classloader de extensões", e);
            }
        }
    }

    /** Manifesto maior do que isto é tratado como inválido (evita OOM com um ficheiro gigante). */
    static final long MAX_MANIFEST_BYTES = 1024 * 1024;
    private static final int MAX_REJECTED_SHOWN = 10;
    private static final int MAX_ENTRY_CHARS = 200;

    private ProjectExtensions() {
    }

    /**
     * Identifica o estado de que depende o resultado de {@link #forProject}: manifesto (caminho,
     * mtime, tamanho) e confiança. Se não mudou, não é preciso recarregar.
     */
    static String fingerprint(Path sourceRoot, Path workspaceFolder, boolean trusted) {
        try {
            Optional<Path> manifest = findManifest(sourceRoot, workspaceFolder);
            if (manifest.isEmpty()) {
                return "none|" + trusted;
            }
            return manifest.get() + "|" + Files.getLastModifiedTime(manifest.get()).toMillis() + "|"
                + Files.size(manifest.get()) + "|" + trusted;
        } catch (IOException | RuntimeException e) {
            return "unreadable|" + trusted + "|" + System.nanoTime();
        }
    }

    static Loaded builtIn(Optional<String> notice) {
        return new Loaded(ExtensionRegistry.defaults(), List.of("jte"), notice);
    }

    static Loaded forProject(Path sourceRoot, Path workspaceFolder, boolean trusted) {
        Optional<Path> manifest = findManifest(sourceRoot, workspaceFolder);
        if (manifest.isEmpty()) {
            return builtIn(Optional.empty());
        }
        if (!trusted) {
            return builtIn(Optional.of(
                "Suko: extensões do projeto ignoradas porque o workspace não é confiável (" + manifest.get() + ")"));
        }
        URLClassLoader loader = null;
        try {
            if (Files.size(manifest.get()) > MAX_MANIFEST_BYTES) {
                return builtIn(Optional.of("Suko: " + manifest.get() + " ignorado: maior do que "
                    + MAX_MANIFEST_BYTES + " bytes"));
            }
            JsonObject json = JsonParser.parseString(Files.readString(manifest.get(), StandardCharsets.UTF_8))
                .getAsJsonObject();
            List<URL> urls = new ArrayList<>();
            List<String> rejected = new ArrayList<>();
            if (json.get("classpath") != null && json.get("classpath").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("classpath")) {
                    Optional<URL> url = validJar(e);
                    if (url.isPresent()) {
                        urls.add(url.get());
                    } else {
                        rejected.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
                    }
                }
            }
            List<String> targets = new ArrayList<>();
            if (json.get("targets") != null && json.get("targets").isJsonArray()) {
                for (JsonElement e : json.getAsJsonArray("targets")) {
                    if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
                        targets.add(e.getAsString());
                    }
                }
            }
            loader = new URLClassLoader(urls.toArray(new URL[0]), ProjectExtensions.class.getClassLoader());
            Optional<String> notice = rejected.isEmpty() ? Optional.empty() : Optional.of(
                "Suko: entradas inválidas ignoradas em " + manifest.get() + ": " + summarize(rejected));
            return new Loaded(ExtensionRegistry.load(loader), targets.isEmpty() ? List.of("jte") : targets,
                notice, loader);
        } catch (Throwable e) {
            ExtensionFailures.rethrowFatal(e);
            if (loader != null) {
                try {
                    loader.close();
                } catch (IOException ignored) {
                    // nada a fazer
                }
            }
            return builtIn(Optional.of(
                "Suko: não foi possível ler " + manifest.get() + ": " + e));
        }
    }

    /** No máximo 10 entradas de 200 caracteres: um manifesto malicioso não enche o log. */
    static String summarize(List<String> rejected) {
        List<String> shown = new ArrayList<>();
        for (String s : rejected.subList(0, Math.min(rejected.size(), MAX_REJECTED_SHOWN))) {
            shown.add(s.length() > MAX_ENTRY_CHARS ? s.substring(0, MAX_ENTRY_CHARS) + "..." : s);
        }
        String text = String.join(", ", shown);
        return rejected.size() > MAX_REJECTED_SHOWN
            ? text + " (e mais " + (rejected.size() - MAX_REJECTED_SHOWN) + ")" : text;
    }

    private static Optional<URL> validJar(JsonElement element) {
        try {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            String text = element.getAsString();
            if (text.startsWith("\\\\") || text.startsWith("//")) {
                return Optional.empty(); // UNC: nunca tocar na rede (fuga de NTLM no Windows)
            }
            Path path = Path.of(text);
            if (path.getRoot() != null && path.getRoot().toString().startsWith("\\\\")) {
                return Optional.empty();
            }
            if (!path.isAbsolute() || !path.getFileName().toString().endsWith(".jar")) {
                return Optional.empty();
            }
            Path real = path.toRealPath();
            if (!Files.isRegularFile(real)) {
                return Optional.empty();
            }
            return Optional.of(real.toUri().toURL());
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static Optional<Path> findManifest(Path sourceRoot, Path workspaceFolder) {
        Path stop = workspaceFolder.toAbsolutePath().normalize();
        Path start = sourceRoot.toAbsolutePath().normalize();
        if (!start.startsWith(stop)) {
            start = stop;
        }
        for (Path dir = start; dir != null; dir = dir.getParent()) {
            for (String candidate : List.of("build/suko/extensions.json", "target/suko/extensions.json")) {
                Path file = dir.resolve(candidate);
                if (Files.isRegularFile(file)) {
                    return Optional.of(file);
                }
            }
            if (dir.equals(stop)) {
                break;
            }
        }
        return Optional.empty();
    }
}
