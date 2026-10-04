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
    private final Runnable afterRediscover;

    SukoWorkspaceService(Workspace workspace, DiagnosticsService diagnostics, Consumer<String> onSourceRootSetting,
                         Runnable afterRediscover) {
        this.afterRediscover = afterRediscover;
        this.workspace = workspace;
        this.diagnostics = diagnostics;
        this.onSourceRootSetting = onSourceRootSetting;
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        Requests.guardedRun("didChangeWatchedFiles", () -> handleWatchedFiles(params));
    }

    private void handleWatchedFiles(DidChangeWatchedFilesParams params) {
        boolean configChanged = false;
        Set<Project> touched = new LinkedHashSet<>();
        for (FileEvent event : params.getChanges()) {
            Path file = Workspace.tryPathOf(event.getUri()).orElse(null);
            if (file == null) {
                continue;
            }
            if (file.getFileName() != null && (ProjectLocator.CONFIG_FILE.equals(file.getFileName().toString())
                    || isExtensionsManifest(file))) {
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
            afterRediscover.run();
            touched.addAll(workspace.projects());
        }
        touched.forEach(diagnostics::request);
    }

    /** {@code build/suko/extensions.json} ou {@code target/suko/extensions.json}. */
    private static boolean isExtensionsManifest(Path file) {
        Path parent = file.getParent();
        return "extensions.json".equals(file.getFileName().toString())
            && parent != null && parent.getFileName() != null && "suko".equals(parent.getFileName().toString());
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
        Requests.guardedRun("didChangeConfiguration", () -> {
            String sourceRoot = sourceRootFrom(params.getSettings());
            if (sourceRoot != null) {
                onSourceRootSetting.accept(sourceRoot);
                workspace.projects().forEach(diagnostics::request);
            }
        });
    }

    /** {@code {"trusted": true}} nas opções de inicialização; ausente ou não-booleano conta como não confiável. */
    static boolean trustedFrom(Object initializationOptions) {
        if (!(initializationOptions instanceof JsonObject object)) {
            return false;
        }
        JsonElement value = object.get("trusted");
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
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
