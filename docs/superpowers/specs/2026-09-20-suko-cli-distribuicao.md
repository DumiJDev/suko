# Suko — Subprojeto 8: CLI de Distribuição (`suko add`)

Data: 2026-09-20

## Contexto

Os subprojetos 1-7 estão concluídos e mergeados em `main` (ver
`ARCHITECTURE.md` → "Roadmap por subprojeto"; o 7 fechou em `c434080`).
O pré-requisito direto deste subprojeto é o 7, que entregou:

- o módulo `suko-registry` — modelo de dados do manifesto
  (`RegistryIndex`/`ComponentManifest`/`ComponentFile`/
  `ExternalRequirement`), leitor/escritor JSON (`RegistryJson`, Gson
  2.11.0 pinado), a interface de transporte `RegistrySource` com a
  implementação de sistema de ficheiros, e o gerador
  (`RegistryGenerator`);
- `suko-components/` como conteúdo puro, com 8 componentes `.sk` reais
  (`Button`, `Input`, `Label`, `Badge`, `Alert`, `Card`, `Field`,
  `Dialog`);
- o manifesto real, gerado a partir dos fontes e commitado
  (`suko-components/registry.json` + `components/*.json`).

A fronteira decidida na spec do 7 é **"o 7 define os dados, o 8 define a
ferramenta"**. Este documento fecha o lado da ferramenta: como a CLI é
construída e distribuída, o que os seus comandos fazem, como a reescrita
de namespace acontece, e como o estado no projeto do consumidor é
registado e reconciliado.

Este documento resulta do scoping arquitetural de 2026-09-20 e das
decisões do utilizador sobre ele (D1-D12 abaixo, todas marcadas como
**decidido**).

### O que muda face à fronteira original do 7

A spec do 7 previa que o 8 fosse **apenas** a ferramenta de cópia. O
scoping deste subprojeto encontrou uma fronteira mal desenhada: depois de
`suko add`, o consumidor não tem forma suportada de **compilar** o que
acabou de instalar (ver "Constrangimentos", C8). O utilizador decidiu
alargar o 8 deliberadamente (D11) para incluir o fecho do plugin Gradle,
porque o objetivo declarado é ter um utilizador real e não apenas
infraestrutura.

Consequência de processo, registada aqui para não ser descoberta na
revisão: **três decisões deste subprojeto reabrem código já mergeado** —
D3 (partir `suko-registry` em dois) e D4 (política de `schemaVersion`)
tocam o subprojeto 7; D11 e D13 tocam o `suko-gradle-plugin` do
subprojeto 4. Isto é intencional e aprovado, não scope creep.

## Constrangimentos que moldam este desenho

Verificados no código real de `suko-registry`/`suko-components`/
`suko-gradle-plugin`, não inferidos da spec do 7.

- **C1 — A superfície de transporte é mínima e já está fixada.**
  `RegistrySource` é `byte[] resolve(String relativePath)` + `String
  base()`. `FileSystemRegistrySource` faz **duas** verificações de
  contenção: lexical (`normalize()` + `startsWith`) e real
  (`toRealPath()`, para o caso de um symlink dentro da base apontar para
  fora). A implementação HTTPS tem de replicar a *intenção* dessa
  fronteira, não o mecanismo — o equivalente HTTP de um symlink que
  escapa à base é um **redirect** para outro host ou scheme.

- **C2 — O `sha256` do manifesto é do ficheiro upstream, ANTES da
  reescrita de namespace.** O ficheiro escrito no disco do consumidor
  tem a linha `package` e as linhas `import` diferentes, logo o seu hash
  **nunca** bate certo com o `files[].sha256` do manifesto. Uma
  comparação ingénua "hash do manifesto vs. hash do ficheiro no disco"
  classifica *todos* os ficheiros como editados localmente no instante
  seguinte à instalação. É o constrangimento central do desenho do
  lockfile (D7).

- **C3 — `files[].target` já inclui o `packageSuffix`.** No manifesto
  real, `field.json` tem `"target": "ui/Field.sk"` e
  `"packageSuffix": "ui"`. O destino é
  `<sourceRoot>/<base do consumidor como pastas>/<target>` — **não**
  `.../<packageSuffix>/<target>`. A fórmula escrita em D2 da spec do 7
  (`<sourceRoot>/<baseDoConsumidor como pastas>/<packageSuffix>/Nome.sk`)
  lê-se facilmente como dupla aplicação do sufixo; esta spec substitui-a.

- **C4 — O layout público do registry é o layout interno do módulo.**
  `files[].path` é `src/main/suko/io/suko/ui/Field.sk` (o
  `sourceRootPrefix` do gerador). A base HTTPS tem portanto de ser
  `https://raw.githubusercontent.com/<owner>/suko/<tag>/suko-components/`.
  Funciona, e é o que torna o fallback local trivialmente equivalente
  (base = o diretório `suko-components/`), mas amarra o URL público à
  estrutura do repo.

- **C5 — `RegistryJson` valida `schemaVersion` por igualdade estrita.**
  `if (found != SUPPORTED_SCHEMA_VERSION) throw`. A spec do 7 justificou
  isto como "recusar um registry do futuro"; o código também recusa o
  **passado**. Uma CLI que suporte o schema 2 não consegue ler um
  registry v1 servido a partir de uma tag antiga — exatamente o que
  acontece com `--registry-ref v0.1.0`. Ver D4.

- **C6 — `dependsOn` é fiável mas não exaustivo.** O gerador deriva-o
  dos imports resolvidos e **ignora em silêncio** (comentário explícito
  no pass 2 de `RegistryGenerator`) qualquer import que não resolva para
  um componente do mesmo registry. Para a biblioteca atual isso é
  inofensivo; a CLI não deve assumir que `dependsOn` cobre tudo o que um
  ficheiro precisa.

- **C7 — Não há hash nem assinatura do `registry.json` nem dos
  `components/*.json`.** Há `sha256` dos `.sk`, e nada mais. A âncora de
  confiança para os documentos JSON é só o TLS.

