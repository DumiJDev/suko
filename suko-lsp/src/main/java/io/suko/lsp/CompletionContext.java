package io.suko.lsp;

import io.suko.lang.SukoLexer;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Em que sítio da gramática está o cursor, decidido pelos <b>tokens do
 * lexer</b> até ao cursor — não pelo AST, que num ficheiro a meio de ser
 * escrito não existe (`A(` num corpo é engolido como texto pelo parser).
 * O lexer nunca falha, por isso isto funciona em qualquer estado do fonte.
 *
 * <p>O agrupamento é o mesmo do compilador: procura-se, para trás, o
 * parêntese ou chaveta ainda por fechar que envolve o cursor, saltando os
 * grupos já fechados.
 */
final class CompletionContext {

    enum Kind {
        /** Nada a sugerir (dentro de uma tag, string, {@code ${}}, cabeçalho de componente, ...). */
        NONE,
        /** Depois de {@code import}: nomes qualificados de componentes. */
        IMPORT,
        /** Corpo de um componente/if/for/slot: componentes e keywords. */
        BODY,
        /** Bloco de uma chamada, {@code Card() { | }}: slots e o mesmo que BODY. */
        CALL_BLOCK,
        /** Dentro de {@code Card( | )}: parâmetros ainda não passados. */
        ARGUMENT
    }

    /**
     * @param typed o que já foi escrito da palavra (ou nome qualificado, num import) sob o cursor
     * @param typedLength comprimento de {@code typed} em unidades UTF-16 (o intervalo a substituir)
     * @param callName componente chamado (CALL_BLOCK e ARGUMENT)
     * @param usedArguments nomes de argumentos já passados nesta chamada (ARGUMENT)
     */
    record Result(Kind kind, String typed, int typedLength, String callName, Set<String> usedArguments) {
        static Result none() {
            return new Result(Kind.NONE, "", 0, null, Set.of());
        }
    }

    private static final Pattern LINE_COMMENT = Pattern.compile("//[ \\t]");

    private CompletionContext() {
    }

    static Result analyze(String prefix) {
        int lineStart = prefix.lastIndexOf('\n') + 1;
        if (LINE_COMMENT.matcher(prefix.substring(lineStart)).find()) {
            return Result.none();
        }

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(prefix));
        lexer.removeErrorListeners();
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        // Dentro de uma string (ou de um ${} numa string) o lexer fica noutro modo.
        if (lexer._mode != Lexer_DEFAULT_MODE || !lexer._modeStack.isEmpty()) {
            return Result.none();
        }
        List<Token> tokens = new ArrayList<>();
        for (Token t : stream.getTokens()) {
            if (t.getType() != Token.EOF && t.getChannel() == Token.DEFAULT_CHANNEL) {
                tokens.add(t);
            }
        }

        int cursorCodePoints = prefix.codePointCount(0, prefix.length());
        // A palavra sob o cursor: último token, se colado ao cursor e for identificador/keyword.
        String typed = "";
        int last = tokens.size() - 1;
        if (last >= 0 && tokens.get(last).getStopIndex() + 1 == cursorCodePoints && isWord(tokens.get(last))) {
            typed = tokens.get(last).getText();
            last--;
        }

        // Import: `import` seguido de Identifier (. Identifier)* — o nome qualificado é o "typed".
        int importStart = importStart(tokens, last);
        if (importStart >= 0) {
            StringBuilder qualified = new StringBuilder();
            for (int i = importStart + 1; i <= last; i++) {
                qualified.append(tokens.get(i).getText());
            }
            qualified.append(typed);
            // `import ui.Card |`: já há um espaço depois do nome — nada a completar
            boolean cursorGluedToName = typed.length() > 0
                || (last > importStart && tokens.get(last).getStopIndex() + 1 == cursorCodePoints);
            if (!cursorGluedToName && last > importStart) {
                return Result.none();
            }
            return new Result(Kind.IMPORT, qualified.toString(), qualified.length(), null, Set.of());
        }

