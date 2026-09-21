# Suko — Subprojeto 9: Unificação da Sintaxe de Interpolação — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A linguagem Suko passa a ter uma regra de interpolação invariante e independente da posição — `${expr}` interpola em todas as posições (corpo de componente, valor de atributo, literal de string) e uma chaveta nua nunca interpola em lado nenhum, com as duas grafias antigas (`{expr}` de statement e `attr={expr}`) a passarem a erro de compilação com a correção literal na mensagem.

**Architecture:** O token `EXPR_INTERP_START` (`${`) passa a existir também em `DEFAULT_MODE`, e uma única regra de parser `interpolation` serve as três posições (statement, atributo, `stringPart`). A regra mantém a forma legada (`LBRACE expression RBRACE`) como alternativa aceite mas marcada no AST, porque removê-la da gramática produziria recuperação silenciosa do ANTLR nos dois caminhos de parse que removem os error listeners de propósito (`ProjectIndex`, `RegistryGenerator`) — e, nesse último, um manifesto gerado a partir de árvore truncada. A rejeição é semântica (`SemanticChecker`, severidade ERROR), não sintática. O `JteEmitter` **não muda**: já emitia `${...}` no `.jte`.

**Tech Stack:** ANTLR 4.13.1 (lexer com modes + parser separados), Java 21 (`options.release`), JUnit 5, gg.jte 3.1.12 (render real via `testFixtures(suko-core)`/`JteRenderSupport`), Gradle 9.5 multi-módulo sem wrapper commitado.

**Spec:** docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md

## Global Constraints

Os oito primeiros são os constrangimentos C1-C8 da spec, copiados com os valores exatos. Valem para todas as tarefas.

- **C1 — `textRun` é um closure guloso sem lookahead intermédio.** `textRun : ( ~(LBRACE | RBRACE | LT | LTSLASH) )+` (`suko-core/src/main/antlr/io/suko/lang/SukoParser.g4:115-117`). É a causa-raiz de dois bugs já documentados em `ARCHITECTURE.md` (slot nomeado engolido depois de conteúdo solto, linhas 334-350; `var c = Card() { ... }` engolido como texto, linhas 365-378). Se o token novo de `${` **não** for excluído do conjunto, `<p>custa ${price} euros</p>` faz o `textRun` engolir `custa ${price` como texto e o `}` sobra — silenciosamente. A exclusão não é otimização, é condição de correção.
- **C2 — Existem três caminhos de parse no monorepo, e dois removem os error listeners de propósito.** `JteCompiler.parseAndBuild` (`suko-core/src/main/java/io/suko/lang/JteCompiler.java:57-67`) instala `SukoErrorListener` e aborta em `diagnostics.hasErrors()` (linhas 66, 93, 112). `ProjectIndex.buildFileAst` (`suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java:135-138`) e `RegistryGenerator` (`suko-registry-generator/src/main/java/io/suko/registry/RegistryGenerator.java:233-243`) fazem `parser.removeErrorListeners()`. Nesses dois, um `.sk` que deixe de fazer parse não dá erro: dá AST parcial em silêncio — no `RegistryGenerator`, um manifesto gerado a partir de árvore truncada.
- **C3 — O `SemanticChecker` tem dois percursos de statements, e o que diagnostica sintaxe é o incompleto.** `checkStatement` (`suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java:256-286`) desce a `if`/`for`/`switch`/atributos/filhos de tag. `checkBareBraceInStatement` (`:175-190`), onde vive o `BARE_BRACE_IN_STRING`, trata só `HtmlElement`/`Interpolation`/`TextRun` e tem `default -> {}`. **Nenhum dos dois** desce aos corpos de slot fill (`checkComponentCall`, `:394-397`, itera `call.slotFills()` só para contar cardinalidade). `Alert.sk`, `Badge.sk` e `Button.sk` têm o corpo **inteiro** dentro de um `switch`.
- **C4 — O `.jte` emitido já usa `${}`.** `JteEmitter.emitStatement` (`suko-core/src/main/java/io/suko/lang/JteEmitter.java:328-336`) escreve `${expr}` e `emitHtmlElement` (`:597-612`) escreve `attr="${expr}"`. **Nenhum golden file de `suko-core/src/test/resources/golden/` (`Card.jte`, `NavLink.jte`) pode mudar neste subprojeto.** Se mudar, é regressão, não migração.
- **C5 — Dentro de `STRING_MODE` uma chaveta nunca produz um token `LBRACE`.** `STRING_TEXT : ~["\\$]+` (`suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4:189-191`) cobre `{` e `}`. É por isso que `x-data="{ open: false }"` (`suko-components/src/main/suko/io/suko/ui/Dialog.sk:17`) funciona, e por isso que a alternativa legada é inalcançável dentro de uma string mesmo com a regra `interpolation` partilhada com `stringPart`.
- **C6 — O `popMode` do `RBRACE` é condicional e frágil por desenho.** `RBRACE : '}' { if (!_modeStack.isEmpty()) popMode(); }` (`SukoLexer.g4:68-70`). O token novo de `${` em `DEFAULT_MODE` **não pode empurrar modo** (já estamos em `DEFAULT_MODE`); empurrar sujaria a pilha e um `${` sem fecho faria o próximo `}` de bloco dar um pop indevido.
- **C7 — O escape `\$` produz Java inválido.** `SukoAstBuilder.buildStringLiteral` (`suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java:361-378`) acumula o texto cru do token `STRING_ESCAPE` no literal run, e `JteEmitter.emitStringPart` (`:782`) embrulha-o em aspas Java: `\$` chega ao `javac` como `\$`, que não é escape Java válido. Não existe nenhum teste de escapes em `suko-core/src/test`.
- **C8 — O manifesto não tem campo de versão mínima de linguagem.** `RegistryIndex`/`ComponentManifest` têm `schemaVersion`, `registryVersion` e `version` por componente — nenhum diz "exige compilador >= X". Mitigação existente: a tag por omissão do registry deriva da versão da CLI (`suko-cli/src/main/java/io/suko/cli/Version.java`). Não é fechado neste subprojeto.

Constrangimentos de processo (herdados dos subprojetos 7/8, continuam a valer):

- O projeto **não** tem `gradlew` commitado — todos os comandos usam `gradle` (sistema), nunca `./gradlew`.
- Git dentro de worktrees deste harness tem um bug intermitente do sandbox (classificador "rtk") em `git status`/`git diff`/`git add` bare — invocar sempre via caminho absoluto `/usr/bin/git <subcomando>`. Aplica-se a todos os passos de commit.
- **Nunca acrescentar linhas `Co-Authored-By:` nem `Claude-Session:` a mensagens de commit** (`CLAUDE.md` da raiz; o histórico foi reescrito a 2026-09-20 para as remover).
- Testes de render usam `JteRenderSupport` (motor `gg.jte` 3.1.12 real) sempre que o teste dependa de código Java gerado. Nada de smoke de parse quando há forma de provar comportamento.
- Qualquer alteração aos `.g4` obriga a regenerar (`gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser --console=plain`) e a confirmar **ausência da palavra `warning`** na saída.
- Qualquer desvio descoberto durante a implementação é documentado no código, no ponto exato da descoberta, com o texto exato do comportamento observado.
- **Os números de linha citados neste plano são os de 2026-09-21.** Tarefas de documentação editam ficheiros que tarefas anteriores já mexeram: localizar sempre pelo texto âncora citado, nunca só pelo número de linha.
- **Nenhuma dependência nova.** Nada entra no `build.gradle.kts` de nenhum módulo.

## Não-objetivos — NÃO implementar (D9 da spec)

Isto não é uma lista de "seria bom": é uma lista de coisas que quem executar este plano vai ter abertas à frente e vai ser tentado a fazer "já que estou na gramática". Fazer qualquer uma delas é motivo de rejeição na revisão.

- **Não alargar `textRun` para aceitar `{`/`}` literais em texto livre.** Torna-se tecnicamente possível depois da Task 2 (a chaveta deixa de ser sigilo), mas `}` continua a fechar `templateBlock`/`slotBlock`/`if`/`for`/`switch`: a liberalização seria parcial e reabriria a família de bugs de C1.
- **Não alargar `htmlName` para aceitar `:`/`@`** (Alpine `x-on:click`, `@click`). Continua a ser o trabalho descrito em `ARCHITECTURE.md:416-427`.
- **Não remover `SIMPLE_INTERP_START` nem `Expr.StringPart.SimpleInterp`.** D3 da spec foi **rejeitada** pelo utilizador: a forma curta `$ident` dentro de strings **fica exatamente como está**. Não há nenhum diagnóstico `LEGACY_DOLLAR_IDENT` neste plano — se procuras por ele, não existe, e a razão é esta.
- **Não mexer em auto-`toString`, ancoragem de concatenação, nem semântica de `null` em strings.** Este subprojeto muda o sigilo, não a emissão. Qualquer mudança aí viola C4.
- **Não corrigir as duas limitações de gulodice do `textRun` já documentadas** (slot nomeado depois de conteúdo solto; `var c = Card() { ... }`).
- **Não introduzir `minSukoVersion` no manifesto** (C8).
- **Não tocar em `suko-website/`** para lá da renumeração textual da Task 12.

## Descobertas feitas ao escrever este plano (detalhe de implementação, não decisão de design)

- **Alternativas etiquetadas (`# Label`) no `interpolation` custariam mais do que valem.** Com labels, o ANTLR deixa de gerar acessores na `InterpolationContext` base e obriga o `SukoAstBuilder` a fazer `instanceof` sobre subclasses de contexto. A forma escolhida é um **label de token** dentro de um conjunto de alternativas — `(EXPR_INTERP_START | legacy=LBRACE) expression RBRACE` — que mantém `ctx.expression()` a funcionar e dá `ctx.legacy != null` como teste da forma usada.
- **Só existem três sítios de construção dos dois records que mudam,** todos em `SukoAstBuilder`: `new Statement.Interpolation(` em `:149` e `:277`, `new Statement.Attribute(` em `:305`. Os 9 sítios que fazem `case Statement.Interpolation` não são afetados por acrescentar um componente a um record. Isto é o que torna a Task 3 barata.
- **`checkBareBraceInStatement` pode passar a exaustivo sem `default`.** O `sealed interface Statement` tem exatamente 8 variantes (`HtmlElement`, `TextRun`, `Interpolation`, `VarDecl`, `IfStmt`, `ForStmt`, `SwitchStmt`, `ComponentCallStmt`); cobrindo as 8, o `default -> {}` desaparece e uma variante nova passa a ser erro de compilação em vez de um buraco silencioso. É a correção certa para C3.
- **`JteEmitterGoldenFileTest` lê `../examples/Card.sk`** (`suko-core/src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java:25` e `:33`). Migrar `examples/Card.sk` é, por construção, o teste de C4: se o `.jte` gerado mudar um byte, o teste falha.
- **O fonte `.sk` embutido em Java de `suko-cli` não tem interpolação nenhuma.** `FullCycleTest` e `UpdateCommandTest` usam conteúdo como `<div class="label-v2">{{ text }}</div>`, que é texto literal e nunca é parseado pela CLI (que não depende de `suko-core` em produção). O único item de `suko-cli` na migração é o `sha256` hard-coded de `UpdateCommandTest.java:249`.
- **O inventário real de fonte `.sk` inline com chaveta-interpolação são 23 ficheiros Java**, não os ~48 que contêm a palavra "component": 20 em `suko-core` (incl. 3 de `src/main`, só em comentários/javadoc), 3 em `suko-gradle-plugin`, 4 em `suko-components`. A lista exata está nas Tasks 8 e 9.
- **`examples/layout/LayoutComponents.sk:64` já mistura as duas grafias na mesma linha** (`<a href={href} class="btn btn-${variant}" disabled={disabled}>`) — é o exemplo mais claro da confusão que este subprojeto elimina, e um bom caso de teste de migração.
- **Os fixtures `.sk` de `suko-registry-generator` usam `{text}`** em 5 ficheiros; `suko-cli/src/test/resources/rewrite-fixtures/html-noise.sk` não tem nenhuma interpolação.
- **A ordem das tarefas é deliberada: a migração vem ANTES do diagnóstico de erro.** Entre a Task 2 e a Task 10 as duas grafias funcionam em paralelo, o que mantém a suite verde a cada commit. Se o diagnóstico ERROR entrasse antes da migração, todas as tarefas intermédias deixariam o branch vermelho — o oposto de "cada tarefa termina com um deliverable testável".

---

### Task 0: Sondas de Fase 0 — três provas empíricas antes de tocar na gramática

**Agent:** `antlr4-specialist`

**Files:**
- Nenhum ficheiro do repositório é alterado de forma permanente. Ficheiros tocados **temporariamente e revertidos**: `suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4`, `suko-core/src/main/antlr/io/suko/lang/SukoParser.g4`.
- Scratch: usar o diretório de scratch da sessão para ficheiros `.sk` de sonda.

**Interfaces:**
- Consumes: nada.
- Produces: três factos, registados no ledger SDD e no relatório desta tarefa — (a) a tradução correta de `\$`, (b) a confirmação de que `EXPR_INTERP_START` tem de ser excluído do `textRun`, (c) a confirmação de que `-> type(EXPR_INTERP_START)` entre modos é aceite pelo ANTLR 4.13.1 sem warnings.

**Esta tarefa não produz commit de código.** É uma sonda, na convenção do projeto ("nenhuma forma nova de Java gerado avança sem confirmação empírica prévia"). Todas as alterações a `.g4` feitas aqui são revertidas no Step 5. O que sobrevive é conhecimento, registado no ledger.

- [ ] **Step 1: Sonda (a) — provar que `\$` não compila hoje**

Criar `suko-core/src/test/java/io/suko/lang/EscapeProbeTest.java` (temporário, revertido no Step 5):

```java
package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EscapeProbeTest {

    @Test
    void dollarEscapeRendersLiterally() throws Exception {
        String source = """
            component Price() {
              <p>{"custa \\$5"}</p>
            }
            """;
        assertEquals("<p>custa $5</p>",
            JteRenderSupport.render(source, "Price", Map.of()).trim());
    }

    @Test
    void escapedInterpolationRendersLiterally() throws Exception {
        String source = """
            component Tpl() {
              <p>{"literal \\${x}"}</p>
            }
            """;
        assertEquals("<p>literal ${x}</p>",
            JteRenderSupport.render(source, "Tpl", Map.of()).trim());
    }
}
```

