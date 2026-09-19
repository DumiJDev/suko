package io.suko.lang.project;

import java.nio.file.Path;

public record ProjectIndexEntry(
    String qualifiedName,
    String simpleName,
    Path sourceFile,
    boolean isPublic,
    int paramCount
) {
}
