# Segurança por omissão (subprojeto 14) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O compilador Suko fecha, em compile-time, o que o JTE não vê (protocolos de URL, sinks perigosos, atributos de frameworks que avaliam código, sintaxe JTE em texto), o registry passa a ser assinado e verificado, e `examples/` passa a ser uma loja de e-commerce executável (Spring Boot + H2) que serve de demonstração e de alvo do futuro pentest.

**Architecture:** Uma classe `SukoSafe` é **gerada** no projeto consumidor (só JDK, sem dependência de runtime do Suko) a partir de um template em `suko-jte`; o `JteEmitter` embrulha os atributos de URL não literais nela. As regras de sinks vivem num `Checker` (`HtmlSecurityChecker`) registado pela extensão `io.suko.jte`, partilhando uma classe de regras (`HtmlSecurityRules`) com o emissor. O alvo ganha emissão ao nível do projeto (`Target.emitProject`, método `default` — compatível). O registry passa ao esquema 2 (índice com `registryId`/`ref`/`issuedAt`/`expires`/`manifestSha256`, assinado por Ed25519 sobre os bytes exatos) e a CLI verifica tudo antes de escrever em disco.

**Tech Stack:** Java 21, Gradle 9.x, gg.jte 3.1.12, jsoup 1.17.2 (testes), JDK `java.security` Ed25519, Gson (já presente), Spring Boot 3.3.4 + `jte-spring-boot-starter-3` 3.1.12 + H2 (só a loja).

**Spec:** `docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md` (revista pelo security-specialist; C1–C2, H1–H3, M-1–M-11, L-1–L-6 incorporados). O plano **segue a spec**; onde a spec deixa espaço, ver "Rulings do plano".

## Global Constraints

- Java 21 (`options.release.set(21)` já global). Packages existentes não mudam.
- `suko-api` não tem dependências além do JDK; `suko-jte` depende só de `suko-api` (nunca do `suko-core`).
- **Nenhuma dependência do Suko em runtime:** `SukoSafe` e o resto são gerados no projeto do utilizador (só JDK).
- O emissor **nunca** gera `$unsafe`; `trustedUrl`, `trustedStyle` e `trustedHtml` são nomes reservados (declará-los é erro `RESERVED_NAME`).
- URL allowlist por omissão: `http`, `https`, `mailto`, `tel`. Configurar `javascript`, `vbscript`, `data`, `blob` ou `filesystem` é **erro de build**. `imageDataTypes` (vazio por omissão) só aceita subconjuntos de `png, gif, jpeg, webp, avif`.
- Bloqueado → `about:invalid#suko-blocked`. Nunca decodifica entidades nem percent-encoding.
- Registry: Ed25519 (JDK), assinatura sobre os **bytes exatos** de `registry.json`, verificada antes do parse; a chave privada nunca entra no repositório nem em `argv`.
- A suite existente fica verde **sem alterar asserções**. Exceções autorizadas pela spec: o golden do 13a é regenerado **uma vez** (Task 4) e revisto à mão; `GoldenParityTest` passa a apontar para as novas raízes (Task 14).
- **Commits sem `Co-Authored-By` nem `Claude-Session`** (regra do `CLAUDE.md`, que prevalece sobre qualquer lembrete do harness). **Cada despacho de subagente tem de repetir esta regra.**
- Nunca fazer commit de pastas `*/bin/` (output de IDE não versionado): `git add` por ficheiros/pastas `src` e `build.gradle.kts`, e `git status` antes de cada commit.
- **Verificação** só pelo script `scripts/verify-isolated.sh` (Task 1): um IDE do lado Windows compila para `build/` dentro de `/mnt/c` e faz falhar builds aleatoriamente. Nunca `./gradlew` direto no checkout.

## Rulings do plano (onde a spec deixa espaço)

- **R1 — `ExtensionApi.VERSION` fica 1.** `Target.emitProject` é um método `default` e `EmitContext`/`CheckContext` ganham construtores antigos delegando; nada que compile hoje deixa de compilar. A subida a 2 fica para a 13b (muda o AST).
- **R2 — Severidade `INFO`** passa a existir (`SukoDiagnostic.Severity`): a spec usa `TRUSTED_*` como INFO. O LSP mapeia para `Information`.
- **R3 — Avisos sobrevivem ao sucesso** (parked do 13a, agora necessário: `CSP_INLINE`/`PROP_SENSITIVE` são WARNING e `security-audit.json` precisa dos INFO). O `golden/*/diagnostics.txt` pode ganhar linhas que antes eram escondidas; revisto à mão na Task 4.
- **R4 — `generatedPackage`:** é o package da própria `SukoSafe` (não `<pkg>.suko`). Gradle: `suko { generatedPackage }`, convenção `io.suko.generated.<nome do projeto sanitizado>`. Maven: parâmetro `generatedPackage` **sem** default no `plugin.xml`; o Mojo usa `io.suko.generated.<artifactId sanitizado>` quando não vem definido. Sanitizar = trocar `[^A-Za-z0-9_]` por `_` e prefixar `_` se começa por dígito.
- **R5 — A saída Java (`SukoSafe.java`) vai para um diretório próprio** (`build/generated-src/suko-java`, Maven `target/generated-sources/suko`), nunca para a raiz dos `.jte`. O plugin Gradle liga-o ao `sourceSets.main.java` e faz `compileJava` depender de `sukoCompile`; o Mojo faz `project.addCompileSourceRoot`.
- **R6 — Chave pública do registry oficial:** o repo não traz chave de produção (nada foi publicado). `trusted-keys.json` (recurso do `suko-cli`) sai com **lista vazia** para o registry oficial; gerar o par e embutir a pública é um passo da checklist de release (`suko-registry-generator --generate-key` existe para isso). Um registry HTTPS sem chave configurada falha fechado.
- **R7 — Sem `.sig` commitado** para `suko-components/registry.json` (seria uma assinatura com chave de teste, inútil). O teste do gerador assina e verifica com um par gerado no teste. A spec dizia que o `RegistryGoldenTest` verifica o `.sig`; desvio registado.
- **R9 — Playwright fica para o pentest.** A spec previa testes de browser com CSP e Trusted Types; hoje o Suko não gera JS no cliente (isso é a 13b), por isso não há nada para o browser executar além do HTML. A loja é coberta por MockMvc + jsoup + o corpus (Tasks 8 e 13), e os testes de browser com CSP/Trusted Types entram quando existirem ilhas, no exercício de pentest. Desvio registado.
- **R10 — O `HtmlSecurityChecker` é um `Checker`, não código do `SemanticChecker`.** A spec dizia que o `SemanticChecker` emitia `UPPERCASE_NAME`; fica no checker de segurança (mesma extensão `io.suko.jte`) para o core continuar sem regras de HTML.
- **R8 — Loja como build separado** (`examples/shop`, `includeBuild("../..")` no seu `settings.gradle.kts` para obter o plugin `io.suko.lang`); **não** entra no `settings.gradle.kts` da raiz (Spring Boot pesado e ciclo de plugins). Corre-se com `scripts/verify-isolated.sh -p examples/shop test`.

## Review Focus

1. **`null` num atributo de URL** (`href=${maybeNull}`): o atributo tem de ser omitido (smart attribute do JTE), nunca `href="null"` nem `about:invalid`. Teste na Task 3.
2. **`@` em texto** (`a@b.com`, `@if(true){X}@endif`): o e-mail sai igual; as diretivas saem inertes. Teste na Task 3.
3. **Componentes existentes** (`Dialog.sk` com `x-data="{ open: false }"` literal) continuam a compilar; `x-data="${dyn}"` dá `UNSAFE_SINK`. Teste na Task 4.
4. **Avisos de checkers visíveis num build bem-sucedido** (Gradle e Maven). Testes nas Tasks 1, 6 e 7.
5. **Registry: rollback e anti-strip** — índice antigo assinado válido é recusado; registry já visto assinado sem `.sig` é recusado mesmo com `--allow-unsigned`. Testes na Task 11.

---

### Task 1: Base — `INFO`, `SecurityOptions`, contextos, `Target.emitProject`, avisos no sucesso, script de verificação

**Files:**
- Create: `scripts/verify-isolated.sh`
- Modify: `suko-api/src/main/java/io/suko/lang/diagnostic/SukoDiagnostic.java` (`Severity.INFO`)
- Create: `suko-api/src/main/java/io/suko/ext/SecurityOptions.java`
- Create: `suko-api/src/main/java/io/suko/ext/ProjectOutput.java`, `ProjectEmitContext.java`
- Modify: `suko-api/src/main/java/io/suko/ext/Target.java` (`emitProject` default), `EmitContext.java`, `CheckContext.java`
- Modify: `suko-core/src/main/java/io/suko/lang/JteCompiler.java`, `suko-core/src/main/java/io/suko/lang/project/SukoProjectCompiler.java`
- Modify: `suko-lsp/src/main/java/io/suko/lsp/DiagnosticsService.java` (mapeamento `INFO`)
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoCompileTask.java`, `SukoWatchTask.java` (log de `INFO`)
- Test: `suko-api/src/test/java/io/suko/ext/SecurityOptionsTest.java`, `suko-core/src/test/java/io/suko/lang/ext/WarningsSurviveTest.java`

**Interfaces:**
- Produces:
  - `io.suko.ext.SecurityOptions` (record): `generatedPackage`, `urlSchemes`, `imageDataTypes`, `strictCsp`, `codeAttributes`, `urlAttributes`; `SecurityOptions.DEFAULT`; `withGeneratedPackage(String)`; `withUrlSchemes(Set<String>)`; `withImageDataTypes(Set<String>)`; `withStrictCsp(boolean)`; `withCodeAttributes(Set<String>)`; `withUrlAttributes(Set<String>)`; `static String sanitizePackageSegment(String)`.
  - `io.suko.ext.ProjectOutput(Kind kind, String relativePath, String source)` com `enum Kind { TEMPLATE, JAVA_SOURCE, RESOURCE }`.
  - `io.suko.ext.ProjectEmitContext(ProjectView project, SecurityOptions options)`.
  - `Target.emitProject(ProjectEmitContext)` → `List<ProjectOutput>` (default `List.of()`).
  - `EmitContext(file, project, importedByShortName, packagePrefix, options)` e o construtor antigo de 4 argumentos.
  - `CheckContext(fileName, project, diagnostics, options, activeVocabularies)` e o construtor antigo de 3 argumentos (`DEFAULT`, `Set.of()`).
  - `new JteCompiler(fileName, source, registry, targets, options)`; `new SukoProjectCompiler(registry, targets, options)`.
  - `SukoProjectCompiler.ProjectCompileResult` ganha `List<ProjectCompileResult.Output> projectOutputs()` com `record Output(String targetId, ProjectOutput.Kind kind, Path relativePath, String source)`; construtor antigo de 4 argumentos mantido.

- [ ] **Step 1: Script de verificação isolada**

Criar `scripts/verify-isolated.sh` (executável):

```bash
#!/usr/bin/env bash
# Corre o Gradle numa cópia do repositório no sistema de ficheiros do Linux.
# Porquê: um IDE do lado Windows compila para build/ dentro de /mnt/c e faz
# falhar builds aleatoriamente ("bad class file", "cannot find symbol").
#
# Uso:  scripts/verify-isolated.sh :suko-core:test --tests '*Foo*'
# Env:  SUKO_VERIFY_DIR  pasta da cópia (por omissão /tmp/suko-verify)
#       SUKO_PULL        caminhos (relativos à raiz) a copiar DE VOLTA depois do
#                        Gradle, separados por espaço — p.ex. os goldens
#                        regenerados com -Dsuko.updateGolden=true
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
V="${SUKO_VERIFY_DIR:-/tmp/suko-verify}"
mkdir -p "$V"
rsync -a --delete \
  --exclude='.git' --exclude='build' --exclude='bin' --exclude='.gradle' \
  --exclude='.superpowers' --exclude='.claude' --exclude='.kilo' \
  --exclude='node_modules' --exclude='out' \
  "$ROOT/" "$V/"
status=0
( cd "$V" && ./gradlew --console=plain "$@" ) || status=$?
for p in ${SUKO_PULL:-}; do
  mkdir -p "$ROOT/$p"
  rsync -a --delete "$V/$p/" "$ROOT/$p/"
done
exit $status
```

Run: `chmod +x scripts/verify-isolated.sh && scripts/verify-isolated.sh :suko-api:compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Teste de `SecurityOptions` (falha)**

`suko-api/src/test/java/io/suko/ext/SecurityOptionsTest.java`:

```java
package io.suko.ext;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SecurityOptionsTest {

    @Test
    void defaultsAreTheSpecAllowlist() {
        SecurityOptions o = SecurityOptions.DEFAULT;
        assertEquals(Set.of("http", "https", "mailto", "tel"), o.urlSchemes());
        assertTrue(o.imageDataTypes().isEmpty());
        assertFalse(o.strictCsp());
        assertEquals("io.suko.generated", o.generatedPackage());
    }

    @Test
    void forbiddenSchemesAreABuildError() {
        for (String bad : new String[] {"javascript", "VBScript", "data", "blob", "filesystem"}) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SecurityOptions.DEFAULT.withUrlSchemes(Set.of("https", bad)));
            assertTrue(e.getMessage().toLowerCase().contains(bad.toLowerCase()), e.getMessage());
        }
    }

    @Test
    void schemesAreNormalisedToLowerCaseAndSorted() {
        SecurityOptions o = SecurityOptions.DEFAULT.withUrlSchemes(Set.of("HTTPS", "Mailto"));
        assertEquals(java.util.List.of("https", "mailto"), java.util.List.copyOf(o.urlSchemes()));
    }

    @Test
    void imageDataTypesAreLimitedToRasterFormats() {
        assertEquals(Set.of("png", "webp"),
            SecurityOptions.DEFAULT.withImageDataTypes(Set.of("png", "webp")).imageDataTypes());
        assertThrows(IllegalArgumentException.class,
            () -> SecurityOptions.DEFAULT.withImageDataTypes(Set.of("svg+xml")));
    }

    @Test
    void generatedPackageMustBeAJavaPackage() {
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("a-b.c"));
        assertThrows(IllegalArgumentException.class, () -> SecurityOptions.DEFAULT.withGeneratedPackage("1abc"));
        assertEquals("com.acme.shop", SecurityOptions.DEFAULT.withGeneratedPackage("com.acme.shop").generatedPackage());
    }

    @Test
    void sanitizePackageSegment() {
        assertEquals("suko_shop", SecurityOptions.sanitizePackageSegment("suko-shop"));
        assertEquals("_9lives", SecurityOptions.sanitizePackageSegment("9lives"));
        assertEquals("_", SecurityOptions.sanitizePackageSegment(""));
    }

    @Test
    void attributeListsAreLowerCased() {
        SecurityOptions o = SecurityOptions.DEFAULT.withCodeAttributes(Set.of("Data-Eval")).withUrlAttributes(Set.of("Data-Href"));
        assertEquals(Set.of("data-eval"), o.codeAttributes());
        assertEquals(Set.of("data-href"), o.urlAttributes());
    }
}
```

Run: `scripts/verify-isolated.sh :suko-api:test --tests '*SecurityOptionsTest*'`
Expected: FAIL (compilação: `SecurityOptions` não existe).

- [ ] **Step 3: `SecurityOptions`, `ProjectOutput`, `ProjectEmitContext`**

`suko-api/src/main/java/io/suko/ext/SecurityOptions.java`:

```java
package io.suko.ext;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Configuração de segurança do projeto (subprojeto 14), lida do build
 * (Gradle {@code suko { security { ... } }}, Maven {@code <security>}) e
 * entregue às extensões nos contextos de emissão e de verificação.
 * Os conjuntos são ordenados para o código gerado ser determinístico.
 */
public record SecurityOptions(String generatedPackage, Set<String> urlSchemes, Set<String> imageDataTypes,
                              boolean strictCsp, Set<String> codeAttributes, Set<String> urlAttributes) {

    public static final Set<String> DEFAULT_URL_SCHEMES = sorted(Set.of("http", "https", "mailto", "tel"));
    private static final Set<String> FORBIDDEN_SCHEMES = Set.of("javascript", "vbscript", "data", "blob", "filesystem");
    private static final Set<String> RASTER_IMAGE_TYPES = Set.of("png", "gif", "jpeg", "webp", "avif");
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

    public static final SecurityOptions DEFAULT = new SecurityOptions(
        "io.suko.generated", DEFAULT_URL_SCHEMES, Set.of(), false, Set.of(), Set.of());

    public SecurityOptions {
        if (generatedPackage == null || !PACKAGE.matcher(generatedPackage).matches()) {
            throw new IllegalArgumentException("generatedPackage inválido (esperado um package Java): " + generatedPackage);
        }
        urlSchemes = sorted(lower(urlSchemes));
        if (urlSchemes.isEmpty()) {
            throw new IllegalArgumentException("urlSchemes não pode ser vazio");
        }
        for (String scheme : urlSchemes) {
            if (FORBIDDEN_SCHEMES.contains(scheme)) {
                throw new IllegalArgumentException("O esquema '" + scheme
                    + "' nunca pode ser permitido em urlSchemes (javascript, vbscript, data, blob, filesystem)");
            }
        }
        imageDataTypes = sorted(lower(imageDataTypes));
        for (String type : imageDataTypes) {
            if (!RASTER_IMAGE_TYPES.contains(type)) {
                throw new IllegalArgumentException("imageDataTypes só aceita " + new TreeSet<>(RASTER_IMAGE_TYPES)
                    + ", recebeu '" + type + "'");
            }
        }
        codeAttributes = sorted(lower(codeAttributes));
        urlAttributes = sorted(lower(urlAttributes));
    }

    public SecurityOptions withGeneratedPackage(String value) {
        return new SecurityOptions(value, urlSchemes, imageDataTypes, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withUrlSchemes(Set<String> value) {
        return new SecurityOptions(generatedPackage, value, imageDataTypes, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withImageDataTypes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, value, strictCsp, codeAttributes, urlAttributes);
    }

    public SecurityOptions withStrictCsp(boolean value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, value, codeAttributes, urlAttributes);
    }

    public SecurityOptions withCodeAttributes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, strictCsp, value, urlAttributes);
    }

    public SecurityOptions withUrlAttributes(Set<String> value) {
        return new SecurityOptions(generatedPackage, urlSchemes, imageDataTypes, strictCsp, codeAttributes, value);
    }

    /** Troca {@code [^A-Za-z0-9_]} por {@code _} e prefixa {@code _} se começar por dígito ou ficar vazio. */
    public static String sanitizePackageSegment(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^A-Za-z0-9_]", "_");
        if (s.isEmpty() || Character.isDigit(s.charAt(0))) {
            s = "_" + s;
        }
        return s;
    }

    private static Set<String> lower(Set<String> in) {
        TreeSet<String> out = new TreeSet<>();
        for (String s : in) {
            out.add(s.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static Set<String> sorted(Set<String> in) {
        return Collections.unmodifiableSortedSet(new TreeSet<>(in));
    }
}
```

`ProjectOutput.java`:

```java
package io.suko.ext;

/** Um ficheiro produzido ao nível do projeto (não por componente), ex.: a SukoSafe.java do alvo JTE. */
public record ProjectOutput(Kind kind, String relativePath, String source) {
    public enum Kind {
        /** Vai para a raiz dos templates (como os .jte). */
        TEMPLATE,
        /** Vai para o diretório de fontes Java geradas. */
        JAVA_SOURCE,
        /** Vai para os recursos estáticos. */
        RESOURCE
    }
}
```

`ProjectEmitContext.java`:

```java
package io.suko.ext;

import io.suko.lang.project.ProjectView;

public record ProjectEmitContext(ProjectView project, SecurityOptions options) {
}
```

`Target.java` — acrescentar o método (e o import `java.util.List`):

```java
    /** Ficheiros do projeto inteiro (não por componente). Por omissão nenhum. */
    default java.util.List<ProjectOutput> emitProject(ProjectEmitContext ctx) {
        return java.util.List.of();
    }
```

`EmitContext.java` (substituir o record):

```java
public record EmitContext(SukoFile file, ProjectView project,
                          Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix,
                          SecurityOptions options) {

    /** Construtor do 13a: opções por omissão. */
    public EmitContext(SukoFile file, ProjectView project,
                       Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix) {
        this(file, project, importedByShortName, packagePrefix, SecurityOptions.DEFAULT);
    }
}
```

`CheckContext.java` (substituir o record; manter `report`):

```java
public record CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics,
                           SecurityOptions options, java.util.Set<String> activeVocabularies) {

    /** Construtor do 13a: opções por omissão, vocabulários ativos desconhecidos. */
    public CheckContext(String fileName, ProjectView project, DiagnosticCollector diagnostics) {
        this(fileName, project, diagnostics, SecurityOptions.DEFAULT, java.util.Set.of());
    }

    public void report(SukoDiagnostic.Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, fileName, span));
    }
}
```

`SukoDiagnostic.java`: `ERROR, WARNING` → `ERROR, WARNING, INFO`.

- [ ] **Step 4: `SecurityOptionsTest` passa**

Run: `scripts/verify-isolated.sh :suko-api:test`
Expected: PASS.

- [ ] **Step 5: Teste dos avisos no sucesso (falha)**

`suko-core/src/test/java/io/suko/lang/ext/WarningsSurviveTest.java`:

```java
package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarningsSurviveTest {

    static final class WarnExtension implements SukoExtension {
        public String id() { return "io.suko.test.warn"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.checker(new Checker() {
                public String id() { return "warn"; }
                public void check(io.suko.lang.ast.SukoFile file, CheckContext c) {
                    c.report(SukoDiagnostic.Severity.WARNING, "TEST_WARN", "aviso de teste", io.suko.lang.ast.SourceSpan.NONE);
                    c.report(SukoDiagnostic.Severity.INFO, "TEST_INFO", "info de teste", io.suko.lang.ast.SourceSpan.NONE);
                }
            });
        }
    }

    @Test
    void singleFileCompileKeepsWarningsOnSuccess() {
        ExtensionRegistry registry = ExtensionRegistry.of(List.of(new WarnExtension(), jte()));
        var result = new JteCompiler("A.sk", "component A() { <p>x</p> }", registry, List.of("jte")).compile();
        assertTrue(result.success());
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("TEST_WARN")));
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("TEST_INFO")));
    }

    @Test
    void projectCompileKeepsWarningsOnSuccess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.sk"), "component A() { <p>x</p> }");
        ExtensionRegistry registry = ExtensionRegistry.of(List.of(new WarnExtension(), jte()));
        var result = new SukoProjectCompiler(registry, List.of("jte")).compile(dir);
        assertTrue(result.success());
        var diags = result.diagnosticsByFile().get(Path.of("A.sk")).getDiagnostics();
        assertTrue(diags.stream().anyMatch(d -> d.code().equals("TEST_WARN")), diags.toString());
    }

    @Test
    void infoIsNotAnError() {
        var c = new io.suko.lang.diagnostic.DiagnosticCollector();
        c.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO, "m", "X", "f", io.suko.lang.ast.SourceSpan.NONE));
        assertFalse(c.hasErrors());
    }

    private static SukoExtension jte() {
        return new io.suko.jte.JteExtension();
    }
}
```

(Se `SukoProjectCompiler.compile(Path)` não existir com esta assinatura, usar a que o `GoldenParityTest` usa: `new SukoProjectCompiler().compile(root)` aceita `Path`.)

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*WarningsSurviveTest*'`
Expected: FAIL (`projectCompileKeepsWarningsOnSuccess` e `singleFileCompileKeepsWarningsOnSuccess`: o sucesso devolve um coletor vazio).

- [ ] **Step 6: Compiladores — opções, contextos, avisos, saídas de projeto**

Em `JteCompiler.java`:
1. Acrescentar o campo `private final SecurityOptions options;` e o construtor
   `public JteCompiler(String fileName, String sukoSource, ExtensionRegistry registry, List<String> targets, SecurityOptions options)`; o construtor de 4 argumentos delega com `SecurityOptions.DEFAULT`; o de 2 argumentos continua a delegar para o de 4.
2. `runExtensionChecks`: calcular os vocabulários ativos (guardado) e passar contexto novo:

```java
        java.util.Set<String> active = new java.util.TreeSet<>();
        for (Target t : resolved) {
            try {
                active.addAll(t.vocabularies());
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                // VocabularyChecker.check já reporta o EXTENSION_FAILED deste alvo
            }
        }
        CheckContext checkContext = new CheckContext(fileName, project, diagnostics, options, java.util.Collections.unmodifiableSet(active));
```
   e usar `checkContext` em `checker.check(sukoFile, checkContext)`.
3. `emitAll` passa a receber o coletor da análise e a devolvê-lo no sucesso:

```java
    private CompileResult emitAll(SukoFile sukoFile, ProjectView project,
                                  Map<String, ProjectIndexEntry> importedByShortName, String packagePrefix,
                                  DiagnosticCollector diagnostics) {
        Map<String, Map<String, String>> byTarget = new LinkedHashMap<>();
        EmitContext ctx = new EmitContext(sukoFile, project, importedByShortName, packagePrefix, options);
        // ... (corpo igual; os diagnósticos de falha de emissão vão para `diagnostics`)
        if (diagnostics.hasErrors()) {
            return CompileResult.failure(diagnostics);
        }
        Map<String, String> first = byTarget.isEmpty() ? Map.of() : byTarget.values().iterator().next();
        return new CompileResult(true, diagnostics, first, byTarget);
    }
```
   Os dois sítios que chamam `emitAll` passam o `diagnostics` que já têm (`compile()` e `compile(ProjectIndex, Path)` usam `analysis.diagnostics()`).

