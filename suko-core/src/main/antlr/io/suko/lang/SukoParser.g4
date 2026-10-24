parser grammar SukoParser;

options { tokenVocab = SukoLexer; }

@members {
    // Elementos HTML5 que nunca têm filhos nem tag de fecho. Usado como
    // predicado semântico para desambiguar VoidElement de OpenElement
    // sem introduzir ambiguidade real na gramática (ver htmlElement).
    private static final java.util.Set<String> VOID_ELEMENT_NAMES = java.util.Set.of(
        "area", "base", "br", "col", "embed", "hr", "img", "input",
        "link", "meta", "source", "track", "wbr"
    );

    private boolean isVoidElementName(String name) {
        return VOID_ELEMENT_NAMES.contains(name);
    }
}

// ============================================================
// Suko — Parser
// ============================================================

compilationUnit
    : packageDecl? importDecl* componentDecl* EOF
    ;

packageDecl
    : PACKAGE packageName SEMI
    ;

importDecl
    : IMPORT packageName (AS Identifier)? SEMI
    ;

// 13b: `package app.state;` e `import app.reactive.X;` aceitam as palavras reservadas como segmento.
packageName
    : nameSegment (DOT nameSegment)*
    ;

// Na chamada de componente só o primeiro segmento é Identifier (`state ...` abriria um reactiveDecl);
// `app.state.Card()` faz parse.
qualifiedName
    : Identifier (DOT nameSegment)*
    ;

nameSegment
    : Identifier | REACTIVE | STATE | DERIVED | NEW
    ;

// --- Declaração de componente ---

componentDecl
    : PUBLIC? REACTIVE? COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN componentBody
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

// Corpo de um componente: o ÚNICO sítio onde `<!DOCTYPE html>` é aceite, e só
// como primeiro item. Regra separada de templateBlock (partilhado por
// if/for/switch) de propósito: um doctype a meio de uma página é HTML inválido.
componentBody
    : LBRACE DOCTYPE? templateStatement* RBRACE
    ;

templateBlock
    : LBRACE templateStatement* RBRACE
    ;

templateStatement
    : reactiveDecl
    | varDecl
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
// NOTA (tarefa 3): também exclui LTSLASH ("</"), não só LT. Sem isso, um
// "</algumaCoisa>" perdido (por exemplo, o resultado de o parser escolher
// a alternativa VoidElement para uma tag que na verdade não é vazia) seria
// silenciosamente engolido como texto solto em vez de gerar um erro de
// parse — e isso torna OpenElement/VoidElement genuinamente ambíguos do
// ponto de vista estrutural (fora do predicado semântico, que o ANTLR não
// consegue usar durante a predição porque depende do texto já casado
// nesta mesma invocação da regra): há sempre uma leitura alternativa do
// input em que a tag "fecha cedo" (VoidElement) e o que sobra vira texto.
// Excluindo LTSLASH, essa leitura alternativa deixa de ser válida sempre
// que exista mesmo uma tag de fecho pendente mais adiante, e a ambiguidade
// desaparece sem precisar que o predicado seja avaliado durante a
// predição — ele só entra para validar/confirmar depois da escolha.
// NOTA (subprojeto 9): EXPR_INTERP_START ("${") também tem de ser
// excluído. Este closure é guloso e não tem lookahead intermédio — sem a
// exclusão, "<p>custa ${price} euros</p>" faz o textRun engolir
// "custa ${price" como texto e o "}" sobra, silenciosamente. É a mesma
// mecânica das duas limitações já documentadas em ARCHITECTURE.md.
textRun
    : ( ~(LBRACE | RBRACE | LT | LTSLASH | EXPR_INTERP_START | DOCTYPE | INVALID_DECL) )+
    ;

// 13b: `state T x = e;`, `state var x = e;`, `derived ...`. Aceite em qualquer posição de instrução para o
// erro ser claro: o construtor do AST recusa-a fora do topo de um reactive component (PARSE_ERROR) e o
// verificador dá REACTIVE_REQUIRED num component simples.
reactiveDecl
    : kind=(STATE | DERIVED) (VAR | type) Identifier EQ expression SEMI
    ;

varDecl
    : VAR Identifier EQ expression SEMI
    ;

ifStmt
    : IF LPAREN expression RPAREN templateBlock (ELSE (ifStmt | templateBlock))?
    ;

forStmt
    : FOR LPAREN (VAR | type) Identifier COLON expression RPAREN templateBlock
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
    : qualifiedName typeArguments? LPAREN componentArgList? RPAREN slotBlock?
    ;

