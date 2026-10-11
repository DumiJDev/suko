# Suko — Subprojeto 13b: reatividade em ilhas no alvo JTE

Data: 2026-10-04
Estado: **rascunho para revisão do utilizador.** Desenho aprovado secção a
secção na conversa de 2026-10-04.
Ordem: **spec agora, implementação depois do item 12** (interop Java),
decidido pelo utilizador a 2026-10-04. Depende do 13a (API de extensões).
**Alinhada com o subprojeto 14 a 2026-10-11** (revisão final do architect ao
14, pendentes I6/I7 em `ARCHITECTURE.md`, item 14): `on*` vs `on:`, `sanitizeUrl`
como porte do `SukoSafe`, um só `urlSchemes`, plano da API v2, pentest das
ilhas. As decisões D1–D8 e as decisões de segurança do utilizador não mudaram;
o que ficou por decidir está em "Decisões em aberto para o utilizador", no fim.

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
  1. o prefixo é exatamente `on:` ou `bind:` em minúsculas; `On:click` ou
     `ON:click` são erro de sintaxe, não HTML cru;
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
     como handlers.
- **Gramática (mudança mínima).** Hoje um nome de atributo é a regra de parser
  `htmlName : Identifier (MINUS Identifier)*` (`SukoParser.g4`, ~l. 191). O
  token `COLON` existe no lexer (`SukoLexer.g4`, ~l. 117), mas o parser não o
  aceita em nomes. Por isso `on:click`, `bind:value`, `xlink:href`,
  `x-on:click` e `hx-on:click` não fazem parse, e os ramos `:`/`@` do
  `HtmlSecurityChecker` são código morto (lacuna (k) do item 14). A 13b
  acrescenta `htmlName (COLON htmlName)?` **só a nomes de atributo** (não a
  nomes de tag). Os três tokens têm de ser adjacentes: o espaço em branco é
  `skip` no modo por omissão, e `on : click` tem de dar erro. `@` continua
  fora. Fica em aberto (Q4) se o prefixo se limita a `on`/`bind` ou se se abre
  a qualquer `prefixo:nome`.

## Composição e fronteira servidor → cliente

1. **`component` dentro de `reactive component`:** fora de blocos que dependem
   de `state`, é renderizado no servidor como hoje. **Dentro** de um bloco que
   depende de `state` só entram componentes reativos (consequência de D5); um
   simples dá erro `CLIENT_UNSUPPORTED` ("`Card` não é reativo e não pode ser
   criado no cliente").
2. **`reactive component` dentro de `component`** é o ponto de entrada de uma
   ilha: o servidor renderiza o HTML inicial e escreve os parâmetros em
   `data-suko-props` (JSON); o cliente monta a ilha a partir daí.
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
  `nonce`/`integrity` ficam para o plano (`nonce`: ver Q2).
- **Configuração:** `suko { js { outputDir = ...; basePath = "/suko" } }` no
  Gradle e o equivalente no Maven. Por omissão: `src/main/resources/static/suko`
  e `/suko` (a pasta por omissão é a Q1 de "Decisões em aberto"). O
  bloco `js` só tem a entrega dos ficheiros. **Tudo o que é política de
  segurança vem de `suko { security { ... } }`** (`urlSchemes`,
  `imageDataTypes`, `allowSchemeRelative`, `urlAttributes`), que governa o
  servidor (`SukoSafe`) e o cliente (porte em `suko.js`). Não há
  `js { urlSchemes }`.
- **Onde se escrevem os `.js`.** Hoje o `ProjectOutputWriter` (core) escreve
  `RESOURCE` na raiz dos templates (ao lado dos `.jte`) e só limpa ficheiros
  obsoletos de `JAVA_SOURCE` (manifesto próprio). O JS da 13b precisa do seu
  próprio destino (`outputDir`) e de limpeza por manifesto dos módulos com
  hash: é a decisão (b) da API v2, abaixo.
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

**Ainda por fazer: o que a 13b exige e sobe `ExtensionApi.VERSION` para 2**
(nada foi publicado):

- `Target.emit` devolve um só `Emitted`. Os `.js` por componente precisam de
  várias saídas por componente, ou então de serem emitidos por
  `emitProject` (que vê o projeto inteiro). Escolhe-se no plano.
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
  (`outputDir`, ex. `static/suko`, ou um `Kind` novo) e de limpeza por
  manifesto dos módulos com hash.
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
  dentro de `<template>`, como qualquer slot). Se um componente reativo
  chamado a partir de Java é ponto de entrada fica em aberto (Q3).

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
  literal (a forma `propriedade: ${v}` do 14 está na Q5). Literais estáticos
  continuam permitidos.
