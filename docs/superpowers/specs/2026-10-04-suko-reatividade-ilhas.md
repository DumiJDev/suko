# Suko — Subprojeto 13b: reatividade em ilhas no alvo JTE

Data: 2026-10-04
Estado: **plano 1 (linguagem, API v2, tradutor) implementado no ramo `feat/13b-ilhas`; plano 2 (ilhas no `jte`) por fazer.** Desenho aprovado secção a
secção na conversa de 2026-10-04.
Ordem: **spec agora, implementação depois do item 12** (interop Java),
decidido pelo utilizador a 2026-10-04. Depende do 13a (API de extensões).
**Alinhada com o subprojeto 14 a 2026-10-11** (revisão final do architect ao
14, pendentes I6/I7 em `ARCHITECTURE.md`, item 14): `on*` vs `on:`, `sanitizeUrl`
como porte do `SukoSafe`, um só `urlSchemes`, plano da API v2, pentest das
ilhas. As decisões D1–D8 e as decisões de segurança do utilizador não mudaram.
**Decisões do utilizador a 2026-10-11** (Q1–Q6, em "Decisões (utilizador,
2026-10-11)", no fim) incorporadas no texto: pasta de build para o JS, só
`script-src 'self'` na v1, componentes reativos chamados a partir de Java são
ilhas, `prefixo:nome` em atributos, `style="propriedade: ${v}"` reativo e
`trustedUrl`/`trustedStyle` só no servidor. Q2 e Q6 são assumidas pela
recomendação (o utilizador pode revê-las); o C1 alargado pela Q5 **tem de ser
revisto de novo pelo `security-specialist`** antes do plano.

## Contexto

O item 13 do `ARCHITECTURE.md` já fixa a direção: reatividade **resolvida em
compile-time** (modelo Svelte/Solid, não React), sem proxies, subscrições em
runtime nem virtual DOM, e **nenhuma dependência do Suko em runtime**. Para a
web: HTML renderizado no servidor + "ilhas" interativas com JS vanilla mínimo,
sem framework no cliente; é o alvo mais sensível em segurança.

Esta spec define a **sintaxe** que o Suko ganha, o **modelo de ilhas** e o que
o alvo JTE gera. "Ilha" é o termo interno do alvo JTE para a forma como
materializa um componente reativo (HTML do servidor + um módulo JS); a
linguagem fala de **componentes reativos**, porque a mesma sintaxe tem de fazer
sentido noutros alvos (JavaFX, TamboUI), onde tudo já corre no cliente.

## Decisões (utilizador, 2026-10-04)

- **D1 — Componente reativo explícito:** `reactive component Nome(...)`. Sem
  `reactive`, usar `state`, `derived`, `on:` ou `bind:` é erro. O nome
  `reactive` é **provisório** (o utilizador não está convencido); é uma
  palavra-chave e pode mudar até à primeira release.
- **D2 — Estado declarativo com mutação direta** (estilo Svelte, não
  `useState`): `state T nome = expr;` e `derived T nome = expr;`, com
  **suporte a `var`** ("o Suko ainda é Java").
- **D3 — Eventos com namespace e lambda:** `on:evento=${() -> ...}` ou
  `on:evento=${e -> ...}`. O namespace evita manter uma lista de atributos e
  eventos HTML: qualquer `on:x` é um listener do evento `x`. A lambda sem
  parâmetro é válida — o compilador assume o parâmetro do evento e ignora-o
  (o `_` de Java só existe nas versões mais recentes).
- **D4 — Two-way binding:** `bind:value` / `bind:checked` — lista fechada na
  v1 (revisão de segurança H4): `bind:value` só em `input`, `textarea`,
  `select`; `bind:checked` só em `input`. Qualquer outro nome dá `BIND_TARGET`.
- **D5 — Modelo de renderização no cliente (opção A):** o compilador gera JS
  que constrói o DOM dos blocos que dependem de `state`; `<template>` só para
  conteúdo vindo do servidor (slots `Component`).
- **D6 — Dica do compilador:** `reactive component` sem `state`/`derived`/
  `on:`/`bind:` dá **HINT** "pode ser um `component` simples".
- **D7 — O suporte a JS vive no `suko-jte`**, não num artefacto `jte+js`
  separado. O alvo continua a ser `jte`. (Corrige a arrumação "variantes `+js`"
  do item 13.)
- **D8 — Ordem:** implementação depois do item 12.

## Sintaxe

```
package ui;

import ui.Stepper as Stepper;

// Todo é um record Java: record Todo(String title, boolean done) {}

reactive component TodoList(List<Todo> initial, String placeholder) {
  state var items = initial;
  state var draft = "";
  state boolean showDone = true;
  derived var remaining = items.size();

  <section class="todos">
    <input bind:value=${draft} placeholder="${placeholder}" />
    <button on:click=${() -> { items.add(new Todo(draft, false)); draft = ""; }}>
      Adicionar
    </button>
    <label><input type="checkbox" bind:checked=${showDone} /> Mostrar feitos</label>

    if (showDone) {
      <ul>
        for (var t : items) { <li>${t.title()}</li> }
      </ul>
    }
    <p>${remaining} itens</p>
  </section>
}

reactive component Counter(int start) {
  state var count = start;
  <Stepper value=${count} onChange=${v -> count = v} />
  <button on:click=${() -> count = 0}>reset</button>
}
```

### Regras

- `state` e `derived` só aparecem no topo do corpo de um `reactive component`,
  antes do markup. `derived` não é atribuível.
- **`<!DOCTYPE html>` só em componentes simples** (o layout). Desde o 14 o
  doctype só é aceite como primeiro item do corpo, e um componente reativo é
  sempre um fragmento dentro de uma página. Por isso um `reactive component`
  que comece por doctype dá `INVALID_DOCTYPE` (o código que já existe, com
  uma mensagem própria a apontar para o layout).
- **Tipo:** explícito ou `var`. `var` é inferido dentro do subconjunto do
  cliente (literais, parâmetros com tipo declarado, outros `state`/`derived`,
  operadores, ternário, concatenação, métodos permitidos). Se não for possível,
  `TYPE_NOT_INFERRED` pede o tipo explícito. Com o 11c (tipos Java) a inferência
  passa a cobrir mais casos.
- **`derived`:** recalculado só quando um `state`/`derived` de que depende muda;
  o grafo de dependências é calculado em compile-time; ciclos são erro.
- **`on:evento=${lambda}`:** o valor tem de ser uma lambda (expressão ou bloco).
  Com parâmetro, este tem o tipo fixo do evento (ver "Expressões no cliente").
  Qualquer nome de evento é aceite, sem lista.
- **Nomes são léxicos:** em `on:nome` e `bind:nome` o nome é um identificador;
  `on:${...}`, `bind:${...}` e atributos espalhados são erro de sintaxe. Nomes de
  atributos emitidos no cliente obedecem a `[A-Za-z][A-Za-z0-9:_-]*`.
- **`bind:value` / `bind:checked`:** o alvo tem de ser um `state` simples (não
  `a.b`, não `derived`); caso contrário `BIND_TARGET`. Com `state` numérico, o
  valor do input é convertido; conversão falhada não altera o estado.
- **`onclick="${...}"`** (atributo HTML `on*` cru, sem `:`) é **ERROR
  `UNSAFE_SINK`** em qualquer componente, reativo ou não. É a regra do
  subprojeto 14: o `HtmlSecurityChecker` (`suko-jte`) recusa qualquer atributo
  cujo nome, em minúsculas, começa por `on` e tem valor não literal. Um valor
  literal continua permitido (com `strictCsp`, dá `CSP_INLINE`).
