# Suko — Subprojeto 8: CLI de Distribuição (`suko add`) — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Entregar o ciclo completo do consumidor externo: `suko add <componente>` obtém o componente do registry (HTTPS ou base local), resolve o grafo `dependsOn`, reescreve o namespace para o pacote do consumidor, escreve no disco, regista tudo num lockfile — **e** o resultado compila num build Gradle real via `plugins { id("io.suko.lang") }`.

**Architecture:** Um módulo novo `suko-cli` (só JDK + o modelo do registry) assenta sobre um `suko-registry` que deixa de arrastar o compilador: o gerador sai para `suko-registry-generator`. A outra metade do transporte de D1 do subprojeto 7 (`HttpRegistrySource`) entra no módulo do modelo. Em paralelo, o `suko-gradle-plugin` (subprojeto 4) passa a ser um plugin Gradle a sério — ID descobrível, convenções, teste TestKit — porque sem isso a CLI entrega ficheiros que ninguém consegue construir.

**Tech Stack:** Java 21 (piso passa a ser declarado, não assumido), Gson 2.11.0 (já pinado, herdado do 7), `java.net.http.HttpClient` do JDK, JUnit 5, Gradle multi-módulo + `java-gradle-plugin` + TestKit, gg.jte 3.1.12 (só nos testes de render).

**Spec:** `docs/superpowers/specs/2026-09-20-suko-cli-distribuicao.md`

## Global Constraints

- O projeto NÃO tem `gradlew` commitado — todos os comandos usam `gradle` (sistema), nunca `./gradlew`.
- Testes de render usam `JteRenderSupport` (motor `gg.jte` real, via `testFixtures(suko-core)`) sempre que o teste dependa de código Java gerado. Convenção do projeto desde o subprojeto 1.
- Qualquer desvio descoberto durante a implementação é documentado no código, no ponto exato da descoberta, com o texto exato do comportamento observado — convenção obrigatória do projeto.
- Git dentro de worktrees deste harness tem um bug intermitente do sandbox (classificador "rtk") em `git status`/`git diff`/`git add` bare — o workaround é invocar sempre via caminho absoluto `/usr/bin/git <subcomando>`. Aplica-se a todos os passos de commit deste plano.
- **`suko-cli` NUNCA depende de `suko-core`.** É a razão de ser de D3 e a condição de D9. Uma tarefa que precise do compilador dentro da CLI está errada: ou o dado devia vir do manifesto, ou o passo pertence ao plugin Gradle.
- **`suko-registry` (modelo) NUNCA volta a depender de `suko-core`** depois da Tarefa 2. Qualquer import de `io.suko.lang.*` nesse módulo é erro de revisão.
- **Nenhuma gramática, AST, emitter ou diagnóstico é tocado.** Regra D5 do subprojeto 7 continua a valer: um bug de compilador descoberto aqui é **registado no ledger, não corrigido**. Os únicos módulos que este plano pode alterar fora de `suko-cli` são os nomeados nas decisões: `suko-registry` (D3/D4), `suko-gradle-plugin` (D11/D13), a raiz (D12) e `suko-components`/`settings.gradle.kts` pelo efeito mecânico do split.
- **A CLI nunca escreve fora do `sourceRoot` do consumidor e nunca edita um ficheiro que não criou** (D10). Não toca em `tailwind.config.js`, `package.json`, HTML de layout, ou qualquer configuração de terceiros. Imprime; não age.
- **Comparar `files[].sha256` com um ficheiro no disco do consumidor é sempre erro** (C2 da spec). O hash do manifesto é do ficheiro *pré-reescrita*. Quem comparar com o disco compara com o `localSha256` do lockfile, nunca com o do manifesto. Um passo que confunda os dois produz "tudo foi editado localmente" e é o modo de falha mais caro deste subprojeto.
- **Escrita em LF, comparação com CRLF normalizado** (D8). Nunca escrever com `System.lineSeparator()`.
- **Nada é escrito antes de tudo estar obtido, verificado, reescrito e confrontado com o lockfile** (pipeline de 8 passos da spec, mitigação de R1). Um passo que escreva no disco a meio da resolução é scope creep na direção errada.
- **Dependências novas: nenhuma.** `suko-cli` usa o JDK e o que `suko-registry` já traz (Gson). Sem picocli, sem Shadow, sem cliente HTTP externo, sem biblioteca de diff, e **sem plugin Gradle de native-image** (`org.graalvm.buildtools.native` não entra — ver Tarefa 13: não há mais nenhuma tarefa Gradle que construa ou publique um binário nativo, logo não há escolha de mecanismo a fazer para ela). Se uma tarefa parecer precisar de uma dependência nova, parar e reportar.
- **O GraalVM não é requisito de build, nem de teste normal.** Não há tarefa Gradle de release que produza um binário nativo (Tarefa 13, decisão revista em 2026-09-20: o caminho nativo passou a ser `jbang --native`, do lado do consumidor — ver D2 da spec). A única coisa opt-in que continua a existir do lado do build é uma verificação de desenvolvimento que invoca `jbang` para validar o `reflect-config.json`, condicional à disponibilidade de `jbang`/`native-image` e sem entrar em `build`/`check`: o ambiente de desenvolvimento tem Corretto 25, não GraalVM, e `gradle build` não pode passar a falhar por causa disto.
- **O manifesto de `suko-components` continua a ser gerado, nunca editado à mão** (regra herdada do subprojeto 7). Este plano não muda um único `.sk` nem um único JSON da biblioteca.

---

## Descobertas feitas ao escrever este plano (detalhe de implementação, não decisão de design — por isso estão aqui e não na spec)

- **O JDK ambiente é o 25 (Corretto 25.0.3) e o Gradle é o 9.5.0; não há `gradlew` commitado e não há CI.** Isto transformou D12 numa decisão real, não numa linha de configuração: com só um JDK 25 instalado e sem o plugin `foojay-resolver-convention` no `settings.gradle.kts`, declarar `languageVersion = 21` **quebra o build** com "No matching toolchains found". **Decidido pelo utilizador em 2026-09-20: `options.release = 21` uniforme, sem toolchain e sem foojay** (ver Tarefa 1, incluindo o custo aceite — os testes correm no 25, não no piso).
- **`suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java` importa sete tipos de `io.suko.registry`** (`ComponentFile`, `ComponentManifest`, `ExternalRequirement`, `GeneratedRegistry`, `GeneratorConfig`, `RegistryGenerator`, `RegistryJson`) — repartidos pelos dois módulos que a Tarefa 2 cria. A dependência `api` do gerador sobre o modelo resolve isto sem linha extra em `suko-components`. **E o `main()` desta mesma classe é o entry point da task `:suko-components:generateRegistry`** (`mainClass.set("io.suko.components.RegistryGoldenTest")`): o split não pode partir isso.
- **`RegistryJson.SUPPORTED_SCHEMA_VERSION` é `static final int` package-private e deriva de `RegistryIndex.SCHEMA_VERSION`.** Ambos ficam no módulo do modelo, portanto o split não lhes toca e D4 é uma alteração local.
- **`RegistryJson.requireFields` exige, por reflexão sobre `getRecordComponents()`, que *todos* os campos do record estejam presentes.** É exatamente este mecanismo que torna a regra de D4 crítica: num futuro schema 2, um campo novo obrigatório no record quebra a leitura de documentos v1 no instante em que for acrescentado. A Tarefa 3 escreve isto como comentário no sítio onde alguém o vai ler.
- **`FileSystemRegistrySource.requireRelative` (incluindo o `WINDOWS_DRIVE_LETTER`) é validação partilhável.** A Tarefa 6 extrai-a para um helper comum em vez de a duplicar no `HttpRegistrySource` — duplicar validação de segurança é como ela diverge.
- **`gradleTestKit()` já está declarado em `suko-gradle-plugin/build.gradle.kts` e nenhum teste usa `GradleRunner`.** `SukoGradlePluginTest` testa `JteCompiler` isolado (apesar do nome) e `SukoWatchTaskE2ETest` chama o compilador diretamente, sem passar pelo Gradle. A Tarefa 4 introduz o primeiro teste do repo que corre mesmo um build Gradle.
- **`SukoExtension` carrega uma implementação manual de `Property<T>` (`TestProperty`, classe interna) para servir o construtor sem argumentos usado em testes.** Com TestKit e um plugin aplicado a sério, o Gradle instancia a extensão pelo construtor `@Inject`. A Tarefa 4 avalia remover essa classe; se algum teste ainda depender dela, fica, mas com um comentário a dizer porquê.
- **`SukoCompileTask` escreve `outputDir.resolve(entry.getKey())` e as chaves do resultado do `SukoProjectCompiler` já trazem o subcaminho do pacote** (`ui/NavLink.jte`). A Tarefa 5 (watch) replica esse padrão, não reinventa o cálculo de destino.
- **Não existe infraestrutura de teste de Gradle no repo** (`ProjectBuilder` nunca foi usado). A Tarefa 4 introduz a primeira, deliberadamente e com âmbito fechado: um projeto temporário, um `.sk`, um `sukoCompile`.
- **`suko-website/build.gradle.kts` continua a declarar `implementation(project(":suko-components"))`** sobre um módulo sem código Java. **Não é corrigido aqui** (subprojeto 9), mas depois da Tarefa 2 o alvo correto passa a ser inequívoco: `suko-registry`, que já não arrasta o compilador.
- **`examples/` está fora de qualquer módulo** e não é tocado por este plano.
- **A versão do projeto é `0.1.0-SNAPSHOT`** (definida em `subprojects {}` na raiz) e o `registryVersion` do manifesto é `0.1.0`, um campo próprio. D5 diz que a CLI deriva a tag por omissão da sua **própria** versão — a Tarefa 13 tem de decidir como a injeta (atributo `Implementation-Version` no MANIFEST do fat jar, como o `suko-maven-plugin` já faz no seu jar, com constante de fallback para quando a CLI corre fora do jar, em teste).