Em `SukoProjectCompiler.java`:
1. Campo `private final SecurityOptions options;`, construtor `(registry, targets, options)`; os existentes delegam com `SecurityOptions.DEFAULT`; todos os `new JteCompiler(..., registry, targets)` passam `options`.
2. `ProjectCompileResult` ganha o componente `List<Output> projectOutputs` e `record Output(String targetId, ProjectOutput.Kind kind, Path relativePath, String source)`; construtor antigo de 4 argumentos delega com `List.of()`.
3. No fim de `compile(SukoSources)`, se `success`, recolher as saídas de projeto, uma vez por alvo, com a mesma proteção de falhas que `emitAll`:

```java
        List<ProjectCompileResult.Output> projectOutputs = new ArrayList<>();
        if (success) {
            for (String targetId : targets) {
                Target target = registry.target(targetId).orElse(null);
                if (target == null) continue;
                try {
                    for (ProjectOutput out : target.emitProject(new ProjectEmitContext(index, options))) {
                        projectOutputs.add(new ProjectCompileResult.Output(
                            targetId, out.kind(), Path.of(out.relativePath()), out.source()));
                    }
                } catch (Throwable e) {
                    ExtensionFailures.rethrowFatal(e);
                    project = withExtra(project, new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                        "A extensão '" + registry.ownerOf(target) + "' falhou ao emitir os ficheiros do projeto para o alvo '"
                            + targetId + "': " + VocabularyChecker.describe(e), "EXTENSION_FAILED", null, SourceSpan.NONE));
                    success = false;
                }
            }
        }
        return new ProjectCompileResult(success, diagnosticsByFile, generatedJteSources, project, projectOutputs);
```
   (`withExtra` = copiar a lista de diagnósticos de projeto e acrescentar; escrever como método privado de 3 linhas.) Imports: `io.suko.ext.ProjectOutput`, `ProjectEmitContext`, `SecurityOptions`, `Target`, `io.suko.lang.ext.ExtensionFailures`.

- [ ] **Step 7: LSP e Gradle conhecem `INFO`**

`DiagnosticsService.severity`: acrescentar `case INFO -> DiagnosticSeverity.Information;`.
`SukoCompileTask.printDiagnostics` e o equivalente em `SukoWatchTask`: acrescentar, depois do ciclo dos WARNING,

```java
        for (SukoDiagnostic diag : diagnostics.getDiagnostics()) {
            if (diag.severity() == SukoDiagnostic.Severity.INFO) {
                String location = diag.span() != null && !diag.span().isNone()
                    ? fileName + ":" + diag.span().startLine() + ":" + diag.span().startColumn()
                    : fileName;
                getLogger().info("[INFO] {} - {}: {}", location, diag.code(), diag.message());
            }
        }
```

- [ ] **Step 8: Tudo verde**

Run: `scripts/verify-isolated.sh :suko-api:test :suko-core:test :suko-jte:test :suko-lsp:test :suko-gradle-plugin:test :suko-maven-plugin:test`
Expected: BUILD SUCCESSFUL. `GoldenParityTest`: se falhar só por `diagnostics.txt` (avisos antes escondidos), **parar e reportar** o diff — não regenerar aqui (regeneração só na Task 4).

- [ ] **Step 9: Commit**

```bash
git add scripts/verify-isolated.sh suko-api suko-core/src suko-lsp/src suko-gradle-plugin/src
git commit -m "feat(14): INFO, SecurityOptions, Target.emitProject, contextos com opções, avisos sobrevivem ao sucesso"
```

---

### Task 2: `SukoSafe` gerada (URL, srcset, ping, pathSegment, cssValue, rel)

**Files:**
- Create: `suko-jte/src/main/resources/io/suko/jte/SukoSafe.java.template`
- Create: `suko-jte/src/main/java/io/suko/jte/SukoSafeSource.java`
- Test: `suko-jte/src/test/java/io/suko/jte/SukoSafeTest.java`, `suko-jte/src/test/java/io/suko/jte/CompiledSukoSafe.java` (helper de teste)

**Interfaces:**
- Consumes: `SecurityOptions` (Task 1).
- Produces: `SukoSafeSource.generate(SecurityOptions)` → `String` com o ficheiro `<generatedPackage>/SukoSafe.java`; `SukoSafeSource.relativePath(SecurityOptions)` → `"io/suko/generated/SukoSafe.java"` (package com `/`). Métodos públicos estáticos da classe gerada: `url(Object)`, `imageUrl(Object)`, `srcset(Object)`, `imageSrcset(Object)`, `ping(Object)`, `pathSegment(Object)`, `cssValue(Object)`, `rel(Object)`; constante `BLOCKED`.

- [ ] **Step 1: Helper de teste que compila e carrega o fonte gerado**

`suko-jte/src/test/java/io/suko/jte/CompiledSukoSafe.java`:

```java
package io.suko.jte;

import io.suko.ext.SecurityOptions;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compila a SukoSafe gerada com o javac do JDK e chama os seus métodos por reflexão. */
final class CompiledSukoSafe implements AutoCloseable {

    private final URLClassLoader loader;
    private final Class<?> type;

    CompiledSukoSafe(SecurityOptions options) throws Exception {
        String source = SukoSafeSource.generate(options);
        Path out = Files.createTempDirectory("suko-safe");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(
            URI.create("string:///" + SukoSafeSource.relativePath(options)), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            boolean ok = javac.getTask(null, fm, null, List.of("-d", out.toString(), "--release", "21"), null, List.of(file)).call();
            if (!ok) {
                throw new IllegalStateException("A SukoSafe gerada não compila:\n" + source);
            }
        }
        loader = new URLClassLoader(new URL[] {out.toUri().toURL()});
        type = loader.loadClass(options.generatedPackage() + ".SukoSafe");
    }

    Object call(String method, Object arg) throws Exception {
        Method m = type.getMethod(method, Object.class);
        return m.invoke(null, arg);
    }

    String url(Object v) throws Exception {
        return (String) call("url", v);
    }

    @Override
    public void close() throws Exception {
        loader.close();
    }
}
```

- [ ] **Step 2: Testes (falham)**

`suko-jte/src/test/java/io/suko/jte/SukoSafeTest.java`:

```java
package io.suko.jte;

import io.suko.ext.SecurityOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SukoSafeTest {

    static final String BLOCKED = "about:invalid#suko-blocked";
    static CompiledSukoSafe safe;

    @BeforeAll
    static void compile() throws Exception {
        safe = new CompiledSukoSafe(SecurityOptions.DEFAULT);
    }

    @AfterAll
    static void close() throws Exception {
        safe.close();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)", " javascript:alert(1)", "JAVASCRIPT:alert(1)", "JaVaScRiPt:alert(1)",
        "java\tscript:alert(1)", "java\nscript:alert(1)", "\u0001javascript:alert(1)", "\u0000javascript:alert(1)",
        "java\u0000script:alert(1)", "javascript\n:alert(1)", "ｊａｖａｓｃｒｉｐｔ:alert(1)",
        "vbscript:msgbox(1)", "data:text/html,<script>alert(1)</script>", "blob:https://x/abc", "file:///etc/passwd",
        "  \t javascript:alert(1)", "javascript&#58;alert(1)x:y"
    })
    void hostileSchemesAreBlocked(String value) throws Exception {
        assertEquals(BLOCKED, safe.url(value), value);
    }

    @Test
    void entityEncodedSchemeIsInertNotDecoded() throws Exception {
        // Sem ':' real: é um caminho relativo inofensivo; o valor sai intacto (nunca se decodificam entidades).
        assertEquals("javascript&#58;alert(1)", safe.url("javascript&#58;alert(1)"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://example.com/a?b=c#d", "http://example.com", "mailto:a@b.com", "tel:+244123", "HTTPS://EXAMPLE.COM",
        "/carrinho", "relative/path", "../up", "#frag", "?q=1", "//cdn.example.com/x.js", "/\\evil.example",
        "\\\\evil.example", "https:evil", ""
    })
    void allowedValuesComeBackUnchanged(String value) throws Exception {
        assertEquals(value, safe.url(value));
    }

    @Test
    void nullStaysNullSoTheAttributeIsOmitted() throws Exception {
        assertNull(safe.url(null));
    }

    @Test
    void controlCharactersInsideAnAllowedUrlAreKept() throws Exception {
        String v = "https://example.com/a\tb";
        assertEquals(v, safe.url(v));
    }

    @Test
    void imageDataIsBlockedByDefaultAndAllowedWhenConfigured() throws Exception {
        assertEquals(BLOCKED, safe.call("imageUrl", "data:image/png;base64,AAAA"));
        try (CompiledSukoSafe png = new CompiledSukoSafe(SecurityOptions.DEFAULT.withImageDataTypes(Set.of("png")))) {
            assertEquals("data:image/png;base64,AAAA", png.call("imageUrl", "data:image/png;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:image/svg+xml;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:image/gif;base64,AAAA"));
            assertEquals(BLOCKED, png.call("imageUrl", "data:text/html,x"));
            assertEquals(BLOCKED, png.url("data:image/png;base64,AAAA")); // url() nunca aceita data:
        }
    }

    @Test
    void customSchemesAreHonoured() throws Exception {
        try (CompiledSukoSafe c = new CompiledSukoSafe(SecurityOptions.DEFAULT.withUrlSchemes(Set.of("https", "sms")))) {
            assertEquals("sms:+1", c.url("sms:+1"));
            assertEquals(BLOCKED, c.url("http://example.com"));
        }
    }

    @Test
    void srcsetKeepsOnlyAllowedCandidates() throws Exception {
        assertEquals("/a.png 1x, https://x.com/b.png 2x",
            safe.call("srcset", "/a.png 1x, javascript:alert(1) 1.5x, https://x.com/b.png 2x"));
        assertEquals("", safe.call("srcset", "data:image/png;base64,AAA 1x"));
        assertEquals("/a.png, /b.png", safe.call("srcset", "/a.png,, /b.png,"));
        assertEquals("/a.png 100w", safe.call("srcset", "/a.png 100w, javascript:x 200w".replace("javascript:x 200w", "javascript:x 200w")));
        assertNull(safe.call("srcset", null));
    }

    @Test
    void srcsetDescriptorsMayContainParenthesesWithCommas() throws Exception {
        assertEquals("/a.png image-set(1x, 2x)", safe.call("srcset", "/a.png image-set(1x, 2x)"));
    }

    @Test
    void pingValidatesEachUrl() throws Exception {
        assertEquals("https://a.example/p /q", safe.call("ping", "https://a.example/p javascript:x /q"));
        assertNull(safe.call("ping", null));
    }

    @Test
    void pathSegmentCannotChangeOriginOrInjectQuery() throws Exception {
        assertEquals("abc-123_x.y~z", safe.call("pathSegment", "abc-123_x.y~z"));
        assertEquals("a%2Fb%3Fc%23d%25e%5Cf", safe.call("pathSegment", "a/b?c#d%e\\f"));
        assertEquals("%0A%00", safe.call("pathSegment", "\n\u0000"));
        assertEquals("%C3%A9", safe.call("pathSegment", "é"));
        assertEquals("", safe.call("pathSegment", ".."));
        assertEquals("", safe.call("pathSegment", "."));
        assertEquals("", safe.call("pathSegment", null));
    }

    @Test
    void cssValueIsAnAllowlist() throws Exception {
        assertEquals("42", safe.call("cssValue", "42"));
        assertEquals("-1.5em", safe.call("cssValue", "-1.5em"));
        assertEquals("#fff", safe.call("cssValue", "#fff"));
        assertEquals("#1A2b3C", safe.call("cssValue", "#1A2b3C"));
        assertEquals("red", safe.call("cssValue", "red"));
        assertEquals("space-between", safe.call("cssValue", "space-between"));
        assertEquals("unset", safe.call("cssValue", "red; background: url(javascript:x)"));
        assertEquals("unset", safe.call("cssValue", "expression(alert(1))"));
        assertEquals("unset", safe.call("cssValue", "url(x)"));
        assertEquals("unset", safe.call("cssValue", "1px solid"));
        assertEquals("unset", safe.call("cssValue", null));
    }

    @Test
    void relAddsNoopenerUnlessOptedOut() throws Exception {
        assertEquals("noopener", safe.call("rel", null));
        assertEquals("noopener", safe.call("rel", "  "));
        assertEquals("nofollow noopener", safe.call("rel", "nofollow"));
        assertEquals("nofollow noopener", safe.call("rel", "nofollow noopener"));
        assertEquals("opener", safe.call("rel", "opener"));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test --tests '*SukoSafeTest*'`
Expected: FAIL (`SukoSafeSource` não existe).

- [ ] **Step 3: Template da classe gerada**

`suko-jte/src/main/resources/io/suko/jte/SukoSafe.java.template` (os marcadores `@@...@@` são substituídos pelo gerador):

```java
package @@PACKAGE@@;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Gerado pelo Suko (subprojeto 14) — não editar. Só JDK.
 * Verificações de segurança que o JTE não faz: protocolos de URL, srcset,
 * segmentos de caminho, valores CSS e o token noopener do rel.
 */
public final class SukoSafe {

    public static final String BLOCKED = "about:invalid#suko-blocked";

    private static final Set<String> SCHEMES = Set.of(@@URL_SCHEMES@@);
    private static final Set<String> IMAGE_DATA_TYPES = Set.of(@@IMAGE_DATA_TYPES@@);

    private SukoSafe() {
    }

    /** Atributo de URL: devolve o valor tal como veio, ou {@link #BLOCKED}; {@code null} omite o atributo. */
    public static String url(Object v) {
        return check(v, false);
    }

    /** Como {@link #url} mas aceita {@code data:image/<tipo>} dos tipos configurados (img/source). */
    public static String imageUrl(Object v) {
        return check(v, true);
    }

    public static String srcset(Object v) {
        return srcset(v, false);
    }

    public static String imageSrcset(Object v) {
        return srcset(v, true);
    }

    /** Lista de URLs separados por espaço (atributo ping): cada um é validado, os bloqueados caem. */
    public static String ping(Object v) {
        if (v == null) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (String part : String.valueOf(v).trim().split("[ \\t\\n\\r\\f]+")) {
            if (part.isEmpty()) {
                continue;
            }
            String checked = check(part, false);
            if (checked != null && !checked.equals(BLOCKED)) {
                out.add(part);
            }
        }
        return String.join(" ", out);
    }

    /** Um segmento de caminho: nada que mude de origem ou injete query/fragmento. */
    public static String pathSegment(Object v) {
        if (v == null) {
            return "";
        }
        String s = String.valueOf(v);
        if (s.equals(".") || s.equals("..")) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (isPathSafe(c)) {
                out.append((char) c);
            } else {
                out.append('%').append(HEX[c >> 4]).append(HEX[c & 15]);
            }
        }
        return out.toString();
    }

    /** Um valor de declaração CSS: número com unidade, cor #hex ou identificador; o resto vira {@code unset}. */
    public static String cssValue(Object v) {
        if (v == null) {
            return "unset";
        }
        String s = String.valueOf(v).trim();
        if (s.matches("[-+]?[0-9]+(\\.[0-9]+)?(%|[A-Za-z]{1,4})?")
            || s.matches("#[0-9A-Fa-f]{3,8}")
            || s.matches("[A-Za-z-]+")) {
            return s;
        }
        return "unset";
    }

    /** Junta {@code noopener} ao rel; um rel com o token {@code opener} é um opt-out explícito. */
    public static String rel(Object v) {
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.isEmpty()) {
            return "noopener";
        }
        for (String token : s.toLowerCase(Locale.ROOT).split("[ \\t\\n\\r\\f]+")) {
            if (token.equals("opener") || token.equals("noopener")) {
                return s;
            }
        }
        return s + " noopener";
    }

    // ---- implementação ------------------------------------------------

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private static boolean isPathSafe(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || "-._~!$'()*+,;=:@".indexOf(c) >= 0;
    }

    private static String check(Object v, boolean image) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        String t = normalize(s);
        int colon = t.indexOf(':');
        if (colon >= 0) {
            int slash = firstOf(t, "/?#");
            if (slash < 0 || colon < slash) {
                String scheme = t.substring(0, colon).toLowerCase(Locale.ROOT);
                if (SCHEMES.contains(scheme)) {
                    return s;
                }
                if (image && scheme.equals("data") && imageDataAllowed(t.substring(colon + 1))) {
                    return s;
                }
                return BLOCKED;
            }
        }
        return s;
    }

    /** Como o URL parser WHATWG: tira C0/espaço nas pontas e TAB/LF/CR em qualquer posição. */
    private static String normalize(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) <= ' ') {
            start++;
        }
        while (end > start && s.charAt(end - 1) <= ' ') {
            end--;
        }
        StringBuilder b = new StringBuilder(end - start);
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c != '\t' && c != '\n' && c != '\r') {
                b.append(c);
            }
        }
        return b.toString();
    }

    private static int firstOf(String s, String chars) {
        for (int i = 0; i < s.length(); i++) {
            if (chars.indexOf(s.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    private static boolean imageDataAllowed(String rest) {
        String r = rest.toLowerCase(Locale.ROOT);
        if (!r.startsWith("image/")) {
            return false;
        }
        int end = r.length();
        for (int i = 6; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == ';' || c == ',') {
                end = i;
                break;
            }
        }
        return IMAGE_DATA_TYPES.contains(r.substring(6, end));
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f';
    }

    /** Candidatos de srcset como na WHATWG; um candidato com URL bloqueado é removido. */
    private static String srcset(Object v, boolean image) {
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v);
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            while (i < n && (isSpace(s.charAt(i)) || s.charAt(i) == ',')) {
                i++;
            }
            if (i >= n) {
                break;
            }
            int start = i;
            while (i < n && !isSpace(s.charAt(i))) {
                i++;
            }
            String url = s.substring(start, i);
            String descriptors = "";
            if (url.endsWith(",")) {
                int e = url.length();
                while (e > 0 && url.charAt(e - 1) == ',') {
                    e--;
                }
                url = url.substring(0, e);
            } else {
                int ds = i;
                int depth = 0;
                while (i < n) {
                    char c = s.charAt(i);
                    if (c == '(') {
                        depth++;
                    } else if (c == ')' && depth > 0) {
                        depth--;
                    } else if (c == ',' && depth == 0) {
                        break;
                    }
                    i++;
                }
                descriptors = s.substring(ds, i).trim();
                if (i < n) {
                    i++;
                }
            }
            if (url.isEmpty()) {
                continue;
            }
            String checked = check(url, image);
            if (checked != null && !checked.equals(BLOCKED)) {
                out.add(descriptors.isEmpty() ? url : url + " " + descriptors);
            }
        }
        return String.join(", ", out);
    }
}
```

- [ ] **Step 4: Gerador**

`suko-jte/src/main/java/io/suko/jte/SukoSafeSource.java`:

```java
package io.suko.jte;

import io.suko.ext.SecurityOptions;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/** Produz o fonte Java de {@code SukoSafe} para um projeto (só JDK; ver SukoSafe.java.template). */
public final class SukoSafeSource {

    private static final String TEMPLATE = load();

    private SukoSafeSource() {
    }

    public static String generate(SecurityOptions options) {
        return TEMPLATE
            .replace("@@PACKAGE@@", options.generatedPackage())
            .replace("@@URL_SCHEMES@@", literals(options.urlSchemes()))
            .replace("@@IMAGE_DATA_TYPES@@", literals(options.imageDataTypes()));
    }

    /** Ex.: {@code io/suko/generated/SukoSafe.java}. */
    public static String relativePath(SecurityOptions options) {
        return options.generatedPackage().replace('.', '/') + "/SukoSafe.java";
    }

    private static String literals(Set<String> values) {
        return values.stream().map(v -> "\"" + v + "\"").collect(Collectors.joining(", "));
    }

    private static String load() {
        try (InputStream in = SukoSafeSource.class.getResourceAsStream("SukoSafe.java.template")) {
            if (in == null) {
                throw new IllegalStateException("SukoSafe.java.template não encontrado no classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

(Os valores de `urlSchemes`/`imageDataTypes` já vêm validados e em minúsculas ASCII pelo `SecurityOptions`, por isso não precisam de escape de Java.)

- [ ] **Step 5: Testes passam**

Run: `scripts/verify-isolated.sh :suko-jte:test`
Expected: PASS. Se `hostileSchemesAreBlocked` falhar num valor, **a SukoSafe está errada** — corrigir o template, não o teste.

- [ ] **Step 6: Commit**

```bash
git add suko-jte/src
git commit -m "feat(jte): SukoSafe gerada (URL por allowlist, srcset, ping, pathSegment, cssValue, rel)"
```

---

### Task 3: Emissor — URLs, noopener, exceções (origem constante, `style`), `@` inerte, `trusted*`

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java`
- Modify: `suko-jte/src/main/java/io/suko/lang/JteEmitter.java` (emissão de atributos e de `TextRun`; construtor com `SecurityOptions`)
- Modify: `suko-jte/src/main/java/io/suko/jte/JteTarget.java` (opções do contexto; `emitProject`)
- Test: `suko-jte/src/test/java/io/suko/jte/HtmlSecurityRulesTest.java`, `suko-core/src/test/java/io/suko/lang/security/EmitterSecurityTest.java`, `suko-core/src/test/java/io/suko/lang/security/RenderHarness.java`
- Modify: `suko-core/build.gradle.kts` (`testImplementation("org.jsoup:jsoup:1.17.2")`)

**Interfaces:**
- Consumes: `SecurityOptions`, `ProjectOutput`, `ProjectEmitContext` (Task 1); `SukoSafeSource` (Task 2); AST de `suko-api`.
- Produces (`HtmlSecurityRules`, package-private no package `io.suko.jte`, mas o emissor está em `io.suko.lang` — por isso a classe é **`public final`** com métodos estáticos públicos):
  - `static String lower(String)`; `static boolean isLiteral(Expr)`; `static Optional<Expr> trustedArgument(Expr value, String functionName)`
  - `static boolean isUrlAttribute(String tag, String attr, SecurityOptions)`; `isSrcset(String attr)`; `isPing(String attr)`; `isImageContext(String tag, String attr)`
  - `static boolean originConstant(String tag, String attr, Expr value, SecurityOptions)` — o valor é um `StringLiteralExpr` cuja primeira parte é literal `^(https?)://[^/?#\\@]+/` com esquema em `urlSchemes`, e as interpolações só vêm depois
  - `static Optional<List<StyleChunk>> styleDeclarations(Expr value)` com `record StyleChunk(String literal, Expr interpolation)`
  - `static boolean needsNoopener(Statement.HtmlElement)` (tag `a|area|form` com `target` literal fora de `_self/_parent/_top` ou não literal)
  - `static String neutralizeJteSyntax(String text)` → troca cada `@` por `${"@"}`
  - `static final Set<String> LOADING_LINK_RELS = Set.of("stylesheet","import","preload","modulepreload","icon","manifest")`

- [ ] **Step 1: Testes das regras (falham)**

`suko-jte/src/test/java/io/suko/jte/HtmlSecurityRulesTest.java`:

