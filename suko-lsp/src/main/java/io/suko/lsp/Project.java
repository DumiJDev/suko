package io.suko.lsp;

import io.suko.lang.TolerantParser;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectAnalysis;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;

import io.suko.lang.diagnostic.SukoDiagnostic;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Um source root e o seu estado: o snapshot do disco mais os buffers abertos
 * no editor por cima (overlay, nunca gravados). Sem compilação incremental
 * (mesmo compromisso do {@code sukoWatch}): cada verificação recompila o root
 * inteiro; o resultado fica em cache até algo mudar.
 */
final class Project {

    private final Path root;
    private final Map<Path, String> overlays = new LinkedHashMap<>();
    private SukoSources disk;
    private ProjectAnalysis cached;
    private SukoSources cachedSources;
    private final Map<Path, SukoFile> tolerantAsts = new LinkedHashMap<>();

    private ProjectExtensions.Loaded extensions;
    private String extensionsKey = "";

    Project(Path root) {
        this(root, ProjectExtensions.builtIn(Optional.empty()));
    }

    Project(Path root, ProjectExtensions.Loaded extensions) {
        this(root, extensions, "");
    }

    Project(Path root, ProjectExtensions.Loaded extensions, String extensionsKey) {
        this.root = root;
        this.extensions = extensions;
        this.extensionsKey = extensionsKey;
    }

    /** Troca as extensões (recarga): fecha o classloader anterior e invalida a verificação. */
    synchronized void setExtensions(ProjectExtensions.Loaded next, String key) {
        ProjectExtensions.Loaded old = extensions;
        extensions = next;
        extensionsKey = key;
        invalidate();
        old.close();
    }

    synchronized String extensionsKey() {
        return extensionsKey;
    }

    /** Liberta o classloader das extensões (projeto descartado). */
    synchronized void dispose() {
        extensions.close();
    }

    synchronized Optional<String> notice() {
        return extensions.notice();
    }

    private SukoProjectCompiler compiler() {
        return new SukoProjectCompiler(extensions.registry(), extensions.targets());
    }

    /** Problemas ao nível do projeto (extensão que falhou a carregar, alvo em falta). */
    synchronized List<SukoDiagnostic> projectDiagnostics() {
        return compiler().projectDiagnostics();
    }

    /** Um ficheiro aberto no editor (onde se mostram os diagnósticos de projeto), se houver. */
    synchronized Optional<Path> firstOpen() {
        return overlays.keySet().stream().findFirst();
    }

    Path root() {
        return root;
    }

    /** Buffer aberto ou alterado no editor ({@code relative} é relativo ao source root). */
    synchronized void put(Path relative, String text) {
        overlays.put(relative, text);
        invalidate();
    }

    /** Fecha o buffer: volta a valer o conteúdo do disco. */
    synchronized void close(Path relative) {
        if (overlays.remove(relative) != null) {
            invalidate();
        }
    }

    /** Ficheiros alterados fora do editor (criados, apagados, gravados). */
    synchronized void diskChanged() {
        disk = null;
        invalidate();
    }

    synchronized boolean isOpen(Path relative) {
        return overlays.containsKey(relative);
    }

    /** Disco + overlays: o que o utilizador está a ver. */
    synchronized SukoSources sources() {
        if (cachedSources == null) {
            if (disk == null) {
                disk = readDisk();
            }
            cachedSources = disk.withOverlay(overlays);
        }
        return cachedSources;
    }

    /** Fontes e verificação do mesmo instante — o texto certo para converter as posições dos diagnósticos. */
    record Snapshot(SukoSources sources, ProjectAnalysis analysis) {
    }

    synchronized Snapshot snapshot() {
        return new Snapshot(sources(), analysis());
    }

    /** Verificação do root inteiro, com cache; os diagnósticos são os do {@code sukoCompile}. */
    synchronized ProjectAnalysis analysis() {
        if (cached == null) {
            cached = compiler().analyze(sources());
        }
        return cached;
    }

    /**
     * AST tolerante do ficheiro (ver {@link TolerantParser}) — para navegação e
     * completion, nunca para diagnósticos. Em cache até algo mudar.
     */
    synchronized SukoFile tolerantAst(Path relative) {
        String text = sources().files().get(relative);
        if (text == null) {
            return null;
        }
        return tolerantAsts.computeIfAbsent(relative, k -> TolerantParser.parse(text));
    }

    private SukoSources readDisk() {
        try {
            return SukoSources.fromDirectory(root);
        } catch (RuntimeException e) {
            // pasta apagada/ilegível: sem ficheiros em disco, só os buffers abertos
            return SukoSources.of(root, Map.of());
        }
    }

    private void invalidate() {
        cached = null;
        cachedSources = null;
        tolerantAsts.clear();
    }
}