---

### Task 1: D12 — Java 21 como piso declarado, via `options.release`

**Agent:** `java-specialist`

**Files:**
- Modify: `build.gradle.kts` (raiz)
- Modify: `suko-maven-plugin/build.gradle.kts` (o comentário sobre versão de Java fica desatualizado)

**Interfaces:**
- Produz: um piso de bytecode declarado e uniforme (major version 65). Nenhum tipo novo.

**Porquê primeiro:** muda a compilação de todos os módulos. Fazê-lo depois do split da Tarefa 2 obrigaria a validar o split duas vezes; fazê-lo depois do fat jar seria descobrir tarde que o artefacto entregue não arranca no JDK que promete.

**Mecanismo escolhido (decidido pelo utilizador em 2026-09-20):** `options.release = 21` aplicado uniformemente, **não** uma toolchain. O ambiente é JDK 25 (Corretto 25.0.3) + Gradle 9.5.0, sem `gradlew` e sem CI; pinar `languageVersion = 21` sem um JDK 21 instalado exigiria o plugin `foojay-resolver-convention` no `settings.gradle.kts` — uma dependência de build nova e uma descarga de JDK na primeira build limpa. O utilizador preferiu não a ter.

**Custo aceite, a registar em comentário no código:** os testes continuam a correr no JDK ambiente (25), não no piso real (21). O que `options.release` garante é o que protege o artefacto da Tarefa 13: bytecode major 65 e **impossibilidade de usar API introduzida depois do 21** (o compilador rejeita, em vez de deixar passar e rebentar em runtime no JDK do utilizador). O que não garante é comportamento de runtime no 21; se isso vier a ser necessário, é uma toolchain ou uma matriz de CI, e nenhuma das duas existe hoje.

- [ ] **Step 1: Aplicar na raiz, uniformemente**

No `build.gradle.kts` da raiz, dentro do `subprojects {}` já existente:

```kotlin
    // Java 21 é o piso declarado (D12). `options.release`, não toolchain:
    // não obriga a ter um JDK 21 instalado nem a acrescentar o
    // foojay-resolver ao settings.gradle.kts, e garante o que interessa ao
    // fat jar do suko-cli — bytecode major 65 e recusa, em tempo de
    // compilação, de qualquer API posterior ao 21.
    //
    // UNIFORMEMENTE em todos os módulos, de propósito: o conflito de
    // resolução de variante documentado em suko-maven-plugin/build.gradle.kts
    // veio de UM módulo declarar uma versão enquanto os outros não
    // declaravam nenhuma. Uniformidade é o que o evita.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
```

A guarda `plugins.withId("java") { ... }` não é necessária para `tasks.withType`, que é inerte num módulo sem tarefas de compilação Java — mas todos os módulos atuais aplicam `java` (direta ou via `java-library`/`java-gradle-plugin`).

**Não** acrescentar `sourceCompatibility`/`targetCompatibility`: são precisamente os que provocaram o conflito de variante registado no `suko-maven-plugin`, porque alteram o atributo `org.gradle.jvm.version` do módulo. `options.release` não o altera.

- [ ] **Step 2: Corrigir o comentário obsoleto do `suko-maven-plugin`**

O bloco de comentário sobre "Java version: deixado ao default (mesmo JDK que suko-core...)" passa a estar errado. Substituir por uma nota a apontar para a decisão uniforme da raiz, a explicar que o problema original (conflito de variante por declaração não-uniforme) deixou de existir, e a dizer explicitamente que `options.release` foi escolhido **em vez de** `sourceCompatibility`/`targetCompatibility` por não mexer no atributo de variante.

- [ ] **Step 3: Validar o piso, não só o build**

Run: `gradle clean build --console=plain`
Expected: verde.

Duas verificações que o build verde não dá sozinho:
1. `javap -verbose` numa classe de `suko-core` → campo `major version` = **65**.
2. Escrever temporariamente, num ficheiro de teste qualquer, uma chamada a API introduzida depois do 21 e confirmar que o **compilador a rejeita** (é esta a garantia que protege o fat jar; sem a confirmar, `options.release` pode estar a ser ignorado por alguma configuração de tarefa). Reverter a seguir, sem commitar.

