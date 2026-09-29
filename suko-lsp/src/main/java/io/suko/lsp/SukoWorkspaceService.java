package io.suko.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.services.WorkspaceService;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Ficheiros que mudaram fora do editor ({@code *.sk}, {@code suko.json}) e
 * settings. Um {@code suko.json} novo ou alterado pode mudar o source root,
 * por isso volta a descobrir os projectos.
 */
final class SukoWorkspaceService implements WorkspaceService {

    private final Workspace workspace;
    private final DiagnosticsService diagnostics;
    private final Consumer<String> onSourceRootSetting;

    SukoWorkspaceService(Workspace workspace, DiagnosticsService diagnostics, Consumer<String> onSourceRootSetting) {
        this.workspace = workspace;
        this.diagnostics = diagnostics;
        this.onSourceRootSetting = onSourceRootSetting;
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean configChanged = false;
        Set<Project> touched = new LinkedHashSet<>();
        for (FileEvent event : params.getChanges()) {
            Path file = Workspace.pathOf(event.getUri());
            if (file.getFileName() != null && ProjectLocator.CONFIG_FILE.equals(file.getFileName().toString())) {
                configChanged = true;
                continue;
            }
            workspace.projectFor(file).ifPresent(project -> {
                project.diskChanged();
                touched.add(project);
            });
        }
        if (configChanged) {
            workspace.rediscover();
            touched.addAll(workspace.projects());
        }
        touched.forEach(diagnostics::request);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        String sourceRoot = sourceRootFrom(params.getSettings());
        if (sourceRoot != null) {
            onSourceRootSetting.accept(sourceRoot);
            workspace.projects().forEach(diagnostics::request);
        }
    }

    /** Aceita {@code {"suko":{"sourceRoot":"..."}}} e {@code {"sourceRoot":"..."}}. */
    static String sourceRootFrom(Object settings) {
        if (!(settings instanceof JsonObject object)) {
            return null;
        }
        JsonElement suko = object.get("suko");
        JsonObject scope = suko != null && suko.isJsonObject() ? suko.getAsJsonObject() : object;
        JsonElement value = scope.get("sourceRoot");
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
