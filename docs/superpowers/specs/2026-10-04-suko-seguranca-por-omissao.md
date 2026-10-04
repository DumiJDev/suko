# Suko — Subprojeto 14: segurança por omissão

Data: 2026-10-04
Estado: **rascunho para revisão do utilizador.** Âmbito decidido pelo
utilizador a 2026-10-04: "aplica todas as medidas de segurança". Revisto no
mesmo dia com a revisão do `security-specialist` (C1–C2, H1–H3, M-1–M-11,
L-1–L-6 incorporados). Bloqueia a primeira release (junta-se ao 11b, 11c, 13a e
item 12).

## Contexto

O JTE (`ContentType.Html`) **escapa caracteres no render conforme o contexto**
e tem uma verificação mínima de `javascript:` só em `<a href>` (minúsculas; só
ignora espaço inicial — `java\tscript:` e `\u0001javascript:` passam).
Limitações verificadas no `jte-runtime` 3.1.12 (bytecode):

- `Escape.javaScriptAttribute` não codifica `&`: `&#39;` fecha a string JS
  depois de o browser decodificar as entidades do atributo.
- A deteção de `on*` é sensível a maiúsculas (`ONCLICK` recebe escape de
  atributo HTML, igualmente quebrável).
- Dentro de `<svg><script>` as entidades são decodificadas, por isso
  `javaScriptBlock` também é quebrável.
- O `OwaspHtmlPolicy` (desligado por omissão) junta
  `PreventUppercaseTagsAndAttributes`, `PreventOutputInTagsAndAttributes`,
  `PreventUnquotedAttributes` e `PreventInvalidAttributeNames` — só validam
  nomes e aspas, nunca valores. `PreventInlineEventHandlers` não faz parte dele.

Por isso `on*` e `<script>` com valor não literal são ERROR mesmo quando o valor
está dentro de uma string JS. Tudo o que depende do **significado** de um valor
fica fora do alcance do JTE; o Suko conhece o AST em compile-time e pode fechar
essas lacunas sem dependência de runtime. Princípio (decisão do utilizador):
**delegar ao JTE o que ele já faz; o Suko só acrescenta o que o JTE não pode
ver.**

Lacunas atuais (ver `ARCHITECTURE.md`): nada valida protocolos de URL (a
lacuna do subprojeto 2); o `SemanticChecker` não tem regras de HTML; os
componentes do repositório já emitem atributos Alpine (`x-data`, `x-show`) que
avaliam código; o índice do registry não é assinado (os hashes por ficheiro
vivem em `components/*.json`, também não assinados).

As mesmas regras servem a 13b (reatividade): allowlist de URLs e regras de
sinks partilhadas entre o servidor (aqui) e o cliente (13b).

## Definições

- **Literal** = `StringLiteralExpr` sem partes de interpolação (ou atributo
  booleano sem valor). Qualquer interpolação, concatenação, variável,
  parâmetro (incluindo parâmetros `Component`/slots e chamadas de componente) é
  **não literal**. As verificações correm sobre o AST (o emissor embrulha até
  os literais em `${"..."}`, por isso nunca sobre o `.jte`).
- Nomes de elemento e atributo são comparados **insensíveis a maiúsculas**, por
  **nome local**, **independentemente do namespace** (HTML, SVG, MathML).
- O valor **inteiro** de um atributo é verificado (`"java${x}"` é tratado como
  um todo, nunca por partes).

## Medidas

### M1 — Protocolos de URL em todos os componentes

- **Atributos de URL:** `href` (qualquer elemento, incl. SVG/MathML),
  `xlink:href`, `src`, `action`, `formaction`, `data` (em `object`), `poster`,
  `cite`, `background`, `ping` (lista separada por espaços, cada URL
  validado), `srcset` e `imagesrcset` (cada candidato), `manifest`,
  `longdesc`, `usemap`, `codebase`, `itemtype`, e `hx-get|post|put|patch|delete`
  (htmx). Com valor **não literal** são emitidos através de
  `SukoSafe.url(...)` (ou `srcset(...)`).
- **`SukoSafe`** é gerada pelo alvo JTE **no output do projeto**, no pacote
  `<basePackage do projeto>.suko.SukoSafe` (por projeto, nunca partilhado entre
  artefactos), só com JDK — "nenhuma dependência do Suko em runtime" mantém-se.
