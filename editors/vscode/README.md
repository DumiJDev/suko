# Suko para VS Code

Suporte da linguagem [Suko](https://github.com/DumiJDev/suko) (`.sk`) no VS Code:

- **Destaque de sintaxe** — componentes, slots, tags, `${...}` e `$ident` em strings.
- **Diagnósticos em tempo real** — os mesmos erros e avisos do `sukoCompile`, enquanto escreve.
- **Go to Definition** — de uma chamada, de um `import`, de um argumento (`title = ...`) ou de um slot (`header { ... }`) para a declaração.
- **Hover** — assinatura do componente, slots e cardinalidade, visibilidade, package e ficheiro.
- **Completion** — componentes visíveis, parâmetros ainda não passados, slots e `import` de componentes `public` (aceitar um sugere e acrescenta o `import`).

Toda a lógica vive num language server Java embutido na extensão (`server/suko-lsp.jar`); a extensão só o arranca.

## Requisitos

**Java 21 ou superior** para correr o server. É procurado por esta ordem: a setting `suko.java.home`, a variável `JAVA_HOME` e o `java` do `PATH`. Se faltar ou for mais antigo, a extensão diz que versão encontrou e oferece abrir a setting.

## Projeto

O source root (a pasta dos `.sk`) é descoberto por pasta do workspace: a chave `sourceRoot` do `suko.json` (criado por `suko init`); senão `src/main/suko`; senão a setting `suko.sourceRoot`.

## Settings

| Setting | Descrição |
|---|---|
| `suko.java.home` | JDK/JRE 21+ para o server. Vazio = `JAVA_HOME`, depois `PATH`. |
| `suko.sourceRoot` | Pasta dos `.sk`, relativa ao workspace, quando não há `suko.json` nem `src/main/suko`. |
| `suko.trace.server` | `off`, `messages` ou `verbose`: regista o tráfego LSP no canal "Suko Language Server (trace)". |

**Workspaces não confiáveis:** o server não arranca (a extensão executaria o Java indicado pelas settings do workspace); `suko.java.home` só vale nas settings de utilizador/máquina.

Comando: **Suko: Restart Language Server**. Os logs do server estão no canal de output "Suko Language Server".

## Instalar o `.vsix`

O workflow `build-vscode-extension.yml` gera o `.vsix` como artefacto (ainda não está publicado no Marketplace nem no Open VSX):

```sh
code --install-extension suko-vscode-<versão>.vsix
```

## Desenvolvimento

```sh
cd editors/vscode
npm ci
npm test               # copia o jar; testes unitários, de integração com o server real e snapshots da gramática
npm run package        # ./gradlew :suko-lsp:fatJar, copia o jar para server/ e gera o .vsix
npm run test:electron  # teste de fumo num VS Code real (descarrega o VS Code)
```

Para desenvolver o server sem reempacotar, aponte `SUKO_LSP_JAR` para o `suko-lsp-*-all.jar` antes de abrir o VS Code.

Licença: Apache-2.0.
