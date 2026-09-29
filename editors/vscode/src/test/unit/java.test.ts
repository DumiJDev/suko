import { strict as assert } from 'node:assert';
import { describe, it } from 'node:test';
import * as path from 'node:path';
import { candidates, findJava, parseJavaMajor, Probe } from '../../java';

const OPENJDK_21 = 'openjdk version "21.0.2" 2024-01-16\nOpenJDK Runtime Environment (build 21.0.2+13)\n';
const JAVA_17 = 'openjdk version "17.0.9" 2023-10-17\n';
const JAVA_8 = 'java version "1.8.0_392"\n';

/** Um `java -version` falso por comando: `undefined` = não arranca. */
const probeWith = (table: Record<string, string | undefined>): Probe => async (command) => table[command];

describe('parseJavaMajor', () => {
  it('lê as formas de versão conhecidas', () => {
    assert.equal(parseJavaMajor(OPENJDK_21), 21);
    assert.equal(parseJavaMajor(JAVA_17), 17);
    assert.equal(parseJavaMajor(JAVA_8), 8);
    assert.equal(parseJavaMajor('openjdk version "21-ea" 2023-09-19'), 21);
    assert.equal(parseJavaMajor('openjdk version "25" 2025-09-16'), 25);
    assert.equal(parseJavaMajor('java version "9.0.4"'), 9);
  });

  it('devolve undefined para o que não é uma versão', () => {
    assert.equal(parseJavaMajor(''), undefined);
    assert.equal(parseJavaMajor('command not found'), undefined);
  });
});

describe('candidates', () => {
  it('a setting, se definida, é o único candidato', () => {
    const list = candidates('/opt/jdk21', { JAVA_HOME: '/other' }, 'linux');
    assert.deepEqual(list, [{ source: 'suko.java.home', command: path.join('/opt/jdk21', 'bin', 'java') }]);
  });

  it('sem setting: JAVA_HOME e depois o PATH', () => {
    const list = candidates('  ', { JAVA_HOME: '/usr/lib/jvm/x' }, 'linux');
    assert.deepEqual(list.map((c) => c.source), ['JAVA_HOME', 'PATH']);
    assert.equal(list[1].command, 'java');
  });

  it('sem JAVA_HOME só há o PATH; no Windows o executável é java.exe', () => {
    assert.deepEqual(candidates('', {}, 'linux').map((c) => c.source), ['PATH']);
    assert.ok(candidates('C:\\jdk', {}, 'win32')[0].command.endsWith('java.exe'));
  });
});

describe('findJava', () => {
  const home = path.join('/jvm/21', 'bin', 'java');

  it('usa o primeiro candidato com Java 21+', async () => {
    const result = await findJava('', { JAVA_HOME: '/jvm/21' }, 'linux', probeWith({ [home]: OPENJDK_21, java: JAVA_17 }));
    assert.ok(result.ok);
    assert.equal(result.java.source, 'JAVA_HOME');
    assert.equal(result.java.major, 21);
  });

  it('salta um JAVA_HOME velho e usa o java do PATH se for 21+', async () => {
    const old = path.join('/jvm/17', 'bin', 'java');
    const result = await findJava('', { JAVA_HOME: '/jvm/17' }, 'linux', probeWith({ [old]: JAVA_17, java: OPENJDK_21 }));
    assert.ok(result.ok);
    assert.equal(result.java.source, 'PATH');
  });

  it('a setting não cai para os outros: se a escolhida é velha, é erro', async () => {
    const chosen = path.join('/jvm/17', 'bin', 'java');
    const result = await findJava('/jvm/17', { JAVA_HOME: '/jvm/21' }, 'linux', probeWith({ [chosen]: JAVA_17, [home]: OPENJDK_21, java: OPENJDK_21 }));
    assert.ok(!result.ok);
    assert.equal(result.found?.major, 17);
  });

  it('java velho: a mensagem diz a versão encontrada e onde', async () => {
    const result = await findJava('', {}, 'linux', probeWith({ java: JAVA_17 }));
    assert.ok(!result.ok);
    assert.match(result.message, /Java 21 ou superior, mas encontrei Java 17/);
    assert.match(result.message, /`java` no PATH/);
    assert.match(result.message, /suko\.java\.home/);
  });

  it('entre vários velhos, reporta o mais recente', async () => {
    const result = await findJava('', { JAVA_HOME: '/jvm/8' }, 'linux', probeWith({ [path.join('/jvm/8', 'bin', 'java')]: JAVA_8, java: JAVA_17 }));
    assert.ok(!result.ok);
    assert.equal(result.found?.major, 17);
  });

  it('sem Java: diz onde procurou', async () => {
    const result = await findJava('', { JAVA_HOME: '/jvm/none' }, 'linux', probeWith({}));
    assert.ok(!result.ok);
    assert.match(result.message, /Não encontrei Java/);
    assert.match(result.message, /JAVA_HOME/);
    assert.equal(result.found, undefined);
  });

  it('output ilegível não é confundido com "sem Java"', async () => {
    const result = await findJava('', {}, 'linux', probeWith({ java: 'wrapper script says hi' }));
    assert.ok(!result.ok);
    assert.match(result.message, /Não consegui ler a versão/);
  });
});