- **`url(Object v)`:** `null` → `null` (o JTE omite o atributo — "smart
  attribute"); `Component`/`Content` num atributo de URL é erro de compilação;
  caso contrário `s = String.valueOf(v)`:
  1. remover U+0000–U+0020 nas pontas e U+0009/U+000A/U+000D em qualquer
     posição (como o URL parser WHATWG);
  2. se existir `:` **antes** do primeiro `/`, `?` ou `#`, o prefixo até ao `:`
     (minúsculas ASCII, `Locale.ROOT`) tem de estar na allowlist, senão é
     bloqueado — mais estrito que o browser, de propósito;
  3. sem `:` antes de `/?#` → relativo, permitido. `//host`, `/\host`, `\\host`
     e `\/host` são absolutos de rede (o browser trata `\` como `/`): permitidos
     com a allowlist por omissão porque `https:` já o é; documentados como não
     "same-origin";
  4. permitido → devolve o valor original sem alterações; bloqueado →
     `about:invalid#suko-blocked`. Nunca decodifica entidades nem
     percent-encoding.
- **`srcset`/`imagesrcset`:** `SukoSafe.srcset(v)` faz o parse de candidatos da
  WHATWG (vírgulas dentro do URL, vírgulas finais, descritores) e aplica `url`
  a cada um; um candidato bloqueado é removido.
- **Configuração:** `suko { security { urlSchemes = ["http","https","mailto","tel"] } }`
  (Gradle) e o equivalente no Maven. Configurar `javascript`, `vbscript`,
  `data`, `blob` ou `filesystem` é erro de build. Opção `imageSchemes` (vazia
  por omissão) para `img|source src/srcset`, que aceita apenas
  `data:image/(png|gif|jpeg|webp|avif)`.
- **Saída explícita:** `${trustedUrl(expr)}` dispensa a verificação e gera
  `TRUSTED_URL` (INFO) por uso.
- Literais (`href="/carrinho"`) não são tocados.
- **Paridade:** o `sanitizeUrl` do cliente (13b) implementa a mesma
  classificação; o teste compara permitido/bloqueado (não a string, porque o
  cliente resolve contra `baseURI`).

### M2 — Sinks perigosos (`UNSAFE_SINK`, ERROR, todos os componentes)

Com valor **não literal**, é erro:

- **Conteúdo** de `<script>`, `<style>` e `<noscript>` (dentro de `<noscript>` o
  browser reinterpreta texto como HTML quando o JS está desligado). Um
  parâmetro `Component` ou uma chamada de componente lá dentro também é
  `UNSAFE_SINK`.
- **`<script>`** (qualquer namespace, incl. `svg:script`): `src`, `href`,
  `xlink:href` e `type`. Exceção: `nonce` e `integrity` podem ser dinâmicos (o
  `nonce` por pedido é o padrão CSP correto).
- **Atributos `on*`**, `srcdoc` e `style` (ver exceção de `style` abaixo).
- **Atributos que determinam a semântica de outro:** `rel` em `<link>`,
  `http-equiv`/`charset`/`name` em `<meta>`, `type` em `<script>`,
  `attributeName` em `<animate>`/`<set>`/`<animateMotion>`/`<animateTransform>`.
- **`<animate>`, `<set>`, `<animateMotion>`, `<animateTransform>`:** `values`,
  `to`, `from` e `by`.
- **`<base href>`**; **`<link href>`** com `rel` que carrega recursos
  (`stylesheet`, `import`, `preload`, `modulepreload`, `icon`, `manifest`),
  com `rel` comparado por tokens (espaço ASCII, insensível a maiúsculas);
  **`<meta content>`** quando há `http-equiv`, ou com `name`/`property`
  `referrer`.
- **`<iframe src>`, `<frame src>`, `<object data>`, `<embed src>`**
  (conteúdo embebido dinâmico).
- **Elementos** (no cliente da 13b, qualquer ocorrência; no servidor, com
  conteúdo dinâmico): `frame`, `foreignObject` e os anteriores.
- **Atributos de frameworks que avaliam código:** `x-*` (Alpine), `hx-on*`
  (htmx) e, quando `htmlName` aceitar `:`/`@`, atributos que comecem por `:` ou
  `@`. A lista é extensível por configuração
  (`suko { security { codeAttributes += [...]; urlAttributes += [...] } }`) e por
  extensões 13a (um `Vocabulary` pode declarar atributos de código e de URL).

**Exceções que evitam empurrar autores para `.jte` com `$unsafe`:**

- **Origem constante:** para `src` de `iframe`/`frame`/`object`/`embed`/
  `script` e `<link href>` de carregamento, um valor com interpolações cujo
  prefixo **literal** contém esquema permitido + `//` + host + `/` completo é
  aceite (ex.: `https://www.youtube.com/embed/${id}`); cada interpolação passa
  por `SukoSafe.pathSegment(...)` (percent-encode de `/ ? # % \` e controlo),
  impedindo mudar de origem ou injetar query/fragment.
- **`style`:** aceite quando a parte literal é uma sequência de declarações
  `propriedade: ${expr}` com nomes literais (incl. custom properties
  `--pct: ${pct}%`); cada valor passa por `SukoSafe.cssValue(...)` (allowlist:
  número com unidade opcional, `%`, cor `#hex`, identificador `[a-zA-Z-]+`;
  senão `unset`). Qualquer outra forma: erro. Saída explícita
  `trustedStyle(...)` com `TRUSTED_STYLE` (INFO).

Literais estáticos continuam permitidos. **`UNSAFE_SINK` é a união desta lista
com a lista (a) de `CLIENT_UNSAFE_SINK` da 13b**; a 13b passa a referir esta
secção. `onclick="${...}"` fora de componentes reativos passa de WARNING (texto
original da 13b) a ERROR.

### M3 — Endurecimento automático de atributos

- Se existir `target` (literal diferente de `_self`/`_parent`/`_top`, ou não
  literal) em `<a>`, `<area>` ou `<form>`, o compilador junta `noopener` ao
  `rel` (literal: em compile-time; `rel` não literal: `SukoSafe.rel(expr)`
  acrescenta o token). **Não** acrescenta `noreferrer` (mudaria o Referer). Um
  `rel` literal com o token `opener` desativa (opt-out explícito).
- Lint opcional **CSP estrita** (`suko { security { strictCsp = true } }`):
  `CSP_INLINE` (WARNING) para `style="..."`, `<style>` inline, atributos `on*`
  mesmo literais, `<script>` inline, URLs literais `javascript:` e atributos
  `x-*` (o build padrão do Alpine exige `'unsafe-eval'`; sugerir o build CSP).
- **Invariante testada:** o compilador nunca emite `<script>` inline próprio,
  para que `script-src 'self'` seja possível sem nonces; `nonce="${...}"`
  continua permitido.

### M4 — Política do próprio JTE (defesa em profundidade)

- **Gradle:** quando `gg.jte.gradle` está aplicado (`pluginManager.withPlugin`),
  o plugin do Suko define `htmlPolicyClass = "gg.jte.html.OwaspHtmlPolicy"`
  (classe do `jte-runtime`, sem classe gerada) **se o utilizador não definiu
  outro valor** — nunca sobrescreve em silêncio.
- **Maven:** documentação + verificação no Mojo do Suko (WARNING se o
  `jte-maven-plugin` não tiver `htmlPolicyClass`).
- **Valor esperado:** proteção para `.jte` escritos à mão no mesmo projeto; para
  o `.jte` gerado pelo Suko é redundante (sempre com aspas, nomes estáticos).
- `PreventInlineEventHandlers` **não** é incluída por omissão (recusa `on*`
  mesmo literal, contradiz a M2); só com `strictCsp`.
- O `SemanticChecker` dá diagnóstico Suko para nomes de tag/atributo todo em
  maiúsculas, antes de o JTE os recusar.
- O `jte-specialist` confirma no plano os nomes exatos das propriedades nos
  plugins 3.1.12 e se o starter Spring/Quarkus tem propriedade equivalente.

### M5 — HTML confiável explícito e auditável; sintaxe JTE neutralizada

- Hoje o único caminho para HTML cru são os parâmetros `Component` (slots), o
  que está correto.
- **O emissor neutraliza sintaxe JTE em texto** (`TextRun`): `@` seguido de
  palavra-chave JTE (`@if`, `@for`, `@template.`, `@raw`, `@import`, `@param`,
  ...) é emitido de forma inerte (ex.: `${"@"}`; forma exata verificada pelo
  `jte-specialist`). Hoje `@if(...)` num texto passa cru e o JTE executa-o —
  relevante para componentes de terceiros vindos do registry.
- Varrimento de teste do `.jte` gerado: nunca `$unsafe`, `@raw` nem diretivas
  JTE que o emissor não produziu.
- `trustedUrl`, `trustedStyle` e `trustedHtml` são **nomes reservados**
  (declará-los é erro). Um futuro equivalente a `$unsafe{}` chamar-se-á
  `trustedHtml(...)`, com `TRUSTED_HTML` (INFO); não entra nesta versão.
- **Auditoria:** o build escreve `build/suko/security-audit.json` (Maven:
  `target/suko/...`) com cada uso de `trusted*` (ficheiro, linha, expressão),
  porque diagnósticos INFO não costumam aparecer no output.

### M6 — Cadeia de fornecimento do registry (TUF-lite)

- Cada entrada do índice ganha **`manifestSha256`** (sha256 dos bytes exatos de
  `components/<nome>.json`). A CLI verifica a assinatura do índice, depois o
  `manifestSha256` de cada manifesto, depois o `sha256` de cada ficheiro,
  **antes** de escrever em disco. A assinatura cobre os **bytes exatos** de
  `registry.json` (verificada antes do parse; nunca re-serializar).
- **Payload assinado** (dentro do JSON): `registryId` (URL base canónico),
  `ref` (tag pedida), `registryVersion`, `issuedAt`, `expires`. A CLI recusa se
  `registryId` ≠ registry configurado, se `ref` ≠ ref pedido, se `expires`
  passou (refs móveis como `main`), ou se `registryVersion`/`issuedAt` for
  inferior ao registado no `suko.lock.json` para o mesmo registry, sem
  `--allow-downgrade` explícito.
- **`registry.json.sig`** é JSON `{ "keyid", "alg": "ed25519", "sig" }` e
  admite várias assinaturas. A CLI embute um **conjunto** de chaves com `keyid`;
  cada chave está vinculada a um `registryId` (uma chave de um registry nunca
  valida outro). Chaves adicionais por configuração (`suko.json`:
  `registries[].publicKeys`).
- **Rotação:** chave nova entra num release da CLI antes de ser usada;
  revogação = release da CLI que a remove (documentado: CLIs antigas confiam até
  atualizarem). Root/targets separados e threshold ficam fora.
- **`--allow-unsigned`:** só para `FileSystemRegistrySource` e
  `http(s)://localhost|127.0.0.1`; nunca persistido em `suko.json`; o lockfile
  regista `signed`/`keyid` por registry e, se um registry já foi visto
  assinado, a falta de assinatura é erro mesmo com a flag (anti-strip).
- **Assinatura:** `suko-registry-generator --sign-key-file <ficheiro>` (ou
  variável de ambiente), nunca a chave em argv; feita na tag de release, fora do
  CI disparado por push (ou num environment protegido); o `.sig` é regenerado
  sempre que `registry.json` muda (o `RegistryGoldenTest` verifica-o com a chave
  pública). A chave privada nunca entra no repositório.
- Nota de plano: confirmar que `Signature.getInstance("Ed25519")` funciona no
  build native-image do jbang.

### M7 — Testes que provam

- **Corpus XSS** (OWASP XSS Filter Evasion Cheat Sheet, PortSwigger) renderizado
  pelo `gg.jte.TemplateEngine` real (`ContentType.Html`); o HTML é parseado por
  um parser HTML5 (jsoup) que verifica: nenhum atributo `on*` nem elemento que
  não esteja no template, nenhum URL fora da allowlist.
- **Maiúsculas e namespaces:** `HREF`, `OnClick`, `ONCLICK`, `SrcDoc`;
  `<svg><script>`, `<svg><a href>`, `<math href>`, `<animate attributeName=href>`.
- **Regressão das limitações do JTE:** `onclick` com `&#39;`, `<svg><script>`
  com entidades — prova de que a M2 os recusa.
- **URLs:** `" javascript:"`, `"JAVASCRIPT:"`, `"java\tscript:"`,
  `"\u0001javascript:"`, `\u0000javascript:`, `java\u0000script:`,
  `JaVaScRiPt:`, `javascript\n:`, `ｊａｖａｓｃｒｉｐｔ:` (fullwidth),
  `"javascript&#58;"` (inerte), `%6aavascript:`, `https:evil`, `//evil`,
  `/\evil`, `\\evil`, `"java" + "script:"` por concatenação, `data:text/html`,
  `vbscript:`, `null` (atributo omitido); `srcset` com `data:...,AAA 1x`,
  vírgulas finais, descritores com parênteses.
- **Sinks:** um teste por entrada da M2 (e o negativo estático); slots dentro de
  `<script>`/`<style>`/`<noscript>`; `x-*`/`hx-*` não literais; exceções de
  origem constante e de `style`.
- **Sintaxe JTE em texto:** `@if(true)`, `@raw`, `@template.x()` num `TextRun`
  saem inertes.
- **Browser (Playwright):** páginas servidas com CSP `default-src 'self';
  script-src 'self'; object-src 'none'; base-uri 'none';
  require-trusted-types-for 'script'; trusted-types 'none'`; qualquer violação
  reportada, a sentinela `window.__xss` ou um `dialog` falham o teste (Trusted
  Types só no Chromium — documentado).
- **Política do JTE:** o golden compila com `OwaspHtmlPolicy` ligada.
- **Registry:** manifesto adulterado com hashes coerentes (falha por
  `manifestSha256`), índice antigo assinado (rollback), `ref` errado, expirado,
  chave de outro registry, `.sig` removido num registry já visto assinado,
  `--allow-unsigned` fora de local.
- **Paridade servidor/cliente** da classificação de URLs (quando a 13b existir).
- **Kit de conformidade:** o corpus corre sobre cada alvo registado que aceite
  o vocabulário `html`.

## Onde vive cada regra (13a)

- **M2** é um `Checker` ligado ao vocabulário `html` (corre para qualquer alvo
  que o aceite).
- **M1** é obrigação de cada alvo que aceite `html`: o contrato de `Target`
  (`suko-api`) documenta-o e o kit de conformidade da M7 corre sobre cada alvo.
- O cliente da 13b não lê propriedades globais nomeadas nem `document.<nome>`
  (DOM clobbering); não há regra de compilador para `id`/`name`.

## Diagnósticos novos

| Código | Severidade | Quando |
|---|---|---|
| `UNSAFE_SINK` | ERROR | sink da M2 com valor não literal |
| `TRUSTED_URL` / `TRUSTED_STYLE` | INFO | uso de `trustedUrl(...)` / `trustedStyle(...)` |
| `CSP_INLINE` | WARNING | só com `strictCsp` |
| `UPPERCASE_NAME` | ERROR | nome de tag/atributo todo em maiúsculas |
| `RESERVED_NAME` | ERROR | declaração de `trustedUrl`/`trustedStyle`/`trustedHtml` |
| `REGISTRY_UNSIGNED`, `REGISTRY_BAD_SIGNATURE`, `REGISTRY_ROLLBACK`, `REGISTRY_EXPIRED`, `REGISTRY_MANIFEST_HASH` | ERROR (CLI) | falhas da M6 |

## Compatibilidade

- Projetos que hoje passam valores dinâmicos a `on*`, `style`, `srcdoc`, `x-*`,
  etc. deixam de compilar — intencional; a mensagem indica a alternativa
  (literal, classe CSS, `style` por declarações, `trustedUrl`/`trustedStyle`).
  Os componentes do repositório que usam `x-data`/`x-show` dinâmicos são
  corrigidos.
- O `.jte` gerado muda para atributos de URL dinâmicos (`SukoSafe.url`), `@` em
  texto e `rel`: o golden do 13a é regenerado **uma vez**, revisto à mão, com o
  diff limitado a essas mudanças.
- A 13b é atualizada: `CLIENT_UNSAFE_SINK` passa a referir a M2 e
  `onclick="${...}"` passa a ERROR.

## Aplicação de exemplo: e-commerce com H2 em memória

Decidido pelo utilizador a 2026-10-04: `examples/` (hoje `.sk` soltos que nem
compilam) é **substituído por uma aplicação de e-commerce executável, com base
de dados H2 em memória** e dados de exemplo, para mostrar vários conceitos do
Suko com dados reais.

- **Neste subprojeto (servidor):** catálogo, detalhe, pesquisa, categorias,
  avaliações com texto de utilizadores e checkout com formulário — serve de
  demonstração e de teste das medidas acima (avaliações e pesquisa são input
  hostil por natureza).
- **Na 13b:** a mesma loja ganha ilhas — carrinho reativo, quantidade +/−,
  filtro de pesquisa ao vivo, contador no cabeçalho.
- **Depois da 13b:** **pentest** à loja completa (pedido do utilizador): XSS em
  todos os contextos, island injection, adulteração de props, CSRF no checkout,
  open redirect, injeção via pesquisa e avaliações. Os achados voltam como
  correções; o que é da aplicação (CSRF, autenticação) sai como recomendação de
  documentação.
- `examples/invalid` (erros de propósito) passa para fixtures de teste do core;
  o golden do 13a que apontava a `examples/` é regenerado para a loja.
- **Framework (decidido pelo utilizador, 2026-10-04): Spring Boot** —
  `jte-spring-boot-starter` oficial, Spring Data JDBC sobre H2 em memória
  (`schema.sql` + `data.sql` com produtos, categorias e avaliações de exemplo),
  consola H2 ativa só no perfil de desenvolvimento. Os templates vêm do
  `sukoCompile` (plugin Gradle do Suko); CSRF e headers de segurança (CSP da M7)
  configurados com Spring Security, como referência de boa prática.

## Ordem e próximos passos

- **Ordem (confirmada pelo utilizador):** integrar o 13a → **14** → item 12 →
  13b → pentest à loja → 11c → editores.
- Utilizador revê esta spec; o plano é escrito a seguir.
