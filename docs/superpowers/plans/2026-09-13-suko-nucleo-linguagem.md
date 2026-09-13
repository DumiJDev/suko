# Suko — Núcleo da Linguagem Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Corrigir a gramática ANTLR do Suko, construir o AST tipado, o `SukoAstBuilder` e o `JteEmitter`, de forma que um componente com generics, controlo de fluxo, composição e slots (incluindo slots com parâmetro) compile de `.sk` para `.jte` e renderize de verdade via `gg.jte`.

**Architecture:** Pipeline em 3 fases sobre o parse tree já gerado pelo ANTLR: (1) `SukoAstBuilder` traduz o `ParseTree` num AST tipado imutável (records Java 21, dispatch via `switch` com pattern matching); (2) `JteEmitter` visita o AST e produz texto `.jte`; (3) o `.jte` gerado é compilado e renderizado pelo motor `gg.jte` real nos testes de integração. Cada tarefa é uma fatia vertical: gramática → AST → emitter → render, sempre terminando num teste que renderiza HTML de verdade.

**Tech Stack:** Java 21 (records, pattern matching em `switch` — JEP 441, sem preview), ANTLR 4.13.1, `gg.jte:jte:3.1.12`, JUnit 5.

**Spec:** `docs/superpowers/specs/2026-09-13-suko-nucleo-linguagem-design.md`

## Global Constraints

- Java 21 é o mínimo suportado (decisão do brainstorming) — todo o código novo pode usar records e pattern matching em `switch`.
- Versões pinadas no `build.gradle.kts` não mudam: `org.antlr:antlr4:4.13.1`, `gg.jte:jte:3.1.12`. Nenhuma tarefa deste plano precisa de nova dependência: `implementation("gg.jte:jte:3.1.12")` já fica disponível em `testImplementation`/runtime de teste por herança padrão das configurações do plugin `java` do Gradle.
- Escopo estrito deste subprojeto: gramática, AST, `SukoAstBuilder`, `JteEmitter`, source map. Nenhum diagnóstico voltado ao utilizador (isso é subprojeto 2/3); nenhum plugin de build/watch (subprojeto 4).
- 1 componente Suko → 1 template `.jte` (decisão do `ARCHITECTURE.md`).
- Slots são uma variante de `Param` (`slot<T>`), nunca uma declaração à parte.
- Depois de qualquer alteração às gramáticas, correr `gradle generateSukoLexer generateSukoParser --console=plain` e confirmar que a saída não contém a palavra `warning` — é a prática já estabelecida no projeto (`ARCHITECTURE.md`).

## Nota sobre duas correções da gramática

Ao detalhar as tarefas 4 e 6, a descrição solta da spec ("resolver via mecanismo de fallback léxico", "só deve valer dentro de contexto de expressão") não correspondia a nenhum mecanismo real, porque o lexer do ANTLR não tem noção de "contexto do parser" — só vê caracteres à frente. As soluções concretas abaixo substituem essa descrição:

- **Comentários de linha (`//`) vs. URL em texto (tarefa 4):** `//` só é tratado como comentário quando é seguido de espaço/tab, ou quando é seguido imediatamente de fim de linha (comentário vazio). Isto cobre o estilo real de comentário (`// texto`) e não é ambíguo com `http://`, porque nenhuma URL tem espaço logo a seguir a `//`. **Limitação aceite:** um `//` sozinho exatamente no fim do ficheiro, sem newline a seguir, não é reconhecido como comentário — caso raro, documentado no código.
- **Aspas soltas em texto (tarefa 6):** em vez de tentar tornar `"` sensível ao contexto do parser (impossível sem duplicar o lexer inteiro num modo novo — exatamente o que o `ARCHITECTURE.md` já rejeitou), uso um predicado semântico do ANTLR que olha para a frente: um `"` só abre string se encontrar um `"` de fecho antes de cruzar `<`, `>` ou uma quebra de linha. Isto cobre todos os literais de string reais do Suko (curtos, numa linha, sem `<`/`>`) e trata aspas soltas em prosa como texto comum. **Limitação aceite:** um literal de string Suko não pode conter `<` ou `>` literal.

Ambas são mecanismos padrão do ANTLR4 (regras lexicais com comandos por alternativa; predicados semânticos com `_input.LA(n)`), não workarounds frágeis, mas mudam o que a v1 promete. Sinalizado ao utilizador fora deste documento.

---

### Task 1: Gramática — literal booleano

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4`
- Test: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java` (novo ficheiro, reutilizado pelas tarefas 2–8)

**Interfaces:**
- Produces: `SukoGrammarFixesTest.parseErrors(String source): List<String>` — helper reutilizado por todas as tarefas de gramática seguintes.

- [ ] **Step 1: Write the failing test**

```java
package io.suko.lang;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Casos-limite da gramática que a sondagem manual (ver ARCHITECTURE.md /
 * spec do subprojeto 1) encontrou como falhas. Cada teste corresponde a
 * uma correção pontual na gramática — só verifica ausência de erro de
 * parsing, não a árvore resultante (isso é coberto pelos testes de
 * AST/emitter mais à frente).
 */
class SukoGrammarFixesTest {

    static List<String> parseErrors(String source) {
        List<String> errors = new ArrayList<>();
        BaseErrorListener collector = new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                     int line, int charPositionInLine, String msg,
                                     RecognitionException e) {
                errors.add(line + ":" + charPositionInLine + " " + msg);
            }
        };

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        lexer.removeErrorListeners();
        lexer.addErrorListener(collector);

        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        parser.addErrorListener(collector);

        parser.compilationUnit();
        return errors;
    }

    @Test
    void booleanLiteralInExpression() {
        List<String> errors = parseErrors("component A() { if (true) { <p>x</p> } }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: FAIL — `booleanLiteralInExpression` falha porque `true` gera o token `TRUELIT`, não `BooleanLiteral`, e `primary` só aceita `BooleanLiteral`.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoLexer.g4`, substituir:

```antlr
NULLLIT   : 'null';
TRUELIT   : 'true';
FALSELIT  : 'false';

BooleanLiteral
    : TRUELIT | FALSELIT
    ;
```

por:

```antlr
NULLLIT   : 'null';

BooleanLiteral
    : 'true' | 'false'
    ;
```

(`TRUELIT`/`FALSELIT` não eram referenciados em mais nenhum sítio da gramática — eram tokens órfãos.)

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: nenhuma linha contendo `warning` na saída.

- [ ] **Step 6: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoLexer.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): literal booleano true/false em expressões"
```

---

### Task 2: Gramática — nomes de tag/atributo com hífen

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java` (adicionar método)

**Interfaces:**
- Produces: regra `htmlName` no parser, usada por `htmlElement`/`attribute` — as tarefas 3, 9 e 15 (emissão de HTML) dependem dela.

- [ ] **Step 1: Write the failing test**

Adicionar a `SukoGrammarFixesTest.java`:

```java
    @Test
    void hyphenatedTagAndAttributeNames() {
        List<String> errors = parseErrors(
            "component A() { <div data-id=\"1\">x</div> <my-button>y</my-button> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.hyphenatedTagAndAttributeNames" --console=plain`
Expected: FAIL — `no viable alternative at input '<divdata-'` (ou erro equivalente), porque `Identifier` não aceita `-`.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoParser.g4`, adicionar a nova regra antes de `htmlElement`:

```antlr
// Nomes de tag/atributo HTML podem ter hífen (data-id, my-button), ao
// contrário de identificadores de expressão Java (onde "a-b" é
// subtração). Por isso esta regra é do PARSER, não do lexer: reconstrói
// o nome a partir de Identifier/MINUS já lexados separadamente, em vez
// de alargar o Identifier léxico (o que quebraria "a-b" em expressões).
htmlName
    : Identifier (MINUS Identifier)*
    ;
```

E substituir `htmlElement`/`attribute` (usando `Identifier` para nome de tag/atributo) por:

```antlr
htmlElement
    : LT htmlName attribute* SLASHGT                                        # SelfClosingElement
    | LT htmlName attribute* GT templateStatement* LTSLASH htmlName GT       # OpenElement
    ;

attribute
    : htmlName EQ stringLiteral
    | htmlName EQ LBRACE expression RBRACE
    | htmlName
    ;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS (incluindo os testes anteriores, sem regressão)

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning` na saída.

- [ ] **Step 6: Run full existing test suite (regression check)**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL — `SukoParserSmokeTest` (Card.sk) continua a passar.

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): nomes de tag/atributo HTML com hífen"
```

---

### Task 3: Gramática — void elements

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

