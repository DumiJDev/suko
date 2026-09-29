import { execFile } from 'child_process';
import * as path from 'path';

/** O language server corre em Java 21+ (o piso do monorepo, `options.release = 21`). */
export const MIN_JAVA = 21;

export type JavaSource = 'suko.java.home' | 'JAVA_HOME' | 'PATH';

export interface JavaCandidate {
  source: JavaSource;
  command: string;
}

export interface JavaFound extends JavaCandidate {
  major: number;
}

export type JavaResult =
  | { ok: true; java: JavaFound }
  | { ok: false; message: string; found?: JavaFound };

/** Corre `<command> -version` e devolve o texto (o Java escreve-o no stderr), ou `undefined` se não arranca. */
export type Probe = (command: string) => Promise<string | undefined>;

/** `openjdk version "21.0.2" 2024-01-16` → 21; `"1.8.0_392"` → 8; `"17"` → 17; `"21-ea"` → 21. */
export function parseJavaMajor(output: string): number | undefined {
  const match = /version\s+"(\d+)(?:\.(\d+))?/.exec(output);
  if (!match) {
    return undefined;
  }
  const first = Number(match[1]);
  // Até ao Java 8 a versão escrevia-se 1.x
  return first === 1 && match[2] !== undefined ? Number(match[2]) : first;
}

/** Ordem de procura: a setting (se definida, é a única — o utilizador escolheu), `JAVA_HOME`, `java` no `PATH`. */
export function candidates(setting: string, env: NodeJS.ProcessEnv, platform: NodeJS.Platform): JavaCandidate[] {
  const exe = platform === 'win32' ? 'java.exe' : 'java';
  const fromHome = (home: string) => path.join(home, 'bin', exe);
  const configured = setting.trim();
  if (configured) {
    return [{ source: 'suko.java.home', command: fromHome(configured) }];
  }
  const result: JavaCandidate[] = [];
  const javaHome = (env.JAVA_HOME ?? '').trim();
  if (javaHome) {
    result.push({ source: 'JAVA_HOME', command: fromHome(javaHome) });
  }
  result.push({ source: 'PATH', command: 'java' });
  return result;
}

export async function findJava(
  setting: string,
  env: NodeJS.ProcessEnv,
  platform: NodeJS.Platform,
  probe: Probe,
): Promise<JavaResult> {
  let oldest: JavaFound | undefined;
  let unreadable: JavaCandidate | undefined;
  const tried = candidates(setting, env, platform);

  for (const candidate of tried) {
    const output = await probe(candidate.command);
    if (output === undefined) {
      continue;
    }
    const major = parseJavaMajor(output);
    if (major === undefined) {
      unreadable ??= candidate;
      continue;
    }
    const found: JavaFound = { ...candidate, major };
    if (major >= MIN_JAVA) {
      return { ok: true, java: found };
    }
    // guarda o mais recente dos que são velhos demais, para a mensagem
    if (!oldest || major > oldest.major) {
      oldest = found;
    }
  }

  const hint = `Instale um JDK ${MIN_JAVA}+ ou aponte "suko.java.home" para um.`;
  if (oldest) {
    return {
      ok: false,
      found: oldest,
      message: `O language server do Suko precisa de Java ${MIN_JAVA} ou superior, mas encontrei Java ${oldest.major} ` +
        `(${describe(oldest)}). ${hint}`,
    };
  }
  if (unreadable) {
    return {
      ok: false,
      message: `Não consegui ler a versão do Java em ${describe(unreadable)}. ${hint}`,
    };
  }
  const where = tried.map((t) => describe(t)).join(', ');
  return {
    ok: false,
    message: `Não encontrei Java (procurei em: ${where}). O language server do Suko precisa de Java ${MIN_JAVA} ou superior. ${hint}`,
  };
}

function describe(candidate: JavaCandidate): string {
  return candidate.source === 'PATH' ? '`java` no PATH' : `${candidate.source}: ${candidate.command}`;
}

export const probeJava: Probe = (command) =>
  new Promise((resolve) => {
    execFile(command, ['-version'], { timeout: 10_000 }, (error, stdout, stderr) => {
      // `java -version` sai com 0 e escreve no stderr; um erro aqui é "não arranca"
      resolve(error ? undefined : `${stderr}\n${stdout}`);
    });
  });
