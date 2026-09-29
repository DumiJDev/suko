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

    /** (Re)descobre os projetos; reaproveita os que continuam no mesmo root (mantém buffers abertos). */
    synchronized void configure(List<Path> workspaceFolders, String sourceRootSetting) {
        this.folders = List.copyOf(workspaceFolders);
        this.sourceRootSetting = sourceRootSetting == null ? "" : sourceRootSetting;
        rediscover();
    }

    /** Volta a procurar roots (ex.: um {@code suko.json} foi criado/alterado). */
    synchronized void rediscover() {
        Map<Path, Project> next = new LinkedHashMap<>();
        for (Path folder : folders) {
            ProjectLocator.locate(folder, sourceRootSetting).ifPresent(root -> {
                Project existing = projectsByRoot.get(root);
                next.putIfAbsent(root, existing != null ? existing : new Project(root));
            });
        }
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

    static Path pathOf(String uri) {
        return Path.of(URI.create(uri)).toAbsolutePath().normalize();
    }
}