Run: `gradle :suko-core:test --tests "io.suko.lang.EscapeProbeTest" --console=plain`

Expected: **ambos falham**. Registar a mensagem exata do `javac`/`gg.jte` (espera-se "illegal escape character" no primeiro). Registar também o que acontece ao segundo — é ele que decide entre traduzir `\$` para `$` cru ou para `\u0024` na Task 6: se `$` cru fizer o `gg.jte` reinterpretar o `${x}` do texto literal como interpolação JTE, `$` cru está fora.

- [ ] **Step 2: Sonda (b) — provar a gulodice do `textRun` contra `${`**

Editar `SukoLexer.g4`: acrescentar, imediatamente antes da regra `STRING_START` (linha 143), a regra

```antlr
EXPR_INTERP_START
    : '${'
    ;
```

e, em `mode STRING_MODE`, substituir a regra `EXPR_INTERP_START` existente (linhas 171-173) por

```antlr
STRING_EXPR_INTERP_START
    : '${' -> type(EXPR_INTERP_START), pushMode(DEFAULT_MODE)
    ;
```

Editar `SukoParser.g4`: na regra `interpolation` (linhas 196-198), trocar `LBRACE` por `EXPR_INTERP_START`. **Não** mexer no `textRun` ainda — é isso que a sonda mede.

Run: `gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser --console=plain`

Depois, um ficheiro de sonda no scratch e um `parseErrors` ad-hoc (copiar o helper de `suko-core/src/test/java/io/suko/lang/SukoGrammarFixesTest.java:25-45`) sobre:

```
component A(String price) { <p>custa ${price} euros</p> }
```

Expected: sem a exclusão no `textRun`, o `textRun` engole `custa ${price` e o parse **falha ou produz um único `TextRun`**. Registar o comportamento exato observado (erro de parse vs. AST com 1 statement em vez de 3). Depois acrescentar `EXPR_INTERP_START` ao conjunto excluído do `textRun` e confirmar que passa a haver 3 statements.

- [ ] **Step 3: Sonda (c) — provar que `type()` entre modos é aceite sem warnings**

Com as edições do Step 2 ainda aplicadas:

Run: `gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser --console=plain 2>&1 | grep -i warning`

Expected: **saída vazia**. Se aparecer qualquer warning, registar o texto exato — é bloqueante para a Task 1 e a forma do lexer tem de ser repensada (alternativa a avaliar nesse caso: declarar dois tokens distintos e aceitar ambos no parser, em vez de re-tipar).

Confirmar também que o `SukoLexer.tokens` gerado em `suko-core/build/generated-src/antlr/main/io/suko/lang/` tem **uma** entrada `EXPR_INTERP_START` e **nenhuma** entrada `STRING_EXPR_INTERP_START` com tipo próprio usado pelo parser.

- [ ] **Step 4: Sonda (c-bis) — provar que a pilha de modos fica equilibrada**

Ainda com as edições aplicadas, parsear:

```
component A(String x, String y) { <p id="a ${y} b">${x}</p> }
```

Expected: zero erros de parse. É o caso que C6 põe em risco — um `${` de `DEFAULT_MODE` que empurrasse modo deixaria a pilha suja e o `}` do `templateBlock` faria um pop indevido.

- [ ] **Step 5: Reverter tudo e registar**

```bash
/usr/bin/git checkout -- suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4 suko-core/src/main/antlr/io/suko/lang/SukoParser.g4
rm -f suko-core/src/test/java/io/suko/lang/EscapeProbeTest.java
/usr/bin/git status --short
```

Expected: árvore limpa. Registar no ledger SDD as quatro respostas (a, b, c, c-bis) com o texto exato observado. **Sem commit.**

---

### Task 1: Lexer — `${` passa a existir em `DEFAULT_MODE`

**Agent:** `antlr4-specialist`

**Files:**
- Modify: `suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4:138-145` (bloco de comentário + `STRING_START`) e `:168-173` (`EXPR_INTERP_START` em `STRING_MODE`)
- Test: `suko-core/src/test/java/io/suko/lang/SukoLexerInterpolationTest.java` (criar)

**Interfaces:**
- Consumes: as conclusões (b) e (c) da Task 0.
- Produces: o tipo de token `SukoLexer.EXPR_INTERP_START`, emitido tanto em `DEFAULT_MODE` como em `STRING_MODE`. É o token que a Task 2 usa na regra `interpolation`.

**Porquê primeiro:** o parser não pode referir um token que o lexer não emite. Esta tarefa é aditiva e não muda nenhum comportamento observável do parser — depois dela, `${` fora de string é um token que ninguém consome ainda.

- [ ] **Step 1: Escrever o teste que falha**

Criar `suko-core/src/test/java/io/suko/lang/SukoLexerInterpolationTest.java`:

