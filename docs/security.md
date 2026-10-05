# Segurança no Suko

Modelo de ameaças curto do subprojeto 14 (segurança por omissão). A spec completa
está em `docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md`. Este
documento diz o que o JTE faz, o que o Suko acrescenta e o que fica à aplicação.

Ainda não há release. O que se descreve existe no repositório e está coberto por
testes, mas não houve pentest (o da loja de exemplo está planeado; ver
`ARCHITECTURE.md`, item 14).

## 1. O que o JTE faz

O Suko compila `.sk` para `.jte`. O JTE (3.1.12) escapa por contexto no render
(texto, atributo, JavaScript, CSS). Isso resolve a injeção de caracteres, mas não
o **significado** do valor. Limitações verificadas:

1. Um valor escapado num atributo de URL continua a ser um URL: `href="javascript:..."`
   passa intacto.
2. Em `<script>` e `<style>` o escape é de JS/CSS dentro de strings; um valor
   dinâmico fora de um literal é código.
3. `@if(...)`, `@for(...)`, `@template...` num texto estático são sintaxe JTE; um
   componente de terceiros (por exemplo, do registry) podia lá pôr diretivas.
4. A `OwaspHtmlPolicy` do JTE só actua se o build a ativar (`htmlPolicyClass`), e
   nunca se os templates forem gerados sem o plugin do JTE.

## 2. O que o Suko acrescenta

Tudo vale para os componentes que usam o vocabulário `html` (o do alvo `jte`).

**URLs (M1).** Todo o valor dinâmico num atributo de URL (`href`, `src`, `action`,
`formaction`, `poster`, `ping`, `srcset`, `hx-get`... ) passa por `SukoSafe.url`
(ou `srcset`/`imageUrl`/`ping`). Só passam os esquemas de `urlSchemes` (por omissão
`http`, `https`, `mailto`, `tel`) e URLs relativos. O resto vira
`about:invalid#suko-blocked`. Literais não são tocados.

```
// antes (o JTE escapava, mas deixava passar javascript:)
<a href=${site}>site</a>
// depois: o compilador emite SukoSafe.url(site); javascript:x sai como about:invalid#suko-blocked
```

**Sinks perigosos (M2).** Valor não literal onde o browser executa ou reinterpreta
código é erro de compilação `UNSAFE_SINK`: conteúdo de `<script>`, `<style>` e
`<noscript>`; `on*`, `srcdoc`; `style` (exceto declarações `propriedade: ${v}`,
cujo valor passa por `SukoSafe.cssValue`); `<script src>` dinâmico;
`<link>`/`<meta>`/`<base>` com efeito; animações SVG; `iframe`/`frame`/`object`/`embed`
dinâmicos (exceto com origem literal e `SukoSafe.pathSegment` nas partes dinâmicas);
`x-*` e `hx-on*` (também na forma `data-`); `hx-vals`/`hx-headers`/`hx-trigger`
dinâmicos. Atributos duplicados: vale o primeiro, como nos browsers.

```
// antes: erro UNSAFE_SINK
<div style=${css}>...</div>
// depois (declarações com nome literal; o valor passa por cssValue)
<div style="width: ${pct}%">...</div>
```

**Endurecimento (M3).** `target` em `<a>`/`<area>`/`<form>` junta `noopener` ao `rel`.
`SukoSafe.rel` garante-o sempre em runtime. Sair disto (`rel="opener"`) só é possível
com `rel` **literal**, em compile-time.

**Saídas explícitas e auditoria (M5).**
`trustedUrl(expr)` (só em atributos de URL/srcset/ping) e `trustedStyle(expr)` (só em
`style`) dispensam a verificação, geram `TRUSTED_URL`/`TRUSTED_STYLE` (INFO) e entram em
`build/suko/security-audit.json` (Maven: `target/suko/`). Qualquer outro uso é
`RESERVED_NAME` (erro). `trustedHtml` não existe: usá-lo ou declará-lo dá o mesmo erro.
O emissor também neutraliza sintaxe JTE em texto estático.

**JTE e CSP (M3/M4).** Gradle: com `gg.jte.gradle` aplicado e `suko.security.jtePolicy`
ligado (por omissão), o plugin define `htmlPolicyClass = gg.jte.html.OwaspHtmlPolicy`
de forma preguiçosa, sem sobrescrever um valor do utilizador. Maven: só há aviso. A
`OwaspHtmlPolicy` recusa nomes de tag/atributo todos em maiúsculas; o Suko adianta-se
com `UPPERCASE_NAME`. Com `strictCsp = true`, `CSP_INLINE` (WARNING) assinala `style=`,
`<style>`/`<script>` inline sem `nonce`, `on*`, `x-*` e `javascript:` literal.

