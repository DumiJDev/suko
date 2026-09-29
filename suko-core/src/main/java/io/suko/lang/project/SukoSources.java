package io.suko.lang.project;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Snapshot imutável dos fontes {@code .sk} de um source root: caminho
 * relativo ao root → texto. É a entrada única do índice e do compilador de
 * projeto, para que o mesmo código sirva o build (lê do disco) e o language
 * server (sobrepõe os buffers abertos ao disco, sem os gravar).
 *
 * <p>{@link #root()} é o caminho real do source root e serve só para
 * resolver caminhos absolutos ({@link #absolute(Path)}) — o índice expõe
 * {@code root.resolve(relativo)}, exatamente como quando lia do disco.
 * Iteração por ordem lexicográfica do caminho, para resultados
 * determinísticos independentes do sistema de ficheiros.
 */
public final class SukoSources {

    private final Path root;
    private final Map<Path, String> files;

    private SukoSources(Path root, Map<Path, String> files) {
        this.root = root;
        this.files = Collections.unmodifiableMap(files);
    }

    /** Lê todos os {@code .sk} de {@code root}, recursivamente. */
    public static SukoSources fromDirectory(Path root) {
        Map<Path, String> files = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)
                    .filter(f -> f.toString().endsWith(".sk"))::iterator) {
                files.put(root.relativize(p), Files.readString(p));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new SukoSources(root, files);
    }

    /** Snapshot sem disco (testes e servers): {@code files} usa caminhos relativos ao root. */
    public static SukoSources of(Path root, Map<Path, String> files) {
        return new SukoSources(root, new TreeMap<>(requireRelative(files)));
    }

    /** Novo snapshot com estes documentos a substituir (ou acrescentar a) os existentes. */
    public SukoSources withOverlay(Map<Path, String> overlay) {
        Map<Path, String> merged = new TreeMap<>(files);
        merged.putAll(requireRelative(overlay));
        return new SukoSources(root, merged);
    }

    /** Novo snapshot sem o ficheiro dado (ficheiro apagado no editor/disco). */
    public SukoSources without(Path relative) {
        Map<Path, String> copy = new LinkedHashMap<>(files);
        copy.remove(relative);
        return new SukoSources(root, new TreeMap<>(copy));
    }

    /** Um caminho absoluto ou não normalizado criaria um ficheiro fantasma no snapshot
     * (e um PACKAGE_DIRECTORY_MISMATCH falso) — fácil de acontecer ao converter URIs. */
    private static Map<Path, String> requireRelative(Map<Path, String> files) {
        for (Path p : files.keySet()) {
            if (p.isAbsolute() || !p.normalize().equals(p)) {
                throw new IllegalArgumentException(
                    "caminho tem de ser relativo ao source root e normalizado: " + p);
            }
        }
        return files;
    }

    public Path root() {
        return root;
    }

    /** Caminhos relativos → texto, imutável, por ordem lexicográfica. */
    public Map<Path, String> files() {
        return files;
    }

    public Path absolute(Path relative) {
        return root.resolve(relative);
    }
}
