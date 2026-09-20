# Suko — Subprojeto 7: Registry e Biblioteca de Componentes — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preencher `suko-components/` com uma biblioteca de 8 componentes `.sk` reais (Tailwind 3.x + Alpine 3.x, um componente público por ficheiro) e com um manifesto JSON **gerado a partir dos fontes**, que descreva cada componente com informação suficiente para a CLI `suko add` do subprojeto 8 fazer o seu trabalho — sem que este subprojeto escreva uma linha de CLI.

**Architecture:** Um módulo novo `suko-registry` (modelo + leitor/escritor JSON + gerador a partir de `.sk`, dependente de `suko-core` e de Gson pinado) é o único sítio com código. `suko-components` passa a ser **conteúdo puro**: `src/main/suko/io/suko/ui/*.sk` + `registry.json` + `components/*.json` gerados e commitados, com `src/test/java` a auto-validar-se (compila e renderiza a biblioteca inteira, e falha se o manifesto divergir dos fontes). Antes de tudo isso, uma tarefa isolada fecha a única correção de compilador pré-aprovada: o `SemanticChecker` passa a descer a HTML aninhado, sem o que os componentes compostos da biblioteca não seriam verificados de todo.

**Tech Stack:** Java 21, ANTLR4 4.13.1, gg.jte / jte-runtime 3.1.12, Gson 2.11.0 (novo, confinado a `suko-registry`), JUnit 5, Gradle multi-módulo.

**Spec:** `docs/superpowers/specs/2026-09-20-suko-registry-componentes.md`

## Global Constraints

- O projeto NÃO tem `gradlew` commitado — todos os comandos usam `gradle` (sistema), nunca `./gradlew`.
- Testes de render usam `JteRenderSupport` (motor `gg.jte` real, não simulado) sempre que o teste dependa de código Java gerado — nunca validar só compilação do `.jte`. Convenção do projeto desde o subprojeto 1.
- Qualquer desvio descoberto durante a implementação é documentado no código, no ponto exato da descoberta, com o texto exato do comportamento observado — convenção obrigatória do projeto.
- Git dentro de worktrees deste harness tem um bug intermitente do sandbox (classificador "rtk") em `git status`/`git diff`/`git add` bare — o workaround é invocar sempre via caminho absoluto `/usr/bin/git <subcomando>`. Aplica-se a todos os passos de commit deste plano.
- **`suko-core` não ganha nenhuma dependência nova.** Gson vive exclusivamente em `suko-registry`. Uma tarefa que precise de JSON em `suko-core` está errada.
- **Nenhuma gramática é tocada.** Este subprojeto não altera `SukoLexer.g4`/`SukoParser.g4`. Se uma tarefa parecer precisar disso, é sinal de um bug fora da lista pré-aprovada — aplica-se a regra D5 (registar, não corrigir).
- **Fronteira 7/8, critério de revisão e não sugestão:** nenhuma tarefa deste plano faz download HTTP, copia ficheiros para fora do repo, reescreve linhas `package`/`import`, ou escreve lockfiles. O 7 produz os **dados**; o 8 é a **ferramenta**. Um passo que faça qualquer dessas coisas é scope creep e deve ser recusado na revisão.
- **Regra D5 (bugs de compilador):** por omissão registar, não corrigir. A lista pré-aprovada tem **um único item** (R2, Tarefa 1). Qualquer outro bug descoberto vira entrada no ledger e, se mudar uma promessa da linguagem, uma limitação no `ARCHITECTURE.md`. Se um bug impedir escrever um componente do v1 e não tiver contorno, o componente **sai do v1** — não é corrigido aqui.
- **Convenções de autoria da biblioteca** (spec, secção homónima), verificadas por teste a partir da Tarefa 5 e portanto obrigatórias em todas as tarefas de conteúdo:
  1. todo componente é `public component`;
  2. um componente por ficheiro, nome do ficheiro = nome do componente;
  3. **nunca** `${...}` dentro de um atributo `class` (quebra o scanner estático do Tailwind em silêncio — variantes são strings de classe completas dentro de `switch`/`if`);
  4. slots nomeados **antes** de conteúdo solto num bloco de chamada;
  5. nada de `{slot ?: "fallback"}`;
  6. nada de `var c = Componente() { ... }`;
  7. nada de `<`/`>` dentro de literais de string;
  8. imports totalmente qualificados e **sem alias**.
- **O manifesto commitado é sempre gerado, nunca editado à mão.** Editar `registry.json` ou `components/*.json` a partir da Tarefa 9 é erro; o caminho é mudar o `.sk` e regenerar.

---

## Descobertas feitas ao escrever este plano (detalhe de implementação, não decisão de design — por isso estão aqui e não na spec)