- **Exceção explícita para `on:` e `bind:`** (arrumação; D3/D4 não mudam):
  `on:evento=${lambda}` e `bind:nome=${state}` são sintaxe Suko. Trata-os a
  etapa da 13b (core: parse, `REACTIVE_REQUIRED`, `BIND_TARGET`; `suko-jte`:
  geração), e **nunca chegam ao `HtmlSecurityChecker` como atributo `on*`
  cru**. Concretamente:
  1. o prefixo é exatamente `on:` ou `bind:` em minúsculas; `On:click`,
     `ON:click` ou `Bind:value` (o prefixo reservado, sem distinguir
     maiúsculas, que não é exatamente `on`/`bind`) são erro de sintaxe, não
     HTML cru. A gramática (Q4) aceita-os como `prefixo:nome`, por isso o erro
     é dado pelo construtor do AST e não pelo parser;
  2. no AST não ficam como `Statement.Attribute` com nome `"on:click"` (o
     checker do 14 dava-lhes `UNSAFE_SINK`, porque o nome começa por `on`): o
     plano escolhe entre um nó próprio e um filtro explícito no checker (ver
     API v2, (d)). Qualquer outro atributo começado por `on` continua
     `UNSAFE_SINK`, e `on:`/`bind:` fora de um `reactive component` dão só
     `REACTIVE_REQUIRED`, nunca um segundo diagnóstico;
  3. o `.jte` emitido nunca contém `on:`/`bind:`: o emissor troca-os por
     âncoras. Assim a `PreventInlineEventHandlers` do JTE, se a aplicação a
     ligar (recusa qualquer nome começado por `on`; não faz parte da
     `OwaspHtmlPolicy` 3.1.12), não os vê;
  4. argumentos de componente com nome `on*` (`<Stepper onChange=${...} />`)
     são `Statement.Arg`, não atributos HTML. O checker do 14 já não os trata
     como handlers;
  5. **os outros `prefixo:nome` não são sintaxe Suko** (Q4, decisão de
     2026-10-11): `xlink:href`, `hx-on:click`, `x-on:click` e afins são
     atributos HTML normais, e o `HtmlSecurityChecker` trata-os pelas regras
     do 14 pelo nome completo. `hx-on:*` e `x-on:*` (e `x-*`) com valor não
     literal continuam **`UNSAFE_SINK`**; `xlink:href` é atributo de URL e vai
     por `SukoSafe.url`. Só `on:` e `bind:` (13b) ficam isentos do ramo `on*`
     do checker. O `@` fica fora: `@click` continua erro de sintaxe.
- **Gramática (mudança mínima).** Hoje um nome de atributo é a regra de parser
  `htmlName : Identifier (MINUS Identifier)*` (`SukoParser.g4`, ~l. 191). O
  token `COLON` existe no lexer (`SukoLexer.g4`, ~l. 117), mas o parser não o
  aceita em nomes. Por isso `on:click`, `bind:value`, `xlink:href`,
  `x-on:click` e `hx-on:click` não fazem parse, e os ramos `:`/`@` do
  `HtmlSecurityChecker` são código morto (lacuna (k) do item 14). A 13b
  acrescenta `htmlName (COLON htmlName)?` **só a nomes de atributo** (não a
  nomes de tag). Os três tokens têm de ser adjacentes: o espaço em branco é
  `skip` no modo por omissão, e `on : click` tem de dar erro. `@` continua
  fora. **Decidido (Q4 = B, 2026-10-11):** o prefixo é qualquer `htmlName`
  (identificador, com hífens: `on:click`, `bind:value`, `xlink:href`,
  `hx-on:click`, `x-on:click`), **não** só `on`/`bind`. Isto fecha a lacuna (k)
  do item 14 e torna **código vivo** os ramos `:` do `HtmlSecurityChecker`; os
  testes desses ramos fazem parte da 13b (ver "Testes"). Um nome com `:` só no
  fim ou no início, ou com mais de um `:`, é erro de sintaxe.

## Composição e fronteira servidor → cliente

