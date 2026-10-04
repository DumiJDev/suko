# Suko — Subprojeto 14: segurança por omissão

Data: 2026-10-04
Estado: **rascunho para revisão do utilizador.** Âmbito decidido pelo
utilizador a 2026-10-04: "aplica todas as medidas de segurança".
Bloqueia a primeira release (junta-se ao 11b, 11c, 13a e item 12).

## Contexto

O JTE (`ContentType.Html`) faz uma coisa em segurança: **escapa caracteres no
render**, conforme o contexto (conteúdo HTML, atributo, bloco `<script>`,
atributo `on*` — `Escape.htmlContent`/`htmlAttribute`/`javaScriptBlock`/
`javaScriptAttribute`, verificado no `jte-runtime` 3.1.12). Tudo o que depende
de saber o **significado** de um valor fica fora do seu alcance. O Suko conhece
o AST em compile-time — sabe que `href` é um URL e que `srcdoc` recebe HTML —
e pode fechar essas lacunas sem dependência de runtime. Princípio (decisão do
utilizador): **delegar ao JTE o que ele já faz; o Suko só acrescenta o que o
JTE não pode ver.**

Lacunas atuais (ver `ARCHITECTURE.md`): nada valida protocolos de URL (a
lacuna conhecida do subprojeto 2); o `SemanticChecker` não tem regras de HTML;
o `OwaspHtmlPolicy` do JTE está desligado por omissão; o índice do registry
(`registry.json`) não é assinado (cada ficheiro de componente já tem `sha256`).

As mesmas regras servem a 13b (reatividade): a allowlist de URLs e as regras
de sinks são partilhadas entre o servidor (aqui) e o cliente (13b).

## Medidas

### M1 — Protocolos de URL em todos os componentes

- Atributos de URL — `href` (qualquer elemento, incl. SVG/MathML),
  `xlink:href`, `src`, `action`, `formaction`, `data` (em `object`), `poster`,
  `cite`, `background`, `ping`, `srcset` (cada candidato) — com **valor não
  literal** são emitidos como `${io.suko.generated.SukoSafe.url(expr)}`.
- `SukoSafe` é uma classe **gerada no output do projeto** pelo alvo JTE (só
  JDK; "nenhuma dependência do Suko em runtime" mantém-se). `url(...)`:
  remove espaços/C0 nas pontas e tab/LF/CR no meio (como o browser), resolve o
  esquema e aplica uma **allowlist**: `http`, `https`, `mailto`, `tel`, e URLs
  relativos/protocol-relative. Qualquer outro valor →
  `about:invalid#suko-blocked`. Nunca denylist, nunca decodificação de
  entidades.
- Configurável: `suko { security { urlSchemes = ["http","https","mailto","tel"] } }`
  (Gradle) e o equivalente no Maven.
- Saída explícita para casos legítimos: `${trustedUrl(expr)}` — função
  reconhecida pelo compilador que dispensa a verificação e gera o diagnóstico
  `TRUSTED_URL` (INFO) em cada uso, para auditoria.
- Literais (`href="/carrinho"`) não são tocados.
- **Paridade:** o `sanitizeUrl` do cliente (13b) implementa a mesma função; um
  teste corre o mesmo corpus nos dois e compara.

### M2 — Sinks perigosos (`UNSAFE_SINK`, ERROR, todos os componentes)

Com valor **não literal**, é erro:

- conteúdo de `<script>`, `<style>`, `<noscript>` (dentro de `<noscript>` o
  browser reinterpreta texto como HTML quando o JS está desligado);
- atributos `on*` (insensível a maiúsculas) — o JTE escapa como string JS, mas
  o template pôs o valor no lugar de código;
- `srcdoc`, `style`;
- `<base href>`, `<meta http-equiv>` / `<meta content>` quando há `http-equiv`,
  `<link href>` com `rel` que carrega recursos (`stylesheet`, `import`,
  `preload`, `modulepreload`);
- `<object data>`, `<embed src>`, `<iframe src>` (estes três passam pela M1 e
  ainda dão erro se o valor não for literal — conteúdo embebido dinâmico é raro
  e perigoso; saída: `${trustedUrl(...)}`).

Literais estáticos continuam permitidos. A 13b usa a mesma regra
(`CLIENT_UNSAFE_SINK` passa a ser este `UNSAFE_SINK`).

### M3 — Endurecimento automático de atributos

- `target="_blank"` com `href` não literal: o compilador acrescenta
  `rel="noopener noreferrer"` (ou junta-os a um `rel` existente).
- Lint opcional **CSP estrita** (`suko { security { strictCsp = true } }`):
  WARNING para `style="..."` e atributos `on*` mesmo literais, e para
  `<script>` inline — para projetos que queiram `script-src 'self'` sem
  `'unsafe-inline'`.

### M4 — Política do próprio JTE