**Interfaces:**
- Produces: alternativa rotulada `VoidElement` em `htmlElement` (contexto gerado: `SukoParser.VoidElementContext`) — a tarefa 9 (emissão de HTML) precisa de tratar este caso além de `SelfClosingElement`/`OpenElement`.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void voidElementsWithoutSelfClosingSlash() {
        List<String> errors = parseErrors(
            "component A() { <p>a<br>b</p> <input type=\"text\"> </div> }"
                .replace(" </div>", "")); // sem tag de fecho pendurada
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.voidElementsWithoutSelfClosingSlash" --console=plain`
Expected: FAIL — `no viable alternative at input '</p>}'` ou equivalente, porque `<br>` exige `/>` ou uma tag de fecho.

- [ ] **Step 3: Fix the grammar**

No topo de `src/main/antlr/io/suko/lang/SukoParser.g4`, logo após `options { ... }`, adicionar:

```antlr
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
```

E substituir `htmlElement` (da tarefa 2) por:

```antlr
htmlElement
    : LT htmlName attribute* SLASHGT                                                                       # SelfClosingElement
    | LT open=htmlName attribute* GT {!isVoidElementName($open.text)}? templateStatement* LTSLASH close=htmlName GT  # OpenElement
    | LT tag=htmlName attribute* GT {isVoidElementName($tag.text)}?                                          # VoidElement
    ;
```

O predicado é avaliado assim que `htmlName` é conhecido (mesmo prefixo para as duas últimas alternativas), por isso não há ambiguidade real: `OpenElement` só é tentado para nomes que não estão na lista de void elements, e vice-versa.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning` — se o ANTLR reportar ambiguidade entre `OpenElement`/`VoidElement`, o predicado não está a ser avaliado durante a predição; nesse caso, mover o predicado para logo depois do `htmlName` (antes do `attribute*`) usando dois rótulos de `htmlName` iguais é o próximo passo, mas o desenho acima é o padrão documentado do ANTLR4 para este caso (predicados no ponto de bifurcação real da alternativa).

- [ ] **Step 6: Run full existing test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): elementos HTML vazios (br, img, input, ...) sem exigir />"
```

---

### Task 4: Gramática — comentário de linha vs. URL em texto

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void urlInsideTextIsNotTreatedAsComment() {
        List<String> errors = parseErrors(
            "component A() {\n  <p>Veja http://x.com agora</p>\n}");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void lineCommentsStillWork() {
        List<String> errors = parseErrors(
            "// comentário de topo\ncomponent A() {\n  <p>x</p> // comentário no fim da linha\n}");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.urlInsideTextIsNotTreatedAsComment" --console=plain`
Expected: FAIL — `//x.com agora</p>` é consumido como comentário, deixando o `}` da linha seguinte órfão.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoLexer.g4`, substituir:

```antlr
LINE_COMMENT
    : '//' ~[\r\n]* -> skip
    ;
```

por:

```antlr
// "//" só conta como comentário quando seguido de espaço/tab (estilo
// "// texto") ou de quebra de linha imediata (comentário vazio). Isto
// distingue de propósito "// comentário" de "http://x.com" em texto —
// nenhuma URL tem espaço logo depois de "//". Limitação aceite: um "//"
// sozinho no fim absoluto do ficheiro (sem newline a seguir) não conta
// como comentário.
LINE_COMMENT
    : '//' [ \t] ~[\r\n]*
    | '//' '\r'? '\n'
    -> skip
    ;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning`.

- [ ] **Step 6: Run full existing test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoLexer.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): // só é comentário seguido de espaço ou fim de linha"
```

---

### Task 5: Gramática — `$` sozinho dentro de string

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4`
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void dollarSignWithoutIdentifierInString() {
        List<String> errors = parseErrors(
            "component A(String p = \"R$ 10\") { <p>x</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.dollarSignWithoutIdentifierInString" --console=plain`
Expected: FAIL — `token recognition error at: '$ '`.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoLexer.g4`, dentro de `mode STRING_MODE;`, adicionar logo após `SIMPLE_INTERP_START`:

```antlr
// "$" sem identificador a seguir (ex: "R$ 10") não é início de
// interpolação — é texto literal. Como SIMPLE_INTERP_START/
// EXPR_INTERP_START exigem pelo menos mais um carácter e casam mais
// texto quando aplicável, esta regra só entra em jogo quando nenhuma
// delas casa (maximal-munch do ANTLR já resolve a prioridade).
SIMPLE_DOLLAR
    : '$'
    ;
```

Em `src/main/antlr/io/suko/lang/SukoParser.g4`, adicionar a alternativa em `stringPart`:

```antlr
stringPart
    : STRING_TEXT
    | STRING_ESCAPE
    | SIMPLE_INTERP_START
    | EXPR_INTERP_START expression RBRACE
    | SIMPLE_DOLLAR
    ;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning`.

- [ ] **Step 6: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoLexer.g4 src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): \$ sem identificador dentro de string literal"
```

---

### Task 6: Gramática — aspas soltas em texto

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoLexer.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void looseQuoteInTagTextIsPlainText() {
        List<String> errors = parseErrors(
            "component A() { <p>Ecrã de 5\" polegadas</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }

    @Test
    void realStringLiteralsStillWork() {
        // regressão: garante que o predicado não quebra strings normais
        List<String> errors = parseErrors(
            "component A(String label = \"Dashboard\") { <p>{label}</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.looseQuoteInTagTextIsPlainText" --console=plain`
Expected: FAIL — o `"` de `5"` abre `STRING_MODE` e consome o resto do ficheiro.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoLexer.g4`, no topo do ficheiro (antes das regras de palavra-chave), adicionar:

```antlr
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
```

E substituir:

```antlr
STRING_START
    : '"' -> pushMode(STRING_MODE)
    ;
```

por:

```antlr
STRING_START
    : '"' {canStartStringLiteral()}? -> pushMode(STRING_MODE)
    ;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning`.

- [ ] **Step 6: Run full existing test suite (garante que Card.sk continua válido)**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoLexer.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): aspas soltas em texto de tag não abrem string literal"
```

---

### Task 7: Gramática — menos unário

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

**Interfaces:**
- Produces: alternativa rotulada `UnaryMinusExpr` em `expression` — a tarefa 10 (emissão de expressões) precisa de tratar este caso.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void unaryMinus() {
        List<String> errors = parseErrors(
            "component A(int x) { <p>{-1}</p> <p>{-x}</p> }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.unaryMinus" --console=plain`
Expected: FAIL — `extraneous input '-' expecting {...}`.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoParser.g4`, na regra `expression`, adicionar `UnaryMinusExpr` logo a seguir a `NotExpr` (mesma precedência tight-binding, mesmo padrão já usado por `NotExpr` ao lado dos operadores binários):

```antlr
expression
    : primary                                                # PrimaryExpr
    | expression QDOT Identifier                             # SafeAccessExpr
    | expression DOT Identifier                              # AccessExpr
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS — todos os testes de `SukoGrammarFixesTest` (tarefas 1–7) passam.

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning`.

- [ ] **Step 6: Run full existing test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "fix(grammar): menos unário em expressões"
```

---

### Task 8: Gramática — sintaxe de preenchimento de slot com parâmetro (render-prop)

**Files:**
- Modify: `src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify: `src/test/java/io/suko/lang/SukoGrammarFixesTest.java`

**Interfaces:**
- Produces: `namedSlot: Identifier (Identifier ARROW)? templateBlock` — a tarefa 18 (emissão de slots render-prop) depende desta forma da gramática.

- [ ] **Step 1: Write the failing test**

```java
    @Test
    void renderPropSlotFillSyntax() {
        List<String> errors = parseErrors(
            "component A() { Foo() { row { item -> <li>{item}</li> } } } component Foo() { }");
        assertTrue(errors.isEmpty(), "Erros: " + errors);
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest.renderPropSlotFillSyntax" --console=plain`
Expected: FAIL — a gramática atual não aceita `Identifier ARROW` dentro de `namedSlot`.

- [ ] **Step 3: Fix the grammar**

Em `src/main/antlr/io/suko/lang/SukoParser.g4`, substituir:

```antlr
namedSlot
    : Identifier templateBlock
    ;
```

por:

```antlr
// A segunda forma ("row { item -> ... }") é o preenchimento de um slot
// com parâmetro (render-prop): "item" é o nome que a expressão dentro
// do templateBlock usa para o valor passado pelo componente. Ver
// SlotParam no AST (subprojeto 1, secção "Sintaxe de slots").
namedSlot
    : Identifier (Identifier ARROW)? templateBlock
    ;
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoGrammarFixesTest" --console=plain`
Expected: PASS — todas as 9 (1+8) verificações de `SukoGrammarFixesTest` passam.

- [ ] **Step 5: Regenerate grammar and check for warnings**

Run: `gradle generateSukoLexer generateSukoParser --console=plain`
Expected: sem `warning`.

- [ ] **Step 6: Run full existing test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL — fim da fase de correções/adições de gramática.

- [ ] **Step 7: Commit**

```bash
git add src/main/antlr/io/suko/lang/SukoParser.g4 src/test/java/io/suko/lang/SukoGrammarFixesTest.java
git commit -m "feat(grammar): sintaxe de slot com parâmetro (render-prop) no ponto de chamada"
```

---

### Task 9: AST — fundação + primeiro componente de ponta a ponta

**Files:**
- Create: `src/main/java/io/suko/lang/ast/SourceSpan.java`
- Create: `src/main/java/io/suko/lang/ast/Type.java`
- Create: `src/main/java/io/suko/lang/ast/Cardinality.java`
- Create: `src/main/java/io/suko/lang/ast/Param.java`
- Create: `src/main/java/io/suko/lang/ast/Expr.java`
- Create: `src/main/java/io/suko/lang/ast/Statement.java`
- Create: `src/main/java/io/suko/lang/ast/ComponentDecl.java`
- Create: `src/main/java/io/suko/lang/ast/SukoFile.java`
- Create: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Create: `src/main/java/io/suko/lang/JteEmitter.java`
- Create: `src/test/java/io/suko/lang/support/JteRenderSupport.java`
- Test: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `SukoAstBuilder.build(SukoParser.CompilationUnitContext): SukoFile`; `JteEmitter.emit(ComponentDecl): String` (texto `.jte`); `JteRenderSupport.render(String sukoSource, String componentName, Map<String,Object> params): String` (HTML renderizado via `gg.jte` real) — reutilizados por todas as tarefas seguintes.

- [ ] **Step 1: Create the AST core types**

`src/main/java/io/suko/lang/ast/SourceSpan.java`:

```java
package io.suko.lang.ast;

/**
 * Posição de um nó do AST no ficheiro .sk de origem. startIndex/endIndex
 * são offsets absolutos de carácter (Token.getStartIndex()/getStopIndex()
 * do ANTLR), usados pelo subprojeto 3 para mapear o stub Java de
 * verificação de volta ao .sk.
 */
public record SourceSpan(int startLine, int startColumn, int startIndex, int endIndex) {
}
```

`src/main/java/io/suko/lang/ast/Type.java`:

```java
package io.suko.lang.ast;

import java.util.List;

public record Type(String name, List<Type> typeArguments, int arrayDimensions) {

    public boolean isSlot() {
        return "slot".equals(name);
    }
}
```

`src/main/java/io/suko/lang/ast/Cardinality.java`:

```java
package io.suko.lang.ast;

public enum Cardinality { ONE, MANY }
```

`src/main/java/io/suko/lang/ast/Param.java`:

```java
package io.suko.lang.ast;

import java.util.Optional;

public sealed interface Param permits Param.ValueParam, Param.SlotParam {

    String name();

    record ValueParam(Type type, String name, Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }

    record SlotParam(Type elementType, String name, Cardinality cardinality,
                      Optional<Expr> defaultValue, SourceSpan span) implements Param {
    }
}
```

`src/main/java/io/suko/lang/ast/Expr.java` (só `PrimaryExpr` e `StringLiteralExpr` sem interpolação nesta tarefa — as restantes variantes chegam na tarefa 10):

```java
package io.suko.lang.ast;

import java.util.List;

public sealed interface Expr permits Expr.PrimaryExpr, Expr.StringLiteralExpr {

    SourceSpan span();

    /** Identificador, inteiro, booleano ou "null" — texto literal recuperado do fonte. */
    record PrimaryExpr(String text, SourceSpan span) implements Expr {
    }

    record StringLiteralExpr(List<StringPart> parts, SourceSpan span) implements Expr {
    }

    sealed interface StringPart permits StringPart.Literal {
        record Literal(String javaEscapedText) implements StringPart {
        }
    }
}
```

`src/main/java/io/suko/lang/ast/Statement.java` (só `HtmlElement`, `TextRun` e `Interpolation` nesta tarefa):

```java
package io.suko.lang.ast;

import java.util.List;

public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation {

    SourceSpan span();

    record HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                        boolean selfClosing, SourceSpan span) implements Statement {
    }

    record Attribute(String name, Expr value, SourceSpan span) {
    }

    record TextRun(String text, SourceSpan span) implements Statement {
    }

    record Interpolation(Expr expr, SourceSpan span) implements Statement {
    }
}
```

`src/main/java/io/suko/lang/ast/ComponentDecl.java`:

```java
package io.suko.lang.ast;

import java.util.List;

public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span) {
}
```

`src/main/java/io/suko/lang/ast/SukoFile.java`:

```java
package io.suko.lang.ast;

import java.util.List;
import java.util.Optional;

public record SukoFile(Optional<String> packageName, List<String> imports, List<ComponentDecl> components) {
}
```

- [ ] **Step 2: Create the AST builder for this minimal slice**

`src/main/java/io/suko/lang/SukoAstBuilder.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Traduz o ParseTree do ANTLR para o AST tipado do Suko. Tradução
 * puramente estrutural — sem verificação semântica (isso é o
 * subprojeto 2/3). A gramática já garante a forma da árvore, por isso o
 * único "erro" possível aqui é um bug interno (forma inesperada de
 * parse tree), sinalizado como IllegalStateException.
 */
public class SukoAstBuilder {

    private final String source;

    public SukoAstBuilder(String source) {
        this.source = source;
    }

    public SukoFile build(SukoParser.CompilationUnitContext ctx) {
        Optional<String> packageName = ctx.packageDecl() == null
            ? Optional.empty()
            : Optional.of(ctx.packageDecl().qualifiedName().getText());

        List<String> imports = new ArrayList<>();
        for (SukoParser.ImportDeclContext importCtx : ctx.importDecl()) {
            imports.add(importCtx.qualifiedName().getText());
        }

        List<ComponentDecl> components = new ArrayList<>();
        for (SukoParser.ComponentDeclContext componentCtx : ctx.componentDecl()) {
            components.add(buildComponent(componentCtx));
        }

        return new SukoFile(packageName, imports, components);
    }

    private ComponentDecl buildComponent(SukoParser.ComponentDeclContext ctx) {
        List<String> typeParameters = new ArrayList<>();
        if (ctx.typeParameters() != null) {
            for (SukoParser.TypeParameterContext tp : ctx.typeParameters().typeParameter()) {
                typeParameters.add(tp.Identifier().getText());
            }
        }

        List<Param> params = new ArrayList<>();
        if (ctx.paramList() != null) {
            for (SukoParser.ParamContext paramCtx : ctx.paramList().param()) {
                params.add(buildParam(paramCtx));
            }
        }

        List<Statement> body = buildStatements(ctx.templateBlock().templateStatement());

        return new ComponentDecl(ctx.Identifier().getText(), typeParameters, params, body, spanOf(ctx));
    }

    private Param buildParam(SukoParser.ParamContext ctx) {
        Type type = buildType(ctx.type());
        String name = ctx.Identifier().getText();
        Optional<Expr> defaultValue = ctx.expression() == null
            ? Optional.empty()
            : Optional.of(buildExpr(ctx.expression()));

        if (type.isSlot()) {
            Type elementType = type.typeArguments().isEmpty()
                ? new Type("Object", List.of(), 0)
                : type.typeArguments().get(0);
            return new Param.SlotParam(elementType, name, Cardinality.ONE, defaultValue, spanOf(ctx));
        }

        return new Param.ValueParam(type, name, defaultValue, spanOf(ctx));
    }

    private Type buildType(SukoParser.TypeContext ctx) {
        List<Type> typeArguments = new ArrayList<>();
        if (ctx.typeArguments() != null) {
            for (SukoParser.TypeContext argCtx : ctx.typeArguments().type()) {
                typeArguments.add(buildType(argCtx));
            }
        }
        int arrayDimensions = ctx.arrayMarker().size();
        return new Type(ctx.Identifier().getText(), typeArguments, arrayDimensions);
    }

    List<Statement> buildStatements(List<SukoParser.TemplateStatementContext> ctxs) {
        List<Statement> statements = new ArrayList<>();
        for (SukoParser.TemplateStatementContext stmtCtx : ctxs) {
            statements.add(buildStatement(stmtCtx));
        }
        return statements;
    }

    Statement buildStatement(SukoParser.TemplateStatementContext ctx) {
        if (ctx.htmlElement() != null) {
            return buildHtmlElement(ctx.htmlElement());
        }
        if (ctx.interpolation() != null) {
            return new Statement.Interpolation(buildExpr(ctx.interpolation().expression()), spanOf(ctx.interpolation()));
        }
        if (ctx.textRun() != null) {
            return new Statement.TextRun(textOf(ctx.textRun()), spanOf(ctx.textRun()));
        }
        throw new IllegalStateException("templateStatement ainda não suportado nesta tarefa: " + ctx.getText());
    }

    private Statement.HtmlElement buildHtmlElement(SukoParser.HtmlElementContext ctx) {
        if (ctx instanceof SukoParser.SelfClosingElementContext c) {
            return new Statement.HtmlElement(c.htmlName(0).getText(), buildAttributes(c.attribute()),
                List.of(), true, spanOf(ctx));
        }
        if (ctx instanceof SukoParser.OpenElementContext c) {
            return new Statement.HtmlElement(c.htmlName(0).getText(), buildAttributes(c.attribute()),
                buildStatements(c.templateStatement()), false, spanOf(ctx));
        }
        if (ctx instanceof SukoParser.VoidElementContext c) {
            return new Statement.HtmlElement(c.htmlName().getText(), buildAttributes(c.attribute()),
                List.of(), true, spanOf(ctx));
        }
        throw new IllegalStateException("Tipo de htmlElement desconhecido: " + ctx.getClass());
    }

    private List<Statement.Attribute> buildAttributes(List<SukoParser.AttributeContext> ctxs) {
        List<Statement.Attribute> attributes = new ArrayList<>();
        for (SukoParser.AttributeContext attrCtx : ctxs) {
            String name = attrCtx.htmlName().getText();
            Expr value = attrCtx.stringLiteral() != null
                ? buildStringLiteral(attrCtx.stringLiteral())
                : attrCtx.expression() != null
                    ? buildExpr(attrCtx.expression())
                    : new Expr.PrimaryExpr("true", spanOf(attrCtx));
            attributes.add(new Statement.Attribute(name, value, spanOf(attrCtx)));
        }
        return attributes;
    }

    Expr buildExpr(SukoParser.ExpressionContext ctx) {
        if (ctx instanceof SukoParser.PrimaryExprContext c) {
            SukoParser.PrimaryContext primary = c.primary();
            if (primary.stringLiteral() != null) {
                return buildStringLiteral(primary.stringLiteral());
            }
            return new Expr.PrimaryExpr(primary.getText(), spanOf(primary));
        }
        throw new IllegalStateException("Tipo de expressão ainda não suportado nesta tarefa: " + ctx.getClass());
    }

    private Expr.StringLiteralExpr buildStringLiteral(SukoParser.StringLiteralContext ctx) {
        StringBuilder javaEscaped = new StringBuilder();
        for (SukoParser.StringPartContext partCtx : ctx.stringPart()) {
            javaEscaped.append(partCtx.getText());
        }
        List<Expr.StringPart> parts = List.of(new Expr.StringPart.Literal(javaEscaped.toString()));
        return new Expr.StringLiteralExpr(parts, spanOf(ctx));
    }

    /** Recupera o texto literal de um textRun pela posição de carácter no fonte
     * (não por concatenação de tokens), preservando espaçamento exatamente como
     * no .sk original — ver ARCHITECTURE.md. */
    private String textOf(SukoParser.TextRunContext ctx) {
        int start = ctx.getStart().getStartIndex();
        int stop = ctx.getStop().getStopIndex();
        return source.substring(start, stop + 1);
    }

    private SourceSpan spanOf(org.antlr.v4.runtime.ParserRuleContext ctx) {
        return new SourceSpan(
            ctx.getStart().getLine(),
            ctx.getStart().getCharPositionInLine(),
            ctx.getStart().getStartIndex(),
            ctx.getStop().getStopIndex());
    }
}
```

- [ ] **Step 3: Create the JTE emitter for this minimal slice**

`src/main/java/io/suko/lang/JteEmitter.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.*;

/**
 * Visitor sobre o AST que produz o texto de um template .jte por
 * ComponentDecl. Tradução próxima de 1:1 — expressões e statements sem
 * necessidade de transformação são apenas reconstruídos textualmente.
 */
public class JteEmitter {

    public String emit(ComponentDecl component) {
        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param)).append('\n');
        }
        out.append('\n');
        for (Statement statement : component.body()) {
            emitStatement(statement, out);
        }
        return out.toString();
    }

    private String jteParamDeclaration(Param param) {
        return switch (param) {
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name();
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name();
            case Param.SlotParam p -> "java.util.List<gg.jte.Content> " + p.name();
        };
    }

    private String javaType(Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(javaType(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(type.arrayDimensions()));
        return sb.toString();
    }

    private void emitStatement(Statement statement, StringBuilder out) {
        switch (statement) {
            case Statement.TextRun textRun -> out.append(textRun.text());
            case Statement.Interpolation interpolation ->
                out.append("${").append(emitExpr(interpolation.expr())).append('}');
            case Statement.HtmlElement element -> emitHtmlElement(element, out);
        }
    }

    private void emitHtmlElement(Statement.HtmlElement element, StringBuilder out) {
        out.append('<').append(element.tagName());
        for (Statement.Attribute attribute : element.attributes()) {
            out.append(' ').append(attribute.name()).append("=\"")
                .append("${").append(emitExpr(attribute.value())).append('}').append('"');
        }
        if (element.selfClosing()) {
            out.append("/>");
            return;
        }
        out.append('>');
        for (Statement child : element.children()) {
            emitStatement(child, out);
        }
        out.append("</").append(element.tagName()).append('>');
    }

    String emitExpr(Expr expr) {
        return switch (expr) {
            case Expr.PrimaryExpr primary -> primary.text();
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral);
        };
    }

    private String emitStringLiteral(Expr.StringLiteralExpr stringLiteral) {
        StringBuilder sb = new StringBuilder("\"");
        for (Expr.StringPart part : stringLiteral.parts()) {
            if (part instanceof Expr.StringPart.Literal literal) {
                sb.append(literal.javaEscapedText());
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
```

Nota: os atributos são sempre emitidos como `attr="${expr}"` (mesmo para strings literais) para manter o emitter simples nesta tarefa — o JTE aceita isso sem problema. Isto é revisitado na tarefa 15 se necessário.

- [ ] **Step 4: Create the shared JTE render test support**

`src/test/java/io/suko/lang/support/JteRenderSupport.java`:

```java
package io.suko.lang.support;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import io.suko.lang.JteEmitter;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Compila um .sk (texto Suko) para .jte via SukoAstBuilder + JteEmitter,
 * escreve o resultado num diretório temporário e renderiza-o com o
 * motor gg.jte real (não simulado), devolvendo o HTML de saída. Usado
 * por todos os testes de emitter/end-to-end deste subprojeto.
 */
public final class JteRenderSupport {

    private JteRenderSupport() {
    }

    public static String compileToJte(String sukoSource, String componentName) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(sukoSource).build(parser.compilationUnit());

        ComponentDecl component = file.components().stream()
            .filter(c -> c.name().equals(componentName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Componente não encontrado: " + componentName));

        return new JteEmitter().emit(component);
    }

    public static String render(String sukoSource, String componentName, Map<String, Object> params) throws IOException {
        String jteSource = compileToJte(sukoSource, componentName);

        Path tempDir = Files.createTempDirectory("suko-jte-render");
        Files.writeString(tempDir.resolve(componentName + ".jte"), jteSource);

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(componentName + ".jte", params, output);
        return output.toString();
    }
}
```

- [ ] **Step 5: Write the failing end-to-end test**

`src/test/java/io/suko/lang/JteEmitterTest.java`:

```java
package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JteEmitterTest {

    @Test
    void rendersStaticParagraphWithInterpolatedParam() throws Exception {
        String source = """
            component Greeting(String name) {
              <p>Hello, {name}!</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Greeting", Map.of("name", "World"));

        assertEquals("<p>Hello, World!</p>\n", html);
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: FAIL antes desta tarefa (as classes ainda não existem) — depois de criar os ficheiros dos passos 1–4, corre e confirma que compila; se falhar por diferença de espaçamento no HTML de saída, ajustar a asserção ao output real do `gg.jte` (o `.jte` gerado usa `\n` entre `@param` e o corpo — o `.jte` engine preserva newlines do template tal como escritos).

- [ ] **Step 7: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 8: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/suko/lang/ast src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/support src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(ast): fundação do AST + SukoAstBuilder + JteEmitter, primeiro render end-to-end"
```

---

### Task 10: Expressões completas (aritmética, comparação, lógica, acesso, chamada, parênteses, not, menos unário)

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Expr.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `Expr` sealed interface (tarefa 9).
- Produces: `Expr` com todas as variantes exceto `SafeAccessExpr`/`ElvisExpr` (tarefa 11).

- [ ] **Step 1: Extend the Expr sealed interface**

Em `src/main/java/io/suko/lang/ast/Expr.java`, substituir a declaração `sealed interface Expr` e adicionar os novos records:

```java
public sealed interface Expr permits
    Expr.PrimaryExpr, Expr.StringLiteralExpr, Expr.AccessExpr, Expr.CallExpr,
    Expr.NotExpr, Expr.UnaryMinusExpr, Expr.BinaryExpr, Expr.TernaryExpr, Expr.ParenExpr {

    SourceSpan span();

    record PrimaryExpr(String text, SourceSpan span) implements Expr {
    }

    record StringLiteralExpr(List<StringPart> parts, SourceSpan span) implements Expr {
    }

    sealed interface StringPart permits StringPart.Literal {
        record Literal(String javaEscapedText) implements StringPart {
        }
    }

    record AccessExpr(Expr target, String memberName, SourceSpan span) implements Expr {
    }

    record CallExpr(Expr callee, List<Expr> args, SourceSpan span) implements Expr {
    }

    record NotExpr(Expr operand, SourceSpan span) implements Expr {
    }

    record UnaryMinusExpr(Expr operand, SourceSpan span) implements Expr {
    }

    /** Cobre Mul/Add/Rel/Eq/And/Or — todos com a mesma forma (operador binário Java válido). */
    record BinaryExpr(Expr left, String operator, Expr right, SourceSpan span) implements Expr {
    }

    record TernaryExpr(Expr condition, Expr whenTrue, Expr whenFalse, SourceSpan span) implements Expr {
    }

    record ParenExpr(Expr inner, SourceSpan span) implements Expr {
    }
}
```

- [ ] **Step 2: Extend SukoAstBuilder.buildExpr**

Em `src/main/java/io/suko/lang/SukoAstBuilder.java`, substituir o método `buildExpr` por:

```java
    Expr buildExpr(SukoParser.ExpressionContext ctx) {
        return switch (ctx) {
            case SukoParser.PrimaryExprContext c -> buildPrimary(c.primary());
            case SukoParser.AccessExprContext c ->
                new Expr.AccessExpr(buildExpr(c.expression()), c.Identifier().getText(), spanOf(c));
            case SukoParser.CallExprContext c -> new Expr.CallExpr(
                buildExpr(c.expression()), buildArgs(c.argList()), spanOf(c));
            case SukoParser.NotExprContext c -> new Expr.NotExpr(buildExpr(c.expression()), spanOf(c));
            case SukoParser.UnaryMinusExprContext c ->
                new Expr.UnaryMinusExpr(buildExpr(c.expression()), spanOf(c));
            case SukoParser.MulExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.AddExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.RelExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.EqExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.AndExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), "&&", buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.OrExprContext c ->
                new Expr.BinaryExpr(buildExpr(c.expression(0)), "||", buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.TernaryExprContext c -> new Expr.TernaryExpr(
                buildExpr(c.expression(0)), buildExpr(c.expression(1)), buildExpr(c.expression(2)), spanOf(c));
            case SukoParser.ParenExprContext c -> new Expr.ParenExpr(buildExpr(c.expression()), spanOf(c));
            default -> throw new IllegalStateException(
                "Tipo de expressão ainda não suportado (ver tarefa 11 para ?./?:): " + ctx.getClass());
        };
    }

    private Expr buildPrimary(SukoParser.PrimaryContext ctx) {
        if (ctx.stringLiteral() != null) {
            return buildStringLiteral(ctx.stringLiteral());
        }
        return new Expr.PrimaryExpr(ctx.getText(), spanOf(ctx));
    }

    private List<Expr> buildArgs(SukoParser.ArgListContext ctx) {
        List<Expr> args = new ArrayList<>();
        if (ctx != null) {
            for (SukoParser.ArgContext argCtx : ctx.arg()) {
                args.add(buildExpr(argCtx.expression()));
            }
        }
        return args;
    }
```

Remover o antigo corpo de `buildExpr` que só tratava `PrimaryExprContext` diretamente (substituído pela chamada a `buildPrimary` acima).

- [ ] **Step 3: Extend JteEmitter.emitExpr**

Em `src/main/java/io/suko/lang/JteEmitter.java`, substituir `emitExpr` por:

```java
    String emitExpr(Expr expr) {
        return switch (expr) {
            case Expr.PrimaryExpr primary -> primary.text();
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral);
            case Expr.AccessExpr access -> emitExpr(access.target()) + "." + access.memberName();
            case Expr.CallExpr call -> emitExpr(call.callee()) + "(" + emitArgs(call.args()) + ")";
            case Expr.NotExpr not -> "!" + emitExpr(not.operand());
            case Expr.UnaryMinusExpr unaryMinus -> "-" + emitExpr(unaryMinus.operand());
            case Expr.BinaryExpr binary ->
                emitExpr(binary.left()) + " " + binary.operator() + " " + emitExpr(binary.right());
            case Expr.TernaryExpr ternary -> emitExpr(ternary.condition()) + " ? "
                + emitExpr(ternary.whenTrue()) + " : " + emitExpr(ternary.whenFalse());
            case Expr.ParenExpr paren -> "(" + emitExpr(paren.inner()) + ")";
        };
    }

    private String emitArgs(java.util.List<Expr> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpr(args.get(i)));
        }
        return sb.toString();
    }
```

- [ ] **Step 4: Write the failing test**

Adicionar a `src/test/java/io/suko/lang/JteEmitterTest.java`:

```java
    @Test
    void rendersArithmeticAndComparisonExpressions() throws Exception {
        String source = """
            component Sum(int a, int b) {
              <p>{a + b}</p>
              <p>{(a - b) * 2}</p>
              <p>{a > b}</p>
            }
            """;

        String html = JteRenderSupport.render(source, "Sum", Map.of("a", 5, "b", 3));

        assertEquals("<p>8</p>\n  <p>4</p>\n  <p>true</p>\n", html);
    }
```

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersArithmeticAndComparisonExpressions" --console=plain`
Expected: FAIL (compilação falha até os passos 1–3 estarem aplicados; depois disso, ajustar a asserção ao whitespace real emitido pelo `.jte` gerado, já que `textRun` preserva os espaços/newlines literais entre tags do `.sk` de origem).

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS (ambos os testes da classe)

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Expr.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): expressões aritméticas, de comparação, lógicas, acesso e chamada"
```

---

### Task 11: Null-safety — `?.` e `?:`

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Expr.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `Expr.SafeAccessExpr`, `Expr.ElvisExpr` — desaçucarados na emissão para Java puro, já que o JTE (Java) não tem estes operadores nativamente (ver spec).

- [ ] **Step 1: Extend Expr**

Em `src/main/java/io/suko/lang/ast/Expr.java`, adicionar `Expr.SafeAccessExpr` e `Expr.ElvisExpr` à lista de `permits` e os records:

```java
    record SafeAccessExpr(Expr target, String memberName, SourceSpan span) implements Expr {
    }

    record ElvisExpr(Expr left, Expr right, SourceSpan span) implements Expr {
    }
```

- [ ] **Step 2: Extend SukoAstBuilder.buildExpr**

Adicionar aos `case` de `buildExpr` em `src/main/java/io/suko/lang/SukoAstBuilder.java`:

```java
            case SukoParser.SafeAccessExprContext c ->
                new Expr.SafeAccessExpr(buildExpr(c.expression()), c.Identifier().getText(), spanOf(c));
            case SukoParser.ElvisExprContext c ->
                new Expr.ElvisExpr(buildExpr(c.expression(0)), buildExpr(c.expression(1)), spanOf(c));
```

(antes do `default ->`, que passa a só cobrir casos genuinamente não implementados).

- [ ] **Step 3: Extend JteEmitter — desugar para Java puro**

Em `src/main/java/io/suko/lang/JteEmitter.java`, adicionar ao `switch` de `emitExpr`:

```java
            case Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target());
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName() + ")";
            }
            case Expr.ElvisExpr elvis -> {
                String left = emitExpr(elvis.left());
                yield "(" + left + " == null ? " + emitExpr(elvis.right()) + " : " + left + ")";
            }
```

Nota: esta forma reavalia `target`/`left` textualmente duas vezes. Para o subconjunto de expressões que o Suko aceita (acessos e chamadas simples, sem efeitos secundários visíveis — a linguagem não tem atribuição dentro de expressões), isto é seguro; um `switch` em Java teria de introduzir uma variável local, o que não é necessário aqui.

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersNullSafeAccessAndElvis() throws Exception {
        String source = """
            component Price(String label) {
              <p>{label?.length() ?: -1}</p>
            }
            """;

        String withValue = JteRenderSupport.render(source, "Price", java.util.Collections.singletonMap("label", "abc"));
        String withNull = JteRenderSupport.render(source, "Price", java.util.Collections.singletonMap("label", null));

        assertEquals("<p>3</p>\n", withValue);
        assertEquals("<p>-1</p>\n", withNull);
    }
```

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersNullSafeAccessAndElvis" --console=plain`
Expected: FAIL antes dos passos 1–3 (tipo de expressão não suportado).

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Expr.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): desaçucarar ?. e ?: para Java puro"
```

---

### Task 12: Controlo de fluxo — if/else

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `Statement.IfStmt(Expr condition, List<Statement> thenBranch, List<Statement> elseBranch)`.

- [ ] **Step 1: Extend Statement**

Em `src/main/java/io/suko/lang/ast/Statement.java`, adicionar `Statement.IfStmt` a `permits` e:

```java
    record IfStmt(Expr condition, List<Statement> thenBranch, List<Statement> elseBranch,
                  SourceSpan span) implements Statement {
    }
```

- [ ] **Step 2: Extend SukoAstBuilder.buildStatement**

Em `src/main/java/io/suko/lang/SukoAstBuilder.java`, substituir `buildStatement` por (adicionando o ramo `ifStmt`):

```java
    Statement buildStatement(SukoParser.TemplateStatementContext ctx) {
        if (ctx.ifStmt() != null) {
            return buildIfStmt(ctx.ifStmt());
        }
        if (ctx.htmlElement() != null) {
            return buildHtmlElement(ctx.htmlElement());
        }
        if (ctx.interpolation() != null) {
            return new Statement.Interpolation(buildExpr(ctx.interpolation().expression()), spanOf(ctx.interpolation()));
        }
        if (ctx.textRun() != null) {
            return new Statement.TextRun(textOf(ctx.textRun()), spanOf(ctx.textRun()));
        }
        throw new IllegalStateException("templateStatement ainda não suportado: " + ctx.getText());
    }

    private Statement.IfStmt buildIfStmt(SukoParser.IfStmtContext ctx) {
        Expr condition = buildExpr(ctx.expression());
        List<Statement> thenBranch = buildStatements(ctx.templateBlock(0).templateStatement());

        List<Statement> elseBranch;
        if (ctx.ifStmt() != null) {
            elseBranch = List.of(buildIfStmt(ctx.ifStmt()));
        } else if (ctx.templateBlock().size() > 1) {
            elseBranch = buildStatements(ctx.templateBlock(1).templateStatement());
        } else {
            elseBranch = List.of();
        }

        return new Statement.IfStmt(condition, thenBranch, elseBranch, spanOf(ctx));
    }
```

- [ ] **Step 3: Extend JteEmitter.emitStatement**

Em `src/main/java/io/suko/lang/JteEmitter.java`, adicionar ao `switch` de `emitStatement`:

```java
            case Statement.IfStmt ifStmt -> emitIfStmt(ifStmt, out);
```

E o novo método auxiliar:

```java
    private void emitIfStmt(Statement.IfStmt ifStmt, StringBuilder out) {
        out.append("@if(").append(emitExpr(ifStmt.condition())).append(")\n");
        for (Statement statement : ifStmt.thenBranch()) {
            emitStatement(statement, out);
        }
        if (!ifStmt.elseBranch().isEmpty()) {
            out.append("\n@else\n");
            for (Statement statement : ifStmt.elseBranch()) {
                emitStatement(statement, out);
            }
        }
        out.append("\n@endif\n");
    }
```

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersIfElse() throws Exception {
        String source = """
            component Status(boolean ok) {
              if (ok) {
                <p>Tudo bem</p>
              } else {
                <p>Falhou</p>
              }
            }
            """;

        assertEquals("<p>Tudo bem</p>\n", stripJteControlLines(JteRenderSupport.render(source, "Status", Map.of("ok", true))));
        assertEquals("<p>Falhou</p>\n", stripJteControlLines(JteRenderSupport.render(source, "Status", Map.of("ok", false))));
    }

    private static String stripJteControlLines(String html) {
        return html.lines().filter(line -> !line.isBlank()).reduce("", (a, b) -> a + b + "\n");
    }
```

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersIfElse" --console=plain`
Expected: FAIL antes dos passos 1–3.

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS — se o whitespace não bater certo com `stripJteControlLines`, inspecionar o HTML real impresso pela falha e ajustar a asserção (o `@if`/`@endif` do JTE não emite texto próprio, mas as quebras de linha do `.jte` gerado à sua volta sim).

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): if/else"
```

---

### Task 13: Controlo de fluxo — for + generics do componente

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `Statement.ForStmt(Type itemType, String itemName, Expr iterable, List<Statement> body)`. `ComponentDecl.typeParameters()` (já existe desde a tarefa 9) passa a ser efetivamente exercitado.

- [ ] **Step 1: Extend Statement**

Em `src/main/java/io/suko/lang/ast/Statement.java`, adicionar a `permits`:

```java
    record ForStmt(Type itemType, String itemName, Expr iterable, List<Statement> body,
                   SourceSpan span) implements Statement {
    }
```

- [ ] **Step 2: Extend SukoAstBuilder**

Adicionar ao início de `buildStatement` (antes do `if (ctx.ifStmt() ...)`):

```java
        if (ctx.forStmt() != null) {
            return buildForStmt(ctx.forStmt());
        }
```

E o novo método:

```java
    private Statement.ForStmt buildForStmt(SukoParser.ForStmtContext ctx) {
        return new Statement.ForStmt(
            buildType(ctx.type()),
            ctx.Identifier().getText(),
            buildExpr(ctx.expression()),
            buildStatements(ctx.templateBlock().templateStatement()),
            spanOf(ctx));
    }
```

Tornar `buildType` acessível (já é privado no mesmo ficheiro, sem alteração de visibilidade necessária).

- [ ] **Step 3: Extend JteEmitter**

Adicionar ao `switch` de `emitStatement`:

```java
            case Statement.ForStmt forStmt -> emitForStmt(forStmt, out);
```

E:

```java
    private void emitForStmt(Statement.ForStmt forStmt, StringBuilder out) {
        out.append("@for(").append(javaType(forStmt.itemType())).append(' ').append(forStmt.itemName())
            .append(" : ").append(emitExpr(forStmt.iterable())).append(")\n");
        for (Statement statement : forStmt.body()) {
            emitStatement(statement, out);
        }
        out.append("\n@endfor\n");
    }
```

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersForLoopOverGenericList() throws Exception {
        String source = """
            component Items<T>(java.util.List<T> items) {
              <ul>
              for (T item : items) {
                <li>{item}</li>
              }
              </ul>
            }
            """;

        String html = JteRenderSupport.render(source, "Items", Map.of("items", java.util.List.of("a", "b", "c")));

        assertTrue(html.contains("<li>a</li>"));
        assertTrue(html.contains("<li>b</li>"));
        assertTrue(html.contains("<li>c</li>"));
    }
```

(usar `java.util.List<T>` explicitamente no `.sk` de teste, já que o Suko não tem imports automáticos de `java.util.*` — isso fica para o subprojeto 2/verificador; nesta camada o emitter só copia o texto do tipo tal como escrito.)

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersForLoopOverGenericList" --console=plain`
Expected: FAIL antes dos passos 1–3. Note: `assertTrue` precisa de `import static org.junit.jupiter.api.Assertions.assertTrue;` no topo do ficheiro de teste, além do `assertEquals` já existente.

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): for loops sobre listas genéricas"
```

---

### Task 14: Controlo de fluxo — switch/case/default

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `Statement.SwitchStmt(Expr subject, List<SwitchCase> cases, Optional<List<Statement>> defaultCase)`, `Statement.SwitchCase(Expr matchValue, List<Statement> body)`.

- [ ] **Step 1: Extend Statement**

Em `src/main/java/io/suko/lang/ast/Statement.java`, adicionar a `permits` `Statement.SwitchStmt` e:

```java
    record SwitchStmt(Expr subject, List<SwitchCase> cases, List<Statement> defaultCase,
                      SourceSpan span) implements Statement {
    }

    record SwitchCase(Expr matchValue, List<Statement> body) {
    }
```

(`defaultCase` vazio = `default` ausente; a checagem de exaustividade é semântica, fora deste subprojeto.)

- [ ] **Step 2: Extend SukoAstBuilder**

Adicionar ao início de `buildStatement`:

```java
        if (ctx.switchStmt() != null) {
            return buildSwitchStmt(ctx.switchStmt());
        }
```

E:

```java
    private Statement.SwitchStmt buildSwitchStmt(SukoParser.SwitchStmtContext ctx) {
        Expr subject = buildExpr(ctx.expression());

        List<Statement.SwitchCase> cases = new ArrayList<>();
        for (SukoParser.SwitchCaseContext caseCtx : ctx.switchCase()) {
            cases.add(new Statement.SwitchCase(buildExpr(caseCtx.expression()), buildCaseBody(
                caseCtx.templateBlock(), caseCtx.expression(1))));
        }

        List<Statement> defaultCase = ctx.defaultCase() == null
            ? List.of()
            : buildCaseBody(ctx.defaultCase().templateBlock(), ctx.defaultCase().expression());

        return new Statement.SwitchStmt(subject, cases, defaultCase, spanOf(ctx));
    }

    private List<Statement> buildCaseBody(SukoParser.TemplateBlockContext blockCtx, SukoParser.ExpressionContext exprCtx) {
        if (blockCtx != null) {
            return buildStatements(blockCtx.templateStatement());
        }
        // "case X -> expression;" — trata a expressão como uma única interpolação.
        return List.of(new Statement.Interpolation(buildExpr(exprCtx), spanOf(exprCtx)));
    }
```

Nota: `switchCase: CASE expression ARROW (templateBlock | expression SEMI)` tem dois usos possíveis de `expression()` (o valor do case e, opcionalmente, a expressão-corpo) — `caseCtx.expression(1)` só existe quando não há `templateBlock`; como `buildCaseBody` só usa `exprCtx` nesse ramo, está correto mesmo que `expression(1)` seja `null` quando há `templateBlock` (o parâmetro não é avaliado nesse caso, curto-circuito do `if`).

- [ ] **Step 3: Extend JteEmitter**

Adicionar ao `switch` de `emitStatement`:

```java
            case Statement.SwitchStmt switchStmt -> emitSwitchStmt(switchStmt, out);
```

E:

```java
    private void emitSwitchStmt(Statement.SwitchStmt switchStmt, StringBuilder out) {
        String subject = emitExpr(switchStmt.subject());
        boolean first = true;
        for (Statement.SwitchCase switchCase : switchStmt.cases()) {
            out.append(first ? "@if(" : "@elseif(").append(subject).append(".equals(")
                .append(emitExpr(switchCase.matchValue())).append("))\n");
            for (Statement statement : switchCase.body()) {
                emitStatement(statement, out);
            }
            out.append('\n');
            first = false;
        }
        if (!switchStmt.defaultCase().isEmpty()) {
            out.append("@else\n");
            for (Statement statement : switchStmt.defaultCase()) {
                emitStatement(statement, out);
            }
            out.append('\n');
        }
        out.append("@endif\n");
    }
```

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersSwitchWithDefault() throws Exception {
        String source = """
            component Role(String role) {
              switch (role) {
                case "admin" -> { <p>Admin</p> }
                case "guest" -> { <p>Visitante</p> }
                default -> { <p>Desconhecido</p> }
              }
            }
            """;

        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "admin")).contains("<p>Admin</p>"));
        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "guest")).contains("<p>Visitante</p>"));
        assertTrue(JteRenderSupport.render(source, "Role", Map.of("role", "other")).contains("<p>Desconhecido</p>"));
    }
```

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersSwitchWithDefault" --console=plain`
Expected: FAIL antes dos passos 1–3.

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): switch/case/default"
```

---

### Task 15: Composição de componentes (componentCall, sem slots)

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/support/JteRenderSupport.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Produces: `Statement.ComponentCallStmt(String componentName, List<Arg> args)`, `Statement.Arg(Optional<String> name, Expr value)`.
- Consumes: convenção de nomes de chamada `@template.<Nome>` do JTE (validada nesta tarefa contra dois componentes reais, não apenas um).

- [ ] **Step 1: Extend Statement**

Em `src/main/java/io/suko/lang/ast/Statement.java`, adicionar a `permits`:

```java
    record ComponentCallStmt(String componentName, List<Arg> args, SourceSpan span) implements Statement {
    }

    record Arg(java.util.Optional<String> name, Expr value) {
    }