```java
package io.suko.jte;

import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HtmlSecurityRulesTest {

    static final SourceSpan S = SourceSpan.NONE;
    static final SecurityOptions O = SecurityOptions.DEFAULT;

    static Expr lit(String text) {
        return new Expr.StringLiteralExpr(List.of(new Expr.StringPart.Literal(text)), S);
    }

    static Expr interp(String... parts) { // alterna literal / identificador (índices ímpares = $ident)
        java.util.ArrayList<Expr.StringPart> list = new java.util.ArrayList<>();
        for (int i = 0; i < parts.length; i++) {
            list.add(i % 2 == 0 ? new Expr.StringPart.Literal(parts[i]) : new Expr.StringPart.SimpleInterp(parts[i]));
        }
        return new Expr.StringLiteralExpr(list, S);
    }

    static Expr id(String name) {
        return new Expr.PrimaryExpr(name, S);
    }

    @Test
    void literalsAreStringsWithoutInterpolationAndBooleanAttributes() {
        assertTrue(HtmlSecurityRules.isLiteral(lit("/carrinho")));
        assertTrue(HtmlSecurityRules.isLiteral(new Expr.PrimaryExpr("true", S)));
        assertFalse(HtmlSecurityRules.isLiteral(interp("/p/", "id")));
        assertFalse(HtmlSecurityRules.isLiteral(id("url")));
    }

    @Test
    void urlAttributesAreCaseInsensitiveAndCoverTheSpecList() {
        for (String a : new String[] {"href", "HREF", "src", "action", "formaction", "poster", "cite", "background",
            "manifest", "longdesc", "usemap", "codebase", "itemtype", "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete"}) {
            assertTrue(HtmlSecurityRules.isUrlAttribute("div", a, O), a);
        }
        assertTrue(HtmlSecurityRules.isUrlAttribute("object", "data", O));
        assertFalse(HtmlSecurityRules.isUrlAttribute("div", "data", O));   // `data` só em <object>
        assertFalse(HtmlSecurityRules.isUrlAttribute("a", "title", O));
        assertTrue(HtmlSecurityRules.isUrlAttribute("a", "data-href", O.withUrlAttributes(java.util.Set.of("data-href"))));
        assertTrue(HtmlSecurityRules.isSrcset("srcset"));
        assertTrue(HtmlSecurityRules.isSrcset("imagesrcset"));
        assertTrue(HtmlSecurityRules.isPing("ping"));
        assertTrue(HtmlSecurityRules.isImageContext("img", "src"));
        assertTrue(HtmlSecurityRules.isImageContext("source", "srcset"));
        assertFalse(HtmlSecurityRules.isImageContext("a", "href"));
    }

    @Test
    void originConstantNeedsAFixedSchemeHostAndSlash() {
        assertTrue(HtmlSecurityRules.originConstant("iframe", "src", interp("https://www.youtube.com/embed/", "id"), O));
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://www.youtube.com", "id"), O));   // sem '/'
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://", "host", "/x"), O));          // host dinâmico
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("https://user@evil.example/", "id"), O)); // '@' no host
        assertFalse(HtmlSecurityRules.originConstant("iframe", "src", interp("javascript://x/", "id"), O));
        assertFalse(HtmlSecurityRules.originConstant("div", "id", interp("https://a.example/", "id"), O));            // atributo não aplicável
    }

    @Test
    void styleDeclarationsAcceptOnlyDynamicValuesAsWholeValues() {
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("--pct: ", "p", "%")).isPresent());
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("width: ", "w", "px; color: red")).isPresent());
        assertTrue(HtmlSecurityRules.styleDeclarations(interp("width: -", "w", "")).isPresent());
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("background: url(", "u", ")")).isPresent());
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("", "css", "")).isPresent());                  // valor sem propriedade
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("width: 1", "w", "x y")).isPresent());          // mistura
        assertFalse(HtmlSecurityRules.styleDeclarations(interp("width: ", "a", ", ", "b", "")).isPresent());   // dois por declaração
    }

    @Test
    void noopenerAppliesToTargetsThatCanOpenAnotherContext() {
        Statement.HtmlElement a = el("a", new Statement.Attribute("target", lit("_blank"), false, S));
        Statement.HtmlElement self = el("a", new Statement.Attribute("target", lit("_self"), false, S));
        Statement.HtmlElement dynamic = el("form", new Statement.Attribute("target", id("t"), false, S));
        Statement.HtmlElement none = el("a");
        assertTrue(HtmlSecurityRules.needsNoopener(a));
        assertFalse(HtmlSecurityRules.needsNoopener(self));
        assertTrue(HtmlSecurityRules.needsNoopener(dynamic));
        assertFalse(HtmlSecurityRules.needsNoopener(none));
        assertFalse(HtmlSecurityRules.needsNoopener(el("div", new Statement.Attribute("target", lit("_blank"), false, S))));
    }

    @Test
    void trustedArgumentRecognisesTheReservedFunctions() {
        Expr call = new Expr.CallExpr(id("trustedUrl"), List.of(id("x")), S);
        assertEquals(Optional.of(id("x")), HtmlSecurityRules.trustedArgument(call, "trustedUrl"));
        assertTrue(HtmlSecurityRules.trustedArgument(call, "trustedStyle").isEmpty());
        assertTrue(HtmlSecurityRules.trustedArgument(id("x"), "trustedUrl").isEmpty());
    }

    @Test
    void jteSyntaxInTextIsMadeInert() {
        assertEquals("a${\"@\"}b.com", HtmlSecurityRules.neutralizeJteSyntax("a@b.com"));
        assertEquals("${\"@\"}if(true){X}${\"@\"}endif", HtmlSecurityRules.neutralizeJteSyntax("@if(true){X}@endif"));
        assertEquals("sem arrobas", HtmlSecurityRules.neutralizeJteSyntax("sem arrobas"));
    }

    private static Statement.HtmlElement el(String tag, Statement.Attribute... attrs) {
        return new Statement.HtmlElement(tag, List.of(attrs), List.of(), false, S);
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test --tests '*HtmlSecurityRulesTest*'`
Expected: FAIL (classe não existe).

- [ ] **Step 2: `HtmlSecurityRules`**

`suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java`:

```java
package io.suko.jte;

import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Statement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regras de segurança HTML partilhadas pelo emissor (que embrulha/sanitiza) e
 * pelo {@code HtmlSecurityChecker} (que recusa). Uma só fonte de verdade
 * (subprojeto 14). Nomes de elemento e atributo comparam-se em minúsculas.
 */
public final class HtmlSecurityRules {

    public static final Set<String> LOADING_LINK_RELS =
        Set.of("stylesheet", "import", "preload", "modulepreload", "icon", "manifest");

    private static final Set<String> URL_ATTRIBUTES = Set.of(
        "href", "xlink:href", "src", "action", "formaction", "poster", "cite", "background",
        "manifest", "longdesc", "usemap", "codebase", "itemtype",
        "hx-get", "hx-post", "hx-put", "hx-patch", "hx-delete");

    private static final Pattern ORIGIN_PREFIX = Pattern.compile("^([A-Za-z][A-Za-z0-9+.-]*)://[^/?#\\\\@]+/.*", Pattern.DOTALL);
    private static final Pattern STYLE_STATIC =
        Pattern.compile("\\s*(?:--[A-Za-z0-9_-]+|[A-Za-z-]+)\\s*:\\s*[^;\\u0001{}()\"'\\\\]*");
    private static final Pattern STYLE_DYNAMIC =
        Pattern.compile("\\s*(?:--[A-Za-z0-9_-]+|[A-Za-z-]+)\\s*:\\s*[-+]?\\u0001(?:%|[A-Za-z]{1,4})?\\s*");

    private HtmlSecurityRules() {
    }

    public static String lower(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    /** Literal = string sem interpolação, ou atributo booleano sem valor. */
    public static boolean isLiteral(Expr e) {
        if (e instanceof Expr.StringLiteralExpr s) {
            return s.parts().stream().allMatch(p -> p instanceof Expr.StringPart.Literal);
        }
        return e instanceof Expr.PrimaryExpr p && p.text().equals("true");
    }

    /** {@code trustedUrl(x)} / {@code trustedStyle(x)} / {@code trustedHtml(x)} → {@code x}. */
    public static Optional<Expr> trustedArgument(Expr value, String functionName) {
        if (value instanceof Expr.CallExpr call
            && call.callee() instanceof Expr.PrimaryExpr p && p.text().equals(functionName)
            && call.args().size() == 1) {
            return Optional.of(call.args().get(0));
        }
        return Optional.empty();
    }

    public static boolean isUrlAttribute(String tag, String attr, SecurityOptions options) {
        String t = lower(tag);
        String a = lower(attr);
        if (a.equals("data")) {
            return t.equals("object");
        }
        return URL_ATTRIBUTES.contains(a) || options.urlAttributes().contains(a);
    }

    public static boolean isSrcset(String attr) {
        String a = lower(attr);
        return a.equals("srcset") || a.equals("imagesrcset");
    }

    public static boolean isPing(String attr) {
        return lower(attr).equals("ping");
    }

    public static boolean isImageContext(String tag, String attr) {
        String t = lower(tag);
        String a = lower(attr);
        return (t.equals("img") || t.equals("source")) && (a.equals("src") || a.equals("srcset"));
    }

    /** Origem constante: esquema+host fixos e '/' no prefixo literal; só o resto é dinâmico. */
    public static boolean originConstant(String tag, String attr, Expr value, SecurityOptions options) {
        String t = lower(tag);
        String a = lower(attr);
        boolean applicable = ((t.equals("iframe") || t.equals("frame") || t.equals("embed") || t.equals("script")) && a.equals("src"))
            || (t.equals("object") && a.equals("data"))
            || (t.equals("link") && a.equals("href"));
        if (!applicable || !(value instanceof Expr.StringLiteralExpr s) || s.parts().isEmpty()
            || !(s.parts().get(0) instanceof Expr.StringPart.Literal first)) {
            return false;
        }
        Matcher m = ORIGIN_PREFIX.matcher(first.javaEscapedText());
        return m.matches() && options.urlSchemes().contains(lower(m.group(1)))
            && (lower(m.group(1)).equals("http") || lower(m.group(1)).equals("https"));
    }

    public record StyleChunk(String literal, Expr interpolation) {
    }

    /**
     * Declarações CSS cujos valores dinâmicos são o valor inteiro (com sinal e
     * unidade opcionais). Devolve os pedaços literal/interpolação, ou vazio se
     * a forma não for aceite.
     */
    public static Optional<List<StyleChunk>> styleDeclarations(Expr value) {
        if (!(value instanceof Expr.StringLiteralExpr s)) {
            return Optional.empty();
        }
        StringBuilder skeleton = new StringBuilder();
        for (Expr.StringPart p : s.parts()) {
            switch (p) {
                case Expr.StringPart.Literal l -> skeleton.append(l.javaEscapedText());
                case Expr.StringPart.Interp i -> skeleton.append('\u0001');
                case Expr.StringPart.SimpleInterp si -> skeleton.append('\u0001');
            }
        }
        for (String declaration : skeleton.toString().split(";", -1)) {
            if (declaration.isBlank()) {
                continue;
            }
            boolean dynamic = declaration.indexOf('\u0001') >= 0;
            Pattern pattern = dynamic ? STYLE_DYNAMIC : STYLE_STATIC;
            if (!pattern.matcher(declaration).matches()) {
                return Optional.empty();
            }
        }
        List<StyleChunk> chunks = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        for (Expr.StringPart p : s.parts()) {
            switch (p) {
                case Expr.StringPart.Literal l -> literal.append(l.javaEscapedText());
                case Expr.StringPart.Interp i -> {
                    chunks.add(new StyleChunk(literal.toString(), i.expr()));
                    literal.setLength(0);
                }
                case Expr.StringPart.SimpleInterp si -> {
                    chunks.add(new StyleChunk(literal.toString(), new Expr.PrimaryExpr(si.identifier(), s.span())));
                    literal.setLength(0);
                }
            }
        }
        chunks.add(new StyleChunk(literal.toString(), null));
        return Optional.of(chunks);
    }

    /** {@code <a>/<area>/<form>} com {@code target} que pode abrir outro contexto. */
    public static boolean needsNoopener(Statement.HtmlElement el) {
        String t = lower(el.tagName());
        if (!(t.equals("a") || t.equals("area") || t.equals("form"))) {
            return false;
        }
        for (Statement.Attribute a : el.attributes()) {
            if (lower(a.name()).equals("target")) {
                if (!isLiteral(a.value())) {
                    return true;
                }
                String text = a.value() instanceof Expr.StringLiteralExpr s
                    ? Expr.pretty(s.parts()).toLowerCase(Locale.ROOT) : "";
                return !(text.equals("_self") || text.equals("_parent") || text.equals("_top"));
            }
        }
        return false;
    }

    /** Cada {@code @} do texto vira {@code ${"@"}}: o JTE nunca o lê como diretiva. */
    public static String neutralizeJteSyntax(String text) {
        return text.indexOf('@') < 0 ? text : text.replace("@", "${\"@\"}");
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test --tests '*HtmlSecurityRulesTest*'`
Expected: PASS.

- [ ] **Step 3: Testes do emissor (falham)**

`suko-core/src/test/java/io/suko/lang/security/EmitterSecurityTest.java` — compila `.sk` para `.jte` com o compilador (o `suko-core` vê o `suko-jte` em `testImplementation`, por isso o teste vive aqui e **não** no `suko-jte`, que nunca depende do core) e confere o texto; um segundo grupo renderiza com o motor JTE real. Acrescentar ao `suko-core/build.gradle.kts`: `testImplementation("org.jsoup:jsoup:1.17.2")`.

```java
package io.suko.lang.security;

import io.suko.ext.SecurityOptions;
import io.suko.lang.JteCompiler;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EmitterSecurityTest {

    static final String PKG = SecurityOptions.DEFAULT.generatedPackage();

    /** Compila um único componente e devolve o .jte do alvo jte. */
    static String jte(String sk) {
        var result = new JteCompiler("T.sk", sk, ExtensionRegistry.defaults(), List.of("jte"), SecurityOptions.DEFAULT).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        return result.generatedJteSources().values().iterator().next();
    }

    @Test
    void dynamicUrlAttributeGoesThroughSukoSafe() {
        String out = jte("component A(String url) { <a href=${url}>x</a> }");
        assertTrue(out.contains("href=\"${" + PKG + ".SukoSafe.url(url)}\""), out);
    }

    @Test
    void interpolatedLiteralUrlIsWrappedAsAWhole() {
        String out = jte("component A(String id) { <a href=\"/p/${id}\">x</a> }");
        assertTrue(out.contains(PKG + ".SukoSafe.url(\"/p/\" + id"), out);   // valor inteiro, nunca por partes
    }

    @Test
    void staticLiteralUrlIsUntouched() {
        String out = jte("component A() { <a href=\"/carrinho\">x</a> }");
        assertFalse(out.contains("SukoSafe"), out);
    }

    @Test
    void srcsetPingAndImageContexts() {
        String out = jte("component A(String s, String p) { <img src=${s} srcset=${s}> <a href=\"/\" ping=${p}>x</a> }");
        assertTrue(out.contains("SukoSafe.url(s)"), out);          // sem imageDataTypes, img usa url()
        assertTrue(out.contains("SukoSafe.srcset(s)"), out);
        assertTrue(out.contains("SukoSafe.ping(p)"), out);
    }

    @Test
    void imageContextUsesImageUrlOnlyWhenConfigured() {
        SecurityOptions o = SecurityOptions.DEFAULT.withImageDataTypes(java.util.Set.of("png"));
        var result = new JteCompiler("T.sk", "component A(String s) { <img src=${s} srcset=${s}> }",
            ExtensionRegistry.defaults(), List.of("jte"), o).compile();
        String out = result.generatedJteSources().values().iterator().next();
        assertTrue(out.contains("SukoSafe.imageUrl(s)") && out.contains("SukoSafe.imageSrcset(s)"), out);
    }

    @Test
    void noopenerIsAddedForTargetBlank() {
        assertTrue(jte("component A(String u) { <a href=${u} target=\"_blank\">x</a> }").contains("rel=\"${\"noopener\"}\""));
        assertTrue(jte("component A() { <a href=\"/x\" target=\"_blank\" rel=\"nofollow\">x</a> }").contains("rel=\"${\"nofollow noopener\"}\""));
        assertTrue(jte("component A(String r) { <a href=\"/x\" target=\"_blank\" rel=${r}>x</a> }").contains("SukoSafe.rel(r)"));
        assertFalse(jte("component A() { <a href=\"/x\" target=\"_self\">x</a> }").contains("noopener"));
        assertFalse(jte("component A() { <a href=\"/x\" target=\"_blank\" rel=\"opener\">x</a> }").contains("noopener"));
    }

    @Test
    void originConstantInterpolationsUsePathSegment() {
        String out = jte("component A(String id) { <iframe src=\"https://www.youtube.com/embed/${id}\"></iframe> }");
        assertTrue(out.contains("https://www.youtube.com/embed/\" + " + PKG + ".SukoSafe.pathSegment(id)"), out);
    }

    @Test
    void styleDeclarationsUseCssValue() {
        String out = jte("component A(int p) { <div style=\"--pct: ${p}%; color: red\">x</div> }");
        assertTrue(out.contains(PKG + ".SukoSafe.cssValue(p)"), out);
    }

    @Test
    void trustedUrlSkipsTheCheckAndTrustedStyleToo() {
        String url = jte("component A(String u) { <a href=${trustedUrl(u)}>x</a> }");
        assertTrue(url.contains("href=\"${u}\"") && !url.contains("SukoSafe"), url);
        String style = jte("component A(String s) { <div style=${trustedStyle(s)}>x</div> }");
        assertTrue(style.contains("style=\"${s}\"") && !style.contains("SukoSafe"), style);
    }

    @Test
    void atSignsInTextAreInert() {
        String out = jte("component A() { <p>mail a@b.com e @if(true) x</p> }");
        assertTrue(out.contains("a${\"@\"}b.com") && out.contains("${\"@\"}if(true)"), out);
    }

    @Test
    void emittedTemplatesNeverContainUnsafe() {
        String out = jte("component A(String u, String x) { <a href=${u} title=${x}>${x}</a> }");
        assertFalse(out.contains("$unsafe") || out.contains("@raw"), out);
    }
}
```

Mais um grupo de testes de **renderização real** na mesma classe ou numa `RenderSecurityTest` (usar `JteRenderSupport`-style: escrever o `.jte` e a `SukoSafe` gerada num diretório temporário, compilar `SukoSafe` com `javax.tools`, e renderizar com `TemplateEngine.create(new DirectoryCodeResolver(tmp), tmpClassesDir, ContentType.Html, parentLoaderQueVêSukoSafe)`). Casos obrigatórios (Review Focus 1 e 2):

```java
    @Test
    void nullUrlOmitsTheAttribute() throws Exception {
        String html = RenderHarness.render("component A(String url) { <a href=${url}>x</a> }", "A", Map.of("url", RenderHarness.NULL));
        assertFalse(html.contains("href"), html);
    }

    @Test
    void javascriptUrlIsReplacedAfterRendering() throws Exception {
        String html = RenderHarness.render("component A(String url) { <a href=${url}>x</a> }", "A", Map.of("url", "java\tscript:alert(1)"));
        assertTrue(html.contains("href=\"about:invalid#suko-blocked\""), html);
    }

    @Test
    void atSignDirectivesAreLiteralTextAndEmailsSurvive() throws Exception {
        String html = RenderHarness.render("component A() { <p>a@b.com @if(true){X}@endif</p> }", "A", Map.of());
        assertTrue(html.contains("a@b.com @if(true){X}@endif"), html);
    }
```

`RenderHarness` (helper de teste no mesmo package, ~40 linhas): gera o `.jte` com `new JteCompiler(...)`, escreve-o em `tmp/<Name>.jte`, compila `SukoSafeSource.generate(DEFAULT)` com `javax.tools` para `tmp/classes`, cria `URLClassLoader(tmp/classes, parent = getClass().getClassLoader())` e `TemplateEngine.create(new DirectoryCodeResolver(tmp), tmp.resolve("jte-classes"), ContentType.Html, loader)`; `NULL` é um marcador que o harness converte em `null` no mapa de parâmetros (`Map.of` não aceita `null`).

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*EmitterSecurityTest*'`
Expected: FAIL.

- [ ] **Step 4: Emissor**

Em `JteEmitter.java`:

1. Campo `private final io.suko.ext.SecurityOptions options;` e um construtor novo no fim da lista de construtores que recebe `SecurityOptions` como último parâmetro; os construtores existentes delegam com `SecurityOptions.DEFAULT` (nenhum chamador existente muda).
2. `case Statement.TextRun textRun -> out.append(io.suko.jte.HtmlSecurityRules.neutralizeJteSyntax(textRun.text()));`
3. Substituir o corpo de `emitHtmlElement` por:

```java
    private void emitHtmlElement(Statement.HtmlElement element, StringBuilder out, java.util.Set<String> slotNames) {
        out.append('<').append(element.tagName());
        boolean noopener = io.suko.jte.HtmlSecurityRules.needsNoopener(element);
        boolean hasRel = false;
        for (Statement.Attribute attribute : element.attributes()) {
            String name = io.suko.jte.HtmlSecurityRules.lower(attribute.name());
            String value;
            if (noopener && name.equals("rel")) {
                hasRel = true;
                value = noopenerRel(attribute.value(), slotNames);
            } else {
                value = attributeValue(element, attribute, slotNames);
            }
            out.append(' ').append(attribute.name()).append("=\"").append("${").append(value).append('}').append('"');
        }
        if (noopener && !hasRel) {
            out.append(" rel=\"${\"noopener\"}\"");
        }
        if (element.selfClosing()) {
            out.append("/>");
            return;
        }
        out.append('>');
        for (Statement child : element.children()) {
            emitStatement(child, out, slotNames);
        }
        out.append("</").append(element.tagName()).append('>');
    }

    private String safe() {
        return options.generatedPackage() + ".SukoSafe";
    }

    private String noopenerRel(Expr value, java.util.Set<String> slotNames) {
        if (io.suko.jte.HtmlSecurityRules.isLiteral(value) && value instanceof Expr.StringLiteralExpr s) {
            String text = Expr.pretty(s.parts());
            boolean optOut = java.util.Arrays.stream(text.toLowerCase(java.util.Locale.ROOT).split("\\s+"))
                .anyMatch(t -> t.equals("opener") || t.equals("noopener"));
            return "\"" + text + (optOut ? "" : " noopener") + "\"";
        }
        return safe() + ".rel(" + emitExpr(value, slotNames) + ")";
    }

    private String attributeValue(Statement.HtmlElement el, Statement.Attribute a, java.util.Set<String> slotNames) {
        Expr v = a.value();
        String tag = io.suko.jte.HtmlSecurityRules.lower(el.tagName());
        String attr = io.suko.jte.HtmlSecurityRules.lower(a.name());

        for (String fn : new String[] {"trustedUrl", "trustedStyle", "trustedHtml"}) {
            var trusted = io.suko.jte.HtmlSecurityRules.trustedArgument(v, fn);
            if (trusted.isPresent()) {
                return emitExpr(trusted.get(), slotNames);
            }
        }
        if (io.suko.jte.HtmlSecurityRules.isLiteral(v)) {
            return emitExpr(v, slotNames);
        }
        if (attr.equals("style")) {
            var chunks = io.suko.jte.HtmlSecurityRules.styleDeclarations(v);
            if (chunks.isPresent()) {
                return emitChunks(chunks.get(), ".cssValue(", slotNames);
            }
        }
        if (io.suko.jte.HtmlSecurityRules.originConstant(tag, attr, v, options)) {
            return emitOriginConstant((Expr.StringLiteralExpr) v, slotNames);
        }
        boolean image = io.suko.jte.HtmlSecurityRules.isImageContext(tag, attr) && !options.imageDataTypes().isEmpty();
        if (io.suko.jte.HtmlSecurityRules.isSrcset(attr)) {
            return safe() + (image ? ".imageSrcset(" : ".srcset(") + emitExpr(v, slotNames) + ")";
        }
        if (io.suko.jte.HtmlSecurityRules.isPing(attr)) {
            return safe() + ".ping(" + emitExpr(v, slotNames) + ")";
        }
        if (io.suko.jte.HtmlSecurityRules.isUrlAttribute(tag, attr, options)) {
            return safe() + (image ? ".imageUrl(" : ".url(") + emitExpr(v, slotNames) + ")";
        }
        return emitExpr(v, slotNames);
    }

    /** Pedaços literal/interpolação → {@code "lit" + SukoSafe.<fn>(expr) + "lit"}. */
    private String emitChunks(java.util.List<io.suko.jte.HtmlSecurityRules.StyleChunk> chunks, String fn,
                              java.util.Set<String> slotNames) {
        StringBuilder sb = new StringBuilder();
        for (var c : chunks) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append('"').append(c.literal()).append('"');
            if (c.interpolation() != null) {
                sb.append(" + ").append(safe()).append(fn).append(emitExpr(c.interpolation(), slotNames)).append(')');
            }
        }
        return sb.toString();
    }

    private String emitOriginConstant(Expr.StringLiteralExpr s, java.util.Set<String> slotNames) {
        StringBuilder sb = new StringBuilder();
        StringBuilder literal = new StringBuilder();
        for (Expr.StringPart p : s.parts()) {
            switch (p) {
                case Expr.StringPart.Literal l -> literal.append(l.javaEscapedText());
                case Expr.StringPart.Interp i -> appendSegment(sb, literal, emitExpr(i.expr(), slotNames));
                case Expr.StringPart.SimpleInterp si -> appendSegment(sb, literal, si.identifier());
            }
        }
        if (sb.length() > 0) {
            sb.append(" + ");
        }
        return sb.append('"').append(literal).append('"').toString();
    }

    private void appendSegment(StringBuilder sb, StringBuilder literal, String expr) {
        if (sb.length() > 0) {
            sb.append(" + ");
        }
        sb.append('"').append(literal).append('"').append(" + ").append(safe()).append(".pathSegment(").append(expr).append(')');
        literal.setLength(0);
    }
```

(O emissor está em `io.suko.lang` e `HtmlSecurityRules` é pública em `io.suko.jte`, no mesmo módulo: chamadas estáticas diretas.)

4. `JteTarget`:

```java
    public Emitted emit(ComponentDecl component, EmitContext ctx) {
        JteEmitter emitter = new JteEmitter(ctx.file().components(), ctx.importedByShortName(), ctx.packagePrefix(), ctx.options());
        JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
        return new Emitted(component.name() + ".jte", result.jteSource(), result.sourceMap());
    }

    @Override
    public java.util.List<io.suko.ext.ProjectOutput> emitProject(io.suko.ext.ProjectEmitContext ctx) {
        return java.util.List.of(new io.suko.ext.ProjectOutput(io.suko.ext.ProjectOutput.Kind.JAVA_SOURCE,
            SukoSafeSource.relativePath(ctx.options()), SukoSafeSource.generate(ctx.options())));
    }
```

Run: `scripts/verify-isolated.sh :suko-jte:test :suko-core:test --tests '*EmitterSecurityTest*' --tests '*HtmlSecurityRulesTest*'`
Expected: PASS. (`GoldenParityTest` vai falhar nesta altura — é esperado, resolve-se na Task 4.)

- [ ] **Step 5: Commit**

```bash
git add suko-jte/src suko-core/src/test suko-core/build.gradle.kts suko-jte/build.gradle.kts
git commit -m "feat(jte): emissão segura — URLs via SukoSafe, noopener, origem constante, style por declarações, @ inerte, trusted*"
```

---

