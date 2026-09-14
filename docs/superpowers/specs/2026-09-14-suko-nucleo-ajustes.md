# Suko — Ajustes de núcleo pós-subprojeto 1 (pré-subprojeto 2)

Data: 2026-09-14

## Contexto

O subprojeto 1 (núcleo da linguagem) está mergeado em `main`
(`76f9306`). Antes de escrever o spec do subprojeto 2 (Verificador
Suko), o agente `architect` auditou o estado real do núcleo e encontrou
três pontos onde a superfície da linguagem que o verificador vai
cimentar nas suas mensagens de erro/tabela de símbolos ainda está
errada ou incompleta. O utilizador decidiu resolver os três agora, como
uma ronda de ajustes de núcleo, antes de começar o subprojeto 2. Ver
`ARCHITECTURE.md` (commit `eec3159`) para o estado documentado
completo e a lista de gaps conhecidos.

Este documento cobre **apenas estes três ajustes**. Não é o spec do
subprojeto 2 — esse vem a seguir, depois destes ajustes estarem
mergeados.

Fora do âmbito deste documento (fica para o plano do subprojeto 2,
como pré-requisito dele, não desta ronda): alias de `import` descartado
(`SukoAstBuilder.java:29-31`) — é um bloqueador da tabela de símbolos
do verificador, não da superfície da linguagem em si.

## Ajuste 1 — Slots simples voltam a ser `Content` nu (não `Function`)

**Estado atual (problema):** desde a tarefa 18 do subprojeto 1, *todo*
`SlotParam` é emitido como `java.util.function.Function<T, gg.jte.Content>`
(cardinalidade `MANY`: `List<Function<T, Content>>`), mesmo quando o
slot nunca é usado como render-prop. Isto contradiz o próprio spec do
subprojeto 1 (secção "Sintaxe de slots e children tipados", linhas
160-162): *"`SlotParam` cardinalidade `ONE` sem render-prop →
`gg.jte.Content`. `SlotParam` com render-prop →
`java.util.function.Function<T, gg.jte.Content>`"*. A tarefa 18 desviou
disto porque não tinha uma forma de decidir, na declaração do
componente, se um dado `SlotParam` viria a ser usado como render-prop
— e documentou o desvio explicitamente (`JteEmitter.java`, comentário
antes de `emitSlotFillContent`).

Consequências visíveis hoje ao autor de `.sk` (ver `ARCHITECTURE.md`,
"Ler um slot exige chamá-lo"): `{header}` não compila, é preciso
`{header(null)}`; testar "não preenchido" é
`header.apply(null) == null`, não `header == null`; iterar
`List<slot<T>>` obriga a escrever o tipo do item como
`Function<T, Content>`; `{slot ?: "fallback"}` não compila (ramos de
tipo incompatível, `Content` vs `String`).

**Decisão de desenho:** um `SlotParam` é "render-prop" **se e só se o
corpo do componente onde ele é declarado o invoca como função** —
ou seja, existe pelo menos um `Expr.CallExpr` cujo `callee` é um
`Expr.PrimaryExpr` com o mesmo nome do slot, em qualquer lugar do corpo
do componente (dentro de `if`/`for`/`switch` incluído). Se não houver
nenhuma invocação assim, o slot é "simples" e o Java gerado usa
`Content`/`List<Content>` nu.

Esta é uma propriedade do **corpo do componente declarante**, calculada
uma vez por componente, antes de emitir os `@param`. Não depende de
como cada chamador preenche o slot (isso já é uma escolha independente
do chamador — dar ou não um `Identifier ARROW` no `namedSlot` — e
continua a ser assim).

**Impacto nos dois lados da emissão (`JteEmitter.java`):**

1. **Declaração do parâmetro** (`jteParamDeclaration`, linha ~116-190):
   hoje o `case Param.SlotParam` sempre produz `Function<...>`. Passa a
   ramificar em três casos: `SlotParam` cardinalidade `ONE`
   render-prop → `Function<T, Content>` (como hoje); `SlotParam`
   cardinalidade `ONE` simples → `Content` nu, valor por omissão
   `null` (não mais o lambda `(T) it -> null`); `SlotParam`
   cardinalidade `MANY` idem, com `List<Content>`/
   `List<Function<T,Content>>` conforme o caso.
2. **Leitura dentro do corpo** (`emitExpr`, caso `CallExpr` com callee
   `PrimaryExpr` em `slotNames`, linha ~438): esse caso só se aplica
   quando o slot É render-prop (é justamente o que o distingue). Para
   um slot simples, o `.sk` não deve escrever `{header(...)}` — só
   `{header}`, que já cai no caso genérico `PrimaryExpr -> primary.text()`
   e funciona sem alteração nenhuma (emite `header`, do tipo `Content`).