```

- [ ] **Step 2: Extend SukoAstBuilder**

Adicionar ao início de `buildStatement`:

```java
        if (ctx.componentCall() != null) {
            return buildComponentCallStmt(ctx.componentCall());
        }
```

E:

```java
    private Statement.ComponentCallStmt buildComponentCallStmt(SukoParser.ComponentCallContext ctx) {
        List<Statement.Arg> args = new ArrayList<>();
        if (ctx.argList() != null) {
            for (SukoParser.ArgContext argCtx : ctx.argList().arg()) {
                Optional<String> name = argCtx.Identifier() == null
                    ? Optional.empty()
                    : Optional.of(argCtx.Identifier().getText());
                args.add(new Statement.Arg(name, buildExpr(argCtx.expression())));
            }
        }
        return new Statement.ComponentCallStmt(ctx.qualifiedName().getText(), args, spanOf(ctx));
    }
```

(`slotBlock`, quando presente, é ignorado nesta tarefa — tratado nas tarefas 16–18.)

- [ ] **Step 3: Extend JteEmitter**

Adicionar ao `switch` de `emitStatement`:

```java
            case Statement.ComponentCallStmt call -> emitComponentCall(call, out);
```

E:

```java
    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out) {
        out.append("@template.").append(call.componentName()).append('(');
        for (int i = 0; i < call.args().size(); i++) {
            if (i > 0) out.append(", ");
            Statement.Arg arg = call.args().get(i);
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value()));
        }
        out.append(")\n");
    }