- **`JteRenderSupport` está em `suko-core/src/test/java/io/suko/lang/support/`** — source set de teste, logo **invisível** para qualquer outro módulo. `suko-components` não lhe consegue chamar sem mais nada. Resolução adotada (Tarefa 5, Passo 1): aplicar `java-test-fixtures` a `suko-core` e mover `JteRenderSupport` para `src/testFixtures/java/io/suko/lang/support/`. O pacote não muda, e o Gradle põe `testFixtures` no classpath de teste do próprio módulo automaticamente — os ~15 testes de `suko-core` que o importam **não** precisam de alteração. Alternativa rejeitada: duplicar um helper de render em `suko-components` (divergiria do original na primeira mudança; e o subprojeto 9 vai querer o mesmo helper).
- **`suko-core` declara `implementation("gg.jte:jte:3.1.12")`, não `api`.** Logo o jte **não** é transitivo para `suko-components`. O teste de render desse módulo precisa da sua própria linha `testImplementation("gg.jte:jte:3.1.12")`, com a versão pinada igual. Não mudar o `implementation` de `suko-core` para `api` só por isto — seria alargar a superfície pública do compilador por conveniência de teste.
- **`suko-registry` expõe tipos de `suko-core` na sua própria API** (o gerador recebe/devolve `ComponentDecl`, `SukoFile`, `ProjectIndex`). Por isso o módulo usa o plugin `java-library` e declara `api(project(":suko-core"))`, não `implementation` — senão `suko-components` (que só depende de `suko-registry` em escopo de teste) não compila o harness.
- **`SemanticChecker` já tem um percurso de `HtmlElement` correto e completo**, mas noutro método: `checkBareBraceInStatement` (`SemanticChecker.java:174-190`) desce a `element.attributes()` e a `element.children()`. Ou seja, R2 não é "ninguém sabe descer a HTML" — é `checkStatement` (`:256-275`) ter um `default -> {}` que apanha `HtmlElement` por omissão. A correção é simétrica ao percurso que já existe ao lado, o que reduz muito o risco.
- **`Statement.HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children, boolean selfClosing, SourceSpan span)`** e `Statement.Attribute(String name, Expr value, SourceSpan span)` — confirmado em `ast/Statement.java:10-15`. Um atributo carrega um `Expr`, logo pode conter uma chamada de componente como valor; a Tarefa 1 **também** desce aos atributos via `checkExprForComponentCalls`, por simetria com o que `checkBareBraceInStatement` já faz.
- **`Statement.ComponentCallStmt` não tem `children` próprios que `checkStatement` ignore** — os slot fills são tratados dentro de `checkComponentCall`. A Tarefa 1 não precisa de lhes tocar.
- **`suko-website/build.gradle.kts` declara `implementation(project(":suko-components"))`** sobre um módulo que, depois deste subprojeto, continua sem código Java em `main`. É uma dependência que não significa nada. **Não é corrigida aqui** (o módulo é âmbito do subprojeto 9); fica registada na Tarefa 10 como achado para o scoping do 9.
- **Não existe infraestrutura de teste de `Task` Gradle neste repo** (`ProjectBuilder` nunca foi usado; `SukoGradlePluginTest` testa `JteCompiler` isolado, apesar do nome). A tarefa `:suko-components:generateRegistry` deste plano é, por isso, uma tarefa fina e sem lógica: toda a lógica está em `suko-registry`, testada por JUnit; a tarefa só a invoca. Não se introduz infraestrutura de teste de Gradle aqui.
- **A versão do projeto é `0.1.0-SNAPSHOT`** (definida em `subprojects {}` na raiz). `registryVersion` no manifesto **não** é derivado dela — é um campo próprio, porque corresponde a uma tag de conteúdo publicado, não à versão do compilador. A Tarefa 4 fixa-o em `0.1.0` e documenta a distinção.

---

