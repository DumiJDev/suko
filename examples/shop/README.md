# Suko Shop — loja de exemplo

Loja de e-commerce executável (Spring Boot 3.3 + H2 em memória + `jte-spring-boot-starter-3`)
cujas páginas são componentes Suko (`src/main/suko/shop/*.sk`). Serve de demonstração e,
mais tarde, de alvo do pentest do subprojeto 14.

Esta é a parte 1/2: catálogo (`/`), categorias (`/c/{slug}`), produto (`/p/{id}`, com as
avaliações só de leitura) e pesquisa (`/search?q=`). Avaliações, carrinho, checkout e os
cabeçalhos de segurança (CSP estrita) chegam na parte 2.

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
- O starter do JTE compila os `.jte` em runtime (`gg.jte.development-mode=true`,
  `gg.jte.template-location=src/main/jte`). Por isso `suko.security.jtePolicy` está a
  `false`: a política do JTE só se aplica com o plugin Gradle do JTE (pré-compilação), que
  esta loja não usa. O escape do HTML continua a ser o do JTE (`ContentType.Html`).
- `suko.security.strictCsp` está ligado: nenhum componente usa `style=`, `on*` ou `<script>`
  inline (o CSS está em `static/css/shop.css`).
- Todo o SQL usa parâmetros nomeados (`JdbcClient`); a pesquisa escapa `%`, `_` e `\` e
  corta o termo a 80 caracteres antes de consultar.
- `gg.jte:jte` é declarado explicitamente: o starter declara-o como dependência opcional.

## Limitações atuais do Suko visíveis aqui

- Os componentes recebem `Map<String,String>` e `List<Map<String,String>>` (montados em
  `shop.view.Views`): a gramática ainda não aceita nomes de tipo qualificados nem importa
  tipos Java (item 12).
- O `Layout` não tem `<!DOCTYPE html>` (a gramática ainda não o aceita), por isso as páginas
  abrem em modo quirks.
- Componentes do mesmo package têm de ser importados explicitamente (`import shop.Layout;`).
