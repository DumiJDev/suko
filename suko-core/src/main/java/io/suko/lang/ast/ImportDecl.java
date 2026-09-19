package io.suko.lang.ast;

import java.util.Optional;

public record ImportDecl(String qualifiedName, Optional<String> alias, SourceSpan span) {
}