3. **Preenchimento no ponto de chamada** (`emitSlotFillContent`, linha
   ~324-340): hoje envolve *sempre* o corpo do fill num lambda
   (`lambdaParam -> @\`...\``). Passa a consultar se o slot-alvo é
   render-prop (mesma heurística de `resolveSlotCardinality`, que já
   consulta `componentsByName` — ver comentário nessa função sobre o
   `null` "desconhecido"; reaproveitar o mesmo padrão para uma nova
   `resolveSlotIsRenderProp`). Se for render-prop, mantém o lambda como
   hoje. Se for simples, emite o corpo do fill diretamente como
   `@\`...\`` **sem** o lambda (a forma que existia antes da tarefa 18,
   documentada no histórico como "caso slot simples da tarefa 16").

**Fora de âmbito, mantém-se como limitação documentada:**
`{slot ?: "fallback"}` continua sem suporte mesmo para slot simples —
`Content` e `String` continuam sem supertipo comum aceite pelo
`?:` dessaçucarado. Não tentar resolver aqui; é candidato a
verificação do subprojeto 2 (detectar e rejeitar, não fazer funcionar).

**Verificação empírica obrigatória** (convenção do projeto, ver lições
em `ARCHITECTURE.md`/plano do subprojeto 1): antes de assumir que
`${header}` de tipo `Content` nu renderiza corretamente via `gg.jte`
3.1.12 real (não só compila), correr um probe direto contra o
`TemplateEngine` — não confiar em memória sobre o comportamento do
JTE. Consultar `jte-specialist` se o resultado for inesperado.

**Testes afetados (a reescrever, não deletar — mudam de forma, não de
intenção):** `JteEmitterTest.rendersRequiredSingleSlot` (linha 208),
`JteEmitterTest.rendersOptionalAndMultipleSlots` (linha 233) — ambos
hoje testam o comportamento `Function`-sempre e têm comentários "DESVIO
DO BRIEF" explicando exatamente essa forma; ao reverter o desenho, os
comentários deixam de ser verdade e o corpo `.sk` de teste volta a
poder usar `{header}`/`{title}` bare e `title == null`.
`JteEmitterTest.rendersRenderPropSlot` (linha 364) **não muda de
comportamento** (já é render-prop de verdade — `row` é invocado como
`row(item)` dentro do corpo de `ItemList`), mas os seus comentários que
descrevem a forma antiga como "a única forma" devem ser corrigidos.

## Ajuste 2 — `var` deixa de rebentar o compilador

**Estado atual (problema):** `var x = 1;` faz parse
(`varDecl: VAR Identifier EQ expression SEMI`, `SukoParser.g4:118-120`)
e está listado no spec do subprojeto 1 (secção 3, lista de
`Statement`), mas `Statement` (`Statement.java`) não tem variante para
isto — `SukoAstBuilder` lança
`IllegalStateException("templateStatement ainda não suportado")`
para qualquer `varDecl`.

**Decisão do utilizador:** `var` fica na linguagem (não sai da
gramática).

**Desenho:**

1. **AST** (`Statement.java`): adicionar
   `record VarDecl(String name, Expr value, SourceSpan span) implements Statement`
   à lista `permits` do `sealed interface Statement`.
2. **`SukoAstBuilder`**: no visitor que despacha `templateStatement`
   (onde hoje está o `throw new IllegalStateException(...)` para
   `varDecl`), construir `new Statement.VarDecl(ctx.varDecl().Identifier().getText(), buildExpr(ctx.varDecl().expression()), spanOf(ctx.varDecl()))`.
3. **`JteEmitter`**: adicionar um `case Statement.VarDecl varDecl -> ...`
   ao `switch` de `emitStatement` (linha ~260). **A forma exata do
   Java/JTE gerado tem de ser confirmada por probe direto contra o
   `gg.jte` real antes de codificar** — candidatos a testar: um bloco
   de código não-processado do JTE (`!{var x = expr;}`) ou uma
   declaração dentro de um `@code` block, dependendo do que a versão
   3.1.12 pinada suportar. Consultar `jte-specialist` se necessário.
   Escrever o teste (`.sk` com `var` seguido de uso da variável,
   render real) ANTES de escolher a forma, exatamente como as tarefas
   do subprojeto 1 fizeram para `?.`/`?:` e para os slots.
4. Symbol/scope: esta ronda **não** precisa de resolver nomes (isso é
   subprojeto 2) — só emitir Java sintaticamente válido. Uma variável
   `var` só tem de estar em escopo Java para o resto do bloco onde foi
   declarada, o que sai de graça se o Java gerado for uma declaração
   Java real na posição certa do output (`.jte`/Java gera blocos
   sequenciais, não uma função só).

