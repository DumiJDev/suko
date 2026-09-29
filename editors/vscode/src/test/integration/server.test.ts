import { strict as assert } from 'node:assert';
import { ChildProcess, spawn } from 'node:child_process';
import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { after, before, describe, it } from 'node:test';
import { pathToFileURL } from 'node:url';
import {
  createProtocolConnection,
  DefinitionRequest,
  Diagnostic,
  DidOpenTextDocumentNotification,
  ExitNotification,
  InitializeRequest,
  InitializedNotification,
  ProtocolConnection,
  PublishDiagnosticsNotification,
  ShutdownRequest,
  StreamMessageReader,
  StreamMessageWriter,
} from 'vscode-languageserver-protocol/node';
import { findJava, probeJava } from '../../java';

/**
 * O jar que a extensão embute, falado por um cliente LSP que não é o LSP4J
 * (o de referência do VS Code): enquadramento das mensagens, capabilities e
 * o caminho completo de diagnóstico e go-to-definition. Não precisa de um VS Code.
 */
const jar = path.resolve(__dirname, '..', '..', '..', 'server', 'suko-lsp.jar');

// Fora do CI, sem jar o teste salta (desenvolvimento local sem Gradle); no CI é uma falha —
// um teste de integração verde por ter sido saltado não prova nada.
const skip = !fs.existsSync(jar) && !process.env.CI && 'server/suko-lsp.jar não existe (corra npm run copy-server)';

describe('suko-lsp.jar por stdio', { skip }, () => {
  let workspace: string;
  let child: ChildProcess;
  let connection: ProtocolConnection;
  const diagnostics = new Map<string, Diagnostic[]>();
  const waiting: Array<() => void> = [];

  const uri = (relative: string) => pathToFileURL(path.join(workspace, 'src', 'main', 'suko', relative)).toString();

  async function untilDiagnostics(target: string, predicate: (d: Diagnostic[]) => boolean): Promise<Diagnostic[]> {
    const deadline = Date.now() + 20_000;
    for (;;) {
      const current = diagnostics.get(target);
      if (current && predicate(current)) {
        return current;
      }
      if (Date.now() > deadline) {
        assert.fail(`sem diagnósticos esperados para ${target}: ${JSON.stringify(current)}`);
      }
      await new Promise<void>((resolve) => {
        waiting.push(resolve);
        setTimeout(resolve, 200);
      });
    }
  }

  before(async () => {
    assert.ok(fs.existsSync(jar), `server/suko-lsp.jar não existe (${jar})`);
    const java = await findJava('', process.env, process.platform, probeJava);
    assert.ok(java.ok, java.ok ? '' : java.message);

    workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'suko-ws-'));
    const root = path.join(workspace, 'src', 'main', 'suko');
    fs.mkdirSync(path.join(root, 'ui'), { recursive: true });
    fs.writeFileSync(
      path.join(root, 'ui', 'Card.sk'),
      'package ui;\n\npublic component Card(String title, Component header) {\n  <div>${title}${header}</div>\n}\n',
    );

    child = spawn(java.java.command, ['-jar', jar], { stdio: ['pipe', 'pipe', 'pipe'] });
    connection = createProtocolConnection(new StreamMessageReader(child.stdout!), new StreamMessageWriter(child.stdin!));
    connection.onNotification(PublishDiagnosticsNotification.type, (params) => {
      diagnostics.set(params.uri, params.diagnostics);
      waiting.splice(0).forEach((wake) => wake());
    });
    connection.listen();

    const initialized = await connection.sendRequest(InitializeRequest.type, {
      processId: process.pid,
      rootUri: pathToFileURL(workspace).toString(),
      workspaceFolders: [{ uri: pathToFileURL(workspace).toString(), name: 'ws' }],
      capabilities: {},
    });
    assert.equal(initialized.serverInfo?.name, 'suko-lsp');
    assert.ok(initialized.capabilities.definitionProvider);
    assert.ok(initialized.capabilities.hoverProvider);
    assert.ok(initialized.capabilities.completionProvider);
    await connection.sendNotification(InitializedNotification.type, {});
  });

  after(async () => {
    try {
      await connection.sendRequest(ShutdownRequest.type);
      await connection.sendNotification(ExitNotification.type);
    } finally {
      connection.dispose();
      child.kill();
      fs.rmSync(workspace, { recursive: true, force: true });
    }
  });

  it('publica os diagnósticos do compilador para um .sk com erro e limpa-os ao corrigir', async () => {
    const page = uri('Page.sk');
    const broken = 'import ui.Card;\n\ncomponent Page() {\n  Card(titel = "x")\n}\n';
    await connection.sendNotification(DidOpenTextDocumentNotification.type, {
      textDocument: { uri: page, languageId: 'suko', version: 1, text: broken },
    });

    const found = await untilDiagnostics(page, (d) => d.length > 0);
    const codes = found.map((d) => d.code);
    assert.ok(codes.includes('PARAM_NOT_FOUND'), JSON.stringify(found));
    assert.ok(codes.includes('REQUIRED_SLOT_MISSING'), JSON.stringify(found));
    const param = found.find((d) => d.code === 'PARAM_NOT_FOUND')!;
    assert.match(typeof param.message === 'string' ? param.message : param.message.value, /quis dizer 'title'/);
    assert.equal(param.range.start.line, 3);
    assert.equal(param.range.start.character, 7); // `  Card(` => o nome do argumento

    const fixed = 'import ui.Card;\n\ncomponent Page() {\n  Card(title = "x") {\n    header { <b>h</b> }\n  }\n}\n';
    await connection.sendNotification('textDocument/didChange', {
      textDocument: { uri: page, version: 2 },
      contentChanges: [{ text: fixed }],
    });
    await untilDiagnostics(page, (d) => d.length === 0);
  });

  it('go-to-definition salta de uma chamada para a declaração noutro ficheiro', async () => {
    const page = uri('Def.sk');
    const text = 'import ui.Card;\n\ncomponent Def() {\n  Card(title = "x") {\n    header { <b>h</b> }\n  }\n}\n';
    await connection.sendNotification(DidOpenTextDocumentNotification.type, {
      textDocument: { uri: page, languageId: 'suko', version: 1, text },
    });

    const result = await connection.sendRequest(DefinitionRequest.type, {
      textDocument: { uri: page },
      position: { line: 3, character: 3 },
    });

    const locations = Array.isArray(result) ? result : result ? [result] : [];
    assert.equal(locations.length, 1);
    const target = locations[0] as { uri: string; range: { start: { line: number } } };
    assert.equal(target.uri, uri('ui/Card.sk'));
    assert.equal(target.range.start.line, 2);
  });
});