### Task 4: `HtmlSecurityChecker` (M2, M3-lint, `UPPERCASE_NAME`, `RESERVED_NAME`, `TRUSTED_*`) e o golden regenerado

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java`
- Modify: `suko-jte/src/main/java/io/suko/jte/JteExtension.java` (registar o checker)
- Test: `suko-core/src/test/java/io/suko/lang/security/HtmlSecurityCheckerTest.java`
- Modify (regenerar, **uma vez**): `suko-core/src/test/resources/golden/**`
- Modify (se algum componente do repositório usar um sink): `suko-components/src/main/suko/**`, `suko-website/src/main/suko/**`

**Interfaces:**
- Consumes: `HtmlSecurityRules` (Task 3), `CheckContext.options()`/`activeVocabularies()` (Task 1).
- Produces: checker com id `html-security` (o dono é `io.suko.jte`); códigos `UNSAFE_SINK` (ERROR), `UPPERCASE_NAME` (ERROR), `RESERVED_NAME` (ERROR), `TRUSTED_URL`/`TRUSTED_STYLE` (INFO), `CSP_INLINE` (WARNING, só com `strictCsp`). A mensagem de `TRUSTED_*` termina com `: <expressão>` (o `security-audit.json` da Task 6 extrai-a dali) — formato fixo: `"Uso de trustedUrl(...) dispensa a verificação de URL: <expr>"`.

- [ ] **Step 1: Testes (falham)**

`suko-core/src/test/java/io/suko/lang/security/HtmlSecurityCheckerTest.java`:

```java
package io.suko.lang.security;

import io.suko.ext.SecurityOptions;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class HtmlSecurityCheckerTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return diagnostics(sk, SecurityOptions.DEFAULT);
    }

    static List<SukoDiagnostic> diagnostics(String sk, SecurityOptions options) {
        return new JteCompiler("T.sk", sk, ExtensionRegistry.defaults(), List.of("jte"), options)
            .compile().diagnostics().getDiagnostics();
    }

    static boolean has(List<SukoDiagnostic> d, String code) {
        return d.stream().anyMatch(x -> x.code().equals(code));
    }

    static void assertSink(String sk) {
        List<SukoDiagnostic> d = diagnostics(sk);
        assertTrue(has(d, "UNSAFE_SINK"), sk + " -> " + d);
    }

    static void assertClean(String sk) {
        List<SukoDiagnostic> d = diagnostics(sk);
        assertFalse(has(d, "UNSAFE_SINK"), sk + " -> " + d);
    }

    // ---- um teste por entrada da M2 (e o negativo estático) -----------------

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <script>${x}</script> }",
        "component A(String x) { <style>${x}</style> }",
        "component A(String x) { <noscript>${x}</noscript> }",
        "component A(String x) { <noscript><p>${x}</p></noscript> }",
        "component A(String x) { <svg><foreignObject>${x}</foreignObject></svg> }",
        "component A(Component children) { <script>${children}</script> }",
        "component A(Component children) { <style>${children}</style> }",
        "component A(Component children) { <noscript>${children}</noscript> }",
        "component A(String x) { <button onclick=${x}>b</button> }",
        "component A(String x) { <button onClick=${x}>b</button> }",
        "component A(String x) { <button ONCLICK=${x}>b</button> }",
        "component A(String x) { <iframe srcdoc=${x}></iframe> }",
        "component A(String x) { <div style=${x}>b</div> }",
        "component A(String x) { <div style=\"background: url(${x})\">b</div> }",
        "component A(String x) { <script src=${x}></script> }",
        "component A(String x) { <script type=${x}>1</script> }",
        "component A(String x) { <svg><script href=${x}></script></svg> }",
        "component A(String x) { <link rel=${x} href=\"/a.css\"> }",
        "component A(String x) { <link rel=\"stylesheet\" href=${x}> }",
        "component A(String x) { <link href=${x}> }",
        "component A(String x) { <meta http-equiv=${x} content=\"1\"> }",
        "component A(String x) { <meta charset=${x}> }",
        "component A(String x) { <meta name=${x} content=\"c\"> }",
        "component A(String x) { <meta http-equiv=\"refresh\" content=${x}> }",
        "component A(String x) { <meta name=\"referrer\" content=${x}> }",
        "component A(String x) { <svg><animate attributeName=${x} values=\"1\"/></svg> }",
        "component A(String x) { <svg><animate attributeName=\"href\" values=${x}/></svg> }",
        "component A(String x) { <svg><set attributeName=\"href\" to=${x}/></svg> }",
        "component A(String x) { <svg><animateMotion from=${x}/></svg> }",
        "component A(String x) { <svg><animateTransform by=${x}/></svg> }",
        "component A(String x) { <base href=${x}> }",
        "component A(String x) { <iframe src=${x}></iframe> }",
        "component A(String x) { <frame src=${x}> }",
        "component A(String x) { <object data=${x}></object> }",
        "component A(String x) { <embed src=${x}> }",
        "component A(String x) { <div x-data=${x}>b</div> }",
        "component A(String x) { <div x-html=${x}>b</div> }",
        "component A(String x) { <div hx-on-click=${x}>b</div> }",
        "component A(String x) { <div hx-on=${x}>b</div> }",
        "component A(String x) { <button onclick=\"f('${x}')\">b</button> }",
        "component A(String x) { <svg><script>${x}</script></svg> }"
    })
    void dynamicValueInASinkIsRejected(String sk) {
        assertSink(sk);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <script>var a = 1;</script> }",
        "component A() { <style>.a { color: red }</style> }",
        "component A() { <noscript><p>sem js</p></noscript> }",
        "component A() { <button onclick=\"go()\">b</button> }",
        "component A() { <iframe srcdoc=\"<p>x</p>\"></iframe> }",
        "component A() { <div style=\"color: red\">b</div> }",
        "component A() { <script src=\"/app.js\"></script> }",
        "component A() { <link rel=\"stylesheet\" href=\"/a.css\"> }",
        "component A() { <meta charset=\"utf-8\"> }",
        "component A() { <base href=\"/\"> }",
        "component A() { <iframe src=\"https://www.youtube.com/embed/x\"></iframe> }",
        "component A() { <div x-data=\"{ open: false }\" x-show=\"open\">b</div> }",
        "component A(String nonce) { <script nonce=${nonce}>var a = 1;</script> }",
        "component A(String h) { <script src=\"/a.js\" integrity=${h}></script> }",
        "component A(String x) { <link rel=\"canonical\" href=${x}> }",
        "component A(String x) { <div title=${x} class=${x} id=${x} data-x=${x}>b</div> }",
        "component A(String x) { <a href=${x}>l</a> }"
    })
    void staticOrHarmlessUsesAreAccepted(String sk) {
        assertClean(sk);
    }

    @Test
    void originConstantExceptionAcceptsSegmentedEmbeds() {
        assertClean("component A(String id) { <iframe src=\"https://www.youtube.com/embed/${id}\"></iframe> }");
        assertSink("component A(String id) { <iframe src=\"https://${id}/embed\"></iframe> }");
        assertSink("component A(String id) { <iframe src=\"${id}/embed\"></iframe> }");
    }

    @Test
    void styleDeclarationExceptionAcceptsWholeValues() {
        assertClean("component A(int p) { <div style=\"--pct: ${p}%\">b</div> }");
        assertClean("component A(String w) { <div style=\"width: ${w}px; color: red\">b</div> }");
        assertSink("component A(String c) { <div style=\"${c}\">b</div> }");
    }

    @Test
    void urlAttributeCannotTakeAComponent() {
        assertSink("component A(Component children) { <a href=${children}>l</a> }");
    }

    @Test
    void configurableCodeAttributesExtendTheList() {
        SecurityOptions o = SecurityOptions.DEFAULT.withCodeAttributes(Set.of("data-eval"));
        assertTrue(has(diagnostics("component A(String x) { <div data-eval=${x}>b</div> }", o), "UNSAFE_SINK"));
        assertFalse(has(diagnostics("component A(String x) { <div data-eval=${x}>b</div> }"), "UNSAFE_SINK"));
    }

    // ---- trusted* ---------------------------------------------------------------

    @Test
    void trustedUrlIsInfoAndAllowedInUrlAndEmbedAttributes() {
        List<SukoDiagnostic> d = diagnostics("component A(String u) { <a href=${trustedUrl(u)}>l</a> <iframe src=${trustedUrl(u)}></iframe> }");
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
        assertEquals(2, d.stream().filter(x -> x.code().equals("TRUSTED_URL")).count());
        assertTrue(d.stream().filter(x -> x.code().equals("TRUSTED_URL")).allMatch(x -> x.severity() == SukoDiagnostic.Severity.INFO));
        assertTrue(d.stream().anyMatch(x -> x.message().endsWith(": u")), d.toString());
    }

    @Test
    void trustedStyleIsInfo() {
        List<SukoDiagnostic> d = diagnostics("component A(String s) { <div style=${trustedStyle(s)}>b</div> }");
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
        assertTrue(has(d, "TRUSTED_STYLE"));
    }

    @Test
    void trustedFunctionsInTheWrongPlaceAreRejected() {
        assertTrue(has(diagnostics("component A(String u) { <div title=${trustedUrl(u)}>b</div> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String u) { <a href=${trustedStyle(u)}>b</a> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String u) { <div title=${trustedHtml(u)}>b</div> }"), "RESERVED_NAME"));
    }

    @Test
    void reservedNamesCannotBeDeclared() {
        assertTrue(has(diagnostics("component trustedUrl() { <p>x</p> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A(String trustedStyle) { <p>x</p> }"), "RESERVED_NAME"));
        assertTrue(has(diagnostics("component A() { var trustedHtml = \"x\"; <p>x</p> }"), "RESERVED_NAME"));
    }

    // ---- nomes em maiúsculas ----------------------------------------------------

    @Test
    void allUppercaseTagAndAttributeNamesAreRejected() {
        assertTrue(has(diagnostics("component A() { <DIV>x</DIV> }"), "UPPERCASE_NAME"));
        assertTrue(has(diagnostics("component A() { <div CLASS=\"a\">x</div> }"), "UPPERCASE_NAME"));
        assertFalse(has(diagnostics("component A() { <div class=\"a\">x</div> }"), "UPPERCASE_NAME"));
        assertFalse(has(diagnostics("component A() { <svg viewBox=\"0 0 1 1\"><foreignObject></foreignObject></svg> }"), "UPPERCASE_NAME"));
    }

    // ---- lint de CSP estrita ----------------------------------------------------

    @Test
    void strictCspLintWarnsOnInlineThings() {
        SecurityOptions o = SecurityOptions.DEFAULT.withStrictCsp(true);
        for (String sk : new String[] {
            "component A() { <div style=\"color: red\">b</div> }",
            "component A() { <style>.a{}</style> }",
            "component A() { <button onclick=\"go()\">b</button> }",
            "component A() { <script>var a = 1;</script> }",
            "component A() { <a href=\"javascript:void(0)\">b</a> }",
            "component A() { <div x-data=\"{}\">b</div> }"}) {
            List<SukoDiagnostic> d = diagnostics(sk, o);
            assertTrue(d.stream().anyMatch(x -> x.code().equals("CSP_INLINE") && x.severity() == SukoDiagnostic.Severity.WARNING), sk + " -> " + d);
        }
        assertFalse(has(diagnostics("component A() { <div style=\"color: red\">b</div> }"), "CSP_INLINE"));
        assertFalse(has(diagnostics("component A() { <script src=\"/a.js\"></script> }", o), "CSP_INLINE"));
        assertFalse(has(diagnostics("component A(String n) { <script nonce=${n}>1</script> }", o), "CSP_INLINE"));
    }

    @Test
    void checkerOnlyRunsWhenTheHtmlVocabularyIsActive() {
        // Sem alvo html ativo o checker não tem nada a dizer: simulado com um registo sem alvos.
        var d = new JteCompiler("T.sk", "component A(String x) { <script>${x}</script> }",
            ExtensionRegistry.defaults(), List.of(), SecurityOptions.DEFAULT).compile().diagnostics().getDiagnostics();
        assertFalse(has(d, "UNSAFE_SINK"), d.toString());
    }
}
```

Nota de sintaxe para quem implementa: `Component` é o tipo dos slots (ver `suko-components/.../Card.sk`: `Component children`); `<svg>` e atributos camelCase (`viewBox`) devem fazer parse — se algum caso destes não fizer parse com a gramática atual, **substituir o caso por um equivalente que faça parse e anotar no relatório**, nunca remover a regra.

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*HtmlSecurityCheckerTest*'`
Expected: FAIL.

- [ ] **Step 2: O checker**

`suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java`:

```java
package io.suko.jte;

import io.suko.ext.CheckContext;
import io.suko.ext.Checker;
import io.suko.ext.SecurityOptions;
import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static io.suko.jte.HtmlSecurityRules.isLiteral;
import static io.suko.jte.HtmlSecurityRules.lower;

/**
 * M2/M3 do subprojeto 14: recusa valores dinâmicos em sinks perigosos,
 * avisa do que a CSP estrita não aceita e reserva os nomes {@code trusted*}.
 * Corre para qualquer alvo que aceite o vocabulário {@code html}.
 */
public final class HtmlSecurityChecker implements Checker {

    private static final Set<String> RESERVED = Set.of("trustedUrl", "trustedStyle", "trustedHtml");
    private static final Set<String> CONTENT_SINKS = Set.of("script", "style", "noscript", "foreignobject");
    private static final Set<String> ANIMATION = Set.of("animate", "set", "animatemotion", "animatetransform");
    private static final Set<String> ANIMATION_ATTRS = Set.of("attributename", "values", "to", "from", "by");

    @Override
    public String id() {
        return "html-security";
    }

    @Override
    public void check(SukoFile file, CheckContext ctx) {
        if (!ctx.activeVocabularies().contains("html")) {
            return;
        }
        for (ComponentDecl component : file.components()) {
            reserved(component.name(), component.nameSpan(), "componente", ctx);
            Set<String> slots = new HashSet<>();
            for (Param p : component.params()) {
                SourceSpan span = p instanceof Param.ValueParam v ? v.nameSpan() : ((Param.SlotParam) p).nameSpan();
                reserved(p.name(), span, "parâmetro", ctx);
                if (p instanceof Param.SlotParam) {
                    slots.add(p.name());
                }
            }
            walk(component.body(), slots, ctx);
        }
    }