        int enclosing = enclosing(tokens, last);
        if (enclosing < 0) {
            // Nível de topo do ficheiro: só `component`/`import`/`package`, que não completamos.
            return Result.none();
        }
        Token opener = tokens.get(enclosing);
        int type = opener.getType();

        if (type == SukoLexer.LPAREN) {
            return argumentContext(tokens, enclosing, last, typed);
        }
        if (type == SukoLexer.EXPR_INTERP_START || type == SukoLexer.SIMPLE_INTERP_START) {
            return Result.none();
        }

        // LBRACE: um bloco. Só se sugere onde uma instrução pode começar.
        if (!atStatementStart(tokens, last)) {
            return Result.none();
        }
        if (enclosing > 0 && tokens.get(enclosing - 1).getType() == SukoLexer.RPAREN) {
            int open = matchingParen(tokens, enclosing - 1);
            String callee = open < 0 ? null : qualifiedNameBefore(tokens, open);
            if (callee != null && !isBlockKeywordCall(tokens, open) && !isComponentDeclaration(tokens, open)) {
                return new Result(Kind.CALL_BLOCK, typed, typed.length(), callee, Set.of());
            }
        }
        return new Result(Kind.BODY, typed, typed.length(), null, Set.of());
    }

    // O valor de Lexer.DEFAULT_MODE (0) sem depender do nome da constante herdada.
    private static final int Lexer_DEFAULT_MODE = org.antlr.v4.runtime.Lexer.DEFAULT_MODE;

    private static Result argumentContext(List<Token> tokens, int lparen, int last, String typed) {
        // `component Nome(` — cabeçalho, não uma chamada; `if (`/`for (`/`switch (` — expressão
        if (isComponentDeclaration(tokens, lparen) || isBlockKeywordCall(tokens, lparen)) {
            return Result.none();
        }
        String callee = qualifiedNameBefore(tokens, lparen);
        if (callee == null) {
            return Result.none();
        }
        // Só onde um NOME de argumento pode começar: logo depois de `(` ou de `,`.
        int previous = last;
        if (previous <= lparen) {
            return new Result(Kind.ARGUMENT, typed, typed.length(), callee, Set.of());
        }
        if (tokens.get(previous).getType() != SukoLexer.COMMA) {
            return Result.none();
        }
        return new Result(Kind.ARGUMENT, typed, typed.length(), callee, usedArgumentNames(tokens, lparen, last));
    }

    /** Nomes {@code x} de {@code x = ...} ao nível da chamada (sem descer a parênteses aninhados). */
    private static Set<String> usedArgumentNames(List<Token> tokens, int lparen, int last) {
        Set<String> used = new LinkedHashSet<>();
        int depth = 0;
        for (int i = lparen + 1; i <= last; i++) {
            int t = tokens.get(i).getType();
            if (t == SukoLexer.LPAREN || t == SukoLexer.LBRACE) {
                depth++;
            } else if (t == SukoLexer.RPAREN || t == SukoLexer.RBRACE) {
                depth--;
            } else if (depth == 0 && t == SukoLexer.Identifier && i + 1 <= last
                    && tokens.get(i + 1).getType() == SukoLexer.EQ) {
                used.add(tokens.get(i).getText());
            }
        }
        return used;
    }

    /** Índice do {@code import} se o cursor está numa linha {@code import a.b.c}, senão -1. */
    private static int importStart(List<Token> tokens, int last) {
        int i = last;
        while (i >= 0 && (tokens.get(i).getType() == SukoLexer.Identifier || tokens.get(i).getType() == SukoLexer.DOT)) {
            i--;
        }
        if (i >= 0 && tokens.get(i).getType() == SukoLexer.IMPORT) {
            return i;
        }
        return -1;
    }

    /** Parêntese/chaveta ainda por fechar que envolve a posição {@code last}, saltando grupos fechados. */
    private static int enclosing(List<Token> tokens, int last) {
        int parens = 0;
        int braces = 0;
        for (int i = last; i >= 0; i--) {
            int t = tokens.get(i).getType();
            if (t == SukoLexer.RPAREN) {
                parens++;
            } else if (t == SukoLexer.LPAREN) {
                if (parens == 0) {
                    return i;
                }
                parens--;
            } else if (t == SukoLexer.RBRACE) {
                braces++;
            } else if (t == SukoLexer.LBRACE || t == SukoLexer.EXPR_INTERP_START) {
                if (braces == 0) {
                    return i;
                }
                braces--;
            }
        }
        return -1;
    }

    private static int matchingParen(List<Token> tokens, int rparen) {
        int depth = 0;
        for (int i = rparen; i >= 0; i--) {
            int t = tokens.get(i).getType();
            if (t == SukoLexer.RPAREN) {
                depth++;
            } else if (t == SukoLexer.LPAREN) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** {@code Ident(.Ident)*} imediatamente antes de {@code index}, ou {@code null}. */
    private static String qualifiedNameBefore(List<Token> tokens, int index) {
        int i = index - 1;
        if (i < 0 || tokens.get(i).getType() != SukoLexer.Identifier) {
            return null;
        }
        StringBuilder name = new StringBuilder(tokens.get(i).getText());
        while (i >= 2 && tokens.get(i - 1).getType() == SukoLexer.DOT
                && tokens.get(i - 2).getType() == SukoLexer.Identifier) {
            name.insert(0, tokens.get(i - 2).getText() + ".");
            i -= 2;
        }
        return name.toString();
    }

    /** {@code if (}, {@code for (}, {@code switch (} — o parêntese é de uma expressão, não de uma chamada. */
    private static boolean isBlockKeywordCall(List<Token> tokens, int lparen) {
        if (lparen == 0) {
            return false;
        }
        int t = tokens.get(lparen - 1).getType();
        return t == SukoLexer.IF || t == SukoLexer.FOR || t == SukoLexer.SWITCH;
    }

    private static boolean isComponentDeclaration(List<Token> tokens, int lparen) {
        return lparen >= 2 && tokens.get(lparen - 1).getType() == SukoLexer.Identifier
            && tokens.get(lparen - 2).getType() == SukoLexer.COMPONENT;
    }

    /** Uma instrução começa depois de {@code {}, {@code }}, {@code ;} ou do {@code >} que fecha uma tag. */
    private static boolean atStatementStart(List<Token> tokens, int last) {
        if (last < 0) {
            return true;
        }
        int t = tokens.get(last).getType();
        if (t == SukoLexer.LBRACE || t == SukoLexer.RBRACE || t == SukoLexer.SEMI) {
            return true;
        }
        if (t == SukoLexer.GT || t == SukoLexer.SLASHGT) {
            return !insideOpenTag(tokens, last);
        }
        return false;
    }

    /** {@code >} pode ser o fecho de uma tag ({@code <div>}) — sempre é, no corpo; aqui só se exclui {@code a > b}. */
    private static boolean insideOpenTag(List<Token> tokens, int gtIndex) {
        // Corpo de template: um `>` solto sem `<` ou `</` correspondente na mesma "linha lógica" é operador.
        for (int i = gtIndex - 1; i >= 0; i--) {
            int t = tokens.get(i).getType();
            if (t == SukoLexer.LT || t == SukoLexer.LTSLASH) {
                return false;
            }
            if (t == SukoLexer.LBRACE || t == SukoLexer.RBRACE || t == SukoLexer.SEMI
                    || t == SukoLexer.GT || t == SukoLexer.SLASHGT) {
                return true;
            }
        }
        return true;
    }

    private static boolean isWord(Token token) {
        int t = token.getType();
        return t == SukoLexer.Identifier || (t >= SukoLexer.PACKAGE && t <= SukoLexer.NULLLIT)
            || t == SukoLexer.BooleanLiteral;
    }
}
