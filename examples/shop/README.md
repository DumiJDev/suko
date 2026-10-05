# Suko Shop — loja de exemplo

Loja de e-commerce executável (Spring Boot 3.3 + H2 em memória + `jte-spring-boot-starter-3`)
cujas páginas são componentes Suko (`src/main/suko/shop/*.sk`). Serve de demonstração e,
mais tarde, de alvo do pentest do subprojeto 14.

Rotas: catálogo (`/`), categorias (`/c/{slug}`), produto com avaliações (`/p/{id}`,
`POST /p/{id}/reviews`), pesquisa (`/search?q=`), carrinho (`GET /cart`, `POST /cart/add`,
`POST /cart/remove`), checkout (`GET|POST /checkout`) e confirmação
(`/orders/{id}/confirmation`). Nenhuma rota é autenticada.

## Como correr

É um build Gradle **separado** do build principal; usa o plugin `io.suko.lang` do próprio
repositório através de um composite build (`pluginManagement { includeBuild("../..") }`),
tal como um utilizador o usaria.

```bash
# a partir da raiz do repositório
./gradlew -p examples/shop test
./gradlew -p examples/shop bootRun     # http://localhost:8080/
```

Em checkouts dentro de `/mnt/c` (WSL com um IDE Windows aberto) usar
`scripts/verify-isolated.sh -p examples/shop test`.

## Como está montada

- `sukoCompile` compila `src/main/suko` para `src/main/jte` (gerado, fora do git) e gera
  `shop.suko.SukoSafe` em `build/generated-src/suko-java`.
- **Templates pré-compilados.** O plugin `gg.jte.gradle` (modo `generate()`) gera Java a partir
  dos `.jte` que o `sukoCompile` escreveu, e o `compileJava` compila-o com o resto da aplicação.
  Em runtime o starter usa só essas classes (`gg.jte.use-precompiled-templates=true`,
  `gg.jte.development-mode=false`): nada é compilado a partir de ficheiros em disco. Com o plugin
  do JTE aplicado, `suko.security.jtePolicy` fica no valor por omissão (`true`) e o Suko liga a
  `gg.jte.html.OwaspHtmlPolicy` na geração.
- **Modo de desenvolvimento do JTE só para iterar localmente.** `gg.jte.development-mode=true` com
  `gg.jte.template-location=src/main/jte` recompila os templates em runtime; serve para
  desenvolver, mas a pasta dos templates passa a ser código executável. Essa configuração **não**
  é a da loja e fica **fora do âmbito do pentest**.
- `suko.security.strictCsp` está ligado: nenhum componente usa `style=`, `on*` ou `<script>`
  inline (o CSS está em `static/css/shop.css`). O `build/suko/security-audit.json` sai vazio
  (nenhum `trustedUrl`/`trustedStyle`/`trustedHtml`), e um teste verifica-o.
- `gg.jte:jte` é declarado explicitamente: o starter declara-o como dependência opcional.

## Segurança (alvo do pentest)

A loja não tem vulnerabilidades deliberadas. O que está montado, e o teste que o prova:

- **Escape e URLs**: todo o texto de utilizadores (avaliações, pesquisa, checkout) é escapado
  pelo JTE; os `href`/`src`/`action` dinâmicos passam pela `SukoSafe.url`. O `website` de uma
  avaliação é só texto do lado da aplicação, e é o Suko que mostra `javascript:...` como
  `about:invalid#suko-blocked`. O `data.sql` semeia um destes de propósito, no produto 4
  (`ReviewsTest`, `XssCorpusShopTest`, que corre o corpus XSS do core contra pesquisa,
  avaliações e checkout).