### Task 1: R2 — `SemanticChecker` desce a HTML aninhado (única correção de compilador pré-aprovada)

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java`
- Test: `suko-core/src/test/java/io/suko/lang/SemanticCheckerTest.java`
- Test: `suko-core/src/test/java/io/suko/lang/semantic/SemanticCheckerProjectTest.java`
- Possibly modify (só se o inventário do Passo 1 o exigir): `examples/**/*.sk`, fixtures em `suko-core/src/test/resources/`

**Interfaces:**
- Consome: `Statement.HtmlElement.children()`, `.attributes()`; `checkStatementList`, `checkExprForComponentCalls` (ambos já privados e existentes).
- Produz: nenhum tipo novo. `COMPONENT_NOT_FOUND`/`COMPONENT_NOT_VISIBLE`/`SLOT_NOT_FOUND`/`CARDINALITY_VIOLATION` passam a disparar dentro de tags.

**Porquê primeiro:** é a tarefa com custo imprevisível (vai destapar diagnósticos hoje silenciados). Fazê-la antes de existir um único componente da biblioteca mantém o estrago isolado e reversível; nenhuma tarefa seguinte depende dela para **avançar** — só para ser verificada.

- [ ] **Step 1: Inventário do estrago, ANTES de corrigir**

Aplicar a correção localmente **sem commitar** (só para medir) e correr `gradle test --console=plain`, mais uma compilação de `examples/` via `SukoProjectCompiler`. Registar num comentário de trabalho: quantos testes passam a falhar, quantos diagnósticos novos aparecem em `examples/`, e de que tipo.

**Critério de paragem (obrigatório):** se o inventário mostrar que a correção exige reescrever conteúdo/fixtures muito além de ajustes pontuais — regra prática: mais de ~10 ficheiros a precisar de mudança semântica real (não só de expectativa de teste) — **reverter, não corrigir, e reportar**. R2 passa a subprojeto próprio e este plano continua na Tarefa 2 com R2 registado como limitação. Documentar a decisão no ledger com os números observados.

- [ ] **Step 2: Escrever os testes que falham**

Em `SemanticCheckerTest.java`, adicionar casos que hoje passam silenciosamente e deviam falhar:

```java
    @Test
    void reportsUnknownComponentCalledInsideHtmlElement() {
        String source = """
            component Page() {
              <div>NaoExiste()</div>
            }
            """;
        // Espera-se exatamente 1 diagnóstico COMPONENT_NOT_FOUND.
    }

    @Test
    void reportsUnknownComponentNestedTwoLevelsDeep() {
        String source = """
            component Page() {
              <div><section>NaoExiste()</section></div>
            }
            """;
    }

    @Test
    void reportsSlotViolationForComponentCalledInsideHtmlElement() {
        // Componente com `Component header` obrigatório, chamado dentro de
        // <div> sem fill -> SLOT_NOT_FOUND.
    }
```

Em `SemanticCheckerProjectTest.java`, o caso que a spec do subprojeto 5 identificou como o furo real na visibilidade:

```java
    @Test
    void reportsNonVisibleComponentCalledInsideHtmlElement() {
        // componente file-private noutro ficheiro, chamado como
        // <div>Helper()</div> -> COMPONENT_NOT_VISIBLE
    }
```

- [ ] **Step 3: Rodar, confirmar que falham**

Run: `gradle :suko-core:test --tests "io.suko.lang.SemanticCheckerTest" --tests "io.suko.lang.semantic.SemanticCheckerProjectTest" --console=plain`
Expected: FALHA — nenhum diagnóstico é emitido (é exatamente o bug).

- [ ] **Step 4: Adicionar o caso `HtmlElement` a `checkStatement`**

Em `SemanticChecker.java`, no `switch` de `checkStatement` (`:256`), antes do `default -> {}`:

```java
            // R2 (subprojeto 7): sem este caso, os children() de uma tag nunca
            // eram percorridos — e como quase toda a chamada real em Suko é
            // escrita dentro de uma tag, COMPONENT_NOT_FOUND /
            // COMPONENT_NOT_VISIBLE / SLOT_NOT_FOUND / CARDINALITY_VIOLATION
            // eram trivialmente contornáveis. Percurso simétrico ao que
            // checkBareBraceInStatement já fazia ao lado (:174).
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    checkExprForComponentCalls(attribute.value());
                }
                checkStatementList(element.children(), currentScopeSlots);
            }
```

Nota deliberada: `currentScopeSlots` é propagado sem alteração — uma tag HTML não abre escopo de slot novo.

- [ ] **Step 5: Rodar os testes novos, confirmar que passam**

Run: mesmo comando do Passo 3.
Expected: PASS.

- [ ] **Step 6: Rodar a suite completa e absorver a queda**

Run: `gradle test --console=plain`
Expected: possivelmente vermelho. Para cada falha, classificar e tratar **explicitamente**:
- *Expectativa de teste desatualizada* (o teste afirmava "0 diagnósticos" num caso que agora, corretamente, tem 1) → atualizar a expectativa, com comentário a dizer porquê.
- *Bug real no conteúdo de teste/`examples/`* (chamada a componente inexistente ou não-`public` que estava escondida) → corrigir o conteúdo, não o verificador.
- *Falso positivo do verificador* → **parar**. É um bug novo introduzido aqui; corrigir a correção, não silenciar o teste.

- [ ] **Step 7: Compilar `examples/` e tratar o que aparecer**

Run: compilar cada subdiretório de `examples/` com `SukoProjectCompiler` (via um teste temporário ou o caminho já usado por `MultiFileAcceptanceTest`).
Expected: zero diagnósticos. Onde houver, aplicar a mesma classificação do Passo 6.

- [ ] **Step 8: Commit**

```bash
/usr/bin/git add -A
/usr/bin/git commit -m "fix(semantic): SemanticChecker desce a HTML aninhado (R2)"
```

---

### Task 2: Módulo `suko-registry` — esqueleto e modelo de dados

**Agent:** `java-specialist`

**Files:**
- Create: `suko-registry/build.gradle.kts`
- Modify: `settings.gradle.kts`
- Create: `suko-registry/src/main/java/io/suko/registry/RegistryIndex.java`
- Create: `suko-registry/src/main/java/io/suko/registry/ComponentManifest.java`
- Create: `suko-registry/src/main/java/io/suko/registry/ComponentFile.java`
- Create: `suko-registry/src/main/java/io/suko/registry/ExternalRequirement.java`
- Test: `suko-registry/src/test/java/io/suko/registry/ModelTest.java`

**Interfaces:**
- Produz: os quatro records acima, e a constante `RegistryIndex.SCHEMA_VERSION = 1`. As Tarefas 3, 4 e 9 consomem-nos; o subprojeto 8 também.

- [ ] **Step 1: Registar o módulo**

Em `settings.gradle.kts`:

```kotlin
include("suko-core", "suko-registry", "suko-gradle-plugin", "suko-maven-plugin", "suko-components", "suko-website")
```

- [ ] **Step 2: `suko-registry/build.gradle.kts`**

```kotlin
plugins {
    id("java-library")
}

