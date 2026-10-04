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
- **D4 — Two-way binding:** `bind:propriedade=${state}`.
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
   `PROP_NOT_SERIALIZABLE`. O JSON é visível no HTML: parâmetros com nome
   `password`, `token`, `secret`, `apiKey` (insensível a maiúsculas, também como
   sufixo) dão `PROP_SENSITIVE` (WARNING). Parâmetros `Component` (slots) não
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
  com CSP `script-src 'self'`.
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

Revisão obrigatória do `security-specialist` **antes do plano** (sobre esta
spec) e **no fim da implementação**.

- O JS gerado só escreve no DOM com `textContent`, `setAttribute` e
  `createElement`; **nunca** `innerHTML`, `outerHTML`, `insertAdjacentHTML`,
  `document.write`, `eval` ou `new Function` — um teste falha se aparecerem no
  output.
- Atributos de URL (`href`, `src`, `action`, `formaction`, `xlink:href`) com
  valores de `state` passam por uma verificação no `suko.js` que bloqueia
  `javascript:`, `data:` e `vbscript:`.
- Os nomes de atributos são fixos em compile-time: dados nunca escolhem um
  nome (nada de `on*` vindo de dados).
- `data-suko-props`: JSON com o escape de atributo do JTE; no cliente, só
  `JSON.parse`.
- `onclick="${...}"` em minúsculas: WARNING.
- `PROP_SENSITIVE` para parâmetros de ilha com nomes sensíveis.

## Diagnósticos novos

| Código | Severidade | Quando |
|---|---|---|
| `REACTIVE_REQUIRED` | ERROR | `state`/`derived`/`on:`/`bind:` fora de um `reactive component` |
| `REACTIVE_UNUSED` | HINT | `reactive component` sem nenhum deles |
| `CLIENT_UNSUPPORTED` | ERROR | expressão ou construção fora do subconjunto do cliente |
| `PROP_NOT_SERIALIZABLE` | ERROR | parâmetro de ilha com tipo não serializável |
| `PROP_SENSITIVE` | WARNING | parâmetro de ilha com nome sensível |
| `LAMBDA_ACROSS_BOUNDARY` | ERROR | lambda passada de `component` para `reactive component` |
| `BIND_TARGET` | ERROR | `bind:` sobre algo que não é `state` simples |
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
- **Segurança:** varrimento do JS gerado por sinks proibidos; URLs
  `javascript:` bloqueadas; JSON com `</script>`, aspas e `&` em
  `data-suko-props`.
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
