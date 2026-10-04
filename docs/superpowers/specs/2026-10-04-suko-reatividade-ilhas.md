# Suko — Subprojeto 13b: reatividade em ilhas no alvo JTE

Data: 2026-10-04
Estado: **rascunho para revisão do utilizador.** Desenho aprovado secção a
secção na conversa de 2026-10-04.
Ordem: **spec agora, implementação depois do item 12** (interop Java),
decidido pelo utilizador a 2026-10-04. Depende do 13a (API de extensões).

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
- **`onclick="${...}"`** (atributo HTML em minúsculas, sem `:`) continua HTML
  cru; com `${...}` lá dentro dá WARNING (contexto JS, risco de XSS).

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
  - **`suko.js`, gerado uma vez por projeto** (~1 KB): montagem, agendador que
    junta mudanças num microtask, reconciliação de listas por índice (por `key`
    numa versão seguinte), a verificação de URLs. Gerado, não um jar —
    "nenhuma dependência do Suko em runtime" mantém-se.
  - JS legível, não minificado.
- **Entrega na página:** o `.jte` de cada componente reativo emite
  `<script type="module" src="${base}/ui/Counter.js"></script>` junto ao
  elemento; o browser avalia cada módulo uma vez. Sem scripts inline: funciona
  com CSP `script-src 'self'` **quando os ficheiros são servidos da mesma
  origem**. O `basePath` é validado no build (`^/[A-Za-z0-9._~/-]*$` ou um URL
  `https://` sem `"'<>` nem espaços); outro valor é erro de configuração.
  Nomes de ficheiro com hash do conteúdo (um `suko.js` antigo não se mistura com
  módulos novos) e ganchos opcionais para `nonce`/`integrity` ficam para o
  plano.
- **Configuração:** `suko { js { outputDir = ...; basePath = "/suko" } }` no
  Gradle e o equivalente no Maven. Por omissão: `src/main/resources/static/suko`
  e `/suko`.
- **Alvos sem reatividade** (ex.: o `html` estático do site, TamboUI enquanto
  não a tiver) declaram-no; um componente reativo nesses alvos dá
  `REACTIVE_UNSUPPORTED`.

## Mudanças na API de extensões (13a → `ExtensionApi.VERSION = 2`)

Já previstas na revisão final do 13a (API v1 provisória):

- `Target.emit` pode devolver **vários ficheiros por componente** (`.jte` +
  `.js`).
- **Emissão ao nível do projeto** (para o `suko.js`).
- **Capacidades do alvo** (`reactive`), lidas pelo verificador.
- O AST ganha `state`/`derived` (novos subtipos selados) e o modificador
  `reactive` — mudança incompatível, daí a subida de versão (nada foi
  publicado).

## Segurança

Revisão obrigatória do `security-specialist` **antes do plano** (feita a
2026-10-04 sobre esta spec; achados C1, H1–H4, M1–M4 incorporados abaixo) e
**no fim da implementação**.

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
- **Erro `CLIENT_UNSAFE_SINK`** em blocos que dependem de `state` **e** no
  render do servidor de um `reactive component`: (a) elementos `script`,
  `style`, `iframe`, `frame`, `object`, `embed`, `base`, `meta`, `link`,
  `template` (só o servidor emite), `svg:script`, `svg:animate`, `svg:set`,
  `svg:animateMotion`, `svg:animateTransform`, `foreignObject`; (b) atributos
  `on*` (insensível a maiúsculas) com valor não literal; (c) `srcdoc` e `style`
  com valor não literal. Literais estáticos continuam permitidos.
