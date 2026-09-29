import * as fs from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { runTests } from '@vscode/test-electron';

/** Cria um projecto Suko descartável e abre-o num VS Code real com a extensão carregada. */
async function main(): Promise<void> {
  const workspace = fs.mkdtempSync(path.join(os.tmpdir(), 'suko-e2e-'));
  const root = path.join(workspace, 'src', 'main', 'suko');
  fs.mkdirSync(path.join(root, 'ui'), { recursive: true });
  fs.writeFileSync(
    path.join(root, 'ui', 'Card.sk'),
    'package ui;\n\npublic component Card(String title, Component header) {\n  <div>${title}${header}</div>\n}\n',
  );
  fs.writeFileSync(
    path.join(root, 'Page.sk'),
    'import ui.Card;\n\ncomponent Page() {\n  Card(titel = "x")\n}\n',
  );

  try {
    await runTests({
      // a extensão (dist/extension.js, server/suko-lsp.jar) tem de estar construída: ver o workflow / `npm run test:electron`
      extensionDevelopmentPath: path.resolve(__dirname, '..', '..', '..'),
      extensionTestsPath: path.resolve(__dirname, 'suite', 'index'),
      launchArgs: [workspace, '--disable-extensions', '--disable-workspace-trust'],
      extensionTestsEnv: { SUKO_E2E_WORKSPACE: workspace },
    });
  } finally {
    fs.rmSync(workspace, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