1. **`component` dentro de `reactive component`:** fora de blocos que dependem
   de `state`, é renderizado no servidor como hoje. **Dentro** de um bloco que
   depende de `state` só entram componentes reativos (consequência de D5); um
   simples dá erro `CLIENT_UNSUPPORTED` ("`Card` não é reativo e não pode ser
   criado no cliente").
2. **`reactive component` dentro de `component`** é o ponto de entrada de uma
   ilha: o servidor renderiza o HTML inicial e escreve os parâmetros em
   `data-suko-props` (JSON); o cliente monta a ilha a partir daí. O mesmo vale
   para um componente reativo **chamado a partir de Java** (item 12, Q3 = A):
   é sempre ponto de entrada, com token, props e `<script>`.
3. **Parâmetros que atravessam a fronteira:** só tipos serializáveis —
   primitivos (sem `long`/`char`, ver abaixo) e wrappers, `String`, enums,
   records e `List`/`Map<String, …>` destes; outros tipos dão
   `PROP_NOT_SERIALIZABLE`. O JSON é visível no HTML, por isso:
   - **Minimização:** só os parâmetros (e componentes de record) efetivamente
     lidos pelo código do cliente são serializados; o resto fica só no render do
     servidor (calculado em compile-time).
   - **`PROP_SENSITIVE` (WARNING)** aplica-se aos nomes dos parâmetros e,
     recursivamente, aos componentes de record alcançáveis pelos tipos
     serializados, com a lista `password, passwd, pwd, secret, token, apikey,
     privatekey, credential, hash, session, cookie, jwt, auth, ssn` (insensível a
     maiúsculas, como prefixo ou sufixo). Um `Map<String, …>` serializado dá INFO
     a lembrar que as chaves ficam visíveis.
   Parâmetros `Component` (slots) não
   vão no JSON: são renderizados no servidor dentro de `<template>` e clonados
   no cliente.
4. **`reactive` dentro de `reactive`:** os parâmetros passados pelo pai são
   reativos (o filho atualiza quando o pai muda); sem JSON, mesma árvore no
   cliente.
5. **Filho → pai:** lambdas como parâmetro (`Consumer<T>`, `Runnable`), só entre
   componentes reativos; uma lambda passada a partir de um `component` dá
   `LAMBDA_ACROSS_BOUNDARY`.
6. **`bind:` em componentes** (`<Stepper bind:value=${count} />` como açúcar
   para `value` + `onChange`): fora da primeira versão.

## Expressões no cliente

Abrange `state`/`derived`, `on:`/`bind:` e tudo dentro de blocos que dependem
de `state`. É Java, traduzido para JS; fora deste subconjunto,
`CLIENT_UNSUPPORTED` com a localização exata. Dentro de um componente reativo,
um `${...}` fora desses sítios e que não toca em `state` continua Java
renderizado pelo JTE no servidor.

**Permitido na v1:**

- Literais; variáveis (`state`, `derived`, parâmetros, variáveis de `for`,
  parâmetros de lambda); `+ - * / %`; comparações; `&& || !`; ternário;
  concatenação de `String`; `++ -- = +=` sobre `state`.
- `String`: `length()`, `isEmpty()`, `trim()`, `toUpperCase()`,
  `toLowerCase()`, `contains()`, `startsWith()`, `endsWith()`, `equals()`.
- `List`: `size()`, `isEmpty()`, `get(i)`, `contains()`; mutações sobre
  `state` reconhecidas e propagadas: `add()`, `remove(int)`, `set()`, `clear()`.
- Records: acessores (`t.title()`) e `new Todo(...)` (objetos JS simples).
- `Math.min`, `Math.max`, `Math.abs`.
- Evento: `e.value()`, `e.checked()`, `e.key()`, `e.preventDefault()`.

**Semântica de Java garantida:**

- `int / int` → `Math.trunc(a / b)`.
- `==` entre `String` é erro, com sugestão de `equals()`.
- `long` e `char`: fora da v1 (`number` perde precisão acima de 2^53).
- `null` → `null`; uma chamada sobre `null` lança `TypeError` em vez de NPE
  (falha visível, como em Java).
- **Diferença assumida e documentada:** overflow de `int` (o JS não dá a volta
  em 2^31).

A lista é fechada: sem chamadas arbitrárias, `static` ou classes do projeto
além de records. Cresce por decisão explícita.

## O que o alvo `jte` gera

- **Sem componentes reativos:** exatamente o mesmo `.jte` de hoje, nenhum
  ficheiro `.js` (o golden do 13a continua a provar).
- **Com componentes reativos:**
  - o `.jte` do componente, com marcadores: `data-suko="pkg.Nome"`,
    `data-suko-props` (só nos pontos de entrada de ilha) e âncoras nos blocos
    que dependem de `state`;
  - um **módulo ES por componente reativo** (`ui/Counter.js`) que exporta a
    função de montagem e, ao ser avaliado, monta todos os
    `[data-suko="ui.Counter"]` ainda não montados;
  - **`suko.js`, gerado uma vez por projeto** (~1 KB, mais o porte do
    `SukoSafe`): montagem, agendador que junta mudanças num microtask,
    reconciliação de listas por índice (por `key` numa versão seguinte) e o
    **porte para JS do `SukoSafe`** (ver "URLs"), com as opções de
    `suko { security { ... } }` gravadas como constantes no build. Gerado, não
    um jar: "nenhuma dependência do Suko em runtime" mantém-se.
  - **Java gerado no projeto:** o serializador de `data-suko-props` (e, se o
    plano o puser aí, o gerador do token de montagem) é `JAVA_SOURCE` em
    `generatedPackage`, emitido por `Target.emitProject` ao lado do `SukoSafe`
    (subprojeto 14). O `.jte` gerado já depende do `SukoSafe` desde o 14 (não
    é autónomo; ver `ARCHITECTURE.md`, item 13); a 13b não muda isso.
  - JS legível, não minificado.
- **Entrega na página:** o `.jte` de cada componente reativo emite
  `<script type="module" src="${base}/ui/Counter.js"></script>` junto ao
  elemento; o browser avalia cada módulo uma vez. Sem scripts inline: funciona
  com CSP `script-src 'self'` **quando os ficheiros são servidos da mesma
  origem**. O `basePath` é validado no build (`^/[A-Za-z0-9._~/-]*$` sem
  começar por `//`, ou um URL `https://` sem `"'<>`, `\` nem espaços); outro
  valor é erro de configuração. A exclusão de `//` acompanha o bloqueio por
  omissão dos URLs relativos ao esquema no 14: `//cdn/x` passa no padrão
  antigo, mas é outra origem. O `src` do `<script>` é literal no `.jte` (o
  `basePath` é uma constante de build), por isso não toca na regra do 14 para
  `<script src>` dinâmico. Nomes de ficheiro com hash do conteúdo (um
  `suko.js` antigo não se mistura com módulos novos) e ganchos opcionais para
  `nonce`/`integrity` ficam para o plano; **`nonce` fica fora da v1**
  (Q2, ver "Fora de âmbito").
- **Configuração:** `suko { js { outputDir = ...; basePath = "/suko" } }` no
  Gradle e o equivalente no Maven. **Pasta por omissão (Q1 = B, decidido a
  2026-10-11): uma pasta de build**, registada pelos plugins como pasta de
  recursos: `build/generated-resources/suko/static/suko` (Gradle) e
  `target/generated-resources/suko/static/suko` (Maven). Os `.js` ficam assim
  no classpath em `static/suko` (servidos em `/suko`, o `basePath` por
  omissão), nunca em `src/` nem no controlo de versões, como o Java gerado do
  14 (`build/generated-src/suko-java`, `target/generated-sources/suko-java`).
  Os plugins registam a pasta (Gradle: como `srcDir` dos recursos do `main`,
  com `processResources` a depender da tarefa de geração; Maven:
  `addResource`); `js.outputDir` continua a existir como **override** (quem o
  usa decide se a pasta é recurso). O custo aceite é os plugins registarem
  mais uma pasta de recursos. O bloco `js` só tem a entrega dos ficheiros. **Tudo o que é política de
  segurança vem de `suko { security { ... } }`** (`urlSchemes`,
  `imageDataTypes`, `allowSchemeRelative`, `urlAttributes`), que governa o
  servidor (`SukoSafe`) e o cliente (porte em `suko.js`). Não há
  `js { urlSchemes }`.
- **Onde se escrevem os `.js`.** Hoje o `ProjectOutputWriter` (core) escreve
  `RESOURCE` na raiz dos templates (ao lado dos `.jte`) e só limpa ficheiros
  obsoletos de `JAVA_SOURCE` (manifesto próprio). O JS da 13b precisa do seu
  próprio destino (`outputDir`, por omissão a pasta de build da Q1) e de
  limpeza por manifesto dos módulos com hash: é a decisão (b) da API v2,
  abaixo.
- **Alvos sem reatividade.** Hoje há um alvo real (`jte`, no `suko-jte`) e o
  de teste (`suko-test-ext`). O site **não** é um alvo `html` próprio: o
  `WebsiteGenerator` usa o `jte` e ignora `projectOutputs` (pendente do 14), por
  isso uma página do site com um componente reativo também precisaria dessa
  lacuna fechada. Os alvos sem capacidade reativa (TamboUI enquanto não a
  tiver, o alvo de teste) declaram-no; um componente reativo nesses alvos dá
  `REACTIVE_UNSUPPORTED`.

## Mudanças na API de extensões (13a → `ExtensionApi.VERSION = 2`)

**Já feito no subprojeto 14, dentro da v1** (ruling R1: a v1 mudou no lugar,
`VERSION` continua 1, porque não há extensões externas):

- `Target.emitProject(ProjectEmitContext)` → `List<ProjectOutput>` (`Kind`
  `TEMPLATE`/`JAVA_SOURCE`/`RESOURCE`). É a emissão ao nível do projeto que
  esta spec previa para o `suko.js`. Hoje só o `SukoSafe.java` a usa.
- `SecurityOptions` (record em `suko-api`), presente em `EmitContext.options`,
  `ProjectEmitContext.options` e `CheckContext.options`.
- `CheckContext.activeVocabularies` (vocabulários, não alvos).
- `Severity.INFO`: o INFO sobre um `Map<String, …>` serializado
  (`PROP_MAP_KEYS`) já tem severidade onde cair, e o Gradle e o Maven
  imprimem-no em sucesso (R3 do 14).

**O que a 13b exigia e sobe `ExtensionApi.VERSION` para 2** (feito no plano 1,
`VERSION = 2`, exceto onde indicado; nada foi publicado):

- `Target.emit` devolve um só `Emitted`. Os `.js` por componente precisam de
  várias saídas por componente, ou então de serem emitidos por
  `emitProject` (que vê o projeto inteiro). Escolhe-se no plano. *(Por fazer:
  decidido para o plano 2, R11, `Emitted.companions`/`CLIENT_ASSET`.)*
- **Capacidades do alvo** (`reactive`), lidas pelo verificador. O
  `CheckContext` expõe vocabulários ativos, não alvos, e o
  `REACTIVE_UNSUPPORTED` precisa dos alvos.
- O AST ganha o modificador `reactive`, `state`/`derived` (novos subtipos
  selados de `Statement`) e as expressões do cliente que hoje não existem em
  `Expr`: lambda, atribuição e `++`/`--`/`+=`, e `new Record(...)`. Ganha
  também a representação de `on:`/`bind:`. São mudanças incompatíveis: os
  `switch` exaustivos sobre `Expr`/`Statement`, incluindo o do
  `HtmlSecurityChecker`, deixam de compilar, o que obriga a tratar os casos
  novos.
- `Severity.HINT` (D6): o enum tem só `ERROR, WARNING, INFO`. Acrescentar
  `HINT` parte os `switch` exaustivos de extensões, como aconteceu com o
  `INFO`. Emitir `REACTIVE_UNUSED` como `INFO` não cumpriria D6.

**Decisões que o incremento para v2 tem de tomar** (revisão do architect ao
14, I7). São decisões para a altura do plano, não compromissos novos desta
spec:

- **(a) Opções.** `SecurityOptions` é específico de HTML/JTE, mas é o único
  canal de opções: `generatedPackage` vive lá, e `generatedJavaDir` vive só
  nos plugins. Decidir se `generatedPackage`/`generatedJavaDir` passam a
  opções de projeto (não de segurança) e se as opções por alvo (ex.:
  `js { outputDir; basePath }`) ficam separadas das de segurança, que
  continuam a ser as mesmas para servidor e cliente.
- **(b) Saídas.** O `ProjectOutputWriter` escreve `RESOURCE` na raiz dos
  templates e só limpa `JAVA_SOURCE`. O JS da 13b precisa do seu diretório
  (`outputDir`, por omissão `build/generated-resources/suko/static/suko` /
  `target/generated-resources/suko/static/suko`, ou um `Kind` novo) e de
  limpeza por manifesto dos módulos com hash. Os plugins registam essa pasta
  como recursos (Q1).
- **(c) Auditoria.** O `SecurityAudit` do core tem fixos os códigos
  `TRUSTED_*` do `suko-jte` e extrai a expressão da mensagem depois de `": "`.
  Decidir uma carga estruturada no diagnóstico (ex.: um mapa de dados
  opcional em `SukoDiagnostic`) que qualquer alvo possa usar, incluindo
  auditorias futuras do cliente.
- **(d) `on*` vs `on:`.** O `HtmlSecurityChecker` trata como handler todo o
  atributo começado por `on`. Decidir a forma da exceção de `on:`/`bind:`
  (nó de AST próprio ou filtro explícito; ver "Regras").
- **(e) Item 12.** Uma classe Java que implemente `Component` escreve HTML cru
  com a mesma confiança que um slot, e o checker nunca a vê. Na v1 da 13b um
  componente Java **não** declara estado do cliente nem cria pontos de
  entrada de ilha por si: pode ser slot de uma ilha (renderizado no servidor
  dentro de `<template>`, como qualquer slot). **Decidido (Q3 = A,
  2026-10-11):** um componente reativo **chamado a partir de Java** (item 12,
  direção a) é **sempre ponto de entrada de ilha** (token + props +
  `<script>`), como se o chamasse um `component`. Consequência: a API Java
  gerada para um componente reativo passa a depender do **token de montagem e
  do serializador de props** (Java gerado em `generatedPackage`, ver "Montagem"
  e "Props"), e o scoping do item 12 tem de a contar (assinaturas, o que a
  API exige que o chamador forneça e como o `<script>` chega à página).

## Segurança

Revisão obrigatória do `security-specialist` **antes do plano** (feita a
2026-10-04 sobre esta spec; achados C1, H1–H4, M1–M4 incorporados abaixo) e
**no fim da implementação**.

### O que se delega ao JTE (decisão do utilizador: não reinventar a roda)

Princípio: tudo o que o JTE já faz no servidor continua a ser do JTE; o Suko só
acrescenta o que o JTE não pode ver.

- **Fica no JTE:** todo o output do servidor passa por `${...}` com o escape
  contextual do `ContentType.Html` (conteúdo HTML, atributo, bloco `<script>` e
  atributo `on*` — `Escape.htmlContent`/`htmlAttribute`/`javaScriptBlock`/
  `javaScriptAttribute`, verificados no `jte-runtime` 3.1.12), incluindo
  `data-suko-props` e `data-suko-mount`; nunca `$unsafe{}`. **Desde o 14** o
  plugin Gradle do Suko liga a `OwaspHtmlPolicy` do JTE (`htmlPolicyClass`,
  com `gg.jte.gradle` aplicado e `security.jtePolicy`, que está ligado por
  omissão). O Maven só avisa. **Correção desta spec:** no `jte-runtime`
  3.1.12, a `OwaspHtmlPolicy` é `PreventUppercaseTagsAndAttributes` +
  `PreventOutputInTagsAndAttributes` + `PreventUnquotedAttributes` +
  `PreventInvalidAttributeNames` (verificado com `javap`). **Não** inclui
  `PreventInlineEventHandlers`, ao contrário do que esta spec dizia. Daí:
  marcadores e âncoras das ilhas emitidos com aspas duplas, nomes em
  minúsculas e sem `${...}` em nomes, e nenhum `on:`/`bind:` no `.jte` (ver
  "Regras").
- **Não pode ficar no JTE, e por isso é do Suko:** (1) o JS que corre no
  browser depois do render — o JTE não o vê, por isso a allowlist de operações
  DOM, o porte do `SukoSafe` no cliente, a validação de props e o token de
  montagem são do código gerado; (2) a verificação de protocolos de URL, que o
  JTE não faz (escapar `javascript:alert(1)` não o torna inofensivo) e que,
  no servidor, já é do `SukoSafe` do 14 em todos os componentes; (3) o JSON de
  `data-suko-props`, porque o JTE não serializa JSON — o Suko gera o
  serializador e o JTE escapa o resultado como atributo; (4) as regras de
  compile-time sobre o `.sk` (`CLIENT_UNSAFE_SINK`), porque o `OwaspHtmlPolicy`
  é configuração da aplicação e o Suko não a controla.

### Operações DOM e sinks (C1)

- O JS gerado só usa esta **lista fechada** de operações DOM:
  `createElement`/`createElementNS` (nome fixo em compile-time; SVG/MathML com
  `createElementNS`), `createTextNode`, `textContent` (nunca em `script`/
  `style`), `setAttribute`/`setAttributeNS`/`removeAttribute` (nome fixo),
  `addEventListener` (nome fixo), as propriedades `value`/`checked` (só via
  `bind:`), `template.content.cloneNode`/`importNode`, `append`/`insertBefore`/
  `remove`. Tudo o resto é proibido; o teste de segurança é um **varrimento por
  allowlist** do JS gerado (qualquer acesso DOM fora da lista falha), não por
  denylist.
- **Alinhado com o subprojeto 14:** a lista abaixo junta-se à M2 de
  `docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md`, que inclui
  `<script src>`, animações SVG, `x-*`/`hx-on*`, `<frame>`, entre outros. O 14
  é a referência, e a implementação é o `HtmlSecurityChecker` +
  `HtmlSecurityRules` do `suko-jte`. Esse checker corre em **todos** os
  componentes com o vocabulário `html`, incluindo os reativos e os seus blocos
  de `state`. `on*` com valor não literal é **ERROR `UNSAFE_SINK`** em
  qualquer componente, e `on:`/`bind:` são a exceção explícita descrita em
  "Regras".
- **Erro `CLIENT_UNSAFE_SINK`** em blocos que dependem de `state` **e** no
  render do servidor de um `reactive component`: (a) elementos `script`,
  `style`, `iframe`, `frame`, `object`, `embed`, `base`, `meta`, `link`,
  `template` (só o servidor emite), `svg:script`, `svg:animate`, `svg:set`,
  `svg:animateMotion`, `svg:animateTransform`, `foreignObject`. Os nomes
  comparam-se como no 14: sem distinguir maiúsculas, pelo nome local e
  independentemente do namespace. `svg:script` designa o `script` dentro de
  `<svg>`; o prefixo não se escreve. (b) Atributos `on*` (sem distinguir
  maiúsculas) com valor não literal. (c) `srcdoc` e `style` com valor não
  literal, **exceto a forma `propriedade: ${v}` do 14** (ver "CSS: `style`
  reativo (C1 alargado)"). Literais estáticos continuam permitidos.
- **Um diagnóstico por sítio.** Onde o `UNSAFE_SINK` do 14 já dispara sobre o
  mesmo atributo ou elemento (ex.: (b), `srcdoc`, conteúdo dinâmico de
  `<script>`), a 13b não acrescenta `CLIENT_UNSAFE_SINK`. Este código cobre só
  o que o 14 não recusa: elementos da lista (a) criados no cliente, mesmo com
  valores literais, e as exceções do 14 que o cliente não implementa (ex.:
  `iframe src` de origem constante, aceite no servidor mas proibido no
  cliente).
- Os nomes de atributos e de eventos são fixos em compile-time (ver "Nomes são
  léxicos").

### CSS: `style` reativo (C1 alargado, Q5 = B)

Decidido a 2026-10-11. O texto original do C1 proibia `style` não literal em
todo o componente reativo; o 14 aceita a forma `style="propriedade: ${v}"` em
todos os componentes (M2, exceção de `style`). Agora a 13b alinha-se com o 14:

- **Aceite** no render do servidor de um `reactive component` e nos blocos de
  `state` (cliente): a parte literal é uma sequência de declarações
  `propriedade: ${expr}` com **nomes de propriedade literais** (incluindo
  custom properties, `--pct: ${pct}%`). Qualquer outra forma com valor não
  literal continua `CLIENT_UNSAFE_SINK`/`UNSAFE_SINK`; `srcdoc` e o resto do
  C1 não mudam.
- **Servidor:** cada valor passa por `SukoSafe.cssValue(...)` (14), como em
  qualquer componente.
- **Cliente: porte de `SukoSafe.cssValue` em `suko.js`**, ao lado do porte de
  `url`: `null` → `unset`; normalização com a regra `<= U+0020` (nunca
  `String.prototype.trim()`, como no porte de URLs); allowlist de correspondência
  **total** (`^...$`, ASCII, sem flag `i`/`u`): número com unidade opcional
  (`[-+]?[0-9]+(\.[0-9]+)?(%|[A-Za-z]{1,4})?`), cor `#hex` (`#[0-9A-Fa-f]{3,8}`)
  ou identificador (`[A-Za-z-]+`); senão `unset`. O valor devolvido é o valor
  **normalizado** (as unidades `<= U+0020` retiradas das pontas, como o
  `String.valueOf(v).trim()` do `SukoSafe.cssValue` Java; nunca
  `String.prototype.trim()`), não o original: o teste diferencial compara
  strings iguais. `null` (e um `Boxed` nulo) → `unset`, nunca o texto `null`.
- **Operação DOM:** a lista fechada de operações ganha `style.setProperty`
  (nome de propriedade fixo em compile-time, valor vindo do porte). A
  recomendação é usar o CSSOM e não `setAttribute("style", ...)`: a CSP da loja
  tem `style-src 'self'` (sem `'unsafe-inline'`), que bloqueia atributos
  `style` escritos como markup/`setAttribute` mas **não** o CSSOM. A escolha
  final fica para o plano; as duas formas ficam dentro da allowlist e do
  varrimento por allowlist.
- **`trustedStyle(...)`:** só no servidor (ver Q6, em "URLs").
- **O C1 alargado tem de ser revisto de novo pelo `security-specialist`**
  (pendente antes do plano, e de novo no fim da implementação). Pontos a
  rever: a forma aceite (nomes literais, um valor por declaração, sem `;` nem
  `url(`/`expression(` nos valores, garantido pela allowlist), a paridade
  string a string do porte, o efeito sobre `bind:` (um valor de input que
  chega a `cssValue`) e a ausência de acesso a `cssText`.

### URLs (H1, H2)

- **Conjunto de atributos: o do 14, sem lista própria.** É o de
  `HtmlSecurityRules.isUrlAttribute`: `href` (qualquer elemento, incl.
  SVG/MathML), `xlink:href`, `src`, `action`, `formaction`, `data` (só em
  `object`), `poster`, `cite`, `background`, `manifest`, `longdesc`,
  `usemap`, `codebase`, `itemtype`, `hx-get|post|put|patch|delete` e os
  `security.urlAttributes` configurados. A estes juntam-se `srcset`/
  `imagesrcset` (por candidato) e `ping` (por URL). Com **qualquer valor não
  literal** (`state`, `derived`, parâmetros, variáveis de `for`,
  concatenações), o JS gerado passa o valor pela função equivalente do porte
  do `SukoSafe` em `suko.js` antes de `setAttribute`/`setAttributeNS` (os
  nomes de função JS fixam-se no plano): `url` (ou `imageUrl` em
  `img|source src`), `srcset`/`imageSrcset`, `ping`, e `rel` para o
  `noopener`. Um `null` remove o atributo (`removeAttribute`), como o "smart
  attribute" do JTE omite o atributo no servidor. `xlink:href` é emitido com
  `setAttributeNS` (escreve-se desde a mudança de gramática da Q4).
- **O cliente é um porte fiel do `SukoSafe.url`, não outro algoritmo.** O
  texto anterior (`new URL(v, document.baseURI)` + verificação de
  `protocol`) classificava de outra forma. Exemplos: `ｊａｖａｓｃｒｉｐｔ:x`
  (largura total) é bloqueado pelo `SukoSafe`, mas o `new URL` lê-o como
  caminho relativo e deixa-o passar; `//host` e `/\host` são bloqueados por
  omissão no servidor e passariam no cliente. O porte reproduz o
  `SukoSafe.java.template` passo a passo:
  1. **Normalização:** tirar nas pontas as unidades `<= U+0020` e tirar
     TAB/LF/CR em qualquer posição. Atenção: **nunca**
     `String.prototype.trim()`, que tira U+00A0/U+FEFF/U+2028 e não tira C0
     como U+0001. Onde o Java usa `String.trim()` (`ping`, descritores de
     `srcset`, `rel`, `cssValue`), o porte usa a mesma regra `<= U+0020`.
  2. **Relativo ao esquema:** com `allowSchemeRelative = false` (omissão), um
     valor normalizado que comece por dois caracteres do conjunto `/`/`\`
     (`//`, `/\`, `\/`, `\\`) dá `BLOCKED`.
  3. **Esquema:** um `:` antes do primeiro `/`, `?` ou `#` define o esquema.
     Passa a minúsculas com `toLowerCase()` (nunca `toLocaleLowerCase`;
     equivale ao `toLowerCase(Locale.ROOT)`) e tem de estar em `urlSchemes`.
     Para `imageUrl`, aceita-se também `data:image/<tipo>` dos
     `imageDataTypes`. Fora disso, `BLOCKED`.
  4. **Resultado:** permitido devolve o valor **original** sem alterações;
     bloqueado devolve a mesma constante,
     `BLOCKED = "about:invalid#suko-blocked"`. Nunca decodifica entidades nem
     percent-encoding, e não há `new URL`, `URL.canParse` nem resolução contra
     `baseURI`.
  5. **`srcset`:** o mesmo parse WHATWG de candidatos (vírgulas, parênteses
     sem aninhamento) e a mesma regra de descritor
     `[+-]?[0-9]*\.?[0-9]+(e[+-]?[0-9]+)?[wxh]`, sem distinguir maiúsculas. Em
     JS é a flag `i` sem `u`, que, como o `CASE_INSENSITIVE` do Java, só
     iguala ASCII. Um candidato com descritor inválido ou URL bloqueado cai
     inteiro. **`ping`:** cada URL é validado e os bloqueados caem. **`rel`:**
     garante o token `noopener`.
  6. **Índices:** Java e JS indexam ambos em unidades UTF-16, e o porte usa
     os mesmos índices. O `split` do Java descarta vazios no fim e o do JS
     não: o porte filtra-os. Só entram no porte valores `String`, `null` e
     números inteiros. A conversão de `double` difere (`"1.0"` vs `"1"`) e
     não chega a atributos de URL no subconjunto do cliente.
  As constantes `SCHEMES`, `IMAGE_DATA_TYPES` e `ALLOW_SCHEME_RELATIVE` são
  gravadas no `suko.js` no build, a partir do mesmo `SecurityOptions` que
  preenche o `SukoSafe.java.template` (`SukoSafeSource`). Uma só fonte de
  configuração (`security.*`) serve as duas metades.
- **O primeiro render no servidor não precisa de nada novo.** É o `SukoSafe`
  gerado pelo 14 (por projeto, só JDK, em `generatedPackage`): o emissor JTE
  já embrulha todo o atributo de URL dinâmico em `SukoSafe.url`/`srcset`/
  `imageUrl`/`ping`/`rel` em **todos** os componentes, reativos incluídos.
  Não há "classe auxiliar" própria da 13b para URLs.
- **Paridade mais estrita do que a M1 do 14.** A spec do 14 compara só
  permitido/bloqueado, "porque o cliente resolve contra `baseURI`". Com o
  porte o cliente já não resolve, por isso a 13b exige **a mesma string**:
  `SukoSafe.url(v)` em Java e `url(v)` em JS dão o mesmo resultado para todos
  os vetores, e no browser o atributo depois da montagem/atualização é igual
  ao do primeiro render do servidor. Testes em "Testes".
- `component` simples: **coberto desde o 14** (M1, `SukoSafe`). A antiga
  lacuna do subprojeto 2 está fechada.
- **`rel`/`noopener`:** a mesma regra da M3 do 14, não só para `_blank`. Em
  `<a>`/`<area>`/`<form>` com `target` que não seja `_self`/`_parent`/`_top`
  (ou `target` dinâmico), o `rel` ganha `noopener`: em compile-time se for
  literal, pelo porte de `SukoSafe.rel` se for dinâmico. `rel="opener"`
  literal é o único opt-out.
- **Saídas explícitas do 14 (`trustedUrl`/`trustedStyle`) — Q6 = A (assumida
  pela recomendação, o utilizador pode revê-la):** são **proibidas** nos
  blocos de `state` (erro `RESERVED_NAME`, o código que o 14 já usa para
  estes nomes) e válidas **só no render do servidor**. Assim o cliente nunca
  salta o porte do `SukoSafe`, e `security-audit.json` continua **completo**
  (todos os usos de `trusted*` estão no servidor, onde o 14 já os regista).
  Um URL de confiança que muda com o estado tem de vir de uma lista fechada no
  componente.

### Montagem e "island injection" (H3)

Ameaça: HTML de utilizador renderizado na mesma página (Markdown com HTML,
sanitizadores que mantêm `data-*`, como o DOMPurify por omissão) pode plantar
`<div data-suko="ui.Counter" data-suko-props='{...}'>` e fazer um componente
confiável montar com props escolhidas por quem ataca ("script gadget").
**Decidido pelo utilizador a 2026-10-04: opção A (token de montagem).**

- Cada ponto de entrada de ilha recebe no servidor um **token de montagem**
  aleatório (`SecureRandom`, ≥128 bits, gerado no render do `.jte`, só JDK) em
  `data-suko-mount`. O `.jte` chama um método estático do Java gerado no
  projeto (no `SukoSafe` ou numa classe irmã do mesmo `emitProject`; escolhe-se
  no plano), e o JTE escapa o resultado como atributo. O mesmo token vai num atributo do `<script type="module">`
  que o template emite junto à ilha. O módulo só monta elementos cujo token
  aparece num `<script>` da página — HTML de utilizador sanitizado não pode
  conter `<script>`, por isso não consegue forjar o par. Sem custo de cache (o
  URL do módulo não muda).
- Slots (`<template>`) e âncoras são localizados por travessia a partir da raiz
  da ilha, nunca por `id`, `getElementById` ou propriedades nomeadas de
  `window`/`document` (DOM clobbering).
- Documentado: HTML de utilizador renderizado na mesma página deve ter
  `data-suko*` e `<template>` removidos pelo sanitizador da aplicação.

### Props: serialização e validação (M1, M2)

- `data-suko-props` é produzido por um **serializador gerado** (Java,
  `JAVA_SOURCE` em `generatedPackage` emitido por `emitProject`, como o
  `SukoSafe`; só JDK) que: serializa só componentes de record declarados (nunca getters
  nem reflexão de campos); escapa em strings `"`, `\`, todos os C0
  (`\u0000`–`\u001F`), `<`, `>`, `&`, U+2028, U+2029 e surrogates isolados
  como `\uXXXX`; recusa `NaN`/`Infinity` (exceção no render); é emitido sempre
  via `${...}` (escape de atributo do JTE), nunca `$unsafe{}`.
- Na montagem, o módulo **valida** `data-suko-props` contra os tipos Java
  declarados (código gerado por ilha: `Number.isInteger` para `int`, `typeof`
  para `String`/`boolean`, arrays, forma dos records, valores de enum); uma
  falha de validação deixa a ilha por montar e escreve `console.error`.
  `Map<String, …>` é materializado como `new Map(Object.entries(...))`; o
  `suko.js` e o código gerado nunca usam `Object.assign` nem merge profundo
  sobre dados de props; `get(i)` só com índice validado como inteiro.

### Tradução Java → JS

- Literais de string JS escapados (`\`, aspas, terminadores de linha).
- Identificadores Java que são palavras reservadas ou globais em JS
  (`arguments`, `eval`, `await`, `yield`, `let`, `delete`, `typeof`,
  `undefined`, `NaN`, `window`, `document`) são renomeados.
- Acessores de record chamados `constructor`, `__proto__` ou `toString` nunca
  resolvem para membros do protótipo.

## Diagnósticos novos

| Código | Severidade | Quando |
|---|---|---|
| `REACTIVE_REQUIRED` | ERROR | `state`/`derived`/`on:`/`bind:` fora de um `reactive component` |
| `REACTIVE_UNUSED` | HINT | `reactive component` sem nenhum deles (exige `Severity.HINT`, ver API v2) |
| `CLIENT_UNSUPPORTED` | ERROR | expressão ou construção fora do subconjunto do cliente |
| `PROP_NOT_SERIALIZABLE` | ERROR | parâmetro de ilha com tipo não serializável |
| `PROP_SENSITIVE` | WARNING | parâmetro de ilha com nome sensível |
| `PROP_MAP_KEYS` (nome provisório) | INFO | `Map<String, …>` serializado em `data-suko-props`: as chaves ficam visíveis no HTML (a regra já estava na secção da fronteira, mas faltava na tabela) |
| `LAMBDA_ACROSS_BOUNDARY` | ERROR | lambda passada de `component` para `reactive component` |
| `BIND_TARGET` | ERROR | `bind:` com nome fora de `value`/`checked`, no elemento errado, ou sobre algo que não é `state` simples |
| `CLIENT_UNSAFE_SINK` | ERROR | elemento ou atributo perigoso num componente reativo que o `UNSAFE_SINK` do 14 não cobre; lista em "Operações DOM e sinks", unida à M2 da spec do 14, com um só diagnóstico por sítio. `style` só na forma `propriedade: ${v}` é aceite (Q5) |
| `TYPE_NOT_INFERRED` | ERROR | `var` cujo tipo não é inferível |
| `DERIVED_CYCLE` | ERROR | ciclo no grafo de `derived` |
| `REACTIVE_UNSUPPORTED` | ERROR | alvo sem suporte a reatividade |

Códigos que já existem e que a 13b passa a usar: `UNSAFE_SINK` (14; `on*`
dinâmico em qualquer componente, e agora também `hx-on:*`/`x-on:*` dinâmicos,
que passam a escrever-se pela Q4), `INVALID_DOCTYPE` (14; `reactive component`
que começa por doctype, ver "Regras") e `RESERVED_NAME` (14; `trustedUrl`/
`trustedStyle` dentro de um bloco de `state`, Q6).
`On:click`/`ON:click`/`Bind:value` são erro de sintaxe e não acumulam outro
diagnóstico. Não há códigos novos por causa das decisões de 2026-10-11.

## Testes

- **Golden** de `.jte` + `.js` para: contador, toggle, lista com add/remove,
  formulário com `bind:`, pai/filho com `onChange`, slot `Component` dentro de
  uma ilha.
- **Golden de paridade:** projetos sem componentes reativos geram o mesmo que
  antes e nenhum `.js`.
- **Browser (Playwright):** pelo menos um teste por exemplo: clicar,
  escrever, verificar o DOM. **Correção:** o Playwright **não** existe no
  repositório. O site não o usa (só o `npx tailwindcss` no build), e a ronda 1
  do pentest usou `playwright-core` + Chromium headless num script Node fora
  do repositório (`docs/pentest-loja-lab.md`). A 13b traz o harness para o
  repositório, com versões fixadas como as restantes dependências.
- **Tradução Java → JS:** casos de divisão inteira, `equals`, `null`,
  concatenação, executados em Java e em JS (Node) com resultados comparados.
- **Conformidade do porte do `SukoSafe` (teste diferencial).** Usa vetores
  partilhados de uma só fonte: as linhas de
  `suko-core/src/test/resources/security/xss-corpus.txt` mais os valores do
  `SukoSafeTest`, extraídos para um recurso partilhado (ex.
  `suko-jte/src/test/resources/security/url-vectors.txt`). Acrescentam-se os
  casos do porte: largura total, `İ`/`K` (U+212A), U+00A0/U+FEFF/U+2028 nas
  pontas, `//`, `/\`, `\/`, `\\`, descritores de `srcset` inválidos e
  surrogates isolados; para `cssValue`: números com unidade, `%`, `#hex`,
  identificadores, `;`, `url(`, `expression(`, `\`, espaços internos,
  U+00A0 nas pontas, dígitos e letras de largura total, `null`. Cada função (`url`, `imageUrl`, `srcset`,
  `imageSrcset`, `ping`, `rel` e `cssValue`, Q5) corre com
  vários conjuntos de opções (omissão, `allowSchemeRelative = true`,
  `imageDataTypes = [png]`, `urlSchemes` personalizados). O esperado é
  calculado em cada corrida pelo `SukoSafe` Java compilado (o
  `CompiledSukoSafe` dos testes do `suko-jte`), nunca escrito à mão. O mesmo
  lote, em JSON com `\uXXXX` para preservar surrogates isolados, é avaliado
  pelo `suko.js` gerado com essas opções, e as strings têm de ser **iguais**.
  **Motor: Node via `ProcessBuilder`** (`node --input-type=module`, lote
  único por conjunto de opções). Razões: o Node já é exigido pelo build
  (`suko-website` → `npx` no `check`); esta spec já o usa nos testes de
  tradução; o V8 é o motor do Chromium que o Playwright e o pentest usam; e
  não acrescenta dependências à JVM. O GraalJS traria várias dezenas de MB de
  polyglot para o classpath de teste e outro motor (regex/Unicode), que
  podia esconder ou criar divergências face ao browser. Sem `node` no PATH, o
  teste **falha**, não é saltado: o `NativeImageSmokeTest` saltado em silêncio
  é a lacuna (i) do item 14. Um teste Playwright corre ainda uma amostra dos
  mesmos vetores no Chromium real.
- **Segurança:**
  - varrimento por allowlist das operações DOM no JS gerado;
  - um teste por elemento/atributo de `CLIENT_UNSAFE_SINK`, e o negativo (o
    mesmo, estático, compila);
  - URLs: `" javascript:"`, `"JAVASCRIPT:"`, `"java\tscript:"`,
    `"\u0001javascript:"`, `"javascript&#58;"` (fica inerte),
    `"ｊａｖａｓｃｒｉｐｔ:"` (bloqueado), `data:text/html`, `vbscript:`,
    relativos e relativos ao esquema (`//host`, `/\host`, `\/host`,
    `\\host`: bloqueados por omissão, passam com `allowSchemeRelative`), e
    `srcset` com descritor inválido (`java<TAB>script:x 1x` cai). Todos no
    cliente e no primeiro render do servidor, com a **mesma string** (ver
    "Conformidade do porte");
  - `on:click=${...}` não gera `UNSAFE_SINK`; `onclick=${...}` gera, também
    dentro de um componente reativo; `On:click` é erro de sintaxe; o `.jte`
    emitido não contém `on:`/`bind:` (os goldens das ilhas compilam com a
    `OwaspHtmlPolicy` **e** com a `PreventInlineEventHandlers`);
  - **ramos `:` do `HtmlSecurityChecker` (Q4, parte da 13b):** testes de
    gramática (`on:click`, `bind:value`, `xlink:href`, `hx-on:click`,
    `x-on:click` fazem parse; `on : click`, `on:`, `:click`, `a:b:c`,
    `on:${x}` e `@click` dão erro de sintaxe) e testes do checker para cada
    ramo que antes era código morto: `hx-on:click=${x}` e `x-on:click=${x}`
    dão `UNSAFE_SINK` (e o valor literal passa), `xlink:href=${u}` passa por
    `SukoSafe.url` (e `javascript:` fica bloqueado), `on:click` e `bind:value`
    **não** dão `UNSAFE_SINK`, `online:x=${v}` (começa por `on`) dá
    `UNSAFE_SINK`; o mesmo conjunto fora de um componente reativo dá só
    `REACTIVE_REQUIRED` para `on:`/`bind:` e as regras do 14 para os outros;
  - **`style` reativo (Q5):** `style="width: ${pct}%"` aceite no render do
    servidor e num bloco de `state` (o valor passa por `cssValue`, no
    servidor e no cliente, com a mesma string); `style="${v}"`,
    `style="a: 1; ${v}"`, nome de propriedade dinâmico e `srcdoc=${v}`
    continuam erro; payloads `expression(...)`, `url(...)`, `;`, `\` e
    valores vindos de `bind:value` dão `unset`; um teste Playwright com a CSP
    da loja (`style-src 'self'`) confirma que o estilo se aplica pelo CSSOM e
    não gera violação;
  - **`trusted*` (Q6):** `trustedUrl(...)`/`trustedStyle(...)` dentro de um
    bloco de `state` dão `RESERVED_NAME`; no render do servidor do mesmo
    componente continuam válidos e aparecem em `security-audit.json`;
  - **ilha a partir de Java (Q3):** a API Java gerada para um componente
    reativo emite token, `data-suko-props` e `<script>` (teste da 13b sobre o
    contrato; o teste de ponta a ponta fica com o item 12);
  - **pasta de saída (Q1):** testes dos plugins (Gradle TestKit, Maven com
    `mvn` real) de que a pasta de build por omissão é gerada, registada como
    recursos e incluída no jar (`static/suko/...`), de que `js.outputDir`
    a substitui e de que os módulos obsoletos são limpos;
  - `bind:innerHTML`, `bind:textContent` e `bind:value` num `<div>` falham;
  - props: barra invertida, C0, U+2028, surrogate isolado, `<!--`, `</script>`,
    aspas, `&`, `NaN`, records/listas/maps aninhados — ida e volta igual via
    `JSON.parse`; props com tipos errados não montam;
  - ilha forjada (token errado ou ausente) não é montada;
  - os testes Playwright correm com CSP `script-src 'self'; object-src 'none';
    base-uri 'none'; require-trusted-types-for 'script'; trusted-types 'none'`;
    qualquer violação CSP/Trusted Types falha o teste;
  - tradução: palavras reservadas JS, acessores `constructor`/`__proto__`.
- **Um teste por código de erro.**

## Critérios de conclusão

- Os exemplos acima compilam e funcionam no browser (Playwright verde).
- Projeto sem componentes reativos: output idêntico ao anterior, sem `.js`.
- Um teste por diagnóstico novo; suite existente verde sem alterar asserções.
- Revisões do `security-specialist` (spec e implementação) sem achados abertos
  de severidade média ou superior.
- Teste diferencial do porte do `SukoSafe` verde (Java = JS, string a string,
  em todos os conjuntos de opções).
- **A loja ganha ilhas** (decidido na spec do 14, secção "Aplicação de
  exemplo", e registado em `ARCHITECTURE.md`, item 14: "No fim da 13b: a loja
  ganha ilhas JS e é o alvo do pentest"): carrinho reativo, quantidade +/−,
  filtro de pesquisa ao vivo e contador no cabeçalho, com a CSP da loja
  (`script-src 'self'`) sem alterações. Mais o conjunto `lab` de ilhas (ver
  "Pentest das ilhas").
- Os plugins Gradle e Maven geram o JS na pasta de build por omissão e
  registam-na como recursos (Q1); o jar da loja contém `static/suko/*.js`.
- Os ramos `:` do `HtmlSecurityChecker` têm testes e já não são código morto
  (Q4); `@click` continua erro de sintaxe.
- `style="propriedade: ${v}"` funciona no servidor e em blocos de `state`, com
  `cssValue` no vetor diferencial (Q5), e o **C1 alargado foi revisto de novo
  pelo `security-specialist`** sem achados abertos de severidade média ou
  superior.
- `trustedUrl`/`trustedStyle` em blocos de `state` dão `RESERVED_NAME` (Q6) e
  `security-audit.json` continua completo.
- A API Java gerada para componentes reativos (item 12, Q3) emite a ilha
  completa; o scoping do item 12 refere a dependência do token e do
  serializador.
- `ARCHITECTURE.md` atualizado: item 13 (13b, D7); fecho do pendente "13b
  desalinhada" do item 14; a lacuna (k) (`:` em nomes de atributo) conforme a
  Q4 (feito a 2026-10-11, a confirmar no fim); a pasta de saída do JS (Q1); e
  as decisões da API v2.

## Pentest das ilhas

A ronda 1 (`docs/pentest-loja-lab.md`) deixou as ilhas fora de âmbito porque
ainda não existiam. A ronda seguinte, depois da 13b e antes da release, usa a
**mesma abordagem**:

- **Perfil `lab` com páginas de ilhas** (ex. `/lab/islands/*`). Mesmo modelo
  das páginas do lab do 14: cada página recebe payloads por HTTP e põe-nos nos
  sítios que a 13b protege. Os testes `LabPagesTest` (MockMvc + jsoup)
  afirmam o markup, como no 14.
- **Harness da ronda 1:** Chromium headless via `playwright-core` (agora
  dentro do repositório; ver "Testes"), uma matriz páginas × payloads, leitura
  do DOM final **depois da montagem e depois de cada interação** (cliques e
  escrita nos inputs com `bind:`), e captura de diálogos, erros de página,
  navegações e pedidos para fora. Acrescenta-se a captura de violações de
  CSP/Trusted Types (`securitypolicyviolation`).

O que a ronda tem de cobrir:

1. **Trusted Types:** páginas servidas com
   `require-trusted-types-for 'script'; trusted-types 'none'` (só Chromium).
   Qualquer violação é achado, porque o JS gerado não pode usar nenhum sink de
   Trusted Types.
2. **Injeção no JSON de props:** `</script>`, `<!--`, aspas, `\`, C0, U+2028,
   surrogates isolados, chaves `__proto__`/`constructor` em `Map`/records,
   `NaN`, tipos errados (a ilha não pode montar), e props adulteradas no HTML
   sem token.
3. **Token de montagem / island injection:** HTML de utilizador (avaliações)
   com `data-suko=`, `data-suko-props=`, `data-suko-mount=` copiado de outra
   ilha da página ou adivinhado, e `<template>` plantado. Nenhuma ilha forjada
   pode montar. Inclui DOM clobbering (`id`/`name` iguais a âncoras ou a
   globais).
4. **Caminhos de `bind:`:** escrever payloads em inputs com `bind:value` que
   depois aparecem em texto, em atributos e em atributos de URL (o porte do
   `SukoSafe` tem de dar o mesmo que o servidor: `javascript:`, largura total,
   `//host`, `/\host`); e, com a Q5, `style="width: ${v}%"` alimentado por
   `bind:value` com `expression(...)`, `url(...)`, `;`, `\` e largura total
   (tudo tem de dar `unset`), com a CSP da loja (`style-src 'self'`).
5. **Slots em `<template>`:** conteúdo de slot com texto hostil, clonado pela
   ilha. Verificar que um `<script>` dentro de um slot clonado não ganha
   execução além da que já teria no render do servidor (o slot é HTML de
   confiança, mas a clonagem não pode alargar essa confiança), e que a CSP o
   bloqueia quando é inline.
6. **CSP com módulos:** a CSP da loja (`script-src 'self'`, sem
   `'unsafe-inline'`) com `<script type="module" src>` e `import` relativos;
   `basePath` noutra origem tem de falhar de forma visível.
7. **`nonce`/`'strict-dynamic'`:** com uma CSP por nonce +
   `'strict-dynamic'`, o `'self'` é ignorado e os `<script type="module">`
   inseridos pelo parser sem `nonce` são bloqueados. A v1 não suporta este
   modo (Q2): a ronda confirma o comportamento e que a documentação o diz, não
   procura uma correção.

O relatório segue o formato da ronda 1 (método, resultado, tabela de achados
F*, não coberto, reprodução). Achados de desenho voltam como decisões do
utilizador, como o F1 da ronda 1.

## Fora de âmbito (v1)

- `bind:` em componentes; `key` em `for`; `long`/`char`; chamadas a métodos
  Java arbitrários; transições/animações; SSR streaming; minificação.
- Reatividade noutros alvos (JavaFX, TamboUI): a sintaxe é a mesma, a geração é
  de cada alvo.
- Declaração de records em `.sk`: na v1 os records vêm de Java (depende do
  item 12).
- **CSP por `nonce` / `'strict-dynamic'` (Q2 = A, assumida pela recomendação;
  o utilizador pode revê-la).** A v1 só suporta `script-src 'self'`. O
  `<script type="module">` de cada ilha é emitido sem `nonce`, por isso uma
  CSP por nonce + `'strict-dynamic'` (que ignora `'self'`) bloqueia-o.
  **Consequência:** projetos com CSP baseada em nonce **não podem usar ilhas**
  até uma versão posterior (as opções ponderadas eram um nonce opcional vindo
  do layout, ou um gancho `SukoCsp.nonce()` gerado no projeto). A limitação
  vai para `docs/security.md` e para `ARCHITECTURE.md` quando a 13b for
  implementada.

## Riscos

- **Segurança** (gerar JS a partir de expressões): mitigado pela lista fechada,
  sinks proibidos, revisões e testes.
- **Divergência semântica Java/JS:** mitigada pela lista fechada e pelos testes
  de tradução lado a lado; o overflow de `int` fica documentado.
- **Divergência servidor/cliente na segurança de URLs:** o cliente é um porte
  do `SukoSafe` e não outro algoritmo, e o teste diferencial corre em cada
  build. Uma mudança no `SukoSafe.java.template` sem a mesma mudança no porte
  parte o build.
- **Tamanho do JS:** o agendador e a reconciliação vivem no `suko.js`
  partilhado; cada componente só gera o seu código.
- **Dependência do item 12** (records e tipos vindos de Java nos parâmetros) e,
  para inferência mais rica, do 11c. Com a Q3, o item 12 depende também da 13b:
  a API Java gerada de um componente reativo precisa do token e do serializador.
- **C1 alargado (Q5):** aceitar `style="propriedade: ${v}"` no cliente junta
  mais um porte ao `suko.js` e alarga o achado C1; mitigado pela allowlist do
  `cssValue`, pelo vetor diferencial e pela nova revisão do
  `security-specialist`.
- **Pasta de recursos gerada (Q1):** a ordem das tarefas (o JS tem de existir
  antes de `processResources`/`resources`) e o `js.outputDir` personalizado
  fora do classpath são modos de falha; cobertos pelos testes dos plugins.
- **`:` em nomes (Q4):** mais superfície de gramática; mitigada por tratar
  `on:`/`bind:` como únicos prefixos Suko e pelo checker do 14 para os
  restantes.
- **Limitação CSP por nonce (Q2):** quem a usa fica sem ilhas na v1;
  documentada e a rever se houver procura.

## Próximos passos

1. ~~Utilizador revê esta spec e responde às decisões em aberto~~ (feito a
   2026-10-11; Q2 e Q6 por recomendação, a rever se o utilizador quiser).
2. Revisão do `security-specialist` sobre a spec (a primeira foi a
   2026-10-04). **Obrigatória** sobre as alterações de 2026-10-11: o **C1
   alargado** (`style` reativo e porte do `cssValue`, Q5), o porte do
   `SukoSafe`, a exceção `on:`/`bind:` com `prefixo:nome` (Q4), a regra de um
   diagnóstico por sítio, `trusted*` só no servidor (Q6) e o pentest das
   ilhas.
3. O scoping do item 12 conta com a Q3 (API Java gerada depende do token e do
   serializador).
4. Plano de implementação (writing-plans) — executado **depois do item 12**.

## Decisões (utilizador, 2026-10-11)

Respostas às seis perguntas que esta spec tinha em aberto. Estão incorporadas
no texto acima (as secções indicadas); D1–D8 e as decisões de segurança de
2026-10-04 não mudam.

- **Q1 — Pasta por omissão dos `.js` gerados: B.** Uma pasta de **build**
  registada pelos plugins como pasta de recursos:
  `build/generated-resources/suko/static/suko` (Gradle) e
  `target/generated-resources/suko/static/suko` (Maven). Substitui
  `src/main/resources/static/suko`. `js.outputDir` continua como override.
  Ver "O que o alvo `jte` gera" (Configuração) e API v2 (b).
- **Q2 — `nonce` e `'strict-dynamic'`: A (assumida pela recomendação).** A v1
  só suporta `script-src 'self'`; nonce fica para uma versão seguinte, e
  projetos com CSP por nonce não podem usar ilhas até lá. Ver "Fora de
  âmbito".
- **Q3 — Componente reativo chamado a partir de Java: A.** É sempre ponto de
  entrada de ilha (token + props + `<script>`), como se o chamasse um
  `component`. A API Java gerada depende do token e do serializador, e o
  scoping do item 12 tem de a contar. Ver "Composição" (2) e API v2 (e).
- **Q4 — `:` em nomes de atributo: B.** Qualquer `prefixo:nome` com
  identificadores (`on:click`, `bind:value`, `xlink:href`, `hx-on:click`,
  `x-on:click`), tokens adjacentes, **sem `@`** (`@click` continua erro de
  sintaxe). Os ramos `:` do `HtmlSecurityChecker` passam a ser código vivo, e
  os testes deles fazem parte da 13b. O checker trata `on:`/`bind:` (13b) como
  exceção, e `hx-on:`/`x-on:` continuam `UNSAFE_SINK` quando dinâmicos. Ver
  "Regras".
- **Q5 — `style="propriedade: ${v}"` reativo: B.** Aceite no render do
  servidor e nos blocos de `state`, com porte de `SukoSafe.cssValue` no cliente
  e nos vetores diferenciais partilhados. **O achado C1 fica alargado e tem de
  ser revisto de novo pelo `security-specialist`.** Ver "CSS: `style` reativo
  (C1 alargado)" e "Testes".
- **Q6 — `trustedUrl`/`trustedStyle` em atributos atualizados no cliente: A
  (assumida pela recomendação).** Proibidos nos blocos de `state`
  (`RESERVED_NAME`), válidos só no render do servidor; `security-audit.json`
  continua completo. Ver "URLs".
