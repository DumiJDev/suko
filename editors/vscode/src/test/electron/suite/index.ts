import * as path from 'node:path';
import * as vscode from 'vscode';
import { State } from 'vscode-languageclient/node';
import type { SukoApi } from '../../../extension';

/** Runner mínimo (sem mocha): cada passo é uma função assíncrona; o primeiro que falhar falha a corrida. */
export async function run(): Promise<void> {
  const workspace = process.env.SUKO_E2E_WORKSPACE!;
  const pageUri = vscode.Uri.file(path.join(workspace, 'src', 'main', 'suko', 'Page.sk'));

  await step('a extensão ativa-se e o language server arranca', async () => {
    const extension = vscode.extensions.getExtension<SukoApi>('suko.suko-vscode');
    assert(extension, 'extensão suko.suko-vscode não encontrada');
    const api = await extension.activate();
    await waitFor('cliente em execução', () => api.getClient()?.state === State.Running);
  });

  await step('um .sk com erro produz diagnósticos do compilador', async () => {
    const document = await vscode.workspace.openTextDocument(pageUri);
    await vscode.window.showTextDocument(document);
    const diagnostics = await waitFor('diagnósticos', () => {
      const found = vscode.languages.getDiagnostics(pageUri);
      return found.length > 0 ? found : undefined;
    });
    const codes = diagnostics.map((d) => (typeof d.code === 'object' ? String(d.code.value) : String(d.code)));
    assert(codes.includes('PARAM_NOT_FOUND'), `PARAM_NOT_FOUND em falta: ${codes.join(', ')}`);
    assert(diagnostics.every((d) => d.source === 'suko'), 'os diagnósticos devem vir de "suko"');
  });

  await step('go-to-definition salta para a declaração noutro ficheiro', async () => {
    const locations = await waitFor('definição', async () => {
      const result = await vscode.commands.executeCommand<vscode.Location[]>(
        'vscode.executeDefinitionProvider', pageUri, new vscode.Position(3, 3));
      return result && result.length > 0 ? result : undefined;
    });
    assert(locations[0].uri.fsPath.endsWith(path.join('ui', 'Card.sk')), `destino inesperado: ${locations[0].uri.fsPath}`);
  });

  await step('hover mostra a assinatura do componente', async () => {
    const hovers = await waitFor('hover', async () => {
      const result = await vscode.commands.executeCommand<vscode.Hover[]>(
        'vscode.executeHoverProvider', pageUri, new vscode.Position(3, 3));
      return result && result.length > 0 ? result : undefined;
    });
    const text = hovers.flatMap((h) => h.contents).map((c) => (typeof c === 'string' ? c : c.value)).join('\n');
    assert(text.includes('component Card('), `hover sem assinatura: ${text}`);
  });

  await step('completion sugere os parâmetros ainda não passados', async () => {
    const list = await waitFor('completion', async () => {
      const result = await vscode.commands.executeCommand<vscode.CompletionList>(
        'vscode.executeCompletionItemProvider', pageUri, new vscode.Position(3, 7));
      return result && result.items.length > 0 ? result : undefined;
    });
    const labels = list.items.map((i) => (typeof i.label === 'string' ? i.label : i.label.label));
    assert(labels.includes('header'), `header em falta: ${labels.join(', ')}`);
  });
}

async function step(name: string, body: () => Promise<void>): Promise<void> {
  try {
    await body();
    console.log(`  ok  ${name}`);
  } catch (error) {
    console.error(`  FALHOU  ${name}`);
    throw error;
  }
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

async function waitFor<T>(what: string, probe: () => T | undefined | Promise<T | undefined>, timeoutMs = 45_000): Promise<NonNullable<T>> {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    const value = await probe();
    if (value) {
      return value as NonNullable<T>;
    }
    if (Date.now() > deadline) {
      throw new Error(`tempo esgotado à espera de: ${what}`);
    }
    await new Promise((resolve) => setTimeout(resolve, 250));
  }
}
