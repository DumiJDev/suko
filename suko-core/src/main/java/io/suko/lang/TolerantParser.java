package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Terceiro caminho de parse do monorepo (além dos dois sem error listener
 * já descritos no ARCHITECTURE.md), usado <b>só</b> pelo language server:
 * produz o melhor AST possível de um ficheiro que está a meio de ser escrito,
 * para completion e navegação. Nunca reporta diagnósticos — esses vêm sempre
 * do caminho estrito ({@code JteCompiler}), idêntico ao do {@code sukoCompile}.
 *
 * <p><b>Isolamento por componente.</b> A recuperação de erros do ANTLR pode
 * engolir os componentes seguintes para dentro do que está partido
 * ({@code ${t.} sem fecho deixa {@code component Good() {..}} como texto do
 * corpo de A}). Por isso, quando o parse do ficheiro inteiro não reproduz
 * os componentes que o lexer vê, cada componente é reparseado sozinho: o
 * fonte é o mesmo com tudo o resto substituído por espaços (mantendo as
 * quebras de linha e o número de code points), pelo que linhas, colunas e
 * offsets dos spans coincidem com os do ficheiro real.
 */
public final class TolerantParser {

    private TolerantParser() {
    }

    /** Nunca lança: no pior caso devolve um ficheiro vazio. */
    public static SukoFile parse(String source) {
        try {
            return doParse(source);
        } catch (RuntimeException e) {
            return new SukoFile(Optional.empty(), Optional.empty(), List.of(), List.of());
        }
    }

    private static SukoFile doParse(String source) {
        List<Integer> starts = componentStarts(source);

        SukoParser wholeParser = parser(source);
        SukoParser.CompilationUnitContext whole = wholeParser.compilationUnit();
        // Sem erros de sintaxe o parse do ficheiro inteiro é a verdade — e a
        // segmentação por linha só pode enganar-se: `component Card(x)` escrito
        // como prosa dentro de um <p> (ex.: páginas de documentação) parece o
        // início de um componente ao lexer, que não tem modos.
        boolean wholeIsFaithful = wholeParser.getNumberOfSyntaxErrors() == 0
            || (whole.componentDecl().size() == starts.size()
                && whole.componentDecl().stream().allMatch(c -> c.componentBody() != null));
        if (wholeIsFaithful) {
            return SukoAstBuilder.tolerant(source).build(whole);
        }

        if (starts.isEmpty()) {
            return SukoAstBuilder.tolerant(source).build(whole);
        }

        SukoFile header = buildRange(source, 0, starts.get(0));
        List<ComponentDecl> components = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < starts.size() ? starts.get(i + 1) : source.length();
            components.addAll(buildRange(source, starts.get(i), end).components());
        }
        List<ImportDecl> imports = header.imports();
        Optional<SourceSpan> packageSpan = header.packageSpan();
        return new SukoFile(header.packageName(), packageSpan, imports, components);
    }

    private static SukoFile buildRange(String source, int from, int to) {
        String isolated = blankOutside(source, from, to);
        return SukoAstBuilder.tolerant(isolated).build(parser(isolated).compilationUnit());
    }

    private static SukoParser parser(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        return parser;
    }

    /**
     * Offsets (em UTF-16 do {@code source}) onde começa cada declaração de
     * componente: {@code [public] component Nome (} ou {@code <}, sendo a
     * primeira coisa da sua linha.
     */
    private static List<Integer> componentStarts(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        List<Token> tokens = stream.getTokens().stream()
            .filter(t -> t.getChannel() == Token.DEFAULT_CHANNEL && t.getType() != Token.EOF)
            .toList();

        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getType() != SukoLexer.COMPONENT || i + 2 >= tokens.size()) {
                continue;
            }
            if (tokens.get(i + 1).getType() != SukoLexer.Identifier) {
                continue;
            }
            int after = tokens.get(i + 2).getType();
            if (after != SukoLexer.LPAREN && after != SukoLexer.LT) {
                continue;
            }
            int first = i;
            if (i > 0 && tokens.get(i - 1).getType() == SukoLexer.PUBLIC
                    && tokens.get(i - 1).getLine() == token.getLine()) {
                first = i - 1;
            }
            boolean firstOnLine = first == 0 || tokens.get(first - 1).getLine() < tokens.get(first).getLine();
            if (firstOnLine) {
                starts.add(codePointIndexToCharIndex(source, tokens.get(first).getStartIndex()));
            }
        }
        return starts;
    }

    // Os índices do ANTLR contam code points; String.substring conta chars.
    private static int codePointIndexToCharIndex(String source, int codePointIndex) {
        return source.offsetByCodePoints(0, codePointIndex);
    }

    /** Substitui cada code point fora de [from,to) por um espaço, exceto quebras de linha. */
    static String blankOutside(String source, int from, int to) {
        StringBuilder sb = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            int cp = source.codePointAt(i);
            int width = Character.charCount(cp);
            boolean inside = i >= from && i < to;
            if (inside || cp == '\n' || cp == '\r') {
                sb.appendCodePoint(cp);
            } else {
                sb.append(' ');
            }
            i += width;
        }
        return sb.toString();
    }
}