- **C8 — Nenhum dos plugins de build é utilizável.**
  `suko-gradle-plugin` existe só como classe `Plugin<Project>`: não há
  `gradlePlugin{}` nem `META-INF/gradle-plugins/*.properties`, logo
  `plugins { id(...) }` não funciona. `gradleTestKit()` está declarado em
  `suko-gradle-plugin/build.gradle.kts` mas **nenhum teste usa
  `GradleRunner`** — o `SukoWatchTaskE2ETest` chama o compilador
  diretamente, sem passar pelo Gradle. O `suko-maven-plugin` não produz
  `plugin.xml`. Ou seja: depois de `suko add`, não existe caminho
  suportado para construir o resultado. Ver D11.

- **C9 — `sukoWatch` não foi migrado para o modelo multi-ficheiro**
  (lacuna do subprojeto 5, já registada no `ARCHITECTURE.md`).
  `SukoWatchTask` continua a chamar `new JteCompiler(...).compile()` por
  ficheiro, com `Files.list` não recursivo. Consequência que deixa de ser
  interna a partir deste subprojeto: quem fizer `suko add field` e
  desenvolver em modo watch fica com output plano e **sem resolução
  cross-ficheiro** — o `Field` instalado não encontra o `Label` que a
  própria CLI instalou ao lado. Ver D13.

- **C10 — Não há toolchain Java pinada em lado nenhum.** Nenhum módulo
  declara `sourceCompatibility`/`targetCompatibility`/toolchain; tudo
  assume o JDK ambiente. `suko-maven-plugin/build.gradle.kts` documenta
  que declarar `sourceCompatibility` *por módulo* provoca conflito de
  resolução de variante com `suko-core`. Até hoje isto era inofensivo
  porque nada saía do repo; o fat jar deste subprojeto é o primeiro
  artefacto entregue a terceiros. Ver D12.

- **C11 — `SukoExtension` não tem valores por omissão.** `getSourceDir()`
  e `getOutputDir()` são `Property<String>` sem convenção. Não existe no
  código um `src/main/suko` canónico que a CLI possa assumir — tem de o
  perguntar ou convencioná-lo explicitamente. Ver D6.

