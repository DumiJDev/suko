// Copia o fat jar do language server para editors/vscode/server/suko-lsp.jar, onde a extensão o procura.
// Corre `./gradlew :suko-lsp:fatJar` primeiro (incremental, é barato); SUKO_SKIP_GRADLE=1 salta esse passo
// (o CI constrói o jar num passo à parte).
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, readdirSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..');
const libs = join(repoRoot, 'suko-lsp', 'build', 'libs');
const target = resolve(here, '..', 'server', 'suko-lsp.jar');

if (!process.env.SUKO_SKIP_GRADLE) {
  const gradlew = join(repoRoot, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  const result = spawnSync(gradlew, [':suko-lsp:fatJar', '--console=plain', '-q'], {
    cwd: repoRoot,
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });
  if (result.status !== 0) {
    console.error('copy-server: `gradlew :suko-lsp:fatJar` falhou.');
    process.exit(result.status ?? 1);
  }
}

const jars = existsSync(libs)
  ? readdirSync(libs).filter((name) => name.endsWith('-all.jar')).map((name) => join(libs, name))
  : [];
if (jars.length === 0) {
  console.error(`copy-server: nenhum *-all.jar em ${libs}. Corra "./gradlew :suko-lsp:fatJar".`);
  process.exit(1);
}
jars.sort((a, b) => statSync(b).mtimeMs - statSync(a).mtimeMs);

mkdirSync(dirname(target), { recursive: true });
copyFileSync(jars[0], target);
console.log(`copy-server: ${jars[0]} -> ${target}`);