- **Um diagnóstico por sítio.** Onde o `UNSAFE_SINK` do 14 já dispara sobre o
  mesmo atributo ou elemento (ex.: (b), `srcdoc`, conteúdo dinâmico de
  `<script>`), a 13b não acrescenta `CLIENT_UNSAFE_SINK`. Este código cobre só
  o que o 14 não recusa: elementos da lista (a) criados no cliente, mesmo com
  valores literais, e as exceções do 14 que o cliente não implementa (ex.:
  `iframe src` de origem constante, aceite no servidor mas proibido no
  cliente).
- Os nomes de atributos e de eventos são fixos em compile-time (ver "Nomes são
  léxicos").

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
  `setAttributeNS` (só se escreve depois da mudança de gramática; ver Q4).
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
- As saídas explícitas do 14 (`trustedUrl`/`trustedStyle`) em atributos que o
  cliente cria ou atualiza estão em aberto (Q6).

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
| `CLIENT_UNSAFE_SINK` | ERROR | elemento ou atributo perigoso num componente reativo que o `UNSAFE_SINK` do 14 não cobre; lista em "Operações DOM e sinks", unida à M2 da spec do 14, com um só diagnóstico por sítio |
| `TYPE_NOT_INFERRED` | ERROR | `var` cujo tipo não é inferível |
| `DERIVED_CYCLE` | ERROR | ciclo no grafo de `derived` |
| `REACTIVE_UNSUPPORTED` | ERROR | alvo sem suporte a reatividade |

Códigos que já existem e que a 13b passa a usar: `UNSAFE_SINK` (14; `on*`
dinâmico em qualquer componente), `INVALID_DOCTYPE` (14; `reactive component`
que começa por doctype, ver "Regras") e `RESERVED_NAME` (14; ver Q6).
`On:click`/`ON:click` são erro de sintaxe e não acumulam outro diagnóstico.

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
  surrogates isolados. Cada função (`url`, `imageUrl`, `srcset`,
  `imageSrcset`, `ping`, `rel` e, se a Q5 o pedir, `cssValue`) corre com
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
- `ARCHITECTURE.md` atualizado: item 13 (13b, D7); fecho do pendente "13b
  desalinhada" do item 14; a lacuna (k) (`:` em nomes de atributo) conforme a
  Q4; e as decisões da API v2.

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
   `//host`, `/\host`).
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
   inseridos pelo parser sem `nonce` são bloqueados. A ronda confirma o
   comportamento e a resposta documentada (Q2).

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
  para inferência mais rica, do 11c.

## Próximos passos

1. Utilizador revê esta spec e responde às "Decisões em aberto" abaixo.
2. Revisão do `security-specialist` sobre a spec (a primeira foi a
   2026-10-04). Recomenda-se uma passagem curta sobre as alterações de
   2026-10-11: porte do `SukoSafe`, exceção `on:`/`bind:`, regra de um
   diagnóstico por sítio e pentest das ilhas.
3. Plano de implementação (writing-plans) — executado **depois do item 12**.

## Decisões em aberto para o utilizador

Escolhas de produto/desenho que nem as decisões D1–D8, nem as do 14, nem o
código resolvem. A recomendação é só isso; nada disto está decidido.