```

- [ ] **Step 4: Extend JteRenderSupport to render more than one component (needed for calls)**

Em `src/test/java/io/suko/lang/support/JteRenderSupport.java`, adicionar um novo método (mantendo os existentes intactos):

```java
    /** Compila TODOS os componentes do ficheiro para .jte (necessário quando um
     * componente chama outro) e renderiza o indicado por entryComponent. */
    public static String renderWithDependencies(String sukoSource, String entryComponent, Map<String, Object> params) throws IOException {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(sukoSource).build(parser.compilationUnit());

        Path tempDir = Files.createTempDirectory("suko-jte-render-multi");
        JteEmitter emitter = new JteEmitter();
        for (ComponentDecl component : file.components()) {
            Files.writeString(tempDir.resolve(component.name() + ".jte"), emitter.emit(component));
        }

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(entryComponent + ".jte", params, output);
        return output.toString();
    }
```

- [ ] **Step 5: Write the failing test**

```java
    @Test
    void rendersComponentComposition() throws Exception {
        String source = """
            component NavLink(String label, String href) {
              <a href={href}>{label}</a>
            }

            component Menu(String activeLabel) {
              <nav>
              NavLink(label = activeLabel, href = "/")
              </nav>
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Menu", Map.of("activeLabel", "Home"));

        assertTrue(html.contains("<a href=\"/\">Home</a>"));
    }
```

- [ ] **Step 6: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersComponentComposition" --console=plain`
Expected: FAIL antes dos passos 1–4.

- [ ] **Step 7: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 8: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/support/JteRenderSupport.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): composição de componentes (componentCall)"
```

---

### Task 16: Slots — `slot<T>` único obrigatório

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Statement.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `Param.SlotParam` (tarefa 9).
- Produces: preenchimento de slot simples no `Statement.ComponentCallStmt` — novo campo `slotFills`.

- [ ] **Step 1: Extend Statement.ComponentCallStmt**

Em `src/main/java/io/suko/lang/ast/Statement.java`, substituir `ComponentCallStmt` e adicionar `SlotFill`:

```java
    record ComponentCallStmt(String componentName, List<Arg> args, List<SlotFill> slotFills,
                             SourceSpan span) implements Statement {
    }

    record Arg(java.util.Optional<String> name, Expr value) {
    }

    /** paramName é o nome do slot; lambdaParamName só é usado por slots render-prop (tarefa 18). */
    record SlotFill(String paramName, java.util.Optional<String> lambdaParamName,
                    List<Statement> body) {
    }
```

- [ ] **Step 2: Extend SukoAstBuilder.buildComponentCallStmt**

Substituir o método por:

```java
    private Statement.ComponentCallStmt buildComponentCallStmt(SukoParser.ComponentCallContext ctx) {
        List<Statement.Arg> args = new ArrayList<>();
        if (ctx.argList() != null) {
            for (SukoParser.ArgContext argCtx : ctx.argList().arg()) {
                Optional<String> name = argCtx.Identifier() == null
                    ? Optional.empty()
                    : Optional.of(argCtx.Identifier().getText());
                args.add(new Statement.Arg(name, buildExpr(argCtx.expression())));
            }
        }

        List<Statement.SlotFill> slotFills = new ArrayList<>();
        if (ctx.slotBlock() != null) {
            for (SukoParser.NamedSlotContext slotCtx : ctx.slotBlock().namedSlot()) {
                String paramName = slotCtx.Identifier(0).getText();
                Optional<String> lambdaParamName = slotCtx.Identifier().size() > 1
                    ? Optional.of(slotCtx.Identifier(1).getText())
                    : Optional.empty();
                List<Statement> body = buildStatements(slotCtx.templateBlock().templateStatement());
                slotFills.add(new Statement.SlotFill(paramName, lambdaParamName, body));
            }
        }

        return new Statement.ComponentCallStmt(ctx.qualifiedName().getText(), args, slotFills, spanOf(ctx));
    }
```

- [ ] **Step 3: Extend JteEmitter — @param para SlotParam e preenchimento de slot no call site**

Em `emitComponentCall`, em `src/main/java/io/suko/lang/JteEmitter.java`, substituir por:

```java
    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out) {
        out.append("@template.").append(call.componentName()).append('(');
        boolean first = true;
        for (Statement.Arg arg : call.args()) {
            if (!first) out.append(", ");
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value()));
            first = false;
        }
        for (Statement.SlotFill slotFill : call.slotFills()) {
            if (!first) out.append(", ");
            out.append(slotFill.paramName()).append(" = @`");
            for (Statement statement : slotFill.body()) {
                emitStatement(statement, out);
            }
            out.append('`');
            first = false;
        }
        out.append(")\n");
    }
```

(o caso `lambdaParamName` presente — render-prop — fica sem tratamento especial ainda; é a tarefa 18.)

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersRequiredSingleSlot() throws Exception {
        String source = """
            component Card(slot<String> header) {
              <div class="card">{header}</div>
            }

            component Page() {
              Card() {
                header { <b>Título</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>Título</b>"));
    }
```

Nota: `{header}` dentro do `Card` interpola um `gg.jte.Content` — o JTE aceita `Content` diretamente em `${}` (ver secção "Java Expression Limitations" da documentação do JTE, consultada durante o brainstorming): não precisa de `.toString()`, o `Content` sabe escrever-se a si próprio.

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersRequiredSingleSlot" --console=plain`
Expected: FAIL antes dos passos 1–3.

- [ ] **Step 6: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Statement.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): slot<T> único obrigatório"
```

---

### Task 17: Slots — opcional (default) e múltiplo (`List<slot<T>>`)

**Files:**
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `Param.SlotParam.cardinality()` (tarefa 9), agora efetivamente lido a partir de `List<slot<T>>` na assinatura do componente.

- [ ] **Step 1: Extend SukoAstBuilder.buildParam to detect List<slot<T>>**

Em `src/main/java/io/suko/lang/SukoAstBuilder.java`, substituir `buildParam` por:

```java
    private Param buildParam(SukoParser.ParamContext ctx) {
        Type type = buildType(ctx.type());
        String name = ctx.Identifier().getText();
        Optional<Expr> defaultValue = ctx.expression() == null
            ? Optional.empty()
            : Optional.of(buildExpr(ctx.expression()));

        if (type.isSlot()) {
            return new Param.SlotParam(slotElementType(type), name, Cardinality.ONE, defaultValue, spanOf(ctx));
        }

        boolean isListOfSlot = "List".equals(type.name())
            && type.typeArguments().size() == 1
            && type.typeArguments().get(0).isSlot();
        if (isListOfSlot) {
            Type slotType = type.typeArguments().get(0);
            return new Param.SlotParam(slotElementType(slotType), name, Cardinality.MANY, defaultValue, spanOf(ctx));
        }

        return new Param.ValueParam(type, name, defaultValue, spanOf(ctx));
    }

    private Type slotElementType(Type slotType) {
        return slotType.typeArguments().isEmpty()
            ? new Type("Object", List.of(), 0)
            : slotType.typeArguments().get(0);
    }
```

- [ ] **Step 2: Extend JteEmitter to accumulate MANY slot fills into a List**

Em `src/main/java/io/suko/lang/JteEmitter.java`, o método `jteParamDeclaration` já trata `Cardinality.MANY` como `java.util.List<gg.jte.Content>` desde a tarefa 9 — não precisa de alteração. Alterar `emitComponentCall` para agrupar múltiplos `SlotFill` com o mesmo `paramName` numa lista `java.util.List.of(...)`:

```java
    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out) {
        out.append("@template.").append(call.componentName()).append('(');
        boolean first = true;
        for (Statement.Arg arg : call.args()) {
            if (!first) out.append(", ");
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value()));
            first = false;
        }

        java.util.Map<String, java.util.List<Statement.SlotFill>> grouped = new java.util.LinkedHashMap<>();
        for (Statement.SlotFill slotFill : call.slotFills()) {
            grouped.computeIfAbsent(slotFill.paramName(), k -> new ArrayList<>()).add(slotFill);
        }

        for (var entry : grouped.entrySet()) {
            if (!first) out.append(", ");
            java.util.List<Statement.SlotFill> fills = entry.getValue();
            if (fills.size() == 1) {
                out.append(entry.getKey()).append(" = ");
                emitSlotFillContent(fills.get(0), out);
            } else {
                out.append(entry.getKey()).append(" = java.util.List.of(");
                for (int i = 0; i < fills.size(); i++) {
                    if (i > 0) out.append(", ");
                    emitSlotFillContent(fills.get(i), out);
                }
                out.append(")");
            }
            first = false;
        }

        out.append(")\n");
    }

    private void emitSlotFillContent(Statement.SlotFill slotFill, StringBuilder out) {
        out.append("@`");
        for (Statement statement : slotFill.body()) {
            emitStatement(statement, out);
        }
        out.append('`');
    }
