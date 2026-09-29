package io.suko.lsp;

import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Lembra o URI exacto com que o cliente abriu cada documento: o editor
 * compara URIs como strings (o VSCode manda {@code file:///c%3A/...} no
 * Windows, o {@code Path.toUri()} não), por isso as respostas usam o dele
 * sempre que existir e o do {@code Path} só para ficheiros nunca abertos.
 */
final class DocumentUris {

    private final Map<Path, String> openUris = new ConcurrentHashMap<>();

    void opened(String uri) {
        Workspace.tryPathOf(uri).ifPresent(path -> openUris.put(path, uri));
    }

    void closed(String uri) {
        Workspace.tryPathOf(uri).ifPresent(openUris::remove);
    }

    String uriOf(Path absolutePath) {
        Path normalized = absolutePath.toAbsolutePath().normalize();
        String open = openUris.get(normalized);
        return open != null ? open : normalized.toUri().toString();
    }
}