**Testes a criar:** um novo teste em `JteEmitterTest` (ex.:
`rendersVarDecl`), com um componente que declara `var` e usa o valor
num `if`/interpolação a seguir, renderizado de verdade via
`JteRenderSupport`.

## Ajuste 3 — `examples/Card.sk` deixa de ser genérico

**Estado atual (problema):** `examples/Card.sk` usa
`component Card<T>(String title, List<T> items, ...)`. Com o
verificador do subprojeto 2 a rejeitar generics de componente (decisão
de roadmap já tomada, ver `ARCHITECTURE.md`), o exemplo-bandeira do
projeto passa a ser um programa inválido no dia em que o subprojeto 2
começar a correr sobre ele.

**Decisão do utilizador:** tornar `Card.sk` concreto (não genérico);
mover o caso genérico para `examples/invalid/` como exemplo do que o
verificador rejeita.

**Desenho:**

1. Reescrever `component Card<T>(String title, List<T> items, String emptyLabel = "Sem itens")`
   em `examples/Card.sk` para um tipo concreto. Usar `String` como o
   tipo dos itens (o que já é exercitado noutro lado nos testes, sem
   introduzir um tipo qualificado novo que não faz parse — ver gap 1
   do núcleo) — `List<String> items`, e ajustar o corpo (`item.name`,
   `item.price?.format()`) para algo coerente com `String` (ex.: só
   `{item}` em vez de acesso a membros que `String` não tem). Manter o
   resto do ficheiro (`NavLink`, `Page`) inalterado — não são
   genéricos.
2. Criar `examples/invalid/Card.sk` com o `component Card<T>(...)`
   genérico original **exatamente como estava antes desta mudança**
   (copiar o texto, não reescrever), como referência do que o
   subprojeto 2 vai rejeitar. Adicionar um comentário no topo do
   ficheiro a dizer isso.
3. **`src/test/resources/golden/Card.jte`** — confirmado:
   `JteEmitterGoldenFileTest.cardMatchesGoldenFile` lê
   `examples/Card.sk` diretamente do disco
   (`Files.readString(Path.of("examples/Card.sk"))`), portanto muda de
   comportamento com este ajuste. Regenerar o golden a partir do novo
   `Card.sk` concreto: correr `JteRenderSupport.compileToJte` sobre o
   `.sk` atualizado (ex.: via um teste temporário ou REPL) e substituir
   `src/test/resources/golden/Card.jte` pelo texto exato emitido (sem
   `T` literal desta vez). Atualizar também o javadoc da classe
   `JteEmitterGoldenFileTest` (linhas 17-21), que hoje descreve
   explicitamente o golden como cobrindo "o componente genérico Card<T>
   real (nunca compilado/renderizado via gg.jte)" — deixa de ser
   verdade. `navLinkMatchesGoldenFile` lê do mesmo ficheiro mas testa o
   componente `NavLink` (não genérico) — confirmar que o seu golden
   não muda (é esperado que não mude, já que `NavLink` não é tocado por
   este ajuste), mas correr o teste para confirmar.
4. Atualizar `ARCHITECTURE.md` onde cita `examples/Card.sk` como
   "referência da superfície da linguagem" que "é hoje um programa que
   o verificador do subprojeto 2 terá de rejeitar" (linha adicionada
   pelo `architect`) — deixa de ser verdade depois desta mudança;
   ajustar essa frase.

## Fora de âmbito (confirmado, não repetir aqui)

- Alias de `import` (gap 2/10) — fica para o plano do subprojeto 2.
- Nomes de tipo qualificados (gap 1) — idem.
- Whitespace órfão, backtick por escapar, nome composto de componente,
  children anónimos descartados, error listener — todos já
  classificados no `ARCHITECTURE.md`, nenhum pertence a esta ronda.

## Global Constraints (repetidas do plano do subprojeto 1, continuam a valer)

- Java 21 como piso.
- `org.antlr:antlr4:4.13.1` e `gg.jte:jte:3.1.12` pinadas — não mudar
  de versão para resolver nada aqui.
- 1 componente → 1 `.jte`.
- Slots são uma variante de `Param`, nunca uma declaração à parte.
- Regenerar gramáticas sem `warning` na saída
  (`gradle generateSukoLexer generateSukoParser --console=plain`).
- TDD: escrever o teste (com render real via `gg.jte`, não só
  compilação) antes da implementação; documentar qualquer desvio
  descoberto por probe, com o texto exato do erro, no ponto da
  descoberta — convenção estabelecida em todas as tarefas do
  subprojeto 1.