```

- [ ] **Step 3: Write the failing test**

```java
    @Test
    void rendersOptionalAndMultipleSlots() throws Exception {
        String source = """
            component Toolbar(slot<String> title = @`<span>Sem título</span>`, java.util.List<slot<String>> actions = java.util.List.of()) {
              <div>{title}</div>
              for (gg.jte.Content action : actions) {
                <span>{action}</span>
              }
            }

            component WithActions() {
              Toolbar() {
                title { <b>Editar</b> }
                actions { <button>Salvar</button> }
                actions { <button>Cancelar</button> }
              }
            }

            component WithoutSlots() {
              Toolbar()
            }
            """;

        String withActions = JteRenderSupport.renderWithDependencies(source, "WithActions", Map.of());
        assertTrue(withActions.contains("<b>Editar</b>"));
        assertTrue(withActions.contains("<button>Salvar</button>"));
        assertTrue(withActions.contains("<button>Cancelar</button>"));

        String withoutSlots = JteRenderSupport.renderWithDependencies(source, "WithoutSlots", Map.of());
        assertTrue(withoutSlots.contains("Sem título"));
    }
```

Nota: o valor por omissão de um `slot<T>`/`List<slot<T>>` é escrito no `.sk` como uma expressão Suko comum (`@`...`` e `java.util.List.of()` não são sintaxe Suko válida como *valor por omissão* de parâmetro — isto expõe uma lacuna: o `param: type Identifier (EQ expression)?` da gramática só aceita `expression`, não sintaxe de content block. **Ajuste necessário nesta tarefa**: gerar o valor por omissão de um `SlotParam` diretamente no `JteEmitter` (sempre `gg.jte.Content` vazio ou `List.of()`), ignorando qualquer `defaultValue` de `Param.SlotParam` vindo do `.sk` — simplifica e evita a lacuna de sintaxe. Refletir isso no `jteParamDeclaration`:

```java
    private String jteParamDeclaration(Param param) {
        return switch (param) {
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name();
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
            case Param.SlotParam p ->
                "java.util.List<gg.jte.Content> " + p.name() + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
        };
    }
