# suko-lsp

Language server do Suko (Language Server Protocol, por stdio). Depende só de `suko-core`; a extensão VSCode (`editors/vscode/`) embute o fat jar e o cliente IntelliJ (11b) irá reutilizá-lo.

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

## Limites conhecidos

- Cada verificação recompila o root inteiro (sem compilação incremental — o mesmo compromisso do `sukoWatch`).
- Se o ficheiro que declara um componente não faz parse, os ficheiros que o chamam mostram `IMPORT_NOT_FOUND`/`COMPONENT_NOT_FOUND` enquanto se escreve (igual ao build).
- Só documentos `file:`; ficheiros por gravar (`untitled:`) são ignorados.
- Uma exceção num pedido vira log (`window/logMessage` e stderr) e resposta vazia; o server não cai.
