package io.suko.lang.ast;

/** Mapeia uma linha (1-based) do .jte gerado para o span do .sk que a originou. */
public record SourceMapEntry(int jteLine, SourceSpan sukoSpan) {
}