componentArgList
    : componentArg (COMMA componentArg)*
    ;

componentArg
    : (Identifier EQ)? (lambda | expression)
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

// A segunda forma ("row { item -> ... }") é o preenchimento de um slot
// com parâmetro (render-prop): "item" é o nome que a expressão dentro
// do template block usa para o valor passado pelo componente. Ver
// SlotParam no AST (subprojeto 1, secção "Sintaxe de slots").
namedSlot
    : Identifier LBRACE (Identifier ARROW)? templateStatement* RBRACE
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
    : htmlNamePart (MINUS htmlNamePart)*
    ;

// As palavras reservadas da 13b continuam a valer dentro de nomes HTML (`data-state`, `data-new`).
htmlNamePart
    : Identifier | REACTIVE | STATE | DERIVED | NEW
    ;

htmlElement
    : LT htmlName attribute* SLASHGT                                                                       # SelfClosingElement
    | LT open=htmlName attribute* GT {!isVoidElementName($open.text)}? templateStatement* LTSLASH close=htmlName GT  # OpenElement
    | LT tag=htmlName attribute* GT {isVoidElementName($tag.text)}?                                          # VoidElement
    ;

// 13b (Q4): `prefixo:nome` só em nomes de atributo. Os três pedaços têm de estar colados (o espaço é
// `skip`, por isso quem o garante é o construtor do AST, pelas posições). `on:`/`bind:` viram
// Statement.Directive; qualquer outro prefixo (`xlink:href`, `hx-on:click`) é um atributo HTML normal.
attrName
    : htmlName (COLON htmlName)?
    ;

attribute
    : attrName EQ stringLiteral
    | attrName EQ interpolation
    | attrName EQ lambdaInterpolation
    | attrName
    ;

lambdaInterpolation
    : EXPR_INTERP_START lambda RBRACE
    ;

// Interpolação de uma expressão. UMA regra para as três posições em que
// a linguagem interpola (subprojeto 9, D1/D2): statement/corpo de tag,
// valor de atributo sem aspas, e dentro de um literal de string
// (`stringPart`). A forma legada `{expr}` continua aceite de propósito e
// é marcada pelo label `legacy` — NÃO é para remover: removê-la da
// gramática faria o ANTLR recuperar em silêncio nos dois caminhos de
// parse que removem os error listeners (ProjectIndex e RegistryGenerator,
// C2 do plano), e no segundo isso é um manifesto gerado a partir de uma
// árvore truncada. A rejeição é semântica (SemanticChecker), não
// sintática.
//
// Nota (C5): dentro de STRING_MODE nunca existe um token LBRACE
// (STRING_TEXT cobre `{`), logo a alternativa legada é inalcançável a
// partir de `stringPart`. É deliberado, não uma ambiguidade por limpar.
interpolation
    : (EXPR_INTERP_START | legacy=LBRACE) expression RBRACE
    ;

// 13b: lambdas só como valor de atributo (`on:click=${() -> ...}`) e argumento de componente. As
// atribuições e os `++`/`--`/`+=` só existem como instruções do corpo (não são expressões).
lambda
    : lambdaParams ARROW lambdaBody
    ;

lambdaParams
    : LPAREN RPAREN
    | LPAREN Identifier RPAREN
    | Identifier
    ;

lambdaBody
    : LBRACE (clientStatement SEMI)* RBRACE
    | clientStatement
    ;

clientStatement
    : expression op=(EQ | PLUSEQ) expression         # AssignStatement
    | op=(INC | DEC) expression                      # PrefixUpdateStatement
    | expression op=(INC | DEC)                      # PostfixUpdateStatement
    | expression                                     # ExpressionStatement
    ;

// --- Expressões ---

expression
    : primary                                                # PrimaryExpr
    | NEW Identifier LPAREN argList? RPAREN                   # NewExpr
    | expression QDOT memberName                             # SafeAccessExpr
    | expression DOT memberName                              # AccessExpr
    | expression LPAREN argList? RPAREN                       # CallExpr
    | NOT expression                                          # NotExpr
    | MINUS expression                                        # UnaryMinusExpr
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

// Um record Java pode ter um componente chamado `state` (`order.state()`): as palavras da 13b valem como membro.
memberName
    : Identifier | REACTIVE | STATE | DERIVED
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
    | interpolation
    | SIMPLE_DOLLAR
    ;