```java
package io.suko.lang;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subprojeto 9 (D1/D2): o token `${` passa a ser emitido nas DUAS posições
 * — fora de string (DEFAULT_MODE, sem mudança de modo, ver C6 do plano) e
 * dentro de string (STRING_MODE, com pushMode). O mesmo TIPO de token nos
 * dois casos é o que permite ao parser ter uma única regra `interpolation`.
 */
class SukoLexerInterpolationTest {

    private static List<Token> tokens(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        CommonTokenStream stream = new CommonTokenStream(lexer);
        stream.fill();
        return stream.getTokens();
    }

    private static long countOfType(String source, int type) {
        return tokens(source).stream().filter(t -> t.getType() == type).count();
    }

    @Test
    void dollarBraceOutsideStringIsExprInterpStart() {
        assertEquals(1, countOfType("component A() { ${x} }", SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void dollarBraceInsideStringIsTheSameTokenType() {
        assertEquals(1, countOfType("component A() { <p id=\"a ${x} b\">y</p> }",
            SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void loneDollarOutsideStringIsNotInterpolation() {
        // "R$ 10" em texto livre continua texto: `$` sozinho nunca foi, e
        // continua a não ser, início de interpolação fora de string.
        assertEquals(0, countOfType("component A() { <p>R$ 10</p> }", SukoLexer.EXPR_INTERP_START));
    }

    @Test
    void simpleInterpStartSurvivesInsideStrings() {
        // D3 REJEITADA: `$ident` dentro de string mantém-se exatamente como
        // está. Este teste existe para falhar se alguém a remover por engano.
        assertEquals(1, countOfType("component A() { <p id=\"a $x b\">y</p> }",
            SukoLexer.SIMPLE_INTERP_START));
    }

    @Test
    void modeStackStaysBalancedAcrossBothPositions() {
        // C6: um `${` de DEFAULT_MODE que empurrasse modo deixaria a pilha
        // suja e o `}` do templateBlock faria um popMode indevido.
        List<Token> all = tokens("component A(String x, String y) { <p id=\"a ${y} b\">${x}</p> }");
        assertTrue(all.stream().anyMatch(t -> t.getType() == Token.EOF),
            "o lexer tem de chegar ao EOF sem rebentar na pilha de modos");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.SukoLexerInterpolationTest" --console=plain`

Expected: falha de **compilação** do teste ou falha de asserção em `dollarBraceOutsideStringIsExprInterpStart` (o `${` fora de string lexa hoje como `OTHER` + `LBRACE`, dando contagem 0). Os testes `loneDollarOutsideStringIsNotInterpolation` e `simpleInterpStartSurvivesInsideStrings` passam desde já.

- [ ] **Step 3: Write minimal implementation**

Em `SukoLexer.g4`, imediatamente **antes** da regra `STRING_START` (linha 143), acrescentar:

```antlr
// "${ expr }" FORA de string (subprojeto 9, D1/D2): statement, corpo de
// tag e valor de atributo. Deliberadamente SEM comando de modo — já
// estamos em DEFAULT_MODE, e empurrar um modo aqui sujaria a pilha que o
// popMode condicional do RBRACE (acima) consome: um "${" sem fecho faria
// o "}" seguinte de um templateBlock/if/for dar um pop indevido.
// O mesmo TIPO de token é reemitido de dentro de STRING_MODE (ver
// STRING_EXPR_INTERP_START, no fim deste ficheiro), o que permite ao
// parser ter uma única regra `interpolation` para as três posições.
EXPR_INTERP_START
    : '${'
    ;
```

Em `mode STRING_MODE`, substituir a regra `EXPR_INTERP_START` existente (linhas 168-173) por:

```antlr
// "${ expr }" dentro de string — a expressão em si não tem chaves no seu
// próprio grammar (sem lambdas/blocos), então um único '}' fecha sem
// ambiguidade de profundidade. Re-tipa para EXPR_INTERP_START (declarado
// em DEFAULT_MODE) de propósito: é a unificação do subprojeto 9 ao nível
// do token, e é o que permite a `stringPart` reutilizar a mesma regra
// `interpolation` do parser. O pushMode continua aqui, e SÓ aqui —
// é esta a única posição que precisa de voltar de modo.
STRING_EXPR_INTERP_START
    : '${' -> type(EXPR_INTERP_START), pushMode(DEFAULT_MODE)
    ;
```

Atualizar também o bloco de comentário das linhas 138-141 (que hoje diz que a interpolação com `$` é "usada em VALORES ... não é o mecanismo de interpolação de conteúdo de tag") — passou a ser exatamente o mesmo mecanismo nas duas posições.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser --console=plain 2>&1 | grep -i warning`
Expected: saída vazia (constrangimento global).

Run: `gradle :suko-core:test --console=plain`
Expected: PASS, incluindo os 5 testes novos e toda a suite existente (nada consome ainda o token novo).

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(lang): '\${' passa a ser token em DEFAULT_MODE, com o mesmo tipo do de STRING_MODE (D1/D2)"
```

---

### Task 2: Parser — uma regra `interpolation` para as três posições

**Agent:** `antlr4-specialist`

**Files:**
- Modify: `suko-core/src/main/antlr/io/suko/lang/SukoParser.g4:115-117` (`textRun`), `:189-193` (`attribute`), `:195-198` (`interpolation`), `:232-238` (`stringPart`)
- Modify: `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java:296-308` (`buildAttributes`) e `:361-378` (`buildStringLiteral`) — adaptação mínima ao novo shape do parse tree
- Test: `suko-core/src/test/java/io/suko/lang/SukoInterpolationSyntaxTest.java` (criar)

**Interfaces:**
- Consumes: `SukoLexer.EXPR_INTERP_START` (Task 1).
- Produces: `SukoParser.InterpolationContext` com o campo público `legacy` (`Token`, não-nulo quando a forma usada foi a chaveta nua) e o acessor `expression()`. Consumida pela Task 3 (`SukoAstBuilder`) e, indiretamente, pela Task 10 (diagnósticos).

**Porquê aqui:** depois desta tarefa as **duas** grafias funcionam em paralelo e produzem exatamente o mesmo `.jte`. É isso que permite migrar o repositório inteiro (Tasks 6-9) com a suite verde a cada commit, e só depois fechar a porta (Task 10).

- [ ] **Step 1: Write the failing test**

Criar `suko-core/src/test/java/io/suko/lang/SukoInterpolationSyntaxTest.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.support.JteRenderSupport;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Subprojeto 9 (D1/D4): `${expr}` interpola nas três posições. Enquanto
 * este subprojeto não fecha a porta (Task 10 do plano), a forma legada
 * `{expr}` continua a fazer parse e a produzir o MESMO .jte — é isso que
 * permite migrar o repositório com a suite verde.
 */
class SukoInterpolationSyntaxTest {

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

    static SukoFile ast(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void dollarBraceParsesAsStatementInterpolation() {
        assertEquals(List.of(), parseErrors("component A(String x) { ${x} }"));
    }

    /** C1: a prova de que o textRun guloso não engole o `${`. */
    @Test
    void textRunDoesNotSwallowDollarBrace() {
        SukoFile file = ast("component A(String price) { <p>custa ${price} euros</p> }");
        List<Statement> children =
            ((Statement.HtmlElement) file.components().get(0).body().get(0)).children();
        assertEquals(3, children.size(), "esperado TextRun + Interpolation + TextRun, veio: " + children);
        assertTrue(children.get(0) instanceof Statement.TextRun);
        assertTrue(children.get(1) instanceof Statement.Interpolation);
        assertTrue(children.get(2) instanceof Statement.TextRun);
    }

    @Test
    void dollarBraceRendersTheSameAsLegacyBrace() throws Exception {
        String modern = "component A(String x) { <p>${x}</p> }";
        String legacy = "component A(String x) { <p>{x}</p> }";
        assertEquals(JteRenderSupport.compileToJte(legacy, "A"),
            JteRenderSupport.compileToJte(modern, "A"));
        assertEquals("<p>oi</p>",
            JteRenderSupport.render(modern, "A", Map.of("x", "oi")).trim());
    }

    @Test
    void dollarBraceWorksAsUnquotedAttributeValue() throws Exception {
        String modern = "component A(boolean disabled) { <button disabled=${disabled}>x</button> }";
        String legacy = "component A(boolean disabled) { <button disabled={disabled}>x</button> }";
        assertEquals(List.of(), parseErrors(modern));
        assertEquals(JteRenderSupport.compileToJte(legacy, "A"),
            JteRenderSupport.compileToJte(modern, "A"));
    }

    @Test
    void whitespaceAroundStatementInterpolationIsPreserved() throws Exception {
        String source = "component A(String name) { <p>Ola, ${name}!</p> }";
        assertEquals("<p>Ola, Ana!</p>",
            JteRenderSupport.render(source, "A", Map.of("name", "Ana")).trim());
    }

    @Test
    void stringInterpolationIsUnchanged() throws Exception {
        String source = "component A(String id) { <div id=\"dialog-${id}\">x</div> }";
        assertEquals("<div id=\"dialog-7\">x</div>",
            JteRenderSupport.render(source, "A", Map.of("id", "7")).trim());
    }

    /** D3 REJEITADA: `$ident` dentro de string mantém-se. */
    @Test
    void simpleDollarIdentInStringIsUnchanged() throws Exception {
        String source = "component A(String id) { <div id=\"dialog-$id\">x</div> }";
        assertEquals("<div id=\"dialog-7\">x</div>",
            JteRenderSupport.render(source, "A", Map.of("id", "7")).trim());
    }

    /** C5: chavetas nuas dentro de string continuam texto literal (Alpine). */
    @Test
    void bareBracesInsideStringStayLiteral() throws Exception {
        String source = "component A() { <div x-data=\"{ open: false }\">x</div> }";
        assertEquals("<div x-data=\"{ open: false }\">x</div>",
            JteRenderSupport.render(source, "A", Map.of()).trim());
    }

    /** Ponte de migração: a forma legada ainda faz parse (fecha na Task 10). */
    @Test
    void legacyBraceStillParsesForNow() {
        assertEquals(List.of(), parseErrors("component A(String x) { {x} }"));
        assertEquals(List.of(), parseErrors("component A(String x) { <p id={x}>y</p> }"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.SukoInterpolationSyntaxTest" --console=plain`

Expected: falham `dollarBraceParsesAsStatementInterpolation`, `textRunDoesNotSwallowDollarBrace`, `dollarBraceRendersTheSameAsLegacyBrace`, `dollarBraceWorksAsUnquotedAttributeValue`, `whitespaceAroundStatementInterpolationIsPreserved`. Passam os cinco restantes (comportamento atual).

- [ ] **Step 3: Write minimal implementation — gramática**

Em `SukoParser.g4`, substituir a regra `interpolation` (linhas 195-198) por:

```antlr
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
```

Na regra `textRun` (linhas 115-117), acrescentar o token ao conjunto excluído — **é condição de correção, ver C1**:

```antlr
textRun
    : ( ~(LBRACE | RBRACE | LT | LTSLASH | EXPR_INTERP_START) )+
    ;
```

e acrescentar ao bloco de comentário acima do `textRun`:

```
// NOTA (subprojeto 9): EXPR_INTERP_START ("${") também tem de ser
// excluído. Este closure é guloso e não tem lookahead intermédio — sem a
// exclusão, "<p>custa ${price} euros</p>" faz o textRun engolir
// "custa ${price" como texto e o "}" sobra, silenciosamente. É a mesma
// mecânica das duas limitações já documentadas em ARCHITECTURE.md.
```

Na regra `attribute` (linhas 189-193):

```antlr
attribute
    : htmlName EQ stringLiteral
    | htmlName EQ interpolation
    | htmlName
    ;
```

Na regra `stringPart` (linhas 232-238):

```antlr
stringPart
    : STRING_TEXT
    | STRING_ESCAPE
    | SIMPLE_INTERP_START
    | interpolation
    | SIMPLE_DOLLAR
    ;
```

- [ ] **Step 4: Write minimal implementation — adaptar o `SukoAstBuilder` ao novo shape**

Duas adaptações mecânicas, sem mudança de comportamento (a marcação da forma legada é a Task 3).

Em `buildAttributes` (`SukoAstBuilder.java:296-308`), a expressão sem aspas deixa de estar diretamente no `AttributeContext`:

```java
            Expr value = attrCtx.stringLiteral() != null
                ? buildStringLiteral(attrCtx.stringLiteral())
                : attrCtx.interpolation() != null
                    ? buildExpr(attrCtx.interpolation().expression())
                    : new Expr.PrimaryExpr("true", spanOf(attrCtx));
```

Em `buildStringLiteral` (`SukoAstBuilder.java:361-378`), a parte interpolada deixa de ser reconhecida pelo token e passa a ser reconhecida pela sub-regra:

```java
            if (partCtx.interpolation() != null) {
                flushLiteral(parts, literalRun);
                parts.add(new Expr.StringPart.Interp(buildExpr(partCtx.interpolation().expression())));
            } else if (partCtx.SIMPLE_INTERP_START() != null) {
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gradle :suko-core:generateSukoLexer :suko-core:generateSukoParser --console=plain 2>&1 | grep -i warning`
Expected: saída vazia.

Run: `gradle :suko-core:test --console=plain`
Expected: PASS — os 10 testes novos e **toda** a suite existente, que continua escrita na forma legada. Se algum teste existente falhar, a ponte de compatibilidade partiu-se e é bloqueante.

Run: `gradle build --console=plain`
Expected: PASS em todos os módulos.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(lang): uma regra 'interpolation' para statement, atributo e string (D1/D2/D4)"
```

---

### Task 3: AST — `legacyBraceForm` em `Statement.Interpolation` e `Statement.Attribute`

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/ast/Statement.java:14` (`Attribute`) e `:20-21` (`Interpolation`)
- Modify: `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java:148-150`, `:277`, `:296-308`
- Test: `suko-core/src/test/java/io/suko/lang/SukoInterpolationSyntaxTest.java` (acrescentar; o helper `ast(String)` já existe lá desde a Task 2)

**Interfaces:**
- Consumes: `SukoParser.InterpolationContext.legacy` (Task 2).
- Produces:
  - `Statement.Interpolation(Expr expr, boolean legacyBraceForm, SourceSpan span)`
  - `Statement.Attribute(String name, Expr value, boolean legacyBraceForm, SourceSpan span)`

  Estes dois construtores são consumidos pela Task 10 (diagnósticos) e pela Task 4 (que só lê `attribute.value()`/`attribute.span()`, não o campo novo).

**Porquê aqui:** o campo tem de existir antes de alguém o ler. Isolado de propósito — são três sítios de construção em todo o repositório (ver "Descobertas"), e nenhum teste constrói estes records à mão.

- [ ] **Step 1: Write the failing test**

Acrescentar a `suko-core/src/test/java/io/suko/lang/SukoInterpolationSyntaxTest.java`:

```java
    @Test
    void astMarksLegacyBraceInterpolation() {
        SukoFile file = ast("component A(String x) { {x} }");
        Statement.Interpolation interpolation = (Statement.Interpolation) file.components().get(0).body().get(0);
        assertTrue(interpolation.legacyBraceForm(), "'{x}' tem de ficar marcado como forma legada");
    }

    @Test
    void astDoesNotMarkModernInterpolation() {
        SukoFile file = ast("component A(String x) { ${x} }");
        Statement.Interpolation interpolation = (Statement.Interpolation) file.components().get(0).body().get(0);
        assertFalse(interpolation.legacyBraceForm(), "'${x}' é a forma atual, não legada");
    }

    @Test
    void astMarksLegacyBraceAttribute() {
        SukoFile file = ast("component A(String x) { <p id={x}>y</p> }");
        Statement.HtmlElement element = (Statement.HtmlElement) file.components().get(0).body().get(0);
        assertTrue(element.attributes().get(0).legacyBraceForm());
    }

    @Test
    void astDoesNotMarkModernAttributeOrQuotedString() {
        SukoFile modern = ast("component A(String x) { <p id=${x}>y</p> }");
        assertFalse(((Statement.HtmlElement) modern.components().get(0).body().get(0))
            .attributes().get(0).legacyBraceForm());

        SukoFile quoted = ast("component A(String x) { <p id=\"v-${x}\">y</p> }");
        assertFalse(((Statement.HtmlElement) quoted.components().get(0).body().get(0))
            .attributes().get(0).legacyBraceForm());

        SukoFile bare = ast("component A() { <input required /> }");
        assertFalse(((Statement.HtmlElement) bare.components().get(0).body().get(0))
            .attributes().get(0).legacyBraceForm());
    }
```

Acrescentar o import `static org.junit.jupiter.api.Assertions.assertFalse;` ao mesmo ficheiro.

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.SukoInterpolationSyntaxTest" --console=plain`

Expected: falha de compilação do teste — `legacyBraceForm()` não existe em nenhum dos dois records.

- [ ] **Step 3: Write minimal implementation**

Em `suko-core/src/main/java/io/suko/lang/ast/Statement.java`:

```java
    /** `legacyBraceForm` = a interpolação foi escrita com a chaveta nua
     * `{expr}`, removida pelo subprojeto 9 (D1). Marcada no AST, e não
     * rejeitada no parser, porque a gramática tem de continuar a aceitá-la
     * para não haver recuperação silenciosa nos caminhos de parse sem
     * error listener (ProjectIndex, RegistryGenerator) — quem a rejeita é
     * o SemanticChecker. */
    record Interpolation(Expr expr, boolean legacyBraceForm, SourceSpan span) implements Statement {
    }
```

```java
    /** `legacyBraceForm` = o valor sem aspas foi escrito `attr={expr}`,
     * removido pelo subprojeto 9 (D4). Mesma mecânica de Interpolation. */
    record Attribute(String name, Expr value, boolean legacyBraceForm, SourceSpan span) {
    }
```

Em `SukoAstBuilder.java:148-150`:

```java
        if (ctx.interpolation() != null) {
            return new Statement.Interpolation(
                buildExpr(ctx.interpolation().expression()),
                ctx.interpolation().legacy != null,
                spanOf(ctx.interpolation()));
        }
```

Em `SukoAstBuilder.java:277` (o ramo `case X -> expression;` do switch, que não tem chavetas de interpolação nenhumas — é uma expressão nua na gramática):

```java
        return List.of(new Statement.Interpolation(buildExpr(exprCtx), false, spanOf(exprCtx)));
```

Em `buildAttributes` (`SukoAstBuilder.java:296-308`):

```java
            boolean legacyBraceForm = attrCtx.interpolation() != null
                && attrCtx.interpolation().legacy != null;
            attributes.add(new Statement.Attribute(name, value, legacyBraceForm, spanOf(attrCtx)));
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS (os 4 testes novos e toda a suite; nenhum outro sítio constrói estes records).

Run: `gradle build --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(ast): marca a forma legada de chaveta em Interpolation e Attribute (D5)"
```

---

### Task 4: `SemanticChecker` — travessia completa de statements para diagnósticos de sintaxe (C3)

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java:155` (chamada), `:167-190` (os dois métodos de travessia)
- Test: `suko-core/src/test/java/io/suko/lang/SukoSyntaxDiagnosticsTest.java` (criar)

**Interfaces:**
- Consumes: `Statement.Attribute.value()`/`.span()` (forma já existente; o campo novo da Task 3 não é lido aqui).
- Produces:
  - `private void checkSyntaxSurface(ComponentDecl component)` — renomeado de `checkBareBraceInStrings`
  - `private void checkSyntaxInStatement(Statement statement, java.util.Set<String> paramNames)` — renomeado de `checkBareBraceInStatement`, agora exaustivo sobre as 8 variantes de `Statement`
  - `private void checkSyntaxInStatements(java.util.List<Statement> statements, java.util.Set<String> paramNames)` — novo

  A Task 10 acrescenta os diagnósticos novos **dentro** de `checkSyntaxInStatement`, com estes nomes exatos.

**Porquê aqui, antes da Task 10:** o percurso que hoje diagnostica sintaxe tem `default -> {}` e não desce a `if`/`for`/`switch`, e nenhum dos dois percursos desce a corpos de slot fill (C3). `Alert.sk`, `Badge.sk` e `Button.sk` têm o corpo inteiro dentro de um `switch` — construir o diagnóstico de migração em cima do percurso partido deixá-lo-ia cego exatamente nos ficheiros que mais precisam dele. Esta tarefa também **fecha um buraco que já existe hoje** no `BARE_BRACE_IN_STRING`.

- [ ] **Step 1: Write the failing test**

Criar `suko-core/src/test/java/io/suko/lang/SukoSyntaxDiagnosticsTest.java`:

```java
package io.suko.lang;

import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnósticos de SINTAXE do SemanticChecker, exercitados pelo pipeline
 * real (JteCompiler: parse -> check), não por AST construído à mão.
 *
 * C3 do plano do subprojeto 9: o percurso que emite estes diagnósticos
 * tinha `default -> {}` e não descia a if/for/switch nem a corpos de slot
 * fill — e os três componentes mais usados da biblioteca (Alert, Badge,
 * Button) têm o corpo INTEIRO dentro de um switch.
 */
class SukoSyntaxDiagnosticsTest {

    private static List<SukoDiagnostic> diagnosticsOf(String source) {
        return new JteCompiler("test.sk", source).compile().diagnostics().getDiagnostics();
    }

    private static long countOf(String source, String code) {
        return diagnosticsOf(source).stream().filter(d -> code.equals(d.code())).count();
    }

    @Test
    void bareBraceInStringIsFoundAtTopLevel() {
        String source = """
            component Button(String variant) {
              <a class="btn btn-{variant}">Click</a>
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideSwitchCase() {
        String source = """
            component Badge(String variant) {
              switch (variant) {
                case "success" -> {
                  <span class="badge badge-{variant}">ok</span>
                }
                default -> {
                  <span>x</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideIfBranch() {
        String source = """
            component Card(String variant, boolean shown) {
              if (shown) {
                <div class="card card-{variant}">x</div>
              } else {
                <div class="card card-{variant}">y</div>
              }
            }
            """;
        assertEquals(2, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideForBody() {
        String source = """
            component ListView(String variant, List<String> items) {
              for (String item : items) {
                <li class="row row-{variant}">z</li>
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideSlotFillBody() {
        String source = """
            component Box(Component header) {
              <div>{header}</div>
            }

            component Page(String variant) {
              Box() {
                header {
                  <span class="h h-{variant}">t</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void cleanComponentProducesNoSyntaxDiagnostics() {
        String source = """
            component Badge(String variant) {
              switch (variant) {
                case "success" -> {
                  <span class="badge badge-success">${variant}</span>
                }
                default -> {
                  <span class="badge">${variant}</span>
                }
              }
            }
            """;
        assertTrue(diagnosticsOf(source).isEmpty(), "sem diagnósticos: " + diagnosticsOf(source));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.SukoSyntaxDiagnosticsTest" --console=plain`

Expected: `bareBraceInStringIsFoundAtTopLevel` e `cleanComponentProducesNoSyntaxDiagnostics` passam; **falham** `...InsideSwitchCase`, `...InsideIfBranch`, `...InsideForBody`, `...InsideSlotFillBody` (esperado 1/2, obtido 0) — são os quatro buracos de C3.

- [ ] **Step 3: Write minimal implementation**

Em `SemanticChecker.java`, substituir `checkBareBraceInStrings` (`:167-173`) e `checkBareBraceInStatement` (`:175-190`) por:

```java
    /** Diagnósticos de SINTAXE sobre o corpo de um componente
     * (`BARE_BRACE_IN_STRING`, `VAR_DECL_NOT_PARSED`, e os de forma legada
     * do subprojeto 9). Renomeado de `checkBareBraceInStrings` quando
     * deixou de ser só sobre chavetas em strings. */
    private void checkSyntaxSurface(ComponentDecl component) {
        java.util.Set<String> paramNames = component.params().stream()
                .map(Param::name).collect(java.util.stream.Collectors.toSet());
        checkSyntaxInStatements(component.body(), paramNames);
    }

    private void checkSyntaxInStatements(List<Statement> statements, java.util.Set<String> paramNames) {
        for (Statement statement : statements) {
            checkSyntaxInStatement(statement, paramNames);
        }
    }

    // EXAUSTIVO SOBRE AS 8 VARIANTES DE `Statement`, sem `default` — de
    // propósito. A versão anterior tinha `default -> {}` e não descia a
    // if/for/switch, e nenhum percurso do checker descia a corpos de slot
    // fill: Alert.sk, Badge.sk e Button.sk têm o corpo INTEIRO dentro de um
    // switch, logo o BARE_BRACE_IN_STRING nunca os via. Sem `default`, uma
    // variante nova de Statement passa a ser erro de compilação aqui, em
    // vez de um buraco silencioso.
    private void checkSyntaxInStatement(Statement statement, java.util.Set<String> paramNames) {
        switch (statement) {
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    checkBareBraceInExpr(attribute.value(), paramNames, attribute.span());
                }
                checkSyntaxInStatements(element.children(), paramNames);
            }
            case Statement.Interpolation interpolation ->
                checkBareBraceInExpr(interpolation.expr(), paramNames, interpolation.span());
            case Statement.TextRun textRun -> checkSwallowedVarDecl(textRun);
            case Statement.VarDecl varDecl ->
                checkBareBraceInExpr(varDecl.value(), paramNames, varDecl.span());
            case Statement.IfStmt ifStmt -> {
                checkBareBraceInExpr(ifStmt.condition(), paramNames, ifStmt.span());
                checkSyntaxInStatements(ifStmt.thenBranch(), paramNames);
                checkSyntaxInStatements(ifStmt.elseBranch(), paramNames);
            }
            case Statement.ForStmt forStmt -> {
                checkBareBraceInExpr(forStmt.iterable(), paramNames, forStmt.span());
                checkSyntaxInStatements(forStmt.body(), paramNames);
            }
            case Statement.SwitchStmt switchStmt -> {
                checkBareBraceInExpr(switchStmt.subject(), paramNames, switchStmt.span());
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    checkBareBraceInExpr(switchCase.matchValue(), paramNames, switchStmt.span());
                    checkSyntaxInStatements(switchCase.body(), paramNames);
                }
                checkSyntaxInStatements(switchStmt.defaultCase(), paramNames);
            }
            case Statement.ComponentCallStmt call -> {
                for (Statement.Arg arg : call.args()) {
                    checkBareBraceInExpr(arg.value(), paramNames, call.span());
                }
                for (Statement.SlotFill fill : call.slotFills()) {
                    checkSyntaxInStatements(fill.body(), paramNames);
                }
            }
        }
    }
```

Em `checkComponent` (`SemanticChecker.java:155`), trocar a chamada `checkBareBraceInStrings(component);` por `checkSyntaxSurface(component);`.

`checkBareBraceInExpr` (`:225-247`) e `checkSwallowedVarDecl` (`:206-223`) **não mudam**.

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS nos 6 testes novos. **A suite inteira tem de continuar verde** — a travessia mais funda pode destapar um `{ident}` em string dentro de um `switch`/`if`/`for` nalgum teste existente que nunca tinha sido visto. Se isso acontecer, é um achado real (o teste estava a documentar um bug não detetado): corrigir o `.sk` do teste e registar no ledger.

Run: `gradle build --console=plain`
Expected: PASS em todos os módulos, incluindo `:suko-components:test` (a biblioteca não tem nenhuma chaveta nua em `class`, por convenção 3 — se algum componente falhar aqui, a convenção estava a ser violada sem ninguém ver).

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "fix(semantic): travessia de sintaxe passa a descer a if/for/switch e slot fills (C3)"
```

---

### Task 5: D6 — o escape `\$` passa a produzir Java válido

**Agent:** `jte-specialist`

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java:361-385` (`buildStringLiteral` + helper novo)
- Test: `suko-core/src/test/java/io/suko/lang/StringEscapeTest.java` (criar)

**Interfaces:**
- Consumes: nada das tarefas anteriores.
- Produces: `private String javaEscapeOf(String sukoEscape)` em `SukoAstBuilder`. Nenhum outro consumidor.

**Porquê aqui:** isolado e independente das Tasks 1-4 — pode correr em qualquer ordem entre a Task 3 e a Task 6. Tem de entrar **antes** de a Task 10 fechar a porta ao `{expr}`, porque a partir daí `\$` é a única forma de escrever um `$` literal numa string, e hoje ela produz `.jte` que não compila (C7).

**Escolha de tradução, e porque não o óbvio:** `\$` traduz para a sequência de 6 caracteres `\u0024`, **não** para um `$` cru. Um `$` cru dentro do literal Java emitido volta a aparecer no texto do `.jte`, onde um `${` seguinte seria lido pelo próprio `gg.jte` como início de interpolação. `\u0024` nunca forma um `${` no texto do `.jte`, e o `javac` resolve-o para `$` no pré-processamento de escapes unicode, antes de lexar — o resultado renderizado é idêntico. (Em código Java, a constante escreve-se `"\\u0024"`: o segundo backslash é precedido por um número ímpar de backslashes contíguos, portanto **não** inicia um escape unicode no próprio `SukoAstBuilder.java`.)

- [ ] **Step 1: Write the failing test**

Criar `suko-core/src/test/java/io/suko/lang/StringEscapeTest.java`:

```java
package io.suko.lang;

import io.suko.lang.support.JteRenderSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Escapes dentro de literais de string Suko. Antes do subprojeto 9 não
 * existia um único teste sobre isto (C7 do plano) e `\$` produzia Java
 * inválido — "illegal escape character" no javac. Com `${...}` a ser o
 * único sigilo de interpolação em todas as posições (D1), `\$` passou a
 * ser a única saída de emergência do autor para escrever um `$` literal.
 */
class StringEscapeTest {

    @Test
    void escapedDollarRendersAsLiteralDollar() throws Exception {
        String source = """
            component Price() {
              <p>${"custa \\$5"}</p>
            }
            """;
        assertEquals("<p>custa $5</p>", JteRenderSupport.render(source, "Price", Map.of()).trim());
    }

    @Test
    void escapedDollarInAttributeRendersAsLiteralDollar() throws Exception {
        String source = """
            component Price() {
              <p title="custa \\$5">x</p>
            }
            """;
        assertEquals("<p title=\"custa $5\">x</p>",
            JteRenderSupport.render(source, "Price", Map.of()).trim());
    }

    @Test
    void escapedQuoteStillWorks() throws Exception {
        String source = """
            component Q() {
              <p>${"diz \\"oi\\""}</p>
            }
            """;
        assertEquals("<p>diz &#34;oi&#34;</p>", JteRenderSupport.render(source, "Q", Map.of()).trim());
    }

    @Test
    void escapedBackslashStillWorks() throws Exception {
        String source = """
            component B() {
              <p>${"c:\\\\tmp"}</p>
            }
            """;
        assertEquals("<p>c:\\tmp</p>", JteRenderSupport.render(source, "B", Map.of()).trim());
    }

    @Test
    void unescapedDollarIdentStillInterpolates() throws Exception {
        // D3 REJEITADA: o escape não pode ter partido a forma curta.
        String source = """
            component Greet(String name) {
              <p title="ola $name">x</p>
            }
            """;
        assertEquals("<p title=\"ola Ana\">x</p>",
            JteRenderSupport.render(source, "Greet", Map.of("name", "Ana")).trim());
    }
}
```

Sobre `escapedQuoteStillWorks`: o valor esperado é o HTML **escapado pelo `gg.jte`** (`ContentType.Html`). Se o motor real produzir `&quot;` em vez de `&#34;`, ajustar a expectativa ao que o motor produz e registar o valor observado no ledger — é comportamento do `gg.jte`, não do Suko.

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.StringEscapeTest" --console=plain`

Expected: falham `escapedDollarRendersAsLiteralDollar` e `escapedDollarInAttributeRendersAsLiteralDollar` com um erro de compilação do `.jte` vindo do `javac` ("illegal escape character"), exatamente como registado na sonda (a) da Task 0. Os outros três passam.

- [ ] **Step 3: Write minimal implementation**

Em `SukoAstBuilder.buildStringLiteral` (`:361-378`), o ramo final deixa de copiar o texto cru de qualquer parte:

```java
            } else if (partCtx.STRING_ESCAPE() != null) {
                literalRun.append(javaEscapeOf(partCtx.getText()));
            } else {
                literalRun.append(partCtx.getText());
            }
```

E, a seguir a `flushLiteral` (`:380-385`):

```java
    /** Um escape Suko é sempre `\` + 1 caractere (token STRING_ESCAPE). O
     * texto acumulado aqui vai parar DENTRO de um literal de string Java
     * emitido no `.jte`, por isso tem de ser um escape que o javac aceite.
     *
     * `\$` não é escape Java válido ("illegal escape character") — e é a
     * única forma de escrever um `$` literal desde que `${...}` passou a
     * ser o único sigilo de interpolação em todas as posições (subprojeto
     * 9, D1/D6). Traduz-se para `\u0024` e NÃO para um `$` cru: um `$` cru
     * volta a aparecer no texto do `.jte`, onde um `${` seguinte seria
     * lido pelo próprio gg.jte como início de interpolação; `\u0024` nunca
     * forma um `${` no `.jte`, e o javac resolve-o para `$` no
     * pré-processamento de escapes unicode, antes de lexar.
     *
     * Qualquer outro escape passa intacto: `\"`, `\\`, `\n`, `\t`, `\r`,
     * `\b`, `\f`, `\s` já são válidos em Java, e traduzi-los aqui só
     * arriscaria mudar-lhes o significado. */
    private String javaEscapeOf(String sukoEscape) {
        if (sukoEscape.equals("\\$")) {
            return "\\u0024";
        }
        return sukoEscape;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS nos 5 testes novos e em toda a suite.

Caso especial, decidido pela sonda (a) da Task 0: se lá ficou registado que `"literal \${x}"` não sobrevive (o `gg.jte` fecha a interpolação cedo no `}` do texto literal), **não** tentar corrigi-lo aqui — acrescentar o teste marcado `@Disabled("gg.jte fecha a interpolação no primeiro '}' do texto literal — ver sonda (a) da Task 0")` com a mensagem exata observada, e registar no ledger para a Task 13 o documentar como limitação.

Run: `gradle build --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "fix(ast): escape '\\\$' passa a produzir Java valido (D6)"
```

---

### Task 6: Migrar os 8 componentes da biblioteca e regenerar o manifesto

**Agent:** `dx-specialist`

**Files:**
- Modify: `suko-components/src/main/suko/io/suko/ui/Alert.sk`, `Badge.sk`, `Button.sk`, `Card.sk`, `Dialog.sk`, `Field.sk`, `Input.sk`, `Label.sk`
- Modify: `suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java:68-83` (bump de versão)
- Modify (gerados, via task Gradle — **nunca à mão**): `suko-components/registry.json`, `suko-components/components/*.json`
- Test: `suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java` (já existente, é a rede)

**Interfaces:**
- Consumes: a gramática da Task 2 (as duas grafias funcionam em paralelo, por isso esta migração não pode partir nada).
- Produces: os 8 `.sk` na grafia nova e um manifesto regenerado em `0.2.0`. Consumido pela Task 9 (o `sha256` de `Label.sk` muda) e pela Task 14.

**Porquê aqui:** a biblioteca é o consumidor mais visível da linguagem e o único com manifesto gerado. Migrá-la primeiro põe o mecanismo de regeneração à prova cedo, enquanto o resto do repositório ainda está na grafia antiga e serve de controlo.

- [ ] **Step 1: Migrar os 8 `.sk`**

Substituições exatas, ficheiro a ficheiro:

- `Label.sk:4`: `<label>{text}</label>` → `<label>${text}</label>`
- `Card.sk:18`, `:22`, `:26`: `{header}` → `${header}`, `{children}` → `${children}`, `{footer}` → `${footer}`
- `Alert.sk:16`, `:21`, `:26`, `:31`: `{children}` → `${children}` (4 ramos do switch)
- `Badge.sk:9`, `:14`, `:19`, `:24`: `{children}` → `${children}` (4 ramos do switch)
- `Button.sk:11`, `:16`, `:21`: `disabled={disabled}` → `disabled=${disabled}` (3 ramos); `:12`, `:17`, `:22`: `{label}` → `${label}`
- `Input.sk:4`: `id={id} name={name} type={type} required={required} placeholder={placeholder}` → `id=${id} name=${name} type=${type} required=${required} placeholder=${placeholder}`
- `Dialog.sk:19`: `<h2 ...>{title}</h2>` → `<h2 ...>${title}</h2>`; `:21`: `{children}` → `${children}`
- `Field.sk`: **não tem interpolação nenhuma** — só o comentário do Step 2 abaixo.

**Não mudar** em `Dialog.sk:17`: `id="dialog-${id}"` (já é a forma atual) nem `x-data="{ open: false }"` (chavetas dentro de string continuam texto literal — é a prova de que D1 não parte o Alpine).

- [ ] **Step 2: Reescrever os comentários que descrevem a linguagem antiga**

- `Dialog.sk:3-15`: o bloco inteiro explica a coexistência das duas sintaxes ("a interpolação Suko dentro de um literal string exige `$` ... prova empírica de que os dois convivem"). **Reescrever**, não ajustar. Texto novo:

```
// Único componente da biblioteca com `externalRequirements` de
// Alpine.js (Tarefa 9 do subprojeto 7, manifesto). `x-data`/`x-show` são
// JavaScript literal do Alpine — as chavetas nuas de `{ open: false }`
// são texto literal dentro de uma string Suko, e continuam a sê-lo
// depois da unificação do subprojeto 9: a regra da linguagem é que
// interpolação é SEMPRE `${...}` e uma chaveta nua nunca interpola, em
// posição nenhuma.
//
// `id="dialog-${id}"` ao lado É interpolação Suko real — prova empírica
// de que o JavaScript do Alpine e a interpolação do Suko convivem no
// mesmo atributo sem ambiguidade.
```

- `Alert.sk:3-4`, `Badge.sk:3-4`, `Button.sk:3-7`: a convenção 3 ("nunca `${...}` dentro de `class`") **continua válida e correta** — não mexer no conteúdo, só confirmar que continua a ler-se bem agora que `${...}` é a única forma.

- [ ] **Step 3: Bump de versão para 0.2.0**

Em `suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java`, substituir as 9 ocorrências de `"0.1.0"` por `"0.2.0"` (8 `ComponentConfig` nas linhas 68-78 + o `registryVersion` na linha 83). Acrescentar o comentário, imediatamente acima do bloco `componentConfigs`:

```java
    // 0.2.0 (subprojeto 9): o fonte destes componentes passou a usar
    // `${...}` como única forma de interpolação e exige um compilador Suko
    // do subprojeto 9 ou posterior. O manifesto não tem campo de versão
    // mínima de linguagem (C8) — o que protege um consumidor antigo é a
    // tag por omissão do registry derivar da versão da CLI.
```

- [ ] **Step 4: Regenerar o manifesto e correr a rede**

```bash
gradle :suko-components:generateRegistry --console=plain
/usr/bin/git diff --stat suko-components/registry.json suko-components/components/
```

Expected: `registry.json` + 8 `components/*.json` alterados; cada um com `sha256` novo e `version` `0.2.0`.

Run: `gradle :suko-components:test --console=plain`
Expected: PASS — `RegistryGoldenTest` (manifesto commitado == regenerado), `LibraryConventionsTest` (convenção 3 continua satisfeita), `LibraryCompilesTest`, `LeafComponentsRenderTest`, `ChildrenComponentsRenderTest`, `AlpineInteropTest` (o `x-data` do Dialog continua literal).

- [ ] **Step 5: Confirmar que nada mais mudou**

Run: `gradle build --console=plain`
Expected: PASS em todos os módulos. `:suko-cli:test` continua verde porque copia o registry real em tempo de teste e todos os hashes que compara vêm do manifesto regenerado. O `sha256` hard-coded de `UpdateCommandTest.java:249` **não** faz o teste falhar — é um `assertNotEquals` contra o hash antigo do `Label.sk`, que continua trivialmente verdadeiro depois da migração. O problema é que deixa de testar o que diz testar; a correção é da Task 9 e não deve ser antecipada aqui.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "refactor(components): migra os 8 componentes para \${expr} e regenera o manifesto em 0.2.0 (D7)"
```

---

### Task 7: Migrar os `.sk` restantes do repositório (exemplos + fixtures)

**Agent:** `dx-specialist`

**Files:**
- Modify: `examples/Card.sk:6,10,14,20`
- Modify: `examples/dashboard/Dashboard.sk:7`
- Modify: `examples/forms/Forms.sk:11`
- Modify: `examples/invalid/Card.sk:8,16`
- Modify: `examples/layout/LayoutComponents.sk:6,12,16,19,23,32,33,36,39,49,50,53,56,64,65,70,74`
- Modify: `suko-registry-generator/src/test/resources/generator-fixtures/valid/io/suko/ui/Label.sk:4`
- Modify: `suko-registry-generator/src/test/resources/generator-fixtures/not-public/io/suko/ui/Bad.sk:4`
- Modify: `suko-registry-generator/src/test/resources/generator-fixtures/two-components-per-file/io/suko/ui/Bad.sk:4,8`
- Modify: `suko-registry-generator/src/test/resources/generator-fixtures/duplicate-name-different-package/io/suko/ui/Field.sk:4`
- Modify: `suko-registry-generator/src/test/resources/generator-fixtures/duplicate-name-different-package/io/suko/other/Field.sk:4`
- Test: `suko-core/src/test/java/io/suko/lang/JteEmitterGoldenFileTest.java` (já existente — é a rede de C4)

**Interfaces:**
- Consumes: a gramática da Task 2.
- Produces: nada de código. Produz o critério de aceitação de C4 verificado sobre um ficheiro real.

**Não tocar:** `suko-cli/src/test/resources/rewrite-fixtures/html-noise.sk` (não tem nenhuma interpolação; confirmado por grep), `suko-registry-generator/.../cyclic/io/suko/ui/A.sk` e `B.sk` (idem), `suko-registry-generator/.../valid/io/suko/ui/Field.sk` (idem).

**Regra mecânica, sem exceções:** cada `{ident}` ou `{expr}` em posição de statement passa a `${...}`; cada `attr={expr}` passa a `attr=${expr}`; **nenhuma** string entre aspas muda — `class="btn btn-${variant}"` e `"$name"` já estão na forma atual.

- [ ] **Step 1: Verificar o golden ANTES de tocar em nada**

Run: `gradle :suko-core:test --tests "io.suko.lang.JteEmitterGoldenFileTest" --console=plain`
Expected: PASS. É a linha de base: `suko-core/src/test/resources/golden/Card.jte` e `NavLink.jte` têm de ficar byte a byte iguais no fim desta tarefa.

- [ ] **Step 2: Migrar `examples/`**

`examples/Card.sk`: `<h2>{title}</h2>` → `<h2>${title}</h2>` (linha 6); `<li>{item}</li>` → `<li>${item}</li>` (10); `<p>{emptyLabel}</p>` → `<p>${emptyLabel}</p>` (14); `<a href={href}>{label}</a>` → `<a href=${href}>${label}</a>` (20).

`examples/dashboard/Dashboard.sk:7`: `<a href={href}>{label}</a>` → `<a href=${href}>${label}</a>`.

`examples/forms/Forms.sk:11`: `<div class="error">{error}</div>` → `<div class="error">${error}</div>`.

`examples/invalid/Card.sk:8,16`: `{title}` e `{emptyLabel}` → `${title}`, `${emptyLabel}`. (Este ficheiro é deliberadamente inválido por outra razão — generics — e continua a sê-lo; a migração não o torna válido nem o pretende.)

`examples/layout/LayoutComponents.sk`: todas as interpolações de statement (`{title}`, `{header}`, `{sidebar}`, `{body}`, `{footer}`, `{label}`) e todos os atributos sem aspas (`href={href}`, `disabled={disabled}`, `id={id}`, `name={name}`, `type={type}`, `required={required}`, `placeholder={placeholder}`, `minlength={minlength}`). **A linha 64 é o caso emblemático** — `<a href={href} class="btn btn-${variant}" disabled={disabled}>` passa a `<a href=${href} class="btn btn-${variant}" disabled=${disabled}>`: as três posições ficam com o mesmo sigilo, que é exatamente a tese deste subprojeto.

- [ ] **Step 3: Migrar os fixtures do gerador**

Cinco `.sk`, todos com a mesma substituição `<tag>{text}</tag>` → `<tag>${text}</tag>`:
`valid/io/suko/ui/Label.sk:4`, `not-public/io/suko/ui/Bad.sk:4`, `two-components-per-file/io/suko/ui/Bad.sk:4` e `:8`, `duplicate-name-different-package/io/suko/ui/Field.sk:4`, `duplicate-name-different-package/io/suko/other/Field.sk:4`.

- [ ] **Step 4: Confirmar C4 — os golden files não mudaram**

Run: `gradle :suko-core:test --tests "io.suko.lang.JteEmitterGoldenFileTest" --console=plain`
Expected: PASS **sem alterar nenhum ficheiro em `suko-core/src/test/resources/golden/`**.

```bash
/usr/bin/git status --short suko-core/src/test/resources/golden/
```
Expected: saída vazia. Se algum golden aparecer como modificado, **parar**: é regressão de emissão, não migração, e viola C4.

Run: `gradle :suko-registry-generator:test --console=plain`
Expected: PASS.

- [ ] **Step 5: Verificar que não sobrou nenhum `.sk`**

```bash
grep -rnE '(^|[^$])\{[a-zA-Z_][a-zA-Z0-9_.()?: ]*\}|=\{[a-zA-Z_]' --include='*.sk' . | grep -v '/build/'
```
Expected: saída vazia.

Run: `gradle build --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "refactor(examples): migra exemplos e fixtures .sk para \${expr} (D7)"
```

---

### Task 8: Migrar o fonte `.sk` inline de `suko-core` — família do emitter

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-core/src/test/java/io/suko/lang/JteEmitterTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/JteEmitterSourceMapTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/JteEmitterProjectTest.java`
- Test: os próprios ficheiros acima

**Interfaces:**
- Consumes: a gramática da Task 2.
- Produces: nada de código.

**Porquê esta família sozinha:** `JteEmitterTest.java` tem 28 KB e é o ficheiro com mais fonte `.sk` inline do repositório; juntá-lo aos outros dez faria uma tarefa impossível de rever. Os outros dois são da mesma família (emitter) e partilham o mesmo modo de falha.

**Aviso que vale para as Tasks 8, 9 e 10:** `JteRenderSupport.compileToJte`/`render` **não** passam pelo `SemanticChecker` — vão direto de `SukoAstBuilder` para `JteEmitter`. Um teste de emitter que fique por migrar **não** falha na Task 11, quando a forma legada passar a erro. Por isso a verificação por `grep` do Step 3 não é redundante com a suite: é a única rede para estes ficheiros.

- [ ] **Step 1: Migrar as três classes**

Aplicar a mesma regra mecânica da Task 7 a todo o fonte `.sk` dentro de text blocks e literais Java: `{ident}`/`{expr}` em posição de statement → `${...}`; `attr={expr}` → `attr=${expr}`; nada dentro de aspas muda.

**Cuidado específico deste ficheiro:** `JteEmitterTest` compara, em várias asserções, o texto `.jte` **esperado**, que já contém `${...}`. Esses literais são a **saída**, não a entrada — não se tocam. Só muda o `.sk` de entrada. Se uma asserção de saída precisar de mudar para o teste passar, é regressão de emissão (viola C4) e a tarefa tem de parar.

- [ ] **Step 2: Run tests**

Run: `gradle :suko-core:test --tests "io.suko.lang.JteEmitterTest" --tests "io.suko.lang.JteEmitterSourceMapTest" --tests "io.suko.lang.JteEmitterProjectTest" --console=plain`
Expected: PASS, **sem nenhuma alteração a valores esperados**.

- [ ] **Step 3: Verificar que não sobrou entrada por migrar nestes três ficheiros**

```bash
grep -nE '>\{[a-zA-Z_]|=\{[a-zA-Z_]|^\s*\{[a-zA-Z_][a-zA-Z0-9_.()]*\}\s*$' \
  suko-core/src/test/java/io/suko/lang/JteEmitterTest.java \
  suko-core/src/test/java/io/suko/lang/JteEmitterSourceMapTest.java \
  suko-core/src/test/java/io/suko/lang/JteEmitterProjectTest.java
```
Expected: só linhas que sejam `.jte` **esperado** (saída). Rever uma a uma e confirmar que nenhuma é fonte `.sk` de entrada.

- [ ] **Step 4: Run full module test**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "test(core): migra o .sk inline dos testes de emitter para \${expr} (D7)"
```

---

### Task 9: Migrar o fonte `.sk` inline de `suko-core` — parser, AST, semântica, projeto

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-core/src/test/java/io/suko/lang/FinalReviewFixesTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/JteCompilerTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/SemanticCheckerTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/SukoEndToEndTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/SukoGrammarFixesTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/SukoParserSmokeTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/project/MultiFileAcceptanceTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/project/ProjectIndexTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/project/SukoProjectCompilerTest.java`
- Modify: `suko-core/src/test/java/io/suko/lang/semantic/SemanticCheckerProjectTest.java`
- Modify (só comentários/javadoc, sem código): `suko-core/src/main/java/io/suko/lang/JteEmitter.java`, `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java`, `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java`

**Interfaces:**
- Consumes: a gramática da Task 2.
- Produces: nada de código.

**Porquê separada da Task 8:** ao contrário da família do emitter, **estes passam pelo `SemanticChecker`** (via `JteCompiler`/`SukoProjectCompiler`), logo qualquer omissão aqui rebenta na Task 11 — o que é bom, mas só se a Task 11 vier depois, como vem.

- [ ] **Step 1: Migrar os dez ficheiros de teste**

Mesma regra mecânica das Tasks 7 e 8.

Casos que exigem atenção e **não** são substituição cega:

- `SemanticCheckerTest.java:326-350` (`bareBraceInStringSuggestsDollarSyntax`): o `class="btn btn-{variant}"` é a **entrada deliberadamente errada** do teste do `BARE_BRACE_IN_STRING`. **Não migrar** — migrá-lo destrói o teste. A expectativa (`diag.message().contains("${variant}")`) também não muda.
- `FinalReviewFixesTest.java:50`: asserção de **ausência** de `BARE_BRACE_IN_STRING`. Migrar o `.sk` de entrada só se ele usar chaveta de statement; a asserção não muda.
- `SukoGrammarFixesTest.java`: os casos que testam texto livre com palavras como `for`/`if` não têm interpolação e não se tocam.

- [ ] **Step 2: Atualizar os comentários de `src/main` que descrevem a sintaxe antiga**

Três sítios, só prosa:

- `JteEmitter.java:405-410` — o javadoc de `shouldWrapIdentifierInToString` fala das "duas posições em que uma interpolação pode aparecer: `Statement.Interpolation` (`{expr}`, tarefa 10) e `Expr.StringPart.Interp`/`SimpleInterp` (`"...${expr}..."`, tarefa 9)". Passa a `${expr}` na primeira, mantendo a referência histórica às tarefas do subprojeto 6.
- `JteEmitter.java:355-399` — o bloco de DESVIO DO BRIEF cita exemplos `{a + b}`, `{a > b}`, `{label?.length() ?: -1}`, `{row(item)}`, `{header}`, `{c}`. São exemplos de sintaxe Suko: passam todos a `${...}`.
- `SemanticChecker.java:161-166` e `:195-205` — os javadoc de `checkBareBraceInExpr` e `checkSwallowedVarDecl` citam `{...}`/`{ ... }`. O de `checkBareBraceInExpr` continua correto no essencial (chavetas dentro de string são texto literal) e ganha uma frase: depois do subprojeto 9, a chaveta nua não interpola **em posição nenhuma**, não só dentro de strings.

- [ ] **Step 3: Run tests**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS na suite inteira.

- [ ] **Step 4: Verificar que não sobrou entrada por migrar**

```bash
grep -rnE '>\{[a-zA-Z_]|=\{[a-zA-Z_]' --include='*.java' suko-core/src | grep -v '/build/'
```
Expected: só `SemanticCheckerTest.java` (o caso deliberado do `BARE_BRACE_IN_STRING`) e linhas que sejam `.jte` esperado. Rever uma a uma.

Run: `gradle :suko-core:test --tests "io.suko.lang.JteEmitterGoldenFileTest" --console=plain` e `/usr/bin/git status --short suko-core/src/test/resources/golden/`
Expected: PASS e saída vazia (C4).

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "test(core): migra o .sk inline dos testes de parser/semantica/projeto para \${expr} (D7)"
```

---

### Task 10: Migrar o `.sk` inline dos módulos restantes e corrigir o hash hard-coded

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoGradlePluginTest.java`
- Modify: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoPluginFunctionalTest.java`
- Modify: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoWatchTaskE2ETest.java`
- Modify: `suko-components/src/test/java/io/suko/components/AlpineInteropTest.java`
- Modify: `suko-components/src/test/java/io/suko/components/LeafComponentsRenderTest.java`
- Modify: `suko-components/src/test/java/io/suko/components/LibraryCompilesTest.java`
- Modify: `suko-components/src/test/java/io/suko/components/LibraryConventionsTest.java`
- Modify: `suko-cli/src/test/java/io/suko/cli/command/UpdateCommandTest.java:249`
- Test: os próprios ficheiros acima

**Interfaces:**
- Consumes: o manifesto regenerado da Task 6 (o `sha256` novo de `Label.sk`).
- Produces: nada de código.

**Sobre `suko-maven-plugin`, `suko-registry` e `suko-registry-generator`:** o fonte `.sk` inline destes módulos **não tem interpolação nenhuma** (confirmado por grep ao escrever este plano) — não entram nesta tarefa. `suko-cli` também não tem: o seu `.sk` inline é conteúdo como `<div class="label-v2">{{ text }}</div>`, que é texto literal e nunca é parseado pela CLI (que não depende de `suko-core` em produção). O único item de `suko-cli` é o hash do Step 3.

- [ ] **Step 1: Migrar os três testes do plugin Gradle e os quatro de `suko-components`**

Mesma regra mecânica das Tasks 7-9.

Cuidado específico em `LibraryConventionsTest.java`: o javadoc (linhas 31-37 e 138-155) descreve a convenção 3 em termos de `${...}` **e** da forma sem aspas `class={variant}`. O teste em si opera sobre o AST e não muda; o javadoc tem de passar a escrever a forma sem aspas como `class=${variant}`, que é como ela se escreve depois de D4.

Cuidado específico em `AlpineInteropTest.java`: é o teste que prova que `x-data="{ open: false }"` é texto literal. **A entrada não muda** — se mudar, o teste deixou de testar o que interessa.

- [ ] **Step 2: Run tests dos módulos migrados**

Run: `gradle :suko-gradle-plugin:test :suko-components:test --console=plain`
Expected: PASS.

- [ ] **Step 3: Corrigir o `sha256` hard-coded de `UpdateCommandTest`**

`suko-cli/src/test/java/io/suko/cli/command/UpdateCommandTest.java:249` compara contra `"74a03dcf4e832ddb09b3e30225dc35dc8d118630b8ccd0198051c19dd8beeca3"`, que era o `sha256` do `Label.sk` **antes** da migração. Depois da Task 6 o `assertNotEquals` continua trivialmente verdadeiro e o teste deixou de testar o que diz testar.

Obter o valor novo:

```bash
grep -A3 '"path": "src/main/suko/io/suko/ui/Label.sk"' suko-components/components/label.json
```

Substituir a constante pelo `sha256` novo e acrescentar o comentário:

```java
        // O sha256 do Label.sk upstream ANTES do tamper acima. Tem de ser
        // atualizado sempre que o Label.sk da biblioteca mudar (mudou no
        // subprojeto 9, com a migração para `${expr}`): o valor sai de
        // suko-components/components/label.json. Sem isto o assertNotEquals
        // passa a ser trivialmente verdadeiro e deixa de provar que o
        // upstreamSha256 foi mesmo atualizado.
```

- [ ] **Step 4: Run tests**

Run: `gradle :suko-cli:test --console=plain`
Expected: PASS, incluindo `conflictWithForceOverwritesAndUpdatesBothHashes` e `FullCycleTest`.

Run: `gradle build --console=plain`
Expected: PASS em todos os módulos.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "test: migra o .sk inline dos modulos restantes e atualiza o sha256 do Label.sk (D7)"
```

---

### Task 11: Fechar a porta — `LEGACY_BRACE_INTERPOLATION` e `LEGACY_BRACE_ATTRIBUTE` como ERROR

**Agent:** `dx-specialist`

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java` (dentro de `checkSyntaxInStatement`, criado na Task 4)
- Test: `suko-core/src/test/java/io/suko/lang/SukoSyntaxDiagnosticsTest.java` (criado na Task 4 — acrescentar)
- Modify: `suko-core/src/test/java/io/suko/lang/SukoInterpolationSyntaxTest.java` (o teste `legacyBraceStillParsesForNow` da Task 2 ganha a nota de que a porta fechou)

**Interfaces:**
- Consumes: `Statement.Interpolation.legacyBraceForm()` e `Statement.Attribute.legacyBraceForm()` (Task 3); `checkSyntaxInStatement(Statement, java.util.Set<String>)` e `checkSyntaxInStatements(java.util.List<Statement>, java.util.Set<String>)` (Task 4).
- Produces: os códigos de diagnóstico `LEGACY_BRACE_INTERPOLATION` e `LEGACY_BRACE_ATTRIBUTE`, ambos `Severity.ERROR`.

**Porquê no fim da migração e não no início:** entre a Task 2 e esta, as duas grafias funcionam em paralelo, o que mantém a suite verde a cada commit. Se este diagnóstico entrasse antes, todas as tarefas de migração deixariam o branch vermelho.

**Não existe `LEGACY_DOLLAR_IDENT`.** D3 foi rejeitada: `$ident` dentro de strings é sintaxe atual e válida, não legada.

**Alcance real deste portão, dito por extenso:** o diagnóstico só dispara em quem passa pelo `SemanticChecker` — `JteCompiler` e `SukoProjectCompiler`. `JteRenderSupport.compileToJte`/`render` vão direto de `SukoAstBuilder` para `JteEmitter` e **não** são apanhados, tal como `ProjectIndex.buildFileAst` e o `RegistryGenerator` (C2). É por isso que as Tasks 7-10 têm verificação por `grep` própria e não delegam nesta.

- [ ] **Step 1: Write the failing test**

Acrescentar a `suko-core/src/test/java/io/suko/lang/SukoSyntaxDiagnosticsTest.java`:

```java
    @Test
    void legacyBraceInterpolationIsAnErrorWithTheExactFix() {
        String source = """
            component Label(String text) {
              <label>{text}</label>
            }
            """;
        SukoDiagnostic diag = diagnosticsOf(source).stream()
                .filter(d -> "LEGACY_BRACE_INTERPOLATION".equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sem LEGACY_BRACE_INTERPOLATION: " + diagnosticsOf(source)));
        assertEquals(SukoDiagnostic.Severity.ERROR, diag.severity());
        assertTrue(diag.message().contains("${text}"), diag.message());
    }

    @Test
    void legacyBraceAttributeIsAnErrorWithTheExactFix() {
        String source = """
            component Input(String id) {
              <input id={id} />
            }
            """;
        SukoDiagnostic diag = diagnosticsOf(source).stream()
                .filter(d -> "LEGACY_BRACE_ATTRIBUTE".equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sem LEGACY_BRACE_ATTRIBUTE: " + diagnosticsOf(source)));
        assertEquals(SukoDiagnostic.Severity.ERROR, diag.severity());
        assertTrue(diag.message().contains("id=${id}"), diag.message());
    }

    @Test
    void legacyBraceIsFoundInsideSwitchCase() {
        // C3 outra vez: Alert/Badge/Button têm o corpo inteiro num switch.
        String source = """
            component Badge(String variant, Component children) {
              switch (variant) {
                case "success" -> {
                  <span class="badge">{children}</span>
                }
                default -> {
                  <span class="badge">${children}</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
    }

    @Test
    void legacyBraceIsFoundInsideSlotFillBody() {
        String source = """
            component Box(Component header) {
              <div>${header}</div>
            }

            component Page(String t) {
              Box() {
                header {
                  <span>{t}</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
    }

    @Test
    void modernSyntaxProducesNoLegacyDiagnostic() {
        String source = """
            component Input(String id, boolean required) {
              <input id=${id} required=${required} class="field-${id}" title="ola $id" />
            }
            """;
        assertEquals(0, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
        assertEquals(0, countOf(source, "LEGACY_BRACE_ATTRIBUTE"));
    }

    @Test
    void bareBracesInsideStringAreNotFlaggedAsLegacy() {
        // C5: chavetas dentro de string nunca foram interpolação e continuam
        // texto literal — Alpine. O diagnóstico novo não pode tocar nelas.
        String source = """
            component Dlg() {
              <div x-data="{ open: false }">x</div>
            }
            """;
        assertEquals(0, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
        assertEquals(0, countOf(source, "LEGACY_BRACE_ATTRIBUTE"));
    }
```

Acrescentar ao mesmo ficheiro o import `import io.suko.lang.diagnostic.SukoDiagnostic;` (já lá está desde a Task 4) e `import static org.junit.jupiter.api.Assertions.assertEquals;` (idem).

- [ ] **Step 2: Run test to verify it fails**

Run: `gradle :suko-core:test --tests "io.suko.lang.SukoSyntaxDiagnosticsTest" --console=plain`

Expected: falham os quatro primeiros (nenhum diagnóstico com esses códigos existe ainda); passam `modernSyntaxProducesNoLegacyDiagnostic` e `bareBracesInsideStringAreNotFlaggedAsLegacy`.

- [ ] **Step 3: Write minimal implementation**

Em `SemanticChecker.java`, dentro de `checkSyntaxInStatement`, acrescentar as duas verificações:

```java
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    checkLegacyBraceAttribute(attribute);
                    checkBareBraceInExpr(attribute.value(), paramNames, attribute.span());
                }
                checkSyntaxInStatements(element.children(), paramNames);
            }
            case Statement.Interpolation interpolation -> {
                checkLegacyBraceInterpolation(interpolation);
                checkBareBraceInExpr(interpolation.expr(), paramNames, interpolation.span());
            }
```

e os dois métodos, a seguir a `checkSyntaxInStatement`:

```java
    /** Subprojeto 9 (D1/D5): a chaveta nua deixou de interpolar em todas as
     * posições. A gramática continua a aceitá-la de propósito — removê-la
     * faria o ANTLR recuperar em silêncio nos caminhos de parse sem error
     * listener (ProjectIndex, RegistryGenerator), e no segundo isso é um
     * manifesto gerado a partir de uma árvore truncada. Quem fecha a porta é
     * este erro, com a correção literal na mensagem. */
    private void checkLegacyBraceInterpolation(Statement.Interpolation interpolation) {
        if (!interpolation.legacyBraceForm()) {
            return;
        }
        String shown = shownExprText(interpolation.expr());
        diagnostics.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                "'{" + shown + "}' já não interpola — escreva '${" + shown + "}'",
                "LEGACY_BRACE_INTERPOLATION",
                sourceFile,
                interpolation.span()
        ));
    }

    private void checkLegacyBraceAttribute(Statement.Attribute attribute) {
        if (!attribute.legacyBraceForm()) {
            return;
        }
        String shown = shownExprText(attribute.value());
        diagnostics.add(new SukoDiagnostic(
                SukoDiagnostic.Severity.ERROR,
                "'" + attribute.name() + "={" + shown + "}' já não interpola — escreva '"
                        + attribute.name() + "=${" + shown + "}'",
                "LEGACY_BRACE_ATTRIBUTE",
                sourceFile,
                attribute.span()
        ));
    }

    /** Texto da expressão para a mensagem. Um identificador simples — que é
     * a forma de praticamente toda a interpolação real (`{children}`,
     * `{label}`, `{title}`) — sai literal, o que dá ao autor a linha exata
     * para escrever. Para uma expressão composta não há representação textual
     * no AST (só `Expr` tipado), e reconstruí-la aqui seria um segundo
     * pretty-printer a divergir do JteEmitter: usa-se um marcador genérico,
     * e o `SourceSpan` do diagnóstico aponta para a posição exata. */
    private String shownExprText(Expr expr) {
        return expr instanceof Expr.PrimaryExpr primary ? primary.text() : "expr";
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradle :suko-core:test --console=plain`
Expected: PASS nos 6 testes de diagnóstico e em **toda** a suite. Qualquer falha aqui é um `.sk` que escapou às Tasks 6-10 — corrigir o `.sk`, não o diagnóstico.

Run: `gradle build --console=plain`
Expected: PASS em todos os módulos.

- [ ] **Step 5: Atualizar a nota do teste-ponte da Task 2**

Em `SukoInterpolationSyntaxTest.java`, o teste `legacyBraceStillParsesForNow` continua correto (a gramática **continua** a aceitar), mas o nome e o comentário passam a dizer porquê:

```java
    /** A forma legada continua a fazer PARSE de propósito (C2 do plano): a
     * rejeição é semântica, não sintática — ver LEGACY_BRACE_INTERPOLATION
     * em SukoSyntaxDiagnosticsTest. Se um dia isto deixar de fazer parse,
     * os caminhos sem error listener voltam a recuperar em silêncio. */
    @Test
    void legacyBraceStillParsesButIsRejectedBySemantics() {
```

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(semantic): chaveta nua passa a erro com a correcao literal na mensagem (D1/D5)"
```

---

### Task 12: `ARCHITECTURE.md` — corrigir a afirmação errada sobre erros de parse

**Agent:** `architect`

**Files:**
- Modify: `ARCHITECTURE.md:379-383` (bullet "Erros de parse não param a compilação")

**Interfaces:**
- Consumes: nada.
- Produces: nada de código. Produz a premissa correta para quem escrever os subprojetos 10 e 11.

**Porquê é uma tarefa e não uma nota:** o bullet atual afirma que não existe `ErrorListener` em `src/main`. Existe — `suko-core/src/main/java/io/suko/lang/diagnostic/SukoErrorListener.java`, ligado em `JteCompiler.parseAndBuild`. A afirmação errada é exatamente a que levaria o próximo autor a concluir que um corte direto de sintaxe é sempre suicida (ou sempre seguro): a verdade é mais específica e é o que fundamenta D5 deste subprojeto.

- [ ] **Step 1: Confirmar o facto antes de o escrever**

```bash
ls suko-core/src/main/java/io/suko/lang/diagnostic/SukoErrorListener.java
grep -n 'SukoErrorListener' suko-core/src/main/java/io/suko/lang/JteCompiler.java
grep -n 'removeErrorListeners' suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java suko-registry-generator/src/main/java/io/suko/registry/RegistryGenerator.java
```
Expected: o ficheiro existe; `JteCompiler` instala o listener; os outros dois removem-nos. Se a realidade divergir disto, **parar e reportar** — o texto novo estaria errado da mesma forma que o antigo.

- [ ] **Step 2: Substituir o bullet**

Localizar pelo texto âncora `**Erros de parse não param a compilação.**` (linha 379 à data deste plano) e substituir o bullet inteiro por:

```markdown
- **Erros de parse param a compilação no caminho principal, e só nesse.**
  `SukoErrorListener` (`io.suko.lang.diagnostic`) existe em `src/main` e é
  instalado por `JteCompiler.parseAndBuild`, que aborta em
  `diagnostics.hasErrors()` — um `.sk` sintaticamente inválido compilado
  por aí dá erro com ficheiro, linha e coluna, não `.jte` corrompido.
  **Há dois caminhos de parse que removem os error listeners de
  propósito** e não têm essa proteção: `ProjectIndex.buildFileAst` (Fase 1
  da indexação multi-ficheiro, que só quer a assinatura e delega o
  diagnóstico à Fase 2) e `RegistryGenerator` (geração do manifesto da
  biblioteca). Nesses dois, um ficheiro que deixe de fazer parse produz
  recuperação silenciosa do ANTLR e um AST parcial — no segundo, isso
  significa um manifesto gerado a partir de uma árvore truncada, que sai
  commitado em JSON com aspeto normal. É por isso que o subprojeto 9
  manteve a produção legada de chaveta nua na gramática e rejeitou a
  sintaxe antiga no `SemanticChecker`, em vez de a apagar do `.g4`.
```

- [ ] **Step 3: Confirmar que nenhuma outra afirmação do documento depende da versão errada**

```bash
grep -n 'ErrorListener\|erro de parse\|Erros de parse\|stderr' ARCHITECTURE.md
```
Expected: só o bullet novo. Se aparecer outra referência à ausência de listener, corrigi-la com a mesma redação.

- [ ] **Step 4: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "docs: corrige a afirmacao sobre erros de parse (ha ErrorListener; falta em 2 caminhos)"
```

---

### Task 13: Renumeração do roadmap — 9 = interpolação, 10 = site, 11 = IDE

**Agent:** `architect`

**Files:**
- Modify: `ARCHITECTURE.md:567-576` (itens 9 e 10 do roadmap)
- Modify: `ARCHITECTURE.md:111` e `:119` (descrição de `suko-website/`)
- Modify: `suko-website/README.md:8`
- Modify: `suko-website/src/main/suko/README.md:9`
- Modify: `README.md:221` (tabela "Subprojects")

**Interfaces:**
- Consumes: nada.
- Produces: nada de código.

**Estado desta decisão:** a renumeração foi **confirmada pelo utilizador** depois de a spec ter sido escrita (a spec, no topo, ainda a marca como "assumida, não confirmada" — a Task 14 atualiza essa nota).

- [ ] **Step 1: Roadmap de `ARCHITECTURE.md`**

Localizar pelo texto âncora `9. **Site de documentação**`. Inserir **antes** dele o item novo, e renumerar os dois seguintes:

```markdown
9. **Unificação da sintaxe de interpolação** — CONCLUÍDO. Spec em
   `docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md`,
   plano em `docs/superpowers/plans/2026-09-21-suko-interpolacao-unificada.md`.
   `${expr}` passa a ser a única forma de interpolar, nas três posições
   (statement/corpo de tag, valor de atributo sem aspas, literal de
   string); a chaveta nua deixa de interpolar em qualquer posição e passa
   a erro com a correção literal na mensagem. `$ident` dentro de strings
   mantém-se (D3 rejeitada). Antecipado à frente do site de documentação
   por pedido explícito do utilizador.
10. **Site de documentação** — preenche `suko-website/`. Inclui
    compilação para HTML estático em build-time (deployável em
    serverless/CDN sem JVM em runtime) como primeiro caso de uso real
    dessa capacidade, antes de generalizá-la no compilador.
11. **Suporte de IDE** (VSCode + IntelliJ) — language server sobre o
    `DiagnosticCollector`/`SemanticChecker` já existentes. Sequenciado
    depois do 7/8 (quer uma superfície de AST/diagnostics estável), mas
    sem dependência bloqueante neles. IntelliJ não fala LSP nativamente
    (LSP4IJ vs. plugin PSI-based próprio) — decisão a tomar no scoping
    deste item.
```

- [ ] **Step 2: Descrição de `suko-website/` em `ARCHITECTURE.md`**

Localizar pelo texto âncora `scaffold vazio para o site de documentação` (linha 110-111): `(subprojeto 9)` passa a `(subprojeto 10)`. E, no fim do mesmo bullet (linha 119), `Decisão de scoping do subprojeto 9, não deste.` passa a `Decisão de scoping do subprojeto 10, não deste.`

- [ ] **Step 3: Os dois READMEs de `suko-website`**

`suko-website/README.md:8`: `Real content is subprojeto 9 of the roadmap` → `Real content is subprojeto 10 of the roadmap`.

`suko-website/src/main/suko/README.md:9`: `real content is subprojeto 9 of the roadmap` → `real content is subprojeto 10 of the roadmap`.

- [ ] **Step 4: Tabela de subprojetos do `README.md` da raiz**

Localizar a linha `| 9. Documentation site | Planned |`. Inserir antes dela e renumerar:

```markdown
| 9. Interpolation unification | ✅ Done | `${expr}` is the only interpolation syntax, in all three positions; bare braces never interpolate |
| 10. Documentation site | Planned | Built in Suko itself, depends on 7 and 8, populates `suko-website/` |
```

- [ ] **Step 5: Verificar que não sobrou nenhuma referência ao número antigo**

```bash
grep -rn 'subprojeto 9\|subproject 9\|Subproject 9' --include='*.md' . | grep -v '/build/' | grep -v 'docs/superpowers/'
```
Expected: só ocorrências que se refiram a **este** subprojeto (a unificação). Nenhuma a apontar para o site.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "docs: renumera o roadmap (9 = interpolacao unificada, 10 = site, 11 = IDE)"
```

---

### Task 14: Documentar a linguagem nova — `ARCHITECTURE.md` e os READMEs

**Agent:** `dx-specialist`

**Files:**
- Modify: `ARCHITECTURE.md` — secção "Decisões de design que moldam o pipeline" (bullet novo), secção "Limitações conhecidas" (três entradas novas), linhas `174-179`, `260-269`, `341`, `370-373`, `393` (exemplos em prosa na grafia antiga), linha `524` (bullet do subprojeto 6)
- Modify: `README.md:103,107,111,144,146,147` (snippets `.sk`)
- Modify: `suko-components/README.md:97` (menção à forma sem aspas)
- Modify: `docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md` (nota da renumeração, no topo)

**Interfaces:**
- Consumes: os achados registados no ledger pelas Tasks 0 e 5 (o comportamento exato de `\${` no `gg.jte`).
- Produces: nada de código.

- [ ] **Step 1: Bullet novo em "Decisões de design que moldam o pipeline"**

Acrescentar, a seguir ao bullet dos slots nomeados (que termina com a limitação do `{slot ?: "fallback"}`):

```markdown
- **Interpolação é sempre `${...}`, em todas as posições (subprojeto 9).**
  A regra da linguagem é independente da posição: `${expr}` interpola no
  corpo de um componente, dentro de uma tag, num valor de atributo sem
  aspas (`disabled=${disabled}`) e dentro de um literal de string
  (`id="dialog-${id}"`); uma **chaveta nua nunca interpola, em posição
  nenhuma**. Antes do subprojeto 9 a regra era o inverso conforme a
  posição — `{expr}` interpolava fora de strings e era texto literal
  dentro delas — e era essa inversão, mais do que o número de grafias, o
  que confundia quem chegava.
  **Porquê `${}` e não `{}`:** chaveta nua colide com usos reais que a
  linguagem já aceita e de que a biblioteca de componentes depende —
  `x-data="{ open: false }"` (Alpine, em `Dialog.sk`), `style="--tw-ring:
  {0}"`, `onclick="if(x){go()}"`. `${}` não colide com nenhum, é o que o
  `.jte` gerado já escrevia (o autor passa a ler a mesma grafia no fonte e
  no artefacto intermédio), e tem precedente em Kotlin, template literals
  de JS, Groovy e JSP EL.
  **A forma curta `$ident` dentro de strings mantém-se** (`"ola $name"`) —
  é um atalho do mesmo sigilo, não uma segunda regra posicional.
  **A gramática continua a aceitar a chaveta nua**, de propósito: quem a
  rejeita é o `SemanticChecker` (`LEGACY_BRACE_INTERPOLATION`,
  `LEGACY_BRACE_ATTRIBUTE`, ambos ERROR, com a correção literal na
  mensagem). Removê-la do `.g4` faria o ANTLR recuperar em silêncio nos
  dois caminhos de parse sem error listener — ver o bullet sobre erros de
  parse em "Limitações conhecidas".
```

- [ ] **Step 2: Três entradas novas em "Limitações conhecidas"**

```markdown
- **`$` seguido de identificador dentro de uma string é sempre
  interpolação Suko — colide com as *magic properties* do Alpine.js.**
  `x-data="$store.foo"`, `x-on:click="$dispatch('evento')"`, `$el`,
  `$refs`: o `.jte` gerado tenta resolver um símbolo Java `store`/
  `dispatch` e o `javac` falha com "cannot find symbol". É o espelho exato
  do problema que levou a rejeitar `{}` dentro de strings no subprojeto 6.
  Hoje não morde porque `htmlName` ainda não aceita `:` nem `@` num nome
  de atributo (limitação separada, abaixo) — **morde no momento em que
  essa limitação for levantada**, e o subprojeto que alargar `htmlName`
  para interop com Alpine tem de resolver as duas em conjunto, não pode
  assumir que `$` está livre. Mitigação disponível hoje: o escape `\$`
  (`x-data="\$store.foo"`). Manter `$ident` foi decisão explícita do
  utilizador no subprojeto 9 (D3 rejeitada), com este custo aceite.
- **Não há escape para um `${` literal fora de uma string.** Dentro de uma
  string escreve-se `\$`; em texto livre não há forma de escrever `${`
  literal — o que torna JS com template literals dentro de um `<script>`
  inline não escrevível num `.sk`. Já era verdade para `{` antes do
  subprojeto 9; passou a ser verdade para `${`.
- **O manifesto do registry não declara versão mínima de linguagem.**
  `RegistryIndex`/`ComponentManifest` têm `schemaVersion`,
  `registryVersion` e `version` por componente, mas nada que diga "este
  fonte exige um compilador Suko >= X". Um consumidor com compilador
  antigo que faça `suko add --ref main` recebe fonte que não parseia. O
  que o protege por omissão é a tag do registry derivar da versão da CLI
  (D5 do subprojeto 8); um `--ref` explícito contorna essa proteção.
```

Se a sonda (a) da Task 0 e o Step 4 da Task 5 registaram que `"literal \${x}"` não sobrevive ao `gg.jte`, acrescentar também essa limitação, com a mensagem exata observada.

- [ ] **Step 3: Atualizar os exemplos em prosa que ainda usam a grafia antiga**

- Linhas 174-179: `{header}` → `${header}`, `{header(item)}` → `${header(item)}`, `{row(item)}` → `${row(item)}`, `{slot ?: "fallback"}` → `${slot ?: "fallback"}`.
- Linhas 260-269: as mesmas três formas, no bullet "Ler um slot render-prop exige chamá-lo".
- Linha 373: `{c}` → `${c}`.
- Linha 393: `{x} {y}` → `${x} ${y}` (a emissão `${x}${y}` ao lado é a saída `.jte`, **não muda**).
- Linha 524: no bullet do subprojeto 6, `interpolação real \`${expr}\`/\`$ident\` em strings e atributos` passa a `interpolação real \`${expr}\`/\`$ident\` em strings e atributos (generalizada a todas as posições pelo subprojeto 9)`.

- [ ] **Step 4: Efeito colateral positivo, a registar onde a limitação vive**

As duas limitações de gulodice do `textRun` (linhas 334-350 e 365-378) descrevem um `{ ... }` que sobra e "vira uma `interpolation` comum" — em silêncio. Depois do subprojeto 9 esse leftover passa a ser `LEGACY_BRACE_INTERPOLATION`, ou seja **um erro visível em vez de HTML corrompido em silêncio**. Acrescentar uma frase a cada um dos dois bullets a dizer isto. A limitação em si (o `textRun` engolir) **não** foi corrigida e continua a valer.

- [ ] **Step 5: Snippets dos READMEs**

`README.md`: linhas 103, 107, 111 (`{title}`, `{item}`, `{emptyLabel}`) e 144, 146, 147 (`{title}`, `{sidebar}`, `{content}`) passam a `${...}`. Confirmar que nenhum outro snippet `.sk` do ficheiro ficou por migrar.

`suko-components/README.md:97`: `or unquoted (\`class={variant}\`)` passa a `or unquoted (\`class=${variant}\`)`.

`suko-cli/README.md`: confirmar por grep que não tem snippets `.sk` com interpolação; se tiver, migrar.

- [ ] **Step 6: Fechar a nota de renumeração da spec**

Em `docs/superpowers/specs/2026-09-21-suko-interpolacao-unificada.md`, a secção "Nota sobre a numeração — assumida, não confirmada" passa a:

```markdown
### Nota sobre a numeração — confirmada em 2026-09-21

Este trabalho é o **subprojeto 9**; o site de documentação passa a **10** e
o suporte de IDE a **11**. Confirmado pelo utilizador depois da escrita
desta spec; aplicado pela Task 13 do plano nos cinco sítios que o
referiam (`ARCHITECTURE.md` roadmap e descrição de `suko-website/`,
`suko-website/README.md`, `suko-website/src/main/suko/README.md`,
`README.md`).
```

- [ ] **Step 7: Verificar**

```bash
grep -rnE '`\{[a-zA-Z_][a-zA-Z0-9_.()? :?]*\}`|>\{[a-zA-Z_]|=\{[a-zA-Z_]' ARCHITECTURE.md README.md suko-components/README.md suko-cli/README.md
```
Expected: só ocorrências que descrevem **deliberadamente** a grafia antiga (o bullet novo de "Decisões de design", as limitações do `textRun`, e o texto dos diagnósticos). Rever uma a uma.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "docs: documenta a regra unica de interpolacao e as limitacoes novas (D1/D3/D6/C8)"
```

---

### Task 15: Verificação final ponta-a-ponta

**Agent:** `architect`

**Files:**
- Nenhum ficheiro é alterado, exceto se a verificação encontrar algo — e nesse caso a correção pertence à tarefa de origem, não a esta.

**Interfaces:**
- Consumes: tudo.
- Produces: o relatório que precede a revisão final do branch.

- [ ] **Step 1: Build limpo**

Run: `gradle clean build --console=plain`
Expected: PASS em todos os 8 módulos.

- [ ] **Step 2: C4 — nenhum golden mudou em todo o branch**

```bash
/usr/bin/git diff --stat main -- suko-core/src/test/resources/golden/
```
Expected: saída vazia. **Qualquer** alteração aqui é regressão de emissão e invalida a tese do subprojeto (o sigilo mudou, a emissão não).

- [ ] **Step 3: Nenhuma grafia antiga sobreviveu**

```bash
grep -rnE '(^|[^$])\{[a-zA-Z_][a-zA-Z0-9_.()?: ]*\}|=\{[a-zA-Z_]' --include='*.sk' . | grep -v '/build/'
grep -rnE '>\{[a-zA-Z_]|=\{[a-zA-Z_]' --include='*.java' . | grep -v '/build/'
```
Expected: o primeiro vazio; o segundo só com os casos deliberados listados nas Tasks 9 e 10 (`SemanticCheckerTest`, `FinalReviewFixesTest`, e literais que sejam `.jte` esperado).

- [ ] **Step 4: Os não-objetivos aguentaram**

```bash
/usr/bin/git diff main -- suko-core/src/main/antlr/
```
Revisão manual do diff da gramática. Tem de conter **apenas**: o token `${` em `DEFAULT_MODE`, o re-tipo em `STRING_MODE`, a regra `interpolation` com as duas formas, a exclusão no `textRun`, e as duas regras que passam a referenciar `interpolation` (`attribute`, `stringPart`). Se contiver `htmlName` alargado, `textRun` a aceitar chavetas literais, ou `SIMPLE_INTERP_START` removido, um não-objetivo foi implementado.

```bash
grep -n 'SIMPLE_INTERP_START' suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4 suko-core/src/main/antlr/io/suko/lang/SukoParser.g4
grep -rn 'SimpleInterp' suko-core/src/main/java/
```
Expected: presentes em ambos — D3 foi rejeitada e `$ident` tem de continuar vivo.

- [ ] **Step 5: O emitter não mudou**

```bash
/usr/bin/git diff main -- suko-core/src/main/java/io/suko/lang/JteEmitter.java
```
Expected: **só comentários** (Task 9, Step 2). Qualquer alteração de código no emitter contradiz C4 e a decisão central da spec.

- [ ] **Step 6: O manifesto está coerente com os fontes**

Run: `gradle :suko-components:generateRegistry --console=plain`
```bash
/usr/bin/git status --short suko-components/
```
Expected: saída vazia — o manifesto commitado é byte a byte o que o gerador produz.

- [ ] **Step 7: Relatório**

Registar no ledger: os resultados dos Steps 1-6, as respostas das quatro sondas da Task 0, e qualquer teste que tenha ficado `@Disabled` (com a razão e a mensagem observada). Sem commit se nada mudou.

---

## Após todas as tarefas

Correr a suite completa uma última vez (`gradle clean build --console=plain`) e seguir para `superpowers:finishing-a-development-branch` (revisão final de todo o branch antes de merge/PR), como nos subprojetos anteriores. A revisão final deve verificar especificamente seis coisas, porque são as que uma revisão por-tarefa não vê:

1. **Nenhum golden `.jte` mudou** e o `JteEmitter.java` só tem alterações de comentário. É a prova de que este subprojeto mudou o sigilo e não a emissão. Se um golden mudou, alguém "aproveitou" para mexer na saída.
2. **A produção legada continua na gramática.** Procurar `legacy=LBRACE` em `SukoParser.g4`. Se desapareceu, alguém a achou morta e apagou-a — e reintroduziu a recuperação silenciosa nos caminhos sem error listener (C2), com um manifesto de registry gerado a partir de árvore truncada como pior caso.
3. **`SIMPLE_INTERP_START`/`Expr.StringPart.SimpleInterp` continuam vivos.** D3 foi rejeitada pelo utilizador. Se foram removidos, uma decisão do utilizador foi desfeita por conveniência de implementação.
4. **Nenhum não-objetivo foi implementado.** Em particular: `htmlName` sem `:`/`@`, `textRun` sem chavetas literais, e nenhuma mudança em auto-`toString`/ancoragem de concatenação.
5. **Os diagnósticos novos alcançam `if`/`for`/`switch` e corpos de slot fill.** É a correção de C3, e é o que faz o portão funcionar nos três componentes que mais importam (`Alert`, `Badge`, `Button`, todos com o corpo inteiro num `switch`). Um refactor que reintroduza um `default -> {}` em `checkSyntaxInStatement` desfaz isto sem partir nenhum teste existente.
6. **A documentação diz uma regra só.** `ARCHITECTURE.md` e os READMEs não podem continuar a ensinar duas grafias em sítios diferentes — era esse o problema que o subprojeto existe para resolver.

---

## Auto-revisão deste plano

### 1. Cobertura da spec

| Secção / requisito da spec | Tarefa que o implementa |
|---|---|
| D1 — `${expr}` em todas as posições | Tasks 1, 2 (gramática); Tasks 6-10 (migração); Task 11 (fecho) |
| D1 — forma concreta do lexer (`type()` entre modos, sem `pushMode` em `DEFAULT_MODE`) | Task 0 Steps 3-4 (sonda), Task 1 Step 3 |
| D2 — `STRING_MODE` mantém-se; uma regra `interpolation` partilhada | Task 2 Step 3 (`stringPart` referencia `interpolation`) |
| D3 — REJEITADA, `$ident` mantém-se | Não-objetivos; Task 1 Step 1 (teste de regressão), Task 2 (`simpleDollarIdentInStringIsUnchanged`), Task 5 (`unescapedDollarIdentStillInterpolates`), Task 15 Step 4 |
| D3 — limitação Alpine a documentar | Task 14 Step 2 |
| D4 — `attr=${expr}`; `attr={expr}` removida | Task 2 Step 3 (`attribute`), Task 3 (marcador), Task 11 (`LEGACY_BRACE_ATTRIBUTE`) |
| D5 — aceitar e rejeitar com mensagem; produção legada fica na gramática | Task 2 Step 3, Task 3, Task 11 |
| D5 — diagnósticos no percurso completo (C3) | Task 4 |
| D6 — escape `\$` | Task 0 Step 1 (sonda), Task 5 |
| D6 — limitação "sem escape para `${` fora de string" | Task 14 Step 2 |
| D7 — migração dos 8 componentes | Task 6 Steps 1-2 |
| D7 — manifesto regenerado, bump 0.1.0 → 0.2.0 | Task 6 Steps 3-4 |
| D7 — restantes `.sk` do repo | Task 7 |
| D7 — `.sk` inline em Java | Tasks 8, 9, 10 |
| D7 — `sha256` hard-coded de `UpdateCommandTest:249` | Task 10 Step 3 |
| D7 — critério "golden não muda" | Task 7 Step 4, Task 8 Step 1, Task 9 Step 4, Task 15 Step 2 |
| D7 — lacuna `minSukoVersion` (C8) registada | Task 14 Step 2 |
| D8 — risco `suko-registry`/`suko-cli` | Tasks 6 Step 5 e 10 (verificação); a análise da spec não gera trabalho próprio |
| D9 — não-objetivos | Secção "Não-objetivos" + Task 15 Step 4 |
| Achado 1 — `ARCHITECTURE.md:379-383` errado | Task 12 |
| Achado 2 — bullet do subprojeto 6 desatualizado | Task 14 Step 3 |
| Achado 3 — renumeração do roadmap | Task 13 |
| Tabela "Superfície da linguagem: antes e depois" | Coberta transversalmente; cada linha tem teste em Task 2 ou Task 5 |
| Testes exigidos pela spec (sondas, render real, preservação de espaços, equilíbrio de modos, diagnósticos em `switch`/slot fill, não-regressão de `$ident` e do `x-data`, goldens, biblioteca, CLI) | Task 0; Task 1 Step 1; Task 2 Step 1; Task 4 Step 1; Task 5 Step 1; Task 11 Step 1; Tasks 6/10 |

**Lacunas identificadas e fechadas durante esta auto-revisão:**

- A spec diz que os diagnósticos seriam implementados "em `checkStatement`". A leitura do código mostrou que `checkStatement` é o percurso **semântico** (resolução de componentes/slots) e `checkBareBraceInStatement` é o percurso **sintático** — pôr diagnósticos de sintaxe no primeiro duplicaria travessias. O plano corrige: a Task 4 conserta e renomeia o percurso sintático (`checkSyntaxInStatement`) e a Task 11 acrescenta-lhe os diagnósticos. Desvio deliberado face à letra da spec, com a mesma intenção.
- A spec não dizia **onde** na ordem entrava a migração face ao diagnóstico ERROR. O plano fixa-a explicitamente (migração antes, portão depois) e explica porquê em "Descobertas" e na Task 11.
- A spec não notava que `JteRenderSupport` não passa pelo `SemanticChecker`, ou seja, que o portão da Task 11 **não** apanha testes de emitter por migrar. Está agora dito por extenso na Task 8 e na Task 11, e é a razão de haver verificação por `grep` própria nas Tasks 7-10.

### 2. Scan de placeholders

Procurado: "TBD", "etc.", "similar to Task", "add appropriate", "write tests for the above", "...".

- Nenhuma ocorrência de "TBD" nem de "similar to Task N" — cada tarefa repete o código de que precisa, incluindo os helpers (`parseErrors`, `ast`, `diagnosticsOf`) que outra tarefa já definiu, com indicação explícita do ficheiro onde já existem.
- Dois pontos onde o plano **não** fixa um valor literal, ambos deliberados e com ação definida em vez de placeholder: (a) o valor esperado do escape de aspas em `StringEscapeTest.escapedQuoteStillWorks` (`&#34;` vs `&quot;`) — é comportamento do `gg.jte`, e o plano manda ajustar ao observado e registar; (b) o destino do caso `"literal \${x}"` — o plano manda-o decidir pela sonda (a) da Task 0, que corre **antes**, e define as duas ações possíveis por extenso.
- O `sha256` novo do `Label.sk` (Task 10 Step 3) não é um placeholder: o plano dá o comando exato que o extrai do ficheiro gerado na Task 6.

### 3. Consistência de tipos e assinaturas entre tarefas

- `SukoLexer.EXPR_INTERP_START` — declarado na Task 1, consumido com o mesmo nome nas Tasks 1 (teste), 2 (gramática) e 15 (verificação). O nome do **rule** de `STRING_MODE` (`STRING_EXPR_INTERP_START`) é distinto do **tipo de token** (`EXPR_INTERP_START`) de propósito, e isso está dito nas duas tarefas.
- `SukoParser.InterpolationContext.legacy` — produzido na Task 2, consumido na Task 3 com a mesma grafia (`ctx.interpolation().legacy != null`, `attrCtx.interpolation().legacy != null`).
- `Statement.Interpolation(Expr, boolean, SourceSpan)` e `Statement.Attribute(String, Expr, boolean, SourceSpan)` — ordem dos componentes fixada na Task 3 (`span` sempre no fim, como no resto do ficheiro) e usada com essa ordem nos três sítios de construção. O acessor lido nas Tasks 11 e 15 é `legacyBraceForm()` em ambos.
- `checkSyntaxSurface` / `checkSyntaxInStatement` / `checkSyntaxInStatements` — nomes fixados na Task 4 ("Produces") e usados com exatamente essas grafias na Task 11 ("Consumes" e código).
- `checkBareBraceInExpr(Expr, java.util.Set<String>, SourceSpan)` e `checkSwallowedVarDecl(Statement.TextRun)` — pré-existentes, invocados na Task 4 com a assinatura que já têm; nenhuma tarefa lhes muda a forma.
- `javaEscapeOf(String)` — declarado e usado só na Task 5.
- Códigos de diagnóstico: `LEGACY_BRACE_INTERPOLATION` e `LEGACY_BRACE_ATTRIBUTE`, com esta grafia nas Tasks 11 (implementação e teste), 14 (documentação) e 15. `BARE_BRACE_IN_STRING` e `VAR_DECL_NOT_PARSED` mantêm nome e semântica. **Não existe `LEGACY_DOLLAR_IDENT`** — dito por extenso nos não-objetivos e na Task 11, porque o scoping original previa-o e D3 foi rejeitada.
- Helpers de teste: `parseErrors(String)` e `ast(String)` vivem em `SukoInterpolationSyntaxTest` (Task 2) e são reutilizados pela Task 3 no mesmo ficheiro; `diagnosticsOf(String)`/`countOf(String, String)` vivem em `SukoSyntaxDiagnosticsTest` (Task 4) e são reutilizados pela Task 11 no mesmo ficheiro. Nenhum helper é usado a partir de outro ficheiro, logo não há dependência de visibilidade entre classes de teste.
