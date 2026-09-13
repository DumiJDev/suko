parser grammar SukoParser;

options { tokenVocab = SukoLexer; }

// ============================================================
// Suko — Parser
// ============================================================

compilationUnit
    : packageDecl? importDecl* componentDecl* EOF
    ;

packageDecl
    : PACKAGE qualifiedName SEMI
    ;

importDecl
    : IMPORT qualifiedName (AS Identifier)? SEMI
    ;

qualifiedName
    : Identifier (DOT Identifier)*
    ;

// --- Declaração de componente ---

componentDecl
    : COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN templateBlock
    ;

typeParameters
    : LT typeParameter (COMMA typeParameter)* GT
    ;

typeParameter
    : Identifier (COLON type)?
    ;

paramList
    : param (COMMA param)*
    ;

param
    : type Identifier (EQ expression)?
    ;

type
    : Identifier typeArguments? arrayMarker*
    ;

typeArguments
    : LT type (COMMA type)* GT
    ;

arrayMarker
    : LBRACKET RBRACKET
    ;

// --- Corpo do componente ---

templateBlock
    : LBRACE templateStatement* RBRACE
    ;

templateStatement
    : varDecl
    | ifStmt
    | forStmt
    | switchStmt
    | componentCall
    | htmlElement
    | interpolation
    | textRun
    ;

// Texto puro dentro do corpo de um componente ou de uma tag.
// Resolvido inteiramente no PARSER: como forStmt/ifStmt/switchStmt/
// componentCall são tentados ANTES (na ordem da alternação acima),
// o ANTLR só cai em textRun quando o restante dos tokens não forma
// nenhuma dessas construções — ou seja, "for"/"if"/"var" usados
// como palavras soltas em texto (ex: "espere for a confirmação")
// continuam funcionando, porque o parser vê que não há "(" logo
// depois formando um for-loop de verdade, e recua para textRun.
// O texto literal final é recuperado depois, no AST builder, pela
// posição de caractere no fonte (start/stop do token stream), não
// pela concatenação ingênua do texto de cada token — isso preserva
// espaçamento e hifenização exatamente como no .sk original.
textRun
    : ( ~(LBRACE | RBRACE | LT) )+
    ;

varDecl
    : VAR Identifier EQ expression SEMI
    ;

ifStmt
    : IF LPAREN expression RPAREN templateBlock (ELSE (ifStmt | templateBlock))?
    ;

forStmt
    : FOR LPAREN type Identifier COLON expression RPAREN templateBlock
    ;

switchStmt
    : SWITCH LPAREN expression RPAREN LBRACE switchCase* defaultCase? RBRACE
    ;

switchCase
    : CASE expression ARROW (templateBlock | expression SEMI)
    ;

defaultCase
    : DEFAULT ARROW (templateBlock | expression SEMI)
    ;

// --- Chamada de componente, estilo Compose/Flutter ---

componentCall
    : qualifiedName typeArguments? LPAREN argList? RPAREN slotBlock?
    ;

argList
    : arg (COMMA arg)*
    ;

arg
    : (Identifier EQ)? expression
    ;

slotBlock
    : LBRACE (namedSlot | templateStatement)* RBRACE
    ;

namedSlot
    : Identifier templateBlock
    ;

// --- Elementos HTML crus ---
// NOTA: a checagem de que a tag de abertura e a de fechamento
// coincidem não é expressável em BNF puro — fica para a análise
// semântica (fase pós-parse).

// Nomes de tag/atributo HTML podem ter hífen (data-id, my-button), ao
// contrário de identificadores de expressão Java (onde "a-b" é
// subtração). Por isso esta regra é do PARSER, não do lexer: reconstrói
// o nome a partir de Identifier/MINUS já lexados separadamente, em vez
// de alargar o Identifier léxico (o que quebraria "a-b" em expressões).
htmlName
    : Identifier (MINUS Identifier)*
    ;

htmlElement
    : LT htmlName attribute* SLASHGT                                        # SelfClosingElement
    | LT htmlName attribute* GT templateStatement* LTSLASH htmlName GT       # OpenElement
    ;

attribute
    : htmlName EQ stringLiteral
    | htmlName EQ LBRACE expression RBRACE
    | htmlName
    ;

// Interpolação de nível de statement, dentro ou fora de uma tag.
interpolation
    : LBRACE expression RBRACE
    ;

// --- Expressões ---

expression
    : primary                                                # PrimaryExpr
    | expression QDOT Identifier                             # SafeAccessExpr
    | expression DOT Identifier                              # AccessExpr
    | expression LPAREN argList? RPAREN                       # CallExpr
    | NOT expression                                          # NotExpr
    | expression op=(STAR|SLASH|PERCENT) expression           # MulExpr
    | expression op=(PLUS|MINUS) expression                   # AddExpr
    | expression op=(LT|LE|GT|GE) expression                  # RelExpr
    | expression op=(EQEQ|NEQ) expression                     # EqExpr
    | expression AND expression                               # AndExpr
    | expression OR expression                                # OrExpr
    | expression QCOLON expression                            # ElvisExpr
    | expression QUESTION expression COLON expression         # TernaryExpr
    | LPAREN expression RPAREN                                 # ParenExpr
    ;

primary
    : Identifier
    | stringLiteral
    | IntegerLiteral
    | BooleanLiteral
    | NULLLIT
    ;

stringLiteral
    : STRING_START stringPart* STRING_END
    ;

stringPart
    : STRING_TEXT
    | STRING_ESCAPE
    | SIMPLE_INTERP_START
    | EXPR_INTERP_START expression RBRACE
    ;