- Quando o projeto usa o plugin de pré-compilação do JTE (Gradle
  `gg.jte.gradle` ou `jte-maven-plugin`), o plugin do Suko configura
  `htmlPolicyClass` para uma política gerada que estende `OwaspHtmlPolicy` do
  JTE (`PreventInlineEventHandlers`, `PreventOutputInTagsAndAttributes`,
  `PreventUnquotedAttributes`, ...). Desligável com
  `suko { security { jtePolicy = false } }`.
- Sem o plugin do JTE (templates compilados em runtime), a documentação mostra
  como ativar com `templateEngine.setHtmlPolicy(...)` (e a propriedade do
  starter Spring/Quarkus, se existir — a verificar no plano).
- A política tem de aceitar o `.jte` que o Suko gera (teste: o golden inteiro
  compila com a política ligada).

### M5 — HTML confiável explícito e auditável

- Hoje o único caminho para HTML cru são os parâmetros `Component` (slots), o
  que está correto. Fica proibido, por regra testada, o emissor gerar
  `$unsafe{}` (teste: varrimento do output).
- Se no futuro existir um equivalente a `$unsafe{}`, terá nome explícito
  (`trustedHtml(...)`) e diagnóstico `TRUSTED_HTML` (INFO) por uso. Não entra
  nesta versão.

### M6 — Cadeia de fornecimento do registry

- O índice `registry.json` passa a ter assinatura **Ed25519** (JDK, sem
  dependências) num ficheiro `registry.json.sig`; o `suko-cli` traz a chave
  pública do registry oficial embutida e aceita chaves adicionais por
  configuração (`suko.json`: `registries[].publicKey`).
- Índice sem assinatura ou com assinatura inválida: erro (opção
  `--allow-unsigned` para registries locais/de desenvolvimento, com aviso).
- O `sha256` por ficheiro mantém-se; com o índice assinado, a cadeia fica
  fechada.
- Ferramenta para o mantenedor assinar (`suko-registry-generator` ganha
  `--sign <chave privada>`); a chave privada nunca entra no repositório.

### M7 — Testes que provam

- **Corpus XSS** (OWASP XSS Filter Evasion Cheat Sheet e payloads do
  PortSwigger) passado por cada contexto do emissor: texto, atributo, URL,
  `srcset`, e confirmação de que os sinks da M2 são recusados.
- **URLs:** `" javascript:"`, `"JAVASCRIPT:"`, `"java\tscript:"`,
  `"\u0001javascript:"`, `"javascript&#58;"` (inerte), `data:text/html`,
  `vbscript:`, relativos, protocol-relative, `srcset` com um candidato hostil.
- **Browser (Playwright):** páginas de exemplo servidas com CSP
  `default-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none';
  require-trusted-types-for 'script'; trusted-types 'none'`; qualquer violação
  reportada falha.
- **Política do JTE:** o golden compila com a política gerada ligada.
- **Registry:** índice adulterado, assinatura de outra chave, sem assinatura,
  `--allow-unsigned`.
- **Paridade servidor/cliente** da função de URL (quando a 13b existir).
- **Varrimento** do `.jte` gerado: nunca `$unsafe`.

## Diagnósticos novos

| Código | Severidade | Quando |
|---|---|---|
| `UNSAFE_SINK` | ERROR | sink da M2 com valor não literal |
| `TRUSTED_URL` | INFO | uso de `trustedUrl(...)` |
| `CSP_INLINE` | WARNING | só com `strictCsp`: `style=`, `on*` ou `<script>` inline |
| `REGISTRY_UNSIGNED` / `REGISTRY_BAD_SIGNATURE` | ERROR (CLI) | índice sem assinatura / assinatura inválida |

## Compatibilidade

- Projetos que hoje passam valores dinâmicos a `on*`, `style`, `srcdoc`, etc.
  deixam de compilar — intencional; a mensagem indica a alternativa (literal,
  classe CSS, `trustedUrl`).
- O `.jte` gerado muda para atributos de URL dinâmicos (passam a chamar
  `SukoSafe.url`): o golden do 13a é regenerado **uma vez**, revisto à mão, com
  o diff limitado a esses atributos. Os exemplos do repositório, os componentes
  e o site têm de continuar a compilar (corrigidos se usarem um sink da M2).

## Ordem e próximos passos

- **Ordem proposta:** a seguir à integração do 13a e **antes do item 12** — é
  independente, mexe no emissor que o item 12 também vai mexer, e fecha uma
  lacuna antiga.
- **Exercício final (pedido do utilizador):** depois de a 13b estar
  implementada, construir um exemplo de **e-commerce** com Suko + JTE + JS
  (catálogo, pesquisa, carrinho reativo, checkout com formulário, avaliações
  com texto de utilizadores) e fazer um **pentest** a ele: XSS em todos os
  contextos, island injection, adulteração de props, CSRF no checkout, open
  redirect, injeção via pesquisa/avaliações. Os achados voltam como correções.
  (Coisas da aplicação — CSRF, autenticação — são do framework da aplicação,
  não do Suko; o exercício regista-as como recomendações de documentação.)
- Revisão do `security-specialist` sobre esta spec antes do plano.