- **Q1 — Pasta por omissão dos `.js` gerados.** A spec diz
  `src/main/resources/static/suko`, uma pasta de **fontes**, enquanto o 14 põe
  o Java gerado em `build/generated-src/suko-java` (Gradle) /
  `target/generated-sources/suko-java` (Maven).
  (A) manter `src/main/resources/static/suko`; (B) uma pasta de build
  (`build/generated-resources/suko/static/suko` /
  `target/generated-resources/suko/static/suko`) registada pelos plugins como
  pasta de recursos; (C) não ter omissão e exigir `js.outputDir`.
  **Recomendação: B.** Trade-off: coerente com o 14 e sem módulos obsoletos
  no controlo de versões, à custa de os plugins registarem mais uma pasta de
  recursos.
- **Q2 — `nonce` e `'strict-dynamic'` na v1.** Com uma CSP por nonce +
  `'strict-dynamic'`, os `<script type="module">` emitidos sem `nonce` são
  bloqueados. (A) v1 só com `script-src 'self'`, documentado, e nonce numa
  versão seguinte; (B) o `.jte` da ilha aceita um nonce opcional (ex. um
  parâmetro implícito vindo do layout) e emite-o; (C) um gancho gerado no
  projeto (`SukoCsp.nonce()`) que a aplicação implementa (ex. por pedido).
  **Recomendação: A.** Trade-off: a CSP da loja e a de `docs/security.md` já
  são `'self'` e nada muda na assinatura dos componentes, mas quem usa CSP por
  nonce fica sem ilhas até à versão seguinte.
- **Q3 — Componente reativo chamado a partir de Java (item 12, direção a).**
  (A) é sempre ponto de entrada de ilha (token + props + `<script>`), como se
  fosse chamado por um `component`; (B) na v1 os componentes reativos só se
  chamam a partir de `.sk` (a API Java gerada não os expõe); (C) a API Java
  expõe-nos, mas só para render estático (sem montagem).
  **Recomendação: A.** Trade-off: é a leitura simétrica do item 12 ("o
  compilador trata disso"), mas a API Java gerada passa a depender do token e
  do serializador, e o scoping do item 12 tem de contar com isso.
- **Q4 — `:` em nomes de atributo.** (A) só os prefixos `on:` e `bind:`;
  qualquer outro `x:y` continua erro de sintaxe; (B) qualquer `prefixo:nome`
  (`xlink:href`, `x-on:click`, `hx-on:click` passam a escrever-se, e os ramos
  `:` do checker do 14 deixam de ser código morto), sem `@`; (C) B mais `@`
  (`@click` do Alpine).
  **Recomendação: B.** Trade-off: fecha a lacuna (k) do 14 e torna escrevível
  o `xlink:href` que esta spec já trata, à custa de mais superfície e de
  testes para os ramos `:` do checker.
- **Q5 — `style="propriedade: ${v}"` em componentes reativos.** O 14 aceita
  esta forma em todos os componentes (`SukoSafe.cssValue`). O texto desta
  spec (C1) proíbe `style` não literal em todo o componente reativo.
  (A) manter a proibição (servidor e cliente); (B) aceitar no render do
  servidor e nos blocos de `state`, com porte do `cssValue` nos vetores
  partilhados; (C) aceitar só no render do servidor.
  **Recomendação: B.** Trade-off: cobre o caso típico (`width: ${pct}%` com
  estado) com a allowlist que o 14 já fixou, mas alarga o achado C1, que o
  `security-specialist` tem de rever, e junta mais uma função ao porte.
- **Q6 — `trustedUrl`/`trustedStyle` em atributos atualizados no cliente.**
  (A) proibidos nos blocos de `state` (`RESERVED_NAME`), válidos só no render
  do servidor; (B) permitidos, com o cliente a saltar o porte e o uso
  registado em `security-audit.json`; (C) permitidos só no render inicial,
  com as atualizações sempre verificadas.
  **Recomendação: A.** Trade-off: falha fechada, e a auditoria do 14 continua
  completa, mas um URL de confiança que muda com o estado tem de vir de uma
  lista fechada no componente.
