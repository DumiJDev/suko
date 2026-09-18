# Suko — Subprojeto 6: Modelo de Componente

Data: 2026-09-18

## Contexto

Os subprojetos 1-4 do roadmap original estão concluídos e mergeados em
`main` (ver `ARCHITECTURE.md`). Depois disso, uma sessão sem spec/plano
formal produziu trabalho não commitado (`TemplateResolver`,
`SukoProjectCompiler`, `SukoImport`) e um `PLAN_CODE.md` com um roadmap
auto-inventado. O agente `architect` auditou esse trabalho
empiricamente (sondas contra o `gg.jte` 3.1.12 real), confirmou vários
bugs reais (alias de import nunca lido, resolução de nome composto
inalcançável, `IllegalStateException` não tratada em nomes duplicados
entre pacotes, regressão silenciosa na emissão de slots cross-pacote) e
recomendou **não commitar esse código como está** — guardá-lo como
spike numa branch e formalizar o trabalho como subprojetos próprios.
`PLAN_CODE.md` deve ser apagado (substituído por este documento e pelos
subprojetos formais 5-9 registados em `ARCHITECTURE.md`).

Este documento é o subprojeto **6** desse roadmap revisto:

5. Projeto multi-ficheiro (resolução de nomes) — spec própria, ainda
   por escrever, cobre `TemplateResolver`/`SukoProjectCompiler` feito
   de raiz (reusando `JteCompiler`, não um fork).
6. **Modelo de Componente** (este documento) — independente do 5,
   pode correr em paralelo.
7. Registry e biblioteca de componentes (Tailwind, um por ficheiro) —
   depende do 5 e do 6.
8. CLI de distribuição estilo shadcn/ui (`suko add`) — depende do 7.
9. Site de documentação Tailwind — depende do 7 e do 8, não é
   subprojeto de compilador.

O utilizador quer que a biblioteca de componentes (7) seja distribuída
como o shadcn/ui: a CLI copia o código-fonte `.sk` para o projeto do
consumidor, que passa a possuir e customizar esse código — não é uma
dependência de biblioteca. Isso não muda o escopo deste subprojeto
diretamente, mas motiva os dois requisitos centrais abaixo: uma
sintaxe de `.sk` que pareça natural a um developer Java, e componentes
que possam compor-se livremente (`Field` compõe `Label` + `Input`,
`Form` recebe filhos livres, etc.).

## Decisão central: `Component` substitui `slot<T>`

`slot<T>` é substituído por `Component` em toda a superfície da
linguagem. **Substituição completa, sem forma dupla** — o projeto não
tem consumidores externos ainda, e `examples/` é migrado como parte da
aceitação deste subprojeto (a fatia que os testes tocarem; a migração
completa de `examples/` para um-componente-por-ficheiro é âmbito do
subprojeto 5).

`Component` é um **nome Suko especial**, reconhecido pelo
`SukoAstBuilder` do mesmo jeito que `Content`/`List`/`Function` já são
hoje (hardcoded em `JteEmitter.javaType`) — mapeia para `gg.jte.Content`
no Java gerado. **Não é uma interface nova, não há jar de runtime
Suko.** Mantém a decisão de arquitetura já existente: Suko é só uma
camada de sintaxe sobre o JTE.

### Cardinalidade e opcionalidade — só genéricos Java, sem símbolo novo

| Conceito | Sintaxe `.sk` | Nota |
|---|---|---|
| Um, obrigatório | `Component x` | equivalente ao `slot<Content> x` de hoje |
| Muitos | `List<Component> x` | equivalente a `List<slot<Content>> x` |
| Opcional | `Component x = null` | reusa o mecanismo de valor por omissão que `ValueParam`/`SlotParam` já têm (`Optional<Expr> defaultValue`) — **nenhuma gramática nova** |

