package io.suko.lang.ast;

/**
 * Posição de um nó do AST no ficheiro .sk de origem. startLine é 1-based;
 * startColumn (0-based), startIndex e endIndex contam <b>code points</b> (não
 * unidades UTF-16 — um emoji vale 1) exatamente como o ANTLR
 * (Token.getCharPositionInLine()/getStartIndex()/getStopIndex()); endIndex é
 * <b>inclusivo</b>. Usados pelo subprojeto 3 para mapear o stub Java de
 * verificação de volta ao .sk e pelo language server para os intervalos do LSP.
 */
public record SourceSpan(int startLine, int startColumn, int startIndex, int endIndex) {

    /** "Sem posição": nós construídos à mão e erros que não vêm de um sítio do fonte. */
    public static final SourceSpan NONE = new SourceSpan(0, 0, 0, 0);

    /** {@code true} para {@link #NONE} (nenhum nó lido do fonte tem linha 0). */
    public boolean isNone() {
        return startLine == 0;
    }
}