```

E simplificar o `.sk` de teste acima para não usar `@`...`` como valor por omissão — usar apenas `null`/ausência e checar `{title}` com um `?:` já suportado (tarefa 11):

```java
    @Test
    void rendersOptionalAndMultipleSlots() throws Exception {
        String source = """
            component Toolbar(slot<String> title = null, java.util.List<slot<String>> actions = null) {
              <div>{title ?: "sem-titulo"}</div>
              for (gg.jte.Content action : actions) {
                <span>{action}</span>
              }
            }

            component WithActions() {
              Toolbar() {
                title { <b>Editar</b> }
                actions { <button>Salvar</button> }
                actions { <button>Cancelar</button> }
              }
            }

            component WithoutTitle() {
              Toolbar() {
                actions { <button>Só</button> }
              }
            }
            """;

        String withActions = JteRenderSupport.renderWithDependencies(source, "WithActions", Map.of());
        assertTrue(withActions.contains("<b>Editar</b>"));
        assertTrue(withActions.contains("<button>Salvar</button>"));
        assertTrue(withActions.contains("<button>Cancelar</button>"));

        String withoutTitle = JteRenderSupport.renderWithDependencies(source, "WithoutTitle", Map.of());
        assertTrue(withoutTitle.contains("sem-titulo"));
    }
