import * as fs from 'fs';
import * as os from 'os';
import * as vscode from 'vscode';
import { LanguageClient, LanguageClientOptions, ServerOptions, State } from 'vscode-languageclient/node';
import { findJava, probeJava, resolveOnPath } from './java';

/** Toda a lógica da linguagem vive no server Java (para o cliente IntelliJ a reutilizar); isto é só o arranque. */
let client: LanguageClient | undefined;
let output: vscode.LogOutputChannel;
let traceOutput: vscode.LogOutputChannel;

export interface SukoApi {
  /** O cliente actual (para os testes); `undefined` se o server não arrancou. */
  getClient(): LanguageClient | undefined;
}

export async function activate(context: vscode.ExtensionContext): Promise<SukoApi> {
  output = vscode.window.createOutputChannel('Suko Language Server', { log: true });
  traceOutput = vscode.window.createOutputChannel('Suko Language Server (trace)', { log: true });
  context.subscriptions.push(
    output,
    traceOutput,
    vscode.commands.registerCommand('suko.restartServer', () => restart(context)),
    vscode.workspace.onDidChangeConfiguration((event) => {
      if (event.affectsConfiguration('suko.java.home') || event.affectsConfiguration('suko.sourceRoot')) {
        void restart(context);
      }
    }),
  );
  await start(context);
  return { getClient: () => client };
}

export async function deactivate(): Promise<void> {
  await stop();
}

async function restart(context: vscode.ExtensionContext): Promise<void> {
  output.appendLine('A reiniciar o language server…');
  await stop();
  await start(context);
}

async function stop(): Promise<void> {
  const current = client;
  client = undefined;
  if (current && current.state !== State.Stopped) {
    try {
      await current.stop();
    } catch (error) {
      output.appendLine(`Falha ao parar o server: ${String(error)}`);
    }
  }
}

async function start(context: vscode.ExtensionContext): Promise<void> {
  const config = vscode.workspace.getConfiguration('suko');

  const java = await findJava(config.get<string>('java.home', ''), process.env, process.platform, (command) =>
    // `java` do PATH resolve-se para um caminho absoluto antes de ser executado
    probeJava(command === 'java' ? resolveOnPath('java', process.env, process.platform) ?? command : command));
  if (!java.ok) {
    output.appendLine(java.message);
    const openSettings = 'Abrir definições';
    const choice = await vscode.window.showErrorMessage(java.message, openSettings);
    if (choice === openSettings) {
      await vscode.commands.executeCommand('workbench.action.openSettings', 'suko.java.home');
    }
    return;
  }
  output.appendLine(`Java ${java.java.major} (${java.java.source}): ${java.java.command}`);

  // SUKO_LSP_JAR: só para desenvolver o server sem reempacotar a extensão.
  const serverJar = process.env.SUKO_LSP_JAR || context.asAbsolutePath('server/suko-lsp.jar');
  if (!fs.existsSync(serverJar)) {
    const message = `Não encontrei o language server (${serverJar}). Reinstale a extensão, ou, num clone do repositório, corra "npm run copy-server".`;
    output.appendLine(message);
    void vscode.window.showErrorMessage(message);
    return;
  }

  const command = java.java.command === 'java'
    ? resolveOnPath('java', process.env, process.platform) ?? java.java.command
    : java.java.command;
  // cwd fora do workspace: nada do que lá esteja é encontrado em vez do Java
  const serverOptions: ServerOptions = { command, args: ['-jar', serverJar], options: { cwd: os.homedir() } };
  const clientOptions: LanguageClientOptions = {
    documentSelector: [{ scheme: 'file', language: 'suko' }],
    outputChannel: output,
    traceOutputChannel: traceOutput,
    initializationOptions: { sourceRoot: config.get<string>('sourceRoot', '') },
    synchronize: {
      // O server só relê do disco o que o editor não tem aberto: precisa de saber quando
      // ficheiros .sk ou o suko.json mudam fora dele (git checkout, suko add, ...).
      fileEvents: [
        vscode.workspace.createFileSystemWatcher('**/*.sk'),
        vscode.workspace.createFileSystemWatcher('**/suko.json'),
      ],
      configurationSection: 'suko',
    },
  };

  client = new LanguageClient('suko', 'Suko Language Server', serverOptions, clientOptions);
  try {
    await client.start();
  } catch (error) {
    output.appendLine(`O language server não arrancou: ${String(error)}`);
    void vscode.window.showErrorMessage(`O language server do Suko não arrancou: ${String(error)}`);
  }
}
