package io.suko.lsp;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Descobre o source root de uma pasta do workspace, por esta ordem (spec do
 * 11a): chave {@code sourceRoot} do {@code suko.json}; senão
 * {@code src/main/suko}; senão a setting {@code suko.sourceRoot}. Só devolve
 * pastas que existem. Lê o {@code suko.json} com Gson directamente — o server
 * não depende do {@code suko-cli}.
 */
final class ProjectLocator {

    static final String CONFIG_FILE = "suko.json";
    static final String DEFAULT_SOURCE_ROOT = "src/main/suko";

    private ProjectLocator() {
    }

    static Optional<Path> locate(Path workspaceFolder, String settingSourceRoot) {
        Optional<String> fromConfig = readSourceRoot(workspaceFolder.resolve(CONFIG_FILE));
        if (fromConfig.isPresent()) {
            Optional<Path> candidate = insideWorkspace(workspaceFolder, fromConfig.get());
            if (candidate.isPresent()) {
                return candidate;
            }
        }
        Optional<Path> conventional = insideWorkspace(workspaceFolder, DEFAULT_SOURCE_ROOT);
        if (conventional.isPresent()) {
            return conventional;
        }
        if (settingSourceRoot != null && !settingSourceRoot.isBlank()) {
            return insideWorkspace(workspaceFolder, settingSourceRoot.strip());
        }
        return Optional.empty();
    }

    /**
     * Um source root tem de ficar dentro da pasta do workspace: valores absolutos, UNC ou com
     * {@code ..} que saiam dela são ignorados (cai para a descoberta por omissão). A verificação
     * léxica vem antes de qualquer acesso ao disco — um caminho UNC nunca é consultado (no
     * Windows isso fugiria credenciais NTLM) — e, se a pasta existe, o caminho real também tem
     * de ficar dentro (symlinks).
     */
    static Optional<Path> insideWorkspace(Path workspaceFolder, String value) {
        try {
            if (value.startsWith("\\\\") || value.startsWith("//") || Path.of(value).isAbsolute()
                    || Path.of(value).getRoot() != null) {
                return Optional.empty();
            }
            Path base = workspaceFolder.toAbsolutePath().normalize();
            Path candidate = workspaceFolder.resolve(value).normalize();
            if (!candidate.toAbsolutePath().normalize().startsWith(base) || !Files.isDirectory(candidate)) {
                return Optional.empty();
            }
            if (!candidate.toRealPath().startsWith(workspaceFolder.toRealPath())) {
                return Optional.empty();
            }
            return Optional.of(candidate);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** {@code sourceRoot} do {@code suko.json}; vazio se o ficheiro não existe, não é JSON ou não tem a chave. */
    private static Optional<String> readSourceRoot(Path config) {
        if (!Files.isRegularFile(config)) {
            return Optional.empty();
        }
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(config));
            if (!parsed.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject object = parsed.getAsJsonObject();
            JsonElement value = object.get("sourceRoot");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return Optional.empty();
            }
            String text = value.getAsString();
            return text.isBlank() ? Optional.empty() : Optional.of(text);
        } catch (IOException | RuntimeException e) {
            // suko.json partido não pode derrubar o server: cai para a convenção.
            return Optional.empty();
        }
    }
}