```

(usa `{title ?: "sem-titulo"}`, já suportado pela tarefa 11, em vez de depender de um valor por omissão real — o valor por omissão de slots fica marcado como uma lacuna de sintaxe a resolver no subprojeto 2, não bloqueia esta tarefa.)

- [ ] **Step 4: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersOptionalAndMultipleSlots" --console=plain`
Expected: FAIL antes dos passos 1–2.

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): slots opcionais e múltiplos (List<slot<T>>)"
```

---

### Task 18: Slots — render-prop (`Function<T, Content>`)

**Files:**
- Modify: `src/main/java/io/suko/lang/ast/Param.java`
- Modify: `src/main/java/io/suko/lang/SukoAstBuilder.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Modify: `src/test/java/io/suko/lang/JteEmitterTest.java`

**Interfaces:**
- Consumes: `Statement.SlotFill.lambdaParamName` (tarefa 16, já presente na gramática desde a tarefa 8).
- Produces: distinção, no `Param.SlotParam`, entre slot simples e slot render-prop — necessária para o emitter decidir entre `Content` e `Function<T, Content>` na assinatura do `.jte`.

- [ ] **Step 1: Decisão de desenho — todo slot é `Function<T, Content>`**

Um `SlotParam` não sabe, no ponto de declaração, se algum ponto de chamada vai usá-lo como render-prop (`row { item -> ... }`) ou como slot simples (`header { ... }`) — quem chama pode estar noutro ficheiro. Como a sintaxe Suko só tem uma forma de tipo (`slot<T>`) para os dois casos, a decisão adotada é: **todo `slot<T>` é emitido sempre como `Function<T, gg.jte.Content>`**, nunca como `Content` simples. O caso "slot simples sem parâmetro" da tarefa 16 passa a ser apenas o caso em que quem o usa ignora o argumento do `Function`. Isto evita precisar de duas formas de tipo e mantém `Param.SlotParam` exatamente como ficou na tarefa 9 (sem novo campo) — nenhuma alteração a `src/main/java/io/suko/lang/ast/Param.java` nesta tarefa.

Em `src/main/java/io/suko/lang/JteEmitter.java`, `jteParamDeclaration` passa a emitir sempre `Function`:

```java
    private String jteParamDeclaration(Param param) {
        return switch (param) {
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name();
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content> " + p.name()
                    + (p.defaultValue().isPresent() ? " = (" + javaType(p.elementType()) + " it) -> null" : "");
            case Param.SlotParam p ->
                "java.util.List<java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content>> " + p.name()
                    + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
        };
    }
```

E `jteParamDeclaration` já usava `Object` como `elementType` por omissão (via `slotElementType` na tarefa 17) quando `slot` não tem argumento de tipo — comportamento preservado.

- [ ] **Step 2: Update slot-fill emission to always emit a lambda**

Em `src/main/java/io/suko/lang/JteEmitter.java`, `emitSlotFillContent` passa a envolver sempre num lambda, usando `lambdaParamName` quando presente ou um nome sintético (`__ignored`) caso contrário:

```java
    private void emitSlotFillContent(Statement.SlotFill slotFill, StringBuilder out) {
        String lambdaParam = slotFill.lambdaParamName().orElse("__ignored");
        out.append(lambdaParam).append(" -> @`");
        for (Statement statement : slotFill.body()) {
            emitStatement(statement, out);
        }
        out.append('`');
    }
```

- [ ] **Step 3: Update how a filled slot is READ inside the component body**

Como `header`/`title`/`row` etc. passam a ser `Function<T, Content>` em vez de `Content`, `{header}` sozinho (tarefa 16) deixa de compilar — é preciso chamar `.apply(...)`. Isto significa que `Statement.Interpolation` sobre um `SlotParam` precisa de emitir `header.apply(null)` quando usado sem argumento explícito. Como o `AstBuilder`/`JteEmitter` desta camada não fazem resolução de nomes (isso é o verificador do subprojeto 2), **a forma suportada nesta tarefa passa a exigir sempre uma chamada explícita** no `.sk`: `{header(null)}` para o caso simples, `{row(item)}` dentro de um `for`. Ajustar o exemplo da tarefa 16 (retroativo) e o novo teste desta tarefa a usar essa forma — `CallExpr` já emite `.apply`? Não: `CallExpr` emite `callee(args)`, não `callee.apply(args)`. **Ajuste no emitter**: quando o `callee` de um `CallExpr` é um `Expr.PrimaryExpr` cujo texto corresponde a um nome de slot não é detetável sem tabela de símbolos — em vez de detetar isso no emitter (que não tem essa informação nesta fase), a Suko exige que a chamada de um slot seja escrita com a sintaxe de invocação normal e o `JteEmitter` trata **toda chamada de função sobre um único identificador simples seguida de exatamente um argumento, dentro do corpo de um componente que declara um `SlotParam` com esse nome**, como `.apply(...)`. Isto exige que `emitExpr` receba o conjunto de nomes de slot do componente atual.

Alterar a assinatura interna do emitter para passar o conjunto de nomes de slot ao longo da árvore:

```java
    public String emit(ComponentDecl component) {
        java.util.Set<String> slotNames = new java.util.HashSet<>();
        for (Param param : component.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                slotNames.add(slotParam.name());
            }
        }

        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param)).append('\n');
        }
        out.append('\n');
        for (Statement statement : component.body()) {
            emitStatement(statement, out, slotNames);
        }
        return out.toString();
    }
```

E propagar `java.util.Set<String> slotNames` como parâmetro adicional em `emitStatement`, `emitHtmlElement`, `emitIfStmt`, `emitForStmt`, `emitSwitchStmt`, `emitComponentCall` e `emitExpr` (assinatura passa a `emitExpr(Expr expr, java.util.Set<String> slotNames)`), e em `Expr.CallExpr`:

```java
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text()) ->
                p.text() + ".apply(" + emitArgs(call.args(), slotNames) + ")";
            case Expr.CallExpr call -> emitExpr(call.callee(), slotNames) + "(" + emitArgs(call.args(), slotNames) + ")";
```

(esta é uma mudança mecânica e extensa de assinaturas — o executor deve propagar `slotNames` por todos os métodos `emit*` existentes das tarefas 9–17, seguindo o mesmo padrão do `emitComponentCall`/`emitExpr` acima.)

- [ ] **Step 4: Write the failing test**

```java
    @Test
    void rendersRenderPropSlot() throws Exception {
        String source = """
            component ItemList(java.util.List<String> items, slot<String> row) {
              <ul>
              for (String item : items) {
                <li>{row(item)}</li>
              }
              </ul>
            }

            component Page() {
              ItemList(items = java.util.List.of("a", "b")) {
                row { item -> <b>{item}</b> }
              }
            }
            """;

        String html = JteRenderSupport.renderWithDependencies(source, "Page", Map.of());

        assertTrue(html.contains("<b>a</b>"));
        assertTrue(html.contains("<b>b</b>"));
    }
```

- [ ] **Step 5: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest.rendersRenderPropSlot" --console=plain`
Expected: FAIL antes do passo 1 (e também antes de propagar `slotNames`, com erro de compilação Java no `.jte` gerado — inspecionar via `JteRenderSupport.compileToJte` num teste auxiliar temporário se a causa não for óbvia pela mensagem do `gg.jte`).

- [ ] **Step 6: Run test to verify it passes, and re-run every earlier JteEmitterTest**

Run: `gradle test --tests "io.suko.lang.JteEmitterTest" --console=plain`
Expected: PASS em todos — incluindo o teste da tarefa 16 (`rendersRequiredSingleSlot`), que precisa de ser atualizado para usar `{header(null)}` em vez de `{header}`, e o da tarefa 17 (`rendersOptionalAndMultipleSlots`), atualizado para `{action.apply(null)}`-style dentro do `for` (`for (java.util.function.Function<Object, gg.jte.Content> action : actions) { <span>{action(null)}</span> }`).

- [ ] **Step 7: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add src/main/java/io/suko/lang/ast/Param.java src/main/java/io/suko/lang/SukoAstBuilder.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterTest.java
git commit -m "feat(emitter): unifica slots como Function<T, Content> (render-prop)"
```

---

### Task 19: Teste de integração completo — Card.sk (ARCHITECTURE.md)

**Files:**
- Modify: `src/test/java/io/suko/lang/SukoParserSmokeTest.java`
- Create: `src/test/java/io/suko/lang/SukoEndToEndTest.java`

**Interfaces:**
- Consumes: todas as interfaces das tarefas 9–18.

- [ ] **Step 1: Adjust examples/Card.sk to the syntax settled by this plan**

`examples/Card.sk` usa `for (T item : items)` sem qualificar `List` — isso já é aceite pela gramática (tarefa 13 exige apenas que o tipo seja escrito por extenso no `.sk`, e `List<T>` já vem qualificado no parâmetro do componente `Card<T>(String title, List<T> items, ...)`, não no `for`). Confirmar lendo o ficheiro atual — não deve precisar de alteração para o `Card`/`NavLink`; o componente `Page` usa `Layout(...)`, `Sidebar { }`, `Content { }`, que são slots de um componente `Layout` **não definido** no ficheiro. Como este teste teria de resolver `Layout`/`AdminPanel`, que não existem, **restringir este teste a `Card` e `NavLink`** (que são autossuficientes) e não tentar renderizar `Page` nesta tarefa — isso fica para quando o subprojeto 2 permitir declarar `Layout`/`AdminPanel` como stubs, ou para um exemplo adicional futuro.

- [ ] **Step 2: Write the failing test**

`src/test/java/io/suko/lang/SukoEndToEndTest.java`:

```java
package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Renderiza de ponta a ponta o Card.sk do ARCHITECTURE.md através do
 * pipeline real: SukoAstBuilder -> JteEmitter -> gg.jte. Complementa o
 * SukoParserSmokeTest (que só verifica ausência de erro de parsing).
 */