**Registry (M6).** Assinatura Ed25519 do índice; ver a secção 5.

### Configuração

Gradle:

```kotlin
suko {
  security {
    urlSchemes.set(listOf("http", "https", "mailto", "tel"))  // omissão
    imageDataTypes.set(listOf("png", "webp"))   // subconjunto de png,gif,jpeg,webp,avif; só img/source
    strictCsp.set(true)
    codeAttributes.add("x-meu")      // tratados como UNSAFE_SINK se dinâmicos
    urlAttributes.add("data-url")    // verificados como URLs
    jtePolicy.set(true)              // omissão
  }
  // generatedPackage, generatedJavaDir (ver abaixo)
}
```

Maven: `<security>` com `urlSchemes`, `imageDataTypes`, `codeAttributes`, `urlAttributes` e
`strictCsp` na configuração do plugin; `generatedPackage` (`-Dsuko.package`) e
`generatedJavaDir` (`-Dsuko.generatedJavaDir`).

- Esquemas proibidos em `urlSchemes` (`javascript`, `vbscript`, `data`, `blob`,
  `filesystem`) são erro de build. `imageDataTypes` só aceita raster (`png`, `gif`,
  `jpeg`, `webp`, `avif`); SVG nunca.
- A `SukoSafe` é **gerada no projeto** (só JDK, sem dependência do Suko em runtime) em
  `generatedPackage`: Gradle, por omissão `io.suko.generated.<projeto>`; Maven, derivado do
  `artifactId`. Pasta: Gradle `build/generated-src/suko-java`; Maven
  `target/generated-sources/suko-java`.
- **URL dinâmico com prefixo literal não garante mesma origem.** Em `href="/${path}"`, a
  `SukoSafe.url` aceita valores relativos ao esquema: com `path = "/evil.com"` o resultado é
  `//evil.com`, que o browser resolve para outro anfitrião. Quem precisa de mesma origem tem de
  validar ou codificar o valor (por exemplo, só aceitar segmentos de caminho); o `pathSegment`
  é aplicado apenas a embeds de origem constante.
- `SukoSafe.pathSegment` só é seguro depois de um prefixo literal `esquema://host/`.

## 3. O que não é do Suko

- **CSRF**: é da aplicação (na loja, Spring Security).
- **Autenticação e autorização**, sessões, limites contra abuso.
- **Sanitização de HTML de terceiros.** `Component` num slot é HTML de confiança por
  construção. Se uma página mostrar HTML de utilizadores, sanitize-o antes. Quando houver
  ilhas interativas (13b), esse HTML não pode partilhar a página com elas a menos que
  se retirem os atributos `data-suko*`.
- Cabeçalhos de resposta (CSP, `X-Content-Type-Options`, ...) e TLS.
- O que está em `.jte` escritos à mão ou em Java: o Suko só verifica os `.sk`.
- Extensões e alvos de terceiros correm como código no build e no LSP (o LSP só as carrega
  em workspaces confiáveis); não estão em sandbox.

## 4. CSP recomendada

A da loja (`examples/shop`, `SecurityConfig`), possível porque nenhum componente usa
`style=`, `on*` ou `<script>` inline (`strictCsp` ligado):

```
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:;
object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'
```

Mais `Referrer-Policy: strict-origin-when-cross-origin`, `X-Content-Type-Options: nosniff`,
`X-Frame-Options: DENY` e uma `Permissions-Policy` restritiva. O `img-src ... data:` é da loja;
retire-o se não usar `data:`. O compilador nunca emite `<script>` inline próprio.

## 5. Registry: modelo de confiança

O índice `registry.json` leva `registryId`, `ref`, `registryVersion`, `issuedAt`,
`expires` e o `manifestSha256` de cada manifesto. `registry.json.sig` assina os bytes
exatos do índice. A CLI verifica pela ordem: assinatura, parse e `schemaVersion == 2`,
datas, expiração, `registryId` e `ref`, anti-rollback, hash de cada manifesto, hash de
cada ficheiro, e só depois escreve em disco.

Erros (CLI): `REGISTRY_UNSIGNED`, `REGISTRY_BAD_SIGNATURE`, `REGISTRY_MISMATCH`,
`REGISTRY_EXPIRED`, `REGISTRY_ROLLBACK`, `REGISTRY_MANIFEST_HASH` e `REGISTRY_INVALID`
(estrutura: schema, datas ilegíveis, caminho de manifesto inválido; não está na spec).

