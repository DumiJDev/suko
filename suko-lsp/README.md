# suko-lsp

Language server do Suko (Language Server Protocol, por stdio). Depende de `suko-core` e `suko-jte`; a extensão VSCode (`editors/vscode/`) embute o fat jar e o cliente IntelliJ (11b) irá reutilizá-lo.

```sh
./gradlew :suko-lsp:fatJar        # build/libs/suko-lsp-<versão>-all.jar
java -jar suko-lsp-<versão>-all.jar   # fala LSP por stdin/stdout (Java 21+)
```

## O que faz

| Pedido | Comportamento |
|---|---|
| Diagnósticos | Reverifica o source root inteiro 250 ms depois da última alteração e publica, por ficheiro, os diagnósticos do `sukoCompile` (mesmos códigos, mensagens e severidades), mais os avisos que o build ainda não mostra. Buffers abertos sobrepõem-se ao disco sem serem gravados. |
| `definition` | Componente (chamada, valor ou `import`) → nome na declaração; argumento ou slot → o parâmetro. Usa o mesmo `CallResolver` do compilador. |
| `hover` | Assinatura, slots e cardinalidade, visibilidade, package e ficheiro. |
| `completion` | Por tokens do lexer (não depende do AST): após `import`, componentes e keywords no corpo (com `import` automático para `public` não importados), parâmetros ainda não passados dentro de `Nome(...)`, slots dentro de `Nome() { ... }`. |

Java dentro de `${...}` (completion, hover, tipos) fica para o 11c.

## Projeto

Por pasta do workspace: `sourceRoot` do `suko.json`; senão `src/main/suko`; senão a setting `suko.sourceRoot` (`initializationOptions.sourceRoot` / `workspace/didChangeConfiguration`). Um índice por source root; sem resolução de nomes entre roots. O cliente deve enviar `workspace/didChangeWatchedFiles` para `**/*.sk` e `suko.json`.

## Extensões do projeto (13a)

O server traz o alvo `jte` embutido. As extensões do projeto (alvos, vocabulários, checkers — ver `suko-api/README.md`) vêm do `build/suko/extensions.json` (Gradle) ou `target/suko/extensions.json` (Maven), escrito pelo `sukoCompile`/`suko:compile`; é procurado do source root para cima, até à pasta do workspace.

- **Só em workspace confiável.** O cliente envia `trusted` nas `initializationOptions` (o VSCode usa `workspace.isTrusted`). Não confiável: só o `jte` embutido e o manifesto nem é aberto. Ao conceder confiança, a extensão VSCode reinicia o server.
- Manifesto com mais de 1 MiB é ignorado; cada entrada do classpath tem de ser um caminho absoluto para um `.jar` existente (diretórios e UNC rejeitados, com aviso). Um `suko-jte` listado no manifesto é ignorado.
- O `extensions.json` é vigiado (`**/suko/extensions.json`) e as extensões são recarregadas quando muda (comparação por fingerprint). Se o manifesto não existe ainda (nunca houve build) ou a escrita não é atómica, o server pode ver o estado anterior.
- O `sourceRoot` do `suko.json` tem de ficar dentro da pasta do workspace.
- Sem `extensions.json` o server fica em silêncio (a spec dizia avisar uma vez; desvio deliberado para evitar ruído). Só procura `build/suko` e `target/suko`: com um `buildDirectory` personalizado no Gradle não o encontra.
- Uma extensão que falhe dá `EXTENSION_FAILED` e o server continua.

## Limites conhecidos

- Cada verificação recompila o root inteiro (sem compilação incremental — o mesmo compromisso do `sukoWatch`).
- Se o ficheiro que declara um componente não faz parse, os ficheiros que o chamam mostram `IMPORT_NOT_FOUND`/`COMPONENT_NOT_FOUND` enquanto se escreve (igual ao build).
- Só documentos `file:`; ficheiros por gravar (`untitled:`) são ignorados.
- Uma exceção num pedido vira log (`window/logMessage` e stderr) e resposta vazia; o server não cai.
