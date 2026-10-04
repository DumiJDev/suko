package io.suko.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * As pastas do workspace e um {@link Project} por source root. Workspaces
 * multi-pasta funcionam sem resolução de nomes entre roots — a mesma
 * limitação do compilador (um source root, ver subprojeto 8).
 */
final class Workspace {

    private final Map<Path, Project> projectsByRoot = new LinkedHashMap<>();
    private List<Path> folders = List.of();
    private String sourceRootSetting = "";
    private boolean trusted;

    /** (Re)descobre os projetos; reaproveita os que continuam no mesmo root (mantém buffers abertos). */
    synchronized void configure(List<Path> workspaceFolders, String sourceRootSetting) {
        configure(workspaceFolders, sourceRootSetting, trusted);
    }

    synchronized void configure(List<Path> workspaceFolders, String sourceRootSetting, boolean trusted) {
        this.trusted = trusted;
        this.folders = List.copyOf(workspaceFolders);
        this.sourceRootSetting = sourceRootSetting == null ? "" : sourceRootSetting;
        rediscover();
    }

    /** Volta a procurar roots (ex.: um {@code suko.json} foi criado/alterado). */
    synchronized void rediscover() {
        Map<Path, Project> next = new LinkedHashMap<>();
        List<ProjectExtensions.Loaded> created = new ArrayList<>();
        try {
            for (Path folder : folders) {
                Optional<Path> located = ProjectLocator.locate(folder, sourceRootSetting);
                if (located.isEmpty() || next.containsKey(located.get())) {
                    continue;
                }
                Path root = located.get();
                Project existing = projectsByRoot.get(root);
                String key = ProjectExtensions.fingerprint(root, folder, trusted);
                if (existing != null && key.equals(existing.extensionsKey())) {
                    next.put(root, existing); // manifesto e confiança iguais: não recarregar
                    continue;
                }
                ProjectExtensions.Loaded loaded;
                try {
                    loaded = ProjectExtensions.forProject(root, folder, trusted);
                } catch (Throwable e) {
                    io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                    loaded = ProjectExtensions.builtIn(Optional.of("Suko: falha ao carregar extensões de " + root + ": " + e));
                }
                created.add(loaded);
                if (existing != null) {
                    existing.setExtensions(loaded, key);
                    next.put(root, existing);
                } else {
                    next.put(root, new Project(root, loaded, key));
                }
            }
        } catch (Throwable fatal) {
            // erro fatal da JVM a meio: não deixar os classloaders já criados abertos
            created.forEach(ProjectExtensions.Loaded::close);
            throw fatal;
        }
        projectsByRoot.forEach((root, project) -> {
            if (!next.containsKey(root)) {
                project.dispose();
            }
        });
        projectsByRoot.clear();
        projectsByRoot.putAll(next);
    }

    synchronized List<Project> projects() {
        return new ArrayList<>(projectsByRoot.values());
    }

    /** O projeto cujo source root contém este ficheiro {@code .sk} (o root mais específico ganha). */
    synchronized Optional<Project> projectFor(Path file) {
        if (!file.toString().endsWith(".sk")) {
            return Optional.empty();
        }
        Path normalized = file.toAbsolutePath().normalize();
        Project best = null;
        for (Project project : projectsByRoot.values()) {
            if (normalized.startsWith(project.root())
                    && (best == null || project.root().getNameCount() > best.root().getNameCount())) {
                best = project;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Só documentos {@code file:}; {@code untitled:} e afins (ficheiros por gravar) não pertencem a nenhum source root. */
    static Optional<Path> tryPathOf(String uri) {
        try {
            return Optional.of(pathOf(uri));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    static Path pathOf(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }
}