- **SQL**: só parâmetros nomeados (`JdbcClient`). A pesquisa escapa `%`, `_` e `\` e corta o
  termo a 80 caracteres (`CatalogPagesTest`).
- **CSRF**: ligado, com o repositório de sessão por omissão. Todos os formulários `POST` levam
  `<input type="hidden" name=${csrfName} value=${csrfToken}>` (`ShopModelAdvice`), e um POST sem
  token dá 403.
- **Cabeçalhos** (`SecurityConfig`, `SecurityHeadersTest`):
  - CSP estrita sem `'unsafe-inline'`
  - `Referrer-Policy: strict-origin-when-cross-origin`
  - `X-Content-Type-Options: nosniff`
  - `X-Frame-Options: DENY` e `frame-ancestors 'none'`
  - `Permissions-Policy` a desligar câmara, microfone, geolocalização, pagamento e USB
- **Sessão**: cookie `HttpOnly`, `SameSite=Lax`, só por cookie (`tracking-modes=cookie`).
  `Secure` fica desligado porque a demo corre em `http://localhost`.
- **Carrinho** (`CartAndCheckoutTest`):
  - O carrinho vive na sessão (bean `@SessionScope`), por isso não há id de carrinho para trocar.
  - Só guarda ids e quantidades: o preço vem sempre da base de dados, e um `priceCents` forjado
    é ignorado.
  - Cada linha leva 1 a 20 unidades e o carrinho tem no máximo 50 linhas. Fora disso dá 400.
- **Checkout**: valida no servidor `name` (1 a 120), `email` (3 a 200, com `@`) e `address`
  (1 a 400). O `OrderService` corre numa transação: recalcula o total, desce o stock só se
  chegar e grava a encomenda e as linhas. Se falhar, nada fica gravado. A confirmação só abre
  para encomendas feitas na própria sessão; qualquer outro id dá 404.
- **Avaliações**: `author` 1 a 80, `body` 1 a 2000, `rating` 1 a 5, `website` até 200. Uma
  avaliação inválida volta a mostrar a página (200) com os erros e o que foi escrito, escapado.
- **Erros**: `server.error.include-*` a `never`/`false`. Os 400 e 404 da aplicação usam
  `ErrorPage`/`NotFoundPage`. Os erros do contentor (por exemplo, o 403 de CSRF) usam a
  whitelabel do Spring Boot, sem detalhes.
- **Outros**: a consola H2 está desligada (404), e não há utilizador por omissão (nem a
  "generated security password" no log).

## Limitações conhecidas (âmbito do pentest)

Estas limitações estão documentadas de propósito e não foram corrigidas:

- **Pedidos recusados pelo firewall do Spring Security ou pelo Tomcat não levam cabeçalhos de
  segurança.** Exemplos: `/p/1;x=1` e `/p/1%0d%0aX:y`.
- **URLs sem mapeamento e o `/error` mostram a whitelabel do Spring Boot.** Isto inclui o 403
  de CSRF. A página não mostra detalhes (`server.error.include-*`), mas não é a página da loja.
- **Não há limites contra abuso:**
  - avaliações ilimitadas;
  - encomendas sem pagamento, que podem esgotar o stock;
  - cada `GET`/`HEAD` cria uma sessão (carrinho e token CSRF).
- **`name`, `author` e `address` aceitam caracteres de controlo e bidi.** Saem escapados, mas
  não são filtrados. O `email` recusa caracteres de controlo.
- **A consola H2 está sempre desligada.** É um aperto deliberado face à spec, que a previa no
  perfil de desenvolvimento.
- **O checkout é atómico por sessão.** O `Cart.drain()` tira e esvazia o carrinho num só
  passo, e um checkout falhado devolve as linhas ao carrinho. Num checkout com sucesso o id da
  sessão muda (`changeSessionId`, defesa contra session fixation).

## Limitações atuais do Suko visíveis aqui

- Os componentes recebem `Map<String,String>` e `List<Map<String,String>>` (montados em
  `shop.view.Views`): a gramática ainda não aceita nomes de tipo qualificados nem importa
  tipos Java (item 12).
- O `Layout` não tem `<!DOCTYPE html>` (a gramática ainda não o aceita), por isso as páginas
  abrem em modo quirks.
- Componentes do mesmo package têm de ser importados explicitamente (`import shop.Layout;`).
- `for` é palavra reservada e não pode ser nome de atributo: `<label for="x">` não compila. Os
  formulários põem o `<input>` dentro do `<label>`.
- Texto que começa por uma palavra com maiúscula seguida de `(` é lido como chamada de
  componente: `Site (opcional)` dá `COMPONENT_NOT_FOUND: 'Site'`.
