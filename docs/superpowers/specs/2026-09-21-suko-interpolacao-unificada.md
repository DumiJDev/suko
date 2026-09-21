# Suko — Subprojeto 9: Unificação da Sintaxe de Interpolação

Data: 2026-09-21

## Contexto

Os subprojetos 1-8 estão concluídos e mergeados em `main` (o 8 fechou em
`5997f30`, PR #4). O roadmap de `ARCHITECTURE.md` marcava como próximos o
site de documentação e o suporte de IDE; o utilizador pediu
explicitamente, a 2026-09-21, para **antecipar** este trabalho à frente de
ambos, por a linguagem ter hoje duas grafias diferentes para interpolar
uma expressão e isso ser confuso para quem chega agora.

O follow-up tinha sido registado a 2026-09-21 e adiado até o subprojeto 8
mergear. Este documento resulta do scoping arquitetural dessa data e das
decisões do utilizador sobre ele (D1-D9 abaixo; **D3 foi rejeitada**, e
está registada como tal, com o status quo mantido).

### Nota sobre a numeração — assumida, não confirmada

Esta spec assume que este trabalho passa a ser o **subprojeto 9**, e que o
site de documentação e o suporte de IDE deslizam para **10** e **11**. O
utilizador confirmou a prioridade (fazer isto primeiro) mas **não**
confirmou explicitamente a renumeração.

Se a renumeração for aceite, quatro sítios têm de ser atualizados no mesmo
commit, senão a próxima pessoa a abrir `suko-website/` lê que está a
trabalhar no subprojeto 9:

- `ARCHITECTURE.md:567-576` (roadmap: itens 9 e 10);
- `ARCHITECTURE.md:111` e `ARCHITECTURE.md:119` (descrição de
  `suko-website/`, que remete o scoping "para o subprojeto 9");
- `suko-website/README.md:8`;
- `suko-website/src/main/suko/README.md:9`.

**Ação requerida antes de o plano ser executado:** confirmar a
renumeração, ou dizer que este trabalho entra com outro número (por
exemplo, "subprojeto 8.5" ou "9-bis") e o site mantém o 9.

### Correção de âmbito: são três superfícies, não duas

O pedido original fala em "duas sintaxes de interpolação". O scoping
encontrou **três** superfícies com chaveta ou `$`, e uma unificação que
trate só duas deixa a terceira como grafia órfã e não resolve a queixa:

1. **Statement / corpo de componente** — `{expr}`
   (`interpolation : LBRACE expression RBRACE`, `SukoParser.g4:196-198`).
   É o `{children}` de `Badge.sk`/`Alert.sk`/`Card.sk`/`Dialog.sk`.
2. **Valor de atributo sem aspas** — `attr={expr}`
   (`attribute`, `SukoParser.g4:189-193`). É o `disabled={disabled}` de
   `Button.sk` e os cinco atributos de `Input.sk`.
3. **Dentro de literal de string** — `${expr}` e `$ident`
   (`EXPR_INTERP_START`/`SIMPLE_INTERP_START`,
   `SukoLexer.g4:171-178`). É o `id="dialog-${id}"` de `Dialog.sk`.

Esta spec cobre as três.

## Constrangimentos que moldam este desenho

Verificados no código real, não inferidos das specs anteriores.

- **C1 — `textRun` é um closure guloso sem lookahead intermédio.**
  `textRun : ( ~(LBRACE | RBRACE | LT | LTSLASH) )+` (`SukoParser.g4:115-117`).
  É a causa-raiz já documentada de dois bugs conhecidos em
  `ARCHITECTURE.md` (o slot nomeado engolido quando vem depois de
  conteúdo solto, linhas 334-350; o `var c = Card() { ... }` engolido como
  texto, linhas 365-378). Consequência direta para este subprojeto: se o
  token novo de `${` **não** for excluído do conjunto do `textRun`,
  `<p>custa ${price} euros</p>` faz o `textRun` engolir `custa ${price`
  como texto e o `}` sobra — silenciosamente. A exclusão não é uma
  otimização, é uma condição de correção.

- **C2 — Existem três caminhos de parse no monorepo, e dois removem os
  error listeners de propósito.**
  `JteCompiler.parseAndBuild` (`JteCompiler.java:57-67`) instala
  `SukoErrorListener` e aborta em `diagnostics.hasErrors()` (linhas 66, 93
  e 112). Mas `ProjectIndex.buildFileAst`
  (`suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java:135-138`)
  e `RegistryGenerator`
  (`suko-registry-generator/src/main/java/io/suko/registry/RegistryGenerator.java:233-243`,
  com o comentário explícito "no `SukoErrorListener`") fazem
  `parser.removeErrorListeners()`. Nesses dois caminhos, um `.sk` que
  deixe de fazer parse **não dá erro**: dá recuperação silenciosa do ANTLR
  e um AST parcial. No caso do `RegistryGenerator`, isso significa um
  **manifesto gerado a partir de uma árvore truncada**. É o constrangimento
  central de D5.

- **C3 — O `SemanticChecker` tem dois percursos de statements, e o que
  diagnostica sintaxe é o incompleto.**
  `checkStatement` (`SemanticChecker.java:256-286`) desce a `if`/`for`/
  `switch`/atributos/filhos de tag. `checkBareBraceInStatement`
  (`SemanticChecker.java:175-190`), que é onde vive o diagnóstico
  `BARE_BRACE_IN_STRING`, trata `HtmlElement`/`Interpolation`/`TextRun` e
  tem `default -> {}` — **não desce a `if`/`for`/`switch`**. E
  **nenhum dos dois** desce aos corpos de slot fill
  (`checkComponentCall`, `SemanticChecker.java:394-397`, itera
  `call.slotFills()` só para contar cardinalidade, nunca visita
  `fill.body()`).
  Consequência medida: `Alert.sk`, `Badge.sk` e `Button.sk` têm **todo** o
  seu corpo dentro de um `switch`, logo o diagnóstico de sintaxe atual
  nunca os vê. Um diagnóstico de migração construído em cima do percurso
  errado falharia exatamente nos ficheiros que mais precisam dele.

- **C4 — O `.jte` emitido já usa `${}`.** `JteEmitter.emitStatement`
  (`JteEmitter.java:328-336`) escreve `${expr}` e `emitHtmlElement`
  (`JteEmitter.java:597-612`) escreve `attr="${expr}"`. A unificação
  aproxima o fonte do artefacto gerado, e — mais importante como critério
  de aceitação — **nenhum golden file `.jte` de
  `suko-core/src/test/resources/golden/` pode mudar** neste subprojeto. Se
  mudar, é regressão, não migração.

- **C5 — Dentro de `STRING_MODE` uma chaveta nunca produz um token
  `LBRACE`.** `STRING_TEXT : ~["\\$]+` (`SukoLexer.g4:189-191`) cobre `{` e
  `}`. É por isso que `x-data="{ open: false }"` (`Dialog.sk:17`) funciona
  hoje, e é por isso que a alternativa legada de D5 é inalcançável dentro
  de uma string mesmo que a regra `interpolation` passe a ser partilhada
  com `stringPart`.

- **C6 — O `popMode` do `RBRACE` é condicional e frágil por desenho.**
  `RBRACE : '}' { if (!_modeStack.isEmpty()) popMode(); }`
  (`SukoLexer.g4:68-70`), com um comentário longo a explicar que um
  `-> popMode` incondicional rebentaria em todo o `}` que fecha um bloco.
  Qualquer token novo de `${` em `DEFAULT_MODE` tem de ser desenhado para
  **não** empurrar modo (não há mudança de modo a fazer: já estamos em
  `DEFAULT_MODE`), senão um `${` sem fecho deixa a pilha suja e o próximo
  `}` de um bloco faz um pop indevido.

- **C7 — O escape `\$` produz Java inválido.**
  `SukoAstBuilder.buildStringLiteral` (`SukoAstBuilder.java:361-378`)
  acumula o texto cru do token `STRING_ESCAPE` no literal run, e
  `JteEmitter.emitStringPart` (`JteEmitter.java:782`) embrulha-o em aspas
  Java: `"custa \$5"` chega ao `javac` como `"custa \$5"`, e `\$` não é uma
  sequência de escape Java válida ("illegal escape character"). Não existe
  um único teste de escapes em `suko-core/src/test` (grep por
  `STRING_ESCAPE`/`escape`: zero resultados). É um canto obscuro hoje;
  torna-se a única saída de emergência do autor quando `$` for o sigilo
  usado em todas as posições. **A confirmar por sonda** (convenção do
  projeto: nenhuma forma nova de Java gerado avança sem confirmação
  empírica prévia).

- **C8 — O manifesto não tem campo de versão mínima de linguagem.**
  `RegistryIndex`/`ComponentManifest`
  (`suko-registry/src/main/java/io/suko/registry/`) têm `schemaVersion`,
  `registryVersion` e `version` por componente — nenhum campo diz "este
  fonte exige um compilador Suko >= X". A mitigação existente é a tag por
  omissão do registry derivar da versão da CLI
  (`suko-cli/src/main/java/io/suko/cli/Version.java`, D5 do subprojeto 8):
  uma CLI antiga aponta por omissão para uma tag antiga. Um
  `suko add --ref main` contorna a mitigação.

## Decisão central

A linguagem passa a ter **uma regra invariante e independente da
posição**: *interpolação de uma expressão é sempre `${...}`; uma chaveta
nua nunca interpola, em lado nenhum*.

Hoje a regra é dependente da posição — chaveta nua interpola fora de
strings e é texto literal dentro de strings — e é essa inversão, mais do
que o número de grafias, que confunde quem chega. O ganho pedagógico é
esse; a redução de duas grafias para uma é a consequência, não o objetivo.

A forma curta `$ident` sobrevive dentro de strings (D3, rejeitada): é um
atalho do **mesmo** sigilo, não uma segunda regra posicional.

## D1 — `${expr}` vence em todas as posições (**decidido**)

`{expr}` deixa de interpolar a nível de statement e dentro de atributos.
`${expr}` passa a ser a forma válida nas três superfícies.

**Porque não `{expr}` em todo o lado.** Já foi decidido e fundamentado
empiricamente no subprojeto 6
(`docs/superpowers/specs/2026-09-18-suko-modelo-componente.md`, secção
"Decisão de sintaxe: `${expr}` / `$ident`, não `{expr}` bare"), com
colisões reais e não hipotéticas: `x-data="{ open: false }"` (Alpine — está
**hoje** em `Dialog.sk:17`), `style="--tw-ring: {0}"` (custom property
CSS), `onclick="if(x){go()}"` (JS inline). Reverter para "chaveta nua em
todo o lado" reabriria um caso fechado com evidência e partiria a própria
biblioteca de componentes. Não é para relitigar.

**Porque não uma terceira forma** (`{{expr}}`, `@expr`): obrigaria a migrar
100% das ocorrências (as duas formas atuais, não uma), divergiria do `${}`
que o `.jte` gerado já usa (C4), e `{{` reintroduz ambiguidade com um bloco
que começa por interpolação (`if (x) { {y} }`).

**Porque `${}` é positivamente a escolha certa, não a que sobra:**

- é exatamente o que o `JteEmitter` já escreve no `.jte` (C4). O autor
  passa a ler a mesma grafia no fonte e no artefacto intermédio legível —
  que é o argumento declarado em `ARCHITECTURE.md` para gerar JTE puro em
  vez de bytecode;
- precedente externo forte: Kotlin, template literals de JS, Groovy, shell,
  JSP EL;
- o diagnóstico `BARE_BRACE_IN_STRING` (`SemanticChecker.java:237-244`)
  continua correto e ganha um irmão simétrico (D5). Se tivéssemos escolhido
  `{}`, esse diagnóstico teria de ser invertido e a sua mensagem passaria a
  contradizer a spec do subprojeto 6.

## D2 — `STRING_MODE` mantém-se; a unificação é do sigilo, não do modo léxico (**decidido**)

A pergunta "os literais de string ainda precisam de gramática distinta
texto-vs-expressão depois da unificação?" tem resposta **sim**, e isso não
é uma falha da unificação.

A razão é que o *default* difere nos dois lados. Fora de uma string, o
default é **estrutura** (tags, `if`/`for`/`switch`, chamadas de componente)
e o texto é o último recurso (`textRun`). Dentro de uma string, o default é
**texto** e nada mais pode ser lexado como palavra-chave ou pontuação — sem
`STRING_MODE`, `"if (x)"` dentro de uma string voltaria a produzir tokens
`IF`/`LPAREN`.

O que a unificação faz é convergir as duas posições no **mesmo token e na
mesma produção de parser**:

- o lexer passa a emitir o **mesmo tipo de token** (`EXPR_INTERP_START`)
  para `${` em `DEFAULT_MODE` e em `STRING_MODE`;
- o parser passa a ter **uma** regra
  `interpolation : EXPR_INTERP_START expression RBRACE`, reutilizada em
  `templateStatement`, em `attribute` e em `stringPart`.

Resposta direta a "como distinguir texto literal de expressão interpolada
dentro de uma string, se a sintaxe é a mesma de fora": pelo mesmo mecanismo
de hoje — `$` é o sigilo, tudo o resto é `STRING_TEXT`. Não muda nada
dentro da string; o que muda é que fora dela a regra passa a ser a mesma em
vez de ser a oposta.

### Forma concreta no lexer

Recomendada (a validar na sonda da Fase 0):

- em `DEFAULT_MODE`, declarar `EXPR_INTERP_START : '${' ;` — **sem**
  comando de modo (C6);
- em `STRING_MODE`, a regra existente passa a re-tipar para esse token e
  mantém o `pushMode`:
  `STRING_EXPR_INTERP : '${' -> type(EXPR_INTERP_START), pushMode(DEFAULT_MODE);`

Isto mantém o `pushMode` exatamente onde é necessário (só a saída de
`STRING_MODE` precisa de voltar) e dá ao parser um único tipo de token, que
é o que permite uma única regra `interpolation`. `RBRACE`
(`SukoLexer.g4:68-70`) fica **inalterado**.

Maximal-munch do ANTLR garante que `${` ganha a `SIMPLE_DOLLAR`/`OTHER`
seguidos de `LBRACE`, sem ordem especial de regras.

## D3 — `$ident` mantém-se (**rejeitada** — recomendação do scoping recusada pelo utilizador)

O scoping recomendou **remover** a forma curta `$ident` dentro de strings,
para que ficasse literalmente uma só forma (`${expr}`) e para desbloquear
um bug latente de interoperabilidade com Alpine.js. O utilizador
**rejeitou** essa recomendação: `$ident` fica, exatamente como está hoje.

Registado aqui com o racional de ambos os lados, porque a decisão tem uma
consequência que sobrevive a este subprojeto.

**O que se mantém sem nenhuma alteração:**

- o token `SIMPLE_INTERP_START` (`SukoLexer.g4:175-178`) fica como está,
  exclusivo de `STRING_MODE`;
- a variante `Expr.StringPart.SimpleInterp` (`Expr.java:27-29`) fica;
- os caminhos do emitter ligados a ela ficam:
  `firstPartIsContentTyped` (`JteEmitter.java:769-775`),
  `emitStringPart` (`JteEmitter.java:780-800`),
  `singlePartBareIdentifier` (`JteEmitter.java:802-812`);
- **`textRun` não precisa de excluir `SIMPLE_INTERP_START`**, porque esse
  token nunca existiu em `DEFAULT_MODE` e não passa a existir. A única
  exclusão nova em `textRun` é `EXPR_INTERP_START` (C1).

**Limitação conhecida que esta decisão aceita e prolonga (a documentar em
`ARCHITECTURE.md`):** dentro de um literal de string, `$` seguido de
identificador é sempre lido como interpolação Suko. Isso colide com as
*magic properties* do Alpine.js — `x-data="$store.foo"`,
`x-on:click="$dispatch('evento')"`, `$el`, `$refs` — que o autor de `.sk`
não tem como escrever: o `.jte` gerado tentaria resolver um símbolo Java
`store`/`dispatch` e o `javac` falharia com "cannot find symbol". É o
espelho exato do problema que motivou rejeitar `{}` dentro de strings no
subprojeto 6. Hoje não morde porque `htmlName`
(`Identifier (MINUS Identifier)*`) ainda não aceita `:` nem `@` num nome de
atributo (limitação já documentada em `ARCHITECTURE.md:416-427`, e a razão
por que `Dialog.sk` não tem botão de fecho próprio). **Morde no momento em
que essa limitação for levantada** — o subprojeto que alargar `htmlName`
para interop Alpine tem de resolver isto em conjunto, e não pode assumir
que `$` está livre.

O escape de C7/D6 é a mitigação parcial disponível: com `\$` a funcionar,
`x-data="\$store.foo"` passa a ser escrevível.

## D4 — A forma de atributo sem aspas passa a `attr=${expr}` (**decidido**)

`attr={expr}` é removida junto com o `{expr}` de statement (é a superfície
2 da correção de âmbito). Ficam duas formas, com semânticas deliberadamente
distintas e uma regra ensinável — **aspas ⇒ string; sem aspas ⇒ o valor da
expressão**:

| Forma | Significado | Exemplo |
|---|---|---|
| `attr=${expr}` | o valor da expressão, tal como é | `disabled=${disabled}` |
| `attr="...${expr}..."` | string com interpolação (concatenação) | `id="dialog-${id}"` |

A forma sem aspas é mantida (não forçamos aspas em todo o lado) porque é a
que permite passar um `boolean`/`Object` cru sem o transformar em string —
é o que `Input.sk` e `Button.sk` usam hoje — e porque forçar aspas mudaria
a emissão Java desses casos, o que exigiria revalidação contra o `gg.jte`
real e faria mudar golden files (violando C4).

A regra do parser passa a `attribute : htmlName EQ interpolation | htmlName
EQ stringLiteral | htmlName`, com a alternativa legada de D5 a cobrir
`attr={expr}`.

Nota de coerência: `LibraryConventionsTest`
(`suko-components/src/test/java/io/suko/components/LibraryConventionsTest.java`)
opera sobre o AST, não sobre texto — a convenção 3 do Tailwind ("nada de
interpolação dentro de `class`") continua a ser verificada sem alteração,
quer o autor escreva `class={variant}` (antes) quer `class=${variant}`
(depois): ambos são um valor de atributo que não é um
`Expr.StringLiteralExpr` totalmente literal, que é o que o teste rejeita.

## D5 — Transição na forma "aceitar e rejeitar com mensagem", nunca "aceitar e compilar" (**decidido**)

Não há período em que as duas grafias compilem — isso perpetuaria
exatamente a confusão que o subprojeto existe para eliminar. Mas também
**não** se remove a produção legada da gramática.

**Porque não um corte direto puro** (remover `LBRACE expression RBRACE`):
por C2. Nos dois caminhos que removem os error listeners, um `.sk` por
migrar deixaria de fazer parse e o ANTLR recuperaria em silêncio,
produzindo um AST parcial. No `RegistryGenerator` isso é um manifesto
gerado a partir de uma árvore truncada — a falha mais cara de detetar de
todo o monorepo, porque sai commitada em JSON com aspeto normal.

**Desenho:**

1. **A gramática mantém a produção legada**, como alternativa etiquetada:

   ```
   interpolation
       : EXPR_INTERP_START expression RBRACE     # ModernInterpolation
       | LBRACE expression RBRACE                # LegacyBraceInterpolation
       ;
   ```

   Custo: duas linhas. Benefício: o parse continua **total** nos três
   caminhos de C2, e o AST continua completo mesmo para ficheiros por
   migrar — o manifesto do registry nunca fica truncado.

   `stringPart` passa a referenciar a mesma regra `interpolation`. A
   alternativa legada é inalcançável lá dentro por C5 (nunca há um token
   `LBRACE` em `STRING_MODE`); é uma consequência subtil e tem de ficar
   comentada na gramática, para ninguém "limpar" o que parece ser uma
   ambiguidade.

2. **O AST marca a forma usada.** `Statement.Interpolation`
   (`suko-core/src/main/java/io/suko/lang/ast/Statement.java:20-21`) e
   `Statement.Attribute` (`:14`) ganham um componente
   `boolean legacyBraceForm`.
   Custo medido de o fazer assim em vez de o esconder: `new
   Statement.Interpolation(` aparece **2 vezes** e `new
   Statement.Attribute(` **1 vez** em todo o repositório, todas em
   `SukoAstBuilder` (`:148-150`, `:277`, `:296-308`); os 9 sítios que fazem
   `case Statement.Interpolation` não são afetados por acrescentar um
   componente ao record. Alternativas descartadas: acrescentar uma lista a
   `SukoFile` (14 sítios de construção, quase todos em testes) ou farejar o
   caractere na posição do `SourceSpan` (frágil e não tipado).

3. **O `SemanticChecker` emite os diagnósticos, com severidade ERROR.**
   Dois códigos novos:

   | Código | Dispara em | Mensagem (forma) |
   |---|---|---|
   | `LEGACY_BRACE_INTERPOLATION` | `{expr}` em posição de statement | `'{label}' já não interpola — escreva '${label}'` |
   | `LEGACY_BRACE_ATTRIBUTE` | `attr={expr}` | `'disabled={disabled}' já não interpola — escreva 'disabled=${disabled}'` |

   A mensagem inclui o texto corrigido literal, reconstruído a partir do
   `Expr`, não uma instrução genérica. Como `JteCompiler` aborta em
   `hasErrors()` (`JteCompiler.java:66`, `:93`, `:112`), isto é um corte
   duro na semântica com aterragem suave na mensagem.

4. **Os diagnósticos vivem no percurso completo, não no parcial** (C3). São
   implementados em `checkStatement` (`SemanticChecker.java:256-286`), e
   este subprojeto **corrige** os dois buracos de travessia encontrados:
   `checkBareBraceInStatement` passa a descer a `if`/`for`/`switch` (ou é
   fundido no percurso completo), e ambos passam a descer aos corpos de
   slot fill. Sem esta correção, `Alert.sk`/`Badge.sk`/`Button.sk` — que
   têm o corpo inteiro dentro de um `switch` — não receberiam nenhum aviso
   de migração, e o `BARE_BRACE_IN_STRING` que já existe continuaria cego
   nesses mesmos ficheiros.

`BARE_BRACE_IN_STRING` (`SemanticChecker.java:225-247`) mantém-se tal como
está: chavetas dentro de strings continuam texto literal, e a sugestão que
ele já dá (`use '${ident}'`) passa a apontar para a **mesma** forma que
todo o resto da linguagem usa.

## D6 — Corrigir o escape `\$` (**decidido**, entra por arrasto de D1)

Ver C7. `SukoAstBuilder.buildStringLiteral` passa a traduzir os escapes
Suko para escapes Java válidos em vez de os copiar crus: `\$` → `$`, `\"` →
`\"`, `\\` → `\\`, `\n`/`\t`/`\r` preservados. Mudança contida a
`buildStringLiteral`/`flushLiteral` (`SukoAstBuilder.java:361-385`);
`emitStringPart` não muda.

Primeira sonda da Fase 0, antes de qualquer tarefa de gramática: confirmar
contra o `javac`/`gg.jte` real que `"custa \$5"` hoje **não** compila e que
a tradução proposta o faz compilar e renderizar `custa $5`.

**Limitação que fica, e tem de ser documentada:** fora de uma string não há
escape para um `${` literal. Já é verdade hoje para `{` (um `{` solto em
texto livre não faz parse); passa a ser verdade para `${`. Consequência
prática: JS com template literals dentro de um `<script>` inline não é
escrevível num `.sk` — o que já era verdade antes deste subprojeto.

## D7 — Estratégia de migração (**decidido**)

A migração é mecânica e cabe numa tarefa por grupo. Inventário medido:

| Grupo | Volume | Notas |
|---|---|---|
| Componentes da biblioteca | 8 `.sk` em `suko-components/src/main/suko/io/suko/ui/` | todos precisam de mudança |
| Exemplos | 5 `.sk` em `examples/` (incl. `examples/invalid/`) | `examples/Card.sk` é referência de superfície, não compila (limitação de generics já documentada) |
| Fixtures de teste `.sk` | ~9 em `suko-registry-generator/src/test/resources/generator-fixtures/`, 1 em `suko-cli/src/test/resources/rewrite-fixtures/` | as do CLI só exercitam `package`/`import`; migração cosmética |
| Fonte `.sk` embutido em Java | ~48 ficheiros `.java` (20 suko-core, 15 suko-cli, 4 suko-components, 3 suko-gradle-plugin, 3 suko-registry-generator, 2 suko-registry, 1 suko-maven-plugin) | **é aqui que está o grosso do esforço**, não nos `.sk` |
| Documentação | `README.md`, `ARCHITECTURE.md`, `suko-components/README.md`, `suko-cli/README.md` | snippets `.sk` inline |
| Golden `.jte` | `suko-core/src/test/resources/golden/` | **não mudam** (C4) |

Detalhe por componente da biblioteca:

- `Label.sk`: `{text}` → `${text}`.
- `Card.sk`: `{header}`, `{children}`, `{footer}`.
- `Alert.sk`, `Badge.sk`: `{children}` em cada ramo do `switch`.
- `Button.sk`: `{label}` em cada ramo, e `disabled={disabled}` →
  `disabled=${disabled}` (D4).
- `Input.sk`: cinco atributos sem aspas (`id`, `name`, `type`, `required`,
  `placeholder`).
- `Field.sk`: só comentários — não tem interpolação.
- `Dialog.sk`: `{title}` e `{children}`; `id="dialog-${id}"` (linha 17)
  **não muda**; `x-data="{ open: false }"` (linha 17) **não muda** e é a
  prova viva de que D1 não parte o Alpine. **O bloco de comentário das
  linhas 3-15 explica a coexistência das duas sintaxes e tem de ser
  reescrito, não ajustado** — senão fica a documentar uma linguagem que
  deixou de existir.

### Manifesto gerado (subprojeto 7)

O mecanismo já existe e é suficiente; não há desenho novo:

- os `sha256` dos 8 `components/*.json` e o `registry.json` ficam
  obsoletos. Regenerar com
  `gradle :suko-components:generateRegistry --console=plain` e commitar;
  `RegistryGoldenTest` falha se divergir, e é essa a rede.
- **Versões:** subir `registryVersion` e o `version` de cada componente de
  `0.1.0` para `0.2.0` — o fonte passa a exigir um compilador mais recente,
  e o consumidor tem de conseguir ver isso no índice.
- **`schemaVersion` não muda** (fica em 1): a forma do JSON é idêntica.
- **Hash hard-coded a corrigir:**
  `suko-cli/src/test/java/io/suko/cli/command/UpdateCommandTest.java:249`
  contém `"74a03dcf...eca3"`, que é o sha256 real do `Label.sk` atual. Depois
  da migração o `assertNotEquals` continua verde mas deixa de testar o que
  diz testar. Item explícito do checklist, não descoberta de revisão.
- **Lacuna registada, não fechada (C8):** o manifesto não tem
  `minSukoVersion`. Um consumidor com compilador antigo que faça
  `suko add --ref main` recebe fonte que não parseia. Mitigação existente: a
  tag por omissão deriva da versão da CLI. Documentar como limitação
  aceite; o campo novo é âmbito de outro subprojeto.

## D8 — Risco em `suko-registry`/`suko-cli`: baixo e delimitado (**decidido**)

- **`suko-registry`: zero.** É modelo de dados + Gson desde a tarefa 2 do
  subprojeto 8; não sabe o que é um `.sk`.
- **`suko-cli`: zero funcional.** `NamespaceRewriter`
  (`suko-cli/src/main/java/io/suko/cli/NamespaceRewriter.java`) é ancorado
  linha a linha em `^\s*(package|import)\s+<dotted>\s*;` e declara
  explicitamente que **não** é um parser Suko (o módulo não depende de
  `suko-core` em produção); `TextDiff` e `Hashes` são textuais/byte a byte.
  O impacto resume-se a fixtures e hashes (D7), mais o facto de qualquer
  instalação existente passar a ver drift de upstream no `suko.lock.json` —
  que é o comportamento **correto** do desenho de duplo hash, não uma
  regressão.
- **`suko-registry-generator`: é o único ponto genuinamente sensível**, e só
  na variante "corte direto" que D5 rejeita (C2). Com a produção legada
  mantida na gramática, o parse continua total, o AST continua completo, o
  manifesto continua correto — e a falha aparece mais tarde, como erro de
  compilação legível, em vez de um JSON silenciosamente errado.

## D9 — Não-objetivos (**decidido**)

Declarados aqui para impedir scope creep durante a implementação; todos
tocam código que este subprojeto vai ter aberto à frente, e por isso são
tentadores.

- **Não alargar `textRun` para aceitar `{`/`}` literais em texto livre.**
  Torna-se *tecnicamente possível* depois de D1 (a chaveta deixa de ser
  sigilo de interpolação), mas `}` continua a fechar `templateBlock`/
  `slotBlock`/`if`/`for`/`switch`, logo a liberalização seria parcial e
  reabriria a família de bugs de gulodice do `textRun`
  (`ARCHITECTURE.md:334-350` e `:365-378`). Follow-up separado, se alguma
  vez se justificar.
- **Não alargar `htmlName` para aceitar `:`/`@`** (Alpine `x-on:click`,
  `@click`). Continua a ser o trabalho descrito em
  `ARCHITECTURE.md:416-427` — e agora com a dependência explícita de D3
  registada: quem o fizer tem de resolver a colisão de `$ident` com as
  magic properties do Alpine ao mesmo tempo.
- **Não remover `$ident`** — D3, decisão do utilizador.
- **Não mexer em auto-`toString`, ancoragem de concatenação, nem semântica
  de `null` em strings.** Este subprojeto muda o sigilo, não a emissão.
  Qualquer mudança nessa área invalida C4 e destrói o critério de aceitação
  "nenhum golden muda".
- **Não introduzir um campo de versão de linguagem no manifesto** (C8).
- **Não corrigir as duas limitações de gulodice do `textRun` já
  documentadas** (slot nomeado depois de conteúdo solto;
  `var c = Card() { ... }`). São a mesma regra, mas não são este problema.
- **Não tocar em `suko-website`** — subprojeto seguinte.

## Superfície da linguagem: antes e depois

| Posição | Antes | Depois |
|---|---|---|
| Corpo de componente / dentro de tag | `{expr}` | `${expr}` |
| Valor de atributo sem aspas | `attr={expr}` | `attr=${expr}` |
| Valor de atributo com aspas | `attr="...${expr}..."` | igual |
| Dentro de string, forma longa | `"${expr}"` | igual |
| Dentro de string, forma curta | `"$ident"` | igual (D3) |
| Chaveta nua dentro de string | texto literal | igual |
| Chaveta nua fora de string | interpolava | **erro com sugestão** (D5) |
| `$` literal dentro de string | `\$` gera Java inválido (C7) | `\$` funciona (D6) |
| `${` literal fora de string | não escrevível | não escrevível (limitação documentada) |

## Mudanças por ficheiro

| Ficheiro | Mudança | Natureza |
|---|---|---|
| `suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4` | `EXPR_INTERP_START` declarado em `DEFAULT_MODE` sem comando de modo; a regra de `STRING_MODE` re-tipa para ele e mantém `pushMode`. `RBRACE`, `SIMPLE_INTERP_START`, `SIMPLE_DOLLAR`, `STRING_TEXT` **inalterados** | aditiva |
| `suko-core/src/main/antlr/io/suko/lang/SukoParser.g4` | `interpolation` com duas alternativas etiquetadas (moderna + legada); `textRun` exclui `EXPR_INTERP_START` (C1); `attribute` usa `interpolation`; `stringPart` reutiliza `interpolation` | **breaking na linguagem**, aditiva no ficheiro |
| `suko-core/.../ast/Statement.java` | `Interpolation` e `Attribute` ganham `boolean legacyBraceForm` | breaking mecânico, 3 sítios de construção |
| `suko-core/.../ast/Expr.java` | **nenhuma** (D3 rejeitada) | — |
| `suko-core/.../SukoAstBuilder.java` | ler o token novo; marcar a forma legada; tradução de escapes (D6) | contida |
| `suko-core/.../JteEmitter.java` | **nenhuma mudança de saída**; nenhuma remoção (D3 rejeitada) | — |
| `suko-core/.../semantic/SemanticChecker.java` | `LEGACY_BRACE_INTERPOLATION` e `LEGACY_BRACE_ATTRIBUTE`; correção dos dois buracos de travessia (C3) | aditiva + correção |
| 8 `.sk` + exemplos + fixtures + ~48 `.java` com fonte inline | migração de grafia (D7) | mecânica |
| `suko-components/registry.json` + `components/*.json` | regeneração + bump 0.1.0 → 0.2.0 (D7) | gerada |
| `ARCHITECTURE.md`, READMEs | ver "Achados" e "Próximos passos" | documentação |

## Achados em `ARCHITECTURE.md` a corrigir neste subprojeto

Encontrados durante o scoping; nenhum é opcional, porque todos afetam
decisões que este subprojeto ou o seguinte vão tomar.

1. **`ARCHITECTURE.md:379-383` está factualmente errado.** Afirma: "Erros
   de parse não param a compilação. Não há `ErrorListener` em `src/main`: o
   ANTLR imprime o erro no stderr e o `SukoAstBuilder` continua a percorrer
   uma árvore com nós de erro, produzindo `.jte` corrompido em silêncio."
   Existe:
   `suko-core/src/main/java/io/suko/lang/diagnostic/SukoErrorListener.java`,
   ligado em `JteCompiler.parseAndBuild` com `hasErrors()` a abortar. O que
   é verdade — e é uma afirmação **diferente e mais útil** — é o que está em
   C2: o caminho principal tem listener e aborta; `ProjectIndex` e
   `RegistryGenerator` removem-no de propósito e recuperam em silêncio. Custo
   de deixar como está: este subprojeto (e os seguintes) tomariam decisões de
   sintaxe sobre uma premissa falsa — a diferença entre "corte direto é
   suicida em todo o lado" e "corte direto é arriscado só na geração do
   manifesto". A redação nova tem de nomear os três caminhos.

2. **O bullet do subprojeto 6 (`ARCHITECTURE.md:517-528`)** descreve
   "interpolação real `${expr}`/`$ident` em strings e atributos". Depois
   deste subprojeto, "em strings e atributos" passa a "em todas as
   posições", e a menção a `{expr}` como forma de statement desaparece da
   descrição da linguagem.

3. **Renumeração do roadmap** — ver a nota no topo desta spec (assumida, não
   confirmada).

## Riscos

- **R1 (alto) — `textRun` engole `${`.** Ver C1. Se a exclusão falhar ou for
  incompleta, o sintoma é texto literal em HTML em vez de um valor, sem erro
  nenhum — a falha mais difícil de apanhar por revisão de diff. Mitigação:
  sonda da Fase 0 antes de qualquer outra tarefa, com casos de `${}` colado a
  texto dos dois lados, e teste de preservação de espaços (que depende de
  `SukoAstBuilder.textOf`, `:410-420`, estender o span sobre whitespace
  adjacente).
- **R2 (alto) — migração incompleta que passa despercebida.** ~48 ficheiros
  Java com fonte inline é muito para fiar em grep. Mitigação: D5 (a forma
  legada é ERROR, não aviso) faz a suite de testes falhar em qualquer
  ficheiro esquecido — desde que C3 esteja corrigido, senão os corpos dentro
  de `switch`/`if`/`for`/slot fill escapam.
- **R3 (médio) — manifesto gerado a partir de AST truncado.** Ver C2 e D5. O
  desenho da produção legada mantida na gramática é precisamente a mitigação;
  o risco reaparece se alguém "limpar" essa alternativa durante a
  implementação por a achar morta.
- **R4 (médio) — o token novo interage mal com a pilha de modos.** Ver C6.
  Mitigação: o `${` de `DEFAULT_MODE` não empurra modo; teste explícito de
  um `${}` em statement dentro do mesmo componente que tem um `${}` dentro de
  uma string, para provar que a pilha fica equilibrada.
- **R5 (médio) — regressão silenciosa na emissão.** Mitigação: nenhum golden
  `.jte` pode mudar (C4). Tornar isto um critério de aceitação explícito do
  plano, não uma observação.
- **R6 (baixo) — `\$` no escape (D6) mexe numa área sem testes.** Mitigação:
  sonda primeiro, teste de render real depois.
- **R7 (baixo, herdado) — colisão `$ident` × Alpine.** Ver D3. Não é mitigado
  neste subprojeto por decisão do utilizador; fica documentado em
  `ARCHITECTURE.md` e amarrado ao subprojeto que alargar `htmlName`.

## Testes

Convenção do projeto: render real via `gg.jte` (`JteRenderSupport`) sempre
que o achado dependa de código Java gerado; nada de smoke de parse quando há
forma de provar o comportamento. E, para qualquer alteração aos `.g4`,
regenerar (`gradle generateSukoLexer generateSukoParser --console=plain`) e
confirmar a ausência da palavra `warning` na saída.

- **Sondas da Fase 0** (antes de planear tarefas): (a) `"custa \$5"` não
  compila hoje e compila depois de D6; (b) `${}` a nível de statement colado
  a texto dos dois lados não é engolido pelo `textRun`; (c) `type()` a
  referenciar um token declarado noutro modo é aceite pelo ANTLR 4.13.1 sem
  warnings.
- **Grafia nova, render real**: `${expr}` em corpo de componente, dentro de
  tag, dentro de `if`/`for`/`switch`, dentro de slot fill, e em
  `attr=${expr}` — com escape HTML confirmado.
- **Preservação de espaços**: `Olá, ${name}!` e `${name} !` renderizam com os
  espaços exatos (é o caso que motivou a correção de `textOf`).
- **Equilíbrio de modos**: um componente com `${x}` em statement **e**
  `"a ${y} b"` em atributo no mesmo ficheiro.
- **Diagnósticos de migração**: `{expr}` em statement e `attr={expr}` dão
  ERROR com a correção literal na mensagem; e — o caso que C3 torna
  obrigatório — o mesmo dispara quando a ocorrência está dentro de um
  `switch`, de um `if`, de um `for` e de um corpo de slot fill.
- **Não-regressão do que fica igual (D3, C5)**: `"$ident"` continua a
  interpolar; `x-data="{ open: false }"` continua texto literal;
  `BARE_BRACE_IN_STRING` continua a disparar com a mesma mensagem.
- **Golden files**: nenhum `.jte` de `suko-core/src/test/resources/golden/`
  muda. Critério de aceitação, não observação.
- **Biblioteca**: `RegistryGoldenTest` passa com o manifesto regenerado;
  `LibraryConventionsTest` passa sem alteração; a suite de render de
  `suko-components` renderiza os 8 componentes migrados com o `gg.jte` real.
- **CLI**: `FullCycleTest` passa ponta a ponta com os componentes migrados; o
  hash hard-coded de `UpdateCommandTest:249` é atualizado.

## Próximos passos

Depois deste subprojeto, `ARCHITECTURE.md` é atualizado:

- **"Decisões de design que moldam o pipeline"**: entra um bullet novo com a
  regra invariante de D1 (`${}` em todas as posições; chaveta nua nunca
  interpola), o racional contra `{}` (colisões Alpine/CSS/JS, herdado do
  subprojeto 6) e contra uma terceira forma, e a nota de que a grafia do
  fonte passa a coincidir com a do `.jte` gerado.
- **Bullet do subprojeto 6** (`:517-528`): corrigido para "interpolação em
  todas as posições", sem `{expr}` de statement.
- **"Limitações conhecidas"**: entra a colisão `$ident` × magic properties do
  Alpine (D3), amarrada ao subprojeto que alargar `htmlName`; entra a
  ausência de escape para `${` literal fora de string (D6); entra a ausência
  de `minSukoVersion` no manifesto (C8).
- **Correção factual** do bullet de erros de parse (`:379-383`), com os três
  caminhos nomeados (achado 1).
- **"Roadmap por subprojeto"**: este item passa a CONCLUÍDO com ponteiro para
  esta spec, **e a renumeração é aplicada aos quatro sítios listados no topo
  deste documento** — sujeita à confirmação pedida lá.

O subprojeto seguinte (site de documentação) beneficia diretamente: passa a
documentar **uma** regra de interpolação em vez de duas, e o scoping da
dependência de `suko-website` sobre `suko-components` (achado já registado em
`ARCHITECTURE.md:110-119`) continua por resolver lá, não aqui.
