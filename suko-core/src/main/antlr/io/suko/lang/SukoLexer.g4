lexer grammar SukoLexer;

// ============================================================
// Suko — Lexer
// Separado do parser porque ANTLR só permite `mode` em gramáticas
// puramente léxicas (gramáticas combinadas geram erro 176/120).
// ============================================================

@members {
    // Um '"' só é tratado como início de string literal se houver um
    // '"' de fecho antes de cruzar '<', '>' ou uma quebra de linha —
    // exatamente o que separa um literal de string Suko real (curto,
    // numa linha só, sem marcação) de uma aspa solta em texto (ex:
    // 5" polegadas). Sem isto, o lexer não tem como distinguir os dois
    // casos, porque não existe modo de lexer separado para texto de tag
    // (ver ARCHITECTURE.md sobre a rejeição de um modo TEXT dedicado).
    // Limitação aceite: um literal de string Suko não pode conter '<'
    // ou '>' literal.
    private boolean canStartStringLiteral() {
        for (int i = 1; ; i++) {
            int c = _input.LA(i);
            if (c == '"') {
                return true;
            }
            if (c == -1 || c == '<' || c == '>' || c == '\n') {
                return false;
            }
        }
    }
}

// --- palavras-chave ---
PACKAGE   : 'package';
IMPORT    : 'import';
AS        : 'as';
PUBLIC    : 'public';
COMPONENT : 'component';
VAR       : 'var';
IF        : 'if';
ELSE      : 'else';
FOR       : 'for';
SWITCH    : 'switch';
CASE      : 'case';
DEFAULT   : 'default';
NULLLIT   : 'null';

BooleanLiteral
    : 'true' | 'false'
    ;

// --- pontuação / operadores ---
// Comprimentos diferentes já resolvem toda ambiguidade por
// maximal-munch do ANTLR; não há colisão de tamanho igual aqui.
LPAREN    : '(';
RPAREN    : ')';
LBRACE    : '{';
// DESVIO DO BRIEF (subprojeto 6, tarefa 7): "-> popMode" incondicional
// fecharia também todo "}" que fecha templateBlock/slotBlock/if/for/switch
// (a esmagadora maioria dos casos), cuja pilha de modos está vazia nesse
// ponto — popMode() nessa condição lança exceção no runtime ANTLR. Só o
// "}" que fecha um "${...}" (EXPR_INTERP_START empurrou DEFAULT_MODE de
// dentro de STRING_MODE) tem de voltar de modo; esse é sempre o único "}"
// com pilha não-vazia neste ponto, porque a gramática de `expression` não
// tem chaves em si mesma (ver comentário em SukoParser.g4 junto a
// stringPart). ANTLR não permite condição num comando "->", por isso usa-se
// ação embutida em vez do atalho de comando — mesmo padrão de desvio já
// usado em LINE_COMMENT.
RBRACE
    : '}' { if (!_modeStack.isEmpty()) popMode(); }
    ;
LBRACKET  : '[';
RBRACKET  : ']';
LE        : '<=';
GE        : '>=';
LT        : '<';
GT        : '>';
EQEQ      : '==';
EQ        : '=';
NEQ       : '!=';
AND       : '&&';
OR        : '||';
NOT       : '!';
ARROW     : '->';
QDOT      : '?.';
QCOLON    : '?:';
QUESTION  : '?';
SLASHGT   : '/>';
LTSLASH   : '</';
PLUS      : '+';
MINUS     : '-';
STAR      : '*';
SLASH     : '/';
PERCENT   : '%';
DOT       : '.';
COMMA     : ',';
SEMI      : ';';
COLON     : ':';

Identifier
    : [a-zA-Z_][a-zA-Z0-9_]*
    ;

IntegerLiteral
    : [0-9]+
    ;

WS
    : [ \t\r\n]+ -> skip
    ;

// "//" só conta como comentário quando seguido de espaço/tab (estilo
// "// texto") ou de quebra de linha imediata (comentário vazio). Isto
// distingue de propósito "// comentário" de "http://x.com" em texto —
// nenhuma URL tem espaço logo depois de "//". Limitação aceite: um "//"
// sozinho no fim absoluto do ficheiro (sem newline a seguir) não conta
// como comentário.
//
// DESVIO DO BRIEF (mínimo, necessário para compilar): o brief escreve os
// dois alts de nível superior separados por "|" com um único "-> skip"
// pendurado no fim. O ANTLR 4.13.1 rejeita isso — "->command in lexer
// rule LINE_COMMENT must be last element of single outermost alt" (erro
// 133 na geração) — porque um comando lexer só se aplica a UM alt
// outermost, não a vários. A correção é agrupar as duas alternativas
// numa sub-regra entre parênteses, tornando a regra um único alt
// outermost ao qual "-> skip" se aplica; o comportamento léxico
// pretendido pelo brief é preservado exatamente.
LINE_COMMENT
    : ( '//' [ \t] ~[\r\n]*
      | '//' '\r'? '\n'
      )
    -> skip
    ;

BLOCK_COMMENT
    : '/*' .*? '*/' -> skip
    ;

// --- strings com interpolação estilo Kotlin: "$x" e "${expr}" ---
// Usadas em VALORES (atribuições, argumentos, atributos) — não é
// o mecanismo de interpolação de conteúdo de tag (esse é resolvido
// no parser, ver SukoParser.g4 / regra textRun).

STRING_START
    : '"' {canStartStringLiteral()}? -> pushMode(STRING_MODE)
    ;

// Qualquer caractere não coberto por nenhuma regra acima — ex:
// travessão "—" (diferente do hífen "-", já coberto por MINUS),
// aspas curvas, outros símbolos unicode usados como texto solto
// fora de tags. Sempre por último: como WS/comentários/palavras-
// chave/pontuação já cobrem tudo que reconhecem antes dessa regra,
// OTHER só entra em jogo pro que sobra. O parser já aceita
// qualquer token aqui via `textRun` (~(LBRACE|RBRACE|LT)).
OTHER
    : .
    ;

mode STRING_MODE;

STRING_END
    : '"' -> popMode
    ;

STRING_ESCAPE
    : '\\' .
    ;

// "${ expr }" — a expressão em si não tem chaves em seu próprio
// grammar (sem lambdas/blocos), então um único '}' fecha sem
// ambiguidade de profundidade.
EXPR_INTERP_START
    : '${' -> pushMode(DEFAULT_MODE)
    ;

// "$identificador" — forma simples, sem chaves
SIMPLE_INTERP_START
    : '$' [a-zA-Z_][a-zA-Z0-9_]*
    ;

// "$" sem identificador a seguir (ex: "R$ 10") não é início de
// interpolação — é texto literal. Como SIMPLE_INTERP_START/
// EXPR_INTERP_START exigem pelo menos mais um carácter e casam mais
// texto quando aplicável, esta regra só entra em jogo quando nenhuma
// delas casa (maximal-munch do ANTLR já resolve a prioridade).
SIMPLE_DOLLAR
    : '$'
    ;

STRING_TEXT
    : ~["\\$]+
    ;