- Os nomes de atributos e de eventos são fixos em compile-time (ver "Nomes são
  léxicos").
- `onclick="${...}"` em minúsculas fora de componentes reativos: WARNING.

### URLs (H1, H2)

- Atributos de URL — `href` (qualquer elemento, incl. SVG/MathML),
  `xlink:href`, `src`, `action`, `formaction`, `data` (em `object`), `poster`,
  `cite`, `background`, `ping` — com **qualquer valor não literal** (`state`,
  `derived`, parâmetros, variáveis de `for`, concatenações) passam por
  `sanitizeUrl` no `suko.js`: `new URL(v, document.baseURI)` e **allowlist** de
  protocolo `http:`, `https:`, `mailto:`, `tel:` (configurável em
  `suko { js { urlSchemes = [...] } }`); qualquer outro valor, ou parse falhado,
  é substituído por `about:invalid#suko-blocked`. Nunca uma denylist por
  prefixo, nunca decodificação de entidades. `xlink:href` é emitido com
  `setAttributeNS`.
- **O primeiro render no servidor** de um `reactive component` aplica a mesma
  allowlist ao mesmo conjunto de atributos, em Java gerado (classe auxiliar no
  output do projeto, sem dependência de runtime do Suko), com o mesmo resultado
  `about:invalid#suko-blocked`. Teste de paridade: o mesmo URL hostil produz o
  mesmo atributo no servidor e depois da montagem.
- Fica registado (não resolvido aqui): `component` simples continua sem
  verificação de URLs — é a lacuna conhecida do subprojeto 2.
- `<a target="_blank">` com `href` dinâmico é emitido com `rel="noopener"`.

### Montagem e "island injection" (H3)

Ameaça: HTML de utilizador renderizado na mesma página (Markdown com HTML,
sanitizadores que mantêm `data-*`, como o DOMPurify por omissão) pode plantar
`<div data-suko="ui.Counter" data-suko-props='{...}'>` e fazer um componente
confiável montar com props escolhidas por quem ataca ("script gadget").
**Decisão em aberto para o utilizador** — proposta:

- Cada ponto de entrada de ilha recebe no servidor um **token de montagem**
  aleatório (`SecureRandom`, ≥128 bits, gerado no `.jte`, só JDK) em
  `data-suko-mount`. O mesmo token vai num atributo do `<script type="module">`
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

- `data-suko-props` é produzido por um **serializador gerado** (Java, no output
  do projeto) que: serializa só componentes de record declarados (nunca getters
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
| `REACTIVE_UNUSED` | HINT | `reactive component` sem nenhum deles |
| `CLIENT_UNSUPPORTED` | ERROR | expressão ou construção fora do subconjunto do cliente |
| `PROP_NOT_SERIALIZABLE` | ERROR | parâmetro de ilha com tipo não serializável |
| `PROP_SENSITIVE` | WARNING | parâmetro de ilha com nome sensível |
| `LAMBDA_ACROSS_BOUNDARY` | ERROR | lambda passada de `component` para `reactive component` |
| `BIND_TARGET` | ERROR | `bind:` com nome fora de `value`/`checked`, no elemento errado, ou sobre algo que não é `state` simples |
| `CLIENT_UNSAFE_SINK` | ERROR | elemento ou atributo perigoso com valor dinâmico num componente reativo |
| `TYPE_NOT_INFERRED` | ERROR | `var` cujo tipo não é inferível |
| `DERIVED_CYCLE` | ERROR | ciclo no grafo de `derived` |
| `REACTIVE_UNSUPPORTED` | ERROR | alvo sem suporte a reatividade |

## Testes

- **Golden** de `.jte` + `.js` para: contador, toggle, lista com add/remove,
  formulário com `bind:`, pai/filho com `onChange`, slot `Component` dentro de
  uma ilha.
- **Golden de paridade:** projetos sem componentes reativos geram o mesmo que
  antes e nenhum `.js`.
- **Browser (Playwright, já usado no site):** pelo menos um teste por exemplo —
  clicar, escrever, verificar o DOM.
- **Tradução Java → JS:** casos de divisão inteira, `equals`, `null`,
  concatenação, executados em Java e em JS (Node) com resultados comparados.
- **Segurança:**
  - varrimento por allowlist das operações DOM no JS gerado;
  - um teste por elemento/atributo de `CLIENT_UNSAFE_SINK`, e o negativo (o
    mesmo, estático, compila);
  - URLs: `" javascript:"`, `"JAVASCRIPT:"`, `"java\tscript:"`,
    `"\u0001javascript:"`, `"javascript&#58;"` (fica inerte), `data:text/html`,
    `vbscript:`, relativos e protocol-relative — no cliente e no primeiro render
    do servidor (paridade);
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
- `ARCHITECTURE.md` atualizado (item 13: 13b, D7).

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
- **Tamanho do JS:** o agendador e a reconciliação vivem no `suko.js`
  partilhado; cada componente só gera o seu código.
- **Dependência do item 12** (records e tipos vindos de Java nos parâmetros) e,
  para inferência mais rica, do 11c.

## Próximos passos

1. Utilizador revê esta spec.
2. Revisão do `security-specialist` sobre a spec.
3. Plano de implementação (writing-plans) — executado **depois do item 12**.