class SukoEndToEndTest {

    @Test
    void rendersCardWithItems() throws Exception {
        String source = Files.readString(Path.of("examples/Card.sk"));

        record Item(String name, String price) {
        }

        String html = JteRenderSupport.renderWithDependencies(source, "Card", Map.of(
            "title", "Produtos",
            "items", List.of(new Item("Café", "3.50"), new Item("Chá", "2.80")),
            "emptyLabel", "Sem itens"));

        assertTrue(html.contains("Produtos"));
        assertTrue(html.contains("Café"));
        assertTrue(html.contains("Chá"));
    }

    @Test
    void rendersCardEmptyState() throws Exception {
        String source = Files.readString(Path.of("examples/Card.sk"));

        String html = JteRenderSupport.renderWithDependencies(source, "Card", Map.of(
            "title", "Produtos",
            "items", List.of(),
            "emptyLabel", "Nada por aqui"));

        assertTrue(html.contains("Nada por aqui"));
    }
}
```

Nota: o `item.price?.format()` do `Card.sk` original chama `.format()` sobre o preço — o `Item` acima usa `String price`, que não tem `.format()`. Ajustar `examples/Card.sk` (ou o record de teste) para que o tipo bata certo: mais simples é o teste usar um `record Item(String name, java.math.BigDecimal price)` e o `.format()` não existir em `BigDecimal` também. **Resolução adotada:** ajustar apenas o teste, não o exemplo, definindo `record Item(String name, Priced price)` com `record Priced(String value) { public String format() { return value; } }`, mantendo `examples/Card.sk` inalterado (é o ficheiro de referência do `ARCHITECTURE.md`).

- [ ] **Step 3: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.SukoEndToEndTest" --console=plain`
Expected: FAIL ou erro de compilação do `.jte` gerado — inspecionar a mensagem; ajustar o record auxiliar do teste (passo 2, nota) até o tipo bater certo com `item.price?.format()`.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.SukoEndToEndTest" --console=plain`
Expected: PASS

- [ ] **Step 5: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/test/java/io/suko/lang/SukoEndToEndTest.java
git commit -m "test: renderização de ponta a ponta do Card.sk via gg.jte real"
```

---

### Task 20: Source map (linha do `.jte` → span do `.sk`)

**Files:**
- Create: `src/main/java/io/suko/lang/ast/SourceMapEntry.java`
- Modify: `src/main/java/io/suko/lang/JteEmitter.java`
- Test: `src/test/java/io/suko/lang/JteEmitterSourceMapTest.java`

**Interfaces:**
- Produces: `JteEmitter.emitWithSourceMap(ComponentDecl): EmitResult` onde `EmitResult(String jteSource, List<SourceMapEntry> entries)` — consumido pelos subprojetos 2 e 3 (fora deste plano) para reportar erros na linha do `.sk`.

- [ ] **Step 1: Create SourceMapEntry and EmitResult**

`src/main/java/io/suko/lang/ast/SourceMapEntry.java`:

```java
package io.suko.lang.ast;

/** Mapeia uma linha (1-based) do .jte gerado para o span do .sk que a originou. */
public record SourceMapEntry(int jteLine, SourceSpan sukoSpan) {
}
```

- [ ] **Step 2: Add EmitResult and emitWithSourceMap to JteEmitter**

Em `src/main/java/io/suko/lang/JteEmitter.java`, adicionar:

```java
    public record EmitResult(String jteSource, java.util.List<io.suko.lang.ast.SourceMapEntry> sourceMap) {
    }

    public EmitResult emitWithSourceMap(ComponentDecl component) {
        java.util.List<io.suko.lang.ast.SourceMapEntry> entries = new ArrayList<>();
        String jteSource = emit(component);

        // Reconstrói o mapeamento percorrendo as mesmas statements de novo,
        // desta vez só para registar em que linha do .jte cada Statement de
        // topo começou a ser escrito. Suficiente para localizar erros por
        // linha (não por coluna) nos subprojetos 2/3.
        StringBuilder probe = new StringBuilder();
        for (Param param : component.params()) {
            probe.append("@param ").append(jteParamDeclaration(param)).append('\n');
        }
        probe.append('\n');
        int lineSoFar = countLines(probe.toString());

        java.util.Set<String> slotNames = slotNamesOf(component);
        for (Statement statement : component.body()) {
            entries.add(new io.suko.lang.ast.SourceMapEntry(lineSoFar + 1, statement.span()));
            StringBuilder single = new StringBuilder();
            emitStatement(statement, single, slotNames);
            lineSoFar += countLines(single.toString());
        }

        return new EmitResult(jteSource, entries);
    }

    private int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private java.util.Set<String> slotNamesOf(ComponentDecl component) {
        java.util.Set<String> slotNames = new java.util.HashSet<>();
        for (Param param : component.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                slotNames.add(slotParam.name());
            }
        }
        return slotNames;
    }
```

(Ajustar `emit(ComponentDecl)` para reutilizar `slotNamesOf`, eliminando a duplicação introduzida na tarefa 18.)

- [ ] **Step 3: Write the failing test**

`src/test/java/io/suko/lang/JteEmitterSourceMapTest.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JteEmitterSourceMapTest {

    @Test
    void mapsTopLevelStatementsToTheirSukoLine() {
        String source = """
            component Greeting(String name) {
              <p>Hello, {name}!</p>
              <p>Bye</p>
            }
            """;

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());
        ComponentDecl component = file.components().get(0);

        JteEmitter.EmitResult result = new JteEmitter().emitWithSourceMap(component);

        assertFalse(result.sourceMap().isEmpty());
        // A primeira statement de topo ("<p>Hello...") começa na linha 2 do .sk.
        assertEquals(2, result.sourceMap().get(0).sukoSpan().startLine());
        // A segunda ("<p>Bye</p>") começa na linha 3 do .sk.
        assertEquals(3, result.sourceMap().get(1).sukoSpan().startLine());
    }
}
```

- [ ] **Step 4: Run test to verify it fails**

Run: `gradle test --tests "io.suko.lang.JteEmitterSourceMapTest" --console=plain`
Expected: FAIL antes do passo 2 (método não existe).

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterSourceMapTest" --console=plain`
Expected: PASS

- [ ] **Step 6: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL — fim do subprojeto 1.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/io/suko/lang/ast/SourceMapEntry.java src/main/java/io/suko/lang/JteEmitter.java src/test/java/io/suko/lang/JteEmitterSourceMapTest.java
git commit -m "feat(emitter): source map linha-do-.jte -> span-do-.sk"
```

---

### Task 21: Testes golden-file (`.sk` → `.jte` esperado)

A spec pede explicitamente testes golden-file, complementares aos testes de render das tarefas 9–19 (que validam comportamento, mas não o texto `.jte` gerado). Como o formato exato do `.jte` só fica definitivo depois de as tarefas 9–18 estarem implementadas, o golden file desta tarefa é gerado a partir da execução real (passo 1), revisto à mão (passo 2), e só depois fixado como valor esperado — prática normal para este tipo de teste.

**Files:**
- Create: `src/test/resources/golden/Card.jte`
- Create: `src/test/resources/golden/NavLink.jte`
- Test: `src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java`

**Interfaces:**
- Consumes: `JteRenderSupport.compileToJte(String sukoSource, String componentName): String` (tarefa 9).

- [ ] **Step 1: Print the real .jte output for Card and NavLink**

Run um pequeno teste temporário (ou `System.out.println` dentro de um `@Test` descartável) chamando:

```java
System.out.println(JteRenderSupport.compileToJte(Files.readString(Path.of("examples/Card.sk")), "Card"));
System.out.println(JteRenderSupport.compileToJte(Files.readString(Path.of("examples/Card.sk")), "NavLink"));
```

Run: `gradle test --tests "io.suko.lang.JteEmitterGoldenFileTestScratch" --console=plain` (classe descartável, apagada no fim deste passo)
Expected: imprime dois blocos de texto `.jte`.

- [ ] **Step 2: Review the printed output by hand**

Confirmar visualmente que ambos os blocos: têm um `@param` por parâmetro do componente na ordem declarada; não têm chamadas a componentes por resolver como texto solto incorreto; o HTML e as chaves `{}` de interpolação batem com o `Card.sk` original. Se algo parecer errado (ex: espaçamento duplicado, tag mal fechada), corrigir o `JteEmitter` antes de prosseguir — este passo é o ponto de deteção manual antes de o golden file ficar fixado.

- [ ] **Step 3: Save the reviewed output as golden files**

Colar o texto revisto (passo 2) em `src/test/resources/golden/Card.jte` e `src/test/resources/golden/NavLink.jte`, byte a byte como impresso.

- [ ] **Step 4: Write the golden-file test**

`src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java`:

```java
package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compara o .jte gerado, byte a byte, com um fixture revisto à mão em
 * src/test/resources/golden/. Complementa os testes de render (que
 * validam comportamento, não o texto gerado) — uma mudança não
 * intencional de formatação no JteEmitter falha aqui mesmo que o HTML
 * final renderizado continue correto.
 */
class JteEmitterGoldenFileTest {

    @Test
    void cardMatchesGoldenFile() throws Exception {
        String sukoSource = Files.readString(Path.of("examples/Card.sk"));
        String actual = JteRenderSupport.compileToJte(sukoSource, "Card");
        String expected = Files.readString(Path.of("src/test/resources/golden/Card.jte"));
        assertEquals(expected, actual);
    }

    @Test
    void navLinkMatchesGoldenFile() throws Exception {
        String sukoSource = Files.readString(Path.of("examples/Card.sk"));
        String actual = JteRenderSupport.compileToJte(sukoSource, "NavLink");
        String expected = Files.readString(Path.of("src/test/resources/golden/NavLink.jte"));
        assertEquals(expected, actual);
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle test --tests "io.suko.lang.JteEmitterGoldenFileTest" --console=plain`
Expected: PASS (os fixtures foram gerados a partir do próprio emitter no passo 1, por isso batem por construção — este teste protege contra regressões futuras, não valida correção nova).

- [ ] **Step 6: Run full test suite**

Run: `gradle test --console=plain`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add src/test/resources/golden src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java
git commit -m "test: golden-file para o .jte gerado de Card e NavLink"
```