Rejeitámos deliberadamente um sigilo `?` (`Component?`) e o
`Optional<Component>`: o primeiro exigia um token novo na gramática
(`type` não tem noção de opcional hoje) para um conceito que já existe
por outro caminho; o segundo é um anti-padrão conhecido de Java como
tipo de parâmetro. `= valor por omissão` unifica "opcional" sob uma
única regra para `ValueParam` e para conteúdo, e não exige gramática
nova nenhuma.

### Render-props: forma explícita substitui a heurística de scan

Hoje um `SlotParam` é "render-prop" **por deteção heurística**: o
`JteEmitter` faz scan ao corpo do componente à procura de um
`CallExpr` cujo `callee` é um `PrimaryExpr` com o mesmo nome do slot
(decisão da ronda `2026-09-14-suko-nucleo-ajustes`, documentada em
`ARCHITECTURE.md` como "não uma declaração explícita do autor do
`.sk`" — friável, calculada por análise do corpo, não pela assinatura).

Com `Component` a existir, a forma passa a ser **explícita na
assinatura**, real Java:

| Assinatura `.sk` | Java gerado | Cardinalidade |
|---|---|---|
| `Component x` | `gg.jte.Content x` | ONE |
| `List<Component> x` | `List<gg.jte.Content> x` | MANY |
| `Function<T, Component> x` | `Function<T, gg.jte.Content> x` | render-prop |

`Param.SlotParam` mantém-se como nó distinto do AST (o verificador
precisa de saber "isto produz conteúdo", cardinalidade e se é
render-prop), mas o critério de reconhecimento no `SukoAstBuilder`
muda de `type.name().equals("slot")` para reconhecer as três formas
acima. **A heurística de scan do corpo é removida** — é a assinatura
que decide, não o uso.

## Children implícitos

### Problema

Hoje, conteúdo solto dentro de um bloco de chamada é descartado em
silêncio: `Layout() { <p>x</p> side { ... } }` só lê `side`, o `<p>x</p>`
desaparece sem erro (`slotBlock: LBRACE (namedSlot | templateStatement)*
RBRACE`, mas o `SukoAstBuilder` só lê `namedSlot`). Isto obriga todo
componente-invólucro (`Card`, `Modal`, `Layout`, `Form`, `Field`, ...)
a exigir um slot nomeado no ponto de chamada, o que é ruído para quem
só quer "meter conteúdo lá dentro" — o padrão mais comum de uma
biblioteca de componentes shadcn-style.

### Desenho: nome reservado `children`

- Um componente que quer receber conteúdo solto declara um parâmetro
  chamado literalmente `children`, tipado `Component` (cardinalidade
  ONE). Todo o conteúdo solto do bloco de chamada — texto, HTML,
  chamadas de componente, misturados, na ordem em que aparecem — é
  reunido num único bloco de conteúdo, exatamente como já acontece
  hoje para qualquer `SlotFill` ONE com corpo de vários `Statement`.
  **A síntese implícita só se aplica a `Component children` (ONE).**
  `List<Component> children` continua a existir como parâmetro válido
  (mesmo mecanismo `MANY` que já existe hoje), mas exige fills
  nomeados explícitos (`children { ... } children { ... }`) — conteúdo
  solto não se reparte automaticamente em vários elementos de lista,
  porque essa repartição não é decidível a partir da gramática (não
  há separador entre "grupos" de conteúdo solto). Fica registado como
  possível extensão futura, não implementada nesta ronda.
- `children` passa a ser **nome reservado**: um parâmetro chamado
  `children` que não seja `Component`/`List<Component>` é erro do
  verificador.
- No ponto de chamada, o autor do `.sk` **nunca escreve `children {
  ... }`** — escreve o conteúdo solto diretamente:
  ```
  Field {
    "Nome"
    Input(name="nome")
  }
  ```
  Isto é exatamente o `props.children` do React — o nome só importa do
  lado de quem declara o componente, nunca de quem o chama.
- Mecanismo de implementação: o `SukoAstBuilder` (hoje só itera
  `ctx.slotBlock().namedSlot()`) passa a recolher também os
  `templateStatement` soltos do `slotBlock`, na ordem em que aparecem
  no fonte, e sintetiza um `Statement.SlotFill("children",
  Optional.empty(), stmts)`. **O `JteEmitter` não muda** — já sabe
  emitir slot fills, incluindo o caso `List<Component>` (`List<Content>`
  no Java gerado).
- Espaço em branco entre statements soltos não é problema: `WS : [ \t\r\n]+
  -> skip` já garante que nunca chega ao parser, logo `Layout() {\n
  header { }\n}` não sintetiza um `children` fill vazio.

### Diagnósticos novos

- Conteúdo solto num componente cujo alvo não declara `children` →
  erro explícito (hoje é descarte silencioso).
- Conteúdo solto **e** um fill explícito nomeado `children { ... }` na
  mesma chamada → erro (ambíguo; só uma das duas formas é aceite por
  chamada).
- Parâmetro chamado `children` com tipo que não é `Component` nem
  `List<Component>` → erro na declaração do componente.

## Componente como valor de primeira classe

### Problema / objetivo

O utilizador confirmou que quer poder usar uma chamada de componente
como **valor**, não só como statement que emite diretamente — por
exemplo `Component c = condicao ? CardA() : CardB();`, ou passar uma
chamada de componente como argumento doutra chamada. Hoje
`ComponentCallStmt` só existe como `Statement`, nunca como `Expr`
(`Expr` é `sealed` e não o inclui).

**Nota importante de âmbito, descoberta durante o design:** o caso
comum de "misturar texto/HTML/componentes dentro de um bloco de
chamada" **já funciona hoje** através do `SlotFill` existente (`body:
List<Statement>`, emitido como bloco de conteúdo `@`...`` do JTE) — um
`Statement` já pode ser `TextRun`, `HtmlElement`, `ComponentCallStmt`,
`Interpolation`, etc., livremente misturados. Isso não precisa de
nenhuma hierarquia Java sealed nova (`PlainText`/`HTMLComponent`/
`CustomUserComponent`) — já é estrutural no `Statement` selado
existente. O trabalho novo desta secção só é preciso para o caso mais
estreito: uma chamada de componente em **posição de expressão**.

### Desenho

- Novo nó de AST: `Expr.ComponentCallExpr(String componentName,
  List<Arg> args, SourceSpan span)` — como `ComponentCallStmt`, mas sem
  `slotFills` (não se preenchem slots inline dentro duma expressão; se
  for preciso, a chamada continua a poder ser feita em posição de
  statement).
- Gramática: permitir `componentCall` sem `slotBlock` (i.e., só
  `qualifiedName typeArguments? LPAREN argList? RPAREN`, sem o `{ }`
  final) também dentro de `primary`/`expression`, não só de
  `templateStatement`.
- Emitter: `emitExpr` ganha um caso para `ComponentCallExpr` — resolve
  o nome do alvo (via `callResolver`, tal como a forma statement já
  faz) e embrulha em bloco de conteúdo JTE: `@`@template.X(args)``,
  produzindo um valor `Content` avaliável inline (mesmo mecanismo já
  usado nos slot fills, aplicado agora a uma posição nova).
- Verificador: `Expr.ComponentCallExpr` é validado com a mesma lógica
  de existência/aridade que `ComponentCallStmt` já tem — partilhar
  código de validação entre os dois, não duplicar.

### Risco a verificar empiricamente antes de implementar

Ao contrário do resto deste design (que reutiliza mecanismos já
usados no projeto), **o embrulho `@`@template.X(...)`` em posição de
expressão Java arbitrária (dentro de um `var c = ... ? ... : ...;`
gerado) ainda não foi confirmado contra o `gg.jte` 3.1.12 real.** É
uma extrapolação de um padrão já usado nos slot fills (que só
aparecem como argumento nomeado dum `@template.X(...)`, nunca dentro
de uma expressão condicional Java arbitrária). Isto tem de ser a
primeira sonda do plano de implementação (convenção do projeto:
nenhuma forma nova de Java gerado avança sem confirmação empírica
prévia) — se `@`...`` não for válido nessa posição, a alternativa é
uma variável `content` local (`var __c0 = cond ? @`@template.CardA()``
: @`@template.CardB()``; ${__c0}`), a confirmar também por sonda.

## Interpolação real em strings e atributos

(Decisão já tomada nesta sessão — D2/D2-bis/D5, incluída aqui por
pertencer ao mesmo subprojeto de "ergonomia de composição".)

### Estado atual (bug, causa-raiz confirmada por sonda)

`class="btn btn-{variant}"` faz parse e renderiza **literalmente**
(`{variant}` sai como texto, HTML errado em silêncio). A sintaxe já
desenhada no lexer para isto é `${expr}`/`$ident`
(`EXPR_INTERP_START`/`SIMPLE_INTERP_START`), mas está partida: o token
`EXPR_INTERP_START` (`'${' -> pushMode(DEFAULT_MODE)`) nunca volta ao
`STRING_MODE` porque `RBRACE` não tem `popMode`. Mesmo corrigindo o
lexer, `SukoAstBuilder.buildStringLiteral` concatena todas as partes
num único `Expr.StringPart.Literal` — `Expr.StringPart` só tem essa
variante.

### Decisão de sintaxe: `${expr}` / `$ident`, não `{expr}` bare

Rejeitámos chaveta nua (`{expr}`) dentro de strings porque colide com
usos reais já aceites hoje pela linguagem: `x-data="{ open: false }"`
(Alpine.js — exatamente o que uma biblioteca shadcn-style usa para
interatividade), `style="--tw-ring: {0}"` (CSS custom property),
`onclick="if(x){go()}"` (JS inline). `${expr}`/`$ident` não colide com
nenhum destes e já está parcialmente desenhado no lexer — só falta
implementar.

### Desenho

1. **Lexer:** `RBRACE` ganha `popMode` de volta a `STRING_MODE` quando
   o modo anterior foi entrado via `EXPR_INTERP_START`.
2. **AST:** `Expr.StringPart` ganha duas variantes novas —
   `Interp(Expr expr)` (para `${expr}`) e `SimpleInterp(String
   identifier)` (para `$ident`) — ao lado da `Literal` existente.
   `Expr.StringLiteralExpr` passa a poder ter partes mistas.
3. **Emitter:** cada `StringLiteralExpr` com partes não-literais emite
   como concatenação Java (`"btn " + variant + " active"`), não como
   uma única string Java.
4. **Diagnóstico:** `{ident}` dentro de uma string onde `ident` é um
   parâmetro conhecido do componente → erro do verificador com
   sugestão explícita (`"use ${ident}"`), para apanhar exatamente o
   engano que motivou este documento — sem isso o erro continua a ser
   "HTML errado em silêncio".

## Auto-`toString` de valores Java arbitrários

(D1/D5 desta sessão.)

### Estado atual (confirmado por sonda contra `jte-runtime` 3.1.12)

`gg.jte.TemplateOutput.writeUserContent` tem overloads para `String`,
`Enum<?>`, `Content`, os 8 primitivos, `Boolean`, `Number`,
`Character` — **não há overload `Object`**. `${x}` já funciona hoje
para `String`/`Number`/`Enum`/primitivos; para `UUID`, `LocalDate`, um
POJO, ou uma `List<Content>` interpolada diretamente, o `.jte` gerado
**não compila** (erro de `javac`, não erro Suko). Isto inclui um caso
já existente e não documentado: `{items}` sobre um slot `MANY`
(`List<slot<Content>>` hoje, `List<Component>` neste desenho) também
não compila como está.

### Desenho

Forma null-safe verificada por sonda: `${x == null ? null : x.toString()}`
— preserva o comportamento de `null` do jte (`""`, não a string
`"null"`), preserva o escape HTML automático, e não exige nenhum
runtime Suko. Aceite avaliar a expressão duas vezes (decisão do
utilizador — mantém o `.jte` gerado legível, que é uma decisão de
design já existente do projeto; a forma de avaliação única com
variável temporária fica reservada só para quando a expressão for uma
chamada de método visivelmente cara, não implementada nesta ronda).

**Exceção obrigatória:** identificadores cujo tipo declarado é
`Component`/`List<Component>`/`Function<_,Component>` (i.e., slots)
**não podem** ser embrulhados nesta forma — `header.toString()`
imprimiria a identidade do objeto Java, não o conteúdo. O emitter já
sabe quais os identificadores são slots (tem a lista de nomes do
componente atual), portanto a regra é decidível: não embrulhar se for
slot ou se o tipo declarado for `Content`/`List<Content>`/`Function<..,
Content>`; embrulhar em todos os outros casos, incluindo expressões de
tipo desconhecido (`{user.nome}`), onde embrulhar é sempre seguro.

## Limitações conhecidas (fim deste subprojeto)

- `Component` não é genérico sobre nenhum outro conceito além do uso
  em `Function<T, Component>` para render-props — não existe
  `Component<X>` para nenhum outro propósito.
- Nenhuma hierarquia Java sealed em runtime para "tipos de children" —
  a distinção texto/HTML/componente existe só na gramática/AST
  (`Statement` selado), nunca como tipo Java exposto ao autor do `.sk`
  nem ao consumidor do `.jte`.
- Resolução cross-ficheiro de `children`/`Component` (chamar um
  componente definido noutro pacote dentro de um children) depende do
  subprojeto 5 — este subprojeto assume ainda o modelo de ficheiro
  único ou multi-componente-no-mesmo-ficheiro que já existe hoje.
- `{expr}` bare (chaveta nua) dentro de strings continua a ser texto
  literal — decisão deliberada, não lacuna.
- Migração completa de `examples/` para um-componente-por-ficheiro não
  é âmbito daqui (subprojeto 5); só migramos os exemplos que os
  próprios testes deste subprojeto tocarem.

## Testes

- Golden-file + render real (convenção do projeto, não smoke de
  parse) para: `Component` ONE obrigatório e opcional (`= null`);
  `List<Component>` MANY; render-prop via `Function<T, Component>` a
  substituir a forma antiga com heurística de scan.
- `children` ONE: conteúdo solto misto (texto + HTML + chamada de
  componente) sintetizado num único fill; erro quando não há
  `children` declarado; erro quando há conteúdo solto E fill
  explícito `children { }` na mesma chamada. `children` MANY
  (`List<Component>`): confirma que continua a exigir fills nomeados
  explícitos (não sintetiza a partir de conteúdo solto).
- `Expr.ComponentCallExpr`: em `var`, em ternário, como argumento —
  precedido da sonda empírica descrita acima.
- Interpolação: `${expr}` e `$ident` dentro de string e de atributo,
  com escape HTML confirmado; diagnóstico de `{ident}` mal-escrito com
  a sugestão `${ident}`.
- Auto-`toString`: `UUID`/`LocalDate`/POJO interpolado (hoje não
  compila); `null` renderiza vazio; slot/`Component` NÃO é embrulhado
  (regressão a evitar).
- Pelo menos 1-2 componentes de `examples/` migrados de `slot<T>` para
  `Component` como prova end-to-end.

## Próximos passos

Após este subprojeto, `ARCHITECTURE.md` é atualizado: seção de
"Decisões de design" sobre slots passa a descrever `Component`
substituindo `slot<T>`; a limitação "Interpolação dentro de literal de
string não funciona" é removida (resolvida); a limitação "conteúdo
anónimo é descartado em silêncio" é removida (resolvida via
`children`); nova limitação registada para os casos explicitamente
fora de âmbito acima. Os subprojetos 5 e 7 (que dependem deste ou
correm em paralelo) ficam desbloqueados para escrever as suas próprias
specs.