dependencies {
    // `api`, não `implementation`: o gerador expõe tipos de suko-core
    // (ComponentDecl, SukoFile, ProjectIndex) na sua própria assinatura.
    api(project(":suko-core"))

    // Gson em vez de Jackson: um único jar, sem transitivas — relevante
    // para o fat-jar da CLI do subprojeto 8. NUNCA em suko-core (ver
    // Global Constraints).
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

- [ ] **Step 3: Escrever o teste do modelo primeiro**

`ModelTest.java`: construir um `RegistryIndex` com um `ComponentManifest` completo e afirmar os acessores; afirmar que `SCHEMA_VERSION == 1`; afirmar que um manifesto com `dependsOn` vazio é válido.

- [ ] **Step 4: Escrever os records**

Todos `record`, todos no pacote `io.suko.registry`, campos exatamente como a spec ("Formato do manifesto"):

```java
public record RegistryIndex(int schemaVersion, String registryVersion, String basePackage,
                            List<Entry> components) {
    public static final int SCHEMA_VERSION = 1;
    public record Entry(String name, String version, String description,
                        String category, String manifest) { }
}
```

```java
public record ComponentManifest(int schemaVersion, String name, String version, String description,
                                String category, String basePackage, String packageSuffix,
                                String component, List<ComponentFile> files,
                                List<String> dependsOn, List<ExternalRequirement> externalRequirements) { }
```

```java
public record ComponentFile(String path, String target, String sha256) { }
public record ExternalRequirement(String kind, String id, String versionRange) { }
```

Comentário obrigatório em `ComponentFile`: `path` e `target` são **sempre relativos** — `path` à base do registry (URL ou diretório), `target` ao pacote de destino no projeto do consumidor. Um caminho absoluto ou uma URL completa aqui quebra o fallback local de D1.

- [ ] **Step 5: Rodar**

Run: `gradle :suko-registry:test --console=plain`
Expected: PASS.

- [ ] **Step 6: Commit**

---

### Task 3: `suko-registry` — leitor/escritor JSON e resolução de caminhos relativos

**Agent:** `java-specialist`

**Files:**
- Create: `suko-registry/src/main/java/io/suko/registry/RegistryJson.java`
- Create: `suko-registry/src/main/java/io/suko/registry/RegistrySource.java`
- Create: `suko-registry/src/main/java/io/suko/registry/FileSystemRegistrySource.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistryJsonTest.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistrySourceTest.java`

**Interfaces:**
- Produz: `RegistryJson.readIndex/writeIndex/readManifest/writeManifest`; a interface `RegistrySource` (`resolve(String relativePath) -> byte[]` + `base()`), com a implementação de sistema de ficheiros. **A implementação HTTP é do subprojeto 8** — aqui só existe a interface e o caminho local.

- [ ] **Step 1: Escrever os testes primeiro**

`RegistryJsonTest`: round-trip modelo → JSON → modelo, com igualdade estrutural; JSON com `schemaVersion` desconhecido (ex. `99`) produz uma exceção **com mensagem legível** que nomeia a versão encontrada e a suportada, não uma `JsonSyntaxException` crua; JSON com campo obrigatório em falta falha com mensagem que nomeia o campo; ordem de chaves estável na escrita (para o golden da Tarefa 9 não oscilar).

`RegistrySourceTest`: `FileSystemRegistrySource` resolve `components/button.json` relativo à base; recusa caminhos absolutos; recusa caminhos com `..` que escapem à base (contenção de path traversal — é entrada de ficheiro externo no subprojeto 8).

- [ ] **Step 2: Implementar**

`RegistryJson` com Gson configurado para saída estável e indentada (`setPrettyPrinting`), e validação explícita de `schemaVersion` **antes** de desserializar o resto.

`RegistrySource` deliberadamente minúsculo:

```java
public interface RegistrySource {
    /** Lê um caminho relativo à base. Implementações: sistema de ficheiros
     * (aqui) e HTTP (subprojeto 8). A relatividade é o que faz os dois
     * caminhos serem o mesmo código — ver spec, D1. */
    byte[] resolve(String relativePath) throws IOException;
    String base();
}
```

- [ ] **Step 3: Rodar, confirmar verde**

Run: `gradle :suko-registry:test --console=plain`

- [ ] **Step 4: Commit**

---

### Task 4: `suko-registry` — gerador do manifesto a partir dos `.sk`

**Agent:** `java-specialist`

**Files:**
- Create: `suko-registry/src/main/java/io/suko/registry/RegistryGenerator.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistryGeneratorTest.java`
- Create: `suko-registry/src/test/resources/generator-fixtures/**` (mini-biblioteca de 2-3 `.sk`, independente da biblioteca real)

**Interfaces:**
- Produz: `RegistryGenerator.generate(Path sourceRoot, GeneratorConfig config) -> GeneratedRegistry` (índice + manifestos, em memória). É o que a Tarefa 9 invoca duas vezes: para escrever e para comparar.

- [ ] **Step 1: Escrever os testes primeiro, contra fixtures próprias**

Fixtures em `generator-fixtures/io/suko/ui/` com dois componentes, um deles a importar o outro. Testes:
- o nome do componente no manifesto vem do `ComponentDecl.name()`, o `name` do registry é a versão lowercase;
- `packageSuffix` é derivado do `package` menos o `basePackage` configurado;
- `dependsOn` é derivado dos `import` do ficheiro, mapeados para nomes de componentes do próprio registry — e um import para fora do registry é **ignorado**, não erro;
- `sha256` bate com o hash do conteúdo real do ficheiro;
- um ficheiro com dois componentes falha com mensagem explícita (convenção "um por ficheiro");
- um componente sem `public` falha com mensagem explícita;
- o grafo `dependsOn` é validado como acíclico, com mensagem que nomeia o ciclo.

- [ ] **Step 2: Implementar o gerador**

Reusa `ProjectIndex.build(sourceRoot)` e o `SukoAstBuilder` já existentes — **não** reimplementa parsing. **Decidido pelo utilizador:** a `description` vem de uma fonte única e explícita: um ficheiro `descriptions.properties` ao lado dos fontes (chave = nome do componente), não de comentários `//` no código (evita depender de o AST preservar comentários, que hoje não é garantido). Documentar esta escolha no código. **Não** inventar descrições a partir do nome.

- [ ] **Step 3: Rodar, confirmar verde**

Run: `gradle :suko-registry:test --console=plain`

- [ ] **Step 4: Commit**

---

### Task 5: `suko-components` — conteúdo puro, harness de validação e testes de convenção

**Agent:** `java-specialist` (com revisão do `jte-specialist` no passo de render)

**Files:**
- Modify: `suko-core/build.gradle.kts` (aplicar `java-test-fixtures`)
- Move: `suko-core/src/test/java/io/suko/lang/support/JteRenderSupport.java` → `suko-core/src/testFixtures/java/io/suko/lang/support/JteRenderSupport.java`
- Modify: `suko-components/build.gradle.kts`
- Create: `suko-components/src/test/java/io/suko/components/LibraryCompilesTest.java`
- Create: `suko-components/src/test/java/io/suko/components/LibraryConventionsTest.java`
- Create: `suko-components/src/main/suko/io/suko/ui/Button.sk`

**Interfaces:**
- Produz: o harness que todas as tarefas de conteúdo seguintes usam, e as guardas de convenção que as vigiam. `Button` existe aqui apenas para provar que o harness funciona ponta-a-ponta.

- [ ] **Step 1: Expor `JteRenderSupport` a outros módulos**

Em `suko-core/build.gradle.kts`, adicionar `id("java-test-fixtures")` aos plugins e mover o ficheiro para `src/testFixtures/java/io/suko/lang/support/`. O pacote não muda; os testes de `suko-core` que o importam **não** precisam de alteração (o Gradle põe `testFixtures` no classpath de teste do próprio módulo).

Run: `gradle :suko-core:test --console=plain`
Expected: verde, mesma contagem de testes de antes da mudança. Se algum teste deixar de compilar, a mudança está errada — não "arranjar" os imports, perceber porquê.

- [ ] **Step 2: Reconfigurar `suko-components` como conteúdo puro**

```kotlin
plugins {
    id("java")
}

// CONTEÚDO PURO (spec, D3): este módulo não tem `src/main/java` e não
// declara nenhuma dependência em `main`. O que ele contém é `.sk` e o
// manifesto JSON gerado. As dependências abaixo existem só para o módulo
// se auto-validar (compilar e renderizar a própria biblioteca, e verificar
// que o manifesto commitado não divergiu dos fontes).
dependencies {
    testImplementation(project(":suko-registry"))
    testImplementation(testFixtures(project(":suko-core")))
    // suko-core declara jte como `implementation`, não `api` — não é
    // transitivo. Versão pinada igual à de suko-core.
    testImplementation("gg.jte:jte:3.1.12")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
```

A linha `implementation(project(":suko-core"))` do scaffold é **removida**.

- [ ] **Step 3: Escrever `LibraryConventionsTest` — as guardas, antes do conteúdo**

Varre `src/main/suko/**/*.sk` e afirma, para cada ficheiro:
1. declara exatamente um componente e ele é `public`;
2. o nome do ficheiro é igual ao nome do componente;
3. o `package` declarado bate com a pasta (redundante com `PACKAGE_DIRECTORY_MISMATCH`, mas falha mais cedo e com melhor mensagem);
4. **nenhum `${` dentro de um atributo `class`** — a guarda de R3, a mais importante das oito. A verificação é sobre o AST (`Statement.Attribute` com `name().equals("class")` e um `Expr.StringLiteralExpr` com partes não-literais), não sobre o texto, para não apanhar falsos positivos em `x-data`;
5. nenhum `import` com alias.

A suite tem de passar com a pasta vazia (0 ficheiros), para poder ser escrita antes do conteúdo.

- [ ] **Step 4: Escrever `LibraryCompilesTest`**

Compila `src/main/suko` inteiro com `SukoProjectCompiler` e afirma **zero diagnósticos** (de qualquer severidade). Depois renderiza cada componente com `JteRenderSupport.renderProject` e parâmetros mínimos, afirmando que o output não está vazio. Também há de passar com a pasta vazia.

- [ ] **Step 5: Rodar com a pasta vazia**

Run: `gradle :suko-components:test --console=plain`
Expected: PASS (vacuamente). Confirma que o harness e o wiring de módulos estão certos antes de haver conteúdo para eles debitarem.

- [ ] **Step 6: Escrever `Button.sk` — o primeiro componente**

`src/main/suko/io/suko/ui/Button.sk`, `package io.suko.ui;`, `public component Button(...)`. Variantes (`primary`/`secondary`/`ghost`) por `switch` sobre um parâmetro `String variant`, **cada ramo com a string de classe Tailwind completa** — é este componente que prova a convenção 3 e a torna concreta para as tarefas seguintes.

O markup Tailwind exato é deixado ao implementador: escolhê-lo aqui seria código não verificado num plano, e a aparência é juízo de autoria, não decisão de arquitetura. O que **não** é negociável: as oito convenções, o `switch` com classes completas, e o teste de render a passar.

- [ ] **Step 7: Rodar, confirmar que o harness apanha o conteúdo real**

Run: `gradle :suko-components:test --console=plain`
Expected: PASS, agora com `Button` a ser compilado, renderizado e verificado pelas guardas.

- [ ] **Step 8: Prova negativa das guardas**

Temporariamente, mudar `Button.sk` para usar `class="btn btn-${variant}"` e confirmar que `LibraryConventionsTest` **falha**. Reverter. Sem este passo não se sabe se a guarda mais importante do subprojeto está sequer ligada.

- [ ] **Step 9: Commit**

---

### Task 6: Componentes folha — `Input`, `Label`, `Badge`

**Agent:** `jte-specialist`

**Files:**
- Create: `suko-components/src/main/suko/io/suko/ui/Input.sk`
- Create: `suko-components/src/main/suko/io/suko/ui/Label.sk`
- Create: `suko-components/src/main/suko/io/suko/ui/Badge.sk`
- Test: `suko-components/src/test/java/io/suko/components/LeafComponentsRenderTest.java`

**Interfaces:**
- Produz: três componentes sem dependências entre si. `Label` e `Input` são os alvos do `dependsOn` de `Field` (Tarefa 8).

- [ ] **Step 1: Escrever os testes de render primeiro**

Um teste por componente, com asserções sobre o HTML renderizado (atributos presentes, escape aplicado, valor por omissão respeitado quando o parâmetro é `= null`). Render real, não comparação de `.jte`.

- [ ] **Step 2: Escrever os três `.sk`**

- `Input`: prova atributos com `${expr}` (fora de `class`) e parâmetros opcionais (`String placeholder = null`).
- `Label`: o caso mínimo — existe sobretudo para ser dependência de `Field`.
- `Badge`: variantes (mesmo padrão de `switch` do `Button`) mais `Component children`.

- [ ] **Step 3: Rodar a suite do módulo**

Run: `gradle :suko-components:test --console=plain`
Expected: PASS, incluindo as guardas de convenção sobre os três ficheiros novos.

- [ ] **Step 4: Commit**

---

### Task 7: Componentes com `children` — `Alert`, `Card`

**Agent:** `jte-specialist`

**Files:**
- Create: `suko-components/src/main/suko/io/suko/ui/Alert.sk`
- Create: `suko-components/src/main/suko/io/suko/ui/Card.sk`
- Test: `suko-components/src/test/java/io/suko/components/ChildrenComponentsRenderTest.java`

**Interfaces:**
- Produz: os dois componentes que exercitam o mecanismo de `children` implícitos do subprojeto 6.

- [ ] **Step 1: Escrever os testes de render primeiro**

- `Alert`: conteúdo solto **misto** (texto + HTML) no bloco de chamada é recolhido no `children` — o caso que o subprojeto 6 entregou e que nunca foi exercitado por conteúdo real.
- `Card`: `Component children` **mais** slots nomeados opcionais (`Component header = null`, `Component footer = null`), com três casos: só children; children + header; children + header + footer. Em todos, os slots nomeados aparecem **antes** do conteúdo solto no bloco de chamada (convenção 4) — e um comentário no teste a dizer porquê, porque a ordem inversa engole o slot em silêncio.

- [ ] **Step 2: Escrever os dois `.sk`**

- [ ] **Step 3: Rodar**

Run: `gradle :suko-components:test --console=plain`

- [ ] **Step 4 (diagnóstico, não correção): registar o que a ordem inversa faz**

Escrever **um** teste que documenta o comportamento atual da ordem "solto-antes-de-nomeado" com um `Card` real, marcado no nome e num comentário como documentação de limitação conhecida, não como comportamento desejado. Não corrigir a gramática (regra D5).

- [ ] **Step 5: Commit**

---

### Task 8: Composição e interatividade — `Field`, `Dialog`

**Agent:** `jte-specialist` (revisão de `security-specialist` no `Dialog`, por causa do HTML/JS emitido)

**Files:**
- Create: `suko-components/src/main/suko/io/suko/ui/Field.sk`
- Create: `suko-components/src/main/suko/io/suko/ui/Dialog.sk`
- Test: `suko-components/src/test/java/io/suko/components/CompositionRenderTest.java`
- Test: `suko-components/src/test/java/io/suko/components/AlpineInteropTest.java`

**Interfaces:**
- Produz: `Field` (o único componente com `dependsOn` não-vazio — é ele que valida o grafo da Tarefa 9) e `Dialog` (o único com `externalRequirements` de Alpine).

- [ ] **Step 1: `Field` — teste primeiro**

Render real de `Field`, afirmando que o HTML contém o output de `Label` **e** de `Input`. Este é o teste que prova a resolução cross-ficheiro do subprojeto 5 sobre conteúdo real — e, depois da Tarefa 1, prova também que as chamadas aninhadas dentro de `<div>` são efetivamente **verificadas**, não só emitidas.

- [ ] **Step 2: Escrever `Field.sk`**

`import io.suko.ui.Label;` e `import io.suko.ui.Input;` — totalmente qualificados, **sem alias** (convenção 8: é a substituição de prefixo do subprojeto 8 que depende disto).

- [ ] **Step 3: `Dialog` — teste primeiro**

Render real afirmando que: (a) `x-data="{ open: false }"` sai **literalmente** no HTML, sem ser confundido com interpolação; (b) um `${expr}` real no mesmo componente **é** interpolado; (c) o conteúdo interpolado é escapado em HTML. É a prova empírica da decisão de sintaxe do subprojeto 6 (rejeitar chaveta nua) contra o uso real que a motivou.

- [ ] **Step 4: Escrever `Dialog.sk`**

- [ ] **Step 5: Rodar a suite completa do módulo**

Run: `gradle :suko-components:test --console=plain`
Expected: PASS, com os 8 componentes compilados, renderizados e sob as guardas.

- [ ] **Step 6: Commit**

---

### Task 9: Gerar e validar o manifesto real

**Agent:** `java-specialist`

**Files:**
- Modify: `suko-components/build.gradle.kts` (tarefa `generateRegistry`)
- Create: `suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java`
- Modify (gerados): `suko-components/registry.json`, `suko-components/components/*.json`

**Interfaces:**
- Produz: o manifesto real, commitado. **É este o artefacto que desbloqueia o subprojeto 8** — a partir daqui o formato é estável e o 8 pode começar mesmo que o conteúdo ainda cresça.

- [ ] **Step 1: Escrever o teste golden primeiro**

`RegistryGoldenTest` invoca `RegistryGenerator.generate` sobre `src/main/suko` e compara com o JSON commitado. Divergência = falha, com uma mensagem que diz literalmente como regenerar (`gradle :suko-components:generateRegistry`). Mais dois testes no mesmo ficheiro:
- **grafo:** todo o nome em `dependsOn` existe no índice; o grafo é acíclico (`field → label, input` é o único arco esperado hoje);
- **versão × conteúdo:** o `sha256` de cada ficheiro bate com o conteúdo real em disco. É esta comparação que faz "conteúdo mudado sem `version` mudada" falhar o build.

- [ ] **Step 2: Rodar, confirmar que falha**

Expected: FALHA — `registry.json` ainda tem `{"components": []}` do scaffold.

- [ ] **Step 3: Adicionar a tarefa `generateRegistry`**

Tarefa Gradle fina, sem lógica própria (toda a lógica está em `suko-registry`, já testada): invoca o gerador e escreve `registry.json` + `components/*.json`. **Não** entra no `build` normal — o commitado é a referência, a tarefa é o caminho para o atualizar.

- [ ] **Step 4: Gerar e inspecionar o resultado à mão**

Run: `gradle :suko-components:generateRegistry --console=plain`

Ler o JSON gerado e confirmar, com os próprios olhos, que ele contém tudo o que o subprojeto 8 precisa: `basePackage`/`packageSuffix` separados (reescrita de namespace), `files[].target` (destino), `sha256` (deteção de edição local), `dependsOn` (grafo), `externalRequirements` (Tailwind/Alpine). Se faltar alguma coisa para o 8, é **agora** que se acrescenta — depois do 8 começar, mudar o formato custa muito mais.

- [ ] **Step 5: Rodar, confirmar verde**

Run: `gradle :suko-components:test --console=plain`

- [ ] **Step 6: Commit**

---

### Task 10: Documentação — `README.md` do módulo e `ARCHITECTURE.md`

**Agent:** `dx-specialist` (o `README` é a superfície que o consumidor lê primeiro); `architect` revê a parte do `ARCHITECTURE.md`

**Files:**
- Modify: `suko-components/README.md`
- Modify: `suko-components/src/main/suko/README.md` (ou remover, se o de cima o cobrir)
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- Produz: as promessas consumer-facing escritas num sítio onde alguém as lê antes de bater nelas.

- [ ] **Step 1: `suko-components/README.md`**

Deixa de ser "scaffolding only". Passa a cobrir:
- o que a biblioteca é e como é distribuída (copy-source, o consumidor fica dono do código);
- **requisitos externos, com versões: Tailwind 3.x e Alpine 3.x.** Incluindo o gotcha real: o `content` do Tailwind tem de varrer os `.sk` (ou os `.jte` gerados, que ficam em `build/` — com as consequências disso). Isto é promessa consumer-facing, não detalhe interno;
- as oito convenções de autoria, para quem contribuir com um componente novo;
- como regenerar o manifesto;
- que a CLI que consome isto é o subprojeto 8 e ainda não existe.

- [ ] **Step 2: `ARCHITECTURE.md` — "Estrutura de módulos"**

`suko-components` deixa de ser descrito como scaffold e passa a **conteúdo puro** (sem `src/main/java`, sem dependências em `main`); entra `suko-registry` com o seu papel e as suas dependências (incluindo Gson 2.11.0, e a nota de que não toca `suko-core`).

Registar como achado, sem corrigir: `suko-website` continua a declarar `implementation(project(":suko-components"))` sobre um módulo sem código Java — a dependência correta é sobre `suko-registry`, e a decisão pertence ao scoping do subprojeto 9.

- [ ] **Step 3: `ARCHITECTURE.md` — "Limitações conhecidas"**

**Remover** a limitação do `SemanticChecker` não descer a HTML aninhado, incluindo o parágrafo "Consequência prática (revisão final do subprojeto 5)" que dizia que o modelo de visibilidade era "verificado apenas no nível de topo" — resolvido pela Tarefa 1. É a única limitação que este subprojeto fecha; se a Tarefa 1 tiver acionado o critério de paragem, **não** remover, e em vez disso registar que R2 passou a subprojeto próprio.

**Acrescentar** a limitação de autoria Tailwind × interpolação: `class="btn btn-${variant}"` compila e renderiza mas não estiliza, porque o scanner do Tailwind é estático e nunca vê a classe composta. Não é só convenção de biblioteca — é uma limitação real de quem escreve `.sk` com Tailwind, e por isso pertence ao `ARCHITECTURE.md` e não só ao `README` do módulo.

**Acrescentar** qualquer bug registado sob a regra D5 durante as Tarefas 5-8.

- [ ] **Step 4: `ARCHITECTURE.md` — roadmap**

Item 7 passa a CONCLUÍDO com ponteiro para `docs/superpowers/specs/2026-09-20-suko-registry-componentes.md`, e uma frase sobre o que ficou entregue (8 componentes, manifesto gerado, `suko-registry`).

- [ ] **Step 5: Commit**

---

## Após todas as tarefas

Rodar a suite completa uma última vez (`gradle test --console=plain`) e seguir para `superpowers:finishing-a-development-branch` (revisão final de todo o branch antes de merge/PR), tal como nos subprojetos anteriores. A revisão final deste subprojeto deve verificar especificamente três coisas, porque são as que um review por-tarefa não vê:

1. **A fronteira 7/8 aguentou** — nenhum ficheiro do branch faz download, copia para fora do repo, reescreve `package`/`import`, ou escreve lockfiles.
2. **O manifesto é suficiente para o 8** — lendo só `registry.json` + `components/*.json`, é possível descrever por escrito, sem abrir mais nada, o algoritmo completo de `suko add field`. Se não for, falta um campo.
3. **A regra D5 aguentou** — a única correção de compilador no branch é a da Tarefa 1. Qualquer outra é scope creep, mesmo que seja uma boa correção.