    private void walk(List<Statement> statements, Set<String> slots, CheckContext ctx) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.HtmlElement e -> {
                    element(e, slots, ctx);
                    walk(e.children(), slots, ctx);
                }
                case Statement.IfStmt i -> {
                    walk(i.thenBranch(), slots, ctx);
                    walk(i.elseBranch(), slots, ctx);
                }
                case Statement.ForStmt f -> walk(f.body(), slots, ctx);
                case Statement.SwitchStmt sw -> {
                    for (Statement.SwitchCase c : sw.cases()) {
                        walk(c.body(), slots, ctx);
                    }
                    walk(sw.defaultCase(), slots, ctx);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), slots, ctx);
                    }
                }
                case Statement.VarDecl v -> reserved(v.name(), v.span(), "variável", ctx);
                case Statement.Interpolation i -> { }
                case Statement.TextRun t -> { }
            }
        }
    }

    private void reserved(String name, SourceSpan span, String what, CheckContext ctx) {
        if (RESERVED.contains(name)) {
            ctx.report(Severity.ERROR, "RESERVED_NAME",
                "'" + name + "' é um nome reservado do Suko (" + what + "); não pode ser declarado", span);
        }
    }

    private void element(Statement.HtmlElement e, Set<String> slots, CheckContext ctx) {
        SecurityOptions options = ctx.options();
        String tag = lower(e.tagName());
        uppercase(e.tagName(), e.span(), "elemento", ctx);

        Map<String, Statement.Attribute> attrs = new LinkedHashMap<>();
        for (Statement.Attribute a : e.attributes()) {
            attrs.put(lower(a.name()), a);
        }

        if (CONTENT_SINKS.contains(tag) && hasDynamicContent(e.children())) {
            ctx.report(Severity.ERROR, "UNSAFE_SINK",
                "Conteúdo dinâmico dentro de <" + e.tagName() + "> é executado ou reinterpretado pelo browser; use texto literal"
                    + (tag.equals("script") || tag.equals("style") ? " e passe os dados por atributos data-*" : ""), e.span());
        }
        if (options.strictCsp()) {
            if (tag.equals("style") || (tag.equals("script") && !attrs.containsKey("src"))) {
                ctx.report(Severity.WARNING, "CSP_INLINE",
                    "<" + e.tagName() + "> inline não é compatível com uma CSP estrita (script-src 'self' / style-src 'self')", e.span());
            }
        }

        for (Statement.Attribute a : e.attributes()) {
            String name = lower(a.name());
            uppercase(a.name(), a.span(), "atributo", ctx);
            Expr v = a.value();

            if (trusted(a, tag, name, options, ctx)) {
                continue;
            }
            if (isLiteral(v)) {
                strictCspLiteral(tag, name, v, a, ctx);
                continue;
            }
            if (slotValue(v, slots) && HtmlSecurityRules.isUrlAttribute(tag, name, options)) {
                ctx.report(Severity.ERROR, "UNSAFE_SINK",
                    "Um parâmetro Component (slot) não pode ser o valor do atributo de URL '" + a.name() + "'", a.span());
                continue;
            }
            String reason = sinkReason(tag, name, v, attrs, options);
            if (reason != null) {
                ctx.report(Severity.ERROR, "UNSAFE_SINK",
                    reason + " em <" + e.tagName() + " " + a.name() + "=...>. Use um valor literal"
                        + (HtmlSecurityRules.isUrlAttribute(tag, name, options) || name.equals("src") || name.equals("data")
                            ? " ou ${trustedUrl(...)} (fica registado em security-audit.json)" : ""), a.span());
            }
        }
    }

    /** Devolve {@code true} se o valor é uma chamada {@code trusted*} (já tratada, bem ou mal). */
    private boolean trusted(Statement.Attribute a, String tag, String name, SecurityOptions options, CheckContext ctx) {
        for (String fn : RESERVED) {
            var arg = HtmlSecurityRules.trustedArgument(a.value(), fn);
            if (arg.isEmpty()) {
                continue;
            }
            boolean ok = switch (fn) {
                case "trustedUrl" -> HtmlSecurityRules.isUrlAttribute(tag, name, options)
                    || name.equals("src") || name.equals("href") || name.equals("data") || name.equals("action");
                case "trustedStyle" -> name.equals("style");
                default -> false;
            };
            if (!ok) {
                ctx.report(Severity.ERROR, "RESERVED_NAME",
                    fn + "(...) não é válido no atributo '" + a.name() + "'"
                        + (fn.equals("trustedHtml") ? " (ainda não existe nesta versão)" : ""), a.span());
            } else {
                ctx.report(Severity.INFO, fn.equals("trustedUrl") ? "TRUSTED_URL" : "TRUSTED_STYLE",
                    "Uso de " + fn + "(...) dispensa a verificação de "
                        + (fn.equals("trustedUrl") ? "URL" : "estilo") + ": " + arg.get().pretty(), a.span());
            }
            return true;
        }
        return false;
    }

    private boolean slotValue(Expr v, Set<String> slots) {
        if (v instanceof Expr.PrimaryExpr p) {
            return slots.contains(p.text());
        }
        return v instanceof Expr.CallExpr c && c.callee() instanceof Expr.PrimaryExpr p && slots.contains(p.text());
    }

    private void uppercase(String name, SourceSpan span, String what, CheckContext ctx) {
        boolean hasLetter = name.chars().anyMatch(Character::isLetter);
        if (hasLetter && name.equals(name.toUpperCase(Locale.ROOT))) {
            ctx.report(Severity.ERROR, "UPPERCASE_NAME",
                "O " + what + " '" + name + "' está todo em maiúsculas; o JTE (OwaspHtmlPolicy) recusa esses nomes", span);
        }
    }

    private void strictCspLiteral(String tag, String name, Expr v, Statement.Attribute a, CheckContext ctx) {
        if (!ctx.options().strictCsp()) {
            return;
        }
        String text = v instanceof Expr.StringLiteralExpr s ? Expr.pretty(s.parts()).trim().toLowerCase(Locale.ROOT) : "";
        boolean inline = name.equals("style") || name.startsWith("on") || name.startsWith("x-")
            || (HtmlSecurityRules.isUrlAttribute(tag, name, ctx.options()) && text.replaceAll("[\\t\\n\\r]", "").startsWith("javascript:"));
        if (inline) {
            ctx.report(Severity.WARNING, "CSP_INLINE",
                "O atributo '" + a.name() + "' não é compatível com uma CSP estrita (inline/eval)", a.span());
        }
    }

    private boolean hasDynamicContent(List<Statement> statements) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.Interpolation i -> {
                    return true;
                }
                case Statement.ComponentCallStmt c -> {
                    return true;
                }
                case Statement.HtmlElement e -> {
                    if (hasDynamicContent(e.children())) {
                        return true;
                    }
                }
                case Statement.IfStmt i -> {
                    if (hasDynamicContent(i.thenBranch()) || hasDynamicContent(i.elseBranch())) {
                        return true;
                    }
                }
                case Statement.ForStmt f -> {
                    if (hasDynamicContent(f.body())) {
                        return true;
                    }
                }
                case Statement.SwitchStmt sw -> {
                    for (Statement.SwitchCase c : sw.cases()) {
                        if (hasDynamicContent(c.body())) {
                            return true;
                        }
                    }
                    if (hasDynamicContent(sw.defaultCase())) {
                        return true;
                    }
                }
                case Statement.TextRun t -> { }
                case Statement.VarDecl v -> { }
            }
        }
        return false;
    }

    /** Motivo pelo qual um valor DINÂMICO é recusado neste atributo, ou {@code null}. */
    private String sinkReason(String tag, String attr, Expr v, Map<String, Statement.Attribute> attrs, SecurityOptions options) {
        boolean origin = HtmlSecurityRules.originConstant(tag, attr, v, options);
        if (attr.startsWith("on")) {
            return "Atributo de evento com valor dinâmico (o escape do JTE não impede a execução)";
        }
        if (attr.equals("srcdoc")) {
            return "srcdoc interpreta o valor como HTML";
        }
        if (attr.equals("style")) {
            return HtmlSecurityRules.styleDeclarations(v).isPresent() ? null
                : "style dinâmico só é aceite na forma 'propriedade: ${valor}'";
        }
        if (attr.startsWith("x-") || attr.startsWith("hx-on") || attr.startsWith(":") || attr.startsWith("@")
            || options.codeAttributes().contains(attr)) {
            return "O atributo '" + attr + "' é avaliado como código pelo framework do cliente";
        }
        switch (tag) {
            case "script":
                if (attr.equals("src") && origin) {
                    return null;
                }
                if (attr.equals("src") || attr.equals("href") || attr.equals("xlink:href") || attr.equals("type")) {
                    return "O atributo '" + attr + "' de <script> carrega ou define código";
                }
                break;
            case "link":
                if (attr.equals("rel")) {
                    return "O rel de <link> decide o que o browser carrega";
                }
                if (attr.equals("href") && relLoads(attrs) && !origin) {
                    return "O href de um <link> que carrega recursos";
                }
                break;
            case "meta":
                if (attr.equals("http-equiv") || attr.equals("charset") || attr.equals("name")) {
                    return "O atributo '" + attr + "' de <meta> altera o comportamento da página";
                }
                if (attr.equals("content") && (attrs.containsKey("http-equiv") || metaReferrer(attrs))) {
                    return "O content de <meta http-equiv/referrer> altera cabeçalhos ou redireciona";
                }
                break;
            case "base":
                if (attr.equals("href")) {
                    return "<base href> redireciona todos os URLs relativos da página";
                }
                break;
            case "iframe":
            case "frame":
            case "embed":
                if (attr.equals("src") && !origin) {
                    return "Conteúdo embebido com origem dinâmica";
                }
                break;
            case "object":
                if (attr.equals("data") && !origin) {
                    return "Conteúdo embebido com origem dinâmica";
                }
                break;
            default:
                if (ANIMATION.contains(tag) && ANIMATION_ATTRS.contains(attr)) {
                    return "A animação SVG pode redefinir um atributo (ex.: href) com um valor dinâmico";
                }
        }
        return null;
    }

    private boolean relLoads(Map<String, Statement.Attribute> attrs) {
        Statement.Attribute rel = attrs.get("rel");
        if (rel == null || !(rel.value() instanceof Expr.StringLiteralExpr s) || !isLiteral(s)) {
            return true;
        }
        for (String token : Expr.pretty(s.parts()).toLowerCase(Locale.ROOT).trim().split("\\s+")) {
            if (HtmlSecurityRules.LOADING_LINK_RELS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean metaReferrer(Map<String, Statement.Attribute> attrs) {
        for (String key : new String[] {"name", "property"}) {
            Statement.Attribute a = attrs.get(key);
            if (a != null && a.value() instanceof Expr.StringLiteralExpr s && isLiteral(s)
                && Expr.pretty(s.parts()).trim().equalsIgnoreCase("referrer")) {
                return true;
            }
        }
        return false;
    }
}
```

`JteExtension.register`: acrescentar `ctx.checker(new HtmlSecurityChecker());`.

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*HtmlSecurityCheckerTest*'`
Expected: PASS. Casos que não façam parse com a gramática: ver a nota do Step 1.

- [ ] **Step 3: Os componentes e o site do repositório continuam a compilar (Review Focus 3)**

Run: `scripts/verify-isolated.sh :suko-components:test :suko-website:compileJava :suko-core:test --tests '*GoldenParityTest*'`
Expected: os módulos compilam (`Dialog.sk` tem `x-data="{ open: false }"` **literal**: aceite). `GoldenParityTest` falha por diferenças esperadas (atributos de URL dinâmicos, `@` em texto, avisos antes escondidos). Se algum `.sk` do repositório for **recusado** pelo checker (erro novo), corrigi-lo no próprio `.sk` com a alternativa que a mensagem sugere e registar a mudança no relatório.

- [ ] **Step 4: Regenerar o golden (uma vez) e rever o diff à mão**

Run: `SUKO_PULL=suko-core/src/test/resources/golden scripts/verify-isolated.sh :suko-core:test --tests '*GoldenParityTest*' -Dsuko.updateGolden=true`
Depois: `git diff --stat suko-core/src/test/resources/golden` e `git diff suko-core/src/test/resources/golden | head -400`.
**Rever à mão.** Só são aceitáveis estas famílias de diferença: (a) atributos de URL dinâmicos que passam a `${<pkg>.SukoSafe.url(...)}`; (b) `@` em texto que passa a `${"@"}`; (c) `rel="${"noopener"}"` em `target`; (d) linhas novas em `diagnostics.txt` (avisos/INFO antes escondidos). **Qualquer outra diferença é um bug do emissor — parar e corrigir, não aceitar.** Colar um resumo das diferenças no relatório da task.

Run: `scripts/verify-isolated.sh :suko-core:test :suko-jte:test :suko-components:test`
Expected: PASS (inclui o `GoldenParityTest` com o golden novo).

- [ ] **Step 5: Commit**

```bash
git add suko-jte/src suko-core/src/test suko-components/src suko-website/src
git commit -m "feat(jte): HtmlSecurityChecker (sinks, trusted*, maiúsculas, CSP) e golden regenerado e revisto"
```

---

### Task 5: Sintaxe JTE em texto vinda do registry e opções no compilador de projeto (cobertura)

> Esta task é curta e existe para **fechar a Review Focus 2/3 ao nível do projeto** e para a regra "o emissor nunca gera `$unsafe`" ser testada sobre **todos** os `.jte` do repositório.

**Files:**
- Test: `suko-core/src/test/java/io/suko/lang/security/NoUnsafeInGeneratedTemplatesTest.java`

**Interfaces:**
- Consumes: `SukoProjectCompiler` (Tasks 1/4), as raízes `../suko-components/src/main/suko` e `../suko-website/src/main/suko`.

- [ ] **Step 1: Teste**

```java
package io.suko.lang.security;

import io.suko.lang.project.SukoProjectCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Varre o .jte gerado de componentes e site reais: nunca $unsafe, @raw, nem diretivas que o emissor não produziu. */
class NoUnsafeInGeneratedTemplatesTest {

    private static final Pattern FORBIDDEN = Pattern.compile("\\$unsafe\\{|@raw\\b|@endraw\\b");

    @ParameterizedTest
    @ValueSource(strings = {"../suko-components/src/main/suko", "../suko-website/src/main/suko"})
    void generatedTemplatesAreFreeOfRawOutput(String root) {
        var result = new SukoProjectCompiler().compile(Path.of(root));
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertFalse(result.generatedJteSources().isEmpty());
        result.generatedJteSources().forEach((path, jte) ->
            assertFalse(FORBIDDEN.matcher(jte).find(), path + " contém output cru:\n" + jte));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*NoUnsafeInGeneratedTemplatesTest*'`
Expected: PASS (o texto do site que usa `&#64;` continua inerte; os `@` literais passam a `${"@"}`).

- [ ] **Step 2: Commit**

```bash
git add suko-core/src/test
git commit -m "test(14): nenhum template gerado contém output cru"
```

---

### Task 6: Plugin Gradle — opções, `generatedPackage`, saída Java, auditoria, política do JTE

**Files:**
- Create: `suko-core/src/main/java/io/suko/lang/ext/SecurityAudit.java`
- Create: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoSecurity.java`
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoExtension.java`, `SukoGradlePlugin.java`, `SukoBaseTask.java`, `SukoCompileTask.java`, `SukoWatchTask.java`
- Test: `suko-core/src/test/java/io/suko/lang/ext/SecurityAuditTest.java`, `suko-gradle-plugin/src/test/java/io/suko/lang/gradle/SukoSecurityTestKitTest.java`

**Interfaces:**
- Consumes: `SecurityOptions`, `SukoProjectCompiler(registry, targets, options)`, `ProjectCompileResult.projectOutputs()` (Tasks 1–4).
- Produces:
  - `SecurityAudit.write(Path file, Map<Path, DiagnosticCollector> diagnosticsByFile)` → escreve `{"entries":[{"file","line","column","code","expression"}]}` só com os diagnósticos de código `TRUSTED_URL`/`TRUSTED_STYLE`/`TRUSTED_HTML` (a expressão é o texto depois do último `": "` da mensagem); cria o diretório; ordenação estável (ficheiro, linha, coluna); escrita para ficheiro temporário + `ATOMIC_MOVE`.
  - DSL Gradle: `suko { generatedPackage = "..."; generatedJavaDir = "..."; security { urlSchemes = [...]; imageDataTypes = [...]; strictCsp = true; codeAttributes = [...]; urlAttributes = [...]; jtePolicy = true } }` (`SukoSecurity` com `ListProperty<String>`/`Property<Boolean>`).
  - `SukoExtension.securityOptions()` → `SecurityOptions` (valida: `IllegalArgumentException` vira `GradleException`).
  - Convenções: `generatedPackage` = `io.suko.generated.<SecurityOptions.sanitizePackageSegment(project.name)>`; `generatedJavaDir` = `build/generated-src/suko-java`; `jtePolicy` = `true`.
  - O plugin liga `generatedJavaDir` ao `sourceSets.main.java` e faz `compileJava` depender de `sukoCompile` quando o plugin `java` está aplicado.
  - `security-audit.json` em `build/suko/security-audit.json`.

- [ ] **Step 1: `SecurityAudit` (teste e implementação)**

`SecurityAuditTest`:

```java
package io.suko.lang.ext;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAuditTest {

    @Test
    void writesOnlyTrustedUsesSortedAndEscaped(@TempDir Path dir) throws Exception {
        Map<Path, DiagnosticCollector> byFile = new LinkedHashMap<>();
        DiagnosticCollector b = new DiagnosticCollector();
        b.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO,
            "Uso de trustedUrl(...) dispensa a verificação de URL: x.url(\"q\")", "TRUSTED_URL", "b/B.sk", new SourceSpan(7, 4, 0, 0)));
        b.add(new SukoDiagnostic(SukoDiagnostic.Severity.WARNING, "outra coisa", "CSP_INLINE", "b/B.sk", new SourceSpan(1, 0, 0, 0)));
        DiagnosticCollector a = new DiagnosticCollector();
        a.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO,
            "Uso de trustedStyle(...) dispensa a verificação de estilo: s", "TRUSTED_STYLE", "a/A.sk", new SourceSpan(2, 9, 0, 0)));
        byFile.put(Path.of("b/B.sk"), b);
        byFile.put(Path.of("a/A.sk"), a);

        Path out = dir.resolve("suko/security-audit.json");
        SecurityAudit.write(out, byFile);

        String json = Files.readString(out);
        assertTrue(json.indexOf("a/A.sk") < json.indexOf("b/B.sk"), json);
        assertTrue(json.contains("\"code\": \"TRUSTED_URL\"") && json.contains("\"code\": \"TRUSTED_STYLE\""), json);
        assertTrue(json.contains("x.url(\\\"q\\\")"), json);
        assertFalse(json.contains("CSP_INLINE"), json);
        assertTrue(json.contains("\"line\": 7") && json.contains("\"column\": 4"), json);
    }

    @Test
    void writesAnEmptyListWhenThereAreNoTrustedUses(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("audit.json");
        SecurityAudit.write(out, Map.of());
        assertTrue(Files.readString(out).replaceAll("\\s", "").contains("\"entries\":[]"));
    }
}
```

`SecurityAudit.java` (Gson já está no classpath do core? **não** — `suko-core` não depende de Gson; escrever o JSON à mão com escape):

```java
package io.suko.lang.ext;

import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Escreve build/suko/security-audit.json com cada uso de trustedUrl/trustedStyle/trustedHtml. */
public final class SecurityAudit {

    private static final Set<String> CODES = Set.of("TRUSTED_URL", "TRUSTED_STYLE", "TRUSTED_HTML");

    private SecurityAudit() {
    }

    private record Entry(String file, int line, int column, String code, String expression) {
    }

    public static void write(Path file, Map<Path, DiagnosticCollector> diagnosticsByFile) {
        List<Entry> entries = new ArrayList<>();
        diagnosticsByFile.forEach((path, collector) -> {
            for (SukoDiagnostic d : collector.getDiagnostics()) {
                if (CODES.contains(d.code())) {
                    String message = d.message();
                    int cut = message.lastIndexOf(": ");
                    entries.add(new Entry(path.toString().replace('\\', '/'),
                        d.span() == null ? 0 : d.span().startLine(), d.span() == null ? 0 : d.span().startColumn(),
                        d.code(), cut < 0 ? message : message.substring(cut + 2)));
                }
            }
        });
        entries.sort(Comparator.comparing(Entry::file).thenComparingInt(Entry::line).thenComparingInt(Entry::column));

        StringBuilder json = new StringBuilder("{\n  \"entries\": [");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {\"file\": ").append(quote(e.file()))
                .append(", \"line\": ").append(e.line()).append(", \"column\": ").append(e.column())
                .append(", \"code\": ").append(quote(e.code())).append(", \"expression\": ").append(quote(e.expression())).append('}');
        }
        json.append(entries.isEmpty() ? "]" : "\n  ]").append("\n}\n");
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = Files.createTempFile(file.toAbsolutePath().getParent(), "audit", ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
```

(O teste espera `"code": "TRUSTED_URL"` com um espaço depois de `:` — o formato acima escreve `"code": "..."`. Confirmar ao correr.)

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*SecurityAuditTest*'`
Expected: FAIL antes da implementação, PASS depois.

- [ ] **Step 2: DSL do Gradle**

`SukoSecurity.java`:

```java
package io.suko.lang.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;

/** {@code suko { security { ... } }} */
public class SukoSecurity {
    private final ListProperty<String> urlSchemes;
    private final ListProperty<String> imageDataTypes;
    private final Property<Boolean> strictCsp;
    private final ListProperty<String> codeAttributes;
    private final ListProperty<String> urlAttributes;
    private final Property<Boolean> jtePolicy;

    @Inject
    public SukoSecurity(ObjectFactory objects) {
        this.urlSchemes = objects.listProperty(String.class);
        this.imageDataTypes = objects.listProperty(String.class);
        this.strictCsp = objects.property(Boolean.class);
        this.codeAttributes = objects.listProperty(String.class);
        this.urlAttributes = objects.listProperty(String.class);
        this.jtePolicy = objects.property(Boolean.class);
    }

    public ListProperty<String> getUrlSchemes() { return urlSchemes; }
    public ListProperty<String> getImageDataTypes() { return imageDataTypes; }
    public Property<Boolean> getStrictCsp() { return strictCsp; }
    public ListProperty<String> getCodeAttributes() { return codeAttributes; }
    public ListProperty<String> getUrlAttributes() { return urlAttributes; }
    public Property<Boolean> getJtePolicy() { return jtePolicy; }
}
```

`SukoExtension`: o construtor atual tem a particularidade documentada (implementação manual de `Property` para os testes unitários — ver o comentário no ficheiro; **ler esse comentário antes de editar**). Acrescentar, no mesmo estilo dos campos existentes: `Property<String> generatedPackage`, `Property<String> generatedJavaDir`, `SukoSecurity security` (criado com `objectFactory.newInstance(SukoSecurity.class)`), os getters `getGeneratedPackage()`, `getGeneratedJavaDir()`, `getSecurity()`, o método `security(org.gradle.api.Action<SukoSecurity> action)` e:

```java
    public Path getGeneratedJavaDirAsPath() {
        return projectLayout.getProjectDirectory().dir(getGeneratedJavaDir().get()).getAsFile().toPath();
    }

    public Path getSecurityAuditPath() {
        return projectLayout.getBuildDirectory().file("suko/security-audit.json").get().getAsFile().toPath();
    }

    public io.suko.ext.SecurityOptions securityOptions() {
        try {
            io.suko.ext.SecurityOptions o = io.suko.ext.SecurityOptions.DEFAULT
                .withGeneratedPackage(generatedPackage.get())
                .withStrictCsp(security.getStrictCsp().getOrElse(false))
                .withImageDataTypes(new java.util.TreeSet<>(security.getImageDataTypes().getOrElse(java.util.List.of())))
                .withCodeAttributes(new java.util.TreeSet<>(security.getCodeAttributes().getOrElse(java.util.List.of())))
                .withUrlAttributes(new java.util.TreeSet<>(security.getUrlAttributes().getOrElse(java.util.List.of())));
            java.util.List<String> schemes = security.getUrlSchemes().getOrElse(java.util.List.of());
            return schemes.isEmpty() ? o : o.withUrlSchemes(new java.util.TreeSet<>(schemes));
        } catch (IllegalArgumentException e) {
            throw new org.gradle.api.GradleException("Configuração suko.security inválida: " + e.getMessage(), e);
        }
    }
```

`SukoGradlePlugin.apply`: convenções e ligação ao Java:

```java
        extension.getGeneratedPackage().convention(
            "io.suko.generated." + io.suko.ext.SecurityOptions.sanitizePackageSegment(project.getName()));
        extension.getGeneratedJavaDir().convention("build/generated-src/suko-java");
        extension.getSecurity().getJtePolicy().convention(true);

        project.getPluginManager().withPlugin("java", p -> {
            var sourceSets = project.getExtensions().getByType(org.gradle.api.tasks.SourceSetContainer.class);
            sourceSets.getByName("main").getJava().srcDir(project.getLayout().dir(
                project.provider(() -> extension.getGeneratedJavaDirAsPath().toFile())));
            project.getTasks().named("compileJava", t -> t.dependsOn("sukoCompile"));
        });

        // M4: política do JTE (defesa em profundidade) — nunca sobrescreve um valor do utilizador.
        project.getPluginManager().withPlugin("gg.jte.gradle", p -> {
            if (extension.getSecurity().getJtePolicy().get()) {
                Object jte = project.getExtensions().findByName("jte");
                if (jte != null) {
                    try {
                        var getter = jte.getClass().getMethod("getHtmlPolicyClass");
                        var prop = (org.gradle.api.provider.Property<String>) getter.invoke(jte);
                        if (!prop.isPresent()) {
                            prop.set("gg.jte.html.OwaspHtmlPolicy");
                        }
                    } catch (ReflectiveOperationException e) {
                        project.getLogger().warn("suko: não foi possível ativar a política HTML do JTE: {}", e.toString());
                    }
                }
            }
        });
```

(O `jte-specialist` confirma o nome real da propriedade no plugin `gg.jte.gradle` 3.1.12 — o código acima usa `htmlPolicyClass` como na spec; se o nome for outro, **corrigir e anotar**. O ramo usa reflexão para o plugin Suko **não** depender do plugin do JTE.)

`SukoCompileTask`/`SukoWatchTask`: construir o compilador com as opções e escrever as saídas e a auditoria:

```java
            var compiler = new io.suko.lang.project.SukoProjectCompiler(registry, targets, getExtension().securityOptions());
            ...
            var result = compiler.compile(sourceDir);
            writeAndReport(result, outputDir);
            io.suko.lang.ext.SecurityAudit.write(getExtension().getSecurityAuditPath(), result.diagnosticsByFile());
```

e em `writeAndReport`, **depois** do ciclo dos `.jte` e **antes** do `if (!result.success())`:

```java
        Path javaDir = getExtension().getGeneratedJavaDirAsPath();
        for (var out : result.projectOutputs()) {
            Path base = switch (out.kind()) {
                case JAVA_SOURCE -> javaDir;
                case TEMPLATE, RESOURCE -> outputDir;
            };
            Path file = base.resolve(out.relativePath());
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, out.source());
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + file, e);
            }
        }
```

(`SukoWatchTask.compileAll(Path, Path)` público mantém a assinatura; o `SukoExtension` pode ser `null` nos testes antigos — **proteger**: se `extension == null`, usar `SecurityOptions.DEFAULT` e `javaDir = outputDir.resolveSibling("suko-java")`, tal como o código atual já tolera `extension == null`.)

- [ ] **Step 3: Teste TestKit**

`SukoSecurityTestKitTest` (no estilo de `SukoExtensionsTestKitTest`; **reutilizar o mesmo esqueleto de projeto temporário**):

```java
    @Test
    void generatesSukoSafeIntoTheJavaSourceDirAndAuditsTrustedUses(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"demo-shop\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { java; id("io.suko.lang") }
            suko { security { urlSchemes = listOf("https", "mailto") } }
            """);
        write(project.resolve("src/main/suko/ui/Link.sk"), """
            package ui;
            public component Link(String url, String embed) {
              <a href=${url}>l</a>
              <iframe src=${trustedUrl(embed)}></iframe>
            }
            """);

        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile", "--stacktrace").build();

        Path safe = project.resolve("build/generated-src/suko-java/io/suko/generated/demo_shop/SukoSafe.java");
        assertTrue(Files.exists(safe), "SukoSafe.java deveria ter sido gerada");
        assertTrue(Files.readString(safe).contains("Set.of(\"https\", \"mailto\")"));
        String jte = Files.readString(project.resolve("build/generated-src/suko/ui/Link.jte"));
        assertTrue(jte.contains("io.suko.generated.demo_shop.SukoSafe.url(url)"), jte);
        String audit = Files.readString(project.resolve("build/suko/security-audit.json"));
        assertTrue(audit.contains("TRUSTED_URL") && audit.contains("\"expression\": \"embed\""), audit);
    }

    @Test
    void forbiddenSchemeFailsTheBuildWithAClearMessage(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            suko { security { urlSchemes = listOf("https", "javascript") } }
            """);
        write(project.resolve("src/main/suko/A.sk"), "component A() { <p>x</p> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").buildAndFail();
        assertTrue(result.getOutput().contains("javascript"), result.getOutput());
    }

    @Test
    void unsafeSinkFailsTheBuild(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), "plugins { id(\"io.suko.lang\") }");
        write(project.resolve("src/main/suko/A.sk"), "component A(String x) { <script>${x}</script> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").buildAndFail();
        assertTrue(result.getOutput().contains("UNSAFE_SINK"), result.getOutput());
    }

    @Test
    void checkerWarningsAreVisibleOnASuccessfulBuild(@TempDir Path project) throws Exception {
        write(project.resolve("settings.gradle.kts"), "rootProject.name = \"x\"");
        write(project.resolve("build.gradle.kts"), """
            plugins { id("io.suko.lang") }
            suko { security { strictCsp = true } }
            """);
        write(project.resolve("src/main/suko/A.sk"), "component A() { <div style=\"color: red\">x</div> }");
        BuildResult result = GradleRunner.create().withProjectDir(project.toFile()).withPluginClasspath()
            .withArguments("sukoCompile").build();
        assertTrue(result.getOutput().contains("CSP_INLINE"), result.getOutput());   // Review Focus 4
    }
```

(O `write(Path, String)` é um helper trivial de teste que cria pastas pai; copiar o do `SukoExtensionsTestKitTest`. O TestKit precisa do `suko-jte` no classpath do plugin — já resolvido no 13a, mesma `withPluginClasspath()`.)

Run: `scripts/verify-isolated.sh :suko-gradle-plugin:test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add suko-core/src suko-gradle-plugin/src
git commit -m "feat(gradle): suko.security, generatedPackage, SukoSafe no fonte Java, security-audit.json, política do JTE"
```

---

### Task 7: Plugin Maven — opções, saída Java, auditoria

**Files:**
- Modify: `suko-maven-plugin/src/main/java/io/suko/lang/maven/SukoCompileMojo.java`
- Modify: `suko-maven-plugin/src/main/resources/META-INF/maven/plugin.xml`
- Test: `suko-maven-plugin/src/test/java/io/suko/lang/maven/SukoCompileMojoSecurityTest.java` (e atualizar o `PluginDescriptorConsistencyTest` só se ele exigir)

**Interfaces:**
- Consumes: `SecurityOptions`, `SecurityAudit.write` (Tasks 1, 6), `ProjectCompileResult.projectOutputs()`.
- Produces: parâmetros do Mojo `security` (POJO `Security` estático com `List<String> urlSchemes, imageDataTypes, codeAttributes, urlAttributes; boolean strictCsp`) e `generatedJavaDir` (`${project.build.directory}/generated-sources/suko`); `generatedPackage` **sem** default no `plugin.xml`; `package-private SecurityOptions securityOptions()`.

- [ ] **Step 1: Teste (falha)**

```java
package io.suko.lang.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SukoCompileMojoSecurityTest {

    private SukoCompileMojo mojo(Path dir, String artifactId) throws Exception {
        SukoCompileMojo m = new SukoCompileMojo();
        m.sourceDir = dir.resolve("src").toFile();
        m.outputDir = dir.resolve("out").toFile();
        m.buildDirectory = dir.resolve("target").toFile();
        m.generatedJavaDir = dir.resolve("target/generated-sources/suko").toFile();
        m.artifactIdForTests = artifactId;   // o Mojo lê project.getArtifactId(); ver nota
        return m;
    }

    @Test
    void generatesSukoSafeAndWiresTheSourceRootAndAudits(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/ui"));
        Files.writeString(dir.resolve("src/ui/Link.sk"),
            "package ui;\npublic component Link(String url, String e) { <a href=${url}>l</a><iframe src=${trustedUrl(e)}></iframe> }");
        SukoCompileMojo m = mojo(dir, "demo-shop");
        m.execute();

        Path safe = dir.resolve("target/generated-sources/suko/io/suko/generated/demo_shop/SukoSafe.java");
        assertTrue(Files.exists(safe), "SukoSafe.java deveria existir");
        assertTrue(Files.readString(dir.resolve("out/ui/Link.jte")).contains("io.suko.generated.demo_shop.SukoSafe.url(url)"));
        assertTrue(Files.readString(dir.resolve("target/suko/security-audit.json")).contains("TRUSTED_URL"));
    }

    @Test
    void forbiddenSchemeIsAMojoExecutionException(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A() { <p>x</p> }");
        SukoCompileMojo m = mojo(dir, "x");
        m.security = new SukoCompileMojo.Security();
        m.security.urlSchemes = List.of("https", "javascript");
        var e = assertThrows(org.apache.maven.plugin.MojoExecutionException.class, m::execute);
        assertTrue(e.getMessage().contains("javascript"), e.getMessage());
    }

    @Test
    void unsafeSinkFailsTheBuild(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src"));
        Files.writeString(dir.resolve("src/A.sk"), "component A(String x) { <script>${x}</script> }");
        assertThrows(org.apache.maven.plugin.MojoExecutionException.class, () -> mojo(dir, "x").execute());
    }
}
```

Nota: o `SukoCompileMojo` existente usa `project` (um `MavenProject`) para ler `artifactId` e para `addCompileSourceRoot`; nos testes de Mojo construídos à mão `project` é `null`. Para o Mojo ser testável **sem** mocks de Maven, extrair para métodos package-private `String effectiveGeneratedPackage()` (usa `generatedPackage` se definido; senão `io.suko.generated.` + sanitizado(`project != null ? project.getArtifactId() : artifactIdForTests`)) e `void registerSourceRoot(File dir)` (`if (project != null) project.addCompileSourceRoot(dir.getAbsolutePath())`). O campo `artifactIdForTests` é package-private, só lido quando `project == null`.

Run: `scripts/verify-isolated.sh :suko-maven-plugin:test --tests '*SukoCompileMojoSecurityTest*'`
Expected: FAIL.

- [ ] **Step 2: Mojo e `plugin.xml`**

No `SukoCompileMojo`: campos `@Parameter File generatedJavaDir` (default `${project.build.directory}/generated-sources/suko`), `@Parameter Security security` (POJO `public static class Security { public List<String> urlSchemes; public List<String> imageDataTypes; public List<String> codeAttributes; public List<String> urlAttributes; public boolean strictCsp; }`), os métodos acima e

```java
    io.suko.ext.SecurityOptions securityOptions() throws MojoExecutionException {
        try {
            io.suko.ext.SecurityOptions o = io.suko.ext.SecurityOptions.DEFAULT.withGeneratedPackage(effectiveGeneratedPackage());
            if (security != null) {
                o = o.withStrictCsp(security.strictCsp);
                if (security.imageDataTypes != null) o = o.withImageDataTypes(new java.util.TreeSet<>(security.imageDataTypes));
                if (security.codeAttributes != null) o = o.withCodeAttributes(new java.util.TreeSet<>(security.codeAttributes));
                if (security.urlAttributes != null) o = o.withUrlAttributes(new java.util.TreeSet<>(security.urlAttributes));
                if (security.urlSchemes != null && !security.urlSchemes.isEmpty()) o = o.withUrlSchemes(new java.util.TreeSet<>(security.urlSchemes));
            }
            return o;
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Configuração suko security inválida: " + e.getMessage(), e);
        }
    }
```

Em `execute()`: `new SukoProjectCompiler(registry, requested, securityOptions())`; depois de gravar os `.jte`, gravar `projectOutputs()` (`JAVA_SOURCE` → `generatedJavaDir`, `TEMPLATE`/`RESOURCE` → `outputDir`), chamar `registerSourceRoot(generatedJavaDir)` e `SecurityAudit.write(buildDirectory.toPath().resolve("suko/security-audit.json"), result.diagnosticsByFile())` (só se `buildDirectory != null`, como o resto); imprimir diagnósticos `WARNING` com `getLog().warn` e `INFO` com `getLog().info` (hoje só `getErrors()` é impresso — **acrescentar os avisos**: Review Focus 4 no Maven).

`plugin.xml`: remover o `default-value` de `generatedPackage` (a descrição passa a dizer "por omissão `io.suko.generated.<artifactId>`") e declarar os parâmetros `generatedJavaDir` (`java.io.File`, `${project.build.directory}/generated-sources/suko`) e `security` (`implementation="io.suko.lang.maven.SukoCompileMojo$Security"`) — copiar o formato exato dos parâmetros `targets`/`buildDirectory` já presentes e atualizar a contagem na descrição (o `PluginDescriptorConsistencyTest` compara descritor e campos).

**Política do JTE no Maven (M4):** o Mojo não altera o `jte-maven-plugin`; só avisa. Método package-private `static void warnIfJtePolicyMissing(java.util.List<org.apache.maven.model.Plugin> plugins, org.apache.maven.plugin.logging.Log log)`: se existir um plugin `gg.jte:jte-maven-plugin` cuja configuração (`Xpp3Dom`) não tenha o filho `htmlPolicyClass`, `log.warn("... configure <htmlPolicyClass>gg.jte.html.OwaspHtmlPolicy</htmlPolicyClass> ...")`; sem o plugin do JTE, nada. `execute()` chama-o com `project.getBuildPlugins()` quando `project != null`. Teste (acrescentar a `SukoCompileMojoSecurityTest`): construir `Plugin` com `setGroupId("gg.jte")`, `setArtifactId("jte-maven-plugin")` e `setConfiguration(new org.codehaus.plexus.util.xml.Xpp3Dom("configuration"))` → um aviso; com `htmlPolicyClass` presente → nenhum; sem o plugin → nenhum (usar um `Log` de teste que conta `warn`; `org.apache.maven.plugin.testing`-style não é necessário — `new org.apache.maven.plugin.logging.SystemStreamLog()` embrulhado numa subclasse que conta).

Run: `scripts/verify-isolated.sh :suko-maven-plugin:test`
Expected: PASS (incluindo `PluginDescriptorConsistencyTest`).

- [ ] **Step 3: Commit**

```bash
git add suko-maven-plugin/src
git commit -m "feat(maven): security, generatedJavaDir, SukoSafe no fonte Java, auditoria e avisos visíveis"
```

---

### Task 8: Corpus XSS no motor real e kit de conformidade

**Files:**
- Create: `suko-core/src/test/resources/security/xss-corpus.txt`
- Create: `suko-core/src/test/java/io/suko/lang/security/RenderHarness.java` (se ainda não existir da Task 3; senão estender)
- Test: `suko-core/src/test/java/io/suko/lang/security/XssCorpusTest.java`

**Interfaces:**
- Consumes: `RenderHarness.render(sk, componentName, params)` (Task 3), jsoup.
- Produces: `XssCorpusTest` parametrizado por `targetId` (hoje só `jte`) — é o "kit de conformidade": qualquer alvo futuro que aceite `html` acrescenta o seu `id` à fonte do teste.

- [ ] **Step 1: O corpus**

`xss-corpus.txt`: um payload por linha (UTF-8), com a sequência `\t`, `\n`, `\u0000`… escrita como `\t`/`\n`/`\u0000` literal e **decodificada pelo teste**. Mínimo exigido (a OWASP XSS Filter Evasion e o PortSwigger acrescentam mais; o implementador junta ≥ 60 linhas com estas famílias):

```
<script>alert(1)</script>
"><script>alert(1)</script>
'><img src=x onerror=alert(1)>
</title><script>alert(1)</script>
</textarea><script>alert(1)</script>
</style><script>alert(1)</script>
</noscript><img src=x onerror=alert(1)>
<svg><script>alert(1)</script></svg>
<svg onload=alert(1)>
<math><mtext><table><mglyph><style><img src=x onerror=alert(1)>
javascript:alert(1)
 javascript:alert(1)
JaVaScRiPt:alert(1)
java\tscript:alert(1)
\u0001javascript:alert(1)
\u0000javascript:alert(1)
javascript&#58;alert(1)
&#106;avascript:alert(1)
vbscript:msgbox(1)
data:text/html,<script>alert(1)</script>
data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==
&#x27;);alert(1)//
');alert(1)//
 alert(1)
%3Cscript%3Ealert(1)%3C/script%3E
{{7*7}}
${7*7}
@if(true){X}@endif
@raw<b>x</b>@endraw
$unsafe{"x"}
!{var x = 1;}
@`<b>x</b>`
```

- [ ] **Step 2: O teste**

```java
package io.suko.lang.security;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class XssCorpusTest {

    static final String SK = """
        component Page(String payload) {
          <div title=${payload} class=${payload} id=${payload} data-x=${payload}>
            <p>${payload}</p>
            <a href=${payload}>link</a>
            <a href="/p/${payload}">interpolado</a>
            <img src=${payload} srcset=${payload} alt=${payload}>
            <form action=${payload}><input value=${payload}></form>
            <a href="/x" ping=${payload}>ping</a>
            <iframe src="https://www.youtube.com/embed/${payload}"></iframe>
            <div style="--pct: ${payload}%">estilo</div>
            <textarea>${payload}</textarea>
            <title>${payload}</title>
          </div>
        }
        """;

    static Stream<String> payloads() throws Exception {
        return Files.readAllLines(Path.of("src/test/resources/security/xss-corpus.txt")).stream()
            .filter(l -> !l.isBlank())
            .map(XssCorpusTest::decode);
    }

    static String decode(String line) {
        return line.replace("\\t", "\t").replace("\\n", "\n").replace("\\u0000", "\u0000")
            .replace("\\u0001", "\u0001").replace("\\u2028", " ");
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void renderedPageContainsNoActiveContentFromThePayload(String payload) throws Exception {
        String html = RenderHarness.render(SK, "Page", Map.of("payload", payload));
        Document doc = Jsoup.parse(html);

        assertTrue(doc.select("script").isEmpty(), "script injetado por: " + payload + "\n" + html);
        for (Element e : doc.getAllElements()) {
            e.attributes().forEach(a -> assertFalse(a.getKey().toLowerCase(Locale.ROOT).startsWith("on"),
                "atributo on* injetado por: " + payload + "\n" + html));
        }
        for (Element e : doc.select("[href],[src],[action],[ping],[srcset],[formaction]")) {
            for (String attr : List.of("href", "src", "action", "formaction")) {
                if (e.hasAttr(attr)) {
                    String v = e.attr(attr).replaceAll("[\\t\\n\\r]", "").stripLeading().toLowerCase(Locale.ROOT);
                    assertFalse(v.startsWith("javascript:") || v.startsWith("vbscript:") || v.startsWith("data:"),
                        attr + " perigoso para: " + payload + " -> " + e.attr(attr));
                }
            }
        }
        assertTrue(doc.select("iframe").size() == 1, "iframe extra para: " + payload);
        assertFalse(html.contains("$unsafe") && !payload.contains("$unsafe"), html);
    }

    @ParameterizedTest
    @MethodSource("payloads")
    void payloadSyntaxIsNeverInterpretedAsTemplateCode(String payload) throws Exception {
        // O payload é DADO: nunca avaliado pelo JTE ({{7*7}}, ${7*7}, @if, @raw...).
        String html = RenderHarness.render(SK, "Page", Map.of("payload", payload));
        if (payload.equals("{{7*7}}") || payload.equals("${7*7}")) {
            assertFalse(html.contains("49"), html);
        }
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests '*XssCorpusTest*'`
Expected: PASS. Se algum payload furar, **o emissor/SukoSafe está errado** — corrigir aí e acrescentar a regressão ao `SukoSafeTest`.

- [ ] **Step 3: Commit**

```bash
git add suko-core/src/test
git commit -m "test(14): corpus XSS contra o motor JTE real (kit de conformidade dos alvos html)"
```

---

### Task 9: Registry esquema 2 — índice com metadados, `manifestSha256`, assinatura Ed25519

**Files:**
- Modify: `suko-registry/src/main/java/io/suko/registry/RegistryIndex.java`, `RegistryJson.java` (esquema 2)
- Create: `suko-registry/src/main/java/io/suko/registry/RegistrySignature.java`, `TrustedKeys.java`, `RegistrySecurityException.java`
- Test: `suko-registry/src/test/java/io/suko/registry/RegistrySignatureTest.java`, ajustar `RegistryJsonTest`/`ModelTest` para o esquema 2

**Interfaces:**
- Produces:
  - `RegistryIndex(int schemaVersion, String registryVersion, String basePackage, String registryId, String ref, String issuedAt, String expires, List<Entry> components)` com `SCHEMA_VERSION = 2`; `Entry(String name, String version, String description, String category, String manifest, String manifestSha256)`. `expires` pode ser `null` (JSON `null`/ausente) para tags imutáveis; `issuedAt` e `expires` são ISO-8601 UTC (`Instant.toString()`).
  - `RegistrySignature`: `static byte[] sign(byte[] indexBytes, PrivateKey key, String keyId)` → JSON `{"keyid": "...", "alg": "ed25519", "sig": "<base64>"}` (admite lista: o ficheiro é `{"signatures": [ {...}, ... ]}`); `static Verified verify(byte[] indexBytes, byte[] sigJson, TrustedKeys keys, String registryId)` → `Verified(String keyId)`; lança `RegistrySecurityException` (mensagem com o código `REGISTRY_BAD_SIGNATURE`) se nenhuma assinatura válida de uma chave confiável **ligada a esse `registryId`** existir. `static KeyPair generateKeyPair()`; `static String publicKeyBase64(PublicKey)`; `static PublicKey publicKeyFromBase64(String)`; `static PrivateKey privateKeyFromPem/ToPem` (PKCS#8 PEM).
  - `TrustedKeys`: `record Key(String keyId, String registryId, PublicKey publicKey)`; `TrustedKeys.of(List<Key>)`; `forRegistry(String registryId)`; `static TrustedKeys parse(String json)` (`{"keys":[{"keyid","registryId","publicKey"}]}`); `TrustedKeys.empty()`.
  - `RegistrySecurityException extends RuntimeException` com campo `code()` (`REGISTRY_UNSIGNED`, `REGISTRY_BAD_SIGNATURE`, `REGISTRY_ROLLBACK`, `REGISTRY_EXPIRED`, `REGISTRY_MANIFEST_HASH`, `REGISTRY_MISMATCH`).

- [ ] **Step 1: Testes (falham)**

`RegistrySignatureTest`:

```java
package io.suko.registry;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistrySignatureTest {

    static final byte[] INDEX = "{\"schemaVersion\":2}".getBytes(StandardCharsets.UTF_8);

    private TrustedKeys trusting(KeyPair kp, String keyId, String registryId) {
        return TrustedKeys.of(List.of(new TrustedKeys.Key(keyId, registryId, kp.getPublic())));
    }

    @Test
    void validSignatureVerifies() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        assertEquals("k1", RegistrySignature.verify(INDEX, sig, trusting(kp, "k1", "https://r.example/"), "https://r.example/").keyId());
    }

    @Test
    void tamperedIndexIsRejected() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        byte[] tampered = "{\"schemaVersion\":3}".getBytes(StandardCharsets.UTF_8);
        var e = assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(tampered, sig, trusting(kp, "k1", "https://r.example/"), "https://r.example/"));
        assertEquals("REGISTRY_BAD_SIGNATURE", e.code());
    }

    @Test
    void signatureFromAnUntrustedKeyIsRejected() throws Exception {
        KeyPair attacker = RegistrySignature.generateKeyPair();
        KeyPair real = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, attacker.getPrivate(), "k1");
        assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(INDEX, sig, trusting(real, "k1", "https://r.example/"), "https://r.example/"));
    }

    @Test
    void aKeyBoundToAnotherRegistryNeverValidates() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        byte[] sig = RegistrySignature.sign(INDEX, kp.getPrivate(), "k1");
        assertThrows(RegistrySecurityException.class,
            () -> RegistrySignature.verify(INDEX, sig, trusting(kp, "k1", "https://other.example/"), "https://r.example/"));
    }

    @Test
    void multipleSignaturesAcceptAnyTrustedOne() throws Exception {
        KeyPair old = RegistrySignature.generateKeyPair();
        KeyPair fresh = RegistrySignature.generateKeyPair();
        byte[] a = RegistrySignature.sign(INDEX, old.getPrivate(), "old");
        byte[] b = RegistrySignature.sign(INDEX, fresh.getPrivate(), "new");
        byte[] both = RegistrySignature.combine(a, b);
        assertEquals("new", RegistrySignature.verify(INDEX, both, trusting(fresh, "new", "https://r.example/"), "https://r.example/").keyId());
    }

    @Test
    void malformedSignatureFilesAreSecurityErrorsNotCrashes() {
        TrustedKeys keys = TrustedKeys.empty();
        for (String junk : new String[] {"", "not json", "{}", "{\"signatures\":\"x\"}", "{\"keyid\":1}", "[]"}) {
            assertThrows(RegistrySecurityException.class,
                () -> RegistrySignature.verify(INDEX, junk.getBytes(StandardCharsets.UTF_8), keys, "https://r.example/"), junk);
        }
    }

    @Test
    void pemRoundTrip() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        var back = RegistrySignature.privateKeyFromPem(RegistrySignature.privateKeyToPem(kp.getPrivate()));
        byte[] sig = RegistrySignature.sign(INDEX, back, "k");
        assertEquals("k", RegistrySignature.verify(INDEX, sig, trusting(kp, "k", "r"), "r").keyId());
        assertEquals(kp.getPublic(), RegistrySignature.publicKeyFromBase64(RegistrySignature.publicKeyBase64(kp.getPublic())));
    }

    @Test
    void trustedKeysParse() throws Exception {
        KeyPair kp = RegistrySignature.generateKeyPair();
        String json = "{\"keys\":[{\"keyid\":\"k\",\"registryId\":\"https://r.example/\",\"publicKey\":\""
            + RegistrySignature.publicKeyBase64(kp.getPublic()) + "\"}]}";
        TrustedKeys keys = TrustedKeys.parse(json);
        assertEquals(1, keys.forRegistry("https://r.example/").size());
        assertTrue(keys.forRegistry("https://x/").isEmpty());
        assertTrue(TrustedKeys.parse("{\"keys\":[]}").forRegistry("a").isEmpty());
    }
}
```

Run: `scripts/verify-isolated.sh :suko-registry:test --tests '*RegistrySignatureTest*'`
Expected: FAIL.

- [ ] **Step 2: Implementação**

`RegistrySecurityException`:

```java
package io.suko.registry;

public final class RegistrySecurityException extends RuntimeException {
    private final String code;

    public RegistrySecurityException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
```

`TrustedKeys`:

```java
package io.suko.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;

/** Conjunto de chaves públicas confiáveis; cada chave está ligada a UM registryId. */
public final class TrustedKeys {

    public record Key(String keyId, String registryId, PublicKey publicKey) {
    }

    private final List<Key> keys;

    private TrustedKeys(List<Key> keys) {
        this.keys = List.copyOf(keys);
    }

    public static TrustedKeys of(List<Key> keys) {
        return new TrustedKeys(keys);
    }

    public static TrustedKeys empty() {
        return new TrustedKeys(List.of());
    }

    public List<Key> forRegistry(String registryId) {
        return keys.stream().filter(k -> k.registryId().equals(registryId)).toList();
    }

    public TrustedKeys plus(TrustedKeys other) {
        List<Key> all = new ArrayList<>(keys);
        all.addAll(other.keys);
        return new TrustedKeys(all);
    }

    /** {@code {"keys":[{"keyid":"..","registryId":"..","publicKey":"<base64 X.509>"}]}} */
    public static TrustedKeys parse(String json) {
        JsonElement root = JsonParser.parseString(json);
        if (!root.isJsonObject() || !root.getAsJsonObject().has("keys") || !root.getAsJsonObject().get("keys").isJsonArray()) {
            throw new IllegalArgumentException("trusted-keys: esperado {\"keys\": [...]}");
        }
        JsonArray array = root.getAsJsonObject().getAsJsonArray("keys");
        List<Key> out = new ArrayList<>();
        for (JsonElement e : array) {
            JsonObject o = e.getAsJsonObject();
            out.add(new Key(o.get("keyid").getAsString(), o.get("registryId").getAsString(),
                RegistrySignature.publicKeyFromBase64(o.get("publicKey").getAsString())));
        }
        return new TrustedKeys(out);
    }
}
```

`RegistrySignature`:

```java
package io.suko.registry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Assinatura Ed25519 do índice do registry (bytes exatos, verificada antes do parse). */
public final class RegistrySignature {

    public record Verified(String keyId) {
    }

    private RegistrySignature() {
    }

    public static KeyPair generateKeyPair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 indisponível neste JDK", e);
        }
    }

    public static byte[] sign(byte[] indexBytes, PrivateKey key, String keyId) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(indexBytes);
            JsonObject o = new JsonObject();
            o.addProperty("keyid", keyId);
            o.addProperty("alg", "ed25519");
            o.addProperty("sig", Base64.getEncoder().encodeToString(s.sign()));
            JsonArray list = new JsonArray();
            list.add(o);
            JsonObject root = new JsonObject();
            root.add("signatures", list);
            return (root + "\n").getBytes(StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Junta dois ficheiros .sig numa lista de assinaturas. */
    public static byte[] combine(byte[]... sigFiles) {
        JsonArray all = new JsonArray();
        for (byte[] f : sigFiles) {
            all.addAll(JsonParser.parseString(new String(f, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("signatures"));
        }
        JsonObject root = new JsonObject();
        root.add("signatures", all);
        return (root + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public static Verified verify(byte[] indexBytes, byte[] sigJson, TrustedKeys keys, String registryId) {
        JsonArray signatures;
        try {
            JsonElement root = JsonParser.parseString(new String(sigJson, StandardCharsets.UTF_8));
            if (!root.isJsonObject() || !root.getAsJsonObject().has("signatures")
                || !root.getAsJsonObject().get("signatures").isJsonArray()) {
                throw new IllegalArgumentException("esperado {\"signatures\": [...]}");
            }
            signatures = root.getAsJsonObject().getAsJsonArray("signatures");
        } catch (RuntimeException e) {
            throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE", "ficheiro de assinatura ilegível: " + e.getMessage());
        }
        for (JsonElement el : signatures) {
            try {
                JsonObject o = el.getAsJsonObject();
                String keyId = o.get("keyid").getAsString();
                if (!"ed25519".equals(o.get("alg").getAsString())) {
                    continue;
                }
                byte[] sig = Base64.getDecoder().decode(o.get("sig").getAsString());
                for (TrustedKeys.Key key : keys.forRegistry(registryId)) {
                    if (!key.keyId().equals(keyId)) {
                        continue;
                    }
                    Signature s = Signature.getInstance("Ed25519");
                    s.initVerify(key.publicKey());
                    s.update(indexBytes);
                    if (s.verify(sig)) {
                        return new Verified(keyId);
                    }
                }
            } catch (GeneralSecurityException | RuntimeException ignored) {
                // assinatura mal formada: tenta a seguinte
            }
        }
        throw new RegistrySecurityException("REGISTRY_BAD_SIGNATURE",
            "nenhuma assinatura válida de uma chave confiável para o registry " + registryId);
    }

    public static String publicKeyBase64(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    public static PublicKey publicKeyFromBase64(String b64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("chave pública inválida: " + e.getMessage(), e);
        }
    }

    public static String privateKeyToPem(PrivateKey key) {
        return "-----BEGIN PRIVATE KEY-----\n"
            + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8)).encodeToString(key.getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }

    public static PrivateKey privateKeyFromPem(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("chave privada inválida: " + e.getMessage(), e);
        }
    }
}
```

`RegistryIndex` passa a `SCHEMA_VERSION = 2` com os campos novos (ver "Interfaces"); `RegistryJson.readIndex`/`writeIndex` não mudam de lógica (`requireFields` aceita `expires` nulo — **ajustar**: se `requireFields` exige todos os componentes não nulos, tratar `expires` como opcional). Atualizar `RegistryJsonTest`/`ModelTest` e as fixtures de teste do `suko-registry`, do `suko-registry-generator` e do `suko-cli` para o esquema 2 (acrescentar `registryId`, `ref`, `issuedAt`, `manifestSha256`). **Nenhuma asserção de teste é enfraquecida;** onde um teste lia um índice v1, o fixture passa a v2.

Run: `scripts/verify-isolated.sh :suko-registry:test`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add suko-registry/src
git commit -m "feat(registry): esquema 2 (registryId, ref, issuedAt, expires, manifestSha256) e assinatura Ed25519"
```


---

### Task 10: Gerador do registry — esquema 2, `manifestSha256`, `generate-key` e `sign`

**Files:**
- Modify: `suko-registry/src/main/java/io/suko/registry/RegistryJson.java` (`manifestBytes`, `indexBytes`)
- Modify: `suko-registry-generator/src/main/java/io/suko/registry/GeneratorConfig.java`, `RegistryGenerator.java`
- Create: `suko-registry-generator/src/main/java/io/suko/registry/RegistryTool.java`
- Modify: `suko-components/src/test/java/io/suko/components/RegistryGoldenTest.java` (config com metadados; escrita com `\n`)
- Modify (regenerados): `suko-components/registry.json`, `suko-components/components/*.json`
- Modify: `.gitattributes`
- Test: `suko-registry-generator/src/test/java/io/suko/registry/RegistryGeneratorTest.java` (fixtures para o novo `GeneratorConfig`), `suko-registry-generator/src/test/java/io/suko/registry/RegistryToolTest.java`

**Interfaces:**
- Consumes: `RegistryIndex` v2, `RegistrySignature`, `TrustedKeys` (Task 9).
- Produces:
  - `RegistryJson.manifestBytes(ComponentManifest)` e `RegistryJson.indexBytes(RegistryIndex)` → bytes UTF-8 do JSON + um único `"\n"` (**sempre LF**, independente da plataforma). São os bytes que o gerador escreve **e** cujo hash fica em `manifestSha256`.
  - `GeneratorConfig` ganha o componente `RegistryMetadata metadata` (último): `record RegistryMetadata(String registryId, String ref, String issuedAt, String expires)`; `expires` pode ser `null`.
  - `RegistryTool.main(String[])` com `generate-key --out-dir <dir> --key-id <id> --registry-id <base>` (escreve `<id>.private.pem`, recusa sobrescrever, tenta `0600`; imprime a linha para `trusted-keys.json`) e `sign --registry-dir <dir> --key-id <id> [--key-file <pem>]` (sem `--key-file`, lê a variável `SUKO_REGISTRY_SIGNING_KEY`; **nunca** a chave em argv; escreve `<dir>/registry.json.sig`).

- [ ] **Step 1: Testes do gerador e da ferramenta (falham)**

Em `RegistryGeneratorTest`, o helper `configFor(...)` (que hoje chama `new GeneratorConfig("io.suko", "0.1.0", "src/main/suko", descriptions, componentConfigs)`) passa a acrescentar o último argumento `new GeneratorConfig.RegistryMetadata("https://reg.example/", "v1", "2026-10-05T00:00:00Z", null)`; os outros 3 sítios que constroem `GeneratorConfig` à mão no ficheiro recebem o mesmo argumento. Acrescentar:

```java
    private static String sha256Hex(byte[] bytes) throws Exception {
        return RegistryJson.sha256Hex(bytes);
    }

    @Test
    void indexCarriesRegistryMetadataAndManifestHashes() throws Exception {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));
        assertEquals(2, RegistryIndex.SCHEMA_VERSION);
        assertEquals("https://reg.example/", registry.index().registryId());
        assertEquals("v1", registry.index().ref());
        assertEquals("2026-10-05T00:00:00Z", registry.index().issuedAt());
        assertNull(registry.index().expires());
        assertEquals(2, registry.index().components().size());
        for (RegistryIndex.Entry e : registry.index().components()) {
            byte[] manifest = RegistryJson.manifestBytes(registry.manifestsByName().get(e.name()));
            assertEquals(sha256Hex(manifest), e.manifestSha256(), e.name());
        }
    }

    @Test
    void manifestBytesAreLfTerminatedWhateverThePlatform() {
        GeneratedRegistry registry = RegistryGenerator.generate(VALID_FIXTURE, configFor(VALID_FIXTURE, Map.of(
                "Label", config("1.0.0", "form"),
                "Field", config("1.0.0", "form"))));
        byte[] b = RegistryJson.manifestBytes(registry.manifestsByName().get("label"));
        assertEquals('\n', b[b.length - 1]);
        assertFalse(new String(b, java.nio.charset.StandardCharsets.UTF_8).contains("\r"));
    }
```

(O helper `RegistryJson.sha256Hex(byte[])` é novo, ver o Step 2; o `RegistryGenerator` passa a usá-lo no lugar do seu `sha256Of` privado.)

`RegistryToolTest`:

```java
package io.suko.registry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryToolTest {

    @Test
    void generateKeyThenSignThenVerify(@TempDir Path dir) throws Exception {
        Path reg = dir.resolve("registry");
        Files.createDirectories(reg);
        byte[] index = "{\"schemaVersion\":2}\n".getBytes(StandardCharsets.UTF_8);
        Files.write(reg.resolve("registry.json"), index);

        RegistryTool.main(new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", "k1", "--registry-id", "https://r.example/"});
        Path pem = dir.resolve("k1.private.pem");
        assertTrue(Files.exists(pem));

        RegistryTool.main(new String[] {"sign", "--registry-dir", reg.toString(), "--key-id", "k1", "--key-file", pem.toString()});
        byte[] sig = Files.readAllBytes(reg.resolve("registry.json.sig"));

        // o JDK não deriva a pública de uma privada Ed25519: o gerador guarda-a em <id>.public.txt
        var pub = RegistrySignature.publicKeyFromBase64(Files.readString(dir.resolve("k1.public.txt")).trim());
        TrustedKeys keys = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", "https://r.example/", pub)));
        assertEquals("k1", RegistrySignature.verify(index, sig, keys, "https://r.example/").keyId());
    }

    @Test
    void generateKeyRefusesToOverwrite(@TempDir Path dir) throws Exception {
        String[] args = {"generate-key", "--out-dir", dir.toString(), "--key-id", "k1", "--registry-id", "https://r.example/"};
        RegistryTool.main(args);
        assertThrows(IllegalStateException.class, () -> RegistryTool.main(args));
    }

    @Test
    void signNeverAcceptsTheKeyOnTheCommandLine(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}");
        assertThrows(IllegalArgumentException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k", "--key", "-----BEGIN PRIVATE KEY-----"}));
    }

    @Test
    void signWithoutAKeySourceFails(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("registry.json"), "{}");
        // sem --key-file e sem SUKO_REGISTRY_SIGNING_KEY no ambiente do teste
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k"}));
    }

    @Test
    void signRequiresAnExistingIndex(@TempDir Path dir) throws Exception {
        RegistryTool.main(new String[] {"generate-key", "--out-dir", dir.toString(), "--key-id", "k", "--registry-id", "r"});
        assertThrows(IllegalStateException.class, () ->
            RegistryTool.main(new String[] {"sign", "--registry-dir", dir.toString(), "--key-id", "k", "--key-file", dir.resolve("k.private.pem").toString()}));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-registry-generator:test`
Expected: FAIL.

- [ ] **Step 2: Implementação**

`RegistryJson`: acrescentar

```java
    public static byte[] manifestBytes(ComponentManifest manifest) {
        return (writeManifest(manifest) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static byte[] indexBytes(RegistryIndex index) {
        return (writeIndex(index) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
```

`RegistryGenerator.generate`: ao criar cada `RegistryIndex.Entry`, calcular `RegistryJson.sha256Hex(RegistryJson.manifestBytes(manifest))` e passá-lo como último argumento; o `RegistryIndex` final recebe `config.metadata()` (`registryId`, `ref`, `issuedAt`, `expires`).

`RegistryTool`:

```java
package io.suko.registry;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.util.HashMap;
import java.util.Map;

/** Ferramenta do mantenedor: gerar o par de chaves e assinar o índice. Nunca recebe a chave em argv. */
public final class RegistryTool {

    private RegistryTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            throw new IllegalArgumentException("uso: generate-key --out-dir D --key-id ID --registry-id URL | sign --registry-dir D --key-id ID [--key-file F]");
        }
        Map<String, String> opts = parse(args);
        switch (args[0]) {
            case "generate-key" -> generateKey(Path.of(require(opts, "out-dir")), require(opts, "key-id"), require(opts, "registry-id"));
            case "sign" -> sign(Path.of(require(opts, "registry-dir")), require(opts, "key-id"), opts.get("key-file"));
            default -> throw new IllegalArgumentException("comando desconhecido: " + args[0]);
        }
    }

    private static void generateKey(Path outDir, String keyId, String registryId) {
        try {
            Files.createDirectories(outDir);
            Path file = outDir.resolve(keyId + ".private.pem");
            KeyPair pair = RegistrySignature.generateKeyPair();
            try {
                Files.writeString(file, RegistrySignature.privateKeyToPem(pair.getPrivate()), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            } catch (java.nio.file.FileAlreadyExistsException e) {
                throw new IllegalStateException("A chave " + file + " já existe; não é sobrescrita");
            }
            Files.writeString(outDir.resolve(keyId + ".public.txt"), RegistrySignature.publicKeyBase64(pair.getPublic()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // sistemas de ficheiros sem POSIX (Windows): fica a permissão por omissão
            }
            System.out.println("Chave privada: " + file + "  (NUNCA a committar)");
            System.out.println("Linha para trusted-keys.json:");
            System.out.println("{\"keyid\": \"" + keyId + "\", \"registryId\": \"" + registryId
                + "\", \"publicKey\": \"" + RegistrySignature.publicKeyBase64(pair.getPublic()) + "\"}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void sign(Path registryDir, String keyId, String keyFile) {
        try {
            Path index = registryDir.resolve("registry.json");
            if (!Files.isRegularFile(index)) {
                throw new IllegalStateException("registry.json não existe em " + registryDir);
            }
            String pem;
            if (keyFile != null) {
                pem = Files.readString(Path.of(keyFile), StandardCharsets.UTF_8);
            } else {
                pem = System.getenv("SUKO_REGISTRY_SIGNING_KEY");
                if (pem == null || pem.isBlank()) {
                    throw new IllegalStateException("Sem chave: use --key-file <ficheiro> ou a variável SUKO_REGISTRY_SIGNING_KEY");
                }
            }
            byte[] sig = RegistrySignature.sign(Files.readAllBytes(index), RegistrySignature.privateKeyFromPem(pem), keyId);
            Files.write(registryDir.resolve("registry.json.sig"), sig);
            System.out.println("Escreveu " + registryDir.resolve("registry.json.sig"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 1; i < args.length; i++) {
            if (!args[i].startsWith("--")) {
                throw new IllegalArgumentException("argumento inesperado: " + args[i]);
            }
            String name = args[i].substring(2);
            if (name.equals("key")) {
                throw new IllegalArgumentException("a chave privada nunca se passa na linha de comandos (use --key-file ou SUKO_REGISTRY_SIGNING_KEY)");
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("falta o valor de --" + name);
            }
            opts.put(name, args[++i]);
        }
        return opts;
    }

    private static String require(Map<String, String> opts, String name) {
        String v = opts.get(name);
        if (v == null) {
            throw new IllegalArgumentException("falta --" + name);
        }
        return v;
    }
}
```

`GeneratorConfig`: novo componente `RegistryMetadata metadata` + o record aninhado (ver "Interfaces").

`RegistryGoldenTest`: `buildConfig()` passa `new GeneratorConfig.RegistryMetadata("https://raw.githubusercontent.com/DumiJDev/suko/", "main", "2026-10-05T00:00:00Z", null)` (valores fixos — a geração tem de ser determinística; o mantenedor atualiza `issuedAt`/`ref`/`expires` ao cortar uma release); `main` escreve com `RegistryJson.indexBytes`/`manifestBytes` (`Files.write(path, bytes)`), e os testes que comparam passam a comparar `new String(bytes, UTF_8)` (já usam `.strip()`, mantê-lo). **`registryId` = a `base` com que um projeto configura o registry** (ver `InitCommand.DEFAULT_REGISTRY_BASE_TEMPLATE`): **ler essa constante e usar a mesma base sem o `%s`/ref** — se a base do registry oficial for outra, usar a real.

`.gitattributes` (acrescentar):

```
suko-components/registry.json text eol=lf
suko-components/registry.json.sig text eol=lf
suko-components/components/*.json text eol=lf
```

- [ ] **Step 3: Regenerar e verificar**

Run: `SUKO_PULL="suko-components" scripts/verify-isolated.sh :suko-components:generateRegistry --console=plain` e depois `scripts/verify-isolated.sh :suko-registry:test :suko-registry-generator:test :suko-components:test`
Expected: PASS. `git diff --stat suko-components/registry.json suko-components/components` deve mostrar só: campos novos no índice, `manifestSha256` em cada entrada e finais de linha LF.

- [ ] **Step 4: Commit**

```bash
git add suko-registry/src suko-registry-generator/src suko-components/src suko-components/registry.json suko-components/components .gitattributes
git commit -m "feat(registry-generator): esquema 2 com manifestSha256, generate-key e sign (Ed25519); registry.json regenerado"
```

---

### Task 11: CLI — verificação do índice, anti-rollback, anti-strip e hash dos manifestos

**Files:**
- Create: `suko-cli/src/main/java/io/suko/cli/VerifiedIndex.java`, `suko-cli/src/main/java/io/suko/cli/Trust.java`
- Create: `suko-cli/src/main/resources/trusted-keys.json` (`{"keys": []}` — ver R6)
- Modify: `suko-cli/src/main/java/io/suko/cli/Args.java` (flags `--allow-unsigned`, `--allow-downgrade`; ajuda), `Lockfile.java` (`signed`, `keyId`, `issuedAt` em `Registry`), `ProjectConfig.java` (`registry.publicKeys`), `Resolver.java` (`manifestSha256`)
- Modify: `suko-cli/src/main/java/io/suko/cli/command/{AddCommand,ListCommand,DiffCommand,UpdateCommand}.java` (usar `VerifiedIndex.load`; gravar o estado no lockfile)
- Test: `suko-cli/src/test/java/io/suko/cli/VerifiedIndexTest.java`; ajustar os testes existentes que usam registries locais para passarem `--allow-unsigned`

**Interfaces:**
- Consumes: `RegistrySignature`, `TrustedKeys`, `RegistrySecurityException`, `RegistryIndex` v2 (Task 9), `Version`, `Lockfile`.
- Produces:
  - `VerifiedIndex.load(RegistrySource source, String registryBase, String registryRef, Optional<Lockfile> lock, boolean allowUnsigned, boolean allowDowngrade, TrustedKeys extraKeys, PrintStream warn)` → `VerifiedIndex.Result(RegistryIndex index, boolean signed, String keyId)`; lança `CliException` cuja mensagem começa pelo código (`REGISTRY_UNSIGNED`, `REGISTRY_BAD_SIGNATURE`, `REGISTRY_MISMATCH`, `REGISTRY_EXPIRED`, `REGISTRY_ROLLBACK`).
  - `static boolean VerifiedIndex.isLocal(RegistrySource source, String base)` — `FileSystemRegistrySource`, ou `http(s)://localhost`/`127.0.0.1`/`[::1]`.
  - `Lockfile.Registry(String base, String ref, String registryVersion, boolean signed, String keyId, String issuedAt)` (o construtor de 3 argumentos mantém-se, `signed=false`); `toJson`/`parse` só escrevem/lêem os novos campos quando presentes (lockfiles antigos continuam a ler).
  - `Resolver.loadManifest` verifica `entry.manifestSha256()` contra os bytes **antes** do parse: `CliException("REGISTRY_MANIFEST_HASH: ...")`.

- [ ] **Step 1: Testes (falham)**

`VerifiedIndexTest` (constrói registries em disco com `RegistryJson`/`RegistrySignature`, sem rede):

```java
package io.suko.cli;

import io.suko.registry.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VerifiedIndexTest {

    static final String BASE = "https://reg.example/";
    final KeyPair keys = RegistrySignature.generateKeyPair();
    final ByteArrayOutputStream warnings = new ByteArrayOutputStream();

    TrustedKeys trusted() {
        return TrustedKeys.of(List.of(new TrustedKeys.Key("k1", BASE, keys.getPublic())));
    }

    /** Escreve registry.json (+ .sig opcional) e devolve a source. */
    RegistrySource registry(Path dir, String registryId, String ref, String issuedAt, String expires,
                            String registryVersion, boolean sign) throws Exception {
        RegistryIndex index = new RegistryIndex(RegistryIndex.SCHEMA_VERSION, registryVersion, "com.acme",
            registryId, ref, issuedAt, expires, List.of());
        byte[] bytes = RegistryJson.indexBytes(index);
        Files.write(dir.resolve("registry.json"), bytes);
        if (sign) {
            Files.write(dir.resolve("registry.json.sig"), RegistrySignature.sign(bytes, keys.getPrivate(), "k1"));
        }
        return new FileSystemRegistrySource(dir);
    }

    VerifiedIndex.Result load(RegistrySource s, Optional<Lockfile> lock, boolean allowUnsigned, boolean allowDowngrade) {
        return VerifiedIndex.load(s, BASE, "v1", lock, allowUnsigned, allowDowngrade, trusted(), new PrintStream(warnings));
    }

    static Lockfile lockWith(boolean signed, String issuedAt, String registryVersion) {
        return new Lockfile(1, new Lockfile.Registry(BASE, "v1", registryVersion, signed, signed ? "k1" : null, issuedAt),
            "com.acme", "src", List.of());
    }

    void assertCode(String code, Runnable r) {
        CliException e = assertThrows(CliException.class, r::run);
        assertTrue(e.getMessage().startsWith(code), e.getMessage());
    }

    @Test
    void signedIndexLoads(@TempDir Path d) throws Exception {
        var r = load(registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true), Optional.empty(), false, false);
        assertTrue(r.signed());
        assertEquals("k1", r.keyId());
    }

    @Test
    void unsignedIsRefusedEvenWhenLocalUnlessExplicitlyAllowed(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", false);
        assertCode("REGISTRY_UNSIGNED", () -> load(s, Optional.empty(), false, false));
        var r = load(s, Optional.empty(), true, false);
        assertFalse(r.signed());
        assertTrue(warnings.toString().contains("sem assinatura"), warnings.toString());
    }

    @Test
    void allowUnsignedIsRefusedForRemoteRegistries() {
        RegistrySource remote = new RegistrySource() {
            public byte[] resolve(String p) { throw new UnsupportedOperationException(); }
            public String base() { return BASE; }
        };
        assertCode("REGISTRY_UNSIGNED", () -> VerifiedIndex.load(remote, BASE, "v1", Optional.empty(), true, false, trusted(), new PrintStream(warnings)));
    }

    @Test
    void isLocalRecognisesFilesystemAndLoopback(@TempDir Path d) {
        assertTrue(VerifiedIndex.isLocal(new FileSystemRegistrySource(d), d.toString()));
        assertTrue(VerifiedIndex.isLocal(null, "http://localhost:8080/"));
        assertTrue(VerifiedIndex.isLocal(null, "http://127.0.0.1/"));
        assertTrue(VerifiedIndex.isLocal(null, "http://[::1]:9/"));
        assertFalse(VerifiedIndex.isLocal(null, "https://reg.example/"));
        assertFalse(VerifiedIndex.isLocal(null, "http://localhost.evil.example/"));
    }

    @Test
    void tamperedIndexFailsTheSignature(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        Files.writeString(d.resolve("registry.json"), Files.readString(d.resolve("registry.json")).replace("0.2.0", "9.9.9"));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> load(s, Optional.empty(), false, false));
    }

    @Test
    void signatureFromAKeyBoundToAnotherRegistryIsRejected(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true);
        TrustedKeys other = TrustedKeys.of(List.of(new TrustedKeys.Key("k1", "https://other.example/", keys.getPublic())));
        assertCode("REGISTRY_BAD_SIGNATURE", () -> VerifiedIndex.load(s, BASE, "v1", Optional.empty(), false, false, other, new PrintStream(warnings)));
    }

    @Test
    void registryIdAndRefMustMatchTheConfiguration(@TempDir Path d) throws Exception {
        assertCode("REGISTRY_MISMATCH", () -> {
            try { load(registry(d, "https://evil.example/", "v1", "2026-10-05T00:00:00Z", null, "0.2.0", true), Optional.empty(), false, false); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
        assertCode("REGISTRY_MISMATCH", () -> {
            try { load(registry(d, BASE, "v9", "2026-10-05T00:00:00Z", null, "0.2.0", true), Optional.empty(), false, false); }
            catch (Exception e) { throw new RuntimeException(e); }
        });
    }

    @Test
    void expiredIndexIsRefused(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2020-01-01T00:00:00Z", "2020-02-01T00:00:00Z", "0.2.0", true);
        assertCode("REGISTRY_EXPIRED", () -> load(s, Optional.empty(), false, false));
        var ok = registry(d, BASE, "v1", Instant.now().toString(), Instant.now().plusSeconds(3600).toString(), "0.2.0", true);
        assertTrue(load(ok, Optional.empty(), false, false).signed());
    }

    @Test
    void rollbackToAnOlderSignedIndexIsRefusedUnlessDowngradeIsExplicit(@TempDir Path d) throws Exception {
        var older = registry(d, BASE, "v1", "2026-01-01T00:00:00Z", null, "0.1.0", true);
        Lockfile lock = lockWith(true, "2026-10-05T00:00:00Z", "0.2.0");
        assertCode("REGISTRY_ROLLBACK", () -> load(older, Optional.of(lock), false, false));
        assertTrue(load(older, Optional.of(lock), false, true).signed());
    }

    @Test
    void sameIssuedAtButLowerVersionIsAlsoARollback(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-05T00:00:00Z", null, "0.1.0", true);
        assertCode("REGISTRY_ROLLBACK", () -> load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), false, false));
    }

    @Test
    void aRegistrySeenSignedCannotBeStrippedEvenWithAllowUnsigned(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-10-06T00:00:00Z", null, "0.3.0", false);
        assertCode("REGISTRY_UNSIGNED", () -> load(s, Optional.of(lockWith(true, "2026-10-05T00:00:00Z", "0.2.0")), true, false));
    }

    @Test
    void lockfileFromAnotherRegistryDoesNotTriggerRollback(@TempDir Path d) throws Exception {
        var s = registry(d, BASE, "v1", "2026-01-01T00:00:00Z", null, "0.1.0", true);
        Lockfile other = new Lockfile(1, new Lockfile.Registry("https://other.example/", "v1", "9.9.9", true, "k1", "2030-01-01T00:00:00Z"),
            "com.acme", "src", List.of());
        assertTrue(load(s, Optional.of(other), false, false).signed());
    }

    @Test
    void manifestWithAWrongHashIsRefusedByTheResolver(@TempDir Path d) throws Exception {
        // Índice assinado cuja entrada aponta para um manifesto adulterado (hashes coerentes dentro do manifesto, mas
        // o manifestSha256 do índice assinado não bate): tem de falhar ANTES do parse.
        ComponentManifest m = new ComponentManifest(RegistryIndex.SCHEMA_VERSION, "button", "0.2.0", "d", "action", "com.acme", "ui",
            "Button", List.of(new ComponentFile("src/Button.sk", "Button.sk", "00")), List.of(), List.of());
        Files.createDirectories(d.resolve("components"));
        byte[] good = RegistryJson.manifestBytes(m);
        RegistryIndex index = new RegistryIndex(RegistryIndex.SCHEMA_VERSION, "0.2.0", "com.acme", BASE, "v1", "2026-10-05T00:00:00Z", null,
            List.of(new RegistryIndex.Entry("button", "0.2.0", "d", "action", "components/button.json", RegistryJson.sha256Hex(good))));
        Files.write(d.resolve("components/button.json"), RegistryJson.manifestBytes(
            new ComponentManifest(RegistryIndex.SCHEMA_VERSION, "button", "0.2.0", "d", "action", "com.acme", "ui",
                "Button", List.of(new ComponentFile("src/Button.sk", "Button.sk", "11")), List.of(), List.of())));
        var source = new FileSystemRegistrySource(d);
        CliException e = assertThrows(CliException.class, () -> Resolver.resolve(index, source, List.of("button")));
        assertTrue(e.getMessage().startsWith("REGISTRY_MANIFEST_HASH"), e.getMessage());
    }
}
```

(`ComponentManifest`/`ComponentFile` têm construtores com estes argumentos — ver `RegistryGenerator` onde são construídos; ajustar a ordem dos argumentos ao construtor real.)

Run: `scripts/verify-isolated.sh :suko-cli:test --tests '*VerifiedIndexTest*'`
Expected: FAIL.

- [ ] **Step 2: Implementação**

`VerifiedIndex.java`:

```java
package io.suko.cli;

import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistryJsonException;
import io.suko.registry.RegistrySecurityException;
import io.suko.registry.RegistrySignature;
import io.suko.registry.RegistrySource;
import io.suko.registry.TrustedKeys;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/** Carrega o índice do registry: assinatura, identidade, validade e anti-rollback, antes de qualquer outra coisa. */
public final class VerifiedIndex {

    public record Result(RegistryIndex index, boolean signed, String keyId) {
    }

    private VerifiedIndex() {
    }

    public static Result load(RegistrySource source, String registryBase, String registryRef, Optional<Lockfile> lock,
                              boolean allowUnsigned, boolean allowDowngrade, TrustedKeys extraKeys, PrintStream warn) {
        boolean local = isLocal(source, registryBase);
        byte[] indexBytes;
        try {
            indexBytes = source.resolve("registry.json");
        } catch (IOException e) {
            throw new CliException("Could not read registry.json from \"" + registryBase + "\": " + e.getMessage());
        }
        byte[] sigBytes = null;
        try {
            sigBytes = source.resolve("registry.json.sig");
        } catch (IOException | RuntimeException e) {
            sigBytes = null; // ausente: tratado abaixo
        }

        Optional<Lockfile.Registry> seen = lock.map(Lockfile::registry).filter(r -> r.base().equals(registryBase));
        boolean signed = sigBytes != null;
        String keyId = null;

        if (signed) {
            try {
                keyId = RegistrySignature.verify(indexBytes, sigBytes, Trust.keys().plus(extraKeys), registryBase).keyId();
            } catch (RegistrySecurityException e) {
                throw new CliException(e.getMessage());
            }
        } else {
            if (seen.isPresent() && seen.get().signed()) {
                throw new CliException("REGISTRY_UNSIGNED: o registry \"" + registryBase
                    + "\" já foi visto assinado e agora não tem assinatura (possível ataque de remoção); --allow-unsigned não é aceite aqui.");
            }
            if (!allowUnsigned || !local) {
                throw new CliException("REGISTRY_UNSIGNED: o registry \"" + registryBase + "\" não tem registry.json.sig."
                    + (local ? " Para um registry local de desenvolvimento use --allow-unsigned."
                             : " --allow-unsigned só é aceite para registries locais (caminho, localhost, 127.0.0.1)."));
            }
            warn.println("WARNING: o registry \"" + registryBase + "\" está sem assinatura (--allow-unsigned); o conteúdo não é autenticado.");
        }

        RegistryIndex index;
        try {
            index = RegistryJson.readIndex(new String(indexBytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException("Could not parse registry.json from \"" + registryBase + "\": " + e.getMessage());
        }

        if (signed) {
            if (!registryBase.equals(index.registryId())) {
                throw new CliException("REGISTRY_MISMATCH: o índice assinado é do registry \"" + index.registryId()
                    + "\", não de \"" + registryBase + "\".");
            }
            if (!registryRef.equals(index.ref())) {
                throw new CliException("REGISTRY_MISMATCH: o índice assinado é da ref \"" + index.ref()
                    + "\", mas foi pedida \"" + registryRef + "\".");
            }
            if (index.expires() != null && instant(index.expires(), "expires").isBefore(Instant.now())) {
                throw new CliException("REGISTRY_EXPIRED: o índice expirou em " + index.expires() + ".");
            }
            if (seen.isPresent() && seen.get().issuedAt() != null && !allowDowngrade) {
                Instant now = instant(index.issuedAt(), "issuedAt");
                Instant before = instant(seen.get().issuedAt(), "issuedAt (lockfile)");
                boolean older = now.isBefore(before);
                boolean sameButLowerVersion = now.equals(before) && seen.get().registryVersion() != null
                    && compareVersions(index.registryVersion(), seen.get().registryVersion()) < 0;
                if (older || sameButLowerVersion) {
                    throw new CliException("REGISTRY_ROLLBACK: o índice (" + index.issuedAt() + ", versão "
                        + index.registryVersion() + ") é mais antigo do que o já instalado (" + seen.get().issuedAt()
                        + ", versão " + seen.get().registryVersion() + "). Use --allow-downgrade se for intencional.");
                }
            }
        }
        return new Result(index, signed, keyId);
    }

    public static boolean isLocal(RegistrySource source, String base) {
        if (source instanceof FileSystemRegistrySource) {
            return true;
        }
        for (String prefix : new String[] {"http://", "https://"}) {
            if (base.startsWith(prefix)) {
                String rest = base.substring(prefix.length());
                String host = rest.startsWith("[") ? rest.substring(0, rest.indexOf(']') + 1)
                    : rest.split("[:/]", 2)[0];
                return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
            }
        }
        return !base.contains("://");
    }

    private static Instant instant(String value, String field) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new CliException("REGISTRY_MISMATCH: o campo " + field + " do índice não é uma data ISO-8601: " + value);
        }
    }

    /** Compara versões "x.y.z" (numérico; sufixos de pré-release ordenam antes da final). */
    static int compareVersions(String a, String b) {
        String[] pa = a.split("[.-]");
        String[] pb = b.split("[.-]");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            String x = i < pa.length ? pa[i] : "0";
            String y = i < pb.length ? pb[i] : "0";
            boolean nx = x.chars().allMatch(Character::isDigit);
            boolean ny = y.chars().allMatch(Character::isDigit);
            int c = nx && ny ? Long.compare(Long.parseLong(x), Long.parseLong(y)) : x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
```

`Trust.java`:

```java
package io.suko.cli;

import io.suko.registry.TrustedKeys;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Chaves embutidas no CLI (recurso trusted-keys.json); vazio até à primeira release (ver R6 do plano). */
final class Trust {

    private static final TrustedKeys EMBEDDED = load();

    private Trust() {
    }

    static TrustedKeys keys() {
        return EMBEDDED;
    }

    private static TrustedKeys load() {
        try (InputStream in = Trust.class.getResourceAsStream("/trusted-keys.json")) {
            if (in == null) {
                return TrustedKeys.empty();
            }
            return TrustedKeys.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`Lockfile.Registry`: novos campos opcionais; `parse` lê `signed` (`false` se ausente), `keyId`, `issuedAt` (nulos se ausentes); `toJson` só escreve os que existem (`signed` só quando `true`). `Resolver.loadManifest`: depois de `source.resolve(entry.manifest())` e **antes** do parse, `if (!Hashes.sha256OfRaw(bytes).equals(entry.manifestSha256())) throw new CliException("REGISTRY_MANIFEST_HASH: ...")`. `Args`: flags `--allow-unsigned` e `--allow-downgrade` (campos, getters, texto de ajuda dos comandos `add`/`list`/`diff`/`update`). `ProjectConfig.Registry`: campo opcional `publicKeys` (`[{"keyid","publicKey"}]`, ligadas ao `base` configurado) e método `TrustedKeys trustedKeys()`; o construtor de dois argumentos mantém-se.

Os quatro comandos: substituir o método `loadIndex(...)` duplicado por

```java
        VerifiedIndex.Result verified = VerifiedIndex.load(source, registryBase, registryRef, Lockfile.load(projectDir),
            args.allowUnsigned(), args.allowDowngrade(), config.registry().trustedKeys(), System.err);
        RegistryIndex index = verified.index();
```

(o `ListCommand` não tem `projectDir`/lockfile: usa `Optional.empty()`), e `buildLockfile` grava `new Lockfile.Registry(base, ref, index.registryVersion(), verified.signed(), verified.keyId(), index.issuedAt())`.

Os testes existentes do `suko-cli` que usam registries em diretório e **não** assinados passam a incluir `--allow-unsigned` nos argumentos (e os fixtures de índice a ser do esquema 2). **Nenhuma asserção é alterada.**

Run: `scripts/verify-isolated.sh :suko-cli:test`
Expected: PASS (incluindo `VerifiedIndexTest`, `FullCycleTest`, `NativeImageSmokeTest` se existir no ambiente).

**Native-image:** o `suko-cli` também sai como executável nativo (jbang + native-image). Confirmar que `Signature.getInstance("Ed25519")` e `KeyFactory.getInstance("Ed25519")` funcionam no binário nativo; se o ambiente não tiver GraalVM para o provar, **registar a verificação como pendente** em `ARCHITECTURE.md` (Task 15) — não declarar o nativo como suportado sem a prova.

- [ ] **Step 3: Commit**

```bash
git add suko-cli/src
git commit -m "feat(cli): índice do registry verificado (assinatura Ed25519, id/ref, validade, rollback, anti-strip) e manifestSha256"
```

---

### Task 12: Loja de exemplo (1/2) — esqueleto, dados H2 e leitura (catálogo, produto, pesquisa)

**Files (todos novos, em `examples/shop/`):**
- `settings.gradle.kts`, `build.gradle.kts`, `.gitignore`, `README.md`
- `src/main/resources/application.properties`, `schema.sql`, `data.sql`, `static/css/shop.css`
- `src/main/java/shop/ShopApplication.java`, `catalog/CatalogRepository.java`, `web/CatalogController.java`, `view/Views.java`
- `src/main/suko/shop/{Layout,ProductCard,ProductGrid,SearchBox}.sk`, `HomePage.sk`, `CategoryPage.sk`, `ProductPage.sk`, `SearchPage.sk`, `NotFoundPage.sk`
- `src/test/java/shop/CatalogPagesTest.java`

**Interfaces:**
- Consumes: plugin `io.suko.lang` do build principal (composite), `suko.security`/`generatedPackage` (Task 6).
- Produces: controladores que devolvem nomes de view `shop/<Page>` com modelos de tipos suportados pelos params do Suko (`String`, `int`, `boolean`, `List<String>`, `List<Map<String,String>>`, `Map<String,String>`); `CatalogRepository` (`JdbcClient`): `categories()`, `products(Long categoryId, int limit)`, `product(long id)`, `search(String q, int limit)`, `reviews(long productId)`; `Views.product(Map<String,Object>)` → `Map<String,String>` (preço formatado, `id` como texto).

**Rulings da loja (para quem implementa):**
- **Tipos de `view model`:** a gramática atual não aceita nomes de tipo qualificados nem importa tipos Java (`type: Identifier typeArguments? arrayMarker*`; `import` só de componentes) — o item 12 / 11c trata disso. Por isso os componentes da loja recebem `List<Map<String,String>>` e `Map<String,String>`; `Views` achata linhas SQL para `Map<String,String>` (tudo texto, preços já formatados). Registar isto em `ARCHITECTURE.md` na Task 15 como lacuna que o item 12 fecha.
- **`<!DOCTYPE html>`:** verificar se o parser aceita; se não, o `Layout` fica sem doctype (modo quirks) e a lacuna regista-se na Task 15 — **não** contornar com um `.jte` escrito à mão.
- **Templates:** `sukoCompile` escreve em `src/main/jte`; `application.properties` põe `gg.jte.development-mode=true` e `gg.jte.template-location=src/main/jte` (compilação em runtime, sem o plugin do JTE → `suko.security.jtePolicy` fica `false` e o README da loja diz porquê).

- [ ] **Step 1: Build isolado que usa o plugin do repositório**

`examples/shop/settings.gradle.kts`:

```kotlin
pluginManagement {
    // O plugin io.suko.lang vem do build principal (composite): o exemplo usa-o como qualquer utilizador usaria.
    includeBuild("../..")
}
rootProject.name = "suko-shop"
```

`examples/shop/build.gradle.kts`:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.4"
    id("io.spring.dependency-management") version "1.1.6"
    id("io.suko.lang")
}

group = "shop"
version = "0.1.0"

java { sourceCompatibility = JavaVersion.VERSION_21 }

repositories { mavenCentral() }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("gg.jte:jte-spring-boot-starter-3:3.1.12")
    runtimeOnly("com.h2database:h2")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jsoup:jsoup:1.17.2")
}

suko {
    sourceDir.set("src/main/suko")
    outputDir.set("src/main/jte")                // raiz de templates do starter do JTE
    generatedPackage.set("shop.suko")
    security { jtePolicy.set(false) }            // templates compilados em runtime: sem o plugin do JTE
}

tasks.test { useJUnitPlatform() }
```

`.gitignore`: `build/` e `src/main/jte/` (gerado).

**Verificação empírica obrigatória antes de continuar:** `scripts/verify-isolated.sh -p examples/shop sukoCompile` com um `src/main/suko/shop/Ping.sk` mínimo. Se o Gradle **não** resolver `id("io.suko.lang")` pelo composite (o build principal não declara o plugin na raiz), **parar e reportar** com a mensagem do Gradle: o fallback acordado é tornar `examples/shop` um subprojeto do build principal (`include("examples:shop")` no `settings.gradle.kts` da raiz) e aplicar o plugin com `buildscript { dependencies { classpath(project(":suko-gradle-plugin")) } }` — isso altera o R8 e tem de ser aprovado pelo controller.

- [ ] **Step 2: Dados**

`schema.sql` e `data.sql` conforme a spec da loja: tabelas `category`, `product`, `review` (com `website varchar(200)` opcional), `orders`, `order_line`; 3 categorias (`livros`, `cafe`, `casa`) e **12 produtos** com nomes e descrições em português; **pelo menos um** produto cuja descrição tem `&`, aspas e o texto literal `<b>negrito?</b>` (para provar o escape), e 4 avaliações benignas. IDs fixos nos `insert` (a identidade `auto_increment` só em `review`/`orders`).

`application.properties`:

```properties
spring.application.name=suko-shop
spring.datasource.url=jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1
spring.sql.init.mode=always
spring.h2.console.enabled=false
gg.jte.development-mode=true
gg.jte.template-location=src/main/jte
server.servlet.session.cookie.http-only=true
server.servlet.session.cookie.same-site=lax
```

- [ ] **Step 3: Testes das páginas de leitura (falham)**

`CatalogPagesTest` (`@SpringBootTest @AutoConfigureMockMvc`):

```java
package shop;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogPagesTest {

    @Autowired MockMvc mvc;

    private Document page(String url) throws Exception {
        String html = mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return Jsoup.parse(html);
    }

    @Test
    void homeListsProductsAndCategories() throws Exception {
        Document d = page("/");
        assertTrue(d.select(".product-card").size() >= 8);
        assertEquals(3, d.select("nav a[href^=/c/]").size());
        assertFalse(d.title().isBlank());
    }

    @Test
    void categoryListsOnlyItsProducts() throws Exception {
        Document d = page("/c/livros");
        assertFalse(d.select(".product-card").isEmpty());
        assertTrue(d.select("h1").text().toLowerCase().contains("livros"));
    }

    @Test
    void unknownCategoryAndProductAre404() throws Exception {
        mvc.perform(get("/c/nao-existe")).andExpect(status().isNotFound());
        mvc.perform(get("/p/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/p/abc")).andExpect(status().isBadRequest());
    }

    @Test
    void productPageEscapesDescriptionMarkup() throws Exception {
        // o produto semeado com "<b>negrito?</b>" na descrição: o texto aparece, o elemento <b> não
        Document d = page("/p/1");
        String bodyText = d.select(".description").text();
        assertTrue(d.select(".description b").isEmpty(), d.select(".description").html());
        assertTrue(bodyText.contains("negrito?") || d.select(".description").html().contains("&lt;b&gt;"), bodyText);
    }

    @Test
    void searchFindsByNameAndEchoesTheQueryEscaped() throws Exception {
        Document d = page("/search?q=caf%C3%A9");
        assertFalse(d.select(".product-card").isEmpty());
        assertEquals("café", d.select("input[name=q]").attr("value"));
    }

    @Test
    void searchHandlesHostileInputWithoutErrors() throws Exception {
        for (String q : new String[] {"%27%20OR%201%3D1%20--", "%22%3E%3Cimg%20src%3Dx%20onerror%3Dalert(1)%3E", "%25", "", "a".repeat(500)}) {
            Document d = page("/search?q=" + q);
            assertTrue(d.select("img[onerror]").isEmpty());
            assertTrue(d.select("script").isEmpty());
        }
    }

    @Test
    void emptySearchShowsAHelpfulMessageNotAnError() throws Exception {
        assertTrue(page("/search?q=zzzznaoexiste").select(".empty").text().length() > 0);
    }
}
```

Run: `scripts/verify-isolated.sh -p examples/shop test`
Expected: FAIL (a aplicação ainda não existe).

- [ ] **Step 4: Implementação**

`ShopApplication`, `CatalogRepository` (JdbcClient, **sempre** parâmetros nomeados — nenhuma concatenação SQL; `search` usa `lower(name) like :q escape '\\'` com `%`, `_` e `\` do input escapados e o limite de 80 caracteres aplicado antes), `Views` (achata para `Map<String,String>` com `id`, `name`, `description`, `price` formatado `"12,50 €"`, `imageUrl`, `categorySlug`, `stock`), `CatalogController`:

```java
@Controller
class CatalogController {
    private final CatalogRepository repo;
    CatalogController(CatalogRepository repo) { this.repo = repo; }

    @GetMapping("/")
    String home(Model m) {
        m.addAttribute("title", "Suko Shop");
        m.addAttribute("categories", Views.categories(repo.categories()));
        m.addAttribute("products", Views.products(repo.products(null, 12)));
        m.addAttribute("cartCount", 0);   // substituído pelo carrinho na Task 13
        return "shop/HomePage";
    }
    // /c/{slug}, /p/{id} (id numérico: MethodArgumentTypeMismatch -> 400), /search?q=
    // desconhecido -> ResponseStatusException(NOT_FOUND) renderizando shop/NotFoundPage com status 404
}
```

Componentes Suko (todos em `package shop;`, **sem** HTML construído por concatenação de strings — só atributos `${...}` e texto): `Layout(String title, int cartCount, List<Map<String,String>> categories, Component children)` com `<header>`, `<nav>` (links `/c/<slug>` e `/cart`), `<main>${children}</main>`, `<footer>`; `ProductCard(Map<String,String> p)` com `<a href="/p/${p.get("id")}">`, `<img src=${p.get("imageUrl")} alt=${p.get("name")}>`, `<span class="price">${p.get("price")}</span>`; `ProductGrid`; `SearchBox(String q)` (`<form action="/search" method="get"><input name="q" value=${q}>`); páginas `HomePage`, `CategoryPage`, `ProductPage` (inclui `.description` e, por agora, lista de avaliações só leitura), `SearchPage`, `NotFoundPage`. Consultar `examples/layout/LayoutComponents.sk` (a apagar na Task 14 — **ler agora**) e `suko-components/src/main/suko/io/suko/ui/Card.sk` para a sintaxe real de slots e de chamadas com `Component children`.

CSS mínimo em `static/css/shop.css` (sem `style=` inline em nenhum componente — a loja tem de passar com `strictCsp = true`; acrescentar `strictCsp.set(true)` ao bloco `security` do build e confirmar que não aparece nenhum `CSP_INLINE`).

Run: `scripts/verify-isolated.sh -p examples/shop test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add examples/shop
git commit -m "feat(examples): loja Suko+Spring Boot+H2 (1/2) — catálogo, produto, pesquisa; strictCsp limpo"
```

---

### Task 13: Loja de exemplo (2/2) — avaliações, carrinho, checkout, Spring Security e corpus XSS

**Files:**
- Modify/Create em `examples/shop/`: `src/main/java/shop/SecurityConfig.java`, `catalog/ReviewRepository.java`, `cart/Cart.java`, `order/OrderRepository.java`, `web/ReviewController.java`, `web/CartController.java`, `web/CheckoutController.java`, `view/Views.java` (+ carrinho/avaliações)
- Create: `src/main/suko/shop/{ReviewList,ReviewForm,CartPage,CartLine,CheckoutPage,CheckoutForm,ConfirmationPage}.sk`; atualizar `ProductPage.sk`, `Layout.sk` (contador do carrinho)
- Test: `src/test/java/shop/{ReviewsTest,CartAndCheckoutTest,SecurityHeadersTest,XssCorpusShopTest}.java`

**Interfaces:**
- Consumes: Task 12.
- Produces: rotas `POST /p/{id}/reviews`, `POST /cart/add`, `POST /cart/remove`, `GET /cart`, `GET|POST /checkout`, `GET /orders/{id}/confirmation`; `Cart` em sessão (`Map<Long,Integer>`, quantidade 1..20 por linha, no máximo 50 linhas); `SecurityConfig` com CSRF ativo, CSP/cabeçalhos e **nenhuma** rota autenticada.

**Regras de negócio e de segurança (cada uma tem teste):**
- Avaliação: `author` 1–80 chars, `body` 1–2000, `rating` 1–5, `website` opcional ≤ 200 e **só texto** (a aplicação não valida o esquema — quem o bloqueia é o Suko; o teste prova-o). Erros voltam como `Map<String,String> errors` (como o exemplo `Forms.sk` fazia) e re-renderizam a página com o que o utilizador escreveu, escapado.
- Carrinho: o preço **nunca** vem do cliente (campo `priceCents` forjado no POST é ignorado); quantidade fora de 1..20 → 400; produto inexistente → 404.
- Checkout: `name` 1–120, `email` 3–200 com `@`, `address` 1–400; o total é recalculado no servidor dentro de uma transação (`orders` + `order_line`), o stock desce, o carrinho esvazia, e a confirmação só abre para o id guardado **na sessão** (outro id → 404, nunca IDOR).
- Spring Security: CSRF com `CookieCsrfTokenRepository`? **Não** — manter o repositório de sessão por omissão e injetar `csrfName`/`csrfToken` no modelo dos formulários (`@ModelAttribute` via `CsrfToken`) para `<input type="hidden" name=${csrfName} value=${csrfToken}>`; cabeçalhos: `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`; `h2-console` desligada.

- [ ] **Step 1: Testes (falham)**

`ReviewsTest`:

```java
    @Test
    void hostileReviewIsStoredAndRenderedAsInertText() throws Exception {
        postReview(1, "<script>alert(1)</script>", "\"><img src=x onerror=alert(1)>", 5, "javascript:alert(document.cookie)")
            .andExpect(status().is3xxRedirection());
        Document d = Jsoup.parse(mvc.perform(get("/p/1")).andReturn().getResponse().getContentAsString());
        assertTrue(d.select("script").isEmpty());
        assertTrue(d.select("img[onerror]").isEmpty());
        assertTrue(d.text().contains("<script>alert(1)</script>"));      // visível como texto
        assertEquals("about:invalid#suko-blocked", d.select(".review a.website").attr("href"));   // M1 na prática
    }

    @Test
    void invalidReviewReRendersWithErrorsAndTheEscapedInput() throws Exception {
        String html = postReview(1, "", "x".repeat(2001), 9, "").andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Document d = Jsoup.parse(html);
        assertFalse(d.select(".error").isEmpty());
        assertFalse(html.contains("x".repeat(2001)) && !html.contains("&"), "o corpo longo não pode ser reescrito sem escape");
    }

    @Test
    void postWithoutCsrfTokenIsForbidden() throws Exception {
        mvc.perform(post("/p/1/reviews").param("author", "a").param("body", "b").param("rating", "5")).andExpect(status().isForbidden());
    }
```

`CartAndCheckoutTest`: adicionar → contador sobe; `priceCents=1` forjado ignorado (total = preço da base × qtd); quantidade 0, -1, 21, 99999999999 → 400; produto inexistente → 404; checkout inválido mostra erros e **não** cria encomenda; checkout válido cria encomenda, desce stock, esvazia carrinho e a confirmação com o id de **outra** sessão → 404; sem CSRF → 403.

`SecurityHeadersTest`: os quatro cabeçalhos acima presentes em `/` com os valores exatos; `/h2-console` → 404.

`XssCorpusShopTest`: lê `../../suko-core/src/test/resources/security/xss-corpus.txt` (decode igual ao da Task 8); para cada payload, usado como (a) `q` de `/search`, (b) `author`, `body` e `website` de uma avaliação, (c) `name`/`address` do checkout, renderiza a página seguinte e verifica com jsoup: nenhum `script`, nenhum atributo `on*`, nenhum `href`/`src`/`action` que comece por `javascript:`/`vbscript:`/`data:` (após remover TAB/LF/CR e espaços iniciais), e que o número de elementos `iframe` é 0.

Run: `scripts/verify-isolated.sh -p examples/shop test`
Expected: FAIL.

- [ ] **Step 2: Implementação**

Seguir as regras acima. Pontos de atenção:

- SQL sempre com parâmetros nomeados (`JdbcClient.sql(...).param(...)`); a transação do checkout é `@Transactional` num serviço, não no controlador.
- Os componentes Suko não usam `trustedUrl`/`trustedStyle` em lado nenhum (a loja prova que não é preciso); o `security-audit.json` da loja tem de sair **vazio** (`"entries": []`) — acrescentar uma verificação no teste (`Files.readString(build/suko/security-audit.json)`).
- `CartPage`/`CartLine`: formulários `<form method="post" action="/cart/remove">` com campo escondido `id` e o par CSRF.
- Nenhum `style=`, nenhum `on*`, nenhum `<script>` inline em nenhum `.sk` da loja (CSP estrita real).

Run: `scripts/verify-isolated.sh -p examples/shop test`
Expected: PASS (todos os testes da loja, incluindo o corpus).

- [ ] **Step 3: Arrancar a loja e ver com os próprios olhos**

Run (numa janela do controller, não num subagente): `scripts/verify-isolated.sh -p examples/shop bootRun` e abrir `http://localhost:8080/` — o controller confirma: home, produto, pesquisa, adicionar ao carrinho, checkout. Se não for possível arrancar no ambiente, registar o motivo no relatório; os testes de MockMvc continuam a ser a prova mínima.

- [ ] **Step 4: Commit**

```bash
git add examples/shop
git commit -m "feat(examples): loja Suko (2/2) — avaliações, carrinho, checkout, Spring Security e corpus XSS"
```

---

### Task 14: Substituir `examples/` pela loja e regenerar os goldens

**Files:**
- Delete: `examples/dashboard/`, `examples/forms/`, `examples/layout/`
- Move: `examples/invalid/Card.sk` → `suko-core/src/test/resources/fixtures/invalid/Card.sk`
- Modify: `suko-core/src/test/java/io/suko/lang/GoldenParityTest.java` (raízes), `suko-core/src/test/resources/golden/` (regenerado)
- Modify: `README.md`, `.github/**` se referenciarem `examples/*.sk`

**Interfaces:**
- Consumes: Tasks 12–13 (a loja tem de estar verde antes de apagar os exemplos antigos que ela leu como referência).
- Produces: `GoldenParityTest` com as raízes `components`, `website`, `shop` (`../examples/shop/src/main/suko`) e `invalid` (`src/test/resources/fixtures/invalid`).

- [ ] **Step 1: Mover e apagar**

```bash
mkdir -p suko-core/src/test/resources/fixtures/invalid
git mv examples/invalid/Card.sk suko-core/src/test/resources/fixtures/invalid/Card.sk
git rm -r examples/dashboard examples/forms examples/layout
git rm -r suko-core/src/test/resources/golden/examples
```

- [ ] **Step 2: `GoldenParityTest`**

Substituir as linhas do `@CsvSource`:

```java
    @CsvSource({
        "invalid, src/test/resources/fixtures/invalid",
        "components, ../suko-components/src/main/suko",
        "shop, ../examples/shop/src/main/suko",
        "website, ../suko-website/src/main/suko"
    })
```

Atualizar o comentário da classe (já não é "antes e depois do 13a": é o golden do output do compilador, regenerado na Task 4 e agora com a loja). Procurar outros testes ou documentos que apontem para `examples/` (`grep -rn "examples/" --include=*.java --include=*.md --include=*.kts --include=*.yml .` fora de `examples/shop` e de `.superpowers`) e atualizar.

- [ ] **Step 3: Regenerar os goldens novos (uma vez) e rever**

Run: `SUKO_PULL=suko-core/src/test/resources/golden scripts/verify-isolated.sh :suko-core:test --tests '*GoldenParityTest*' -Dsuko.updateGolden=true`
Rever `git diff --stat suko-core/src/test/resources/golden`: devem aparecer **só** as raízes novas (`golden/shop`, `golden/invalid`) e a remoção de `golden/examples`; `golden/components` e `golden/website` **não** podem mudar em relação à Task 4. Rever à mão o `.jte` de `golden/shop`: URLs dinâmicos em `SukoSafe.url`, nenhum `$unsafe`, nenhum `@` solto.

Run: `scripts/verify-isolated.sh :suko-core:test :suko-components:test` e `scripts/verify-isolated.sh -p examples/shop test`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add examples suko-core/src README.md
git commit -m "chore(examples): a loja substitui os .sk soltos; goldens regenerados (shop, invalid)"
```

---

### Task 15: Documentação, revisão final e PR

**Files:**
- Modify: `ARCHITECTURE.md` (item 14 → CONCLUÍDO; lacunas novas; secção de segurança), `README.md` (secção Security, loja, `suko.security`, `trusted*`), `suko-api/README.md` (`SecurityOptions`, `emitProject`, contextos), `suko-lsp/README.md` (INFO), `examples/shop/README.md`
- Create: `docs/security.md` (modelo de ameaças curto: o que o JTE faz, o que o Suko acrescenta, o que fica à aplicação — CSRF, autenticação, sanitização de HTML de utilizadores que partilhe a página com ilhas)

**Interfaces:** só documentação.

- [ ] **Step 1: `ARCHITECTURE.md`**

- Item 14: **CONCLUÍDO**, com spec e plano, e a lista dos códigos novos (`UNSAFE_SINK`, `RESERVED_NAME`, `UPPERCASE_NAME`, `TRUSTED_URL`, `TRUSTED_STYLE`, `CSP_INLINE`, `REGISTRY_*`).
- Registar como **lacunas conhecidas**: (a) componentes só recebem tipos de biblioteca (`String`, `List`, `Map`…) — a loja usa `List<Map<String,String>>` — até o item 12/11c; (b) `<!DOCTYPE html>` (o que a Task 12 apurou); (c) o `htmlPolicyClass` do JTE só é ativado com o plugin `gg.jte.gradle` (a loja usa compilação em runtime); (d) `trusted-keys.json` do `suko-cli` vazio até à primeira release (R6) e o `.sig` do registry oficial é um passo de release; (e) política `OwaspHtmlPolicy` no Maven é só aviso; (f) atributos de código/URL declarados por `Vocabulary` de extensões (a spec previa-os) não estão implementados — só por configuração (`codeAttributes`/`urlAttributes`); (g) o LSP usa as opções de segurança por omissão: `suko.security` do build não é lido pelo LSP (o `extensions.json` poderia transportá-las); (h) Playwright com CSP/Trusted Types fica para o pentest (R9); (i) Ed25519 no native-image da CLI por provar (Task 11), se não houve GraalVM.
- Atualizar a ordem: 13a ✔ → 14 ✔ → item 12 → 13b → pentest à loja → 11c → editores; a release continua bloqueada por 11b, 11c, item 12 (13a e 14 cumpridos).

- [ ] **Step 2: `docs/security.md` e READMEs**

`docs/security.md`: (1) O que o JTE faz (escape contextual) e as quatro limitações verificadas na 3.1.12; (2) o que o Suko acrescenta (M1–M7 em linguagem de utilizador: `suko { security { ... } }`, `trustedUrl`, `UNSAFE_SINK` com exemplos antes/depois); (3) o que **não** é do Suko (CSRF, autenticação, sanitização de HTML de terceiros — com a nota de que HTML de utilizadores na mesma página das ilhas da 13b deve ter `data-suko*` removido); (4) configuração de CSP recomendada (a da loja); (5) o registry: modelo de confiança (chave por registry, rotação — a chave nova entra num release da CLI antes de ser usada; revogação = release que a remove, CLIs antigas confiam até atualizarem —, anti-rollback, expiração, `--allow-unsigned` só local), como gerar a chave e assinar (`RegistryTool`; assinar na tag de release, fora do CI disparado por push, ou num environment protegido); (6) o contrato dos alvos: **todo alvo que aceite o vocabulário `html` tem de aplicar a verificação de URLs da M1** (documentado também no Javadoc de `Target` e no `suko-api/README.md`) e correr o corpus da Task 8.

- [ ] **Step 3: Verificação final no repositório inteiro**

Run: `scripts/verify-isolated.sh clean build` e `scripts/verify-isolated.sh -p examples/shop clean test`
Expected: BUILD SUCCESSFUL nos dois; `GoldenParityTest` PASS; `git grep -n "SukoSafe" -- suko-core/src/main` sem resultados (o core continua sem conhecer o alvo); `git diff main -- '*/src/test/**' | grep '^-.*assert'` sem asserções removidas.

- [ ] **Step 4: Revisão final**

Despachar o `architect` (revisão do ramo inteiro contra a spec e este plano) **e** o `security-specialist` (revisão de implementação: `SukoSafe`, `HtmlSecurityChecker`, assinatura do registry, `VerifiedIndex`, a loja). Corrigir o que for material numa ronda; os pendentes ficam registados em `ARCHITECTURE.md`.

- [ ] **Step 5: Commit, push e PR**

```bash
git add ARCHITECTURE.md README.md docs/security.md suko-api/README.md suko-lsp/README.md examples/shop/README.md
git commit -m "docs(14): segurança por omissão concluída; modelo de ameaças; lacunas e ordem atualizadas"
```

O push e a abertura do PR precisam do OK do utilizador (efeito fora do repositório local).

