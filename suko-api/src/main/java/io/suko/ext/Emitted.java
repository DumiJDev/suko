package io.suko.ext;

import io.suko.lang.ast.SourceMapEntry;

import java.util.List;

/** {@code relativePath} é relativo à pasta do .sk de origem, ex.: {@code "Hello.jte"}. */
public record Emitted(String relativePath, String source, List<SourceMapEntry> sourceMap) {
}
