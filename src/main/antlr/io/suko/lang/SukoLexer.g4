lexer grammar SukoLexer;

// ============================================================
// Suko — Lexer
// Separado do parser porque ANTLR só permite `mode` em gramáticas
// puramente léxicas (gramáticas combinadas geram erro 176/120).
// ============================================================

// --- palavras-chave ---
PACKAGE   : 'package';
IMPORT    : 'import';
AS        : 'as';
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
RBRACE    : '}';
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
    : '"' -> pushMode(STRING_MODE)
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

STRING_TEXT
    : ~["\\$]+
    ;