- **C12 — `suko-registry` arrasta o compilador inteiro.** Declara
  `api(project(":suko-core"))` porque o gerador expõe `ComponentDecl`/
  `SukoFile`/`ProjectIndex`. Um fat jar da CLI construído sobre o módulo
  tal como está inclui ANTLR 4.13.1 e `gg.jte` para usar quatro records
  e um parser JSON — o que contradiz de frente o racional escrito no
  `ARCHITECTURE.md` e em `suko-registry/build.gradle.kts` ("Gson em vez
  de Jackson: um único jar, sem transitivas — relevante para o fat-jar
  da CLI do subprojeto 8"). Ver D3.

## Decisão central: o 8 entrega o ciclo completo do consumidor

A fronteira deste subprojeto não é "a ferramenta de cópia". É:
**um utilizador externo consegue instalar um componente e construí-lo.**

- Dentro: obter (HTTPS ou local), resolver o grafo, reescrever o
  namespace, escrever no disco, registar no lockfile, reconciliar
  (`diff`/`update`), **e** aplicar o compilador a partir de um build
  Gradle real via `plugins { id(...) }`.
- Fora: qualquer alteração à linguagem, ao AST, ao emitter ou aos
  diagnósticos; o site (subprojeto 9); o plugin Maven.

## D1 — Stack: Java/JVM (**decidido**)

A CLI é Java, no mesmo monorepo Gradle, num módulo novo `suko-cli`.

Racional: o que a CLI faz é ler o modelo que o 7 já modelou e testou. Um
CLI em Go/Rust (binário estático, arranque instantâneo, sem JDK) obrigaria
a **duplicar o modelo do manifesto** numa segunda linguagem, e nada no
build detetaria a divergência entre as duas definições — o
`RegistryGoldenTest` do 7 só cobre a definição Java. Um segundo toolchain
num monorepo Gradle é também um custo permanente por uma ferramenta que
corre uma vez por componente adicionado.

Custo aceite: o utilizador precisa de um JDK — que já precisa, para
compilar `.sk`.

## D2 — Distribuição: fat jar manual + jbang, incluindo o caminho nativo (**decidido**, revisto em 2026-09-20)

- **Fat jar construído por uma task `Jar` própria**, com
  `configurations.runtimeClasspath` desempacotado. **Não** o Shadow
  plugin: evita uma dependência de build nova e não há `META-INF/services`
  para fundir (Gson não os usa). `Zip64` ligado por segurança.
- **jbang** como porta de entrada ergonómica: um catálogo no repo com um
  alias que aponta para o jar de um GitHub Release. Isto **não exige
  montar publicação Maven** — que a spec do 7 (D1) já registou como
  inexistente no repo.
- **Scripts wrapper** `suko`/`suko.bat` mínimos (`java -jar ...`) para
  quem não usa jbang.
- **Tarefa Gradle ad-hoc: recusada.** Acrescentar componentes não é uma
  operação de build; e ligá-la ao `suko-gradle-plugin` acoplaria a
  ferramenta ao problema de C8 pelo lado errado — o plugin é o caminho de
  *compilação*, não o de *instalação*.
- **Binário nativo GraalVM: um único caminho, via jbang, nunca construído
  nem publicado por nós** (segunda revisão desta decisão, 2026-09-20 —
  ver "Verificação empírica" abaixo). A primeira revisão tinha aceitado o
  native-image como artefacto adicional de release, construído por uma
  tarefa Gradle `Exec` e publicado por plataforma; **essa tarefa Gradle
  foi removida**. O binário deixa de ser algo que o projeto constrói: é o
  próprio `jbang`, já instalado e usado pelo consumidor para correr o fat
  jar, que o compila localmente com `jbang --native`, usando o
  `native-image` que o consumidor já tenha (via `GRAALVM_HOME`/`PATH`), e
  o guarda em cache para reutilização. Isto **une** os dois caminhos de
  empacotamento em vez de os tratar como artefactos separados: não há
  binários por plataforma para gerar, assinar ou publicar; há um único
  alias jbang, e o binário nativo é uma forma opcional e local de o correr.

Fora do v1, declarados como evolução: Homebrew, Scoop.

### Verificação empírica do mecanismo (2026-09-20)

Antes desta decisão, confirmou-se experimentalmente, fora do repo, que
`jbang --native <alias>` aceita um alias de catálogo que aponta para um
**jar já construído** (não um script jbang de fonte única) com `-m
<MainClass>` — exatamente a forma de `suko-cli`. Passos e evidência:

1. Um jar mínimo com `Main-Class` no manifesto, registado como alias
   (`jbang alias add --name x -m Hello ./hello.jar`), correu normalmente
   com `jbang x` — a via não-nativa já era conhecida por funcionar.
2. `jbang --native x`, com GraalVM CE 25.3.4.1 instalado localmente e
   `GRAALVM_HOME`/`PATH` apontados para ele, **invocou `native-image`
   diretamente sobre o jar do alias** (confirmado com `--verbose`: o
   comando construído foi `native-image ... -jar .../hello.jar
   .../hello.jar.bin`) e produziu um binário ELF real, que correu e
   devolveu o output esperado.
3. Um segundo jar com uma classe só alcançável por reflexão, empacotado
   com `META-INF/native-image/.../reflect-config.json` **dentro do
   próprio jar**, provou que o `native-image` invocado por baixo do
   `jbang` lê essa metadata embutida sem nenhuma flag extra — exatamente
   o que a Tarefa 13 precisa do passo do `native-image-agent`.
4. **Bug real encontrado, e já registado a montante:** a primeira
   tentativa (em ambos os casos acima) falhou com
   `Error: Writing image to non-existent directory
   /home/.../.jbang/cache/jars/<jar>.e3b0c44...(hash da string vazia)
   is not allowed`. Isto **não é uma rejeição do mecanismo** — é um bug
   de `jbang` (reproduzido de forma determinística, em jbang 0.138.0) ao
   pré-criar o diretório de cache de saída quando o alvo é um jar
   pré-construído em vez de compilado a partir de fontes. É exatamente o
   bug descrito, com o mesmo hash e a mesma mensagem, em
   [jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623)
   ("fix: jbang build --native from existing/pre-built jar"), **aberto e
   ainda não integrado** à data desta verificação. **Contorno confirmado
   e sem custo:** passar `--build-dir <diretório>` explicitamente (ex.:
   `jbang --native --build-dir ~/.suko/native suko@<owner>`) evita por
   completo o cálculo de diretório com bug, porque o destino passa a ser
   o diretório indicado em vez do cache interno. Com este contorno, tanto
   `jbang build --native --build-dir ...` como `jbang --native
   --build-dir ...` (que compila e corre numa só invocação) funcionaram
   de forma limpa e repetível, incluindo reutilização em cache (a segunda
   invocação com o mesmo `--build-dir` correu em ~1s, não recompilou).

**Decisão consequente:** documentar `--build-dir` como parte obrigatória
da instrução ao consumidor (não é opcional nem cosmético — sem ele, a
primeira compilação nativa falha em jbang 0.138.x). Quando a correção de
jbang#2623 for lançada numa versão futura, `--build-dir` deixa de ser
necessário para contornar o bug mas continua a ser útil por dar um
destino previsível ao binário; a instrução não precisa de ser revista
nessa altura.

### O que o binário nativo obriga

O Gson desserializa os records do manifesto **por reflexão**, e
`RegistryJson.requireFields` percorre `getRecordComponents()` **em
runtime** (ver D4). Nenhuma das duas coisas sobrevive à análise estática
do native-image sem configuração explícita — isto não muda com a decisão
acima: quem lê `reflect-config.json` continua a ser o `native-image`,
apenas invocado por outro processo (`jbang`, no computador do
consumidor, em vez de uma tarefa `Exec` no nosso build).

- A configuração (`reflect-config.json`) é **gerada pelo
  `native-image-agent`** a partir de uma execução real e **commitada** em
  `META-INF/native-image/` do módulo `suko-cli`, **dentro do fat jar** —
  é aí que o `native-image` invocado pelo `jbang` do consumidor a vai
  encontrar, tal como a verificação empírica acima confirmou.
- O smoke-run que alimenta o agente tem de cobrir **todos** os comandos,
  não só o `add`: o agente só regista o que a execução tocou, e um `add`
  isolado nunca exercita a *leitura* do lockfile nem do `suko.json` — que
  só acontecem do segundo comando em diante. Uma configuração gerada a
  partir de um `add` produz um binário que funciona na primeira
  utilização e falha na segunda, no computador do utilizador, com uma
  exceção de reflexão ilegível.
- **Não há tarefa Gradle que produza ou publique o binário.** O que
  existe, opt-in e à parte de `build`/`check`, é uma verificação de
  desenvolvimento (Tarefa 13) que invoca o mesmo `jbang --native
  --build-dir` contra o fat jar recém-construído, para apanhar uma
  entrada em falta no `reflect-config.json` **antes** de o consumidor a
  descobrir — não para gerar um artefacto a distribuir.
- Qualquer comando novo acrescentado à CLI obriga a **reexecutar o
  agente**. Isto tem de ficar escrito ao lado do ficheiro gerado, senão
  apodrece em silêncio — a falha não aparece na compilação nem no fat
  jar, só no binário e só no caminho não exercitado.

Alternativa registada e não adotada: escrever `TypeAdapter`s explícitos do
Gson eliminaria a reflexão de raiz e tornaria o `reflect-config.json`
desnecessário. É mais robusto a prazo, mas é código a manter em paralelo
ao modelo; fica como caminho seguinte se a configuração gerada se tornar
difícil de manter.

## D3 — Partir `suko-registry` em dois (**decidido** — toca código do 7)

| Módulo | Conteúdo | Depende de |
|---|---|---|
| `suko-registry` | `RegistryIndex`, `ComponentManifest`, `ComponentFile`, `ExternalRequirement`, `RegistryJson`, `RegistryJsonException`, `RegistrySource`, `FileSystemRegistrySource` | **Gson apenas.** Zero dependência de `suko-core`. |
| `suko-registry-generator` (**novo**) | `RegistryGenerator`, `GeneratorConfig`, `GeneratedRegistry`, `RegistryGeneratorException` | `suko-registry` (`api`) + `suko-core` (`api`) |
| `suko-cli` (**novo**) | a CLI | `suko-registry` apenas |

Racional: ver C12. Poupar as transitivas do Jackson enquanto se arrasta o
ANTLR inteiro não é coerente; esta divisão é o que torna verdadeiro o
racional já escrito.

O que isto obriga:

1. `suko-components/build.gradle.kts` passa a declarar
   `testImplementation(project(":suko-registry-generator"))` em vez de
   `testImplementation(project(":suko-registry"))`. O `RegistryGoldenTest`
   importa dos dois conjuntos de tipos (`RegistryGenerator`/
   `GeneratorConfig`/`GeneratedRegistry` do gerador; `ComponentFile`/
   `ComponentManifest`/`ExternalRequirement`/`RegistryJson` do modelo), o
   que a dependência `api` do gerador sobre o modelo resolve sem linha
   extra.
2. `settings.gradle.kts` ganha `suko-registry-generator` e `suko-cli`.
3. **O pacote Java não muda** (`io.suko.registry` em ambos os módulos).
   Renomear pacotes obrigaria a tocar em todos os imports de testes já
   escritos, sem ganho — a separação que interessa é de *grafo de
   dependências*, não de nomes.
4. O comentário sobre Gson no `ARCHITECTURE.md` (secção "Estrutura de
   módulos") e em `suko-registry/build.gradle.kts` é corrigido: hoje
   afirma um benefício que a dependência `api(suko-core)` anulava.

## D4 — `schemaVersion`: aceita `<=`, recusa `>` (**decidido** — toca código do 7)

`RegistryJson.validateSchemaVersion` passa de igualdade estrita para:

- `found > SUPPORTED_SCHEMA_VERSION` → erro legível, nomeando as duas
  versões e dizendo para atualizar a CLI (comportamento atual, mantido);
- `found <= SUPPORTED_SCHEMA_VERSION` → aceite.

Racional: ver C5. A intenção declarada no 7 cobria metade do que o código
fazia. O sintoma só apareceria no dia da primeira bump de schema — altura
em que já haveria lockfiles no mundo a apontar para tags antigas.

Consequência de manutenção, a escrever como comentário no código: a
partir do momento em que existir um schema 2, ler um documento v1 tem de
continuar a produzir um modelo válido. Como o modelo é um `record` com
todos os campos obrigatórios (`RegistryJson.requireFields` verifica-os por
reflexão sobre os `RecordComponent`), **qualquer campo acrescentado no
schema 2 tem de ser opcional na leitura**, ou a compatibilidade para trás
quebra-se na hora. É uma regra para o futuro, não trabalho deste
subprojeto.

## D5 — Pinagem: por tag do registry, nunca por versão de componente (**decidido**)

Não existe índice de versões históricas: `registryVersion` é uma tag git e
o URL base contém a tag (C4). `suko add button@1.2.0` — versão *de
componente* — é portanto **irresolvível** com os dados de hoje; exigiria um
índice "que tag contém que versão de que componente", que o 7 não produziu
e que esta spec não acrescenta.

- A CLI leva um URL base por omissão **já com a tag**, correspondente à
  sua própria versão (CLI `0.2.0` → tag `v0.2.0`).
- `--registry-ref <tag>` troca a tag; `--registry <path|url>` substitui a
  base inteira (é o fallback local de D1 do 7, e o caminho de teste).
- A `version` por componente é informativa e serve o lockfile e o
  `update`. **Nunca** é um seletor.

Racional adicional: isto evita qualquer chamada à API do GitHub para
listar tags — dependência nova, rate limits, e um modo de falha offline
novo.

## D6 — Configuração do consumidor: `suko.json`, criado por `suko init` (**decidido**)

```json
{
  "schemaVersion": 1,
  "sourceRoot": "src/main/suko",
  "basePackage": "com.acme.web",
  "registry": {
    "base": "https://raw.githubusercontent.com/<owner>/suko/v0.2.0/suko-components/",
    "ref": "v0.2.0"
  }
}
```

Racional: a alternativa (flags em todos os comandos) garante que alguém,
um dia, escreve um `--base-package` diferente do da instalação anterior e
fica com metade da árvore em `com.acme.ui` e a outra metade em
`com.acme.web.ui` — e o compilador só se queixa com
`PACKAGE_DIRECTORY_MISMATCH` muito depois, num sítio que não aponta para a
causa. É também o sítio onde o `sourceRoot` vive, que o código não
convenciona em lado nenhum (C11).

- `suko init` é interativo com valores por omissão (`src/main/suko`;
  `basePackage` sem default — é perguntado, porque adivinhá-lo a partir da
  estrutura de pastas erraria em silêncio).
- As flags continuam a existir e **sobrepõem-se** ao ficheiro.
- Ausência de `suko.json` num `add` não é erro fatal se todas as opções
  necessárias vierem por flag; sem elas, a mensagem diz para correr
  `suko init`.

## D7 — Lockfile: `suko.lock.json`, dois hashes por ficheiro (**decidido**)

Ficheiro **separado** do `suko.json`: gerado e editado à mão não se
misturam.

```json
{
  "schemaVersion": 1,
  "registry": {
    "base": "https://raw.githubusercontent.com/<owner>/suko/v0.2.0/suko-components/",
    "ref": "v0.2.0",
    "registryVersion": "0.1.0"
  },
  "basePackage": "com.acme.web",
  "sourceRoot": "src/main/suko",
  "components": [
    {
      "name": "field",
      "version": "0.1.0",
      "reason": "direct",
      "files": [
        {
          "target": "com/acme/web/ui/Field.sk",
          "upstreamSha256": "f5e1861e...",
          "localSha256": "9b2c41aa..."
        }
      ]
    },
    {
      "name": "label",
      "version": "0.1.0",
      "reason": "transitive",
      "files": []
    }
  ]
}
```

- **`upstreamSha256`** — o `files[].sha256` do manifesto. Identifica *que
  revisão upstream* está instalada.
- **`localSha256`** — hash dos bytes **efetivamente escritos**, isto é,
  pós-reescrita de namespace. É o único hash que pode ser comparado com o
  ficheiro no disco (C2).
- **`reason`** — `direct` (o utilizador pediu) vs. `transitive` (veio por
  `dependsOn`). Sem isto, o `update` não sabe distinguir "o utilizador quer
  isto atualizado" de "isto só cá está porque outra coisa precisava". Um
  componente instalado como `transitive` e depois pedido explicitamente
  passa a `direct`.
- **Sem timestamps e sem campos derivados da máquina.** Produzem diffs
  espúrios em cada instalação e transformam o lockfile numa fonte
  permanente de conflitos de merge.
- `components` é ordenado alfabeticamente e o JSON é pretty-printed com
  ordem de chaves estável — mesma disciplina do manifesto do 7, pela mesma
  razão (o ficheiro é commitado e revisto).

### Matriz de reconciliação (usada pelo `update` e pelo `add` sobre algo já instalado)

| Ficheiro no disco | Upstream | Ação |
|---|---|---|
| hash == `localSha256` | hash == `upstreamSha256` | no-op |
| hash == `localSha256` | mudou | sobrescreve sem perguntar (é seguro: não havia nada para perder) |
| hash != `localSha256` (editado) | não mudou | não toca; reporta "editado localmente" |
| hash != `localSha256` (editado) | mudou | **conflito**: recusa, sugere `suko diff <nome>`, exige `--force` |
| ficheiro não existe | — | reinstala (o utilizador apagou-o) |

Caso adicional: **o ficheiro de destino já existe e não há entrada no
lockfile** → tratar como propriedade do consumidor e recusar sem
`--force`. Nunca sobrescrever algo que a CLI não sabe ter escrito.

## D8 — Fins de linha: escrever LF, normalizar só na comparação (**decidido**)

- A CLI escreve sempre os bytes com LF, exatamente como vieram do
  upstream. Nunca traduz para os separadores da plataforma.
- Ao **comparar** (calcular o hash de um ficheiro no disco para a matriz
  acima, ou produzir um `diff`), normaliza CRLF para LF antes de fazer
  hash.

Racional: o repo vive num caminho Windows sob WSL, e um consumidor com
`core.autocrlf=true` tem os ficheiros em CRLF no disco mesmo que a CLI os
tenha escrito em LF. Sem a normalização na comparação, o `localSha256`
deixa de bater no computador do colega e **toda a gente vê conflitos
falsos** — o que inutiliza o `update` e destrói a confiança na ferramenta
mais depressa do que qualquer bug funcional.

## D9 — A CLI não verifica compilação no v1 (**decidido**)

Depois de escrever, a CLI **não** invoca `SukoProjectCompiler`.

Racional: (a) obrigaria `suko-cli` a depender de `suko-core`, anulando o
ganho de D3; (b) falharia por erros pré-existentes no projeto do
consumidor que nada têm a ver com o `add`, transformando um `add` bem
sucedido num relatório de erros alheios.

O que a CLI **faz** é validação sintática leve do resultado da reescrita
(ver "Reescrita de namespace"): o pacote resultante é uma sequência de
identificadores válida e concorda com a pasta de destino. Um `suko doctor`
que compila fica para depois.

## D10 — `externalRequirements`: imprimir, nunca editar (**decidido**)

A CLI nunca toca no `tailwind.config.js`, no `package.json`, no HTML de
layout, nem em nenhum ficheiro que não tenha escrito. Depois de instalar,
imprime um resumo dos `externalRequirements` agregados dos componentes
instalados (Tailwind 3.x sempre; Alpine 3.x se instalou `Dialog`) e aponta
para a secção do `README.md` do `suko-components` que explica o `content`
do Tailwind.

Racional: editar configuração de terceiros é exatamente a classe de
comportamento que faz as pessoas desconfiarem de uma ferramenta cuja
proposta é "o código passa a ser teu".

## D11 — O subprojeto 8 fecha o plugin Gradle (**decidido** — alarga o escopo de propósito, toca código do 4)

Ver C8. Sem isto, o 8 entrega ficheiros que ninguém consegue construir por
um caminho suportado.

Entra neste subprojeto:

1. `suko-gradle-plugin` passa a usar o plugin `java-gradle-plugin`, com um
   bloco `gradlePlugin { plugins { create("suko") { id = "io.suko.lang";
   implementationClass = "io.suko.lang.gradle.SukoGradlePlugin" } } }`.
2. `SukoExtension` ganha convenções reais: `sourceDir` = `src/main/suko`,
   `outputDir` = `build/generated-src/suko` (fecha C11 e dá à CLI um
   default defensável para o `suko init`).
3. **Teste funcional com `GradleRunner`** (TestKit), num projeto temporário
   que aplica `plugins { id("io.suko.lang") }`, escreve um `.sk` e corre
   `sukoCompile` — o primeiro teste do repo que passa mesmo pelo Gradle.
   `gradleTestKit()` já está declarado em
   `suko-gradle-plugin/build.gradle.kts` e nunca foi usado.
4. Teste de integração do ciclo completo: `suko add field` contra uma base
   local (`--registry` a apontar para `suko-components/`) num projeto
   temporário, seguido de `sukoCompile` via TestKit, seguido de render com
   `gg.jte` 3.1.12 real. É este teste que prova a tese do subprojeto.

**Não** entra: o `suko-maven-plugin` (`plugin.xml` continua por fazer).
Fica registado como lacuna conhecida, agora assimétrica — o caminho Gradle
é suportado, o caminho Maven não.

**Não** entra: publicação do plugin no Gradle Plugin Portal. O ID
descobrível serve `includeBuild`/composite e os testes TestKit; publicar
exige resolver a questão de publicação que D1 do 7 deixou fora.

## D12 — Java 21 como piso declarado, via `options.release` (**decidido**)

No `build.gradle.kts` da raiz, dentro do `subprojects {}` já existente:
`tasks.withType<JavaCompile>().configureEach { options.release.set(21) }`,
aplicado **uniformemente** a todos os módulos.

Racional: ver C10. O fat jar é o primeiro artefacto entregue a terceiros;
compilado num JDK 25, não arranca num JDK 21 e a mensagem
(`UnsupportedClassVersionError`) não dá qualquer contexto ao utilizador.

**Mecanismo escolhido, e porquê não uma toolchain** (decidido pelo
utilizador em 2026-09-20, depois de o scoping ter proposto a toolchain): o
ambiente é JDK 25 + Gradle 9.5.0, sem `gradlew` e sem CI. Pinar
`languageVersion = 21` sem um JDK 21 instalado exige o plugin
`foojay-resolver-convention` no `settings.gradle.kts` — uma dependência de
build nova e uma descarga de JDK na primeira build limpa. `options.release`
não precisa de nenhuma das duas.

A correção também **não** é `sourceCompatibility`/`targetCompatibility` por
módulo: esses já se provaram provocar conflito de resolução de variante com
`suko-core` (`suko-maven-plugin/build.gradle.kts`, comentário existente),
porque alteram o atributo `org.gradle.jvm.version`. `options.release` não o
altera, e a aplicação uniforme elimina a assimetria que causou o problema
original.

**O que isto garante:** bytecode major 65, e recusa **em tempo de
compilação** de qualquer API introduzida depois do 21 — que é exatamente o
que protege o artefacto entregue.

**O que isto não garante, custo aceite:** os testes continuam a correr no
JDK ambiente (25), não no piso. Comportamento de runtime específico do 21
não é verificado. Se vier a ser preciso, exige uma toolchain ou uma matriz
de CI, e nenhuma das duas existe hoje.

Java 21 passa a ser piso declarado no `ARCHITECTURE.md`, não apenas
assumido.

## D13 — `sukoWatch` migrado para o modelo de projeto (**decidido**)

Ver C9. Este item não estava nas 12 decisões originais; decorre
diretamente de aceitar D11, porque um caminho de build suportado que se
parte em modo watch não é um caminho de build suportado. Confirmado pelo
utilizador em 2026-09-20.

`SukoWatchTask` passa a chamar
`SukoProjectCompiler.compile(sourceRoot)` — **recompilação total do source
root a cada evento**, sem qualquer semântica incremental. Isto contorna por
completo o que o subprojeto 5 deliberadamente adiou (o que reindexar,
quando, e o que fazer quando um ficheiro que outros importam muda): não há
resposta a dar se a resposta for sempre "tudo". O custo é tempo de
recompilação proporcional ao projeto, aceitável para um modo de
desenvolvimento e trivialmente melhorável depois.

Ganho: o output passa a espelhar pacotes (igual ao `sukoCompile`), os 5
diagnósticos de nível de projeto passam a ser vistos em watch, e um `Field`
instalado por `suko add` resolve o `Label` ao lado.

Consequência para o `ARCHITECTURE.md`: a lacuna do `sukoWatch` registada
nas limitações do subprojeto 5 **sai**, e é substituída por uma nota de
que o modo watch recompila o source root inteiro a cada evento — o que é
uma característica de desempenho declarada, não uma limitação escondida.

## Comandos

| Comando | Descrição |
|---|---|
| `suko init` | Cria `suko.json` (interativo, com defaults). |
| `suko list` | Lê `registry.json` e imprime nome, versão, categoria, descrição. |
| `suko add <nome>...` | Instala os componentes e o fecho transitivo de `dependsOn`. |
| `suko diff [<nome>]` | Mostra a diferença entre o ficheiro local e o upstream pós-reescrita. |
| `suko update [<nome>]` | Reaplica a matriz de reconciliação de D7. |

Flags globais: `--registry <path|url>`, `--registry-ref <tag>`,
`--source-root <path>`, `--base-package <pkg>`, `--force`, `--dry-run`,
`--yes`.

Parsing de argumentos **à mão**, sem picocli: são cinco comandos e um
punhado de flags, e o v1 não justifica uma dependência nova num artefacto
cujo peso é uma preocupação declarada (D3).

Fora do v1: `suko remove`, `suko search`, `suko doctor`.

### Pipeline do `add` (a ordem é a mitigação de R1)

1. Ler `suko.json`/flags; validar `basePackage` e `sourceRoot`.
2. Ler `registry.json`; resolver os nomes pedidos; calcular o fecho
   transitivo de `dependsOn`, detetando ciclos defensivamente (o 7 garante
   aciclicidade do registry oficial, mas `--registry` aceita um qualquer).
3. Obter **todos** os manifestos e **todos** os `files[].path` para
   memória. Verificar cada `sha256` contra o conteúdo obtido; divergência
   aborta tudo.
4. Reescrever o namespace de todos os ficheiros, em memória.
5. Calcular destinos e confrontar com o lockfile (matriz de D7). Um único
   conflito não resolvido aborta **antes de escrever seja o que for**.
6. Escrever os ficheiros (criando as pastas necessárias).
7. Escrever o lockfile, por último.
8. Imprimir os `externalRequirements` agregados.

`--dry-run` corre 1-5 e imprime o plano.

## Reescrita de namespace

D2 do subprojeto 7 fixou *que* a reescrita acontece; esta spec fixa *como*.

- **Ancorada por linha, nunca substituição global.** Só linhas que casem
  uma declaração `package` ou `import` cujo alvo comece literalmente pelo
  `manifest.basePackage()` (hoje `io.suko`) são tocadas. Um
  `replace("io.suko", base)` global tocaria em strings, em texto HTML e em
  atributos.
- **Falhar alto, não escrever errado.** Se a linha `package` de um ficheiro
  não começar pelo `basePackage` do manifesto, a CLI aborta com uma
  mensagem que nomeia o ficheiro — não tenta adivinhar.
- **Validar o resultado.** O pacote resultante tem de ser uma sequência de
  identificadores Java válidos (nenhum é palavra-reservada, nenhum começa
  por dígito) e tem de concordar com a pasta de destino, senão o consumidor
  apanha `PACKAGE_DIRECTORY_MISMATCH` do compilador sem perceber porquê.
- **Nada mais é tocado.** Imports que não comecem pelo `basePackage` ficam
  como estão (é o caso de C6).
- O que torna isto mecânico é a convenção 8 da biblioteca do 7 (imports
  totalmente qualificados, sem alias), que lá já é verificada por teste.
- **O `packageSuffix` não é configurável no v1.** O destino é sempre
  `<sourceRoot>/<basePackage do consumidor como pastas>/<target>`, com o
  `target` do manifesto tal e qual (C3). Um `--suffix` é fácil de
  acrescentar depois; no v1 duplicaria a superfície da reescrita sem
  necessidade provada.

## `HttpRegistrySource`

A outra metade de D1 do subprojeto 7. Requisitos, todos derivados de C1 e
C7:

- A base tem de terminar em `/`; a resolução é concatenação simples, com as
  **mesmas** recusas do caminho de ficheiros: caminho absoluto recusado,
  caminho que contenha `..` e escape à base recusado.
- **Exige `https`.** `http` só com `--allow-insecure`, para servidor de
  teste local.
- **Redirects não são seguidos automaticamente** (`Redirect.NEVER`); um
  redirect é reportado como erro, porque um redirect para outro host é o
  equivalente HTTP do symlink que `FileSystemRegistrySource` já bloqueia.
- Timeout de ligação e de leitura; limite máximo de tamanho de resposta;
  `User-Agent` identificável; um único `HttpClient` reutilizado.
- Estado diferente de 200 produz mensagem legível com o URL e o código, não
  uma exceção crua.
- **Sem cache em disco no v1.** O custo é N+1 pedidos por `add` (aceite
  explicitamente na spec do 7); cache só se for medida como necessária, e
  traz invalidação e um modo de falha offline novo.

## Fora de escopo (decisões explícitas desta spec)

- **`suko remove`** — exigiria analisar o código do *consumidor* à procura
  de usos do componente antes de o apagar. Sem isso, é um `rm` com um
  lockfile a fingir que sabe o que está a fazer.
- **`suko search`**, categorias navegáveis, previews — subprojeto 9.
- **Merge a três vias** em conflito. A CLI recusa e mostra o `diff`; o
  utilizador resolve com as ferramentas dele.
- **Publicação Maven de qualquer módulo**, e publicação do plugin no Gradle
  Plugin Portal. Continua a valer D1 do subprojeto 7.
- **Cache HTTP em disco**, Homebrew/Scoop. (O native-image não está nesta
  lista: entra no v1, mas como caminho `jbang --native` do lado do
  consumidor, **nunca** como artefacto construído ou publicado pelo
  projeto — ver D2, revisto em 2026-09-20.)
- **Construir ou publicar binários nativos por plataforma.** É
  deliberadamente do consumidor, via `jbang --native --build-dir`, com o
  `GraalVM` que ele já tenha. O projeto só garante o
  `reflect-config.json` embutido no fat jar; não há CI, não há matriz de
  plataformas, não há artefacto de release para isto.
- **`suko-maven-plugin`** (`plugin.xml`). Ver D11.
- **Alterar `suko-website`** — subprojeto 9.
- **Editar configuração de terceiros** (Tailwind, Alpine, HTML). Ver D10.
- **Um índice de versões históricas do registry.** Ver D5.
- **Qualquer alteração à linguagem, gramática, AST, emitter ou
  diagnósticos.** As únicas exceções são as explicitamente decididas
  acima: D3 e D4 (`suko-registry`, do subprojeto 7), D11 e D13
  (`suko-gradle-plugin`, do subprojeto 4), D12 (build da raiz). Um bug de
  compilador descoberto aqui é **registado, não corrigido** — mesma regra
  D5 do subprojeto 7, pela mesma razão.

## Riscos

- **R1 (alto) — instalação parcial.** Um `add` de um grafo de 3
  componentes que falha a meio deixa a árvore meio-escrita e o lockfile
  inconsistente, e o utilizador sem saber em que estado ficou. Mitigação: o
  pipeline de 8 passos acima — nada é escrito antes de tudo estar obtido,
  verificado, reescrito e confrontado com o lockfile.
- **R2 (alto) — a reescrita de namespace corrompe o ficheiro.** Mitigação:
  reescrita ancorada por linha, prefixo obrigatório, abortar em vez de
  adivinhar, validar o pacote resultante. Ver "Reescrita de namespace".
- **R3 (alto) — conflitos falsos por hashing.** Se o `localSha256` não
  bater por causa de fins de linha (D8) ou por se ter guardado o hash
  errado (C2), o `update` fica inutilizável e ninguém volta a confiar nele.
  Mitigação: D7 (dois hashes) + D8 (normalização na comparação) + teste
  dedicado que instala, lê de volta e confirma o `localSha256`.
- **R4 (médio) — D11 alarga o subprojeto para dentro de um módulo antigo.**
  Fechar o plugin Gradle mexe em `suko-gradle-plugin` (subprojeto 4) e pode
  destapar trabalho não previsto (passar a `java-gradle-plugin` muda a
  forma como o módulo é construído e testado). Mitigação: tarefa isolada e
  precoce no plano, antes de a CLI existir, com critério de paragem — se
  não couber, o plugin vira subprojeto próprio e a CLI segue, documentando
  a lacuna.
- **R5 (médio) — D3 e D4 reabrem código mergeado do 7.** Mitigação: ambas
  são mecânicas e cobertas por testes que já existem (`RegistryJsonTest`,
  `RegistryGeneratorTest`, `RegistryGoldenTest` têm de continuar a passar
  sem alteração de semântica); são as primeiras tarefas do plano, para o
  resto assentar em cima do grafo final.
- **R6 (médio) — segurança do transporte.** Ver `HttpRegistrySource`. O
  `sha256` dos `.sk` é verificado; os documentos JSON não têm hash nem
  assinatura (C7) e dependem só do TLS. Aceite para o v1, registado aqui
  para não ser descoberto como surpresa.
- **R7 (médio) — o binário nativo falha num caminho que o agente não
  observou.** Uma entrada em falta no `reflect-config.json` não parte a
  compilação nem o fat jar: parte só o binário — e agora só no computador
  do consumidor, quando ele correr `jbang --native`, não numa tarefa
  nossa. Mitigação: o smoke-run do agente cobre **todos** os comandos
  (incluindo a segunda invocação, que é a que lê o `suko.json` e o
  lockfile), e uma verificação de desenvolvimento, opt-in, corre o mesmo
  guião contra um binário construído localmente com `jbang --native
  --build-dir` sobre o fat jar da build atual — antes de publicar release,
  não em `build`/`check`. Risco residual: um comando acrescentado depois,
  sem reexecutar o agente — daí a nota obrigatória ao lado do ficheiro
  gerado. Risco residual adicional, aceite: um consumidor que corra
  `jbang --native` **sem** `--build-dir` numa versão de jbang anterior à
  correção de jbangdev/jbang#2623 recebe um erro de "non-existent
  directory" em vez de um binário — mitigado documentando `--build-dir`
  como parte da instrução, não como opção.
- **R10 (baixo) — D12 não verifica runtime no piso.** `options.release`
  garante bytecode 65 e recusa API pós-21 em compilação, mas os testes
  correm no JDK ambiente. Um comportamento que difira entre 21 e 25 em
  runtime não é apanhado. Aceite explicitamente; a alternativa (toolchain
  ou matriz de CI) foi recusada por custo.
- **R8 (baixo) — Tailwind invisível.** O componente instala-se, compila,
  renderiza, e não estiliza porque o `content` do Tailwind não aponta para
  o sítio certo. Já documentado como limitação de linguagem no
  `ARCHITECTURE.md` e no README do `suko-components`; a CLI mitiga
  imprimindo os requisitos no fim do `add` (D10).
- **R9 (baixo) — o lockfile vira fonte de conflitos de merge.** Mitigação:
  ordenação estável e ausência de timestamps (D7).

## Testes

Convenção do projeto: render real via `gg.jte` (`JteRenderSupport`) sempre
que o achado dependa de código Java gerado; nada de smoke de parse quando
há forma de provar o comportamento.

- **`HttpRegistrySource`** contra um `HttpServer` do JDK em `localhost`:
  resolve um caminho relativo; recusa caminho absoluto; recusa `..` que
  escape à base; recusa `http` sem `--allow-insecure`; recusa redirect;
  respeita o timeout; reporta 404 com mensagem legível.
- **Equivalência de transporte:** o mesmo teste de resolução corre contra
  `FileSystemRegistrySource` e `HttpRegistrySource`, provando que a base
  relativa de D1 do 7 é mesmo a mesma operação nos dois caminhos.
- **Grafo:** `field` arrasta `label` e `input`; um ciclo artificial num
  registry de fixture é recusado com mensagem legível.
- **Reescrita:** `package io.suko.ui;` passa a `package com.acme.web.ui;` e
  `import io.suko.ui.Label;` passa a `import com.acme.web.ui.Label;`, com o
  resto do ficheiro byte-a-byte idêntico; um `.sk` de fixture que contenha
  a string `io.suko` dentro de texto HTML prova que a substituição não é
  global; um `basePackage` inválido é recusado.
- **Lockfile:** depois de um `add`, o `localSha256` bate com o ficheiro no
  disco (é o teste que prova que C2 foi entendido); o `upstreamSha256` bate
  com o manifesto; `reason` é `direct` para o pedido e `transitive` para os
  arrastados.
- **Matriz de reconciliação:** os cinco casos da tabela de D7, cada um como
  teste próprio, mais "ficheiro existe sem entrada no lockfile".
- **CRLF:** um ficheiro no disco com CRLF e conteúdo por outro lado
  idêntico **não** é reportado como editado localmente (D8).
- **Atomicidade:** um `add` de 3 componentes em que o terceiro falha a ser
  obtido não deixa nenhum dos 3 no disco nem altera o lockfile (R1).
- **`schemaVersion`:** um documento com `schemaVersion` inferior ao
  suportado é lido; um superior é recusado com mensagem que nomeia as duas
  versões (D4).
- **Plugin Gradle via TestKit:** um projeto temporário com
  `plugins { id("io.suko.lang") }` corre `sukoCompile` e produz o `.jte`
  esperado, espelhando pacotes (D11).
- **Ciclo completo (o teste que prova a tese):** `suko add field` contra
  `--registry suko-components/` num projeto temporário → `sukoCompile` via
  TestKit → render com `gg.jte` 3.1.12 real do `Field` instalado, que
  resolve o `Label` e o `Input` também instalados.
- **Binário nativo (condicional, via `jbang`):** quando `native-image`
  estiver disponível localmente, um teste constrói o binário com `jbang
  build --native --build-dir <tmp> -m io.suko.cli.Main <fat-jar>` sobre o
  fat jar da build atual e corre contra ele o mesmo guião de comandos
  usado para alimentar o `native-image-agent` (`init` → `list` → `add` →
  `list` de novo → `diff` → `update` → um caso de erro), produzindo output
  equivalente ao do fat jar. Ignorado quando `jbang`/`native-image` não
  estão disponíveis — o caso normal no ambiente de desenvolvimento (JDK
  ambiente é Corretto, não GraalVM). Este teste não produz nem publica
  artefacto nenhum: só confirma que o `reflect-config.json` embutido no
  fat jar é suficiente.
- **Regressão do 7:** `RegistryJsonTest`, `RegistryGeneratorTest` e
  `RegistryGoldenTest` continuam a passar sem alteração de semântica depois
  do split de D3.

## Próximos passos

Depois deste subprojeto, `ARCHITECTURE.md` é atualizado:

- **"Estrutura de módulos"**: entram `suko-registry-generator` e
  `suko-cli`; a descrição de `suko-registry` passa a refletir que já não
  depende de `suko-core`; **o racional do Gson é corrigido** (hoje afirma
  um benefício de fat jar que a dependência `api(suko-core)` anulava);
  `suko-gradle-plugin` deixa de estar descrito como tendo a lacuna do ID
  descobrível.
- **"Roadmap por subprojeto"**: item 8 passa a CONCLUÍDO com ponteiro para
  esta spec; a ressalva do item 4 é reescrita para ficar **assimétrica** —
  o caminho Gradle passa a ser suportado end-to-end, o caminho Maven
  continua sem `plugin.xml`.
- **"Limitações conhecidas"**: **sai** a lacuna do `sukoWatch` (fechada
  por D13), substituída pela nota de que o modo watch recompila o source
  root inteiro a cada evento; entra a
  ausência de assinatura/hash dos documentos JSON do registry (C7); entra a
  impossibilidade de pinar uma versão *de componente* independentemente da
  tag do registry (D5); entra o binário nativo GraalVM ser exclusivamente
  `jbang --native --build-dir ...` do lado do consumidor (D2, revisto em
  2026-09-20) — nunca construído nem publicado pelo projeto, requer
  GraalVM local, e requer `--build-dir` explícito por causa de
  jbangdev/jbang#2623 (aberto à data desta spec; revisitar a instrução
  quando essa correção for lançada).
- **"Decisões de design que moldam o pipeline"**: entra Java 21 como piso
  declarado via `options.release` uniforme (D12), incluindo o que essa
  escolha garante (bytecode 65, recusa de API pós-21 em compilação) e o que
  não garante (comportamento de runtime no 21 não é testado).

O subprojeto 9 (site) fica desbloqueado e ganha uma dependência correta
para declarar: `suko-registry` (modelo, leve) em vez de `suko-components` —
o achado já registado no `ARCHITECTURE.md` fica finalmente com um alvo que
faz sentido, porque depois de D3 esse módulo já não arrasta o compilador.