- [ ] **Step 4: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "build: Java 21 como piso declarado via options.release em todos os modulos (D12)"
```

---

### Task 2: D3 — partir `suko-registry` em modelo e gerador

**Agent:** `java-specialist`

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `suko-registry/build.gradle.kts`
- Create: `suko-registry-generator/build.gradle.kts`
- Move: `RegistryGenerator.java`, `GeneratorConfig.java`, `GeneratedRegistry.java`, `RegistryGeneratorException.java` → `suko-registry-generator/src/main/java/io/suko/registry/`
- Move: `RegistryGeneratorTest.java` e as fixtures `src/test/resources/generator-fixtures/**` → `suko-registry-generator/src/test/`
- Modify: `suko-components/build.gradle.kts`

**Interfaces:**
- Produz: `suko-registry` sem nenhuma dependência de `suko-core` (só Gson); `suko-registry-generator` com `api(suko-registry)` + `api(suko-core)`.
- Consome: nada de novo. **O pacote Java não muda** (`io.suko.registry` nos dois módulos): a separação que interessa é de grafo de dependências, não de nomes, e renomear obrigaria a tocar em todos os imports de testes já escritos, sem ganho.

**Porquê aqui:** todo o resto do plano assenta neste grafo. A CLI depender do módulo por partir significaria arrastar ANTLR e `gg.jte` para dentro do fat jar, que é exatamente o que D3 existe para evitar.

- [ ] **Step 1: Confirmar o corte por inspeção, antes de mover nada**

Listar os imports de cada ficheiro de `suko-registry/src/main/java/io/suko/registry/`. Confirmar que **só** `RegistryGenerator` importa de `io.suko.lang.*`. Se algum dos outros quatro importar, o corte proposto está errado — parar e reportar.

- [ ] **Step 2: Criar o módulo e mover**

`settings.gradle.kts` passa a incluir `suko-registry-generator` (e, já agora, `suko-cli`, que a Tarefa 7 preenche — incluí-lo agora evita um segundo commit a mexer no mesmo ficheiro).

`suko-registry-generator/build.gradle.kts`:

```kotlin
plugins {
    id("java-library")
}

dependencies {
    // `api` nos dois: o gerador expõe tipos de suko-core (ComponentDecl,
    // SukoFile, ProjectIndex) E tipos do modelo (GeneratedRegistry,
    // ComponentManifest) na sua própria assinatura pública.
    api(project(":suko-registry"))
    api(project(":suko-core"))
    // ...junit
}
```

`suko-registry/build.gradle.kts` perde `api(project(":suko-core"))` e fica só com Gson. **Corrigir o comentário sobre Gson no mesmo ficheiro**: até aqui afirmava um benefício de fat jar que a dependência sobre `suko-core` anulava; a partir de agora é verdade, e o comentário deve dizer que é a Tarefa 2 do subprojeto 8 que o tornou verdade.

- [ ] **Step 3: Ajustar `suko-components`**

`testImplementation(project(":suko-registry"))` passa a `testImplementation(project(":suko-registry-generator"))`. Nada mais muda: o `RegistryGoldenTest` importa dos dois conjuntos de tipos e a dependência `api` do gerador sobre o modelo dá-lhe os dois.

- [ ] **Step 4: Validar que nada regrediu**

Run: `gradle :suko-registry:test :suko-registry-generator:test :suko-components:test --console=plain`
Expected: PASS, **sem uma única alteração de semântica em nenhum teste**. `RegistryJsonTest`, `ModelTest`, `RegistrySourceTest` (modelo) e `RegistryGeneratorTest` (gerador) passam tal como estavam. Se algum teste precisar de ser alterado para além do caminho do ficheiro, o corte está errado.

- [ ] **Step 5: Validar que a task `generateRegistry` continua a funcionar**

Run: `gradle :suko-components:generateRegistry --console=plain` seguido de `/usr/bin/git diff --stat`
Expected: a task corre e **não produz nenhuma alteração** em `registry.json`/`components/*.json`. Se produzir, o split mudou alguma coisa que não devia (ordem de chaves, por exemplo) — parar.

- [ ] **Step 6: Provar o ganho, não só assumi-lo**

Run: `gradle :suko-registry:dependencies --configuration runtimeClasspath --console=plain`
Expected: só Gson. Nenhum `org.antlr`, nenhum `gg.jte`, nenhum `project :suko-core`. Registar a saída no ledger — é a evidência de que D3 fez o que prometia.

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "refactor(registry): separa modelo e gerador em dois modulos (D3)"
```

---

### Task 3: D4 — `schemaVersion` aceita versões anteriores, recusa posteriores

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-registry/src/main/java/io/suko/registry/RegistryJson.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistryJsonTest.java`

**Interfaces:**
- Produz: leitura compatível para trás. Nenhum tipo novo.

**Porquê aqui:** é a última alteração ao módulo do modelo antes de a CLI passar a assentar nele. Fazê-la depois obrigaria a rever o cliente já escrito.

- [ ] **Step 1: Escrever os testes que falham**

Em `RegistryJsonTest`:

```java
    @Test
    void readsDocumentWithOlderSchemaVersion() {
        // schemaVersion: 0 num índice por outro lado válido -> lido sem erro.
        // Hoje falha: a validação é por igualdade estrita.
    }

    @Test
    void rejectsDocumentWithNewerSchemaVersionNamingBothVersions() {
        // schemaVersion: 99 -> RegistryJsonException cuja mensagem contém
        // "99" e a versão suportada. Comportamento atual, tem de sobreviver.
    }
```

- [ ] **Step 2: Rodar, confirmar que o primeiro falha e o segundo passa**

Run: `gradle :suko-registry:test --tests "io.suko.registry.RegistryJsonTest" --console=plain`

- [ ] **Step 3: Alterar a validação**

`found != SUPPORTED_SCHEMA_VERSION` passa a `found > SUPPORTED_SCHEMA_VERSION`. A mensagem de erro do caso recusado não muda.

- [ ] **Step 4: Escrever a regra de manutenção onde alguém a vá ler**

No javadoc de `RegistryJson` e junto a `requireFields`, registar: a partir do momento em que existir um `schemaVersion` 2, **qualquer campo novo tem de ser opcional na leitura**, porque `requireFields` exige por reflexão todos os `RecordComponent` do record — um campo novo obrigatório quebra a leitura de documentos v1 no instante em que for acrescentado, anulando a compatibilidade que esta tarefa acabou de criar. Sem este comentário, a próxima pessoa reintroduz o problema sem saber.

- [ ] **Step 5: Rodar tudo**

Run: `gradle :suko-registry:test :suko-registry-generator:test :suko-components:test --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(registry): aceita schemaVersion anterior, recusa posterior (D4)"
```

---

### Task 4: D11 — `suko-gradle-plugin` passa a plugin Gradle a sério

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-gradle-plugin/build.gradle.kts`
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoExtension.java`
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoGradlePlugin.java`
- Create: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoPluginFunctionalTest.java`
- Possibly delete: a classe interna `TestProperty` de `SukoExtension`

**Interfaces:**
- Produz: o ID `io.suko.lang`, aplicável com `plugins { id("io.suko.lang") }`; convenções `sourceDir` = `src/main/suko` e `outputDir` = `build/generated-src/suko`.
- Consome: `SukoProjectCompiler` (inalterado).

**Porquê aqui:** é a metade consumer-facing da promessa do subprojeto. Está antes da CLI de propósito: se acionar o critério de paragem abaixo, é melhor descobri-lo agora, com o resto do plano por fazer, do que depois de haver uma CLI que instala ficheiros para um build que não existe.

**Critério de paragem (obrigatório):** se passar a `java-gradle-plugin` destapar trabalho de migração que exceda o que uma tarefa absorve — regra prática: mais do que ajustar os dois testes existentes e o bloco de dependências — **parar, reverter, e reportar**. O fecho do plugin vira subprojeto próprio; o resto deste plano continua, e a limitação passa a ter de ser documentada como consumer-facing na Tarefa 15.

- [ ] **Step 1: Escrever o teste funcional que falha**

`SukoPluginFunctionalTest`, com `GradleRunner` (TestKit) sobre um `@TempDir`:

```java
    @Test
    void appliesByIdAndCompilesSkToJte() throws Exception {
        // settings.gradle.kts + build.gradle.kts com:
        //   plugins { id("io.suko.lang") }
        // um src/main/suko/io/demo/Hello.sk com `package io.demo;`
        // GradleRunner.create().withProjectDir(dir)
        //     .withPluginClasspath().withArguments("sukoCompile").build()
        // Espera: build/generated-src/suko/io/demo/Hello.jte existe.
    }
```

Nota: `withPluginClasspath()` só funciona com o plugin `java-gradle-plugin` aplicado (é ele que gera o `plugin-under-test-metadata.properties`) — o teste falha hoje por não haver ID *e* por não haver metadata.

- [ ] **Step 2: Rodar, confirmar que falha**

Run: `gradle :suko-gradle-plugin:test --tests "*SukoPluginFunctionalTest" --console=plain`
Expected: FALHA, com "Plugin [id: 'io.suko.lang'] was not found".

- [ ] **Step 3: Aplicar `java-gradle-plugin` e declarar o ID**

Em `suko-gradle-plugin/build.gradle.kts`, substituir `id("java")` por `id("java-gradle-plugin")` e acrescentar:

```kotlin
gradlePlugin {
    plugins {
        create("suko") {
            id = "io.suko.lang"
            implementationClass = "io.suko.lang.gradle.SukoGradlePlugin"
        }
    }
}
```

`compileOnly(gradleApi())` deixa de ser necessário (o plugin já o fornece); `gradleTestKit()` já estava declarado e passa finalmente a ser usado. Comentar no ficheiro que este módulo **não** é publicado no Gradle Plugin Portal — o ID serve `includeBuild`/composite e o TestKit, e publicar depende da questão de publicação que D1 do subprojeto 7 deixou fora.

- [ ] **Step 4: Dar convenções reais à extensão**

Em `SukoGradlePlugin.apply`, depois de criar a extensão, fixar as convenções:
- `sourceDir` = `src/main/suko`
- `outputDir` = `build/generated-src/suko`

Comentar porquê: até aqui `SukoExtension` não tinha default nenhum, o que significava que não existia no código um "source root canónico" — e é desse default que a CLI depende para o `suko init` da Tarefa 7 poder propor alguma coisa em vez de perguntar às cegas.

- [ ] **Step 5: Avaliar a dívida do `TestProperty`**

`SukoExtension` carrega uma implementação manual de `Property<T>` para servir o construtor sem argumentos usado por testes. Com o plugin aplicado a sério, o Gradle instancia a extensão pelo construtor `@Inject`. Verificar se algum teste ainda depende do construtor sem argumentos: se não, **remover** a classe interna e o construtor; se sim, deixar ficar **com um comentário** a dizer qual o teste e porque não foi removida. Não deixar sem explicação.

- [ ] **Step 6: Rodar o teste funcional e a suite do módulo**

Run: `gradle :suko-gradle-plugin:test --console=plain`
Expected: PASS, incluindo os dois testes que já existiam.

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(gradle): plugin com ID descobrivel, convencoes e teste TestKit (D11)"
```

---

### Task 5: D13 — `sukoWatch` sobre `SukoProjectCompiler`

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoWatchTask.java`
- Test: `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoWatchTaskE2ETest.java`

**Interfaces:**
- Consome: `SukoProjectCompiler.compile(Path)` — o mesmo que `SukoCompileTask` já usa.
- Produz: paridade de output entre `sukoCompile` e `sukoWatch`.

**Porquê aqui:** fecha o caminho de build antes de a CLI existir. Um `Field` instalado por `suko add` não resolve o `Label` ao lado em modo watch enquanto esta tarefa não for feita — e a primeira pessoa a bater nisso seria o primeiro utilizador externo.

- [ ] **Step 1: Escrever os testes que falham**

Dois casos, ambos sobre um source root com **dois** ficheiros em pacotes:

```java
    @Test
    void watchMirrorsPackagesInOutput() {
        // src/main/suko/io/demo/ui/Label.sk -> build/.../io/demo/ui/Label.jte
        // Hoje: output plano (Label.jte na raiz do outputDir).
    }

    @Test
    void watchResolvesCrossFileComponentCalls() {
        // Field.sk importa e chama Label.sk, ambos no mesmo pacote.
        // Hoje: falha a resolver (o caminho antigo compila ficheiro a ficheiro,
        // sem ProjectIndex).
    }
```

- [ ] **Step 2: Rodar, confirmar que falham**

Run: `gradle :suko-gradle-plugin:test --tests "*SukoWatchTaskE2ETest" --console=plain`

- [ ] **Step 3: Trocar o caminho de compilação**

`SukoWatchTask` passa a invocar `new SukoProjectCompiler().compile(sourceDir)` a cada evento do `WatchService`, escrevendo cada entrada com `outputDir.resolve(entry.getKey())` — **o mesmo padrão que `SukoCompileTask` já usa**, porque as chaves do resultado já trazem o subcaminho do pacote. Não recalcular destinos à mão.

O `Files.list` não recursivo desaparece; o registo do `WatchService` passa a ser recursivo (registar cada subdiretório), senão uma alteração em `io/demo/ui/Label.sk` nunca dispara.

Comentar, no ponto exato: **a recompilação é total a cada evento, deliberadamente**. Isto contorna por completo o que o subprojeto 5 adiou (o que reindexar, quando, e o que fazer quando muda um ficheiro que outros importam) — não há pergunta difícil se a resposta for sempre "tudo". É uma característica de desempenho declarada, não uma limitação escondida.

- [ ] **Step 4: Rodar, confirmar que passam**

Run: mesmo comando do Passo 2.
Expected: PASS, e os diagnósticos de nível de projeto (`IMPORT_NOT_FOUND`, `COMPONENT_NOT_VISIBLE`, `AMBIGUOUS_IMPORT`, `PACKAGE_DIRECTORY_MISMATCH`, `DUPLICATE_COMPONENT`) passam a ser reportados em watch. Acrescentar um teste para pelo menos um deles.

- [ ] **Step 5: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "fix(gradle): sukoWatch usa SukoProjectCompiler, output espelha pacotes (D13)"
```

---

### Task 6: `HttpRegistrySource` — a outra metade do transporte de D1

**Agent:** `security-specialist` (é uma fronteira de segurança: entrada de conteúdo externo, path traversal, redirects, TLS)

**Files:**
- Create: `suko-registry/src/main/java/io/suko/registry/HttpRegistrySource.java`
- Modify: `suko-registry/src/main/java/io/suko/registry/FileSystemRegistrySource.java` (extrair a validação partilhada)
- Create: `suko-registry/src/main/java/io/suko/registry/RegistryPaths.java` (helper de validação de caminho relativo)
- Test: `suko-registry/src/test/java/io/suko/registry/HttpRegistrySourceTest.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistrySourceTest.java` (acrescentar o teste de equivalência)

**Interfaces:**
- Produz: `HttpRegistrySource implements RegistrySource`, construído a partir de uma base URL terminada em `/`.
- Consome: `java.net.http.HttpClient` do JDK. **Nenhuma dependência nova.**

- [ ] **Step 1: Extrair a validação de caminho relativo**

`FileSystemRegistrySource.requireRelative` (incluindo o `WINDOWS_DRIVE_LETTER`) passa para `RegistryPaths`, usada pelas duas implementações. Duplicar validação de segurança é exatamente como ela diverge; a versão de ficheiros continua a fazer, **por cima** desta, as suas duas verificações de contenção (lexical e via `toRealPath()`).

Run: `gradle :suko-registry:test --tests "io.suko.registry.RegistrySourceTest" --console=plain`
Expected: PASS sem alteração de comportamento — é refactor puro.

- [ ] **Step 2: Escrever os testes que falham, contra um servidor real**

`HttpRegistrySourceTest` usa `com.sun.net.httpserver.HttpServer` do JDK em `localhost` (sem dependência nova). Casos:

- resolve `components/button.json` relativo à base e devolve os bytes exatos;
- recusa caminho absoluto (`/etc/passwd`);
- recusa `../` que escape à base — **e verifica que nenhum pedido chegou ao servidor** (a recusa é lexical, antes da rede);
- recusa uma base `http://` sem `--allow-insecure`, aceita-a com;
- **recusa um redirect** (o servidor responde 302 para outro caminho, e para outro host): é o equivalente HTTP do symlink que a implementação de ficheiros já bloqueia;
- 404 produz mensagem legível com URL e código, não exceção crua;
- timeout é respeitado (servidor que não responde);
- resposta acima do limite de tamanho é recusada.

- [ ] **Step 3: Rodar, confirmar que falham**

Run: `gradle :suko-registry:test --tests "*HttpRegistrySourceTest" --console=plain`
Expected: FALHA — a classe ainda não existe.

- [ ] **Step 4: Implementar**

Requisitos, todos derivados da spec:
- base tem de terminar em `/`; a resolução é concatenação simples **depois** de `RegistryPaths` validar o relativo;
- `HttpClient` único e reutilizado, `followRedirects(Redirect.NEVER)`, timeout de ligação e de leitura, `User-Agent` identificável;
- HTTPS obrigatório; `http` só com a flag explícita, cujo único uso legítimo é servidor de teste local — documentar isso no javadoc, não só no help;
- limite máximo de tamanho de resposta;
- status != 200 → `IOException` com URL e código.

- [ ] **Step 5: Teste de equivalência de transporte**

O mesmo cenário de resolução corre contra `FileSystemRegistrySource` (base = um diretório de fixture) e `HttpRegistrySource` (base = o mesmo conteúdo servido), com as **mesmas asserções**. É a prova de que a decisão D1 do subprojeto 7 ("a relatividade é o que faz os dois caminhos serem o mesmo código") se aguentou.

- [ ] **Step 6: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(registry): HttpRegistrySource com contencao de path, sem redirects (D1)"
```

---

### Task 7: Módulo `suko-cli` — esqueleto, `suko.json`, `suko init`, `suko list`

**Agent:** `dx-specialist` (é a primeira superfície que o utilizador externo vê: help, prompts, mensagens de erro)

**Files:**
- Create: `suko-cli/build.gradle.kts`
- Create: `suko-cli/src/main/java/io/suko/cli/Main.java`
- Create: `suko-cli/src/main/java/io/suko/cli/Args.java`
- Create: `suko-cli/src/main/java/io/suko/cli/ProjectConfig.java`
- Create: `suko-cli/src/main/java/io/suko/cli/command/InitCommand.java`, `ListCommand.java`
- Test: `suko-cli/src/test/java/io/suko/cli/ArgsTest.java`, `ProjectConfigTest.java`, `ListCommandTest.java`

**Interfaces:**
- Produz: `ProjectConfig` (record: `schemaVersion`, `sourceRoot`, `basePackage`, `registry{base, ref}`), lido/escrito como `suko.json`; parsing de argumentos; os comandos `init` e `list`.
- Consome: `RegistryJson.readIndex`, `RegistrySource`. **Nunca** `suko-core`.

- [ ] **Step 1: Módulo e dependências**

```kotlin
plugins { id("java") }

dependencies {
    // Só o modelo. NUNCA suko-core: ver Global Constraints e D3/D9.
    implementation(project(":suko-registry"))
    // ...junit
}
```

Verificar, e registar no ledger: `gradle :suko-cli:dependencies --configuration runtimeClasspath` não contém `suko-core`, ANTLR nem `gg.jte`.

- [ ] **Step 2: Testes que falham — parsing de argumentos e config**

`ArgsTest`: comando desconhecido produz help e código de saída != 0; flags globais (`--registry`, `--registry-ref`, `--source-root`, `--base-package`, `--force`, `--dry-run`, `--yes`) são reconhecidas em qualquer posição; uma flag que precisa de valor e não o tem produz mensagem legível.

`ProjectConfigTest`: lê e escreve `suko.json`; precedência **flag > ficheiro > default**; `basePackage` inválido (palavra-reservada, começa por dígito, vazio) é recusado com mensagem que nomeia o segmento errado; ausência de `suko.json` sem flags suficientes produz "corre `suko init`" e não um stack trace.

- [ ] **Step 3: Implementar parsing à mão**

Sem picocli (Global Constraints). São cinco comandos e um punhado de flags. `Main` despacha; `Args` valida; ambos com `--help` por comando.

- [ ] **Step 4: `suko init`**

Interativo, com defaults visíveis (`src/main/suko`, vindo da convenção que a Tarefa 4 fixou no plugin). `basePackage` **não** tem default — é perguntado, porque adivinhá-lo da estrutura de pastas erraria em silêncio e o erro só apareceria como `PACKAGE_DIRECTORY_MISMATCH` muito mais tarde. `--yes` aceita os defaults sem perguntar (e falha se faltar o `basePackage`).

- [ ] **Step 5: `suko list`**

Lê `registry.json` via `RegistrySource` + `RegistryJson.readIndex` e imprime nome, versão, categoria e descrição, alinhados. Teste contra uma base de ficheiros apontada a `suko-components/` — o registry real, não um fixture sintético.

- [ ] **Step 6: Rodar tudo**

Run: `gradle :suko-cli:test --console=plain`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "feat(cli): modulo suko-cli, suko.json, comandos init e list"
```

---

### Task 8: Resolução do grafo `dependsOn`

**Agent:** `java-specialist`

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/Resolver.java`
- Test: `suko-cli/src/test/java/io/suko/cli/ResolverTest.java`
- Create: fixtures de registry sintético em `suko-cli/src/test/resources/registry-fixtures/`

**Interfaces:**
- Produz: `Resolver.resolve(RegistryIndex, RegistrySource, List<String> pedidos) -> ResolutionPlan`, onde `ResolutionPlan` traz os `ComponentManifest` por ordem topológica e a marca `direct`/`transitive` de cada um.
- Consome: `RegistryIndex.Entry.manifest()` (caminho relativo à base) e `ComponentManifest.dependsOn()`.

- [ ] **Step 1: Testes que falham**

- `field` arrasta `label` e `input`, marcados `transitive`; `field` fica `direct`;
- pedir `field` e `label` explicitamente marca **os dois** como `direct`;
- ordem topológica: as dependências vêm antes de quem as pede;
- nome inexistente produz mensagem que o nomeia e sugere `suko list`;
- **ciclo** num registry de fixture produz erro legível com o ciclo impresso. O subprojeto 7 garante aciclicidade do registry oficial, mas `--registry` aceita um qualquer — esta defesa não é redundante;
- um `dependsOn` que aponta para um nome ausente do índice produz erro, não um `null` silencioso.

- [ ] **Step 2: Rodar, confirmar que falham**

- [ ] **Step 3: Implementar**

DFS com deteção de ciclo por pilha de visita. Obter cada manifesto **uma só vez** (memoizar por nome) — o grafo pode ter diamantes.

Comentar o que o manifesto **não** garante: `dependsOn` é derivado dos imports que resolvem dentro do próprio registry, e o gerador do subprojeto 7 ignora em silêncio os que não resolvem (C6 da spec). A CLI não deve assumir que `dependsOn` cobre tudo o que um ficheiro precisa para compilar.

- [ ] **Step 4: Rodar contra o registry real**

Teste adicional com base de ficheiros a apontar para `suko-components/`: `resolve(["field"])` devolve exatamente `[input, label, field]` ou `[label, input, field]` (qualquer ordem topológica válida), todos com o manifesto carregado.

- [ ] **Step 5: Commit**

---

### Task 9: Reescrita de namespace

**Agent:** `java-specialist`

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/NamespaceRewriter.java`
- Test: `suko-cli/src/test/java/io/suko/cli/NamespaceRewriterTest.java`
- Create: fixtures `.sk` em `suko-cli/src/test/resources/rewrite-fixtures/`

**Interfaces:**
- Produz: `NamespaceRewriter.rewrite(byte[] source, String fromBasePackage, String toBasePackage) -> byte[]`.
- Consome: nada. É manipulação de texto — **não** parsing (a CLI não depende de `suko-core`).

**Porquê é a tarefa mais perigosa do plano:** ver Risco R2 da spec. Um `replace` global toca em strings, texto HTML e atributos, e o erro é silencioso: o ficheiro compila e renderiza, só está errado.

- [ ] **Step 1: Testes que falham, incluindo os armadilhados**

- `package io.suko.ui;` → `package com.acme.web.ui;`
- `import io.suko.ui.Label;` → `import com.acme.web.ui.Label;`
- **o resto do ficheiro é byte-a-byte idêntico** (asserção sobre o array completo, não sobre linhas soltas);
- um `.sk` de fixture que contenha a string `io.suko` **dentro de texto HTML e dentro de um atributo** prova que a substituição não é global;
- um `import` que não comece pelo `basePackage` do manifesto fica intacto (caso C6);
- indentação e espaçamento à volta de `package`/`import` são preservados;
- um ficheiro cuja linha `package` **não** começa pelo `fromBasePackage` faz **abortar** com mensagem que nomeia o ficheiro — não é reescrito "na melhor das hipóteses";
- um `toBasePackage` inválido (segmento que é palavra-reservada Java, segmento que começa por dígito, string vazia, ponto duplo) é recusado;
- fim de linha: entrada com LF sai com LF; entrada com CRLF sai com LF (D8 — escrevemos sempre LF).

- [ ] **Step 2: Rodar, confirmar que falham**

- [ ] **Step 3: Implementar, ancorado por linha**

Só linhas que casem uma declaração `package`/`import` cujo alvo comece **literalmente** pelo `fromBasePackage` são tocadas. Tudo o resto passa sem ser lido. Validar o pacote resultante antes de devolver.

- [ ] **Step 4: Prova sobre o conteúdo real**

Correr a reescrita sobre os 8 `.sk` reais de `suko-components/src/main/suko/io/suko/ui/` com `toBasePackage = "com.acme.web"` e afirmar, para cada um: só as linhas `package`/`import` mudaram (comparação linha a linha com o original), e o número de linhas é o mesmo.

- [ ] **Step 5: Commit**

---

### Task 10: Lockfile, hashing e matriz de reconciliação

**Agent:** `java-specialist`

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/Lockfile.java`, `LockEntry.java`, `Hashes.java`, `Reconciler.java`
- Test: `suko-cli/src/test/java/io/suko/cli/LockfileTest.java`, `HashesTest.java`, `ReconcilerTest.java`

**Interfaces:**
- Produz: leitura/escrita de `suko.lock.json` (formato da spec, D7), `Hashes.sha256OfNormalized(byte[])`, e `Reconciler.classify(...)` a devolver um dos cinco estados da matriz.
- Consome: Gson (via `suko-registry`), para manter uma só biblioteca JSON no fat jar.

**Constrangimento que define esta tarefa:** o lockfile guarda **dois** hashes por ficheiro. `upstreamSha256` é o do manifesto (identifica a revisão instalada); `localSha256` é o dos bytes efetivamente escritos, pós-reescrita. Comparar o hash do manifesto com o ficheiro no disco classifica tudo como "editado localmente" — ver Global Constraints.

- [ ] **Step 1: Testes que falham**

`HashesTest`: o hash de um conteúdo com CRLF é igual ao do mesmo conteúdo com LF (normalização na comparação, D8); o hash de conteúdo diferente difere.

`LockfileTest`: round-trip escrita/leitura; `components` sai ordenado alfabeticamente; **não há nenhum campo de timestamp** (asserção explícita sobre o texto gerado — é o que impede alguém de acrescentar um "por conveniência"); ler um lockfile com `schemaVersion` superior produz erro legível.

`ReconcilerTest`: os cinco casos da matriz de D7, um teste por linha, mais o sexto caso — **ficheiro de destino existe e não há entrada no lockfile** → "propriedade do consumidor", recusa sem `--force`.

- [ ] **Step 2: Rodar, confirmar que falham**

- [ ] **Step 3: Implementar**

`Reconciler` é uma função pura sobre (bytes no disco ou ausência, `LockEntry`, `ComponentFile` do manifesto) → estado. Sem I/O lá dentro: é o que a torna testável nos seis casos sem montar árvores de ficheiros.

- [ ] **Step 4: Teste que fecha o modo de falha caro**

Um teste explícito, com nome que diga o que protege, a afirmar que **o hash do manifesto nunca é comparado com o ficheiro no disco**: instalar (em memória) um ficheiro, reescrever o namespace, calcular `localSha256`, e confirmar que `localSha256 != upstreamSha256` e que a reconciliação dá "não modificado". Se alguém, um dia, simplificar os dois hashes num só, é este teste que fica vermelho.

- [ ] **Step 5: Commit**

---

### Task 11: `suko add` — pipeline completo e atomicidade

**Agent:** `java-specialist`

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/command/AddCommand.java`
- Test: `suko-cli/src/test/java/io/suko/cli/command/AddCommandTest.java`

**Interfaces:**
- Consome: `Resolver`, `NamespaceRewriter`, `Lockfile`, `Reconciler`, `RegistrySource`.
- Produz: ficheiros no `sourceRoot` do consumidor e o `suko.lock.json`.

**A ordem dos passos é a mitigação de R1 e não é negociável:** 1) config; 2) índice + fecho do grafo; 3) obter **tudo** e verificar cada `sha256` contra o conteúdo obtido; 4) reescrever **tudo** em memória; 5) calcular destinos e confrontar com o lockfile — **um único conflito não resolvido aborta antes de escrever seja o que for**; 6) escrever ficheiros; 7) escrever lockfile **por último**; 8) imprimir `externalRequirements`.

- [ ] **Step 1: Testes que falham**

- `suko add label` contra `--registry suko-components/` escreve `src/main/suko/com/acme/web/ui/Label.sk` com o `package` reescrito, e cria o lockfile;
- `suko add field` escreve **três** ficheiros (`field`, `label`, `input`), com `reason` correto em cada entrada;
- **atomicidade:** um `add` de três componentes em que o terceiro falha a ser obtido (fonte que devolve 404 nesse caminho) não deixa **nenhum** dos três no disco e não altera o lockfile;
- **divergência de hash:** conteúdo obtido cujo sha256 não bate com o manifesto aborta tudo, com mensagem que nomeia o ficheiro;
- ficheiro de destino já existente e editado, sem `--force`, aborta **antes** de escrever os outros;
- `--dry-run` imprime o plano e não escreve nada (asserção sobre a árvore de ficheiros intacta);
- segundo `add` do mesmo componente sem alterações é um no-op e não reescreve o lockfile com conteúdo diferente (idempotência — é o que impede diffs espúrios em commits).

- [ ] **Step 2: Rodar, confirmar que falham**

- [ ] **Step 3: Implementar o pipeline**

Escrita em LF sempre (D8). Criar diretórios em falta. Nunca escrever fora do `sourceRoot` — validar o destino calculado contra o `sourceRoot` da mesma forma que `FileSystemRegistrySource` valida contra a sua base (é a mesma classe de erro, na direção oposta).

- [ ] **Step 4: Imprimir os requisitos externos, sem agir sobre eles**

Agregar os `externalRequirements` de todos os componentes instalados, deduplicados por `(kind, id)`, e imprimir com um ponteiro para o README. **Não editar nada** (D10).

- [ ] **Step 5: Commit**

---

### Task 12: `suko diff` e `suko update`

**Agent:** `java-specialist`

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/command/DiffCommand.java`, `UpdateCommand.java`
- Create: `suko-cli/src/main/java/io/suko/cli/TextDiff.java`
- Test: `suko-cli/src/test/java/io/suko/cli/command/DiffCommandTest.java`, `UpdateCommandTest.java`

**Interfaces:**
- Consome: `Reconciler`, `NamespaceRewriter`, `Lockfile`.
- Produz: saída de diff legível; atualização in-place com a matriz de D7 aplicada.

- [ ] **Step 1: Testes que falham**

`diff`: ficheiro não modificado produz saída vazia e código 0; ficheiro modificado produz as linhas diferentes, **comparando com o upstream pós-reescrita** (não com o upstream cru — senão todo o ficheiro aparece como diferente, que é o mesmo erro de C2 noutra roupagem); componente não instalado produz mensagem legível.

`update`: os cinco casos da matriz; conflito sem `--force` deixa o ficheiro **intacto** e devolve código != 0; conflito com `--force` sobrescreve e atualiza os dois hashes; `update` sem argumento percorre só os `direct` do lockfile e arrasta os `transitive` que eles ainda exigem; um `transitive` que deixou de ser exigido **não** é apagado (remover é fora de escopo — mas é reportado como órfão).

- [ ] **Step 2: Rodar, confirmar que falham**

- [ ] **Step 3: Implementar**

`TextDiff` é um diff de linhas mínimo, à mão (sem biblioteca — Global Constraints). Não precisa de ser um LCS ótimo: precisa de ser legível e determinístico.

Sem merge a três vias, em nenhuma circunstância. A CLI recusa e mostra; o utilizador resolve.

- [ ] **Step 4: Commit**

---

### Task 13: Empacotamento — fat jar, jbang, wrappers, binário nativo

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-cli/build.gradle.kts`
- Create: `suko-cli/src/main/java/io/suko/cli/Version.java` (ou recurso gerado)
- Create: `jbang-catalog.json` (raiz)
- Create: `scripts/suko`, `scripts/suko.bat`
- Create: `suko-cli/src/main/resources/META-INF/native-image/io.suko/suko-cli/reflect-config.json` (gerado pelo agente, commitado)
- Create: `suko-cli/src/test/java/io/suko/cli/NativeImageSmokeTest.java`

**Interfaces:**
- Produz: `suko-cli-<versão>-all.jar` executável; um alias jbang; scripts wrapper. **Nenhum binário nativo é construído nem publicado por este módulo** — o caminho nativo é `jbang --native`, do lado do consumidor, documentado mas não empacotado por nós.

**Nota de escopo (decidido pelo utilizador em 2026-09-20, revista no mesmo dia depois de verificação empírica):** o native-image entra no v1, **a par** do fat jar, não em vez dele — mas como um **único** caminho de empacotamento, não dois. A primeira versão desta tarefa tinha o native-image como artefacto adicional de release, construído por uma tarefa Gradle `Exec` própria e publicado por plataforma. Verificou-se empiricamente (fora do repo, com um jar mínimo e GraalVM CE 25.3.4.1 instalado localmente) que `jbang --native` aceita diretamente um alias de catálogo que aponta para um jar já construído — o caso de `suko-cli` — incluindo honrar `META-INF/native-image/.../reflect-config.json` embutido no jar sem nenhuma flag extra. A tarefa Gradle `Exec` que construía e publicava o binário foi **removida**: o binário passa a ser compilado **pelo próprio jbang do consumidor**, localmente, a partir do mesmo fat jar que ele já usa para correr a CLI — não há dois artefactos, há um jar e uma forma opcional de o correr nativamente. Isto altera o que a spec dizia originalmente em D2 ("native-image fora do v1", depois "native-image como artefacto adicional de release"); a spec foi atualizada em conformidade, com a verificação empírica registada na secção "Verificação empírica do mecanismo".

**Achado colateral da verificação, com custo real para o consumidor:** a primeira tentativa de `jbang --native` sobre um alias-para-jar falha, em jbang 0.138.0, com `Error: Writing image to non-existent directory .../.jbang/cache/jars/<jar>.e3b0c442...` — um bug de jbang ao pré-criar o diretório de cache para jars pré-construídos (não para scripts compilados de fonte), já registado a montante e ainda não integrado: [jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623). O contorno, confirmado empiricamente e sem custo, é passar `--build-dir <diretório>` explicitamente. **A instrução ao consumidor tem de incluir `--build-dir`, não como opção mas como parte do comando documentado** — sem ele, a primeira compilação nativa falha em qualquer jbang anterior à correção de #2623.

- [ ] **Step 1: Fat jar sem plugin novo**

Uma task `Jar` própria que desempacota `configurations.runtimeClasspath` e fixa o `Main-Class`. **Sem Shadow** (Global Constraints); não há `META-INF/services` para fundir (Gson não os usa), por isso a task simples basta. Ligar `Zip64`.

Teste/verificação: `java -jar build/libs/suko-cli-*-all.jar --help` corre e imprime o help. Registar o tamanho do jar no ledger — é a evidência do ganho de D3.

- [ ] **Step 2: A CLI tem de saber a sua própria versão**

D5 diz que a tag por omissão do registry deriva da versão da CLI. Injetar via `Implementation-Version` no MANIFEST do jar (o `suko-maven-plugin` já usa `attributes(...)` no seu jar — mesmo padrão), com uma constante de fallback para quando a CLI corre fora do jar (em teste). Teste: com o jar, a base por omissão contém a tag esperada; sem o jar, usa o fallback e **di-lo** em vez de inventar uma tag errada em silêncio.

- [ ] **Step 3: Catálogo jbang**

`jbang-catalog.json` na raiz, com um alias que aponta para o jar de um GitHub Release. Documentar que isto **não** exige publicação Maven (D1 do subprojeto 7 registou que ela não existe no repo).

- [ ] **Step 4: Scripts wrapper**

`scripts/suko` e `scripts/suko.bat`, mínimos: localizam o jar e fazem `java -jar`. Mensagem clara se não houver `java` no PATH.

- [ ] **Step 5: Gerar a configuração de reflexão com o `native-image-agent`**

O Gson desserializa os records do manifesto **por reflexão**, e `RegistryJson.requireFields` percorre `getRecordComponents()` **em runtime** — nenhuma das duas coisas sobrevive à análise estática do native-image sem configuração. O agente gera essa configuração observando uma execução real.

**O smoke-run tem de cobrir todos os caminhos de desserialização, não só o `add`.** O agente só regista o que a execução tocou; um `suko add button` sozinho exercita a leitura de `RegistryIndex`/`ComponentManifest`/`ComponentFile`/`ExternalRequirement` e a **escrita** do lockfile, mas **não** a leitura do lockfile nem a leitura do `suko.json` — que só acontecem no segundo comando em diante. Uma configuração gerada a partir de um `add` isolado produz um binário que funciona na primeira utilização e falha na segunda, no computador do utilizador, com uma exceção de reflexão ilegível.

Guião obrigatório do smoke-run, contra um registry local (`--registry suko-components/`), tudo na mesma invocação do agente ou em invocações que **acumulem** (`config-merge-dir`):

1. `suko init --yes` (escreve `suko.json`)
2. `suko list` (lê o índice)
3. `suko add field` (lê manifestos, escreve ficheiros e lockfile)
4. `suko list` outra vez, agora com `suko.json` já existente (**lê** a config)
5. `suko diff field` (lê o lockfile)
6. `suko update field` (lê o lockfile e reescreve-o)
7. um caso de erro: `suko add naoexiste` (o caminho das mensagens de erro também constrói objetos)

Commitar o `reflect-config.json` resultante em
`suko-cli/src/main/resources/META-INF/native-image/io.suko/suko-cli/`, mais os outros ficheiros que o agente produzir se forem não-vazios (`resource-config.json`, `proxy-config.json`). **Rever o ficheiro antes de commitar** — o agente costuma incluir entradas de classes do próprio JDK que não são necessárias; entradas dos records de `io.suko.registry` e `io.suko.cli` são as que interessam.

Alternativa registada e **não** adotada, para não ser redescoberta: escrever `TypeAdapter`s explícitos do Gson eliminaria a reflexão de raiz e tornaria o `reflect-config.json` desnecessário. É mais robusto a prazo, mas é código a manter em paralelo ao modelo e sai fora do que esta tarefa pode absorver; se o `reflect-config.json` se tornar difícil de manter, é este o caminho seguinte.

- [ ] **Step 6: Documentar `jbang --native` como o único caminho para o binário, sem nenhuma tarefa Gradle a construí-lo**

**Não existe tarefa Gradle `nativeImage` (nem `Exec`, nem o plugin `org.graalvm.buildtools.native`).** A decisão original desta tarefa (Step 6, antes desta revisão) tinha uma tarefa `Exec` a invocar `native-image` diretamente para produzir e publicar um binário por plataforma. Foi **removida por inteiro** depois de se verificar empiricamente (ver spec, secção "Verificação empírica do mecanismo") que `jbang --native` já sabe fazer isto sozinho, do lado do consumidor, sobre o mesmo fat jar que ele já usa — sem GraalVM no ambiente de build, sem passo de release por plataforma, sem manutenção de uma segunda forma de invocar o `native-image`.

O que este passo produz é **documentação**, não código de build:

- No `suko-cli/README.md` (Tarefa 15) e no `jbang-catalog.json`/comentário ao lado do alias: a instrução oficial e única para o binário nativo é

  ```
  jbang --native --build-dir <diretorio-a-tua-escolha> suko@<owner>
  ```

  — **o `--build-dir` faz parte do comando, não é opcional.** Sem ele, a primeira compilação falha num bug real e reproduzido de `jbang` (ver spec) ao pré-criar o diretório de cache para um alias que aponta para um jar já construído — [jbangdev/jbang#2623](https://github.com/jbangdev/jbang/pull/2623), aberto e não integrado à data desta spec. Com `--build-dir`, o `jbang` compila com o `native-image` que o consumidor já tenha instalado (`GRAALVM_HOME`/`PATH`), guarda o binário nesse diretório, e reutiliza-o em invocações seguintes sem recompilar.
- Requisito do lado do consumidor, a documentar sem ambiguidade: GraalVM instalado localmente. Sem isso, `jbang` falha com o erro do próprio `native-image` em falta — não é algo que a nossa documentação possa evitar, só explicar.
- O fat jar continua a ser o caminho principal; o binário nativo é conveniência de arranque, compilado e mantido pelo consumidor, não por nós.

- [ ] **Step 7: Verificação de desenvolvimento, opt-in, do `reflect-config.json` — via `jbang`, não via `Exec`**

Substitui o antigo "smoke test do binário construído pelo Gradle": já não há binário construído pelo Gradle para testar. Em vez disso, `NativeImageSmokeTest` (ou nome equivalente) invoca o próprio `jbang` como um processo (`ProcessBuilder`), com `@EnabledIfEnvironmentVariable`/`Assumptions` para ser **ignorado** quando `jbang` ou `native-image` não estão disponíveis (o caso normal no ambiente de desenvolvimento, sem GraalVM):

```
jbang build --native --build-dir <tempDir> -m io.suko.cli.Main <caminho para o fat jar recém-construído>
```

seguido de correr o binário resultante contra o **mesmo guião de 7 passos** do Step 5, e afirmar que todos terminam com código 0 e output equivalente ao do fat jar corrido diretamente com `java -jar`.

**Este teste não constrói nem publica nenhum artefacto de release.** É só a verificação, antes de cortar uma release, de que o `reflect-config.json` embutido no fat jar é suficiente — o mesmo mecanismo (`jbang --native`) que o consumidor vai usar, correndo aqui localmente contra o jar que a build acabou de produzir. É este teste que protege contra o modo de falha próprio do native-image: uma entrada em falta no `reflect-config.json` não parte a compilação nem o fat jar — parte só o binário, e só no caminho que não foi exercitado. Sem ele, o primeiro a descobrir é o consumidor.

- [ ] **Step 8: Documentar o que o binário nativo não faz**

No `suko-cli/README.md` (Tarefa 15): o binário nunca é construído nem publicado pelo projeto — é o consumidor, com `jbang --native --build-dir`, que o compila localmente a partir do fat jar publicado, usando o GraalVM que ele já tenha. Não há CI a construí-lo (não há CI), não há matriz de plataformas, e o binário resultante é específico da máquina/plataforma onde o consumidor o compilou. Qualquer comando novo acrescentado à CLI obriga a **reexecutar o agente** — o que também tem de ficar escrito ao lado do `reflect-config.json`, senão o ficheiro apodrece em silêncio. Documentar também, a par da instrução, que `--build-dir` deixa de ser estritamente necessário quando jbangdev/jbang#2623 for lançado numa versão futura — mas continua a ser recomendado, por dar um destino previsível ao binário.

- [ ] **Step 9: Commit**

---

### Task 14: O teste que prova a tese — `suko add` → `sukoCompile` → render

**Agent:** `jte-specialist` (a prova final é render com o motor real, não compilação)

**Files:**
- Create: `suko-cli/src/test/java/io/suko/cli/FullCycleTest.java`
- Modify: `suko-cli/build.gradle.kts` (dependências de teste: `testFixtures(suko-core)`, `gg.jte` pinado, `gradleTestKit`)

**Interfaces:**
- Consome: tudo. É o único teste do plano que atravessa CLI + plugin + compilador + motor de render.

**Nota deliberada sobre as dependências:** este módulo continua **sem** `suko-core` em `main`. As dependências de compilador e render entram só em `src/test`, exatamente como `suko-components` faz desde o subprojeto 7 — o módulo auto-valida-se sem alargar a superfície de produção. Um passo que acrescente `suko-core` a `implementation` está errado.

- [ ] **Step 1: Montar o projeto de consumidor temporário**

Num `@TempDir`: `settings.gradle.kts`, `build.gradle.kts` com `plugins { id("io.suko.lang") }`, e nada mais. Sem nenhum `.sk` escrito à mão.

- [ ] **Step 2: Correr a CLI a sério**

`suko add field --registry <caminho para suko-components/> --base-package com.acme.web --source-root src/main/suko --yes`, invocada pelo mesmo entry point que o jar usa.

Afirmar: três ficheiros escritos nos caminhos certos, `package`/`import` reescritos, lockfile criado com os dois hashes por ficheiro.

- [ ] **Step 3: Compilar com o plugin, via TestKit**

`GradleRunner` com `withPluginClasspath()`, argumento `sukoCompile`.
Expected: `build/generated-src/suko/com/acme/web/ui/{Field,Label,Input}.jte`, com os pacotes espelhados. **Zero diagnósticos** — o `Field` instalado resolve o `Label` e o `Input` instalados ao lado, que é a prova de que a reescrita de imports e o grafo `dependsOn` estão ambos certos.

- [ ] **Step 4: Renderizar com o motor real**

Render do `Field.jte` gerado com `gg.jte` 3.1.12 via `JteRenderSupport`, com parâmetros reais, e asserção sobre o HTML produzido (o `<label>` do `Label` e o `<input>` do `Input` aparecem, dentro do `<div class="space-y-1">` do `Field`). Compilar não chega — convenção do projeto desde o subprojeto 1.

- [ ] **Step 5: Correr também em modo watch**

Repetir o Passo 3 com `sukoWatch` (uma iteração, com paragem controlada) e afirmar o **mesmo** output espelhado. É a verificação de ponta da Tarefa 5, no cenário que a motivou.

- [ ] **Step 6: Commit**

---

### Task 15: Documentação — README da CLI, README da raiz, `ARCHITECTURE.md`

**Agent:** `dx-specialist` (o README é a superfície que o consumidor lê primeiro); `architect` revê a parte do `ARCHITECTURE.md`

**Files:**
- Create: `suko-cli/README.md`
- Modify: `README.md` (raiz)
- Modify: `suko-components/README.md` (a frase "a CLI não existe ainda" deixa de ser verdade)
- Modify: `ARCHITECTURE.md`

- [ ] **Step 1: `suko-cli/README.md`**

Instalação (jbang, jar, **e binário nativo via `jbang --native`**), `suko init`, `suko add`, `suko diff`, `suko update`; o que o lockfile é e porque tem dois hashes; que a CLI **nunca** edita ficheiros que não criou; que a pinagem é por tag do registry e **não** por versão de componente (D5), com o porquê; requisitos externos (Tailwind/Alpine) e o gotcha do `content` do Tailwind, por ponteiro para o README de `suko-components`, não duplicado.

Sobre o binário nativo, escrever o que a Tarefa 13 apurou, com o comando exato: **nunca é construído nem publicado pelo projeto** — é o consumidor que o compila localmente, uma vez, com

```
jbang --native --build-dir <diretorio> suko@<owner>
```

(o `--build-dir` é obrigatório na instrução, não opcional — sem ele a primeira compilação falha num bug conhecido de jbang, jbangdev/jbang#2623, à data desta spec ainda não integrado). Requer GraalVM instalado localmente pelo consumidor. É conveniência de arranque, específico da máquina/plataforma onde foi compilado, não construído em CI (não há CI), e **não** substitui o fat jar — que continua a ser o caminho principal e o que o alias jbang serve mesmo sem `--native`.

- [ ] **Step 2: `suko-components/README.md`**

A secção "**The CLI that consumes this manifest does not exist yet**" é substituída pelo uso real. O resto do ficheiro fica.

- [ ] **Step 3: `README.md` da raiz**

A linha do roadmap "8. Distribution CLI (`suko add`) | Planned" passa a concluída; acrescentar o quickstart de três linhas (instalar, `suko init`, `suko add button`) — é o que faz alguém experimentar.

- [ ] **Step 4: `ARCHITECTURE.md` — "Estrutura de módulos"**

Entram `suko-registry-generator` e `suko-cli`; `suko-registry` passa a descrito como já não dependendo de `suko-core`; **o racional do Gson é corrigido** (afirmava um benefício de fat jar que a dependência `api(suko-core)` anulava — só passou a ser verdade na Tarefa 2); `suko-gradle-plugin` deixa de ser descrito como tendo a lacuna do ID descobrível.

- [ ] **Step 5: `ARCHITECTURE.md` — "Limitações conhecidas"**

**Sai** a lacuna do `sukoWatch` (fechada pela Tarefa 5), substituída pela nota de que o modo watch recompila o source root inteiro a cada evento — característica declarada, não limitação escondida.

**Entram:** a ausência de hash/assinatura dos documentos JSON do registry (a confiança é o TLS); a impossibilidade de pinar uma versão *de componente* independentemente da tag do registry (D5); o binário nativo GraalVM ser exclusivamente `jbang --native --build-dir ...` do lado do consumidor, nunca construído nem publicado pelo projeto, com a dependência de `--build-dir` num bug de jbang ainda aberto à data desta spec (jbangdev/jbang#2623); e qualquer bug registado sob a regra D5 durante as Tarefas 6-14.

- [ ] **Step 6: `ARCHITECTURE.md` — roadmap e decisões de design**

Item 8 passa a CONCLUÍDO com ponteiro para a spec. A ressalva do item 4 é reescrita para ficar **assimétrica**: o caminho Gradle passa a ser suportado end-to-end (ID, convenções, TestKit); o caminho Maven continua sem `plugin.xml`. Em "Decisões de design", entra Java 21 como piso declarado (Tarefa 1), com a saída que foi escolhida no ponto de decisão dessa tarefa.

- [ ] **Step 7: Commit**

---

## Após todas as tarefas

Rodar a suite completa uma última vez (`gradle clean build --console=plain`) e seguir para `superpowers:finishing-a-development-branch` (revisão final de todo o branch antes de merge/PR), tal como nos subprojetos anteriores. A revisão final deste subprojeto deve verificar especificamente sete coisas, porque são as que um review por-tarefa não vê:

1. **`suko-cli` não depende de `suko-core`.** Verificar no grafo de dependências resolvido, não por leitura do `build.gradle.kts`. Se depender, D3 foi feito e desfeito.
2. **Os dois hashes sobreviveram.** Procurar no branch qualquer comparação entre `ComponentFile.sha256()` e um ficheiro lido do disco do consumidor. Não deve existir nenhuma. É o modo de falha mais caro do subprojeto e o mais fácil de reintroduzir numa "simplificação".
3. **A CLI não escreve fora do `sourceRoot` nem edita o que não criou.** Inventariar todas as escritas de ficheiro em `suko-cli/src/main`: devem ser o `sourceRoot`, o `suko.json` e o `suko.lock.json`, e mais nada.
4. **Nenhuma dependência nova entrou.** `gradle :suko-cli:dependencies` e o conteúdo do fat jar. Sem picocli, Shadow, cliente HTTP ou biblioteca de diff. **Sem plugin GraalVM nem tarefa `Exec` de native-image**: a Tarefa 13 não constrói nem publica nenhum binário nativo — se uma tarefa Gradle desse tipo existir no branch, a decisão de unificação em torno de `jbang --native` foi feita e desfeita.
5. **`gradle build` continua verde sem GraalVM instalado.** Não há nenhuma tarefa de release que precise de GraalVM. A única coisa opt-in relacionada é a verificação de desenvolvimento do Step 7 da Tarefa 13 (via `jbang`, não `Exec`); se ela entrou em `build` ou `check`, ou se alguma tarefa Gradle voltou a exigir `native-image`/`GRAALVM_HOME`, partiu o build de toda a gente.
6. **O `reflect-config.json` cobre todos os comandos.** Comparar a lista de comandos da CLI com o guião de smoke-run do Step 5 da Tarefa 13. Um comando acrescentado depois da geração do ficheiro não está coberto, e a falha só aparece no binário nativo, em runtime, no computador do utilizador.
7. **A regra D5 aguentou.** Nenhuma alteração a gramática, AST, emitter ou diagnósticos no branch. Os módulos tocados fora de `suko-cli` são exatamente os nomeados nas decisões: `suko-registry`, `suko-registry-generator`, `suko-gradle-plugin`, `suko-components` (só o `build.gradle.kts`), `settings.gradle.kts` e a raiz.
