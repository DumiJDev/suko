package io.suko.lang.ast;

/**
 * Posição de um nó do AST no ficheiro .sk de origem. startIndex/endIndex
 * são offsets absolutos de carácter (Token.getStartIndex()/getStopIndex()
 * do ANTLR), usados pelo subprojeto 3 para mapear o stub Java de
 * verificação de volta ao .sk.
 */
public record SourceSpan(int startLine, int startColumn, int startIndex, int endIndex) {
}