- **Chave por registry.** Cada chave está ligada a um `registryId` e nunca valida outro. O template oficial da base (`https://raw.githubusercontent.com/DumiJDev/suko/<ref>/suko-components/`) mapeia para o `registryId` oficial `https://raw.githubusercontent.com/DumiJDev/suko/`; uma base personalizada mapeia para a base normalizada. O
  `registryId` esperado vem da configuração, nunca do índice. As chaves vêm do recurso
  `/io/suko/cli/trusted-keys.json` da CLI e de `registry.publicKeys` no `suko.json`
  (`[{"keyid","publicKey"}]`, base64 X.509).
- **Rotação**: a chave nova entra num release da CLI antes de ser usada. **Revogação**: um
  release que a remove; as CLIs antigas confiam até atualizarem.
- **Anti-rollback**: recusa-se um `issuedAt` ou `registryVersion` inferior ao do
  `suko.lock.json` (`--allow-downgrade` para forçar). **Anti-strip**: um registry já visto
  assinado nunca volta a ser aceite sem assinatura, nem com `--allow-unsigned`.
- **`--allow-unsigned`**: só para registries locais (sistema de ficheiros,
  `http(s)://localhost|127.0.0.1|[::1]`). Caminhos UNC/de rede não contam como locais; uma
  unidade de rede mapeada parece local e conta.

Limites a conhecer:

- A assinatura protege o transporte e o anfitrião do registry, não um `suko.json` ou um
  lockfile hostil (quem os controla escolhe o registry e as chaves).
- Apagar o lockfile repõe o anti-strip e o anti-rollback.
- O lockfile guarda **um** registry; trocar `--registry` perde a memória do anterior.
- Caminhos locais são normalizados antes de entrarem no lockfile.
- Uma ref móvel (por exemplo `main`) sem `expires` pode ser reapresentada (ataque de
  congelamento). Para refs que não sejam tags, a política de release deve definir `expires`.

### Estado atual e checklist de release

A lista de chaves embutida na CLI está **vazia**. Enquanto uma release não embutir a chave
oficial, `suko add`/`list` contra o registry oficial (HTTPS) falha com `REGISTRY_UNSIGNED`;
é intencional (falha fechado), mas visível. Em desenvolvimento: `--registry` para um
registry local com `--allow-unsigned`, ou `registry.publicKeys`.

Para a primeira release:

1. Gerar a chave: `RegistryTool generate-key --out-dir D --key-id ID --registry-id https://raw.githubusercontent.com/DumiJDev/suko/`
   (a privada fica fora do repositório; o `.gitignore` cobre `*.private.pem`).
2. Embutir a pública em `suko-cli/src/main/resources/io/suko/cli/trusted-keys.json`, ligada a esse `registryId`.
3. Atualizar `issuedAt`/`ref` nos metadados do gerador; para refs que não sejam tags, definir `expires`.
4. Assinar o `registry.json` na tag de release: `RegistryTool sign --registry-dir D --key-id ID --key-file F`.
   O `sign` verifica o próprio resultado, salvo `--no-verify`. Assinar fora do CI disparado por push, ou
   num environment protegido.
5. Versionar a assinatura: `git add -f suko-components/registry.json.sig` (o `.gitignore` já não a ignora)
   e confirmar com `git status` que o ficheiro entra no commit/tag da release.
6. Confirmar que o recurso `trusted-keys.json` entra no native-image (ver lacunas em `ARCHITECTURE.md`).

## 6. Contrato dos alvos

**Todo alvo que aceite o vocabulário `html` tem de aplicar a verificação de URLs da M1** (o
mesmo conjunto de atributos e a mesma classificação de `SukoSafe.url`) **e correr o corpus
XSS** (`suko-core/src/test/resources/security/xss-corpus.txt`, usado por `XssCorpusTest`).
Está documentado em `suko-api/README.md`. O `suko-jte` cumpre-o; o core não conhece o alvo.

## 7. A loja de exemplo

`examples/shop` é uma loja Spring Boot + H2 em Suko que serve de demonstração e de alvo do
futuro pentest; ver `examples/shop/README.md`, incluindo as limitações conhecidas. Durante
a revisão encontrou-se e corrigiu-se uma corrida no checkout (várias encomendas a partir do
mesmo carrinho), agora atómico por sessão e com teste de concorrência. O modo de
desenvolvimento do JTE (compilar templates em runtime) é só para demos e fica fora do
âmbito do pentest: a loja usa templates pré-compilados.

## 8. Lacunas conhecidas

Estão em `ARCHITECTURE.md` (item 14): tipos de componentes, `<!DOCTYPE html>`,
`suko.security` no LSP, atributos de extensões, Playwright com CSP/Trusted Types,
`:`/`@` em nomes de atributo e a chave do registry na CLI.
