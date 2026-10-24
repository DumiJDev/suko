# Subprojeto 13b — Reatividade em ilhas, plano 1 de 2: linguagem, API v2 e tradutor Java→JS

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** O Suko ganha a sintaxe dos componentes reativos (`reactive component`, `state`/`derived` com tipo ou `var`, `on:evento=${lambda}`, `bind:value|checked`, `prefixo:nome` em atributos), a API de extensões v2 que a representa, as verificações de linguagem e de segurança da spec e um tradutor puro do subconjunto Java → JS, testado contra o próprio Java a correr no Node. **Este plano não gera ilhas:** até ao plano 2 o alvo `jte` não declara a capacidade `reactive`, e todo o `reactive component` dá `REACTIVE_UNSUPPORTED` no build.

**Architecture:** O AST (`suko-api`) ganha o marcador `ComponentDecl.reactive()`, `Statement.StateDecl`/`DerivedDecl`, as diretivas `on:`/`bind:` como nó próprio (`Statement.Directive`, em `HtmlElement.directives()`, nunca em `attributes()`), as expressões `LambdaExpr`/`AssignExpr`/`UpdateExpr`/`NewExpr`, `Severity.HINT`, `Target.capabilities()` e o utilitário `ExprNames`; `ExtensionApi.VERSION` passa a 2. O core (`suko-core`) recebe a gramática nova, um construtor do AST que reporta erros de forma como `PARSE_ERROR` com posição real, as regras de linguagem independentes do alvo (`ReactiveChecker`: `REACTIVE_REQUIRED`, `REACTIVE_UNUSED`, `DERIVED_CYCLE`, `BIND_TARGET` de state, `LAMBDA_ACROSS_BOUNDARY`, `INVALID_DOCTYPE`) e o `CapabilityChecker` (`REACTIVE_UNSUPPORTED`). O `suko-jte` ganha o pacote `io.suko.jte.client` — tipos do cliente, o tradutor `JsTranslator`, o modelo `ClientModel` (o que corre no cliente num reactive component) e o recurso `prelude.js` (funções de apoio com a semântica de Java) — e o `ReactiveHtmlChecker` (`CLIENT_UNSUPPORTED`, `TYPE_NOT_INFERRED`, `CLIENT_UNSAFE_SINK`, `BIND_TARGET` de elemento/tipo, `PROP_*`); o `HtmlSecurityChecker` passa a recusar `trusted*` em atributos atualizados no cliente (Q6). O plano 2 consome `ClientModel.sites()`, o `JsTranslator` e o `prelude.js` para gerar o DOM, o `suko.js`, o serializador de props e o token de montagem.

**Tech Stack:** Java 21, Gradle (wrapper do repositório, só via `scripts/verify-isolated.sh`), ANTLR 4.13.1, JUnit 5.10.2, gg.jte 3.1.12 (só testes), `javax.tools` (testes diferenciais), Node ≥ 18 no `PATH` (testes; o ambiente de desenvolvimento tem a v24.15.0).

**Spec:** `docs/superpowers/specs/2026-10-04-suko-reatividade-ilhas.md` (aprovada; inclui as decisões Q1–Q6 de 2026-10-11). O plano **segue a spec**; onde a spec deixa espaço ou se contradiz, ver "Rulings do plano". Ler também `docs/superpowers/specs/2026-10-04-suko-seguranca-por-omissao.md`, `docs/security.md` e `ARCHITECTURE.md` (itens 13 e 14, "Pendentes do subprojeto 14").

**Revisão de segurança feita (2026-10-24):** a revisão do `security-specialist` sobre as alterações de 2026-10-11 da spec (Q4/Q5/Q6, um diagnóstico por sítio) está em `.superpowers/sdd/13b-plano1/security-review.md` (nenhum achado bloqueia). As correções estão aplicadas a este plano: S6 e S7 nas Tasks 2 e 4; S1, S2, S8, S9 e S10 nas Tasks 9 e 10 e nos rulings R10 e R26–R29; S3, S4 e S5 nos rulings R26–R27, na Task 10 e nos requisitos do plano 2 (a spec foi corrigida quanto ao S3). Os rulings R13 e R14 foram confirmados pelo utilizador (R13 = A, R14 = B).

## Global Constraints

- **Java 21** (`options.release.set(21)`, já global no `build.gradle.kts` da raiz). Packages existentes não mudam; classes novas do cliente em `io.suko.jte.client`.
- **`suko-api` só depende do JDK; `suko-jte` depende só de `suko-api`** (nunca do `suko-core`); o core continua sem JTE (`git grep -n "gg.jte\|io.suko.jte" -- suko-core/src/main` sem resultados).
- **Nenhuma dependência do Suko em runtime.** Nada deste plano entra no classpath da aplicação; o `prelude.js` é um recurso de compile-time que o plano 2 junta ao `suko.js` gerado.
- **Nenhuma dependência nova** em nenhum módulo (nem de teste). Node é uma ferramenta do ambiente, invocada por `ProcessBuilder` (`node --input-type=module`, lote único por teste).
- **Sem `node` no `PATH`, os testes do cliente FALHAM, não são saltados** (spec, "Testes"; a lacuna (i) do item 14 foi um teste saltado em silêncio). Nunca `Assumptions.assume*` nem `@Disabled` nesses testes. `SUKO_NODE` pode apontar para outro executável.
- **Códigos novos, com a severidade da spec:** `REACTIVE_REQUIRED` (ERROR), `REACTIVE_UNUSED` (**HINT**), `CLIENT_UNSUPPORTED` (ERROR), `PROP_NOT_SERIALIZABLE` (ERROR), `PROP_SENSITIVE` (WARNING), `PROP_MAP_KEYS` (INFO), `LAMBDA_ACROSS_BOUNDARY` (ERROR), `BIND_TARGET` (ERROR), `CLIENT_UNSAFE_SINK` (ERROR), `TYPE_NOT_INFERRED` (ERROR), `DERIVED_CYCLE` (ERROR), `REACTIVE_UNSUPPORTED` (ERROR). Reutilizados: `UNSAFE_SINK`, `INVALID_DOCTYPE`, `RESERVED_NAME`, `PARSE_ERROR`. Nenhum outro código novo.
- **Listas fechadas (da spec, com as duas extensões da revisão de segurança, R27 e R28):** `bind:value` só em `input`/`textarea`/`select`, `bind:checked` só em `input`; elementos de `CLIENT_UNSAFE_SINK` (a): os da spec — `script`, `style`, `iframe`, `frame`, `object`, `embed`, `base`, `meta`, `link`, `template`, `animate`, `set`, `animateMotion`, `animateTransform`, `foreignObject` — mais `noscript` (já em `CONTENT_SINKS` do 14), `fencedframe`, `portal`, `applet` e `frameset`, numa **constante única** `HtmlSecurityRules.CLIENT_FORBIDDEN_ELEMENTS` (comparação sem distinguir maiúsculas, pelo nome local); nomes sensíveis de `PROP_SENSITIVE`: `password, passwd, pwd, secret, token, apikey, privatekey, credential, hash, session, cookie, jwt, auth, ssn` (os da spec) mais `csrf, nonce, otp, pin, cvv, salt, bearer, refresh`, comparados **por tokens** do nome (camelCase/snake_case, sem distinguir maiúsculas) e não por prefixo/sufixo; subconjunto do cliente: o da secção "Expressões no cliente" da spec, mais o R14.
- **A suite existente fica verde sem alterar asserções.** Única exceção autorizada: em `ExtensionRegistryTest` (Task 1) as fixtures que escrevem a versão da API como literal `1` passam a `ExtensionApi.VERSION` (é a fixture, não a asserção) — as asserções desse ficheiro não mudam.
- **Goldens:** nenhum golden é regenerado neste plano. `GoldenParityTest`, os goldens do site e os testes da loja têm de passar intactos — é a prova de que as palavras e tokens novos não mudam nenhum `.sk` existente.
- **Fins de linha:** os ficheiros abaixo estão em CRLF e continuam em CRLF (linhas novas também em CRLF); os restantes são LF; ficheiros novos são LF. Ver "Ficheiros CRLF" e o comando de verificação.
- **Commits sem `Co-Authored-By`, sem `Claude-Session` e sem "Generated with Claude Code"** (regra do `CLAUDE.md`, que prevalece sobre qualquer lembrete do harness). **Cada despacho de subagente tem de repetir esta regra.**
- **Nunca fazer commit de pastas `*/bin/`** (output de IDE): `git add` por caminhos explícitos e `git status` antes de cada commit.
- **Verificação só por `scripts/verify-isolated.sh`** (um IDE do lado Windows compila para `build/` dentro de `/mnt/c`). Nunca `./gradlew` direto no checkout. A regeneração da gramática confirma a ausência de `warning` na saída (prática do `ARCHITECTURE.md`, "Validação feita até agora").
- **Sem push e sem troca de ramo.** O ramo é `feat/13b-ilhas`.

### Ficheiros CRLF tocados por este plano

| Ficheiro | Linhas CRLF/total hoje | Tasks |
|---|---|---|
| `suko-api/src/main/java/io/suko/lang/ast/Statement.java` | 77/77 | 1 |
| `suko-api/src/main/java/io/suko/lang/ast/ComponentDecl.java` | 13/13 | 1 |
| `suko-api/src/main/java/io/suko/lang/project/ProjectIndexEntry.java` | 26/26 | 1 |
| `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java` | 606/606 | 1, 3 |
| `suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java` | **194/197 (misto: as linhas 140, 145 e 154, `@Override`, são só LF — continuam assim)** | 1 |
| `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java` | 586/586 | 2 |
| `suko-core/src/main/java/io/suko/lang/TolerantParser.java` | 151/151 | 2 |
| `suko-core/src/main/java/io/suko/lang/JteCompiler.java` | 245/245 | 2, 3 |
| `suko-lsp/src/main/java/io/suko/lsp/AstQuery.java` | 170/170 | 1 |
| `suko-lsp/src/main/java/io/suko/lsp/DiagnosticsService.java` | 175/175 | 1 |
| `suko-lsp/src/main/java/io/suko/lsp/CompletionService.java` | 218/218 | 10 |

Verificação depois de editar (correr na raiz do repositório):

```bash
crlf_check() { for f in "$@"; do t=$(wc -l < "$f"); c=$(grep -c $'\r$' "$f"); echo "$c/$t $f"; done; }
crlf_check suko-api/src/main/java/io/suko/lang/ast/Statement.java   # etc.
```

Esperado: `N/N` em todos os ficheiros 100 % CRLF, e `ProjectIndex.java` com exatamente 3 linhas sem `\r` (`grep -n -v $'\r$' suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java` → as três `@Override`). Se uma edição deixou linhas LF num ficheiro 100 % CRLF, normalizar **só esse ficheiro** com `sed -i 's/\r*$/\r/' <ficheiro>` (nunca no `ProjectIndex.java`, onde se corrige à mão). `git diff --stat -- <f>` e `git diff --stat --ignore-cr-at-eol -- <f>` têm de dar o mesmo número de linhas alteradas.

**A Task 2 toca na gramática (`SukoLexer.g4`, `SukoParser.g4`) e em ficheiros Java CRLF (`SukoAstBuilder.java`, `TolerantParser.java`, `JteCompiler.java`) — é a única que junta as duas coisas; atenção redobrada aos fins de linha e à regeneração da gramática.**

## Rulings do plano (onde a spec deixa espaço ou se contradiz)

Cada ruling diz o custo se estiver errado. R13 e R14 foram confirmados pelo utilizador a 2026-10-17.

- **R1 — Este plano não materializa ilhas.** O alvo `jte` só declara a capacidade `reactive` no plano 2, na mesma task em que passa a gerar o JS; até lá todo o `reactive component` dá `REACTIVE_UNSUPPORTED` (o `main` nunca fica a emitir um `.jte` sem handlers). *Custo:* entre os dois planos a sintaxe existe mas não compila — é o objetivo.
- **R2 — `on:`/`bind:` são um nó próprio (`Statement.Directive` em `HtmlElement.directives()`), não um `Attribute` filtrado** (decisão (d) da API v2). Quem percorre `attributes()` (o `HtmlSecurityChecker`, o `JteEmitter`, checkers de terceiros) nunca os vê: falha fechada, sem filtro a lembrar em cada consumidor; o `JteEmitter` lança se encontrar diretivas, em vez de as largar em silêncio. *Custo:* os consumidores que precisam delas (o emissor do plano 2) leem uma lista a mais.
- **R3 — `reactive`, `state`, `derived` e `new` são palavras reservadas do lexer** (como `var`, `for`, `component`). Para não partir HTML existente, os segmentos de nomes de tag/atributo (`data-state`, `data-new`) e os nomes de membro depois de `.`/`?.` (`order.state()`) continuam a aceitá-las. *Custo:* um parâmetro, variável ou identificador chamado `state`/`derived`/`reactive` deixa de fazer parse (documentado em "Limitações conhecidas"; nenhum `.sk` do repositório o usa — verificado).
- **R4 — Tokens novos `++`, `--`, `+=`.** Em texto livre nada muda (o `textRun` aceita qualquer token e o texto é recuperado pela posição); numa expressão `a--b` passa a ser erro em vez de `a - -b` (como em Java).
- **R5 — Onde a gramática aceita lambdas:** só no valor sem aspas de um atributo (`attr=${lambda}`) e como argumento de uma chamada de componente. O construtor do AST aceita a lambda de atributo só em `on:`; atribuições, `++`/`--` e `+=` só existem como instruções do corpo de uma lambda (não são expressões). `on:` exige `${lambda}`, `bind:` exige `${expressão}`, ambos sem aspas. Uma lambda dentro de uma string (`"${() -> x}"`) é erro de sintaxe. *Custo:* nada de lambdas em `${...}` de texto ou em chamadas de métodos Java do servidor (fora de âmbito: a spec só as quer no cliente).
- **R6 — Erros de forma detetados pelo construtor do AST são `PARSE_ERROR`** com a posição real (`SukoAstBuilder.syntaxIssues()`): prefixo reservado com maiúsculas (`On:`, `ON:`, `Bind:`), espaços à volta de `:`, lambda fora de `on:`, `on:`/`bind:` com o valor errado, `state`/`derived` fora do topo de um `reactive component`, `new T(nome = ...)`. Só o caminho estrito (`JteCompiler`) os reporta; os caminhos silenciosos (`ProjectIndex`, `RegistryGenerator`) e o tolerante (LSP) ignoram-nos, como já ignoram os erros do ANTLR.
- **R7 — `for (var x : xs)` passa a fazer parse.** O exemplo da spec usa-o e hoje não faz parse (`var` é palavra reservada e `type` só aceita `Identifier`). Fica no AST como `Type("var")` e o emissor escreve `@for(var x : ...)`, que o JTE aceita.
- **R8 — `new T(args)` no servidor é Java tal como está** (o emissor escreve `new T(...)`, o javac/JTE validam, como qualquer chamada Java em `${...}`); no cliente só cria records conhecidos (R13).
- **R9 — Arrumação dos códigos por módulo.** Core (linguagem, todos os alvos): `REACTIVE_REQUIRED`, `REACTIVE_UNUSED`, `DERIVED_CYCLE`, `BIND_TARGET` quando o alvo não é um state simples, `LAMBDA_ACROSS_BOUNDARY`, `INVALID_DOCTYPE` de um reactive component, `REACTIVE_UNSUPPORTED`. `suko-jte` (o cliente é JS, logo o subconjunto é do alvo): `CLIENT_UNSUPPORTED`, `TYPE_NOT_INFERRED`, `CLIENT_UNSAFE_SINK`, `BIND_TARGET` de nome/elemento/tipo, `PROP_*`, `RESERVED_NAME` de `trusted*` no cliente. **Desvio parcial da spec** ("core: … `BIND_TARGET`"): os nomes de elementos HTML (`input`, `textarea`, `select`) ficam no `suko-jte`, pelo R10 do subprojeto 14 (o core continua sem regras de HTML). *Custo:* `BIND_TARGET` sai de dois sítios; o R10 garante um só por diretiva.
- **R10 — Um diagnóstico por sítio, só por pares declarados (revisão de segurança, S2).** O core corre antes das extensões e o `JteExtension` regista o `html-security` antes do `html-reactive`. O `ReactiveHtmlChecker` só omite um ERROR seu quando o span se sobrepõe ao de um diagnóstico **já presente no mesmo ficheiro cujo código pertence ao par declarado** (`SUPPRESSED_BY`): `CLIENT_UNSAFE_SINK` ← `UNSAFE_SINK`; `BIND_TARGET` ← `BIND_TARGET` (o do core ganha ao do `suko-jte`); `CLIENT_UNSUPPORTED` ← `RESERVED_NAME`, `UNSAFE_SINK`; `RESERVED_NAME` ← `RESERVED_NAME` (o problema do tradutor para `trusted*` e o do `html-security` descrevem a mesma chamada; sem este par saem dois); `TYPE_NOT_INFERRED` e `PROP_*` nunca são omitidos. Um ERROR cujo span envolve o sítio (`<MAIN>` com `UPPERCASE_NAME`, `<script>` com `UNSAFE_SINK`) **não** esconde os problemas dentro dele. `endIndex` do `SourceSpan` é inclusivo, por isso spans adjacentes não se sobrepõem. *Custo:* dois problemas distintos no mesmo elemento aparecem ambos (corrige-se um de cada vez); só o par declarado se funde num diagnóstico.
- **R11 — Saídas por componente (API v2 (b)): forma decidida, implementação no plano 2.** O módulo JS de cada componente é emitido pelo `Target.emit` (os nomes com hash do conteúdo obrigam a ter o JS antes de escrever o `.jte`): `Emitted` ganha `List<ProjectOutput> companions` (com construtor de 3 argumentos mantido) e `ProjectOutput.Kind` ganha `CLIENT_ASSET` (vai para `js.outputDir`, limpeza por manifesto). Fica para o plano 2 junto com o encaminhamento no core e as pastas dos plugins, como mudança da v2 no lugar (precedente: R1 do 14 — nada publicado). *Custo:* o plano 2 mexe outra vez na API v2 antes da release.
- **R12 — API v2 (a), (c) e (e).** (a) as opções `js { outputDir; basePath }` e a separação `generatedPackage`/opções de projeto ficam no plano 2 (só aí há consumidor); (c) a carga estruturada no diagnóstico fica **fora da 13b** — nenhum consumidor a pede, e com a Q6 `trusted*` só existe no servidor, onde o `SecurityAudit` já funciona; (e) sem mudança (item 12). O `CheckContext` não ganha os alvos ativos: o `REACTIVE_UNSUPPORTED` é do core, que já os tem.
- **R13 — Tipos Java desconhecidos (confirmado: opção A).** O compilador não conhece records nem enums de Java (item 12 adiado a 2026-10-06; a spec assumia-o feito, D8). O plano cria uma porta `JavaTypes` (só no `suko-jte`) com `JavaTypes.NONE` em produção: um tipo Java que não é da lista do subconjunto fica `Named` — pode ser passado e comparado com `null`, mas acessores, `new` e métodos dão `CLIENT_UNSUPPORTED`, e um parâmetro desse tipo lido no cliente dá `PROP_NOT_SERIALIZABLE` (falha fechada). Os testes exercitam records com uma `JavaTypes` em memória. *Custo:* o exemplo `TodoList` da spec (records) só compila quando houver uma fonte de tipos; **a origem dos tipos tem de ser decidida antes do plano 2** (pergunta 2).
- **R14 — `Map` de só leitura no subconjunto (confirmado: opção B):** `get(chave)`, `containsKey(chave)`, `size()`, `isEmpty()`, com chaves `String`. O `Map<String, …>` já é tipo serializável na spec, mas a lista fechada não tinha nenhum método de `Map` (um `Map` no cliente seria inútil), e a loja usa `List<Map<String,String>>` como view model (lacuna (a) do 14). *Custo se rejeitado:* apagar quatro entradas do `JsTranslator` e os seus testes.
- **R15 — `float`/`Float` ficam fora do cliente** (como `long`/`char`): a aritmética de 32 bits não tem equivalente em JS. Um parâmetro `float` lido no cliente dá `PROP_NOT_SERIALIZABLE` (a spec só excluía `long`/`char`). `byte`/`short` leem-se como `int` (Java promove-os em toda a aritmética).
- **R16 — Semântica do tradutor** (onde a spec não é explícita): `int` com aritmética de 32 bits como em Java (`(a + b) | 0`, `Math.imul`, divisão e resto por `$suko.idiv`/`imod` com `RangeError` em divisão por zero, como a `ArithmeticException`) — **elimina a "diferença assumida" do overflow** que a spec aceitava, sem custo; `?.` e `?:` só no servidor; `==`/`!=` entre referências (`String`, `Integer`, `List`, records) recusado salvo contra `null`; `List.contains` só em listas de `String`/`Integer`/`Boolean`; `List.remove` só com índice `int`; `+=` que estreitaria (`int += double`) recusado; o texto de um `double` segue o algoritmo do `Double.toString` do JDK (validado contra 300 000 valores); `toUpperCase`/`toLowerCase` sem locale (diferença documentada face ao locale por omissão da JVM do servidor).
- **R17 — Contrato do JS traduzido com o runtime do plano 2:** `$s` (state e derived, objeto sem protótipo), `$p` (props validadas, objeto sem protótipo), `$u("nome")` depois de cada mutação de um state, `$suko` (as funções exportadas por `prelude.js`). Variáveis locais (lambdas, `for`, `var`) com nome reservado ou global de JS ficam `$_nome` (um identificador Suko nunca contém `$`).
- **R18 — `PROP_*` em todos os parâmetros lidos pelo cliente de todos os reactive components** (qualquer um pode ser ponto de entrada de ilha: a partir de um `component` ou de Java, Q3). Parâmetros lidos só no render do servidor não são verificados (minimização da spec). Callbacks (`Consumer`/`Runnable`) nunca vão no JSON. Os parâmetros são constantes nesta vista ("raiz de ilha"); o modo "filho reativo" (parâmetros reativos, "reactive dentro de reactive") é do plano 2.
- **R19 — Lambdas e componentes simples.** Num `component`: lambda para um reactive component → `LAMBDA_ACROSS_BOUNDARY`; para outro `component` → `REACTIVE_REQUIRED`. Num reactive component: lambda para um `component` → `LAMBDA_ACROSS_BOUNDARY`. `REACTIVE_UNUSED` conta também as lambdas (um componente que só repassa callbacks não pode ser simples).
- **R20 — Alcance de `CLIENT_UNSAFE_SINK` (a):** elementos da lista criados no cliente (dentro de um bloco que depende de state) e atributos atualizados no cliente de elementos da lista. No render do servidor de um reactive component valem as regras do 14 sem mudança (é o mesmo HTML escapado pelo JTE e passado pelo `SukoSafe`). Isto lê a frase "em blocos que dependem de state **e** no render do servidor" à luz de "Este código cobre só o que o 14 não recusa".
- **R21 — `bind:value` só em state `String`, `int` ou `double`; `bind:checked` só em state `boolean`;** outro tipo dá `BIND_TARGET`.
- **R22 — O exemplo `<Stepper value=${count} onChange=${v -> count = v} />` da spec lê-se como a chamada Suko `Stepper(value = count, onChange = v -> count = v)`** (não há tags de componente em Suko; `<Stepper>` seria HTML com `UPPERCASE_NAME`).
- **R23 — `NodeRunner`:** `node` do `PATH` (ou `SUKO_NODE`), Node ≥ 18 (`Object.hasOwn`), o `prelude.js` importado por URL `data:`; um `node` que não arranca é `AssertionError` com instruções.
- **R24 — `state` só vê parâmetros e states anteriores; `derived` vê parâmetros, todos os states e os outros `derived` (grafo, ordem topológica).** Um `var` do servidor usado no cliente dá `CLIENT_UNSUPPORTED` com a sugestão de o declarar `derived`. Um `for` cujo corpo tem código do cliente que lê a variável do ciclo passa inteiro para o cliente.
- **R25 — `HINT`:** o LSP mostra-o como `Hint`; Gradle e Maven imprimem-no ao nível info com o rótulo `[HINT]` (o Maven já imprime `[<severidade>]`).
- **R26 — `style` reativo no cliente: o contrato fecha-se aqui (revisão de segurança, S3/S4/S5; é o que a spec deixava "para o plano").** (1) O porte de `SukoSafe.cssValue` devolve o valor **depois** de `String.valueOf(v).trim()` com a regra `<= U+0020` (nunca `String.prototype.trim()`), como o Java faz — não "o original", como dizia a spec (corrigida); `null` e um `Boxed` nulo dão `unset`, nunca o texto `null`; regex `^...$` ASCII, sem flags `i`/`u`/`m` e sem `\d`. (2) A escrita no DOM é **só** `style.setProperty(nomeLiteral, valor)` e `style.removeProperty(nomeLiteral)`: nome de propriedade literal fixado em compile-time, sem o 3.º argumento (`!important`), e **nunca** `cssText`, `style[prop] =`, `setAttribute("style", ...)` nem `setAttributeNS`. Razão: o valor passa pelo parser CSS como um valor único (um `;` não abre outra declaração) e não depende de `style-src 'unsafe-inline'`; `setAttribute("style")` falha em silêncio sob `style-src 'self'`. (3) O plano 2 prova em Playwright, sob `style-src 'self'`, que `setProperty` aplica e que `setAttribute('style', ...)` é bloqueado (não assume), e o varrimento por allowlist só aceita estas duas operações de estilo. (4) O teste diferencial do plano 2 inclui, no mínimo: `null`/`Boxed` nulo (→ `unset`), `double` via `$suko.dstr` (`1.0E10`, `1.0E-5`, `NaN`, `Infinity`, `-0.0`, `100.0`), `Integer.MIN_VALUE`, `boolean`, strings com `\u0000`/`\u0001`/U+00A0/U+FEFF/U+2028 nas pontas, `1e3`, `5 5`, `5;color:red`, `url(x)`, `expression(x)`, dígitos e letras de largura total, um surrogate isolado, e `bind:value` com os mesmos valores. *Custo:* um autor não pode usar `cssText` no cliente (não há razão para o fazer).
- **R27 — `CLIENT_FORBIDDEN_ELEMENTS` é uma constante única em `HtmlSecurityRules` e alarga a lista (a) da spec (revisão de segurança, S8).** `CONTENT_SINKS` (`script`, `style`, `noscript`, `foreignobject`) e `ANIMATION` (`animate`, `set`, `animatemotion`, `animatetransform`) passam de privados do `HtmlSecurityChecker` para públicos do `HtmlSecurityRules`, e a constante é a união dos dois com `iframe`, `frame`, `object`, `embed`, `base`, `meta`, `link`, `template` e os embebimentos que nem o 14 nem a spec cobrem: `fencedframe`, `portal`, `applet`, `frameset`. **Decidido aqui (o utilizador pode inverter):** entram todos — o cliente nunca precisa de criar nenhum, e uma cópia manual da lista desvia-se do 14 sem ninguém ver (o `noscript` já faltava na lista da spec). Um teste fixa `CONTENT_SINKS ∪ ANIMATION ⊆ CLIENT_FORBIDDEN_ELEMENTS`. O alinhamento do render do servidor do 14 com os quatro elementos novos (M2 do 14) é do 14 e não bloqueia a 13b. *Custo:* um elemento que um dia o cliente precise de criar sai da lista por decisão escrita.
- **R28 — `PROP_SENSITIVE` por tokens e `PROP_*` por campo (revisão de segurança, S9, S10).** (1) O nome divide-se em tokens (`authToken` → `auth`, `token`; `APIKey` → `api`, `key`; `_`/`-`/`$` separam) e compara-se cada token (e os pares de tokens seguidos, para `apikey`/`privatekey`), com um `s` final tolerado (`credentials`); `author`, `authority`, `pinned` e `spinner` deixam de casar. A lista ganha `csrf`, `nonce`, `otp`, `pin`, `cvv`, `salt`, `bearer`, `refresh`. Limites conhecidos, só WARNING: um nome todo em minúsculas e sem separador (`accesstoken`) é um único token e não casa; `commitHash` casa `hash` (a lista da spec tem `hash` sozinho). (2) R18 minimiza por parâmetro, não por campo: um `User user` lido só por `user.name()` serializa todos os campos (incluindo `passwordHash`). **Requisito do plano 2:** o serializador gerado emite só os campos que o `ClientModel` lê; enquanto não o fizer, um `PROP_SENSITIVE` num campo de record que o cliente não lê passa a ERROR. (3) Callbacks (`Consumer`/`Runnable`) de um parâmetro de raiz de ilha chamados a partir de `component` ou de Java ficam `undefined` no cliente: o plano 2 dá um erro de montagem claro em vez de um `TypeError`. (4) `docs/security.md` (Task 10) avisa que dados não confiáveis ligados a `position`/`display`/`visibility`/`pointer-events`/`opacity` deixam o utilizador escolher entre identificadores válidos (redressing de UI, como no servidor desde o 14).
- **R29 — `trusted*` no modo "filho reativo" (plano 2) é um gate escrito (revisão de segurança, S1).** Este plano trata os parâmetros como constantes (vista de raiz de ilha, R18), por isso `trustedUrl(param)` num atributo de um reactive component continua `TRUSTED_URL` (aceite, só no servidor). Quando o plano 2 juntar o modo filho, os parâmetros passam a reativos e um pai que muda o argumento faz o cliente reescrever o atributo sem passar pelo porte do `SukoSafe` — o que quebra a Q6 e deixa o `security-audit.json` incompleto. **Requisito do plano 2, na mesma task que junta o modo filho:** um `trusted*` cujo argumento lê um parâmetro de um reactive component que é chamado a partir de outro reactive component dá `RESERVED_NAME` (ou o atributo passa a ser tratado como atualizado no cliente); o teste `@Disabled("plano 2: ...")` da Task 9 é o gate — o plano 2 remove o `@Disabled` e fá-lo passar. (Única exceção à regra de nunca desativar testes: não é um teste do cliente em Node e descreve comportamento que ainda não existe.)

## Review Focus

1. **Projetos existentes sem mudança:** nenhum `.sk` do repositório (componentes, site, loja, fixtures) muda de saída ou de diagnósticos com as palavras e tokens novos (`--`, `++`, `+=`, `new`, `state` em `data-state`). Pinado por `GoldenParityTest`, pelos testes do site e da loja (Task 10) e por `textWithNewTokensRendersUnchanged` (Task 2).
2. **`on:`/`bind:` nunca são HTML:** não dão `UNSAFE_SINK`, nunca são emitidos; mas `onclick=${x}` (também num reactive component), `online:x=${v}`, `hx-on:click=${x}`, `x-on:click=${x}` continuam `UNSAFE_SINK`, e `On:click` é só `PARSE_ERROR`. Pinado por `PrefixedAttributeSecurityTest` (Task 4) e `ReactiveHtmlCheckerTest` (Task 9).
3. **Semântica de Java no JS traduzido** (divisão inteira e por zero, overflow, `null` em concatenação, `String` nula + número, texto de `double`, `trim`, índices fora de alcance): testes diferenciais executados no Node contra o Java compilado (Tasks 5–7), que falham sem Node.
4. **Um diagnóstico por sítio:** `UNSAFE_SINK` vs `CLIENT_UNSAFE_SINK`, `BIND_TARGET` do core vs do `suko-jte`, `RESERVED_NAME` vs `TRUSTED_*`/`CLIENT_UNSUPPORTED` (Task 9).
5. **Extensões de terceiros:** uma extensão compilada contra a API 1 dá `EXTENSION_API_MISMATCH` e não é registada (nunca rebenta o build); o que parte num `switch` exaustivo e em padrões de record está documentado no `suko-api/README.md` (Tasks 1 e 10).

## Agentes especialistas

| Task | Implementação | Revisão |
|---|---|---|
| 1 API v2 | `java-specialist` | revisão SDD normal |
| 2 Gramática e AST | `antlr4-specialist` (gramática) com o `java-specialist` no construtor do AST | `antlr4-specialist` + `security-specialist` (`prefixo:nome`, Q4) |
| 3 Regras de linguagem | `java-specialist` | revisão SDD normal |
| 4 Ramos `:` do `HtmlSecurityChecker` | `security-specialist` (testes) | `jte-specialist` (render com `OwaspHtmlPolicy`) |
| 5 Tipos do cliente e `prelude.js` | `java-specialist` | `security-specialist` |
| 6 Tradutor: expressões | `java-specialist` | `security-specialist` |
| 7 Tradutor: handlers | `java-specialist` | `security-specialist` |
| 8 `ClientModel` | `java-specialist` | revisão SDD normal |
| 9 `ReactiveHtmlChecker` e Q6 | `java-specialist` | `security-specialist` |
| 10 LSP, documentação, verificação final | `dx-specialist` (docs) | `architect` (revisão final do ramo) |

---

### Task 1: API de extensões v2 — AST reativo, `HINT`, capacidades do alvo, `VERSION = 2`

**Files:**
- Modify (CRLF): `suko-api/src/main/java/io/suko/lang/ast/ComponentDecl.java`, `suko-api/src/main/java/io/suko/lang/ast/Statement.java`, `suko-api/src/main/java/io/suko/lang/project/ProjectIndexEntry.java`
- Modify: `suko-api/src/main/java/io/suko/lang/ast/Expr.java`, `suko-api/src/main/java/io/suko/lang/diagnostic/SukoDiagnostic.java`, `suko-api/src/main/java/io/suko/ext/Target.java`, `suko-api/src/main/java/io/suko/ext/ExtensionApi.java`
- Create: `suko-api/src/main/java/io/suko/lang/ast/ExprNames.java`
- Modify (CRLF): `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java`, `suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java` (misto)
- Modify: `suko-jte/src/main/java/io/suko/lang/JteEmitter.java`, `suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java`
- Modify (CRLF): `suko-lsp/src/main/java/io/suko/lsp/AstQuery.java`, `suko-lsp/src/main/java/io/suko/lsp/DiagnosticsService.java`
- Modify: `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoCompileTask.java`, `suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoWatchTask.java`
- Modify (fixture autorizada): `suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java`
- Test (create): `suko-api/src/test/java/io/suko/ext/ApiV2Test.java`, `suko-api/src/test/java/io/suko/lang/ast/ExprNamesTest.java`, `suko-lsp/src/test/java/io/suko/lsp/SeverityMappingTest.java`

**Interfaces:**
- Consumes: o AST e a API v1 atuais.
- Produces (exatos; as Tasks seguintes e o plano 2 dependem deles):
  - `ComponentDecl(String name, List<String> typeParameters, List<Param> params, List<Statement> body, SourceSpan span, boolean isPublic, SourceSpan nameSpan, boolean reactive)`; construtores antigos de 6 e 7 argumentos mantidos (`reactive = false`).
  - `Statement.HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children, boolean selfClosing, SourceSpan span, List<Directive> directives)`; construtor antigo de 5 argumentos mantido (`directives = List.of()`).
  - `Statement.Directive(Directive.Kind kind, String name, Expr value, SourceSpan span, SourceSpan nameSpan)` com `enum Kind { ON, BIND }` (não é `Statement`).
  - `Statement.StateDecl(Optional<Type> type, String name, Expr value, SourceSpan span, SourceSpan nameSpan)` e `Statement.DerivedDecl(...)` com a mesma forma — subtipos selados novos (`type` vazio = `var`).
  - `Expr.LambdaExpr(List<String> params, List<Expr> body, boolean block, SourceSpan span)`, `Expr.AssignExpr(Expr target, String operator, Expr value, SourceSpan span)` (`"="`/`"+="`), `Expr.UpdateExpr(Expr target, String operator, boolean prefix, SourceSpan span)` (`"++"`/`"--"`), `Expr.NewExpr(String typeName, List<Expr> args, SourceSpan span)`.
  - `ExprNames.of(Expr) → Set<String>` (nomes lidos, sem os parâmetros das lambdas que os ligam, por ordem) e `ExprNames.isIdentifier(String) → boolean`.
  - `SukoDiagnostic.Severity { ERROR, WARNING, INFO, HINT }`.
  - `Target.CAPABILITY_REACTIVE = "reactive"` e `default Set<String> capabilities()` (vazio).
  - `ProjectIndexEntry(..., SourceSpan nameSpan, boolean reactive)`; construtor antigo de 7 argumentos mantido (`reactive = false`).
  - `ExtensionApi.VERSION == 2`.

- [ ] **Step 1: Testes da API (falham)**

`suko-api/src/test/java/io/suko/ext/ApiV2Test.java`:

```java
package io.suko.ext;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectIndexEntry;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** API de extensões v2 (13b): o que mudou e o que continua compatível na fonte. */
class ApiV2Test {

    static final SourceSpan S = SourceSpan.NONE;

    static Expr.PrimaryExpr p(String text) {
        return new Expr.PrimaryExpr(text, S);
    }

    @Test
    void versionIsTwo() {
        assertEquals(2, ExtensionApi.VERSION);
    }

    @Test
    void hintIsTheLastSeverity() {
        assertEquals(List.of(SukoDiagnostic.Severity.ERROR, SukoDiagnostic.Severity.WARNING,
            SukoDiagnostic.Severity.INFO, SukoDiagnostic.Severity.HINT), List.of(SukoDiagnostic.Severity.values()));
    }

    @Test
    void targetsHaveNoCapabilitiesByDefault() {
        Target target = new Target() {
            public String id() { return "t"; }
            public String componentType() { return "x.C"; }
            public Set<String> vocabularies() { return Set.of(); }
            public Emitted emit(ComponentDecl c, EmitContext ctx) { return new Emitted("x", "", List.of()); }
        };
        assertEquals(Set.of(), target.capabilities());
        assertEquals("reactive", Target.CAPABILITY_REACTIVE);
    }

    @Test
    void oldConstructorsStillWorkAndMeanNotReactive() {
        assertFalse(new ComponentDecl("A", List.of(), List.of(), List.of(), S, false).reactive());
        assertFalse(new ComponentDecl("A", List.of(), List.of(), List.of(), S, false, S).reactive());
        assertTrue(new Statement.HtmlElement("p", List.of(), List.of(), false, S).directives().isEmpty());
        assertFalse(new ProjectIndexEntry("a.B", "B", Path.of("B.sk"), true, List.of(), S, S).reactive());
    }

    @Test
    void newNodesPrettyPrintLikeTheSource() {
        Expr inc = new Expr.UpdateExpr(p("n"), "++", false, S);
        assertEquals("() -> n++", new Expr.LambdaExpr(List.of(), List.of(inc), false, S).pretty());
        Expr assign = new Expr.AssignExpr(p("d"), "=", p("e"), S);
        Expr add = new Expr.AssignExpr(p("n"), "+=", p("1"), S);
        assertEquals("e -> { d = e; n += 1; }", new Expr.LambdaExpr(List.of("e"), List.of(assign, add), true, S).pretty());
        assertEquals("--n", new Expr.UpdateExpr(p("n"), "--", true, S).pretty());
        assertEquals("new Todo(a, false)", new Expr.NewExpr("Todo", List.of(p("a"), p("false")), S).pretty());
    }

    @Test
    void directivesAreNotAttributes() {
        Statement.Directive on = new Statement.Directive(Statement.Directive.Kind.ON, "click",
            new Expr.LambdaExpr(List.of(), List.of(), true, S), S, S);
        Statement.HtmlElement button = new Statement.HtmlElement("button", List.of(), List.of(), false, S, List.of(on));
        assertTrue(button.attributes().isEmpty());
        assertEquals("click", button.directives().get(0).name());
    }
}
```

`suko-api/src/test/java/io/suko/lang/ast/ExprNamesTest.java`:

```java
package io.suko.lang.ast;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExprNamesTest {

    static final SourceSpan S = SourceSpan.NONE;

    static Expr.PrimaryExpr p(String text) {
        return new Expr.PrimaryExpr(text, S);
    }

    @Test
    void literalsAreNotNames() {
        for (String literal : new String[] {"42", "true", "false", "null"}) {
            assertEquals(Set.of(), ExprNames.of(p(literal)), literal);
        }
        assertTrue(ExprNames.isIdentifier("_x1"));
        assertFalse(ExprNames.isIdentifier("1x"));
        assertFalse(ExprNames.isIdentifier("null"));
    }

    @Test
    void namesOfOperatorsCallsAndStringParts() {
        Expr sum = new Expr.BinaryExpr(p("a"), "+", new Expr.CallExpr(new Expr.AccessExpr(p("items"), "size", S), List.of(), S), S);
        assertEquals(List.of("a", "items"), List.copyOf(ExprNames.of(sum)));
        Expr string = new Expr.StringLiteralExpr(List.of(new Expr.StringPart.Literal("x"),
            new Expr.StringPart.SimpleInterp("n"), new Expr.StringPart.Interp(p("m"))), S);
        assertEquals(Set.of("n", "m"), ExprNames.of(string));
    }

    @Test
    void memberNamesAreNotVariables() {
        assertEquals(Set.of("t"), ExprNames.of(new Expr.AccessExpr(p("t"), "title", S)));
        assertEquals(Set.of("d"), ExprNames.of(new Expr.NewExpr("Todo", List.of(p("d")), S)));
    }

    @Test
    void lambdaParametersAreBound() {
        Expr body = new Expr.AssignExpr(p("draft"), "=", new Expr.CallExpr(new Expr.AccessExpr(p("e"), "value", S), List.of(), S), S);
        assertEquals(Set.of("draft"), ExprNames.of(new Expr.LambdaExpr(List.of("e"), List.of(body), false, S)));
        assertEquals(Set.of("n"), ExprNames.of(new Expr.UpdateExpr(p("n"), "++", true, S)));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-api:test --tests '*ApiV2Test*' --tests '*ExprNamesTest*'`
Expected: FAIL (compilação: `ExprNames`, `Severity.HINT`, `capabilities()`, os construtores novos e os nós novos não existem).

- [ ] **Step 2: AST — `ComponentDecl`, `Statement`, `ProjectIndexEntry` (CRLF)**

`ComponentDecl.java` fica (conteúdo completo; CRLF):

```java
package io.suko.lang.ast;

import java.util.List;

/** {@code reactive} (13b): o componente foi declarado com {@code reactive component}. */
public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span, boolean isPublic,
                             SourceSpan nameSpan, boolean reactive) {
    /** Componente simples (antes da 13b e AST construído à mão). */
    public ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                         List<Statement> body, SourceSpan span, boolean isPublic,
                         SourceSpan nameSpan) {
        this(name, typeParameters, params, body, span, isPublic, nameSpan, false);
    }

    /** Sem posição própria do nome (AST construído à mão): usa o span da declaração. */
    public ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                         List<Statement> body, SourceSpan span, boolean isPublic) {
        this(name, typeParameters, params, body, span, isPublic, span, false);
    }
}
```

Em `Statement.java` (CRLF), três edições:

1. A declaração selada passa a:

```java
public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation,
        Statement.VarDecl, Statement.IfStmt, Statement.ForStmt, Statement.SwitchStmt, Statement.ComponentCallStmt,
        Statement.StateDecl, Statement.DerivedDecl {
```

2. O record `HtmlElement` passa a (o resto do ficheiro não muda):

```java
    /** {@code directives} (13b): os {@code on:}/{@code bind:} do elemento, que nunca são atributos HTML. */
    record HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                        boolean selfClosing, SourceSpan span, List<Directive> directives) implements Statement {
        /** Sem diretivas (antes da 13b e AST construído à mão). */
        public HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                           boolean selfClosing, SourceSpan span) {
            this(tagName, attributes, children, selfClosing, span, List.of());
        }
    }
```

3. Antes do `}` final do ficheiro:

```java

    /**
     * {@code on:evento=${lambda}} ({@link Kind#ON}; {@code value} é um {@link Expr.LambdaExpr}) ou
     * {@code bind:nome=${state}} ({@link Kind#BIND}). Sintaxe Suko, não HTML (13b, API v2 (d)): fica fora de
     * {@link HtmlElement#attributes()}, por isso nenhum checker de atributos a confunde com um {@code on*} e
     * nenhum alvo a escreve tal como está. {@code name} é o que vem depois de {@code :} ({@code click},
     * {@code value}); {@code nameSpan} cobre {@code on:click}.
     */
    record Directive(Kind kind, String name, Expr value, SourceSpan span, SourceSpan nameSpan) {
        public enum Kind { ON, BIND }
    }

    /** {@code state T nome = valor;} ou {@code state var nome = valor;} ({@code type} vazio = {@code var}). 13b. */
    record StateDecl(java.util.Optional<Type> type, String name, Expr value, SourceSpan span,
                     SourceSpan nameSpan) implements Statement {
    }

    /** {@code derived T nome = valor;}: recalculado quando um state/derived de que depende muda; não atribuível. 13b. */
    record DerivedDecl(java.util.Optional<Type> type, String name, Expr value, SourceSpan span,
                       SourceSpan nameSpan) implements Statement {
    }
```

`ProjectIndexEntry.java` fica (conteúdo completo; CRLF):

```java
package io.suko.lang.project;

import io.suko.lang.ast.SourceSpan;

import java.nio.file.Path;
import java.util.List;

/**
 * Assinatura indexada de um componente (Fase 1): o suficiente para resolver
 * e validar chamadas vindas de outros ficheiros. {@code declarationSpan} é o
 * da declaração inteira e {@code nameSpan} o do nome, ambos em
 * {@code sourceFile}. {@code reactive} (13b): declarado com {@code reactive component}.
 */
public record ProjectIndexEntry(
    String qualifiedName,
    String simpleName,
    Path sourceFile,
    boolean isPublic,
    List<ParamInfo> params,
    SourceSpan declarationSpan,
    SourceSpan nameSpan,
    boolean reactive
) {
    /** Componente simples (antes da 13b). */
    public ProjectIndexEntry(String qualifiedName, String simpleName, Path sourceFile, boolean isPublic,
                             List<ParamInfo> params, SourceSpan declarationSpan, SourceSpan nameSpan) {
        this(qualifiedName, simpleName, sourceFile, isPublic, params, declarationSpan, nameSpan, false);
    }

    public int paramCount() {
        return params.size();
    }
}
```

- [ ] **Step 3: AST — `Expr`, `ExprNames`, `HINT`, `Target`, `VERSION`**

Em `Expr.java`: a declaração selada passa a

```java
public sealed interface Expr permits
    Expr.PrimaryExpr, Expr.StringLiteralExpr, Expr.AccessExpr, Expr.CallExpr,
    Expr.NotExpr, Expr.UnaryMinusExpr, Expr.BinaryExpr, Expr.TernaryExpr, Expr.ParenExpr,
    Expr.SafeAccessExpr, Expr.ElvisExpr,
    Expr.LambdaExpr, Expr.AssignExpr, Expr.UpdateExpr, Expr.NewExpr {
```

no `switch` de `pretty()`, depois do caso `ElvisExpr`:

```java
            case LambdaExpr l -> (l.params().size() == 1 ? l.params().get(0) : "(" + String.join(", ", l.params()) + ")")
                + " -> " + (l.block()
                    ? "{ " + String.join(" ", l.body().stream().map(x -> x.pretty() + ";").toList()) + " }"
                    : l.body().get(0).pretty());
            case AssignExpr a -> a.target().pretty() + " " + a.operator() + " " + a.value().pretty();
            case UpdateExpr u -> u.prefix() ? u.operator() + u.target().pretty() : u.target().pretty() + u.operator();
            case NewExpr n -> "new " + n.typeName() + "(" + String.join(", ", n.args().stream().map(Expr::pretty).toList()) + ")";
```

e antes do `}` final:

```java

    /**
     * {@code () -> corpo}, {@code e -> corpo}, {@code (e) -> { a; b; }} (13b). Só como valor de {@code on:} ou
     * argumento de uma chamada de componente. {@code body} tem uma expressão por instrução; {@code block}
     * indica que foi escrita com chavetas.
     */
    record LambdaExpr(List<String> params, List<Expr> body, boolean block, SourceSpan span) implements Expr {
    }

    /** {@code alvo = valor} / {@code alvo += valor} — só como instrução do corpo de uma lambda (13b). */
    record AssignExpr(Expr target, String operator, Expr value, SourceSpan span) implements Expr {
    }

    /** {@code ++alvo}, {@code alvo--}, ... — só como instrução do corpo de uma lambda (13b). */
    record UpdateExpr(Expr target, String operator, boolean prefix, SourceSpan span) implements Expr {
    }

    /** {@code new Tipo(args)}: Java tal como está no servidor; no cliente só records (13b). */
    record NewExpr(String typeName, List<Expr> args, SourceSpan span) implements Expr {
    }
```

`suko-api/src/main/java/io/suko/lang/ast/ExprNames.java`:

```java
package io.suko.lang.ast;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Os nomes (variáveis) que uma expressão lê, por ordem, sem os parâmetros das lambdas que os ligam nem os
 * nomes de membro ({@code t.title} lê {@code t}). Partilhado pelo core (grafo de {@code derived},
 * {@code DERIVED_CYCLE}) e pelos alvos (o que depende de state), para os dois lerem o mesmo grafo (13b).
 */
public final class ExprNames {

    private ExprNames() {
    }

    public static Set<String> of(Expr expr) {
        Set<String> out = new LinkedHashSet<>();
        collect(expr, Set.of(), out);
        return out;
    }

    /** Identificador Suko ({@code [A-Za-z_][A-Za-z0-9_]*}) que não é um literal ({@code true}, {@code false}, {@code null}). */
    public static boolean isIdentifier(String text) {
        if (text.isEmpty() || text.equals("true") || text.equals("false") || text.equals("null")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
            if (!(letter || (i > 0 && c >= '0' && c <= '9'))) {
                return false;
            }
        }
        return true;
    }

    private static void collect(Expr e, Set<String> bound, Set<String> out) {
        switch (e) {
            case Expr.PrimaryExpr p -> {
                if (isIdentifier(p.text()) && !bound.contains(p.text())) {
                    out.add(p.text());
                }
            }
            case Expr.StringLiteralExpr s -> {
                for (Expr.StringPart part : s.parts()) {
                    switch (part) {
                        case Expr.StringPart.Literal l -> { }
                        case Expr.StringPart.Interp i -> collect(i.expr(), bound, out);
                        case Expr.StringPart.SimpleInterp si -> {
                            if (!bound.contains(si.identifier())) {
                                out.add(si.identifier());
                            }
                        }
                    }
                }
            }
            case Expr.AccessExpr a -> collect(a.target(), bound, out);
            case Expr.SafeAccessExpr a -> collect(a.target(), bound, out);
            case Expr.CallExpr c -> {
                collect(c.callee(), bound, out);
                c.args().forEach(x -> collect(x, bound, out));
            }
            case Expr.NotExpr n -> collect(n.operand(), bound, out);
            case Expr.UnaryMinusExpr u -> collect(u.operand(), bound, out);
            case Expr.BinaryExpr b -> {
                collect(b.left(), bound, out);
                collect(b.right(), bound, out);
            }
            case Expr.TernaryExpr t -> {
                collect(t.condition(), bound, out);
                collect(t.whenTrue(), bound, out);
                collect(t.whenFalse(), bound, out);
            }
            case Expr.ParenExpr p -> collect(p.inner(), bound, out);
            case Expr.ElvisExpr x -> {
                collect(x.left(), bound, out);
                collect(x.right(), bound, out);
            }
            case Expr.LambdaExpr l -> {
                Set<String> inner = new HashSet<>(bound);
                inner.addAll(l.params());
                l.body().forEach(x -> collect(x, inner, out));
            }
            case Expr.AssignExpr a -> {
                collect(a.target(), bound, out);
                collect(a.value(), bound, out);
            }
            case Expr.UpdateExpr u -> collect(u.target(), bound, out);
            case Expr.NewExpr n -> n.args().forEach(x -> collect(x, bound, out));
        }
    }
}
```

`SukoDiagnostic.java` — o enum passa a:

```java
    /** {@code HINT} (13b, D6): sugestão para o editor, nunca falha o build (ex.: {@code REACTIVE_UNUSED}). */
    public enum Severity {
        ERROR, WARNING, INFO, HINT
    }
```

`Target.java` — antes do método `emitProject`:

```java
    /** Capacidade: o alvo materializa componentes reativos (13b). Sem ela, um reactive component dá REACTIVE_UNSUPPORTED. */
    String CAPABILITY_REACTIVE = "reactive";

    /** Capacidades opcionais do alvo, lidas pelo core (ex.: {@link #CAPABILITY_REACTIVE}). Por omissão nenhuma. */
    default Set<String> capabilities() {
        return Set.of();
    }
```

`ExtensionApi.java` fica:

```java
package io.suko.ext;

/**
 * Versão major da API de extensões. O core só aceita extensões compiladas contra o mesmo major.
 *
 * <p>2 (13b): {@code Statement.StateDecl}/{@code DerivedDecl}, {@code Statement.Directive} em
 * {@code HtmlElement.directives()}, {@code Expr.LambdaExpr}/{@code AssignExpr}/{@code UpdateExpr}/{@code NewExpr},
 * {@code ComponentDecl.reactive()}, {@code ProjectIndexEntry.reactive()}, {@code Severity.HINT},
 * {@code Target.capabilities()} e {@code ExprNames}.
 */
public final class ExtensionApi {
    public static final int VERSION = 2;

    private ExtensionApi() {
    }
}
```

Run: `scripts/verify-isolated.sh :suko-api:test`
Expected: PASS (`ApiV2Test`, `ExprNamesTest`, `SecurityOptionsTest`). Os outros módulos ainda não compilam (switches exaustivos) — é o passo seguinte.

- [ ] **Step 4: Os `switch` exaustivos sobre `Statement`/`Expr`/`Severity`**

`SemanticChecker.java` (CRLF), em `checkSyntaxInStatement`, depois do caso `ComponentCallStmt` (e o comentário "EXAUSTIVO SOBRE AS 8 VARIANTES" passa a "10"):

```java
            case Statement.StateDecl state -> checkBareBraceInExpr(state.value(), paramNames, state.span());
            case Statement.DerivedDecl derived -> checkBareBraceInExpr(derived.value(), paramNames, derived.span());
```

`ProjectIndex.java` (CRLF misto — editar só estas linhas; as três `@Override` LF ficam como estão):

```java
                index.byQualifiedName.put(qualifiedName, new ProjectIndexEntry(
                    qualifiedName, component.name(), skFile, component.isPublic(),
                    component.params().stream().map(ParamInfo::of).toList(),
                    component.span(), component.nameSpan(), component.reactive()));
```

`JteEmitter.java` — em `emitStatement`, depois do caso `ComponentCallStmt`:

```java
            // 13b, plano 1: um reactive component nunca chega aqui (REACTIVE_UNSUPPORTED enquanto o alvo não
            // declarar a capacidade, REACTIVE_REQUIRED fora dele). Falhar alto, nunca emitir algo errado.
            case Statement.StateDecl state -> throw new IllegalStateException(
                "state '" + state.name() + "' não é emitido pelo alvo jte (13b, plano 2)");
            case Statement.DerivedDecl derived -> throw new IllegalStateException(
                "derived '" + derived.name() + "' não é emitido pelo alvo jte (13b, plano 2)");
```

no início de `emitHtmlElement`:

```java
        if (!element.directives().isEmpty()) {
            throw new IllegalStateException("on:/bind: em <" + element.tagName()
                + "> não são emitidos pelo alvo jte (13b, plano 2)");
        }
```

e em `emitExpr`, depois do caso `ElvisExpr`:

```java
            // R8: no servidor, `new` é Java tal como está (o javac/JTE validam o tipo).
            case Expr.NewExpr newExpr -> "new " + newExpr.typeName() + "(" + emitArgs(newExpr.args(), slotNames) + ")";
            case Expr.LambdaExpr lambda -> throw new IllegalStateException(
                "lambda não é emitida pelo alvo jte (13b, plano 2): " + lambda.pretty());
            case Expr.AssignExpr assign -> throw new IllegalStateException("atribuição fora de um handler: " + assign.pretty());
            case Expr.UpdateExpr update -> throw new IllegalStateException(
                "'" + update.operator() + "' fora de um handler: " + update.pretty());
```

`HtmlSecurityChecker.java` — em `walk`, o caso `HtmlElement` passa a

```java
                case Statement.HtmlElement e -> {
                    element(e, slots, ctx);
                    for (Statement.Directive d : e.directives()) {
                        scan(d.value(), ctx);
                    }
                    walk(e.children(), slots, ctx);
                }
```

e acrescentam-se (no mesmo `switch`)

```java
                case Statement.StateDecl st -> {
                    reserved(st.name(), st.nameSpan(), "state", ctx);
                    scan(st.value(), ctx);
                }
                case Statement.DerivedDecl d -> {
                    reserved(d.name(), d.nameSpan(), "derived", ctx);
                    scan(d.value(), ctx);
                }
```

em `scan`, depois do caso `ElvisExpr`:

```java
            case Expr.LambdaExpr l -> l.body().forEach(x -> scan(x, ctx));
            case Expr.AssignExpr a -> {
                scan(a.target(), ctx);
                scan(a.value(), ctx);
            }
            case Expr.UpdateExpr u -> scan(u.target(), ctx);
            case Expr.NewExpr n -> n.args().forEach(x -> scan(x, ctx));
```

e em `hasDynamicContent`, depois do caso `VarDecl`:

```java
                case Statement.StateDecl st -> { }
                case Statement.DerivedDecl d -> { }
```

`AstQuery.java` (CRLF) — em `statement`, o caso `HtmlElement` passa a

```java
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    expr(attribute.value(), out);
                }
                for (Statement.Directive directive : element.directives()) {
                    expr(directive.value(), out);
                }
                statements(element.children(), out);
            }
```

acrescenta-se

```java
            case Statement.StateDecl state -> expr(state.value(), out);
            case Statement.DerivedDecl derived -> expr(derived.value(), out);
```

e em `expr`, antes do caso `PrimaryExpr`:

```java
            case Expr.LambdaExpr lambda -> lambda.body().forEach(x -> expr(x, out));
            case Expr.AssignExpr assign -> {
                expr(assign.target(), out);
                expr(assign.value(), out);
            }
            case Expr.UpdateExpr update -> expr(update.target(), out);
            case Expr.NewExpr newExpr -> newExpr.args().forEach(x -> expr(x, out));
```

`DiagnosticsService.java` (CRLF) — o `switch` de `severity` ganha `case HINT -> DiagnosticSeverity.Hint;`.

`SukoCompileTask.java` e `SukoWatchTask.java` — o terceiro ciclo de `printDiagnostics` passa a (o rótulo de `INFO` continua a ser `[INFO]`):

```java
        for (SukoDiagnostic diag : diagnostics.getDiagnostics()) {
            if (diag.severity() == SukoDiagnostic.Severity.INFO || diag.severity() == SukoDiagnostic.Severity.HINT) {
                String location = diag.span() != null && !diag.span().isNone()
                    ? fileName + ":" + diag.span().startLine() + ":" + diag.span().startColumn()
                    : fileName;
                getLogger().info("[{}] {} - {}: {}", diag.severity(), location, diag.code(), diag.message());
            }
        }
```

(O `SukoCompileMojo` já imprime `"[" + diag.severity() + "] "` com `default -> getLog().info(line)`; não muda.)

- [ ] **Step 5: LSP — teste do `Hint`**

`suko-lsp/src/test/java/io/suko/lsp/SeverityMappingTest.java`:

```java
package io.suko.lsp;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SeverityMappingTest {

    private static DiagnosticSeverity lsp(SukoDiagnostic.Severity severity) {
        PositionMapper mapper = new PositionMapper("reactive component A() {}\n");
        SukoDiagnostic d = new SukoDiagnostic(severity, "m", "C", "A.sk", new SourceSpan(1, 0, 0, 7));
        return DiagnosticsService.toLsp(d, mapper).getSeverity();
    }

    @Test
    void everyCompilerSeverityHasAnLspSeverity() {
        assertEquals(DiagnosticSeverity.Error, lsp(SukoDiagnostic.Severity.ERROR));
        assertEquals(DiagnosticSeverity.Warning, lsp(SukoDiagnostic.Severity.WARNING));
        assertEquals(DiagnosticSeverity.Information, lsp(SukoDiagnostic.Severity.INFO));
        assertEquals(DiagnosticSeverity.Hint, lsp(SukoDiagnostic.Severity.HINT));
    }
}
```

- [ ] **Step 6: `ExtensionRegistryTest` — fixtures na versão atual e a v1 recusada**

As fixtures escreviam a versão "atual" como literal `1`; passam a `ExtensionApi.VERSION` (nenhuma asserção muda):

```bash
sed -i -E 's/new Ext\(("[a-z.]+"), 1, /new Ext(\1, ExtensionApi.VERSION, /; s/public int apiVersion\(\) \{ return 1; \}/public int apiVersion() { return ExtensionApi.VERSION; }/' \
  suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java
grep -n ', 1, List.of\|return 1;' suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java   # esperado: nada
grep -c 'ExtensionApi.VERSION' suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java     # esperado: 13 (5 de antes + 8)
```

(O teste `wrongApiVersionIsRejectedWithBothVersionsInTheMessage` usa `99` e não muda.) Acrescentar ao fim da classe:

```java
    @Test
    void anExtensionBuiltAgainstApiOneIsRejectedCleanly() {
        var registry = ExtensionRegistry.of(List.of(new Ext("v1.ext", 1, List.of(target("v1"), checker("v1-check")))));
        assertTrue(registry.target("v1").isEmpty());
        assertTrue(registry.checkers().isEmpty());
        SukoDiagnostic d = registry.loadDiagnostics().get(0);
        assertEquals("EXTENSION_API_MISMATCH", d.code());
        assertTrue(d.message().contains("v1.ext") && d.message().contains("API de extensões 1")
            && d.message().contains("versão 2"), d.message());
    }
```

- [ ] **Step 7: Verificar**

Run: `scripts/verify-isolated.sh :suko-api:test :suko-core:test :suko-jte:test :suko-lsp:test :suko-gradle-plugin:compileJava :suko-maven-plugin:compileJava :suko-test-ext:compileJava`
Expected: BUILD SUCCESSFUL; `SeverityMappingTest` e `anExtensionBuiltAgainstApiOneIsRejectedCleanly` passam; `GoldenParityTest` passa sem alterações.

Fins de linha: `crlf_check` (ver "Ficheiros CRLF") sobre `Statement.java`, `ComponentDecl.java`, `ProjectIndexEntry.java`, `SemanticChecker.java`, `AstQuery.java`, `DiagnosticsService.java` → `N/N`; `ProjectIndex.java` → `194/197`.

- [ ] **Step 8: Commit**

```bash
git status
git add suko-api/src suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java \
  suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java \
  suko-core/src/test/java/io/suko/lang/ext/ExtensionRegistryTest.java \
  suko-jte/src/main/java/io/suko/lang/JteEmitter.java suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java \
  suko-lsp/src/main/java/io/suko/lsp/AstQuery.java suko-lsp/src/main/java/io/suko/lsp/DiagnosticsService.java \
  suko-lsp/src/test/java/io/suko/lsp/SeverityMappingTest.java \
  suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoCompileTask.java \
  suko-gradle-plugin/src/main/java/io/suko/lang/gradle/SukoWatchTask.java
git commit -m "feat(13b): API de extensões v2 — AST reativo, diretivas on:/bind:, HINT e capacidades do alvo"
```

---

### Task 2: Gramática e construtor do AST — `reactive`, `state`/`derived`, lambdas, `new`, `prefixo:nome` (toca gramática **e** Java CRLF)

**Pré-condição:** a revisão do `security-specialist` sobre as alterações de 2026-10-11 da spec (Q4/Q5/Q6, um diagnóstico por sítio) está feita; se mudar alguma regra desta task, aplicar antes de executar.

**Files:**
- Modify: `suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4`, `suko-core/src/main/antlr/io/suko/lang/SukoParser.g4`
- Modify (CRLF): `suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java`, `suko-core/src/main/java/io/suko/lang/JteCompiler.java`, `suko-core/src/main/java/io/suko/lang/TolerantParser.java`
- Test (create): `suko-core/src/test/java/io/suko/lang/SukoReactiveSyntaxTest.java`

**Interfaces:**
- Consumes: os nós e construtores da Task 1.
- Produces:
  - Tokens `REACTIVE`, `STATE`, `DERIVED`, `NEW`, `INC` (`++`), `DEC` (`--`), `PLUSEQ` (`+=`); regras `reactiveDecl`, `attrName`, `htmlNamePart`, `memberName`, `lambdaInterpolation`, `lambda`, `lambdaParams`, `lambdaBody`, `clientStatement` (alternativas `AssignStatement`, `PrefixUpdateStatement`, `PostfixUpdateStatement`, `ExpressionStatement`), `componentArgList`, `componentArg`, alternativa `NewExpr` de `expression`.
  - `SukoAstBuilder.SyntaxIssue(String message, SourceSpan span)` e `List<SyntaxIssue> SukoAstBuilder.syntaxIssues()`.
  - `JteCompiler`: cada `SyntaxIssue` vira um `PARSE_ERROR` (ERROR) com o span real, e o ficheiro não segue para o verificador.
  - O AST produzido: `ComponentDecl.reactive()`, `StateDecl`/`DerivedDecl` na posição em que aparecem, `HtmlElement.directives()` com os `on:`/`bind:` (nunca em `attributes()`), atributos `prefixo:nome` com o nome completo (`xlink:href`), `ForStmt.itemType()` = `Type("var")` em `for (var x : xs)`, lambdas só em `Directive.value()` e em `Statement.Arg.value()`.

- [ ] **Step 1: Testes de sintaxe (falham)**

`suko-core/src/test/java/io/suko/lang/SukoReactiveSyntaxTest.java`:

```java
package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.SukoSources;
import io.suko.lang.support.JteRenderSupport;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Sintaxe da 13b: reactive component, state/derived, on:/bind:, lambdas, new, prefixo:nome, for (var ...). */
class SukoReactiveSyntaxTest {

    /** Parse estrito sem verificador: falha se o ANTLR ou o construtor do AST encontrarem erros. */
    static SukoFile parse(String source) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(source))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(source);
        SukoFile file = builder.build(parser.compilationUnit());
        assertEquals(0, parser.getNumberOfSyntaxErrors(), "erro de sintaxe do ANTLR em: " + source);
        assertEquals(List.of(), builder.syntaxIssues(), "problemas do construtor do AST em: " + source);
        return file;
    }

    static List<SukoDiagnostic> diagnostics(String source) {
        return new JteCompiler("T.sk", source).compile().diagnostics().getDiagnostics();
    }

    static Set<String> codes(String source) {
        return diagnostics(source).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    @Test
    void reactiveComponentWithStateDerivedAndDirectives() {
        ComponentDecl c = parse("""
            reactive component Counter(int start) {
              state var count = start;
              state String label = "Total";
              derived int twice = count * 2;
              <button on:click=${() -> count++} class="b">${label}</button>
              <input bind:value=${label} />
            }
            """).components().get(0);
        assertTrue(c.reactive());

        Statement.StateDecl count = assertInstanceOf(Statement.StateDecl.class, c.body().get(0));
        assertEquals("count", count.name());
        assertTrue(count.type().isEmpty(), "state var: tipo por inferir");
        Statement.StateDecl label = assertInstanceOf(Statement.StateDecl.class, c.body().get(1));
        assertEquals("String", label.type().orElseThrow().name());
        Statement.DerivedDecl twice = assertInstanceOf(Statement.DerivedDecl.class, c.body().get(2));
        assertEquals("int", twice.type().orElseThrow().name());

        Statement.HtmlElement button = assertInstanceOf(Statement.HtmlElement.class, c.body().get(3));
        assertEquals(List.of("class"), button.attributes().stream().map(Statement.Attribute::name).toList(),
            "on:click não é um atributo HTML");
        Statement.Directive on = button.directives().get(0);
        assertEquals(Statement.Directive.Kind.ON, on.kind());
        assertEquals("click", on.name());
        Expr.LambdaExpr handler = assertInstanceOf(Expr.LambdaExpr.class, on.value());
        assertEquals(List.of(), handler.params());
        Expr.UpdateExpr inc = assertInstanceOf(Expr.UpdateExpr.class, handler.body().get(0));
        assertEquals("++", inc.operator());
        assertFalse(inc.prefix());

        Statement.HtmlElement input = assertInstanceOf(Statement.HtmlElement.class, c.body().get(4));
        assertTrue(input.attributes().isEmpty());
        Statement.Directive bind = input.directives().get(0);
        assertEquals(Statement.Directive.Kind.BIND, bind.kind());
        assertEquals("value", bind.name());
        assertEquals("label", assertInstanceOf(Expr.PrimaryExpr.class, bind.value()).text());
    }

    @Test
    void lambdaForms() {
        ComponentDecl c = parse("""
            reactive component R() {
              state int a = 0;
              state int b = 0;
              state int n = 0;
              state String draft = "";
              <i on:input=${e -> draft = e.value()} on:keyup=${(e) -> { a = 1; b += 2; ++n; n--; }} on:blur=${() -> { }}></i>
            }
            """).components().get(0);
        List<Statement.Directive> ds = ((Statement.HtmlElement) c.body().get(4)).directives();

        Expr.LambdaExpr first = (Expr.LambdaExpr) ds.get(0).value();
        assertEquals(List.of("e"), first.params());
        assertFalse(first.block());
        Expr.AssignExpr set = assertInstanceOf(Expr.AssignExpr.class, first.body().get(0));
        assertEquals("=", set.operator());
        assertInstanceOf(Expr.CallExpr.class, set.value());

        Expr.LambdaExpr second = (Expr.LambdaExpr) ds.get(1).value();
        assertTrue(second.block());
        assertEquals(List.of("=", "+="), second.body().subList(0, 2).stream()
            .map(x -> ((Expr.AssignExpr) x).operator()).toList());
        assertTrue(((Expr.UpdateExpr) second.body().get(2)).prefix());
        assertEquals("--", ((Expr.UpdateExpr) second.body().get(3)).operator());

        Expr.LambdaExpr third = (Expr.LambdaExpr) ds.get(2).value();
        assertTrue(third.block());
        assertTrue(third.body().isEmpty());
    }

    @Test
    void lambdaAsComponentArgument() {
        SukoFile file = parse("""
            reactive component Stepper(int value, Consumer<Integer> onChange) {
              <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
            }

            reactive component C() {
              state int n = 0;
              Stepper(value = n, onChange = v -> n = v)
              Stepper(n, v -> n = v)
            }
            """);
        List<Statement> body = file.components().get(1).body();
        Statement.ComponentCallStmt named = (Statement.ComponentCallStmt) body.get(1);
        assertEquals("onChange", named.args().get(1).name().orElseThrow());
        Expr.LambdaExpr lambda = assertInstanceOf(Expr.LambdaExpr.class, named.args().get(1).value());
        assertEquals(List.of("v"), lambda.params());
        assertInstanceOf(Expr.AssignExpr.class, lambda.body().get(0));
        Statement.ComponentCallStmt positional = (Statement.ComponentCallStmt) body.get(2);
        assertTrue(positional.args().get(1).name().isEmpty());
        assertInstanceOf(Expr.LambdaExpr.class, positional.args().get(1).value());
    }

    @Test
    void newExpressionAndKeywordsAsMemberAndAttributeNames() {
        ComponentDecl c = parse("""
            component A(Order order) {
              <p data-state="open" data-new="1">${order.state()}</p>
              <p>${new String("ab").length()}</p>
            }
            """).components().get(0);
        Statement.HtmlElement p = (Statement.HtmlElement) c.body().get(0);
        assertEquals(List.of("data-state", "data-new"), p.attributes().stream().map(Statement.Attribute::name).toList());
        Expr.CallExpr call = (Expr.CallExpr) ((Statement.Interpolation) p.children().get(0)).expr();
        assertEquals("state", ((Expr.AccessExpr) call.callee()).memberName());
        Expr.CallExpr length = (Expr.CallExpr) ((Statement.Interpolation)
            ((Statement.HtmlElement) c.body().get(1)).children().get(0)).expr();
        Expr.NewExpr created = assertInstanceOf(Expr.NewExpr.class, ((Expr.AccessExpr) length.callee()).target());
        assertEquals("String", created.typeName());
        assertEquals(1, created.args().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <svg><use xlink:href=\"#a\"/></svg> }",
        "component A() { <a hx-on:click=\"go()\">a</a> }",
        "component A() { <a x-on:click=\"open = false\">a</a> }",
        "component A(String u) { <svg><use xlink:href=${u}/></svg> }",
    })
    void prefixedAttributeNamesParse(String source) {
        Statement.Attribute a = firstAttribute(parse(source).components().get(0).body());
        assertNotNull(a, source);
        assertTrue(a.name().contains(":"), a.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reactive component A() { state int n = 0; <b On:click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b ON:click=${() -> n++}>x</b> }",
        "reactive component A() { state String s = \"\"; <input Bind:value=${s}/> }",
        "reactive component A() { state int n = 0; <b on : click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b on:=${() -> n++}>x</b> }",
        "component A() { <b :click=\"x\">x</b> }",
        "component A() { <b a:b:c=\"x\">x</b> }",
        "component A(String x) { <b on:${x}=\"y\">x</b> }",
        "component A() { <b @click=\"x\">x</b> }",
        "reactive component A() { state int n = 0; <b onclick=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b x-on:click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b on:click=\"n++\">x</b> }",
        "reactive component A() { state int n = 0; <b on:click=${n}>x</b> }",
        "reactive component A() { state String s = \"\"; <input bind:value=${() -> s}/> }",
        "reactive component A() { state String s = \"\"; <input bind:value=\"s\"/> }",
        "reactive component A() { <p>x</p> state int n = 0; }",
        "reactive component A() { if (true) { state int n = 0; } }",
        "component A() { <p>${new String(value = \"x\")}</p> }",
        "component A(String s) { <p title=\"${() -> s}\">x</p> }",
    })
    void malformedReactiveSyntaxIsOnlyAParseError(String source) {
        Set<String> codes = codes(source);
        assertEquals(Set.of("PARSE_ERROR"), codes, source + " -> " + diagnostics(source));
    }

    @Test
    void uppercaseReservedPrefixPointsAtTheName() {
        String source = "reactive component A() { state int n = 0; <b On:click=${() -> n++}>x</b> }";
        SukoDiagnostic d = diagnostics(source).get(0);
        assertEquals(1, d.span().startLine());
        assertEquals(source.indexOf("On:click"), d.span().startColumn());
        assertTrue(d.message().contains("on:click"), d.message());
    }

    @Test
    void stateInASimpleComponentIsNotASyntaxError() {
        assertFalse(codes("component A() { state int n = 0; <p>x</p> }").contains("PARSE_ERROR"));
    }

    @Test
    void forVarParsesAndRenders() throws Exception {
        String source = "component A(List<String> xs) { <ul>for (var x : xs) { <li>${x}</li> }</ul> }";
        Statement.ForStmt loop = (Statement.ForStmt) firstElement(parse(source).components().get(0).body()).children().get(0);
        assertEquals("var", loop.itemType().name());
        assertEquals("<ul><li>a</li><li>b</li></ul>",
            JteRenderSupport.render(source, "A", Map.of("xs", List.of("a", "b"))).replaceAll("\\s+", ""));
    }

    @Test
    void newIsJavaOnTheServer() throws Exception {
        String source = "component A() { <p>${new String(\"ab\").length()}</p> }";
        assertEquals("<p>2</p>", JteRenderSupport.render(source, "A", Map.of()).replaceAll("\\s+", ""));
    }

    @Test
    void textWithNewTokensRendersUnchanged() throws Exception {
        String source = "component A() { <pre>suko init --yes; i++; a += b; x = new Y(z)</pre> }";
        assertEquals("<pre>suko init --yes; i++; a += b; x = new Y(z)</pre>",
            JteRenderSupport.render(source, "A", Map.of()).trim());
    }

    @Test
    void tolerantParserRecoversAReactiveComponentAfterABrokenOne() {
        SukoFile file = TolerantParser.parse("""
            component Broken() {
              <p>${t.</p>
            }

            reactive component Counter(int start) {
              state int n = 0;
              <b on:click=${() -> n++}>${n}</b>
            }
            """);
        ComponentDecl counter = file.components().stream().filter(c -> c.name().equals("Counter")).findFirst().orElseThrow();
        assertTrue(counter.reactive());
        assertInstanceOf(Statement.StateDecl.class, counter.body().get(0));
    }

    @Test
    void projectIndexRemembersReactiveComponents() {
        ProjectIndex index = ProjectIndex.build(SukoSources.of(Path.of("/virtual"), Map.of(
            Path.of("ui/C.sk"), "package ui;\n\npublic reactive component C() {\n  state int n = 0;\n  <b>${n}</b>\n}\n",
            Path.of("ui/S.sk"), "package ui;\n\npublic component S() {\n  <b>s</b>\n}\n")));
        ProjectIndexEntry c = index.resolveQualified("ui.C").orElseThrow();
        assertTrue(c.reactive());
        assertFalse(index.resolveQualified("ui.S").orElseThrow().reactive());
    }

    private static Statement.Attribute firstAttribute(List<Statement> body) {
        for (Statement s : body) {
            if (s instanceof Statement.HtmlElement e) {
                if (!e.attributes().isEmpty()) {
                    return e.attributes().get(0);
                }
                Statement.Attribute inner = firstAttribute(e.children());
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static Statement.HtmlElement firstElement(List<Statement> body) {
        return body.stream().filter(Statement.HtmlElement.class::isInstance).map(Statement.HtmlElement.class::cast)
            .findFirst().orElseThrow();
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.SukoReactiveSyntaxTest'`
Expected: FAIL (compilação: `syntaxIssues()` não existe; depois, erros de parse).

- [ ] **Step 2: Lexer**

Em `SukoLexer.g4`, depois de `COMPONENT : 'component';`:

```antlr
// 13b: palavras reservadas como `var` (R3 do plano). Continuam a valer como segmento de um nome de
// tag/atributo (`data-state`) e como nome de membro (`order.state()`): ver `htmlNamePart`/`memberName`.
REACTIVE  : 'reactive';
STATE     : 'state';
DERIVED   : 'derived';
NEW       : 'new';
```

e, na pontuação, antes de `PLUS : '+';`:

```antlr
// 13b: instruções dos handlers (`count++`, `n += 1`). Maximal-munch: `--` ganha a `-`, e `<!--` continua a ser
// INVALID_DECL (mais comprido). Em texto livre nada muda: o textRun aceita qualquer token e o texto é
// recuperado pela posição no fonte.
INC       : '++';
DEC       : '--';
PLUSEQ    : '+=';
```

- [ ] **Step 3: Parser**

Em `SukoParser.g4`:

1. `componentDecl` passa a:

```antlr
componentDecl
    : PUBLIC? REACTIVE? COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN componentBody
    ;
```

2. `templateStatement` ganha `reactiveDecl` como **primeira** alternativa (antes de `textRun`, para `state` em prosa continuar a ser texto quando não forma uma declaração):

```antlr
templateStatement
    : reactiveDecl
    | varDecl
    | ifStmt
    | forStmt
    | switchStmt
    | componentCall
    | htmlElement
    | interpolation
    | textRun
    ;

// 13b: `state T x = e;`, `state var x = e;`, `derived ...`. Aceite em qualquer posição de instrução para o
// erro ser claro: o construtor do AST recusa-a fora do topo de um reactive component (PARSE_ERROR) e o
// verificador dá REACTIVE_REQUIRED num component simples.
reactiveDecl
    : kind=(STATE | DERIVED) (VAR | type) Identifier EQ expression SEMI
    ;
```

3. `forStmt` aceita `var` (R7):

```antlr
forStmt
    : FOR LPAREN (VAR | type) Identifier COLON expression RPAREN templateBlock
    ;
```

4. `componentCall` usa argumentos próprios (aceitam lambdas; o `argList` das chamadas de métodos Java não muda):

```antlr
componentCall
    : qualifiedName typeArguments? LPAREN componentArgList? RPAREN slotBlock?
    ;

componentArgList
    : componentArg (COMMA componentArg)*
    ;

componentArg
    : (Identifier EQ)? (lambda | expression)
    ;
```

5. Nomes HTML e atributos (os nomes de tag continuam `htmlName`, sem `:`):

```antlr
htmlName
    : htmlNamePart (MINUS htmlNamePart)*
    ;

// As palavras reservadas da 13b continuam a valer dentro de nomes HTML (`data-state`, `data-new`).
htmlNamePart
    : Identifier | REACTIVE | STATE | DERIVED | NEW
    ;

// 13b (Q4): `prefixo:nome` só em nomes de atributo. Os três pedaços têm de estar colados (o espaço é
// `skip`, por isso quem o garante é o construtor do AST, pelas posições). `on:`/`bind:` viram
// Statement.Directive; qualquer outro prefixo (`xlink:href`, `hx-on:click`) é um atributo HTML normal.
attrName
    : htmlName (COLON htmlName)?
    ;

attribute
    : attrName EQ stringLiteral
    | attrName EQ interpolation
    | attrName EQ lambdaInterpolation
    | attrName
    ;

lambdaInterpolation
    : EXPR_INTERP_START lambda RBRACE
    ;
```

6. Lambdas e instruções do cliente (depois de `interpolation`):

```antlr
// 13b: lambdas só como valor de atributo (`on:click=${() -> ...}`) e argumento de componente. As
// atribuições e os `++`/`--`/`+=` só existem como instruções do corpo (não são expressões).
lambda
    : lambdaParams ARROW lambdaBody
    ;

lambdaParams
    : LPAREN RPAREN
    | LPAREN Identifier RPAREN
    | Identifier
    ;

lambdaBody
    : LBRACE (clientStatement SEMI)* RBRACE
    | clientStatement
    ;

clientStatement
    : expression op=(EQ | PLUSEQ) expression         # AssignStatement
    | op=(INC | DEC) expression                      # PrefixUpdateStatement
    | expression op=(INC | DEC)                      # PostfixUpdateStatement
    | expression                                     # ExpressionStatement
    ;
```

7. Em `expression`: `NewExpr` logo a seguir a `PrimaryExpr`, e os acessos usam `memberName`:

```antlr
expression
    : primary                                                # PrimaryExpr
    | NEW Identifier LPAREN argList? RPAREN                   # NewExpr
    | expression QDOT memberName                             # SafeAccessExpr
    | expression DOT memberName                              # AccessExpr
    | expression LPAREN argList? RPAREN                       # CallExpr
    | NOT expression                                          # NotExpr
    | MINUS expression                                        # UnaryMinusExpr
    | expression op=(STAR|SLASH|PERCENT) expression           # MulExpr
    | expression op=(PLUS|MINUS) expression                   # AddExpr
    | expression op=(LT|LE|GT|GE) expression                  # RelExpr
    | expression op=(EQEQ|NEQ) expression                     # EqExpr
    | expression AND expression                               # AndExpr
    | expression OR expression                                # OrExpr
    | expression QCOLON expression                            # ElvisExpr
    | expression QUESTION expression COLON expression         # TernaryExpr
    | LPAREN expression RPAREN                                 # ParenExpr
    ;

// Um record Java pode ter um componente chamado `state` (`order.state()`): as palavras da 13b valem como membro.
memberName
    : Identifier | REACTIVE | STATE | DERIVED
    ;
```

Run: `scripts/verify-isolated.sh :suko-core:generateSukoLexer :suko-core:generateSukoParser --rerun-tasks 2>&1 | grep -i warning || echo "sem warnings"`
Expected: `sem warnings`.

- [ ] **Step 4: Construtor do AST (CRLF)**

Em `SukoAstBuilder.java`:

1. Campos e acessor novos, depois do campo `sourceHasSurrogates`:

```java
    /**
     * Erro de forma detetado ao construir o AST (13b): prefixo reservado com maiúsculas, espaços à volta de
     * ':', lambda fora de on:, state/derived fora do sítio. O caminho estrito (JteCompiler) reporta-o como
     * PARSE_ERROR com a posição real; os caminhos silenciosos (ProjectIndex, RegistryGenerator) e o
     * tolerante (LSP) ignoram-no, como já ignoram os erros do ANTLR.
     */
    public record SyntaxIssue(String message, SourceSpan span) {
    }

    private final List<SyntaxIssue> syntaxIssues = new ArrayList<>();

    public List<SyntaxIssue> syntaxIssues() {
        return List.copyOf(syntaxIssues);
    }

    private void issue(String message, SourceSpan span) {
        syntaxIssues.add(new SyntaxIssue(message, span));
    }
```

2. Em `buildComponentTolerant` e em `buildComponent`, o `return new ComponentDecl(...)` passa a terminar em `..., spanOf(ctx.Identifier().getSymbol()), ctx.REACTIVE() != null);` (o 8.º argumento).

3. No início de `buildStatement`:

```java
        if (ctx.reactiveDecl() != null) {
            return buildReactiveDecl(ctx);
        }
```

e os métodos novos (junto de `buildForStmt`):

```java
    private Statement buildReactiveDecl(SukoParser.TemplateStatementContext stmtCtx) {
        SukoParser.ReactiveDeclContext ctx = stmtCtx.reactiveDecl();
        Optional<Type> type = ctx.VAR() != null ? Optional.empty() : Optional.of(buildType(ctx.type()));
        String name = ctx.Identifier().getText();
        Expr value = buildExpr(ctx.expression());
        SourceSpan span = spanOf(ctx);
        SourceSpan nameSpan = spanOf(ctx.Identifier().getSymbol());
        boolean state = ctx.kind.getType() == SukoParser.STATE;
        if (!atTopOfReactiveBody(stmtCtx)) {
            issue("'" + (state ? "state" : "derived") + "' só pode aparecer no topo do corpo de um reactive component,"
                + " antes do markup", span);
        }
        return state
            ? new Statement.StateDecl(type, name, value, span, nameSpan)
            : new Statement.DerivedDecl(type, name, value, span, nameSpan);
    }

    /** Num component que não é reativo devolve true: aí quem fala é o REACTIVE_REQUIRED do verificador. */
    private static boolean atTopOfReactiveBody(SukoParser.TemplateStatementContext stmtCtx) {
        org.antlr.v4.runtime.ParserRuleContext p = stmtCtx;
        while (p != null && !(p instanceof SukoParser.ComponentDeclContext)) {
            p = p.getParent();
        }
        if (!(p instanceof SukoParser.ComponentDeclContext component) || component.REACTIVE() == null) {
            return true;
        }
        if (!(stmtCtx.getParent() instanceof SukoParser.ComponentBodyContext body)) {
            return false;
        }
        for (SukoParser.TemplateStatementContext sibling : body.templateStatement()) {
            if (sibling == stmtCtx) {
                return true;
            }
            if (sibling.reactiveDecl() == null) {
                return false;
            }
        }
        return true;
    }
```

4. `buildForStmt` passa a:

```java
    private Statement.ForStmt buildForStmt(SukoParser.ForStmtContext ctx) {
        Type itemType = ctx.VAR() != null ? new Type("var", List.of(), 0) : buildType(ctx.type());
        return new Statement.ForStmt(
            itemType,
            ctx.Identifier().getText(),
            buildExpr(ctx.expression()),
            buildStatements(ctx.templateBlock().templateStatement()),
            spanOf(ctx));
    }
```

5. Em `buildComponentCallStmt`, o ciclo dos argumentos passa a:

```java
        if (ctx.componentArgList() != null) {
            for (SukoParser.ComponentArgContext argCtx : ctx.componentArgList().componentArg()) {
                Optional<String> name = argCtx.Identifier() == null
                    ? Optional.empty()
                    : Optional.of(argCtx.Identifier().getText());
                Expr value = argCtx.lambda() != null ? buildLambda(argCtx.lambda()) : buildExpr(argCtx.expression());
                SourceSpan argSpan = argCtx.Identifier() != null
                    ? spanOf(argCtx.Identifier().getSymbol())
                    : argCtx.lambda() != null ? spanOf(argCtx.lambda()) : spanOf(argCtx.expression());
                args.add(new Statement.Arg(name, value, argSpan));
            }
        }
```

6. `buildHtmlElement` e `buildAttributes` passam a (substituem os atuais):

```java
    private Statement.HtmlElement buildHtmlElement(SukoParser.HtmlElementContext ctx) {
        if (ctx instanceof SukoParser.SelfClosingElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName().getText(), parts.attributes(),
                List.of(), true, spanOf(ctx), parts.directives());
        }
        if (ctx instanceof SukoParser.OpenElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName(0).getText(), parts.attributes(),
                buildStatements(c.templateStatement()), false, spanOf(ctx), parts.directives());
        }
        if (ctx instanceof SukoParser.VoidElementContext c) {
            AttributeParts parts = buildAttributes(c.attribute());
            return new Statement.HtmlElement(c.htmlName().getText(), parts.attributes(),
                List.of(), true, spanOf(ctx), parts.directives());
        }
        throw new IllegalStateException("Tipo de htmlElement desconhecido: " + ctx.getClass());
    }

    private record AttributeParts(List<Statement.Attribute> attributes, List<Statement.Directive> directives) {
    }

    /** Atributos HTML e diretivas da 13b (`on:`/`bind:`, só com o prefixo exatamente em minúsculas). */
    private AttributeParts buildAttributes(List<SukoParser.AttributeContext> ctxs) {
        List<Statement.Attribute> attributes = new ArrayList<>();
        List<Statement.Directive> directives = new ArrayList<>();
        for (SukoParser.AttributeContext attrCtx : ctxs) {
            SukoParser.AttrNameContext nameCtx = attrCtx.attrName();
            String prefix = nameCtx.htmlName(0).getText();
            boolean prefixed = nameCtx.COLON() != null;
            String local = prefixed ? nameCtx.htmlName(1).getText() : null;
            String name = prefixed ? prefix + ":" + local : prefix;
            SourceSpan span = spanOf(attrCtx);
            SourceSpan nameSpan = spanOf(nameCtx);
            if (prefixed && !adjacent(nameCtx)) {
                issue("'" + name + "': um nome de atributo com ':' escreve-se sem espaços", nameSpan);
            }
            if (prefixed && (prefix.equals("on") || prefix.equals("bind"))) {
                directives.add(buildDirective(attrCtx, prefix.equals("on"), local, span, nameSpan));
                continue;
            }
            if (prefixed && (prefix.equalsIgnoreCase("on") || prefix.equalsIgnoreCase("bind"))) {
                String lower = prefix.toLowerCase(java.util.Locale.ROOT);
                issue("'" + prefix + ":' é um prefixo reservado do Suko e escreve-se em minúsculas: " + lower + ":" + local,
                    nameSpan);
            }
            boolean lambda = attrCtx.lambdaInterpolation() != null;
            if (lambda) {
                issue("uma lambda só é aceite como valor de on:evento (ex.: on:click=${() -> ...})"
                    + " ou como argumento de um componente reativo", span);
            }
            Expr value = attrCtx.stringLiteral() != null
                ? buildStringLiteral(attrCtx.stringLiteral())
                : attrCtx.interpolation() != null
                    ? buildExpr(attrCtx.interpolation().expression())
                    : lambda
                        ? buildLambda(attrCtx.lambdaInterpolation().lambda())
                        : new Expr.PrimaryExpr("true", spanOf(attrCtx));
            boolean legacyBraceForm = attrCtx.interpolation() != null
                && attrCtx.interpolation().legacy != null;
            attributes.add(new Statement.Attribute(name, value, legacyBraceForm, span));
        }
        return new AttributeParts(attributes, directives);
    }

    /** `prefixo:nome` só com os três pedaços colados: o lexer descarta o espaço, por isso comparam-se posições. */
    private static boolean adjacent(SukoParser.AttrNameContext ctx) {
        int prefixEnd = ctx.htmlName(0).getStop().getStopIndex();
        int colon = ctx.COLON().getSymbol().getStartIndex();
        int localStart = ctx.htmlName(1).getStart().getStartIndex();
        return colon == prefixEnd + 1 && localStart == colon + 1;
    }

    private Statement.Directive buildDirective(SukoParser.AttributeContext attrCtx, boolean on, String local,
                                               SourceSpan span, SourceSpan nameSpan) {
        Expr value;
        if (on && attrCtx.lambdaInterpolation() != null) {
            value = buildLambda(attrCtx.lambdaInterpolation().lambda());
        } else if (!on && attrCtx.interpolation() != null && attrCtx.interpolation().legacy == null) {
            value = buildExpr(attrCtx.interpolation().expression());
        } else {
            issue(on
                ? "on:" + local + " espera uma lambda: on:" + local + "=${() -> ...} (sem aspas)"
                : "bind:" + local + " espera o nome de um state: bind:" + local + "=${nome} (sem aspas)", span);
            value = new Expr.PrimaryExpr("null", span);
        }
        return new Statement.Directive(on ? Statement.Directive.Kind.ON : Statement.Directive.Kind.BIND,
            local, value, span, nameSpan);
    }

    private Expr.LambdaExpr buildLambda(SukoParser.LambdaContext ctx) {
        List<String> params = ctx.lambdaParams().Identifier() == null
            ? List.of()
            : List.of(ctx.lambdaParams().Identifier().getText());
        SukoParser.LambdaBodyContext body = ctx.lambdaBody();
        List<Expr> statements = new ArrayList<>();
        for (SukoParser.ClientStatementContext st : body.clientStatement()) {
            statements.add(buildClientStatement(st));
        }
        return new Expr.LambdaExpr(params, statements, body.LBRACE() != null, spanOf(ctx));
    }

    private Expr buildClientStatement(SukoParser.ClientStatementContext ctx) {
        return switch (ctx) {
            case SukoParser.AssignStatementContext c -> new Expr.AssignExpr(
                buildExpr(c.expression(0)), c.op.getText(), buildExpr(c.expression(1)), spanOf(c));
            case SukoParser.PrefixUpdateStatementContext c ->
                new Expr.UpdateExpr(buildExpr(c.expression()), c.op.getText(), true, spanOf(c));
            case SukoParser.PostfixUpdateStatementContext c ->
                new Expr.UpdateExpr(buildExpr(c.expression()), c.op.getText(), false, spanOf(c));
            case SukoParser.ExpressionStatementContext c -> buildExpr(c.expression());
            default -> throw new IllegalStateException("instrução de lambda desconhecida: " + ctx.getText());
        };
    }
```

7. Em `buildExpr`: os casos `AccessExprContext` e `SafeAccessExprContext` passam a ler `c.memberName().getText()` em vez de `c.Identifier().getText()`, e acrescenta-se antes do `default`:

```java
            case SukoParser.NewExprContext c -> {
                if (c.argList() != null) {
                    for (SukoParser.ArgContext a : c.argList().arg()) {
                        if (a.Identifier() != null) {
                            issue("new " + c.Identifier().getText() + "(...) não aceita argumentos com nome", spanOf(a));
                        }
                    }
                }
                yield new Expr.NewExpr(c.Identifier().getText(), buildArgs(c.argList()), spanOf(c));
            }
```

- [ ] **Step 5: `JteCompiler` reporta os problemas do construtor; `TolerantParser` vê `reactive` (CRLF)**

Em `JteCompiler.parseAndBuild`, o bloco `try` passa a:

```java
        try {
            SukoAstBuilder builder = new SukoAstBuilder(sukoSource);
            SukoFile file = builder.build(compilationUnitCtx);
            for (SukoAstBuilder.SyntaxIssue issue : builder.syntaxIssues()) {
                diagnostics.add(new SukoDiagnostic(Severity.ERROR, issue.message(), "PARSE_ERROR", fileName, issue.span()));
            }
            return builder.syntaxIssues().isEmpty() ? file : null;
        } catch (IllegalStateException e) {
```

(o `catch` existente fica igual).

Em `TolerantParser.componentStarts`, o bloco

```java
            int first = i;
            if (i > 0 && tokens.get(i - 1).getType() == SukoLexer.PUBLIC
                    && tokens.get(i - 1).getLine() == token.getLine()) {
                first = i - 1;
            }
```

passa a

```java
            int first = i;
            if (first > 0 && tokens.get(first - 1).getType() == SukoLexer.REACTIVE
                    && tokens.get(first - 1).getLine() == token.getLine()) {
                first--;
            }
            if (first > 0 && tokens.get(first - 1).getType() == SukoLexer.PUBLIC
                    && tokens.get(first - 1).getLine() == token.getLine()) {
                first--;
            }
```

e o Javadoc do método passa a dizer `[public] [reactive] component Nome (`.

- [ ] **Step 6: Verificar**

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.SukoReactiveSyntaxTest'`
Expected: PASS. Se `newIsJavaOnTheServer` ou `forVarParsesAndRenders` falharem no motor JTE (não na gramática), parar e reportar ao controlador com o `.jte` gerado.

Run: `scripts/verify-isolated.sh :suko-core:test :suko-jte:test :suko-lsp:test :suko-registry-generator:test :suko-website:test :suko-components:test`
Expected: BUILD SUCCESSFUL — em especial `GoldenParityTest`, `SukoParserSmokeTest`, `SukoAstBuilderTolerantTest`, os goldens do site e o `RegistryGoldenTest` sem alterações.

Fins de linha: `crlf_check suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java suko-core/src/main/java/io/suko/lang/JteCompiler.java suko-core/src/main/java/io/suko/lang/TolerantParser.java` → `N/N`.

- [ ] **Step 7: Commit**

```bash
git status
git add suko-core/src/main/antlr/io/suko/lang/SukoLexer.g4 suko-core/src/main/antlr/io/suko/lang/SukoParser.g4 \
  suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java suko-core/src/main/java/io/suko/lang/JteCompiler.java \
  suko-core/src/main/java/io/suko/lang/TolerantParser.java suko-core/src/test/java/io/suko/lang/SukoReactiveSyntaxTest.java
git commit -m "feat(13b): gramática de reactive component, state/derived, on:/bind:, lambdas, new e prefixo:nome"
```

---

### Task 3: Regras de linguagem no core — `REACTIVE_REQUIRED`, `REACTIVE_UNUSED`, `DERIVED_CYCLE`, `BIND_TARGET`, `LAMBDA_ACROSS_BOUNDARY`, `REACTIVE_UNSUPPORTED`

**Files:**
- Create: `suko-core/src/main/java/io/suko/lang/semantic/ReactiveChecker.java`, `suko-core/src/main/java/io/suko/lang/ext/CapabilityChecker.java`
- Modify (CRLF): `suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java` (uma linha), `suko-core/src/main/java/io/suko/lang/JteCompiler.java` (uma linha)
- Test (create): `suko-core/src/test/java/io/suko/lang/semantic/ReactiveRulesTest.java`

**Interfaces:**
- Consumes: `ComponentDecl.reactive()`, `StateDecl`/`DerivedDecl`, `HtmlElement.directives()`, `Expr.LambdaExpr`, `ProjectIndexEntry.reactive()`, `ExprNames.of`, `Target.capabilities()` (Task 1); o AST da Task 2; `CallResolver.resolve(String)` (existente).
- Produces:
  - `ReactiveChecker` (package-private, chamado no fim de `SemanticChecker.check`): `REACTIVE_REQUIRED` (ERROR) por `state`/`derived`/`on:`/`bind:` num `component` simples e por lambda passada entre dois `component` (R19); `REACTIVE_UNUSED` (HINT) num reactive component sem `state`, `derived`, diretivas nem lambdas, no `nameSpan`; `DERIVED_CYCLE` (ERROR) um por ciclo, no `nameSpan` do primeiro `derived` do ciclo por ordem de declaração, mensagem `ciclo entre derived: a → b → a`; `BIND_TARGET` (ERROR) quando o valor de `bind:` não é o nome de um `state` do componente; `LAMBDA_ACROSS_BOUNDARY` (ERROR) no span da lambda; `INVALID_DOCTYPE` (ERROR) num reactive component que abre com `<!DOCTYPE html>`.
  - `public static void CapabilityChecker.check(SukoFile, String fileName, List<Target>, ExtensionRegistry, DiagnosticCollector)`: `REACTIVE_UNSUPPORTED` (ERROR) por cada par (reactive component, alvo pedido sem `Target.CAPABILITY_REACTIVE`), no `nameSpan`; uma exceção em `capabilities()`/`id()` vira `EXTENSION_FAILED`.

- [ ] **Step 1: Testes (falham)**

`suko-core/src/test/java/io/suko/lang/semantic/ReactiveRulesTest.java`:

```java
package io.suko.lang.semantic;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Regras de linguagem da 13b que não dependem do alvo (core). */
class ReactiveRulesTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics();
    }

    static List<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).toList();
    }

    static long count(String sk, String code) {
        return codes(sk).stream().filter(code::equals).count();
    }

    static final String CARD = "component Card(Runnable onX) { <b>card</b> }\n";
    static final String STEPPER = """
        reactive component Stepper(int value, Consumer<Integer> onChange) {
          <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
        }
        """;

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { state int n = 0; <p>x</p> }",
        "component A() { derived int n = 1; <p>x</p> }",
        "component A() { <b on:click=${() -> go()}>x</b> }",
        "component A(String s) { <input bind:value=${s}/> }",
        CARD + "component A() { Card(onX = () -> go()) }",
    })
    void reactiveSyntaxInASimpleComponentIsOnlyReactiveRequired(String sk) {
        assertEquals(List.of("REACTIVE_REQUIRED"), codes(sk), diagnostics(sk).toString());
        assertTrue(diagnostics(sk).get(0).message().contains("reactive component A"), diagnostics(sk).toString());
    }

    @Test
    void lambdaFromASimpleComponentToAReactiveOneCrossesTheBoundary() {
        String sk = STEPPER + "component A() { Stepper(value = 1, onChange = v -> go(v)) }";
        assertEquals(1, count(sk, "LAMBDA_ACROSS_BOUNDARY"), diagnostics(sk).toString());
        assertEquals(0, count(sk, "REACTIVE_REQUIRED"));
    }

    @Test
    void lambdaFromAReactiveComponentToASimpleOneCrossesTheBoundary() {
        String sk = CARD + "reactive component R() { state int n = 0; Card(onX = () -> n++) <b>${n}</b> }";
        assertEquals(1, count(sk, "LAMBDA_ACROSS_BOUNDARY"), diagnostics(sk).toString());
    }

    @Test
    void lambdasBetweenReactiveComponentsAreFine() {
        String sk = STEPPER + "reactive component C() { state int n = 0; Stepper(value = n, onChange = v -> n = v) }";
        assertEquals(Set.of("REACTIVE_UNSUPPORTED"), Set.copyOf(codes(sk)), diagnostics(sk).toString());
    }

    @Test
    void reactiveComponentWithoutReactivityGetsAHint() {
        SukoDiagnostic hint = diagnostics("reactive component R() { <p>x</p> }").stream()
            .filter(d -> d.code().equals("REACTIVE_UNUSED")).findFirst().orElseThrow();
        assertEquals(SukoDiagnostic.Severity.HINT, hint.severity());
        assertTrue(hint.message().contains("component simples"), hint.message());
    }

    @Test
    void forwardingACallbackIsNotUnused() {
        String sk = STEPPER + "reactive component Mid(Consumer<Integer> onChange) { Stepper(value = 1, onChange = v -> onChange.accept(v)) }";
        assertEquals(0, count(sk, "REACTIVE_UNUSED"), diagnostics(sk).toString());
    }

    @Test
    void derivedCycleIsReportedOncePerCycle() {
        String sk = "reactive component R() { derived int a = b + 1; derived int b = a + 1; derived int c = a; <p>${c}</p> }";
        List<SukoDiagnostic> cycles = diagnostics(sk).stream().filter(d -> d.code().equals("DERIVED_CYCLE")).toList();
        assertEquals(1, cycles.size(), diagnostics(sk).toString());
        assertTrue(cycles.get(0).message().contains("a → b → a"), cycles.get(0).message());
        assertEquals(1, count("reactive component R() { derived int x = x + 1; <p>${x}</p> }", "DERIVED_CYCLE"));
        assertEquals(0, count("reactive component R() { state int s = 1; derived int a = s + 1; derived int b = a + 1; <p>${b}</p> }", "DERIVED_CYCLE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reactive component R() { state String s = \"\"; derived String d = s; <input bind:value=${d}/> }",
        "reactive component R(String p) { state String s = \"\"; <input bind:value=${p}/> }",
        "reactive component R(Form f) { state String s = \"\"; <input bind:value=${f.name}/> }",
    })
    void bindNeedsAPlainState(String sk) {
        assertEquals(1, count(sk, "BIND_TARGET"), diagnostics(sk).toString());
    }

    @Test
    void bindToAStateIsFine() {
        assertEquals(0, count("reactive component R() { state String s = \"\"; <input bind:value=${s}/> }", "BIND_TARGET"));
    }

    @Test
    void doctypeIsForTheLayoutNotForAReactiveComponent() {
        SukoDiagnostic d = diagnostics("reactive component R() { <!DOCTYPE html> <p>x</p> }").stream()
            .filter(x -> x.code().equals("INVALID_DOCTYPE")).findFirst().orElseThrow();
        assertTrue(d.message().contains("<!DOCTYPE html>") && d.message().contains("layout"), d.message());
    }

    @Test
    void jteDoesNotSupportReactiveComponentsUntilPlanTwo() {
        String sk = "reactive component Counter(int start) { state var count = start; <button on:click=${() -> count++}>${count}</button> }";
        List<SukoDiagnostic> d = diagnostics(sk);
        assertEquals(Set.of("REACTIVE_UNSUPPORTED"), Set.copyOf(codes(sk)), d.toString());
        assertTrue(d.get(0).message().contains("'jte'") && d.get(0).message().contains("Counter"), d.get(0).message());
        assertEquals(1, d.get(0).span().startLine());
        assertEquals(sk.indexOf("Counter"), d.get(0).span().startColumn());
    }

    /** Alvo de teste com a capacidade reactive. */
    static final class ReactiveExtension implements SukoExtension {
        final boolean throwing;

        ReactiveExtension(boolean throwing) {
            this.throwing = throwing;
        }

        public String id() { return "io.suko.test.reactive"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "reactive-test"; }
                public String componentType() { return "java.lang.Object"; }
                public Set<String> vocabularies() { return Set.of(); }
                public Set<String> capabilities() {
                    if (throwing) {
                        throw new IllegalStateException("capacidades partidas");
                    }
                    return Set.of(Target.CAPABILITY_REACTIVE);
                }
                public Emitted emit(ComponentDecl c, EmitContext e) { return new Emitted(c.name() + ".txt", "", List.of()); }
            });
        }
    }

    @Test
    void aTargetWithTheCapabilityAcceptsReactiveComponents() {
        var registry = ExtensionRegistry.of(List.of(new ReactiveExtension(false)));
        var result = new JteCompiler("T.sk", "reactive component R() { state int n = 0; <p>${n}</p> }",
            registry, List.of("reactive-test")).compile();
        assertTrue(result.success(), result.diagnostics().toString());
    }

    @Test
    void capabilitiesThatThrowBecomeExtensionFailed() {
        var registry = ExtensionRegistry.of(List.of(new ReactiveExtension(true)));
        var result = new JteCompiler("T.sk", "reactive component R() { state int n = 0; <p>${n}</p> }",
            registry, List.of("reactive-test")).compile();
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("EXTENSION_FAILED")
            && d.message().contains("io.suko.test.reactive")), result.diagnostics().toString());
    }

    @Test
    void theBoundaryIsCheckedAcrossFiles() {
        var analysis = new SukoProjectCompiler().analyze(SukoSources.of(Path.of("/virtual"), Map.of(
            Path.of("ui/Stepper.sk"), "package ui;\n\npublic " + STEPPER,
            Path.of("Page.sk"), "import ui.Stepper;\n\ncomponent Page() {\n  Stepper(value = 1, onChange = v -> go(v))\n}\n")));
        var page = analysis.files().get(Path.of("Page.sk")).diagnostics().getDiagnostics();
        assertTrue(page.stream().anyMatch(d -> d.code().equals("LAMBDA_ACROSS_BOUNDARY")), page.toString());
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.semantic.ReactiveRulesTest'`
Expected: FAIL (as regras não existem; o `state` num component simples chega ao emissor e dá `EXTENSION_FAILED`).

- [ ] **Step 2: `ReactiveChecker`**

`suko-core/src/main/java/io/suko/lang/semantic/ReactiveChecker.java`:

```java
package io.suko.lang.semantic;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;
import io.suko.lang.project.CallResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Regras de linguagem da 13b que não dependem do alvo: REACTIVE_REQUIRED, REACTIVE_UNUSED (HINT),
 * DERIVED_CYCLE, BIND_TARGET (o alvo de bind: tem de ser um state simples), LAMBDA_ACROSS_BOUNDARY e o
 * INVALID_DOCTYPE de um reactive component. O subconjunto do cliente, os sinks e as props são do alvo
 * (suko-jte), porque o cliente é JS (R9 do plano 1 da 13b).
 */
final class ReactiveChecker {

    private final DiagnosticCollector diagnostics;
    private final String sourceFile;
    private final CallResolver resolver;

    ReactiveChecker(DiagnosticCollector diagnostics, String sourceFile, CallResolver resolver) {
        this.diagnostics = diagnostics;
        this.sourceFile = sourceFile;
        this.resolver = resolver;
    }

    void check(SukoFile file) {
        for (ComponentDecl component : file.components()) {
            check(component);
        }
    }

    private void check(ComponentDecl component) {
        Set<String> states = new HashSet<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.StateDecl d) {
                states.add(d.name());
            }
        }
        boolean[] used = {false};
        walk(component.body(), component, states, used);
        if (!component.reactive()) {
            return;
        }
        if (!component.body().isEmpty() && component.body().get(0) instanceof Statement.TextRun t
                && t.text().equals("<!DOCTYPE html>")) {
            report(Severity.ERROR, "INVALID_DOCTYPE", "um reactive component é sempre um fragmento dentro de uma página:"
                + " o <!DOCTYPE html> pertence ao layout (um component simples)", t.span());
        }
        if (!used[0]) {
            report(Severity.HINT, "REACTIVE_UNUSED", "'" + component.name() + "' não usa state, derived, on:, bind:"
                + " nem lambdas: pode ser um component simples", component.nameSpan());
        }
        derivedCycles(component);
    }

    private void walk(List<Statement> statements, ComponentDecl component, Set<String> states, boolean[] used) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.StateDecl d -> {
                    used[0] = true;
                    required(component, "state", d.nameSpan());
                }
                case Statement.DerivedDecl d -> {
                    used[0] = true;
                    required(component, "derived", d.nameSpan());
                }
                case Statement.HtmlElement e -> {
                    for (Statement.Directive d : e.directives()) {
                        used[0] = true;
                        boolean on = d.kind() == Statement.Directive.Kind.ON;
                        if (!component.reactive()) {
                            required(component, (on ? "on:" : "bind:") + d.name(), d.nameSpan());
                        } else if (!on) {
                            bindTarget(d, states);
                        }
                    }
                    walk(e.children(), component, states, used);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.Arg arg : call.args()) {
                        if (arg.value() instanceof Expr.LambdaExpr lambda) {
                            used[0] = true;
                            lambdaArgument(component, call, lambda);
                        }
                    }
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), component, states, used);
                    }
                }
                case Statement.IfStmt i -> {
                    walk(i.thenBranch(), component, states, used);
                    walk(i.elseBranch(), component, states, used);
                }
                case Statement.ForStmt f -> walk(f.body(), component, states, used);
                case Statement.SwitchStmt sw -> {
                    for (Statement.SwitchCase c : sw.cases()) {
                        walk(c.body(), component, states, used);
                    }
                    walk(sw.defaultCase(), component, states, used);
                }
                case Statement.VarDecl v -> { }
                case Statement.Interpolation i -> { }
                case Statement.TextRun t -> { }
            }
        }
    }

    private void required(ComponentDecl component, String what, SourceSpan span) {
        if (!component.reactive()) {
            report(Severity.ERROR, "REACTIVE_REQUIRED", "'" + what + "' só existe num reactive component:"
                + " escreva 'reactive component " + component.name() + "(...)'", span);
        }
    }

    private void bindTarget(Statement.Directive d, Set<String> states) {
        if (d.value() instanceof Expr.PrimaryExpr p && states.contains(p.text())) {
            return;
        }
        report(Severity.ERROR, "BIND_TARGET", "bind:" + d.name() + " precisa de um state simples deste componente"
            + " (não um derived, um parâmetro nem a.b)", d.span());
    }

    /** R19: lambdas só passam entre componentes reativos. */
    private void lambdaArgument(ComponentDecl component, Statement.ComponentCallStmt call, Expr.LambdaExpr lambda) {
        Optional<Boolean> calleeReactive = switch (resolver.resolve(call.componentName())) {
            case CallResolver.Resolution.Local local -> Optional.of(local.decl().reactive());
            case CallResolver.Resolution.Project project -> Optional.of(project.entry().reactive());
            case CallResolver.Resolution.NotVisible notVisible -> Optional.empty();
            case CallResolver.Resolution.NotFound notFound -> Optional.empty();
        };
        if (calleeReactive.isEmpty()) {
            return; // COMPONENT_NOT_FOUND / COMPONENT_NOT_VISIBLE já foram reportados
        }
        boolean target = calleeReactive.get();
        if (component.reactive() && target) {
            return;
        }
        if (component.reactive()) {
            report(Severity.ERROR, "LAMBDA_ACROSS_BOUNDARY", "'" + call.componentName() + "' não é reativo (renderiza"
                + " no servidor): uma lambda só pode ser passada a um reactive component", lambda.span());
        } else if (target) {
            report(Severity.ERROR, "LAMBDA_ACROSS_BOUNDARY", "uma lambda não atravessa a fronteira servidor → cliente:"
                + " '" + component.name() + "' renderiza no servidor e '" + call.componentName() + "' corre no cliente",
                lambda.span());
        } else {
            report(Severity.ERROR, "REACTIVE_REQUIRED", "lambdas só existem em componentes reativos: escreva"
                + " 'reactive component " + component.name() + "(...)'", lambda.span());
        }
    }

    private void derivedCycles(ComponentDecl component) {
        Map<String, Statement.DerivedDecl> derived = new LinkedHashMap<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.DerivedDecl d) {
                derived.putIfAbsent(d.name(), d);
            }
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Statement.DerivedDecl d : derived.values()) {
            List<String> deps = new ArrayList<>();
            for (String name : ExprNames.of(d.value())) {
                if (derived.containsKey(name)) {
                    deps.add(name);
                }
            }
            edges.put(d.name(), deps);
        }
        Set<String> reported = new HashSet<>();
        for (String start : derived.keySet()) {
            if (reported.contains(start)) {
                continue;
            }
            List<String> cycle = new ArrayList<>(List.of(start));
            if (!backTo(start, start, edges, cycle, new HashSet<>())) {
                continue;
            }
            reported.addAll(cycle);
            report(Severity.ERROR, "DERIVED_CYCLE", "ciclo entre derived: " + String.join(" → ", cycle) + " → " + start
                + " (um derived não pode depender de si próprio)", derived.get(start).nameSpan());
        }
    }

    /** Procura um caminho de {@code node} de volta a {@code start}; {@code path} fica com o ciclo. */
    private static boolean backTo(String node, String start, Map<String, List<String>> edges, List<String> path,
                                  Set<String> seen) {
        for (String next : edges.getOrDefault(node, List.of())) {
            if (next.equals(start)) {
                return true;
            }
            if (seen.add(next)) {
                path.add(next);
                if (backTo(next, start, edges, path, seen)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    private void report(Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, sourceFile, span));
    }
}
```

Em `SemanticChecker.check` (CRLF), depois do ciclo `for (ComponentDecl component : sukoFile.components())`:

```java
        new ReactiveChecker(diagnostics, sourceFile, resolver).check(sukoFile);
```

- [ ] **Step 3: `CapabilityChecker`**

`suko-core/src/main/java/io/suko/lang/ext/CapabilityChecker.java`:

```java
package io.suko.lang.ext;

import io.suko.ext.Target;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;
import java.util.Set;

/**
 * REACTIVE_UNSUPPORTED (13b): um reactive component pedido para um alvo que não declara a capacidade
 * {@link Target#CAPABILITY_REACTIVE}. Vive no core porque é o core que conhece os alvos pedidos (o
 * CheckContext das extensões só expõe vocabulários).
 */
public final class CapabilityChecker {

    private CapabilityChecker() {
    }

    public static void check(SukoFile file, String fileName, List<Target> targets, ExtensionRegistry registry,
                             DiagnosticCollector diagnostics) {
        List<ComponentDecl> reactive = file.components().stream().filter(ComponentDecl::reactive).toList();
        if (reactive.isEmpty()) {
            return;
        }
        for (Target target : targets) {
            String id;
            Set<String> capabilities;
            try {
                id = target.id();
                capabilities = Set.copyOf(target.capabilities());
            } catch (Throwable e) {
                ExtensionFailures.rethrowFatal(e);
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "A extensão '" + registry.ownerOf(target) + "' falhou em " + fileName + " (capacidades do alvo): "
                        + VocabularyChecker.describe(e), "EXTENSION_FAILED", fileName, SourceSpan.NONE));
                continue;
            }
            if (capabilities.contains(Target.CAPABILITY_REACTIVE)) {
                continue;
            }
            for (ComponentDecl component : reactive) {
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "O alvo '" + id + "' não suporta componentes reativos: '" + component.name()
                        + "' não pode ser compilado para ele", "REACTIVE_UNSUPPORTED", fileName, component.nameSpan()));
            }
        }
    }
}
```

Em `JteCompiler.runExtensionChecks` (CRLF), logo a seguir à linha `VocabularyChecker.check(sukoFile, fileName, resolved, registry, diagnostics);`:

```java
        io.suko.lang.ext.CapabilityChecker.check(sukoFile, fileName, resolved, registry, diagnostics);
```

- [ ] **Step 4: Verificar**

Run: `scripts/verify-isolated.sh :suko-core:test`
Expected: BUILD SUCCESSFUL, `ReactiveRulesTest` verde e o resto da suite do core sem alterações.

`crlf_check suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java suko-core/src/main/java/io/suko/lang/JteCompiler.java` → `N/N`.

- [ ] **Step 5: Commit**

```bash
git status
git add suko-core/src/main/java/io/suko/lang/semantic/ReactiveChecker.java \
  suko-core/src/main/java/io/suko/lang/ext/CapabilityChecker.java \
  suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java suko-core/src/main/java/io/suko/lang/JteCompiler.java \
  suko-core/src/test/java/io/suko/lang/semantic/ReactiveRulesTest.java
git commit -m "feat(13b): regras de linguagem dos componentes reativos e REACTIVE_UNSUPPORTED por capacidade do alvo"
```

---

### Task 4: Ramos `:` do `HtmlSecurityChecker` e a exceção `on:`/`bind:` (Q4) — testes e render real

**Pré-condição:** a revisão do `security-specialist` sobre as alterações de 2026-10-11 da spec (Q4/Q5/Q6, um diagnóstico por sítio) está feita; se mudar alguma regra desta task, aplicar antes de executar.

Esta task não muda código de produção se as Tasks 1–3 estiverem certas: o `HtmlSecurityChecker` já trata `hx-on*`, `x-*`, `data-` e `xlink:href` pelo nome completo, e as diretivas nunca chegam a `attributes()` (R2). O trabalho é **provar** que os ramos `:` deixaram de ser código morto (lacuna (k) do item 14) e que o JTE real aceita os nomes com `:` com a `OwaspHtmlPolicy` ligada. Se algum teste falhar, a correção é no `HtmlSecurityChecker`/`HtmlSecurityRules` (com o `security-specialist`) e entra nesta task.

**Files:**
- Test (create): `suko-core/src/test/java/io/suko/lang/security/PrefixedAttributeSecurityTest.java`
- Modify (só se um teste o exigir): `suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java`, `suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java`

**Interfaces:**
- Consumes: a gramática e o AST da Task 2, as regras das Tasks 1 e 3, `SukoSafeSupport.engine(Path)` (testFixtures do core), `gg.jte.html.OwaspHtmlPolicy` (jte-runtime 3.1.12, `testImplementation`).
- Produces: só testes.

- [ ] **Step 1: Testes**

`suko-core/src/test/java/io/suko/lang/security/PrefixedAttributeSecurityTest.java`:

```java
package io.suko.lang.security;

import gg.jte.TemplateEngine;
import gg.jte.html.OwaspHtmlPolicy;
import gg.jte.output.StringOutput;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.support.SukoSafeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 13b, Q4: `prefixo:nome` em atributos. Os ramos `:` do HtmlSecurityChecker passam a ser código vivo;
 * `on:`/`bind:` são sintaxe Suko e nunca chegam ao checker como atributo `on*`.
 */
class PrefixedAttributeSecurityTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics();
    }

    static Set<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A(String x) { <a hx-on:click=${x}>a</a> }",
        "component A(String x) { <a x-on:click=${x}>a</a> }",
        "component A(String x) { <a data-hx-on:click=${x}>a</a> }",
        "component A(String x) { <a hx-on:click=\"${x}\">a</a> }",
        "component A(String v) { <a online:x=${v}>a</a> }",
        "component A(String x) { <button onclick=${x}>b</button> }",
        "reactive component A(String x) { state int n = 0; <button onclick=${x} on:click=${() -> n++}>b</button> }",
    })
    void dynamicCodeAttributesWithAColonAreUnsafeSinks(String sk) {
        assertTrue(codes(sk).contains("UNSAFE_SINK"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <a hx-on:click=\"go()\">a</a> }",
        "component A() { <a x-on:click=\"open = false\">a</a> }",
        "component A(String u) { <svg><use xlink:href=${u}/></svg> }",
        "component A() { <svg><use xlink:href=\"#icon\"/></svg> }",
    })
    void literalCodeAttributesAndDynamicXlinkHrefCompile(String sk) {
        assertEquals(Set.of(), codes(sk), sk + " -> " + diagnostics(sk));
    }

    @Test
    void onAndBindAreNotHtmlEventAttributes() {
        String sk = "reactive component A() { state String s = \"\"; state int n = 0;"
            + " <button on:click=${() -> n++}>b</button> <input bind:value=${s}/> }";
        assertFalse(codes(sk).contains("UNSAFE_SINK"), diagnostics(sk).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <button on:click=${() -> go()}>b</button> }",
        "component A(String s) { <input bind:value=${s}/> }",
    })
    void outsideAReactiveComponentTheyAreOnlyReactiveRequired(String sk) {
        assertEquals(Set.of("REACTIVE_REQUIRED"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void uppercaseReservedPrefixIsOnlyASyntaxError() {
        String sk = "reactive component A() { state int n = 0; <button On:click=${() -> n++}>b</button> }";
        assertEquals(Set.of("PARSE_ERROR"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void atSignIsStillASyntaxError() {
        assertEquals(Set.of("PARSE_ERROR"), codes("component A() { <a @click=\"go()\">a</a> }"));
    }

    @Test
    void dynamicXlinkHrefGoesThroughSukoSafeUrl() throws Exception {
        String sk = "component A(String u) { <svg><use xlink:href=${u}/></svg> }";
        String jte = new JteCompiler("A.sk", sk).compile().generatedJteSources().get("A.jte");
        assertTrue(jte.contains("xlink:href=\"${io.suko.generated.SukoSafe.url(u)}\""), jte);

        String html = render(sk, Map.of("u", "javascript:alert(1)"), false);
        assertTrue(html.contains("xlink:href=\"about:invalid#suko-blocked\""), html);
        assertTrue(render(sk, Map.of("u", "#icon"), false).contains("xlink:href=\"#icon\""));
    }

    @Test
    void namesWithAColonPassTheOwaspHtmlPolicyOfTheRealEngine() throws Exception {
        String sk = "component A(String u) { <svg><use xlink:href=${u}/></svg> <a hx-on:click=\"go()\">a</a> }";
        String html = render(sk, Map.of("u", "#icon"), true);
        assertTrue(html.contains("xlink:href=\"#icon\"") && html.contains("hx-on:click=\"go()\""), html);
    }

    /** Compila com o JteCompiler (SukoSafe por omissão) e renderiza A.jte com o motor real. */
    private static String render(String sk, Map<String, Object> params, boolean owaspPolicy) throws Exception {
        var result = new JteCompiler("A.sk", sk).compile();
        assertTrue(result.success(), result.diagnostics().toString());
        Path dir = Files.createTempDirectory("suko-prefixed");
        for (var e : result.generatedJteSources().entrySet()) {
            Files.writeString(dir.resolve(e.getKey()), e.getValue());
        }
        TemplateEngine engine = SukoSafeSupport.engine(dir);
        if (owaspPolicy) {
            engine.setHtmlPolicy(new OwaspHtmlPolicy());
        }
        StringOutput out = new StringOutput();
        engine.render("A.jte", new HashMap<>(params), out);
        return out.toString();
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.security.PrefixedAttributeSecurityTest'`
Expected: PASS. Se `dynamicXlinkHrefGoesThroughSukoSafeUrl` falhar na forma exata do `.jte`, ler o `.jte` gerado e ajustar **só** a string esperada da asserção `contains` à forma que o emissor já usa para `href` (o comportamento — passar por `SukoSafe.url` — não é negociável). Se o motor JTE recusar `xlink:href`/`hx-on:click` (exceção do `TemplateEngine` ou da `OwaspHtmlPolicy`), parar e reportar ao controlador: é uma limitação do JTE que muda a Q4.

- [ ] **Step 2: Verificar a suite de segurança**

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.security.*'`
Expected: PASS (`HtmlSecurityCheckerTest`, `XssCorpusTest`, `EmitterSecurityTest`, `NoUnsafeInGeneratedTemplatesTest` sem alterações).

- [ ] **Step 3: Commit**

```bash
git status
git add suko-core/src/test/java/io/suko/lang/security/PrefixedAttributeSecurityTest.java
# e, só se o Step 1 o exigiu: suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java
git commit -m "test(13b): ramos ':' do HtmlSecurityChecker vivos (hx-on:, x-on:, xlink:href) e on:/bind: fora do on*"
```

---

### Task 5: Tipos do cliente, `prelude.js` e o executor Node (com o primeiro teste diferencial)

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/client/ClientType.java`, `ClientTypes.java`, `JavaTypes.java`, `ClientScope.java`, `ClientProblem.java`, `JsText.java`, `ClientPrelude.java` (todos em `suko-jte/src/main/java/io/suko/jte/client/`)
- Create: `suko-jte/src/main/resources/io/suko/jte/client/prelude.js`
- Test (create): `suko-jte/src/test/java/io/suko/jte/client/ClientTypesTest.java`, `suko-jte/src/test/java/io/suko/jte/client/JsTextTest.java`, `suko-core/src/test/java/io/suko/lang/client/NodeRunner.java`, `suko-core/src/test/java/io/suko/lang/client/NodeRunnerTest.java`, `suko-core/src/test/java/io/suko/lang/client/PreludeDifferentialTest.java`

**Interfaces:**
- Consumes: `io.suko.lang.ast.Type`, `SourceSpan` (suko-api).
- Produces (o plano 2 também os usa):
  - `sealed interface ClientType` com `enum Scalar { INT, DOUBLE, BOOLEAN, STRING }`, `enum Special { NULL, VOID, EVENT, UNKNOWN }`, `record Boxed(Scalar of)`, `record ListOf(ClientType element)`, `record MapOf(ClientType value)`, `record RecordOf(String name, List<RecordOf.Field> fields)` com `record Field(String name, ClientType type)`, `record Named(String name)`, `record Callback(List<ClientType> params)`, `record Unsupported(String javaType, String reason)`; métodos `boolean nullable()` e `String java()`.
  - `ClientTypes.of(Type, JavaTypes) → ClientType`, `ClientTypes.parse(String) → Type`, `ClientTypes.assignable(ClientType from, ClientType to) → boolean`, `ClientTypes.unify(ClientType, ClientType) → ClientType` (ou `null`).
  - `interface JavaTypes { record Field(String name, Type type); Optional<List<Field>> record(String simpleName); JavaTypes NONE; }`.
  - `ClientScope` (imutável): `enum Kind { STATE, DERIVED, PARAM, LOCAL }`, `record Binding(Kind kind, ClientType type)`, `ClientScope.EMPTY`, `with(String, Kind, ClientType)`, `Optional<Binding> lookup(String)`.
  - `record ClientProblem(String code, String message, SourceSpan span)` com `boolean silent()` (`code == null`: consequência de um problema já reportado).
  - `JsText.string(String)` (literal JS só em ASCII), `JsText.identifier(String)` (`$_nome` para nomes reservados/globais de JS), `JsText.unescapeJava(String)`.
  - `ClientPrelude.RESOURCE = "/io/suko/jte/client/prelude.js"`, `ClientPrelude.source()`.
  - `prelude.js` (módulo ES): `nn, idiv, imod, dstr, dcat, text, dtext, sval, dval, trim, contains, startsWith, endsWith, equals, get, removeAt, setAt, clear, mget, mhas, field, rec, evValue, evChecked, evKey, evPrevent`.
  - Testes (core): `NodeRunner.run(String module) → String` e `NodeRunner.run(String node, String module)`; `NodeRunner.dataUrl(String js)`.

- [ ] **Step 1: Testes unitários (falham)**

`suko-jte/src/test/java/io/suko/jte/client/ClientTypesTest.java`:

```java
package io.suko.jte.client;

import io.suko.lang.ast.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.suko.jte.client.ClientType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientTypesTest {

    static final JavaTypes TODO = name -> switch (name) {
        case "Todo" -> Optional.of(List.of(new JavaTypes.Field("title", ClientTypes.parse("String")),
            new JavaTypes.Field("done", ClientTypes.parse("boolean"))));
        case "Node" -> Optional.of(List.of(new JavaTypes.Field("next", ClientTypes.parse("Node"))));
        default -> Optional.empty();
    };

    static ClientType of(String text) {
        return ClientTypes.of(ClientTypes.parse(text), TODO);
    }

    @Test
    void scalarsAndWrappers() {
        assertEquals(INT, of("int"));
        assertEquals(INT, of("short"));
        assertEquals(INT, of("byte"));
        assertEquals(DOUBLE, of("double"));
        assertEquals(BOOLEAN, of("boolean"));
        assertEquals(STRING, of("String"));
        assertEquals(new ClientType.Boxed(INT), of("Integer"));
        assertEquals(new ClientType.Boxed(DOUBLE), of("Double"));
        assertEquals(new ClientType.Boxed(BOOLEAN), of("Boolean"));
    }

    @Test
    void collectionsCallbacksAndRecords() {
        assertEquals(new ClientType.ListOf(new ClientType.Boxed(INT)), of("List<Integer>"));
        assertEquals(new ClientType.ListOf(STRING), of("List<String>"));
        assertEquals(new ClientType.MapOf(new ClientType.ListOf(STRING)), of("Map<String, List<String>>"));
        assertEquals(new ClientType.Callback(List.of(new ClientType.Boxed(INT))), of("Consumer<Integer>"));
        assertEquals(new ClientType.Callback(List.of()), of("Runnable"));
        assertEquals(new ClientType.RecordOf("Todo", List.of(new ClientType.RecordOf.Field("title", STRING),
            new ClientType.RecordOf.Field("done", BOOLEAN))), of("Todo"));
        assertEquals(new ClientType.Named("Product"), of("Product"));
    }

    @Test
    void outsideTheSubset() {
        for (String t : new String[] {"long", "Long", "char", "Character", "float", "Float", "int[]", "Object",
                                      "Map<Integer, String>", "Optional<String>", "Node", "Component"}) {
            assertInstanceOf(ClientType.Unsupported.class, of(t), t);
        }
        assertTrue(((ClientType.Unsupported) of("long")).reason().contains("2^53"));
        assertTrue(((ClientType.Unsupported) of("float")).reason().contains("double"));
        assertTrue(((ClientType.Unsupported) of("Node")).reason().contains("recursivo"));
    }

    @Test
    void parseIsTheInverseOfParamInfoText() {
        Type t = ClientTypes.parse("Map<String, List<Integer>>");
        assertEquals("Map", t.name());
        assertEquals("List", t.typeArguments().get(1).name());
        assertEquals("Integer", t.typeArguments().get(1).typeArguments().get(0).name());
        assertEquals(2, ClientTypes.parse("String[][]").arrayDimensions());
    }

    @Test
    void assignabilityFollowsJava() {
        assertTrue(ClientTypes.assignable(INT, DOUBLE));
        assertFalse(ClientTypes.assignable(DOUBLE, INT));
        assertTrue(ClientTypes.assignable(ClientType.Special.NULL, STRING));
        assertFalse(ClientTypes.assignable(ClientType.Special.NULL, INT));
        assertTrue(ClientTypes.assignable(INT, new ClientType.Boxed(INT)));
        assertTrue(ClientTypes.assignable(new ClientType.Boxed(INT), INT));
        assertFalse(ClientTypes.assignable(INT, new ClientType.Boxed(DOUBLE)));
        assertTrue(ClientTypes.assignable(new ClientType.Boxed(INT), DOUBLE));
        assertFalse(ClientTypes.assignable(new ClientType.ListOf(STRING), new ClientType.ListOf(new ClientType.Boxed(INT))));
    }

    @Test
    void ternaryTypes() {
        assertEquals(DOUBLE, ClientTypes.unify(INT, DOUBLE));
        assertEquals(new ClientType.Boxed(INT), ClientTypes.unify(ClientType.Special.NULL, INT));
        assertEquals(STRING, ClientTypes.unify(STRING, ClientType.Special.NULL));
        assertEquals(INT, ClientTypes.unify(new ClientType.Boxed(INT), INT));
        assertNull(ClientTypes.unify(STRING, INT));
    }
}
```

`suko-jte/src/test/java/io/suko/jte/client/JsTextTest.java`:

```java
package io.suko.jte.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsTextTest {

    @Test
    void stringLiteralsAreAsciiAndSafeInsideHtml() {
        assertEquals("\"a\\\"b\\\\c\\n\\u2028\\u003c\\u00e9\\u0026\\u0027\"", JsText.string("a\"b\\c\n\u2028<é&'"));
        assertEquals("\"\\ud800x\"", JsText.string("\ud800x"), "surrogate isolado sobrevive");
        assertEquals("\"\\u0000\\u001f\\u007f\"", JsText.string("\u0000\u001f\u007f"));
    }

    @Test
    void reservedAndGlobalNamesAreRenamed() {
        for (String name : new String[] {"arguments", "eval", "await", "yield", "let", "delete", "typeof",
                                         "undefined", "NaN", "window", "document", "Math", "self"}) {
            assertEquals("$_" + name, JsText.identifier(name), name);
        }
        assertEquals("count", JsText.identifier("count"));
        assertEquals("_x", JsText.identifier("_x"));
    }

    @Test
    void javaEscapesFromSukoLiterals() {
        assertEquals("a\n$\"\\ \t", JsText.unescapeJava("a\\n\\u0024\\\"\\\\\\s\\t"));
        assertThrows(IllegalArgumentException.class, () -> JsText.unescapeJava("\\q"));
        assertThrows(IllegalArgumentException.class, () -> JsText.unescapeJava("\\u12"));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test --tests 'io.suko.jte.client.*'`
Expected: FAIL (as classes não existem).

- [ ] **Step 2: `ClientType`, `JavaTypes`, `ClientScope`, `ClientProblem`**

`suko-jte/src/main/java/io/suko/jte/client/ClientType.java`:

```java
package io.suko.jte.client;

import java.util.List;

/**
 * Tipo de um valor no código do cliente (o subconjunto de Java que a 13b traduz para JS). Os primitivos
 * ({@code INT}, {@code DOUBLE}, {@code BOOLEAN}) nunca são null; {@code STRING}, {@link Boxed} e os tipos de
 * referência podem ser.
 */
public sealed interface ClientType {

    enum Scalar implements ClientType { INT, DOUBLE, BOOLEAN, STRING }

    /**
     * {@code NULL}: o literal {@code null}. {@code VOID}: uma chamada sem valor ({@code e.preventDefault()}).
     * {@code EVENT}: o parâmetro de um handler de {@code on:}. {@code UNKNOWN}: tipo de algo que já tem um
     * problema reportado noutro sítio — quem o lê não gera um segundo diagnóstico.
     */
    enum Special implements ClientType { NULL, VOID, EVENT, UNKNOWN }

    /** {@code Integer}, {@code Double}, {@code Boolean}. */
    record Boxed(Scalar of) implements ClientType {
    }

    record ListOf(ClientType element) implements ClientType {
    }

    /** {@code Map<String, V>} (só chaves String; no cliente é um {@code Map} de JS). */
    record MapOf(ClientType value) implements ClientType {
    }

    /** Record Java com forma conhecida (componentes por ordem de declaração). */
    record RecordOf(String name, List<Field> fields) implements ClientType {
        public record Field(String name, ClientType type) {
        }
    }

    /** Tipo Java cuja forma o compilador não conhece (record, enum ou classe vindos de Java; R13). */
    record Named(String name) implements ClientType {
    }

    /** {@code Runnable} (sem parâmetros) ou {@code Consumer<T>} (um). */
    record Callback(List<ClientType> params) implements ClientType {
    }

    /** Fora do subconjunto do cliente; {@code reason} vai para a mensagem. */
    record Unsupported(String javaType, String reason) implements ClientType {
    }

    default boolean nullable() {
        return switch (this) {
            case Scalar s -> s == Scalar.STRING;
            case Special s -> s == Special.NULL || s == Special.UNKNOWN;
            case Boxed b -> true;
            case ListOf l -> true;
            case MapOf m -> true;
            case RecordOf r -> true;
            case Named n -> true;
            case Callback c -> true;
            case Unsupported u -> true;
        };
    }

    /** Como o autor o escreveria em Java (para mensagens). */
    default String java() {
        return switch (this) {
            case Scalar s -> switch (s) {
                case INT -> "int";
                case DOUBLE -> "double";
                case BOOLEAN -> "boolean";
                case STRING -> "String";
            };
            case Special s -> switch (s) {
                case NULL -> "null";
                case VOID -> "void";
                case EVENT -> "evento";
                case UNKNOWN -> "?";
            };
            case Boxed b -> switch (b.of()) {
                case INT -> "Integer";
                case DOUBLE -> "Double";
                case BOOLEAN -> "Boolean";
                case STRING -> "String";
            };
            case ListOf l -> "List<" + l.element().java() + ">";
            case MapOf m -> "Map<String, " + m.value().java() + ">";
            case RecordOf r -> r.name();
            case Named n -> n.name();
            case Callback c -> c.params().isEmpty() ? "Runnable" : "Consumer<" + c.params().get(0).java() + ">";
            case Unsupported u -> u.javaType();
        };
    }
}
```

`suko-jte/src/main/java/io/suko/jte/client/JavaTypes.java`:

```java
package io.suko.jte.client;

import io.suko.lang.ast.Type;

import java.util.List;
import java.util.Optional;

/**
 * De onde vêm as formas dos records Java (R13 do plano 1 da 13b). Em produção é {@link #NONE} até o
 * utilizador decidir a origem dos tipos Java (item 12); os testes usam uma implementação em memória.
 */
@FunctionalInterface
public interface JavaTypes {

    /** Um componente de record: nome e tipo como se escreve em Suko. */
    record Field(String name, Type type) {
    }

    /** Os componentes do record com este nome simples, por ordem; vazio se não é um record conhecido. */
    Optional<List<Field>> record(String simpleName);

    JavaTypes NONE = simpleName -> Optional.empty();
}
```

`suko-jte/src/main/java/io/suko/jte/client/ClientScope.java`:

```java
package io.suko.jte.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Os nomes visíveis numa expressão do cliente e o que são (imutável). */
public final class ClientScope {

    /** STATE/DERIVED leem-se de {@code $s}, PARAM de {@code $p}, LOCAL é uma variável JS (R17). */
    public enum Kind { STATE, DERIVED, PARAM, LOCAL }

    public record Binding(Kind kind, ClientType type) {
    }

    public static final ClientScope EMPTY = new ClientScope(Map.of());

    private final Map<String, Binding> bindings;

    private ClientScope(Map<String, Binding> bindings) {
        this.bindings = bindings;
    }

    public ClientScope with(String name, Kind kind, ClientType type) {
        Map<String, Binding> copy = new LinkedHashMap<>(bindings);
        copy.put(name, new Binding(kind, type));
        return new ClientScope(Collections.unmodifiableMap(copy));
    }

    public Optional<Binding> lookup(String name) {
        return Optional.ofNullable(bindings.get(name));
    }

    public Map<String, Binding> bindings() {
        return bindings;
    }
}
```

`suko-jte/src/main/java/io/suko/jte/client/ClientProblem.java`:

```java
package io.suko.jte.client;

import io.suko.lang.ast.SourceSpan;

/**
 * Um problema numa expressão do cliente, com a localização exata. {@code code} é {@code CLIENT_UNSUPPORTED},
 * {@code TYPE_NOT_INFERRED} ou {@code RESERVED_NAME}; {@code null} quando é consequência de um problema já
 * reportado noutro sítio (não gera diagnóstico: um por sítio).
 */
public record ClientProblem(String code, String message, SourceSpan span) {

    public boolean silent() {
        return code == null;
    }
}
```

- [ ] **Step 3: `ClientTypes` e `JsText`**

`suko-jte/src/main/java/io/suko/jte/client/ClientTypes.java`:

```java
package io.suko.jte.client;

import io.suko.lang.ast.Type;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.suko.jte.client.ClientType.Scalar.BOOLEAN;
import static io.suko.jte.client.ClientType.Scalar.DOUBLE;
import static io.suko.jte.client.ClientType.Scalar.INT;
import static io.suko.jte.client.ClientType.Scalar.STRING;

/** Tipos Suko/Java → {@link ClientType}, e as regras de atribuição e de tipo comum do subconjunto. */
public final class ClientTypes {

    private ClientTypes() {
    }

    public static ClientType of(Type type, JavaTypes types) {
        return of(type, types, new HashSet<>());
    }

    private static ClientType of(Type type, JavaTypes types, Set<String> visiting) {
        String text = text(type);
        if (type.arrayDimensions() > 0) {
            return new ClientType.Unsupported(text, "arrays não fazem parte do subconjunto do cliente (use List)");
        }
        List<Type> args = type.typeArguments();
        switch (type.name()) {
            case "int", "short", "byte" -> {
                return INT;
            }
            case "double" -> {
                return DOUBLE;
            }
            case "boolean" -> {
                return BOOLEAN;
            }
            case "String" -> {
                return STRING;
            }
            case "Integer", "Short", "Byte" -> {
                return new ClientType.Boxed(INT);
            }
            case "Double" -> {
                return new ClientType.Boxed(DOUBLE);
            }
            case "Boolean" -> {
                return new ClientType.Boxed(BOOLEAN);
            }
            case "long", "Long", "char", "Character" -> {
                return new ClientType.Unsupported(text,
                    "long e char ficam fora da v1 (um number de JS perde precisão acima de 2^53)");
            }
            case "float", "Float" -> {
                return new ClientType.Unsupported(text, "float fica fora do cliente (aritmética de 32 bits): use double");
            }
            case "List" -> {
                if (args.size() == 1) {
                    ClientType element = boxed(of(args.get(0), types, visiting));
                    return element instanceof ClientType.Unsupported u ? u : new ClientType.ListOf(element);
                }
            }
            case "Map" -> {
                if (args.size() == 2 && isPlainString(args.get(0))) {
                    ClientType value = boxed(of(args.get(1), types, visiting));
                    return value instanceof ClientType.Unsupported u ? u : new ClientType.MapOf(value);
                }
                return new ClientType.Unsupported(text, "no cliente só Map<String, V> é suportado");
            }
            case "Consumer" -> {
                if (args.size() == 1) {
                    ClientType param = boxed(of(args.get(0), types, visiting));
                    return param instanceof ClientType.Unsupported u ? u : new ClientType.Callback(List.of(param));
                }
            }
            case "Runnable" -> {
                if (args.isEmpty()) {
                    return new ClientType.Callback(List.of());
                }
            }
            case "Object", "Number", "Component", "Content", "Function", "Supplier", "BiConsumer", "Set",
                 "Optional", "var" -> {
                return new ClientType.Unsupported(text, "tipo fora do subconjunto do cliente");
            }
            default -> {
                if (!args.isEmpty()) {
                    return new ClientType.Unsupported(text, "tipos genéricos de Java não fazem parte do subconjunto do cliente");
                }
                Optional<List<JavaTypes.Field>> fields = types.record(type.name());
                if (fields.isEmpty()) {
                    return new ClientType.Named(type.name());
                }
                if (!visiting.add(type.name())) {
                    return new ClientType.Unsupported(text, "records recursivos não são suportados no cliente");
                }
                List<ClientType.RecordOf.Field> out = new ArrayList<>();
                for (JavaTypes.Field f : fields.get()) {
                    ClientType fieldType = of(f.type(), types, visiting);
                    if (fieldType instanceof ClientType.Unsupported u) {
                        visiting.remove(type.name());
                        return new ClientType.Unsupported(text, "o componente '" + f.name() + "' (" + u.javaType() + "): " + u.reason());
                    }
                    out.add(new ClientType.RecordOf.Field(f.name(), fieldType));
                }
                visiting.remove(type.name());
                return new ClientType.RecordOf(type.name(), List.copyOf(out));
            }
        }
        return new ClientType.Unsupported(text, "tipo fora do subconjunto do cliente");
    }

    /** Elementos de coleções e parâmetros de tipo são referências em Java: {@code List<int>} lê-se {@code List<Integer>}. */
    private static ClientType boxed(ClientType t) {
        return t instanceof ClientType.Scalar s && s != STRING ? new ClientType.Boxed(s) : t;
    }

    private static boolean isPlainString(Type t) {
        return t.name().equals("String") && t.typeArguments().isEmpty() && t.arrayDimensions() == 0;
    }

    public static String text(Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(text(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        return sb.append("[]".repeat(type.arrayDimensions())).toString();
    }

    /** O texto de um tipo como o {@code ParamInfo.type()} o guarda (ex.: {@code Map<String, List<Integer>>}). */
    public static Type parse(String text) {
        String s = text.replace(" ", "");
        int[] pos = {0};
        Type type = parse(s, pos);
        if (pos[0] != s.length()) {
            throw new IllegalArgumentException("tipo mal formado: " + text);
        }
        return type;
    }

    private static Type parse(String s, int[] pos) {
        int start = pos[0];
        while (pos[0] < s.length() && (Character.isLetterOrDigit(s.charAt(pos[0])) || s.charAt(pos[0]) == '_'
                || s.charAt(pos[0]) == '.')) {
            pos[0]++;
        }
        String name = s.substring(start, pos[0]);
        List<Type> args = new ArrayList<>();
        if (pos[0] < s.length() && s.charAt(pos[0]) == '<') {
            pos[0]++;
            args.add(parse(s, pos));
            while (pos[0] < s.length() && s.charAt(pos[0]) == ',') {
                pos[0]++;
                args.add(parse(s, pos));
            }
            if (pos[0] >= s.length() || s.charAt(pos[0]) != '>') {
                throw new IllegalArgumentException("tipo mal formado: " + s);
            }
            pos[0]++;
        }
        int dims = 0;
        while (pos[0] + 1 < s.length() && s.charAt(pos[0]) == '[' && s.charAt(pos[0] + 1) == ']') {
            dims++;
            pos[0] += 2;
        }
        return new Type(name, List.copyOf(args), dims);
    }

    /** Atribuição de Java (com boxing/unboxing e alargamento int → double); as coleções são invariantes. */
    public static boolean assignable(ClientType from, ClientType to) {
        if (from.equals(to)) {
            return true;
        }
        if (from == ClientType.Special.NULL) {
            return to.nullable() && !(to instanceof ClientType.Unsupported) && to != ClientType.Special.UNKNOWN;
        }
        if (to == INT) {
            return from.equals(new ClientType.Boxed(INT));
        }
        if (to == DOUBLE) {
            return from == INT || from.equals(new ClientType.Boxed(INT)) || from.equals(new ClientType.Boxed(DOUBLE));
        }
        if (to == BOOLEAN) {
            return from.equals(new ClientType.Boxed(BOOLEAN));
        }
        if (to instanceof ClientType.Boxed b) {
            return from == b.of();
        }
        return false;
    }

    /** O tipo de {@code c ? a : b} (promoção numérica de Java), ou {@code null} se não há tipo comum no cliente. */
    public static ClientType unify(ClientType a, ClientType b) {
        if (a.equals(b)) {
            return a;
        }
        if (a == ClientType.Special.NULL) {
            return nullableOf(b);
        }
        if (b == ClientType.Special.NULL) {
            return nullableOf(a);
        }
        ClientType.Scalar pa = primitive(a);
        ClientType.Scalar pb = primitive(b);
        if (pa != null && pb != null) {
            boolean na = pa == INT || pa == DOUBLE;
            boolean nb = pb == INT || pb == DOUBLE;
            if (na && nb) {
                return pa == INT && pb == INT ? INT : DOUBLE;
            }
            if (pa == BOOLEAN && pb == BOOLEAN) {
                return BOOLEAN;
            }
        }
        return null;
    }

    /** INT/DOUBLE/BOOLEAN, também por detrás de um {@link ClientType.Boxed}; {@code null} para o resto. */
    public static ClientType.Scalar primitive(ClientType t) {
        if (t instanceof ClientType.Scalar s && s != STRING) {
            return s;
        }
        if (t instanceof ClientType.Boxed b && b.of() != STRING) {
            return b.of();
        }
        return null;
    }

    private static ClientType nullableOf(ClientType t) {
        if (t instanceof ClientType.Scalar s && s != STRING) {
            return new ClientType.Boxed(s);
        }
        return t.nullable() && !(t instanceof ClientType.Unsupported) ? t : null;
    }
}
```

`suko-jte/src/main/java/io/suko/jte/client/JsText.java`:

```java
package io.suko.jte.client;

import java.util.Locale;
import java.util.Set;

/** Texto JS seguro: literais de string só em ASCII e nomes de variáveis que não colidem com JS. */
public final class JsText {

    /** Palavras reservadas de JS e globais que o código gerado ou o browser usam (spec 13b, "Tradução Java → JS"). */
    private static final Set<String> RESERVED = Set.of(
        "await", "break", "case", "catch", "class", "const", "continue", "debugger", "default", "delete", "do",
        "else", "enum", "export", "extends", "false", "finally", "for", "function", "if", "implements", "import",
        "in", "instanceof", "interface", "let", "new", "null", "package", "private", "protected", "public",
        "return", "static", "super", "switch", "this", "throw", "true", "try", "typeof", "var", "void", "while",
        "with", "yield", "async", "of", "get", "set",
        "arguments", "eval", "undefined", "NaN", "Infinity", "globalThis", "window", "self", "document", "top",
        "parent", "frames", "location", "name", "Math", "Object", "Array", "String", "Number", "Boolean", "JSON",
        "Map", "Symbol", "Function", "Reflect", "Proxy", "Promise", "console");

    private JsText() {
    }

    /**
     * Literal de string JS com todo o carácter fora do ASCII imprimível (e {@code < > & '}) escrito como
     * {@code \\uXXXX}: o ficheiro fica ASCII, um surrogate isolado sobrevive e o texto nunca fecha um
     * {@code <script>} nem forma {@code <!--}.
     */
    public static String string(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c > 0x7e || c == '<' || c == '>' || c == '&' || c == '\'') {
                        sb.append("\\u").append(String.format(Locale.ROOT, "%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    /** Nome de uma variável local no JS gerado: {@code $_nome} se colide com JS (um identificador Suko nunca tem {@code $}). */
    public static String identifier(String name) {
        return RESERVED.contains(name) ? "$_" + name : name;
    }

    /**
     * O valor real de um literal Suko ({@code StringPart.Literal.javaEscapedText()}): os escapes de Java que o
     * construtor do AST deixa passar ({@code \\n}, {@code \\"}, {@code \\u0024} vindo de {@code \\$}, ...).
     *
     * @throws IllegalArgumentException escape que o cliente não suporta
     */
    public static String unescapeJava(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i + 1 >= s.length()) {
                throw new IllegalArgumentException("'\\' no fim do literal");
            }
            char n = s.charAt(++i);
            switch (n) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 's' -> sb.append(' ');
                case '"' -> sb.append('"');
                case '\'' -> sb.append('\'');
                case '\\' -> sb.append('\\');
                case 'u' -> {
                    if (i + 4 >= s.length()) {
                        throw new IllegalArgumentException("escape \\u incompleto");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> throw new IllegalArgumentException("o escape '\\" + n + "' não é suportado no cliente");
            }
        }
        return sb.toString();
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test --tests 'io.suko.jte.client.*'`
Expected: PASS (`ClientTypesTest`, `JsTextTest`).

- [ ] **Step 4: `prelude.js` e `ClientPrelude`**

`suko-jte/src/main/resources/io/suko/jte/client/prelude.js`:

```js
// Suko — funções de apoio do JS gerado (subprojeto 13b). Reproduzem a semântica de Java que o tradutor
// garante: null visível (TypeError, como a NullPointerException), int de 32 bits, divisão por zero
// (RangeError, como a ArithmeticException), Double.toString, String.trim, índices verificados. Sem DOM,
// sem estado global, sem dependências. O plano 2 junta este módulo ao suko.js gerado; não editar à mão.

export function nn(v) {
  if (v === null || v === undefined) {
    throw new TypeError("null");
  }
  return v;
}

export function idiv(a, b) {
  if (b === 0) {
    throw new RangeError("/ by zero");
  }
  return Math.trunc(a / b) | 0;
}

export function imod(a, b) {
  if (b === 0) {
    throw new RangeError("/ by zero");
  }
  return (a % b) | 0;
}

// Double.toString do JDK (>= 19): os dígitos mais curtos (os de Number.prototype.toString), com pelo menos
// dois quando o mais curto tem um e há um de dois mais próximo; notação simples em [1e-3, 1e7), senão
// d.dddE<n>.
export function dstr(d) {
  if (Number.isNaN(d)) {
    return "NaN";
  }
  if (d === Infinity) {
    return "Infinity";
  }
  if (d === -Infinity) {
    return "-Infinity";
  }
  if (d === 0) {
    return Object.is(d, -0) ? "-0.0" : "0.0";
  }
  const negative = d < 0;
  const a = negative ? -d : d;
  const [mantissa, exponent] = a.toExponential().split("e");
  let digits = mantissa.replace(".", "");
  let exp = Number(exponent);
  if (digits.length === 1) {
    const two = Number(a.toPrecision(2));
    if (two === a) {
      const [m2, e2] = two.toExponential(1).split("e");
      const d2 = m2.replace(".", "");
      if (d2[1] !== "0") {
        digits = d2;
        exp = Number(e2);
      }
    }
  }
  digits = digits.replace(/0+$/, "");
  if (digits === "") {
    digits = "0";
  }
  let out;
  if (a >= 1e-3 && a < 1e7) {
    const point = exp + 1;
    if (point <= 0) {
      out = "0." + "0".repeat(-point) + digits;
    } else if (point >= digits.length) {
      out = digits + "0".repeat(point - digits.length) + ".0";
    } else {
      out = digits.slice(0, point) + "." + digits.slice(point);
    }
  } else {
    out = digits[0] + "." + (digits.length > 1 ? digits.slice(1) : "0") + "E" + exp;
  }
  return (negative ? "-" : "") + out;
}

// Concatenação de Java com um double (null → "null").
export function dcat(v) {
  return v === null || v === undefined ? "null" : dstr(v);
}

// Texto de um nó, como o JTE o escreve: null → "".
export function text(v) {
  return v === null || v === undefined ? "" : String(v);
}

export function dtext(v) {
  return v === null || v === undefined ? "" : dstr(v);
}

// String.valueOf de Java para a entrada dos portes do SukoSafe, mas com null → null (o porte decide).
export function sval(v) {
  return v === null || v === undefined ? null : String(v);
}

export function dval(v) {
  return v === null || v === undefined ? null : dstr(v);
}

// String.trim de Java: tira as unidades <= U+0020 nas pontas (nunca String.prototype.trim()).
export function trim(s) {
  nn(s);
  let start = 0;
  let end = s.length;
  while (start < end && s.charCodeAt(start) <= 0x20) {
    start++;
  }
  while (end > start && s.charCodeAt(end - 1) <= 0x20) {
    end--;
  }
  return s.substring(start, end);
}

export function contains(s, t) {
  return nn(s).includes(nn(t));
}

export function startsWith(s, t) {
  return nn(s).startsWith(nn(t));
}

export function endsWith(s, t) {
  return nn(s).endsWith(nn(t));
}

export function equals(s, o) {
  return nn(s) === o;
}

function index(list, i) {
  nn(list);
  if (!Number.isInteger(i) || i < 0 || i >= list.length) {
    throw new RangeError("Index " + i + " out of bounds for length " + list.length);
  }
  return i;
}

export function get(list, i) {
  return list[index(list, i)];
}

export function removeAt(list, i) {
  return list.splice(index(list, i), 1)[0];
}

export function setAt(list, i, v) {
  const at = index(list, i);
  const old = list[at];
  list[at] = v;
  return old;
}

export function clear(list) {
  nn(list).length = 0;
}

export function mget(m, k) {
  nn(m);
  return m.has(k) ? m.get(k) : null;
}

export function mhas(m, k) {
  return nn(m).has(k);
}

// Acessor de record: só propriedades próprias (constructor, __proto__ e toString nunca vêm do protótipo).
export function field(o, k) {
  nn(o);
  if (!Object.hasOwn(o, k)) {
    throw new TypeError("componente de record em falta: " + k);
  }
  return o[k];
}

// new Record(...): objeto sem protótipo e imutável, como um record.
export function rec(names, values) {
  const o = Object.create(null);
  for (let i = 0; i < names.length; i++) {
    o[names[i]] = values[i];
  }
  return Object.freeze(o);
}

export function evValue(e) {
  const t = nn(e).target;
  return t === null || t === undefined || t.value === null || t.value === undefined ? null : String(t.value);
}

export function evChecked(e) {
  const t = nn(e).target;
  return t !== null && t !== undefined && t.checked === true;
}

export function evKey(e) {
  return typeof nn(e).key === "string" ? e.key : null;
}

export function evPrevent(e) {
  nn(e).preventDefault();
}
```

`suko-jte/src/main/java/io/suko/jte/client/ClientPrelude.java`:

```java
package io.suko.jte.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** O texto de {@code prelude.js} (recurso do suko-jte): as funções {@code $suko.*} que o JS traduzido chama. */
public final class ClientPrelude {

    public static final String RESOURCE = "/io/suko/jte/client/prelude.js";

    private ClientPrelude() {
    }

    public static String source() {
        try (InputStream in = Objects.requireNonNull(ClientPrelude.class.getResourceAsStream(RESOURCE),
                "recurso em falta: " + RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

- [ ] **Step 5: Executor Node e teste diferencial do prelúdio (falham sem Node)**

`suko-core/src/test/java/io/suko/lang/client/NodeRunner.java`:

```java
package io.suko.lang.client;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * Corre um módulo ES no Node ({@code node --input-type=module}, lido do stdin) e devolve o stdout. Sem
 * {@code node} o teste FALHA (spec 13b, "Testes": um teste saltado em silêncio foi a lacuna (i) do item 14).
 * {@code SUKO_NODE} escolhe outro executável.
 */
final class NodeRunner {

    private NodeRunner() {
    }

    static String node() {
        String explicit = System.getenv("SUKO_NODE");
        return explicit == null || explicit.isBlank() ? "node" : explicit;
    }

    static String run(String module) throws Exception {
        return run(node(), module);
    }

    static String run(String node, String module) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(node, "--input-type=module").redirectErrorStream(true);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new AssertionError("o Node não arranca ('" + node + "'): os testes do cliente da 13b precisam do Node 18+"
                + " no PATH (ou SUKO_NODE). " + e.getMessage(), e);
        }
        try (OutputStream in = process.getOutputStream()) {
            in.write(module.getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("o Node não terminou em 120 s");
        }
        if (process.exitValue() != 0) {
            throw new AssertionError("o Node saiu com " + process.exitValue() + ":\n" + out + "\n--- módulo ---\n" + module);
        }
        return out;
    }

    /** URL {@code data:} para importar um módulo sem ficheiros temporários. */
    static String dataUrl(String js) {
        return "data:text/javascript;base64," + Base64.getEncoder().encodeToString(js.getBytes(StandardCharsets.UTF_8));
    }
}
```

`suko-core/src/test/java/io/suko/lang/client/NodeRunnerTest.java`:

```java
package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeRunnerTest {

    @Test
    void runsAModule() throws Exception {
        assertEquals("ok 3", NodeRunner.run("console.log('ok', 1 + 2);").trim());
    }

    @Test
    void aMissingNodeFailsInsteadOfSkipping() {
        AssertionError e = assertThrows(AssertionError.class,
            () -> NodeRunner.run("/nonexistent/suko-node", "console.log(1);"));
        assertTrue(e.getMessage().contains("Node 18+"), e.getMessage());
    }
}
```

`suko-core/src/test/java/io/suko/lang/client/PreludeDifferentialTest.java`:

```java
package io.suko.lang.client;

import io.suko.jte.client.ClientPrelude;
import io.suko.jte.client.JsText;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** As funções do prelude.js dão, no Node, exatamente o que o Java dá (o Java é o oráculo, nunca valores à mão). */
class PreludeDifferentialTest {

    static final String IMPORT = "import * as $suko from \"" + NodeRunner.dataUrl(ClientPrelude.source()) + "\";\n";

    @Test
    void preludeHasNoDomOrGlobalAccess() {
        String js = ClientPrelude.source();
        for (String forbidden : new String[] {"document", "window", "innerHTML", "eval(", "Function(", "globalThis",
                                              "localStorage", "fetch("}) {
            assertFalse(js.contains(forbidden), forbidden);
        }
    }

    @Test
    void doubleToStringMatchesJava() throws Exception {
        List<Double> values = new ArrayList<>(List.of(0.0, -0.0, 1.0, -1.0, 0.1, 0.3, 0.1 + 0.2, 100.0, 1e7, 9999999.0,
            9999999.999, 1e-3, 0.00099, 1e23, 2e22, 1e21, 123456789.0, 1.5e300, Double.MIN_VALUE, Double.MAX_VALUE,
            Double.MIN_NORMAL, 1.0E-5, 2.0, 0.5, 1 / 3.0, 2 / 3.0, 4.35, 1e-7, 123.456, 1e16, 9007199254740993.0,
            Math.PI, Math.E, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY));
        Random random = new Random(42);
        for (int i = 0; i < 20000; i++) {
            double d = Double.longBitsToDouble(random.nextLong());
            if (!Double.isNaN(d)) {
                values.add(d);
            }
            values.add(random.nextDouble() * Math.pow(10, random.nextInt(20) - 8));
        }
        String hex = values.stream().map(d -> "\"" + Long.toHexString(Double.doubleToRawLongBits(d)) + "\"")
            .collect(Collectors.joining(","));
        String module = IMPORT + """
            const dv = new DataView(new ArrayBuffer(8));
            for (const h of [%s]) {
              dv.setBigUint64(0, BigInt("0x" + h));
              console.log($suko.dstr(dv.getFloat64(0)));
            }
            """.formatted(hex);
        List<String> js = NodeRunner.run(module).lines().toList();
        assertEquals(values.size(), js.size());
        for (int i = 0; i < values.size(); i++) {
            assertEquals(Double.toString(values.get(i)), js.get(i), "Double.toString(" + values.get(i) + ")");
        }
    }

    @Test
    void trimMatchesJavaNotJavaScript() throws Exception {
        List<String> values = List.of(" a ", "\u0001a\u0001", "\u00a0a\u00a0", "\ufeffa", "\u2028a\u2028", "\t\n a b \r",
            "", " ", "\u0000x\u001f", "a\u0020\u0021");
        String module = IMPORT + "for (const s of [" + values.stream().map(JsText::string).collect(Collectors.joining(","))
            + "]) { const t = $suko.trim(s); console.log(Array.from({length: t.length}, (_, i) => t.charCodeAt(i).toString(16)).join(\".\")); }\n";
        List<String> js = NodeRunner.run(module).lines().toList();
        for (int i = 0; i < values.size(); i++) {
            String expected = values.get(i).trim().chars().mapToObj(Integer::toHexString).collect(Collectors.joining("."));
            assertEquals(expected, js.get(i), "trim de #" + i);
        }
    }

    @Test
    void intDivisionAndRemainderMatchJava() throws Exception {
        int[][] pairs = {{7, 2}, {-7, 2}, {7, -2}, {-7, -2}, {0, 5}, {-4, 2}, {5, 0}, {Integer.MIN_VALUE, -1},
                         {Integer.MAX_VALUE, 1}, {Integer.MIN_VALUE, 7}};
        StringBuilder module = new StringBuilder(IMPORT);
        module.append("const r = (f) => { try { return String(f()); } catch (e) { return e.constructor.name; } };\n");
        for (int[] p : pairs) {
            module.append("console.log(r(() => $suko.idiv(").append(p[0]).append(", ").append(p[1]).append(")) + \" \" + r(() => $suko.imod(")
                .append(p[0]).append(", ").append(p[1]).append(")));\n");
        }
        List<String> js = NodeRunner.run(module.toString()).lines().toList();
        for (int i = 0; i < pairs.length; i++) {
            int a = pairs[i][0];
            int b = pairs[i][1];
            String div = b == 0 ? "RangeError" : String.valueOf(a / b);
            String mod = b == 0 ? "RangeError" : String.valueOf(a % b);
            assertEquals(div + " " + mod, js.get(i), a + " / " + b);
        }
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.client.*'`
Expected: PASS com o Node no `PATH` (`NodeRunnerTest`, `PreludeDifferentialTest`); sem Node, `NodeRunnerTest.runsAModule` e os testes diferenciais FALHAM com a mensagem "Node 18+" (não são saltados).

- [ ] **Step 6: Commit**

```bash
git status
git add suko-jte/src/main/java/io/suko/jte/client suko-jte/src/main/resources/io/suko/jte/client/prelude.js \
  suko-jte/src/test/java/io/suko/jte/client suko-core/src/test/java/io/suko/lang/client
git commit -m "feat(13b): tipos do cliente, prelude.js com a semântica de Java e testes diferenciais no Node"
```

---

### Task 6: Tradutor Java → JS — expressões, texto e conversões

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/client/JsTranslator.java`
- Test (create): `suko-core/src/test/java/io/suko/lang/client/ClientTestSupport.java`, `suko-core/src/test/java/io/suko/lang/client/JsTranslatorTest.java`, `suko-core/src/test/java/io/suko/lang/client/TranslatorDifferentialTest.java`

**Interfaces:**
- Consumes: `ClientType`, `ClientTypes`, `ClientScope`, `JavaTypes`, `ClientProblem`, `JsText`, `prelude.js`, `NodeRunner` (Task 5); `Expr` e `ExprNames` (Task 1); o parser da Task 2 (nos testes).
- Produces (o plano 2 e as Tasks 7–9 dependem deles):
  - `new JsTranslator(ClientScope scope, JavaTypes types)`;
  - `sealed interface JsTranslator.Result` com `record Ok(String js, ClientType type)` e `record Err(ClientProblem problem)`;
  - `Result expression(Expr)` (valor), `Result text(Expr)` (nó de texto, semântica do JTE: `null` → `""`), `Result javaString(Expr)` (`String.valueOf` de Java com `null` → `null`, entrada dos portes do `SukoSafe`, p.ex. o `cssValue` de `style="width: ${v}%"`);
  - `static String access(String name, ClientScope.Kind kind)` (`$s.x`, `$p.x` ou o nome local);
  - `JsTranslator.CLIENT_UNSUPPORTED`;
  - o JS segue o R17 (`$s`, `$p`, `$suko`); `RESERVED_NAME` para `trusted*(...)` (Q6).

O subconjunto e a semântica são os da spec ("Expressões no cliente") com os rulings R13, R14 e R16. A primeira subexpressão fora do subconjunto pára a tradução e dá **um** problema com o seu próprio span.

- [ ] **Step 1: Apoio dos testes e testes (falham)**

`suko-core/src/test/java/io/suko/lang/client/ClientTestSupport.java` (o harness diferencial: cada caso é texto Suko e Java ao mesmo tempo; o Java compilado pelo javac é o oráculo; os handlers entram na Task 7):

```java
package io.suko.lang.client;

import io.suko.jte.client.ClientPrelude;
import io.suko.jte.client.ClientScope;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.ClientTypes;
import io.suko.jte.client.JavaTypes;
import io.suko.jte.client.JsText;
import io.suko.jte.client.JsTranslator;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.StringWriter;
import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Apoio dos testes do tradutor (13b): parse de expressões e lambdas Suko pelo parser real, âmbitos com tipos, e
 * o harness diferencial — cada caso é escrito uma vez (o texto é Suko e Java ao mesmo tempo), corre compilado
 * pelo javac e traduzido no Node, e as duas codificações têm de ser iguais. O Java é o oráculo.
 */
final class ClientTestSupport {

    private ClientTestSupport() {
    }

    /** O record dos testes, com a mesma forma no Java gerado ({@code SukoDiff.Todo}) e no tradutor. */
    static final JavaTypes TYPES = name -> name.equals("Todo")
        ? Optional.of(List.of(new JavaTypes.Field("title", ClientTypes.parse("String")),
            new JavaTypes.Field("done", ClientTypes.parse("boolean"))))
        : Optional.empty();

    /** Uma variável de um caso: nome, tipo (Suko = Java), o que é no cliente e o inicializador em Java. */
    record Var(String name, String type, ClientScope.Kind kind, String javaInit) {
    }

    static Var state(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.STATE, javaInit);
    }

    static Var derived(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.DERIVED, javaInit);
    }

    static Var param(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.PARAM, javaInit);
    }

    static Var local(String type, String name, String javaInit) {
        return new Var(name, type, ClientScope.Kind.LOCAL, javaInit);
    }

    static ClientScope scope(List<Var> vars) {
        ClientScope s = ClientScope.EMPTY;
        for (Var v : vars) {
            s = s.with(v.name(), v.kind(), ClientTypes.of(ClientTypes.parse(v.type()), TYPES));
        }
        return s;
    }

    static ComponentDecl parse(String sk) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(sk))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(sk);
        SukoFile file = builder.build(parser.compilationUnit());
        if (parser.getNumberOfSyntaxErrors() > 0 || !builder.syntaxIssues().isEmpty()) {
            throw new IllegalArgumentException("não faz parse: " + sk + " " + builder.syntaxIssues());
        }
        return file.components().get(0);
    }

    /** Uma expressão Suko, pelo parser real. */
    static Expr expr(String source) {
        return ((Statement.Interpolation) parse("component T() { ${" + source + "} }").body().get(0)).expr();
    }

    /** Uma lambda Suko, pelo parser real (como valor de on:click). */
    static Expr.LambdaExpr lambda(String source) {
        Statement.HtmlElement b = (Statement.HtmlElement) parse("reactive component T() { <b on:click=${" + source + "}></b> }")
            .body().get(0);
        return (Expr.LambdaExpr) b.directives().get(0).value();
    }

    // ------------------------------------------------------------------ harness diferencial

    /** Um caso: as variáveis e o texto de uma expressão, ou as instruções de um handler (sem chavetas). */
    record Case(List<Var> vars, String source, boolean handler) {
    }

    static Case expression(String source, List<Var> vars) {
        return new Case(vars, source, false);
    }

    static Case handler(String statements, List<Var> vars) {
        return new Case(vars, statements, true);
    }

    /** Uma linha por caso: o que o Java deu e o que o JS deu (variáveis no fim e o resultado, codificados). */
    record Outcome(Case c, String java, String js) {
    }

    static final String JS_HELPERS = """
        class $Thrown { constructor(n) { this.n = n; } }
        function $run(f) {
          try { return f(); }
          catch (e) { return new $Thrown(e instanceof TypeError ? "TypeError" : e instanceof RangeError ? "RangeError" : "Error:" + e); }
        }
        function $dbl(h) { const v = new DataView(new ArrayBuffer(8)); v.setBigUint64(0, BigInt("0x" + h)); return v.getFloat64(0); }
        function $enc(v, t) {
          if (v instanceof $Thrown) return "throw:" + v.n;
          if (v === null || v === undefined) return "null";
          switch (t[0]) {
            case "i": case "b": return String(v);
            case "d": { if (Number.isNaN(v)) return "NaN"; const x = new DataView(new ArrayBuffer(8)); x.setFloat64(0, v); return x.getBigUint64(0).toString(16); }
            case "s": return "s:" + Array.from({length: v.length}, (_, i) => v.charCodeAt(i).toString(16)).join(".");
            case "l": return "[" + v.map(x => $enc(x, t.slice(1))).join(",") + "]";
            case "m": return "{" + [...v.keys()].sort().map(k => $enc(k, "s") + "=" + $enc(v.get(k), t.slice(1))).join(",") + "}";
            case "r": return "R";
          }
          return "?" + t;
        }
        """;

    static List<Outcome> run(List<Case> cases) throws Exception {
        StringBuilder java = new StringBuilder("import java.util.*;\npublic class SukoDiff {\n")
            .append("  public record Todo(String title, boolean done) {}\n");
        for (int i = 0; i < cases.size(); i++) {
            Case c = cases.get(i);
            StringBuilder decls = new StringBuilder();
            StringBuilder names = new StringBuilder();
            for (Var v : c.vars()) {
                decls.append("    ").append(v.type()).append(' ').append(v.name()).append(" = ").append(v.javaInit()).append(";\n");
                names.append(v.name()).append(", ");
            }
            java.append("  public static Object[] i").append(i).append("() {\n").append(decls)
                .append("    return new Object[] {").append(names).append("};\n  }\n");
            java.append("  public static Object[] c").append(i).append("() {\n").append(decls)
                .append("    Object $r = null;\n    try {\n      ")
                .append(c.handler() ? c.source() : "$r = (" + c.source() + ");")
                .append("\n    } catch (RuntimeException $x) {\n      $r = new Object[] {\"throw\", $x.getClass().getName()};\n    }\n")
                .append("    return new Object[] {").append(names).append("$r};\n  }\n");
        }
        String source = java.append("}\n").toString();
        Path out = Files.createTempDirectory("suko-diff");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(URI.create("string:///SukoDiff.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        StringWriter errors = new StringWriter();
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            if (!javac.getTask(errors, fm, null, List.of("-d", out.toString(), "--release", "21", "-nowarn"), null, List.of(file)).call()) {
                throw new AssertionError("o Java dos casos não compila:\n" + errors + "\n" + source);
            }
        }

        List<String> javaLines = new ArrayList<>();
        StringBuilder js = new StringBuilder("import * as $suko from \"" + NodeRunner.dataUrl(ClientPrelude.source()) + "\";\n")
            .append(JS_HELPERS);
        try (URLClassLoader loader = new URLClassLoader(new URL[] {out.toUri().toURL()})) {
            Class<?> type = loader.loadClass("SukoDiff");
            for (int i = 0; i < cases.size(); i++) {
                Case c = cases.get(i);
                ClientScope scope = scope(c.vars());
                Object[] initial = (Object[]) type.getMethod("i" + i).invoke(null);
                Object[] fin = (Object[]) type.getMethod("c" + i).invoke(null);

                JsTranslator translator = new JsTranslator(scope, TYPES);
                JsTranslator.Result result = translator.expression(expr(c.source()));
                if (!(result instanceof JsTranslator.Ok ok)) {
                    throw new AssertionError("não traduz: " + c.source() + " -> " + ((JsTranslator.Err) result).problem());
                }
                ClientType resultType = c.handler() ? ClientType.Scalar.STRING : ok.type();

                List<String> javaEnc = new ArrayList<>();
                List<String> jsEnc = new ArrayList<>();
                js.append("{\n  const $s = Object.create(null);\n  const $p = Object.create(null);\n  const $u = (n) => {};\n");
                for (int k = 0; k < c.vars().size(); k++) {
                    Var v = c.vars().get(k);
                    ClientType t = scope.lookup(v.name()).orElseThrow().type();
                    String access = JsTranslator.access(v.name(), v.kind());
                    js.append(v.kind() == ClientScope.Kind.LOCAL ? "  const " + access : "  " + access)
                        .append(" = ").append(literal(initial[k], t)).append(";\n");
                    javaEnc.add(encode(fin[k], t));
                    jsEnc.add("$enc(" + access + ", \"" + tag(t) + "\")");
                }
                js.append(c.handler()
                    ? "  const $r = $run(() => { (" + ok.js() + ")(); return null; });\n"
                    : "  const $r = $run(() => " + ok.js() + ");\n");
                javaEnc.add(encode(fin[c.vars().size()], resultType));
                jsEnc.add("$enc($r, \"" + tag(resultType) + "\")");
                js.append("  console.log([").append(String.join(", ", jsEnc)).append("].join(\"\\t\"));\n}\n");
                javaLines.add(String.join("\t", javaEnc));
            }
        }
        List<String> jsLines = NodeRunner.run(js.toString()).lines().toList();
        if (jsLines.size() != cases.size()) {
            throw new AssertionError("o Node devolveu " + jsLines.size() + " linhas para " + cases.size() + " casos:\n" + jsLines);
        }
        List<Outcome> outcomes = new ArrayList<>();
        for (int i = 0; i < cases.size(); i++) {
            outcomes.add(new Outcome(cases.get(i), javaLines.get(i), jsLines.get(i)));
        }
        return outcomes;
    }

    static String tag(ClientType t) {
        return switch (t) {
            case ClientType.Scalar s -> switch (s) {
                case INT -> "i";
                case DOUBLE -> "d";
                case BOOLEAN -> "b";
                case STRING -> "s";
            };
            case ClientType.Boxed b -> tag(b.of());
            case ClientType.ListOf l -> "l" + tag(l.element());
            case ClientType.MapOf m -> "m" + tag(m.value());
            default -> "r";
        };
    }

    /** A mesma codificação que {@code $enc} faz em JS. */
    static String encode(Object v, ClientType t) throws Exception {
        if (v instanceof Object[] a && a.length == 2 && "throw".equals(a[0])) {
            return "throw:" + jsError((String) a[1]);
        }
        if (v == null) {
            return "null";
        }
        String tag = tag(t);
        return switch (tag.charAt(0)) {
            case 'i', 'b' -> v.toString();
            case 'd' -> Double.isNaN((Double) v) ? "NaN" : Long.toHexString(Double.doubleToRawLongBits((Double) v));
            case 's' -> "s:" + ((String) v).chars().mapToObj(Integer::toHexString).collect(Collectors.joining("."));
            case 'l' -> {
                ClientType element = ((ClientType.ListOf) t).element();
                List<String> parts = new ArrayList<>();
                for (Object x : (List<?>) v) {
                    parts.add(encode(x, element));
                }
                yield "[" + String.join(",", parts) + "]";
            }
            case 'm' -> {
                ClientType value = ((ClientType.MapOf) t).value();
                List<String> parts = new ArrayList<>();
                for (Map.Entry<?, ?> e : new TreeMap<>((Map<?, ?>) v).entrySet()) {
                    parts.add(encode(e.getKey(), ClientType.Scalar.STRING) + "=" + encode(e.getValue(), value));
                }
                yield "{" + String.join(",", parts) + "}";
            }
            default -> "R";
        };
    }

    /** A exceção de Java e o erro de JS que a spec faz corresponder (NPE → TypeError; aritmética e índices → RangeError). */
    static String jsError(String javaClass) throws Exception {
        Class<?> type = Class.forName(javaClass);
        if (NullPointerException.class.isAssignableFrom(type)) {
            return "TypeError";
        }
        if (ArithmeticException.class.isAssignableFrom(type) || IndexOutOfBoundsException.class.isAssignableFrom(type)) {
            return "RangeError";
        }
        return "Error:" + type.getSimpleName();
    }

    /** O valor inicial de Java como literal JS (o mesmo valor nos dois lados). */
    static String literal(Object v, ClientType t) throws Exception {
        if (v == null) {
            return "null";
        }
        return switch (t) {
            case ClientType.Scalar s when s == ClientType.Scalar.DOUBLE -> dbl((Double) v);
            case ClientType.Scalar s when s == ClientType.Scalar.STRING -> JsText.string((String) v);
            case ClientType.Scalar s -> v.toString();
            case ClientType.Boxed b -> literal(v, b.of());
            case ClientType.ListOf l -> {
                List<String> parts = new ArrayList<>();
                for (Object x : (List<?>) v) {
                    parts.add(literal(x, l.element()));
                }
                yield "[" + String.join(", ", parts) + "]";
            }
            case ClientType.MapOf m -> {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    parts.add("[" + JsText.string((String) e.getKey()) + ", " + literal(e.getValue(), m.value()) + "]");
                }
                yield "new Map([" + String.join(", ", parts) + "])";
            }
            case ClientType.RecordOf r -> {
                List<String> names = new ArrayList<>();
                List<String> values = new ArrayList<>();
                RecordComponent[] components = v.getClass().getRecordComponents();
                for (int i = 0; i < components.length; i++) {
                    names.add(JsText.string(components[i].getName()));
                    values.add(literal(components[i].getAccessor().invoke(v), r.fields().get(i).type()));
                }
                yield "$suko.rec([" + String.join(", ", names) + "], [" + String.join(", ", values) + "])";
            }
            default -> throw new IllegalArgumentException("sem literal para " + t);
        };
    }

    private static String dbl(Double d) {
        return d.isNaN() ? "NaN" : "$dbl(\"" + Long.toHexString(Double.doubleToRawLongBits(d)) + "\")";
    }
}
```

`suko-core/src/test/java/io/suko/lang/client/JsTranslatorTest.java`:

```java
package io.suko.lang.client;

import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientScope;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** O JS exato que o tradutor escreve para cada forma do subconjunto (o contrato R17 com o runtime do plano 2). */
class JsTranslatorTest {

    static final ClientScope SCOPE = scope(List.of(
        state("int", "count", "0"), state("String", "draft", "\"\""), state("List<String>", "items", "null"),
        state("double", "ratio", "0.0"), state("Integer", "maybe", "null"), derived("int", "remaining", "0"),
        param("String", "name", "null"), param("int", "a", "0"), param("int", "b", "0"),
        param("List<Integer>", "nums", "null"), param("Map<String, String>", "labels", "null"),
        param("Todo", "todo", "null"), param("Product", "product", "null"), local("String", "let", "null")));

    static final JsTranslator T = new JsTranslator(SCOPE, TYPES);

    static String js(String source) {
        JsTranslator.Result r = T.expression(expr(source));
        return assertInstanceOf(JsTranslator.Ok.class, r, () -> source + " -> " + r).js();
    }

    static ClientProblem problem(String source) {
        JsTranslator.Result r = T.expression(expr(source));
        return assertInstanceOf(JsTranslator.Err.class, r, () -> source + " -> " + r).problem();
    }

    @Test
    void namesAndLiterals() {
        assertEquals("$s.count", js("count"));
        assertEquals("$s.remaining", js("remaining"));
        assertEquals("$p.name", js("name"));
        assertEquals("$_let", js("let"));
        assertEquals("(-5)", js("-5"));
        assertEquals("null", js("null"));
        assertEquals("\"a\\u0026b\\u0027c\"", js("\"a&b'c\""));
    }

    @Test
    void intArithmeticIsThirtyTwoBit() {
        assertEquals("(($s.count + 1) | 0)", js("count + 1"));
        assertEquals("$suko.idiv($p.a, $p.b)", js("a / b"));
        assertEquals("$suko.imod($p.a, $p.b)", js("a % b"));
        assertEquals("Math.imul($p.a, $p.b)", js("a * b"));
        assertEquals("((-$p.a) | 0)", js("-a"));
        assertEquals("($s.ratio * 2)", js("ratio * 2"));
        assertEquals("(($suko.nn($s.maybe) + 1) | 0)", js("maybe + 1"));
        assertEquals("Math.max($p.a, $s.ratio)", js("Math.max(a, ratio)"));
    }

    @Test
    void concatenationFollowsJava() {
        assertEquals("(\"\" + \"Ol\\u00e1 \" + $p.name)", js("\"Olá \" + name"));
        assertEquals("(\"\" + $p.name + $suko.dcat($s.ratio))", js("name + ratio"));
        assertEquals("(\"\" + $p.name + \"!\")", js("\"$name!\""));
        assertEquals("($p.name == null ? \"\" : \"\" + $p.name)", js("\"${name}\""));
    }

    @Test
    void methodsOfTheClosedList() {
        assertEquals("$suko.equals($p.name, \"x\")", js("name.equals(\"x\")"));
        assertEquals("($p.name == null)", js("name == null"));
        assertEquals("(($s.items).length > 0)", js("items.size() > 0"));
        assertEquals("(!(($s.items).length === 0))", js("!items.isEmpty()"));
        assertEquals("$suko.get($s.items, 0)", js("items.get(0)"));
        assertEquals("(($suko.nn($suko.get($p.nums, 0)) + 1) | 0)", js("nums.get(0) + 1"));
        assertEquals("($p.nums).includes(3)", js("nums.contains(3)"));
        assertEquals("$suko.mget($p.labels, \"k\")", js("labels.get(\"k\")"));
        assertEquals("$suko.field($p.todo, \"title\")", js("todo.title()"));
        assertEquals("$suko.rec([\"title\", \"done\"], [$s.draft, false])", js("new Todo(draft, false)"));
        assertEquals("($_let).length", js("let.length()"));
        assertEquals("(($s.count > 0) ? \"sim\" : \"n\\u00e3o\")", js("count > 0 ? \"sim\" : \"não\""));
    }

    @Test
    void textAndJavaStringConversions() {
        assertEquals("String($s.count)", ((JsTranslator.Ok) T.text(expr("count"))).js());
        assertEquals("$suko.dstr($s.ratio)", ((JsTranslator.Ok) T.text(expr("ratio"))).js());
        assertEquals("$suko.text($p.name)", ((JsTranslator.Ok) T.text(expr("name"))).js());
        assertEquals("$suko.text($s.maybe)", ((JsTranslator.Ok) T.text(expr("maybe"))).js());
        assertInstanceOf(JsTranslator.Err.class, T.text(expr("items")));
        assertEquals("$suko.dstr($s.ratio)", ((JsTranslator.Ok) T.javaString(expr("ratio"))).js());
        assertEquals("$p.name", ((JsTranslator.Ok) T.javaString(expr("name"))).js());
        assertEquals("$suko.sval($s.maybe)", ((JsTranslator.Ok) T.javaString(expr("maybe"))).js());
        assertEquals("String($s.count)", ((JsTranslator.Ok) T.javaString(expr("count"))).js());
    }

    @Test
    void typesComeOut() {
        assertEquals(ClientType.Scalar.DOUBLE, ((JsTranslator.Ok) T.expression(expr("a + ratio"))).type());
        assertEquals(new ClientType.Boxed(ClientType.Scalar.INT), ((JsTranslator.Ok) T.expression(expr("nums.get(0)"))).type());
        assertEquals(ClientType.Scalar.STRING, ((JsTranslator.Ok) T.expression(expr("labels.get(\"k\")"))).type());
    }

    @Test
    void outsideTheSubsetIsClientUnsupportedAtTheExactSpot() {
        assertTrue(problem("name == draft").message().contains("equals"));
        assertTrue(problem("name?.length()").message().contains("?."));
        assertTrue(problem("name ?: \"x\"").message().contains("?:"));
        assertTrue(problem("items.add(\"x\")").message().contains("instrução"));
        assertTrue(problem("x + 1").message().contains("'x'"));
        assertTrue(problem("name.substring(1)").message().contains("lista fechada"));
        assertTrue(problem("new Unknown()").message().contains("não é conhecido"));
        assertTrue(problem("product.price()").message().contains("'Product'"));
        assertTrue(problem("nums.contains(ratio)").message().contains("contains"));
        assertTrue(problem("todo.done").message().contains("done()"));
        assertTrue(problem("010").message().contains("octal"));
        assertTrue(problem("2147483648").message().contains("não cabe"));
        assertTrue(problem("Card()").message().contains("componente"));
        assertTrue(problem("count > 0 ? 1 : \"x\"").message().contains("ternário"));
        assertEquals("CLIENT_UNSUPPORTED", problem("name?.length()").code());
        assertEquals("RESERVED_NAME", problem("trustedUrl(name)").code());
        ClientProblem p = problem("count + name.substring(1)");
        assertEquals(expr("count + name.substring(1)").span().startIndex() + "count + ".length(), p.span().startIndex(),
            "o problema aponta para a subexpressão, não para a expressão inteira");
    }
}
```

`suko-core/src/test/java/io/suko/lang/client/TranslatorDifferentialTest.java`:

```java
package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Expressões do cliente: o JS traduzido dá no Node exatamente o que o Java dá (R16). */
class TranslatorDifferentialTest {

    static final List<Var> VARS = List.of(
        param("int", "a", "7"), param("int", "b", "2"), param("int", "z", "0"),
        param("int", "big", "2147483647"), param("int", "min", "-2147483648"),
        param("double", "d", "1.0"), param("double", "nd", "-0.5"),
        param("String", "s", "\" \\u0001abc\\u00a0 \""), param("String", "ns", "null"),
        param("Integer", "bi", "null"), param("Integer", "bj", "5"),
        param("List<String>", "xs", "new ArrayList<>(List.of(\"a\", \"b\"))"),
        param("Map<String, String>", "m", "new HashMap<>(Map.of(\"k\", \"v\"))"),
        param("Todo", "t", "new Todo(\"x\", true)"),
        state("int", "count", "3"), local("String", "let", "\"kw\""));

    static final List<String> EXPRESSIONS = List.of(
        // int de 32 bits, divisão inteira e por zero
        "a / b", "-a / b", "a % b", "-a % b", "a / z", "a % z", "big + 1", "min - 1", "big * 2", "min / -1", "-min",
        "Math.abs(min)", "count * count",
        // double, -0.0 e promoção
        "d / (z * -1)", "nd * 0", "a + d", "a / 2 * d", "Math.max(a, b)", "Math.min(d, nd)", "a > b ? a : d",
        // concatenação e texto de double
        "1 + 2 + s", "s + 1 + 2", "ns + 1", "\"x\" + ns", "\"x\" + d", "\"x\" + nd * 0", "\"v=\" + d / 3",
        "\"\" + d * 10000000", "\"\" + d / 10000", "let + count",
        // String
        "s.trim()", "s.trim().length()", "s.length()", "s.isEmpty()", "ns.length()", "ns.trim()", "ns == null",
        "s.equals(\" \")", "ns.equals(\"x\")", "s.contains(\"abc\")", "s.contains(ns)", "s.toUpperCase()",
        "a > b ? \"sim\" : ns",
        // List, Map, records
        "xs.get(1)", "xs.get(5)", "xs.get(-1)", "xs.size()", "xs.contains(\"b\")", "xs.isEmpty()",
        "m.get(\"k\")", "m.get(\"nope\")", "m.containsKey(\"k\")", "m.size()",
        "t.title()", "t.done()", "new Todo(\"y\", false).title()",
        // wrappers e igualdade
        "bi == null", "bi + 1", "bj + 1", "bj == 5", "a == 7", "d == 1", "count > 2 && !(a < b)");

    @Test
    void everyExpressionGivesTheSameResultInJavaAndInNode() throws Exception {
        List<Outcome> outcomes = run(EXPRESSIONS.stream().map(e -> expression(e, VARS)).toList());
        for (Outcome o : outcomes) {
            assertEquals(o.java(), o.js(), o.c().source());
        }
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.client.JsTranslatorTest' --tests 'io.suko.lang.client.TranslatorDifferentialTest'`
Expected: FAIL (compilação: `JsTranslator` não existe).

- [ ] **Step 2: `JsTranslator` (expressões)**

`suko-jte/src/main/java/io/suko/jte/client/JsTranslator.java` (a Task 7 acrescenta a secção dos handlers antes de "apoio"):

```java
package io.suko.jte.client;

import io.suko.lang.ast.Expr;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Type;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static io.suko.jte.client.ClientType.Scalar.BOOLEAN;
import static io.suko.jte.client.ClientType.Scalar.DOUBLE;
import static io.suko.jte.client.ClientType.Scalar.INT;
import static io.suko.jte.client.ClientType.Scalar.STRING;
import static io.suko.jte.client.ClientType.Special.EVENT;
import static io.suko.jte.client.ClientType.Special.NULL;
import static io.suko.jte.client.ClientType.Special.UNKNOWN;
import static io.suko.jte.client.ClientType.Special.VOID;

/**
 * Tradutor do subconjunto de Java do cliente (13b) para JS: tipa cada expressão e devolve o JS, ou o primeiro
 * problema com a localização exata (um diagnóstico por sítio). Puro: sem I/O nem DOM. O JS segue o contrato
 * com o runtime do plano 2 (R17): {@code $s} (state e derived), {@code $p} (props), {@code $u("nome")} depois
 * de cada mutação de um state e {@code $suko} (as funções de {@code prelude.js}). A semântica garantida e as
 * recusas estão no R16 do plano 1 e na secção "Expressões no cliente" da spec.
 */
public final class JsTranslator {

    /** Resultado de uma tradução: JS e tipo, ou o problema. */
    public sealed interface Result {
    }

    public record Ok(String js, ClientType type) implements Result {
    }

    public record Err(ClientProblem problem) implements Result {
    }

    public static final String CLIENT_UNSUPPORTED = "CLIENT_UNSUPPORTED";

    private static final Set<String> TRUSTED = Set.of("trustedUrl", "trustedStyle", "trustedHtml");

    /** Interrompe a tradução de uma expressão no primeiro problema. */
    private static final class Stop extends RuntimeException {
        private final ClientProblem problem;

        Stop(ClientProblem problem) {
            super(problem.message(), null, false, false);
            this.problem = problem;
        }
    }

    private record Typed(String js, ClientType type) {
    }

    private final ClientScope scope;
    private final JavaTypes types;

    public JsTranslator(ClientScope scope, JavaTypes types) {
        this.scope = scope;
        this.types = types;
    }

    /** O valor da expressão. */
    public Result expression(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            return new Ok(t.js(), t.type());
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** O texto de um nó, como o JTE o escreveria: null → "", double com String.valueOf. */
    public Result text(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            String js = switch (t.type()) {
                case ClientType.Scalar s when s == INT || s == BOOLEAN -> "String(" + t.js() + ")";
                case ClientType.Scalar s when s == DOUBLE -> "$suko.dstr(" + t.js() + ")";
                case ClientType.Scalar s -> "$suko.text(" + t.js() + ")";
                case ClientType.Boxed b when b.of() == DOUBLE -> "$suko.dtext(" + t.js() + ")";
                case ClientType.Boxed b -> "$suko.text(" + t.js() + ")";
                case ClientType.Special s when s == NULL -> "\"\"";
                default -> throw unsupported(e.span(), "não há texto para " + t.type().java() + " no cliente");
            };
            return new Ok(js, STRING);
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** {@code String.valueOf} de Java, mas null fica null: a entrada dos portes do SukoSafe (url, cssValue, ...). */
    public Result javaString(Expr e) {
        try {
            Typed t = value(expr(e, scope), e.span());
            String js = switch (t.type()) {
                case ClientType.Scalar s when s == INT || s == BOOLEAN -> "String(" + t.js() + ")";
                case ClientType.Scalar s when s == DOUBLE -> "$suko.dstr(" + t.js() + ")";
                case ClientType.Scalar s -> t.js();
                case ClientType.Boxed b when b.of() == DOUBLE -> "$suko.dval(" + t.js() + ")";
                case ClientType.Boxed b -> "$suko.sval(" + t.js() + ")";
                case ClientType.Special s when s == NULL -> "null";
                default -> throw unsupported(e.span(), "não há conversão para texto de " + t.type().java() + " no cliente");
            };
            return new Ok(js, STRING);
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    /** Como o JS gerado lê um nome (o contrato com o runtime do plano 2, R17). */
    public static String access(String name, ClientScope.Kind kind) {
        return switch (kind) {
            case STATE, DERIVED -> "$s." + name;
            case PARAM -> "$p." + name;
            case LOCAL -> JsText.identifier(name);
        };
    }

    // ------------------------------------------------------------------ expressões

    private Typed expr(Expr e, ClientScope s) {
        return switch (e) {
            case Expr.PrimaryExpr p -> primary(p, s);
            case Expr.StringLiteralExpr str -> stringLiteral(str, s);
            case Expr.ParenExpr p -> {
                Typed t = expr(p.inner(), s);
                yield new Typed("(" + t.js() + ")", t.type());
            }
            case Expr.NotExpr n -> new Typed("(!" + bool(value(expr(n.operand(), s), n.operand().span()), n.operand().span()).js() + ")", BOOLEAN);
            case Expr.UnaryMinusExpr u -> negate(u, s);
            case Expr.BinaryExpr b -> binary(b, s);
            case Expr.TernaryExpr t -> ternary(t, s);
            case Expr.CallExpr c -> call(c, s);
            case Expr.NewExpr n -> newRecord(n, s);
            case Expr.AccessExpr a -> throw unsupported(a.span(), "'" + a.memberName() + "' sem parênteses é um acesso a campo;"
                + " no cliente um record lê-se com o acessor: " + a.memberName() + "()");
            case Expr.SafeAccessExpr a -> throw unsupported(a.span(), "'?.' só existe no servidor; no cliente use o ternário"
                + " (x == null ? ... : ...)");
            case Expr.ElvisExpr x -> throw unsupported(x.span(), "'?:' só existe no servidor; no cliente use o ternário");
            case Expr.LambdaExpr l -> throw unsupported(l.span(), "uma lambda só é aceite como valor de on:evento ou como"
                + " argumento de um componente reativo");
            case Expr.AssignExpr a -> throw unsupported(a.span(), "uma atribuição só é aceite como instrução de um handler");
            case Expr.UpdateExpr u -> throw unsupported(u.span(), "'" + u.operator() + "' só é aceite como instrução de um handler");
        };
    }

    private Typed primary(Expr.PrimaryExpr p, ClientScope s) {
        String text = p.text();
        if (text.equals("true") || text.equals("false")) {
            return new Typed(text, BOOLEAN);
        }
        if (text.equals("null")) {
            return new Typed("null", NULL);
        }
        if (!text.isEmpty() && Character.isDigit(text.charAt(0))) {
            return new Typed(intLiteral(text, false, p.span()), INT);
        }
        return name(text, p.span(), s);
    }

    private static String intLiteral(String digits, boolean negative, SourceSpan span) {
        if (digits.length() > 1 && digits.charAt(0) == '0') {
            throw unsupported(span, "o literal " + digits + " é octal em Java; escreva-o sem o zero à esquerda");
        }
        BigInteger value = new BigInteger(digits);
        BigInteger limit = BigInteger.valueOf(negative ? 2147483648L : Integer.MAX_VALUE);
        if (value.compareTo(limit) > 0) {
            throw unsupported(span, "o literal " + digits + " não cabe num int (long fica fora do cliente)");
        }
        return value.signum() == 0 ? "0" : (negative ? "-" : "") + value;
    }

    private Typed name(String name, SourceSpan span, ClientScope s) {
        ClientScope.Binding b = s.lookup(name).orElseThrow(() -> unsupported(span,
            "'" + name + "' não é um state, derived, parâmetro nem variável do cliente"));
        if (b.type() == UNKNOWN) {
            throw silent(span);
        }
        if (b.type() instanceof ClientType.Unsupported u) {
            throw unsupported(span, "'" + name + "' (" + u.javaType() + "): " + u.reason());
        }
        return new Typed(access(name, b.kind()), b.type());
    }

    private Typed stringLiteral(Expr.StringLiteralExpr str, ClientScope s) {
        List<Expr.StringPart> parts = str.parts();
        // Paridade com o JteEmitter: uma string que é só a interpolação de um parâmetro de referência sai
        // "" quando ele é null (e não "null").
        if (parts.size() == 1 && !(parts.get(0) instanceof Expr.StringPart.Literal)) {
            String id = switch (parts.get(0)) {
                case Expr.StringPart.SimpleInterp si -> si.identifier();
                case Expr.StringPart.Interp i when i.expr() instanceof Expr.PrimaryExpr p -> p.text();
                default -> null;
            };
            Optional<ClientScope.Binding> b = id == null ? Optional.empty() : s.lookup(id);
            if (b.isPresent() && b.get().kind() == ClientScope.Kind.PARAM && plainReference(b.get().type())) {
                Typed v = name(id, str.span(), s);
                return new Typed("(" + v.js() + " == null ? \"\" : \"\" + " + concatOperand(v, str.span()) + ")", STRING);
            }
        }
        List<String> pieces = new ArrayList<>();
        for (Expr.StringPart part : parts) {
            switch (part) {
                case Expr.StringPart.Literal l -> pieces.add(JsText.string(unescape(l.javaEscapedText(), str.span())));
                case Expr.StringPart.Interp i ->
                    pieces.add(concatOperand(value(expr(i.expr(), s), i.expr().span()), i.expr().span()));
                case Expr.StringPart.SimpleInterp si -> pieces.add(concatOperand(name(si.identifier(), str.span(), s), str.span()));
            }
        }
        if (pieces.isEmpty()) {
            return new Typed("\"\"", STRING);
        }
        if (parts.size() == 1 && parts.get(0) instanceof Expr.StringPart.Literal) {
            return new Typed(pieces.get(0), STRING);
        }
        return new Typed("(\"\" + " + String.join(" + ", pieces) + ")", STRING);
    }

    private static boolean plainReference(ClientType t) {
        return t == STRING || t instanceof ClientType.Boxed || t instanceof ClientType.Named || t instanceof ClientType.RecordOf;
    }

    private static String unescape(String javaEscaped, SourceSpan span) {
        try {
            return JsText.unescapeJava(javaEscaped);
        } catch (IllegalArgumentException e) {
            throw unsupported(span, e.getMessage());
        }
    }

    private Typed negate(Expr.UnaryMinusExpr u, ClientScope s) {
        if (u.operand() instanceof Expr.PrimaryExpr p && !p.text().isEmpty() && Character.isDigit(p.text().charAt(0))) {
            return new Typed("(" + intLiteral(p.text(), true, p.span()) + ")", INT);
        }
        Typed t = number(value(expr(u.operand(), s), u.operand().span()), u.operand().span());
        return t.type() == INT
            ? new Typed("((-" + t.js() + ") | 0)", INT)
            : new Typed("(-" + t.js() + ")", DOUBLE);
    }

    private Typed binary(Expr.BinaryExpr b, ClientScope s) {
        Typed l = value(expr(b.left(), s), b.left().span());
        Typed r = value(expr(b.right(), s), b.right().span());
        String op = b.operator();
        return switch (op) {
            case "&&", "||" -> new Typed("(" + bool(l, b.left().span()).js() + " " + op + " "
                + bool(r, b.right().span()).js() + ")", BOOLEAN);
            case "==", "!=" -> equality(l, r, op, b);
            case "<", "<=", ">", ">=" -> new Typed("(" + number(l, b.left().span()).js() + " " + op + " "
                + number(r, b.right().span()).js() + ")", BOOLEAN);
            case "+" -> l.type() == STRING || r.type() == STRING
                ? new Typed("(\"\" + " + concatOperand(l, b.left().span()) + " + " + concatOperand(r, b.right().span()) + ")", STRING)
                : arithmetic(l, r, op, b);
            case "-", "*", "/", "%" -> arithmetic(l, r, op, b);
            default -> throw unsupported(b.span(), "o operador '" + op + "' não faz parte do subconjunto do cliente");
        };
    }

    /** int com aritmética de 32 bits como em Java (R16); double como em IEEE 754, igual nos dois. */
    private static Typed arithmetic(Typed l, Typed r, String op, Expr.BinaryExpr b) {
        Typed a = number(l, b.left().span());
        Typed c = number(r, b.right().span());
        if (a.type() == INT && c.type() == INT) {
            return switch (op) {
                case "/" -> new Typed("$suko.idiv(" + a.js() + ", " + c.js() + ")", INT);
                case "%" -> new Typed("$suko.imod(" + a.js() + ", " + c.js() + ")", INT);
                case "*" -> new Typed("Math.imul(" + a.js() + ", " + c.js() + ")", INT);
                default -> new Typed("((" + a.js() + " " + op + " " + c.js() + ") | 0)", INT);
            };
        }
        return new Typed("(" + a.js() + " " + op + " " + c.js() + ")", DOUBLE);
    }

    private static Typed equality(Typed l, Typed r, String op, Expr.BinaryExpr b) {
        boolean eq = op.equals("==");
        if (l.type() == NULL || r.type() == NULL) {
            Typed other = l.type() == NULL ? r : l;
            if (other.type() == NULL) {
                return new Typed(eq ? "true" : "false", BOOLEAN);
            }
            if (!other.type().nullable()) {
                throw unsupported(b.span(), "um " + other.type().java() + " nunca é null");
            }
            return new Typed("(" + other.js() + (eq ? " == null" : " != null") + ")", BOOLEAN);
        }
        if (l.type() == STRING && r.type() == STRING) {
            throw unsupported(b.span(), "'" + op + "' entre Strings compara referências em Java; use a.equals(b)");
        }
        ClientType.Scalar pl = ClientTypes.primitive(l.type());
        ClientType.Scalar pr = ClientTypes.primitive(r.type());
        boolean bothBoxed = l.type() instanceof ClientType.Boxed && r.type() instanceof ClientType.Boxed;
        boolean numbers = (pl == INT || pl == DOUBLE) && (pr == INT || pr == DOUBLE);
        boolean booleans = pl == BOOLEAN && pr == BOOLEAN;
        if (!bothBoxed && (numbers || booleans)) {
            return new Typed("(" + unbox(l).js() + (eq ? " === " : " !== ") + unbox(r).js() + ")", BOOLEAN);
        }
        throw unsupported(b.span(), "'" + op + "' entre " + l.type().java() + " e " + r.type().java()
            + " compara referências; no cliente compare valores primitivos, use equals() em Strings ou compare com null");
    }

    private Typed ternary(Expr.TernaryExpr t, ClientScope s) {
        Typed c = bool(value(expr(t.condition(), s), t.condition().span()), t.condition().span());
        Typed a = value(expr(t.whenTrue(), s), t.whenTrue().span());
        Typed b = value(expr(t.whenFalse(), s), t.whenFalse().span());
        ClientType type = ClientTypes.unify(a.type(), b.type());
        if (type == null) {
            throw unsupported(t.span(), "os ramos do ternário não têm um tipo comum no cliente (" + a.type().java()
                + " e " + b.type().java() + ")");
        }
        return new Typed("(" + c.js() + " ? " + coerce(a, type) + " : " + coerce(b, type) + ")", type);
    }

    private Typed call(Expr.CallExpr c, ClientScope s) {
        if (c.callee() instanceof Expr.PrimaryExpr p) {
            if (TRUSTED.contains(p.text())) {
                throw new Stop(new ClientProblem("RESERVED_NAME", p.text() + "(...) só é válido no render do servidor:"
                    + " no cliente o valor passa sempre pelo porte do SukoSafe", c.span()));
            }
            throw unsupported(c.span(), "'" + p.text() + "(...)' não é uma chamada permitida no cliente"
                + (!p.text().isEmpty() && Character.isUpperCase(p.text().charAt(0))
                    ? " (um componente usado como valor só existe no servidor)" : ""));
        }
        if (!(c.callee() instanceof Expr.AccessExpr a)) {
            expr(c.callee(), s); // `x?.m()` cai aqui: a mensagem certa é a do `?.`
            throw unsupported(c.span(), "chamada fora do subconjunto do cliente");
        }
        String m = a.memberName();
        if (a.target() instanceof Expr.PrimaryExpr t && t.text().equals("Math") && s.lookup("Math").isEmpty()) {
            return math(m, args(c, s), c);
        }
        Typed receiver = value(expr(a.target(), s), a.target().span());
        List<Typed> args = args(c, s);
        ClientType type = receiver.type();
        if (type == STRING) {
            return stringMethod(receiver, m, args, c);
        }
        if (type instanceof ClientType.ListOf l) {
            return listMethod(receiver, l, m, args, c);
        }
        if (type instanceof ClientType.MapOf map) {
            return mapMethod(receiver, map, m, args, c);
        }
        if (type instanceof ClientType.RecordOf r) {
            return accessor(receiver, r, m, args, c);
        }
        if (type == EVENT) {
            return eventMethod(receiver, m, args, c);
        }
        if (type instanceof ClientType.Callback cb) {
            return invoke(receiver, cb, m, args, c);
        }
        if (type instanceof ClientType.Named n) {
            throw unsupported(c.span(), "o tipo Java '" + n.name() + "' não é conhecido do compilador (records e enums vêm"
                + " de Java); '" + m + "()' não pode ser usado no cliente");
        }
        throw unsupported(c.span(), "'" + m + "()' não existe em " + type.java() + " no subconjunto do cliente");
    }

    private List<Typed> args(Expr.CallExpr c, ClientScope s) {
        List<Typed> out = new ArrayList<>();
        for (Expr arg : c.args()) {
            out.add(value(expr(arg, s), arg.span()));
        }
        return out;
    }

    private static void arity(List<Typed> args, int n, Expr.CallExpr c, String m) {
        if (args.size() != n) {
            throw unsupported(c.span(), m + "() recebe " + n + " argumento(s), não " + args.size());
        }
    }

    private static Typed stringMethod(Typed r, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "length" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").length", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").length === 0)", BOOLEAN);
            }
            case "trim" -> {
                arity(a, 0, c, m);
                return new Typed("$suko.trim(" + r.js() + ")", STRING);
            }
            case "toUpperCase", "toLowerCase" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ")." + m + "()", STRING);
            }
            case "contains", "startsWith", "endsWith" -> {
                arity(a, 1, c, m);
                Typed x = a.get(0);
                if (x.type() != STRING && x.type() != NULL) {
                    throw unsupported(c.args().get(0).span(), m + "() espera uma String, não " + x.type().java());
                }
                return new Typed("$suko." + m + "(" + r.js() + ", " + x.js() + ")", BOOLEAN);
            }
            case "equals" -> {
                arity(a, 1, c, m);
                return new Typed("$suko.equals(" + r.js() + ", " + a.get(0).js() + ")", BOOLEAN);
            }
            default -> throw unsupported(c.span(), "String." + m + "() não faz parte do subconjunto do cliente (lista"
                + " fechada: length, isEmpty, trim, toUpperCase, toLowerCase, contains, startsWith, endsWith, equals)");
        }
    }

    private static Typed listMethod(Typed r, ClientType.ListOf l, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "size" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").length", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").length === 0)", BOOLEAN);
            }
            case "get" -> {
                arity(a, 1, c, m);
                return new Typed("$suko.get(" + r.js() + ", " + index(a.get(0), c.args().get(0).span()).js() + ")", l.element());
            }
            case "contains" -> {
                arity(a, 1, c, m);
                if (!containsMatches(l.element(), a.get(0).type())) {
                    throw unsupported(c.span(), "List.contains() só é suportado em listas de String, Integer ou Boolean,"
                        + " com um argumento do mesmo tipo (o equals de records e de doubles difere em JS)");
                }
                return new Typed("(" + r.js() + ").includes(" + a.get(0).js() + ")", BOOLEAN);
            }
            case "add", "remove", "set", "clear" -> throw unsupported(c.span(), "List." + m + "() altera a lista: só como"
                + " instrução de um handler, sobre um state");
            default -> throw unsupported(c.span(), "List." + m + "() não faz parte do subconjunto do cliente (size, isEmpty,"
                + " get, contains; add/remove(int)/set/clear em handlers)");
        }
    }

    private static boolean containsMatches(ClientType element, ClientType arg) {
        if (element == STRING) {
            return arg == STRING || arg == NULL;
        }
        if (element instanceof ClientType.Boxed b && (b.of() == INT || b.of() == BOOLEAN)) {
            return arg == NULL || arg == b.of() || arg.equals(b);
        }
        return false;
    }

    /** R14 (confirmado): Map de só leitura, chaves String. */
    private static Typed mapMethod(Typed r, ClientType.MapOf map, String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "get", "containsKey" -> {
                arity(a, 1, c, m);
                Typed key = a.get(0);
                if (key.type() != STRING && key.type() != NULL) {
                    throw unsupported(c.args().get(0).span(), "as chaves de um Map no cliente são String, não " + key.type().java());
                }
                return m.equals("get")
                    ? new Typed("$suko.mget(" + r.js() + ", " + key.js() + ")", map.value())
                    : new Typed("$suko.mhas(" + r.js() + ", " + key.js() + ")", BOOLEAN);
            }
            case "size" -> {
                arity(a, 0, c, m);
                return new Typed("(" + r.js() + ").size", INT);
            }
            case "isEmpty" -> {
                arity(a, 0, c, m);
                return new Typed("((" + r.js() + ").size === 0)", BOOLEAN);
            }
            default -> throw unsupported(c.span(), "Map." + m + "() não faz parte do subconjunto do cliente (get,"
                + " containsKey, size, isEmpty)");
        }
    }

    private static Typed accessor(Typed r, ClientType.RecordOf record, String m, List<Typed> a, Expr.CallExpr c) {
        arity(a, 0, c, m);
        for (ClientType.RecordOf.Field f : record.fields()) {
            if (f.name().equals(m)) {
                return new Typed("$suko.field(" + r.js() + ", " + JsText.string(m) + ")", f.type());
            }
        }
        throw unsupported(c.span(), "o record " + record.name() + " não tem o componente '" + m + "'");
    }

    private static Typed eventMethod(Typed e, String m, List<Typed> a, Expr.CallExpr c) {
        arity(a, 0, c, m);
        return switch (m) {
            case "value" -> new Typed("$suko.evValue(" + e.js() + ")", STRING);
            case "checked" -> new Typed("$suko.evChecked(" + e.js() + ")", BOOLEAN);
            case "key" -> new Typed("$suko.evKey(" + e.js() + ")", STRING);
            case "preventDefault" -> new Typed("$suko.evPrevent(" + e.js() + ")", VOID);
            default -> throw unsupported(c.span(), "o evento só tem value(), checked(), key() e preventDefault() no cliente");
        };
    }

    private static Typed invoke(Typed r, ClientType.Callback cb, String m, List<Typed> a, Expr.CallExpr c) {
        String expected = cb.params().isEmpty() ? "run" : "accept";
        if (!m.equals(expected)) {
            throw unsupported(c.span(), cb.params().isEmpty() ? "um Runnable chama-se com run()" : "um Consumer chama-se com accept(valor)");
        }
        arity(a, cb.params().size(), c, m);
        List<String> js = new ArrayList<>();
        for (int i = 0; i < a.size(); i++) {
            ClientType to = cb.params().get(i);
            if (!ClientTypes.assignable(a.get(i).type(), to)) {
                throw unsupported(c.args().get(i).span(), a.get(i).type().java() + " não é atribuível a " + to.java());
            }
            js.add(coerce(a.get(i), to));
        }
        return new Typed("(" + r.js() + ")(" + String.join(", ", js) + ")", VOID);
    }

    private static Typed math(String m, List<Typed> a, Expr.CallExpr c) {
        switch (m) {
            case "min", "max" -> {
                arity(a, 2, c, "Math." + m);
                Typed x = number(a.get(0), c.args().get(0).span());
                Typed y = number(a.get(1), c.args().get(1).span());
                ClientType t = x.type() == INT && y.type() == INT ? INT : DOUBLE;
                return new Typed("Math." + m + "(" + x.js() + ", " + y.js() + ")", t);
            }
            case "abs" -> {
                arity(a, 1, c, "Math.abs");
                Typed x = number(a.get(0), c.args().get(0).span());
                return x.type() == INT
                    ? new Typed("(Math.abs(" + x.js() + ") | 0)", INT)
                    : new Typed("Math.abs(" + x.js() + ")", DOUBLE);
            }
            default -> throw unsupported(c.span(), "Math." + m + "() não faz parte do subconjunto do cliente (min, max, abs)");
        }
    }

    private Typed newRecord(Expr.NewExpr n, ClientScope s) {
        ClientType t = ClientTypes.of(new Type(n.typeName(), List.of(), 0), types);
        if (!(t instanceof ClientType.RecordOf r)) {
            throw unsupported(n.span(), "new " + n.typeName() + "(...): no cliente só se criam records conhecidos do compilador"
                + (t instanceof ClientType.Named ? " (o tipo Java '" + n.typeName() + "' não é conhecido: records vêm de Java)" : ""));
        }
        if (n.args().size() != r.fields().size()) {
            throw unsupported(n.span(), "o record " + r.name() + " tem " + r.fields().size() + " componente(s), não " + n.args().size());
        }
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < n.args().size(); i++) {
            ClientType.RecordOf.Field f = r.fields().get(i);
            Typed v = value(expr(n.args().get(i), s), n.args().get(i).span());
            if (!ClientTypes.assignable(v.type(), f.type())) {
                throw unsupported(n.args().get(i).span(), f.name() + ": " + v.type().java() + " não é atribuível a " + f.type().java());
            }
            names.add(JsText.string(f.name()));
            values.add(coerce(v, f.type()));
        }
        return new Typed("$suko.rec([" + String.join(", ", names) + "], [" + String.join(", ", values) + "])", r);
    }

    // ------------------------------------------------------------------ apoio

    private static Typed value(Typed t, SourceSpan span) {
        if (t.type() == VOID) {
            throw unsupported(span, "esta chamada não devolve um valor");
        }
        return t;
    }

    private static Typed number(Typed t, SourceSpan span) {
        if (t.type() == INT || t.type() == DOUBLE) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && (b.of() == INT || b.of() == DOUBLE)) {
            return new Typed("$suko.nn(" + t.js() + ")", b.of());
        }
        throw unsupported(span, "esperava um número (int ou double), não " + t.type().java());
    }

    private static Typed bool(Typed t, SourceSpan span) {
        if (t.type() == BOOLEAN) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && b.of() == BOOLEAN) {
            return new Typed("$suko.nn(" + t.js() + ")", BOOLEAN);
        }
        throw unsupported(span, "esperava um boolean, não " + t.type().java());
    }

    private static Typed index(Typed t, SourceSpan span) {
        if (t.type() == INT) {
            return t;
        }
        if (t.type() instanceof ClientType.Boxed b && b.of() == INT) {
            return new Typed("$suko.nn(" + t.js() + ")", INT);
        }
        throw unsupported(span, "um índice tem de ser int, não " + t.type().java());
    }

    private static Typed unbox(Typed t) {
        return t.type() instanceof ClientType.Boxed b ? new Typed("$suko.nn(" + t.js() + ")", b.of()) : t;
    }

    /** O JS de {@code v} para um destino do tipo {@code to}: um Boxed num primitivo passa por {@code nn} (NPE de Java). */
    private static String coerce(Typed v, ClientType to) {
        return to instanceof ClientType.Scalar && to != STRING && v.type() instanceof ClientType.Boxed
            ? "$suko.nn(" + v.js() + ")"
            : v.js();
    }

    /** Um operando de concatenação de Java: null → "null"; um double com o algoritmo do Double.toString. */
    private static String concatOperand(Typed t, SourceSpan span) {
        ClientType type = t.type();
        if (type == DOUBLE || (type instanceof ClientType.Boxed b && b.of() == DOUBLE)) {
            return "$suko.dcat(" + t.js() + ")";
        }
        if (type == STRING || type == INT || type == BOOLEAN || type == NULL || type instanceof ClientType.Boxed) {
            return t.js();
        }
        throw unsupported(span, "não há conversão para texto de " + type.java() + " no cliente (o toString de Java não é reproduzido)");
    }

    private static Stop unsupported(SourceSpan span, String message) {
        return new Stop(new ClientProblem(CLIENT_UNSUPPORTED, message, span));
    }

    /** Consequência de um problema já reportado noutro sítio: não gera diagnóstico. */
    private static Stop silent(SourceSpan span) {
        return new Stop(new ClientProblem(null, "consequência de um problema anterior", span));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test :suko-core:test --tests 'io.suko.lang.client.*'`
Expected: PASS — `JsTranslatorTest` (o JS exato), `TranslatorDifferentialTest` (cerca de 60 expressões: divisão inteira e por zero, overflow de `int`, `-0.0`, concatenação com `null` e com `double`, `trim`, índices, `Map`, records, wrappers nulos — iguais em Java e no Node) e os testes da Task 5.

- [ ] **Step 3: Commit**

```bash
git status
git add suko-jte/src/main/java/io/suko/jte/client/JsTranslator.java \
  suko-core/src/test/java/io/suko/lang/client/ClientTestSupport.java \
  suko-core/src/test/java/io/suko/lang/client/JsTranslatorTest.java \
  suko-core/src/test/java/io/suko/lang/client/TranslatorDifferentialTest.java
git commit -m "feat(13b): tradutor do subconjunto Java → JS (expressões) com testes diferenciais Java/Node"
```

---

### Task 7: Tradutor Java → JS — handlers de `on:`, callbacks e mutações de state

**Files:**
- Modify: `suko-jte/src/main/java/io/suko/jte/client/JsTranslator.java`
- Modify: `suko-core/src/test/java/io/suko/lang/client/ClientTestSupport.java` (uma instrução)
- Test (create): `suko-core/src/test/java/io/suko/lang/client/JsHandlerTest.java`, `suko-core/src/test/java/io/suko/lang/client/HandlerDifferentialTest.java`

**Interfaces:**
- Consumes: o `JsTranslator` da Task 6.
- Produces:
  - `Result eventHandler(Expr.LambdaExpr)`: `on:evento` com zero parâmetros (D3: o evento é assumido e ignorado) ou um (o evento, tipo `EVENT`); devolve `Ok("(e) => { ... }", Callback)`.
  - `Result callback(Expr.LambdaExpr, List<ClientType> params)`: lambda para um filho reativo (`Runnable`/`Consumer<T>`), aridade exata.
  - Instruções aceites no corpo: `state = v`, `state += v` (String ou número; `int += double` recusado), `++`/`--` (prefixo ou sufixo), `lista.add(x)`/`remove(int)`/`set(i, x)`/`clear()` **só sobre uma `List` guardada num state**, `callback.accept(v)`/`run()`, `e.preventDefault()`, e qualquer chamada de método do subconjunto. Cada mutação de um state é seguida de `$u("nome")` (R17).

- [ ] **Step 1: Testes (falham)**

`suko-core/src/test/java/io/suko/lang/client/JsHandlerTest.java`:

```java
package io.suko.lang.client;

import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static io.suko.lang.client.JsTranslatorTest.T;
import static org.junit.jupiter.api.Assertions.*;

/** O JS dos handlers de on: e das lambdas para filhos reativos: mutações de state seguidas de $u("nome"). */
class JsHandlerTest {

    static String on(String lambda) {
        JsTranslator.Result r = T.eventHandler(lambda(lambda));
        return assertInstanceOf(JsTranslator.Ok.class, r, () -> lambda + " -> " + r).js();
    }

    static String onProblem(String lambda) {
        JsTranslator.Result r = T.eventHandler(lambda(lambda));
        return assertInstanceOf(JsTranslator.Err.class, r, () -> lambda + " -> " + r).problem().message();
    }

    @Test
    void stateMutations() {
        assertEquals("() => { $s.count = 0; $u(\"count\"); }", on("() -> count = 0"));
        assertEquals("() => { $s.count = (($s.count + 1) | 0); $u(\"count\"); }", on("() -> count++"));
        assertEquals("() => { $s.count = (($s.count - 1) | 0); $u(\"count\"); }", on("() -> --count"));
        assertEquals("() => { $s.draft = (\"\" + $s.draft + \"!\"); $u(\"draft\"); }", on("() -> draft += \"!\""));
        assertEquals("() => { $s.ratio = ($s.ratio + $s.count); $u(\"ratio\"); }", on("() -> ratio += count"));
        assertEquals("() => { $s.maybe = (($suko.nn($s.maybe) + 1) | 0); $u(\"maybe\"); }", on("() -> maybe++"));
    }

    @Test
    void listMutationsOnState() {
        assertEquals("() => { $s.items.push($s.draft); $u(\"items\"); $s.draft = \"\"; $u(\"draft\"); }",
            on("() -> { items.add(draft); draft = \"\"; }"));
        assertEquals("() => { $suko.removeAt($s.items, 0); $u(\"items\"); }", on("() -> items.remove(0)"));
        assertEquals("() => { $suko.setAt($s.items, 0, \"z\"); $u(\"items\"); }", on("() -> items.set(0, \"z\")"));
        assertEquals("() => { $suko.clear($s.items); $u(\"items\"); }", on("() -> items.clear()"));
    }

    @Test
    void theEventParameterIsOptional() {
        assertEquals("(e) => { $s.draft = $suko.evValue(e); $u(\"draft\"); }", on("e -> draft = e.value()"));
        assertEquals("(e) => { $suko.evPrevent(e); }", on("e -> e.preventDefault()"));
        assertEquals("(ev) => { }", on("ev -> { }"));
    }

    @Test
    void callbacksForReactiveChildren() {
        JsTranslator.Result r = T.callback(lambda("v -> count = v"), List.of(new ClientType.Boxed(ClientType.Scalar.INT)));
        assertEquals("(v) => { $s.count = $suko.nn(v); $u(\"count\"); }", ((JsTranslator.Ok) r).js());
        assertEquals("() => { $s.count = 0; $u(\"count\"); }",
            ((JsTranslator.Ok) T.callback(lambda("() -> count = 0"), List.of())).js());
        JsTranslator.Result wrong = T.callback(lambda("() -> count = 0"), List.of(ClientType.Scalar.STRING));
        assertTrue(((JsTranslator.Err) wrong).problem().message().contains("espera 1"));
    }

    @Test
    void onlyStateIsWritable() {
        assertTrue(onProblem("() -> name = \"x\"").contains("parâmetro"));
        assertTrue(onProblem("() -> remaining = 1").contains("derived"));
        assertTrue(onProblem("() -> nums.add(1)").contains("state"));
        assertTrue(onProblem("() -> count += ratio").contains("estreita"));
        assertTrue(onProblem("() -> count + 1").contains("não é uma instrução"));
        assertTrue(onProblem("(a) -> count = 1").contains("outro nome"));
        assertTrue(onProblem("() -> items.remove(maybe)").contains("índice int"));
        assertTrue(onProblem("() -> draft = count").contains("não é atribuível"));
    }
}
```

`suko-core/src/test/java/io/suko/lang/client/HandlerDifferentialTest.java`:

```java
package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import java.util.List;

import static io.suko.lang.client.ClientTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Handlers: depois de correr as mesmas instruções, o state é igual em Java e no Node (também quando lança). */
class HandlerDifferentialTest {

    static final List<Var> VARS = List.of(
        state("int", "count", "0"), state("List<String>", "items", "new ArrayList<>(List.of(\"a\"))"),
        state("String", "label", "\"x\""), state("double", "total", "0.5"), state("Integer", "n", "null"),
        param("int", "step", "5"));

    static final List<String> HANDLERS = List.of(
        "count++; count += step;",
        "count = 2147483647; count++;",
        "count--; count--;",
        "items.add(\"b\"); items.remove(0);",
        "items.set(0, \"z\");",
        "count++; items.remove(3);",
        "items.clear(); items.add(label);",
        "label += 1; label += total;",
        "total += count; total += step;",
        "n++;",
        "label = null; label += \"a\";",
        "count = step * 2; count = count % 3;",
        "items.add(label.trim()); count = items.size();");

    @Test
    void stateAfterTheHandlerIsTheSameInJavaAndInNode() throws Exception {
        List<Outcome> outcomes = run(HANDLERS.stream().map(h -> handler(h, VARS)).toList());
        for (Outcome o : outcomes) {
            assertEquals(o.java(), o.js(), o.c().source());
        }
    }
}
```

Em `ClientTestSupport.run`, a linha

```java
                JsTranslator.Result result = translator.expression(expr(c.source()));
```

passa a

```java
                JsTranslator.Result result = c.handler()
                    ? translator.eventHandler(lambda("() -> { " + c.source() + " }"))
                    : translator.expression(expr(c.source()));
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.client.JsHandlerTest' --tests 'io.suko.lang.client.HandlerDifferentialTest'`
Expected: FAIL (compilação: `eventHandler`/`callback` não existem).

- [ ] **Step 2: Handlers no `JsTranslator`**

Em `JsTranslator.java`, imediatamente antes da linha `    // ------------------------------------------------------------------ apoio`:

```java
    // ------------------------------------------------------------------ handlers (Task 7)

    private static final Set<String> LIST_MUTATIONS = Set.of("add", "remove", "set", "clear");

    /** Handler de {@code on:evento}: sem parâmetro (D3: o evento é assumido e ignorado) ou com um, o evento. */
    public Result eventHandler(Expr.LambdaExpr lambda) {
        if (lambda.params().size() > 1) {
            return new Err(new ClientProblem(CLIENT_UNSUPPORTED, "um handler de evento recebe no máximo um parâmetro (o evento)",
                lambda.span()));
        }
        return function(lambda, lambda.params().isEmpty() ? List.of() : List.of(EVENT));
    }

    /** Lambda passada a um componente reativo: {@code Runnable} (sem parâmetros) ou {@code Consumer<T>} (um). */
    public Result callback(Expr.LambdaExpr lambda, List<ClientType> params) {
        if (lambda.params().size() != params.size()) {
            return new Err(new ClientProblem(CLIENT_UNSUPPORTED, "a lambda tem " + lambda.params().size()
                + " parâmetro(s), mas o parâmetro do componente espera " + params.size(), lambda.span()));
        }
        return function(lambda, params);
    }

    private Result function(Expr.LambdaExpr lambda, List<ClientType> paramTypes) {
        try {
            ClientScope inner = scope;
            List<String> names = new ArrayList<>();
            for (int i = 0; i < lambda.params().size(); i++) {
                String p = lambda.params().get(i);
                if (scope.lookup(p).isPresent()) {
                    throw unsupported(lambda.span(), "o parâmetro '" + p + "' tem o nome de um state, derived, parâmetro"
                        + " ou variável do componente; use outro nome");
                }
                inner = inner.with(p, ClientScope.Kind.LOCAL, paramTypes.get(i));
                names.add(JsText.identifier(p));
            }
            StringBuilder body = new StringBuilder();
            for (Expr statement : lambda.body()) {
                body.append(statement(statement, inner));
            }
            return new Ok("(" + String.join(", ", names) + ") => {" + body + " }", new ClientType.Callback(paramTypes));
        } catch (Stop s) {
            return new Err(s.problem);
        }
    }

    private String statement(Expr st, ClientScope s) {
        return switch (st) {
            case Expr.AssignExpr a -> assign(a, s);
            case Expr.UpdateExpr u -> update(u, s);
            case Expr.CallExpr c -> callStatement(c, s);
            default -> throw unsupported(st.span(), "não é uma instrução de um handler (atribuição a um state, ++/--/+=,"
                + " mutação de uma List em state, chamada de um callback ou e.preventDefault())");
        };
    }

    private String assign(Expr.AssignExpr a, ClientScope s) {
        String name = stateTarget(a.target(), s);
        ClientType type = s.lookup(name).orElseThrow().type();
        Typed v = value(expr(a.value(), s), a.value().span());
        String js;
        if (a.operator().equals("=")) {
            if (!ClientTypes.assignable(v.type(), type)) {
                throw unsupported(a.value().span(), v.type().java() + " não é atribuível a '" + name + "' (" + type.java() + ")");
            }
            js = coerce(v, type);
        } else if (type == STRING) {
            js = "(\"\" + $s." + name + " + " + concatOperand(v, a.value().span()) + ")";
        } else {
            Typed current = number(new Typed("$s." + name, type), a.target().span());
            Typed add = number(v, a.value().span());
            if (current.type() == INT && add.type() == DOUBLE) {
                throw unsupported(a.span(), "'" + name + " += double' estreita o resultado para int em Java; o cliente não"
                    + " o faz — use um state double");
            }
            js = current.type() == INT
                ? "((" + current.js() + " + " + add.js() + ") | 0)"
                : "(" + current.js() + " + " + add.js() + ")";
        }
        return " $s." + name + " = " + js + "; $u(" + JsText.string(name) + ");";
    }

    private String update(Expr.UpdateExpr u, ClientScope s) {
        String name = stateTarget(u.target(), s);
        Typed current = number(new Typed("$s." + name, s.lookup(name).orElseThrow().type()), u.target().span());
        String op = u.operator().equals("++") ? " + 1" : " - 1";
        String js = current.type() == INT ? "((" + current.js() + op + ") | 0)" : "(" + current.js() + op + ")";
        return " $s." + name + " = " + js + "; $u(" + JsText.string(name) + ");";
    }

    private static String stateTarget(Expr target, ClientScope s) {
        if (!(target instanceof Expr.PrimaryExpr p) || !io.suko.lang.ast.ExprNames.isIdentifier(p.text())) {
            throw unsupported(target.span(), "só um state pode ser alterado: escreva o nome do state (não a.b nem uma expressão)");
        }
        ClientScope.Binding b = s.lookup(p.text()).orElseThrow(() -> unsupported(target.span(),
            "'" + p.text() + "' não é um state deste componente"));
        if (b.type() == UNKNOWN) {
            throw silent(target.span());
        }
        return switch (b.kind()) {
            case STATE -> p.text();
            case DERIVED -> throw unsupported(target.span(), "'" + p.text() + "' é derived e não é atribuível");
            case PARAM -> throw unsupported(target.span(), "'" + p.text() + "' é um parâmetro; no cliente os parâmetros"
                + " são só de leitura (use um state)");
            case LOCAL -> throw unsupported(target.span(), "'" + p.text() + "' é uma variável local; só um state pode ser alterado");
        };
    }

    private String callStatement(Expr.CallExpr c, ClientScope s) {
        if (c.callee() instanceof Expr.AccessExpr a && LIST_MUTATIONS.contains(a.memberName())) {
            if (a.target() instanceof Expr.PrimaryExpr t
                    && s.lookup(t.text()).map(b -> b.type() instanceof ClientType.ListOf).orElse(false)) {
                return listMutation(t, a.memberName(), c, s);
            }
            Typed receiver = value(expr(a.target(), s), a.target().span());
            if (receiver.type() instanceof ClientType.ListOf) {
                throw unsupported(c.span(), "só uma List guardada num state pode ser alterada (a alteração não seria propagada)");
            }
        }
        return " " + expr(c, s).js() + ";";
    }

    private String listMutation(Expr.PrimaryExpr target, String m, Expr.CallExpr c, ClientScope s) {
        ClientScope.Binding b = s.lookup(target.text()).orElseThrow();
        if (b.kind() != ClientScope.Kind.STATE) {
            throw unsupported(c.span(), "só uma List guardada num state pode ser alterada (a alteração de '" + target.text()
                + "' não seria propagada)");
        }
        ClientType element = ((ClientType.ListOf) b.type()).element();
        String list = "$s." + target.text();
        List<Typed> a = args(c, s);
        String js = switch (m) {
            case "add" -> {
                if (a.size() != 1) {
                    throw unsupported(c.span(), "List.add(i, x) fica fora da v1: use add(x)");
                }
                yield list + ".push(" + element(a.get(0), element, c.args().get(0).span()) + ")";
            }
            case "remove" -> {
                arity(a, 1, c, m);
                if (a.get(0).type() != INT) {
                    throw unsupported(c.args().get(0).span(), "remove() só com um índice int (remove(Object) fica fora da v1)");
                }
                yield "$suko.removeAt(" + list + ", " + a.get(0).js() + ")";
            }
            case "set" -> {
                arity(a, 2, c, m);
                yield "$suko.setAt(" + list + ", " + index(a.get(0), c.args().get(0).span()).js() + ", "
                    + element(a.get(1), element, c.args().get(1).span()) + ")";
            }
            default -> {
                arity(a, 0, c, m);
                yield "$suko.clear(" + list + ")";
            }
        };
        return " " + js + "; $u(" + JsText.string(target.text()) + ");";
    }

    private static String element(Typed v, ClientType element, SourceSpan span) {
        if (!ClientTypes.assignable(v.type(), element)) {
            throw unsupported(span, v.type().java() + " não é atribuível a um elemento " + element.java());
        }
        return coerce(v, element);
    }
```

Run: `scripts/verify-isolated.sh :suko-jte:test :suko-core:test --tests 'io.suko.lang.client.*'`
Expected: PASS — `JsHandlerTest`, `HandlerDifferentialTest` (13 handlers: `++` com overflow, `+=` em `String`/`double`, `add`/`remove`/`set`/`clear`, `remove` fora do alcance a meio do handler, `null++` — o state final é igual em Java e no Node, também quando o handler lança) e os testes das Tasks 5–6.

- [ ] **Step 3: Commit**

```bash
git status
git add suko-jte/src/main/java/io/suko/jte/client/JsTranslator.java \
  suko-core/src/test/java/io/suko/lang/client/ClientTestSupport.java \
  suko-core/src/test/java/io/suko/lang/client/JsHandlerTest.java \
  suko-core/src/test/java/io/suko/lang/client/HandlerDifferentialTest.java
git commit -m "feat(13b): handlers de on:, callbacks e mutações de state no tradutor Java → JS"
```

---

### Task 8: `ClientModel` — o que corre no cliente num reactive component

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/client/Callees.java`, `suko-jte/src/main/java/io/suko/jte/client/ClientModel.java`
- Test (create): `suko-core/src/test/java/io/suko/lang/client/ClientModelTest.java`

**Interfaces:**
- Consumes: `JsTranslator` (Tasks 6–7), `ClientTypes`/`ClientScope`/`JavaTypes`/`ClientProblem` (Task 5), `HtmlSecurityRules.styleDeclarations`/`lower` (existentes), `ProjectView.resolveImports`/`resolveQualified`, `ParamInfo.of` (suko-api), `ExprNames` (Task 1).
- Produces (o plano 2 gera o JS a partir disto):
  - `new Callees(SukoFile, ProjectView)`, `Optional<Callees.Callee> resolve(String)` com `record Callee(boolean reactive, List<ParamInfo> params)`.
  - `ClientModel.of(ComponentDecl, Callees, JavaTypes)`; `sites()` (lista de `Site(Kind kind, Expr expr, ClientScope scope, HtmlElement element, Object node, JsTranslator.Result result)`, pela ordem do fonte, com `Kind` = `STATE_INIT, DERIVED, VAR, TEXT, SLOT, ATTRIBUTE, STYLE_VALUE, CONDITION, ITERABLE, SUBJECT, CASE, HANDLER, BIND, CHILD_ARG, CALLBACK`); `problems()`; `paramTypes()`, `param(String)`, `stateTypes()`, `derivedTypes()`; `isClientCreated(Statement)`, `isClientBlock(Statement)`, `isClientAttribute(Attribute)`; `clientElements()`; `clientAttributes()` (`record ClientAttribute(HtmlElement element, Attribute attribute)`); `clientReadParams()`.
  - Regras: uma expressão "depende de state" se lê um state, um derived ou uma variável que deles depende (`ExprNames`); o corpo de um if/for/switch cuja condição/lista/sujeito depende de state é **criado no cliente** (todas as expressões lá dentro são sites); fora disso, um atributo ou texto que depende de state é **atualizado no cliente**; R18 (parâmetros constantes), R24 (âmbito de state/derived, `var` do servidor, `for` que passa para o cliente). Os problemas silenciosos (consequência de outro já reportado — p.ex. um parâmetro fora do subconjunto, que o `PROP_NOT_SERIALIZABLE` explica) não aparecem em `problems()`; um site `BIND` nunca gera problema (o `BIND_TARGET` trata dele).

- [ ] **Step 1: Testes (falham)**

`suko-core/src/test/java/io/suko/lang/client/ClientModelTest.java`:

```java
package io.suko.lang.client;

import io.suko.jte.client.Callees;
import io.suko.jte.client.ClientModel;
import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectView;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.suko.jte.client.ClientModel.Site.Kind.*;
import static io.suko.jte.client.ClientType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

/** O que corre no cliente num reactive component: regiões, sites, tipos e parâmetros lidos (13b). */
class ClientModelTest {

    /** O modelo do componente {@code name} do ficheiro (os outros componentes do ficheiro resolvem-se como filhos). */
    static ClientModel model(String sk, String name) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(sk))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(sk);
        SukoFile file = builder.build(parser.compilationUnit());
        assertEquals(0, parser.getNumberOfSyntaxErrors(), sk);
        assertEquals(List.of(), builder.syntaxIssues(), sk);
        ComponentDecl c = file.components().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        return ClientModel.of(c, new Callees(file, ProjectView.EMPTY), ClientTestSupport.TYPES);
    }

    static List<ClientModel.Site.Kind> kinds(ClientModel m) {
        return m.sites().stream().map(ClientModel.Site::kind).toList();
    }

    static ClientProblem onlyProblem(ClientModel m) {
        assertEquals(1, m.problems().size(), m.problems().toString());
        return m.problems().get(0);
    }

    static final String TODO = """
        reactive component TodoList(List<String> initial, String placeholder, String footer) {
          state var items = initial;
          state var draft = "";
          state boolean showDone = true;
          derived var remaining = items.size();
          <section>
            <input bind:value=${draft} placeholder="${placeholder}" />
            <button on:click=${() -> { items.add(draft); draft = ""; }}>Adicionar</button>
            if (showDone) {
              <ul>for (var t : items) { <li>${t}</li> }</ul>
            }
            <p>${remaining} itens</p>
            <footer>${footer}</footer>
          </section>
        }
        """;

    @Test
    void todoListWithoutRecords() {
        ClientModel m = model(TODO, "TodoList");
        assertEquals(List.of(), m.problems());
        assertEquals(new ClientType.ListOf(STRING), m.stateTypes().get("items"));
        assertEquals(STRING, m.stateTypes().get("draft"));
        assertEquals(BOOLEAN, m.stateTypes().get("showDone"));
        assertEquals(INT, m.derivedTypes().get("remaining"));
        assertEquals(Set.of("initial"), m.clientReadParams(), "placeholder e footer ficam só no servidor (minimização)");
        assertEquals(List.of(STATE_INIT, STATE_INIT, STATE_INIT, DERIVED, BIND, HANDLER, CONDITION, ITERABLE, TEXT, TEXT),
            kinds(m));

        Statement.HtmlElement section = (Statement.HtmlElement) m.component().body().get(4);
        Statement.HtmlElement input = (Statement.HtmlElement) section.children().get(0);
        Statement.IfStmt ifStmt = (Statement.IfStmt) section.children().get(2);
        Statement.HtmlElement ul = (Statement.HtmlElement) ifStmt.thenBranch().get(0);
        Statement.ForStmt loop = (Statement.ForStmt) ul.children().get(0);
        Statement.HtmlElement li = (Statement.HtmlElement) loop.body().get(0);
        assertFalse(m.isClientCreated(section));
        assertFalse(m.isClientAttribute(input.attributes().get(0)), "placeholder não depende de state");
        assertTrue(m.isClientBlock(ifStmt));
        assertTrue(m.isClientCreated(ul) && m.isClientCreated(li));
        assertEquals(List.of(ul, li), m.clientElements());

        JsTranslator.Ok handler = (JsTranslator.Ok) m.sites().get(5).result();
        assertEquals("() => { $s.items.push($s.draft); $u(\"items\"); $s.draft = \"\"; $u(\"draft\"); }", handler.js());
    }

    @Test
    void aServerLoopMovesToTheClientWhenAHandlerReadsItsVariable() {
        ClientModel m = model("""
            reactive component Picker(List<String> options) {
              state String chosen = "";
              <ul>for (String o : options) { <li on:click=${() -> chosen = o}>${o}</li> }</ul>
              <p>${chosen}</p>
            }
            """, "Picker");
        assertEquals(List.of(), m.problems());
        Statement.ForStmt loop = (Statement.ForStmt) ((Statement.HtmlElement) m.component().body().get(1)).children().get(0);
        assertTrue(m.isClientBlock(loop));
        assertEquals(Set.of("options"), m.clientReadParams());
    }

    @Test
    void reactiveChildrenGetArgumentsAndCallbacks() {
        ClientModel m = model("""
            reactive component Stepper(int value, Consumer<Integer> onChange) {
              <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
            }

            reactive component C() {
              state int n = 0;
              Stepper(value = n, onChange = v -> n = v)
            }
            """, "C");
        assertEquals(List.of(), m.problems());
        assertEquals(List.of(STATE_INIT, CHILD_ARG, CALLBACK), kinds(m));
        assertEquals("(v) => { $s.n = $suko.nn(v); $u(\"n\"); }", ((JsTranslator.Ok) m.sites().get(2).result()).js());
    }

    @Test
    void styleDeclarationsAreTranslatedToJavaStrings() {
        ClientModel m = model("reactive component R() { state int pct = 50; <div style=\"width: ${pct}%\">x</div> }", "R");
        assertEquals(List.of(), m.problems());
        ClientModel.Site site = m.sites().get(1);
        assertEquals(STYLE_VALUE, site.kind());
        assertEquals("String($s.pct)", ((JsTranslator.Ok) site.result()).js());
    }

    @Test
    void typeNotInferred() {
        ClientProblem p = onlyProblem(model("reactive component R() { state var x = null; <p>${x}</p> }", "R"));
        assertEquals("TYPE_NOT_INFERRED", p.code());
        assertTrue(p.message().contains("'x'"), p.message());
    }

    @Test
    void simpleComponentInsideAStateBlock() {
        ClientProblem p = onlyProblem(model("""
            component Card() { <b>c</b> }
            reactive component R() { state boolean open = false; if (open) { Card() } }
            """, "R"));
        assertEquals("CLIENT_UNSUPPORTED", p.code());
        assertTrue(p.message().contains("'Card' não é reativo"), p.message());
    }

    @Test
    void wrongShapesAreClientUnsupported() {
        assertTrue(onlyProblem(model("reactive component R(String p) { state int n = 0; var label = p.trim(); <p>${n + label}</p> }", "R"))
            .message().contains("derived"));
        assertTrue(onlyProblem(model("reactive component R() { state int a = b; state int b = 1; <p>${a}</p> }", "R"))
            .message().contains("declarados antes"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = \"x\"; <p>${n}</p> }", "R"))
            .message().contains("não é atribuível"));
        assertTrue(onlyProblem(model("reactive component R() { state long big = 0; <p>x</p> }", "R"))
            .message().contains("2^53"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = 0; if (n) { <p>x</p> } }", "R"))
            .message().contains("condição"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = 0; for (var x : n) { <p>x</p> } }", "R"))
            .message().contains("List"));
        assertTrue(onlyProblem(model("""
            reactive component Child(int value) { <b>${value}</b> }
            reactive component C() { state int n = 0; Child(value = () -> n++) }
            """, "C")).message().contains("Consumer<T> nem Runnable"));
    }

    @Test
    void parameterTypesKeepWhatTheyAre() {
        ClientModel m = model("reactive component R(long id, Todo t, Product p, Component header) { state int n = 0; <p>${n}</p> }", "R");
        assertInstanceOf(ClientType.Unsupported.class, m.paramTypes().get("id"));
        assertInstanceOf(ClientType.RecordOf.class, m.paramTypes().get("t"));
        assertEquals(new ClientType.Named("Product"), m.paramTypes().get("p"));
        assertFalse(m.paramTypes().containsKey("header"), "slots não são parâmetros de valor");
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.client.ClientModelTest'`
Expected: FAIL (compilação: `ClientModel`/`Callees` não existem).

- [ ] **Step 2: `Callees` e `ClientModel`**

`suko-jte/src/main/java/io/suko/jte/client/Callees.java`:

```java
package io.suko.jte.client;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ParamInfo;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.ProjectView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolve o nome de uma chamada de componente para saber se é reativo e quais são os seus parâmetros, como o
 * emissor JTE resolve (o próprio ficheiro, os imports, nomes qualificados). Visibilidade e "não encontrado" são
 * do core (SemanticChecker), que já os reporta; aqui um nome que não resolve dá {@code Optional.empty()}.
 */
public final class Callees {

    public record Callee(boolean reactive, List<ParamInfo> params) {
    }

    private final Map<String, ComponentDecl> local = new HashMap<>();
    private final Map<String, ProjectIndexEntry> imported;
    private final ProjectView project;

    public Callees(SukoFile file, ProjectView project) {
        for (ComponentDecl c : file.components()) {
            local.putIfAbsent(c.name(), c);
        }
        this.imported = project.resolveImports(file.imports());
        this.project = project;
    }

    public Optional<Callee> resolve(String name) {
        ComponentDecl decl = local.get(name);
        if (decl != null) {
            return Optional.of(new Callee(decl.reactive(), decl.params().stream().map(ParamInfo::of).toList()));
        }
        ProjectIndexEntry entry = name.contains(".") ? project.resolveQualified(name).orElse(null) : imported.get(name);
        return Optional.ofNullable(entry).map(e -> new Callee(e.reactive(), e.params()));
    }
}
```

`suko-jte/src/main/java/io/suko/jte/client/ClientModel.java`:

```java
package io.suko.jte.client;

import io.suko.jte.HtmlSecurityRules;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.ExprNames;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.Type;
import io.suko.lang.project.ParamInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * O que corre no cliente num reactive component (13b): os tipos de state/derived, as regiões criadas no cliente
 * (os corpos de if/for/switch que dependem de state), os atributos e textos atualizados no cliente e cada
 * expressão que o JS vai avaliar ({@link Site}), já traduzida. Os parâmetros são constantes vindas de
 * {@code data-suko-props} (vista de raiz de ilha, R18). Usado pelo {@code ReactiveHtmlChecker}, pelo
 * {@code HtmlSecurityChecker} (Q6) e, no plano 2, pelo emissor das ilhas.
 */
public final class ClientModel {

    /** Uma expressão que o JS avalia, com o âmbito em que é avaliada e a tradução. */
    public record Site(Kind kind, Expr expr, ClientScope scope, Statement.HtmlElement element, Object node,
                       JsTranslator.Result result) {
        public enum Kind {
            /** Inicializador de um state. */ STATE_INIT,
            /** Valor de um derived. */ DERIVED,
            /** {@code var} que depende de state (ou dentro de uma região do cliente). */ VAR,
            /** Interpolação num nó de texto. */ TEXT,
            /** {@code ${slot}} numa região do cliente (o plano 2 clona o {@code <template>}; sem tradução). */ SLOT,
            /** Valor de um atributo atualizado no cliente. */ ATTRIBUTE,
            /** Valor de uma declaração {@code propriedade: ${v}} de um {@code style} atualizado no cliente (Q5). */ STYLE_VALUE,
            /** Condição de um if que passa para o cliente. */ CONDITION,
            /** Lista de um for que passa para o cliente. */ ITERABLE,
            /** Sujeito de um switch que passa para o cliente. */ SUBJECT,
            /** Valor de um case desse switch. */ CASE,
            /** Lambda de {@code on:evento}. */ HANDLER,
            /** Alvo de {@code bind:} (as regras são do BIND_TARGET; a tradução não gera problemas). */ BIND,
            /** Argumento (não lambda) para um componente reativo filho. */ CHILD_ARG,
            /** Lambda passada a um componente reativo filho. */ CALLBACK
        }
    }

    /** Um atributo atualizado no cliente e o seu elemento. */
    public record ClientAttribute(Statement.HtmlElement element, Statement.Attribute attribute) {
    }

    private static final String UNSUPPORTED = JsTranslator.CLIENT_UNSUPPORTED;
    private static final ClientType LATER = new ClientType.Unsupported("?",
        "um state só pode usar parâmetros e states declarados antes dele");

    private final ComponentDecl component;
    private final Callees callees;
    private final JavaTypes types;
    private final Map<String, ClientType> paramTypes = new LinkedHashMap<>();
    private final Map<String, Param.ValueParam> params = new LinkedHashMap<>();
    private final Map<String, ClientType> stateTypes = new LinkedHashMap<>();
    private final Map<String, ClientType> derivedTypes = new LinkedHashMap<>();
    private final Set<String> slotNames = new HashSet<>();
    private final List<Site> sites = new ArrayList<>();
    private final List<ClientProblem> problems = new ArrayList<>();
    private final Set<Statement> clientCreated = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Statement> clientBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Statement.Attribute> clientAttributeSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Statement.HtmlElement> clientElements = new ArrayList<>();
    private final List<ClientAttribute> clientAttributes = new ArrayList<>();

    private ClientModel(ComponentDecl component, Callees callees, JavaTypes types) {
        this.component = component;
        this.callees = callees;
        this.types = types;
    }

    public static ClientModel of(ComponentDecl component, Callees callees, JavaTypes types) {
        ClientModel model = new ClientModel(component, callees, types);
        model.build();
        return model;
    }

    public ComponentDecl component() {
        return component;
    }

    public List<Site> sites() {
        return Collections.unmodifiableList(sites);
    }

    /** Problemas a reportar (CLIENT_UNSUPPORTED, TYPE_NOT_INFERRED, RESERVED_NAME), sem os silenciosos. */
    public List<ClientProblem> problems() {
        return Collections.unmodifiableList(problems);
    }

    /** Tipos declarados dos parâmetros de valor (sem os slots), mesmo os que ficam fora do subconjunto. */
    public Map<String, ClientType> paramTypes() {
        return Collections.unmodifiableMap(paramTypes);
    }

    public Optional<Param.ValueParam> param(String name) {
        return Optional.ofNullable(params.get(name));
    }

    public Map<String, ClientType> stateTypes() {
        return Collections.unmodifiableMap(stateTypes);
    }

    public Map<String, ClientType> derivedTypes() {
        return Collections.unmodifiableMap(derivedTypes);
    }

    public boolean isClientCreated(Statement s) {
        return clientCreated.contains(s);
    }

    /** if/for/switch cuja avaliação passa para o cliente (a raiz de uma região criada no cliente). */
    public boolean isClientBlock(Statement s) {
        return clientBlocks.contains(s);
    }

    public boolean isClientAttribute(Statement.Attribute a) {
        return clientAttributeSet.contains(a);
    }

    /** Elementos criados no cliente, pela ordem do fonte. */
    public List<Statement.HtmlElement> clientElements() {
        return Collections.unmodifiableList(clientElements);
    }

    /** Atributos atualizados no cliente (de elementos criados no cliente ou do servidor), pela ordem do fonte. */
    public List<ClientAttribute> clientAttributes() {
        return Collections.unmodifiableList(clientAttributes);
    }

    /** Os parâmetros que o código do cliente lê: só esses vão em {@code data-suko-props} (minimização da spec). */
    public Set<String> clientReadParams() {
        Set<String> out = new LinkedHashSet<>();
        for (Site s : sites) {
            if (s.expr() == null || s.kind() == Site.Kind.BIND) {
                continue;
            }
            for (String name : ExprNames.of(s.expr())) {
                if (paramTypes.containsKey(name)
                        && s.scope().lookup(name).map(b -> b.kind() == ClientScope.Kind.PARAM).orElse(false)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ construção

    private void build() {
        ClientScope scope = ClientScope.EMPTY;
        for (Param p : component.params()) {
            switch (p) {
                case Param.ValueParam v -> {
                    ClientType t = ClientTypes.of(v.type(), types);
                    paramTypes.put(v.name(), t);
                    params.put(v.name(), v);
                    // Fora do subconjunto fica UNKNOWN (silencioso): quem explica é o PROP_NOT_SERIALIZABLE.
                    scope = scope.with(v.name(), ClientScope.Kind.PARAM,
                        t instanceof ClientType.Unsupported ? ClientType.Special.UNKNOWN : t);
                }
                case Param.SlotParam s -> {
                    slotNames.add(s.name());
                    scope = scope.with(s.name(), ClientScope.Kind.PARAM,
                        new ClientType.Unsupported("Component", "um slot só se insere no cliente com ${" + s.name() + "}"));
                }
            }
        }

        List<Statement.StateDecl> states = new ArrayList<>();
        Map<String, Statement.DerivedDecl> derived = new LinkedHashMap<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.StateDecl d) {
                states.add(d);
            } else if (s instanceof Statement.DerivedDecl d) {
                derived.putIfAbsent(d.name(), d);
            }
        }

        // R24: um state vê os parâmetros e os states anteriores.
        ClientScope stateScope = scope;
        for (int i = 0; i < states.size(); i++) {
            ClientScope visible = stateScope;
            for (int j = i; j < states.size(); j++) {
                visible = visible.with(states.get(j).name(), ClientScope.Kind.STATE, LATER);
            }
            for (String name : derived.keySet()) {
                visible = visible.with(name, ClientScope.Kind.DERIVED, LATER);
            }
            Statement.StateDecl d = states.get(i);
            ClientType t = declare(d.type(), d.value(), d.name(), d.nameSpan(), visible, Site.Kind.STATE_INIT, d);
            stateTypes.put(d.name(), t);
            stateScope = stateScope.with(d.name(), ClientScope.Kind.STATE, t);
        }

        // R24: um derived vê tudo; ordem topológica; os de um ciclo ficam UNKNOWN (DERIVED_CYCLE é do core).
        ClientScope full = stateScope;
        for (String name : derived.keySet()) {
            full = full.with(name, ClientScope.Kind.DERIVED, ClientType.Special.UNKNOWN);
        }
        for (String name : topological(derived)) {
            Statement.DerivedDecl d = derived.get(name);
            ClientType t = declare(d.type(), d.value(), d.name(), d.nameSpan(), full, Site.Kind.DERIVED, d);
            derivedTypes.put(name, t);
            full = full.with(name, ClientScope.Kind.DERIVED, t);
        }
        for (String name : derived.keySet()) {
            derivedTypes.putIfAbsent(name, ClientType.Special.UNKNOWN);
        }

        Set<String> clientNames = new HashSet<>(stateTypes.keySet());
        clientNames.addAll(derived.keySet());
        walk(component.body(), full, false, clientNames);
    }

    private static List<String> topological(Map<String, Statement.DerivedDecl> derived) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, Statement.DerivedDecl> e : derived.entrySet()) {
            Set<String> d = new LinkedHashSet<>(ExprNames.of(e.getValue().value()));
            d.retainAll(derived.keySet());
            deps.put(e.getKey(), d);
        }
        List<String> order = new ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
                if (!order.contains(e.getKey()) && order.containsAll(e.getValue())) {
                    order.add(e.getKey());
                    progress = true;
                }
            }
        }
        return order;
    }

    private ClientType declare(Optional<Type> declared, Expr value, String name, SourceSpan nameSpan,
                               ClientScope scope, Site.Kind kind, Statement node) {
        JsTranslator.Result r = translator(scope).expression(value);
        site(kind, value, scope, null, node, r);
        if (declared.isPresent()) {
            ClientType t = ClientTypes.of(declared.get(), types);
            if (t instanceof ClientType.Unsupported u) {
                problem(UNSUPPORTED, "'" + name + "' (" + u.javaType() + "): " + u.reason(), nameSpan);
                return ClientType.Special.UNKNOWN;
            }
            if (r instanceof JsTranslator.Ok ok && !ClientTypes.assignable(ok.type(), t)) {
                problem(UNSUPPORTED, "o valor (" + ok.type().java() + ") não é atribuível a '" + name + "' (" + t.java() + ")",
                    value.span());
            }
            return t;
        }
        return r instanceof JsTranslator.Ok ok ? inferred(ok.type(), name, nameSpan) : ClientType.Special.UNKNOWN;
    }

    private ClientType inferred(ClientType t, String name, SourceSpan nameSpan) {
        if (t == ClientType.Special.NULL || t == ClientType.Special.VOID || t == ClientType.Special.EVENT) {
            problem("TYPE_NOT_INFERRED", "não é possível inferir o tipo de '" + name + "' a partir de " + t.java()
                + ": escreva o tipo (ex.: String " + name + " = ...)", nameSpan);
            return ClientType.Special.UNKNOWN;
        }
        return t;
    }

    private void walk(List<Statement> statements, ClientScope scope, boolean client, Set<String> clientNames) {
        for (Statement s : statements) {
            if (client) {
                clientCreated.add(s);
            }
            switch (s) {
                case Statement.StateDecl d -> { }
                case Statement.DerivedDecl d -> { }
                case Statement.TextRun t -> { }
                case Statement.VarDecl v -> {
                    if (client || depends(v.value(), clientNames)) {
                        JsTranslator.Result r = translator(scope).expression(v.value());
                        site(Site.Kind.VAR, v.value(), scope, null, v, r);
                        ClientType t = r instanceof JsTranslator.Ok ok ? inferred(ok.type(), v.name(), v.span())
                            : ClientType.Special.UNKNOWN;
                        scope = scope.with(v.name(), ClientScope.Kind.LOCAL, t);
                        clientNames = plus(clientNames, v.name());
                    } else {
                        scope = scope.with(v.name(), ClientScope.Kind.LOCAL, new ClientType.Unsupported("var",
                            "'" + v.name() + "' é uma variável do servidor; para a usar no cliente declare-a como derived"));
                    }
                }
                case Statement.Interpolation i -> {
                    if (client || depends(i.expr(), clientNames)) {
                        if (i.expr() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text())) {
                            site(Site.Kind.SLOT, i.expr(), scope, null, i, null);
                        } else {
                            site(Site.Kind.TEXT, i.expr(), scope, null, i, translator(scope).text(i.expr()));
                        }
                    }
                }
                case Statement.HtmlElement e -> {
                    if (client) {
                        clientElements.add(e);
                    }
                    for (Statement.Attribute a : e.attributes()) {
                        attribute(e, a, scope, client, clientNames);
                    }
                    for (Statement.Directive d : e.directives()) {
                        directive(e, d, scope);
                    }
                    walk(e.children(), scope, client, clientNames);
                }
                case Statement.IfStmt i -> {
                    boolean moves = client || depends(i.condition(), clientNames);
                    if (moves) {
                        clientBlocks.add(i);
                        site(Site.Kind.CONDITION, i.condition(), scope, null, i,
                            expect(translator(scope).expression(i.condition()), ClientModel::isBoolean,
                                "uma condição tem de ser boolean", i.condition().span()));
                    }
                    walk(i.thenBranch(), scope, moves, clientNames);
                    walk(i.elseBranch(), scope, moves, clientNames);
                }
                case Statement.ForStmt f -> forLoop(f, scope, client, clientNames);
                case Statement.SwitchStmt sw -> {
                    boolean moves = client || depends(sw.subject(), clientNames);
                    if (moves) {
                        clientBlocks.add(sw);
                        JsTranslator.Result subject = translator(scope).expression(sw.subject());
                        site(Site.Kind.SUBJECT, sw.subject(), scope, null, sw, subject);
                        for (Statement.SwitchCase c : sw.cases()) {
                            site(Site.Kind.CASE, c.matchValue(), scope, null, c,
                                caseValue(subject, translator(scope).expression(c.matchValue()), c.matchValue().span()));
                        }
                    }
                    for (Statement.SwitchCase c : sw.cases()) {
                        walk(c.body(), scope, moves, clientNames);
                    }
                    walk(sw.defaultCase(), scope, moves, clientNames);
                }
                case Statement.ComponentCallStmt call -> call(call, scope, client, clientNames);
            }
        }
    }

    private void attribute(Statement.HtmlElement e, Statement.Attribute a, ClientScope scope, boolean client,
                           Set<String> clientNames) {
        if (!(client || depends(a.value(), clientNames))) {
            return;
        }
        clientAttributeSet.add(a);
        clientAttributes.add(new ClientAttribute(e, a));
        if (HtmlSecurityRules.lower(a.name()).equals("style")) {
            Optional<List<HtmlSecurityRules.StyleChunk>> chunks = HtmlSecurityRules.styleDeclarations(a.value());
            if (chunks.isPresent()) {
                for (HtmlSecurityRules.StyleChunk chunk : chunks.get()) {
                    if (chunk.interpolation() != null) {
                        site(Site.Kind.STYLE_VALUE, chunk.interpolation(), scope, e, a,
                            translator(scope).javaString(chunk.interpolation()));
                    }
                }
                return;
            }
        }
        site(Site.Kind.ATTRIBUTE, a.value(), scope, e, a, expect(translator(scope).expression(a.value()),
            ClientModel::isAttributeValue, "o valor de um atributo no cliente tem de ser texto, número ou boolean",
            a.value().span()));
    }

    private void directive(Statement.HtmlElement e, Statement.Directive d, ClientScope scope) {
        if (d.kind() == Statement.Directive.Kind.ON) {
            if (d.value() instanceof Expr.LambdaExpr lambda) {
                site(Site.Kind.HANDLER, lambda, scope, e, d, translator(scope).eventHandler(lambda));
            }
        } else {
            site(Site.Kind.BIND, d.value(), scope, e, d, translator(scope).expression(d.value()));
        }
    }

    private void forLoop(Statement.ForStmt f, ClientScope scope, boolean client, Set<String> clientNames) {
        boolean moves = client || depends(f.iterable(), clientNames) || needsLoopVariable(f.body(), f.itemName(), clientNames);
        if (!moves) {
            walk(f.body(), scope.with(f.itemName(), ClientScope.Kind.LOCAL, new ClientType.Unsupported("var",
                "'" + f.itemName() + "' é a variável de um for do servidor")), false, clientNames);
            return;
        }
        clientBlocks.add(f);
        JsTranslator.Result r = translator(scope).expression(f.iterable());
        ClientType item = ClientType.Special.UNKNOWN;
        if (r instanceof JsTranslator.Ok ok) {
            if (ok.type() instanceof ClientType.ListOf list) {
                if (f.itemType().name().equals("var")) {
                    item = list.element();
                } else {
                    ClientType declared = ClientTypes.of(f.itemType(), types);
                    if (ClientTypes.assignable(list.element(), declared)) {
                        item = declared;
                    } else {
                        r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "os elementos (" + list.element().java()
                            + ") não são atribuíveis a " + f.itemType().name(), f.iterable().span()));
                    }
                }
            } else {
                r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "um for no cliente percorre uma List (recebeu "
                    + ok.type().java() + ")", f.iterable().span()));
            }
        }
        site(Site.Kind.ITERABLE, f.iterable(), scope, null, f, r);
        walk(f.body(), scope.with(f.itemName(), ClientScope.Kind.LOCAL, item), true, plus(clientNames, f.itemName()));
    }

    /** R24: um for do servidor cujo corpo tem código do cliente que lê a variável do ciclo passa para o cliente. */
    private boolean needsLoopVariable(List<Statement> body, String var, Set<String> clientNames) {
        for (Statement s : body) {
            boolean hit = switch (s) {
                case Statement.HtmlElement e -> e.directives().stream().anyMatch(d -> ExprNames.of(d.value()).contains(var))
                    || e.attributes().stream().anyMatch(a -> uses(a.value(), var, clientNames))
                    || needsLoopVariable(e.children(), var, clientNames);
                case Statement.Interpolation i -> uses(i.expr(), var, clientNames);
                case Statement.IfStmt i -> uses(i.condition(), var, clientNames)
                    || needsLoopVariable(i.thenBranch(), var, clientNames) || needsLoopVariable(i.elseBranch(), var, clientNames);
                case Statement.ForStmt f -> uses(f.iterable(), var, clientNames) || needsLoopVariable(f.body(), var, clientNames);
                case Statement.SwitchStmt sw -> uses(sw.subject(), var, clientNames)
                    || sw.cases().stream().anyMatch(c -> needsLoopVariable(c.body(), var, clientNames))
                    || needsLoopVariable(sw.defaultCase(), var, clientNames);
                case Statement.ComponentCallStmt c -> c.args().stream().anyMatch(a -> a.value() instanceof Expr.LambdaExpr
                        ? ExprNames.of(a.value()).contains(var) : uses(a.value(), var, clientNames))
                    || c.slotFills().stream().anyMatch(fill -> needsLoopVariable(fill.body(), var, clientNames));
                case Statement.VarDecl v -> uses(v.value(), var, clientNames);
                case Statement.TextRun t -> false;
                case Statement.StateDecl d -> false;
                case Statement.DerivedDecl d -> false;
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** A expressão corre no cliente (lê state) e lê a variável. */
    private static boolean uses(Expr e, String var, Set<String> clientNames) {
        Set<String> names = ExprNames.of(e);
        return names.contains(var) && names.stream().anyMatch(clientNames::contains);
    }

    private void call(Statement.ComponentCallStmt call, ClientScope scope, boolean client, Set<String> clientNames) {
        Optional<Callees.Callee> callee = callees.resolve(call.componentName());
        boolean dependentArgs = call.args().stream()
            .anyMatch(a -> !(a.value() instanceof Expr.LambdaExpr) && depends(a.value(), clientNames));
        boolean simple = callee.isPresent() && !callee.get().reactive();
        if (simple && (client || dependentArgs)) {
            problem(UNSUPPORTED, "'" + call.componentName() + "' não é reativo e não pode ser "
                + (client ? "criado" : "atualizado") + " no cliente (num bloco que depende de state só entram componentes reativos)",
                call.nameSpan().isNone() ? call.span() : call.nameSpan());
        }
        List<ParamInfo> calleeParams = callee.map(Callees.Callee::params).orElse(List.of());
        for (int i = 0; i < call.args().size(); i++) {
            Statement.Arg arg = call.args().get(i);
            Optional<ParamInfo> param = parameter(calleeParams, arg, i);
            if (arg.value() instanceof Expr.LambdaExpr lambda) {
                if (callee.isEmpty() || simple || param.isEmpty()) {
                    continue; // core: LAMBDA_ACROSS_BOUNDARY / COMPONENT_NOT_FOUND / PARAM_NOT_FOUND
                }
                ClientType type = ClientTypes.of(ClientTypes.parse(param.get().type()), types);
                JsTranslator.Result r = type instanceof ClientType.Callback cb
                    ? translator(scope).callback(lambda, cb.params())
                    : new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "o parâmetro '" + param.get().name() + "' ("
                        + param.get().type() + ") de " + call.componentName() + " não é Consumer<T> nem Runnable", lambda.span()));
                site(Site.Kind.CALLBACK, lambda, scope, null, arg, r);
            } else if (!simple && (client || depends(arg.value(), clientNames))) {
                JsTranslator.Result r = translator(scope).expression(arg.value());
                if (r instanceof JsTranslator.Ok ok && param.isPresent() && !param.get().slot()) {
                    ClientType type = ClientTypes.of(ClientTypes.parse(param.get().type()), types);
                    if (!(type instanceof ClientType.Unsupported) && !ClientTypes.assignable(ok.type(), type)) {
                        r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, ok.type().java() + " não é atribuível ao parâmetro '"
                            + param.get().name() + "' (" + param.get().type() + ")", arg.value().span()));
                    }
                }
                site(Site.Kind.CHILD_ARG, arg.value(), scope, null, arg, r);
            }
        }
        for (Statement.SlotFill fill : call.slotFills()) {
            walk(fill.body(), scope, client, clientNames);
        }
    }

    private static Optional<ParamInfo> parameter(List<ParamInfo> params, Statement.Arg arg, int index) {
        if (arg.name().isPresent()) {
            return params.stream().filter(p -> p.name().equals(arg.name().get())).findFirst();
        }
        return index < params.size() ? Optional.of(params.get(index)) : Optional.empty();
    }

    private static JsTranslator.Result caseValue(JsTranslator.Result subject, JsTranslator.Result value, SourceSpan span) {
        if (subject instanceof JsTranslator.Ok s && value instanceof JsTranslator.Ok v) {
            boolean text = (s.type() == ClientType.Scalar.STRING || s.type() == ClientType.Special.NULL)
                && (v.type() == ClientType.Scalar.STRING || v.type() == ClientType.Special.NULL);
            ClientType.Scalar ps = ClientTypes.primitive(s.type());
            ClientType.Scalar pv = ClientTypes.primitive(v.type());
            boolean numbers = (ps == ClientType.Scalar.INT || ps == ClientType.Scalar.DOUBLE)
                && (pv == ClientType.Scalar.INT || pv == ClientType.Scalar.DOUBLE);
            boolean booleans = ps == ClientType.Scalar.BOOLEAN && pv == ClientType.Scalar.BOOLEAN;
            if (!(text || numbers || booleans)) {
                return new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "o case (" + v.type().java()
                    + ") não é comparável com o switch (" + s.type().java() + ") no cliente", span));
            }
        }
        return value;
    }

    private static JsTranslator.Result expect(JsTranslator.Result r, Predicate<ClientType> accepted, String message,
                                              SourceSpan span) {
        if (r instanceof JsTranslator.Ok ok && !accepted.test(ok.type())) {
            return new JsTranslator.Err(new ClientProblem(UNSUPPORTED, message + " (recebeu " + ok.type().java() + ")", span));
        }
        return r;
    }

    private static boolean isBoolean(ClientType t) {
        return t == ClientType.Scalar.BOOLEAN || t.equals(new ClientType.Boxed(ClientType.Scalar.BOOLEAN));
    }

    private static boolean isAttributeValue(ClientType t) {
        return t instanceof ClientType.Scalar || t instanceof ClientType.Boxed || t == ClientType.Special.NULL;
    }

    private static boolean depends(Expr e, Set<String> clientNames) {
        for (String name : ExprNames.of(e)) {
            if (clientNames.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> plus(Set<String> names, String name) {
        Set<String> out = new HashSet<>(names);
        out.add(name);
        return out;
    }

    private JsTranslator translator(ClientScope scope) {
        return new JsTranslator(scope, types);
    }

    private void site(Site.Kind kind, Expr expr, ClientScope scope, Statement.HtmlElement element, Object node,
                      JsTranslator.Result result) {
        sites.add(new Site(kind, expr, scope, element, node, result));
        if (kind != Site.Kind.BIND && result instanceof JsTranslator.Err err && !err.problem().silent()) {
            problems.add(err.problem());
        }
    }

    private void problem(String code, String message, SourceSpan span) {
        problems.add(new ClientProblem(code, message, span));
    }
}
```

Run: `scripts/verify-isolated.sh :suko-jte:test :suko-core:test --tests 'io.suko.lang.client.*'`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git status
git add suko-jte/src/main/java/io/suko/jte/client/Callees.java suko-jte/src/main/java/io/suko/jte/client/ClientModel.java \
  suko-core/src/test/java/io/suko/lang/client/ClientModelTest.java
git commit -m "feat(13b): ClientModel — regiões do cliente, sites traduzidos e parâmetros lidos no cliente"
```

---

### Task 9: `ReactiveHtmlChecker` e `trusted*` só no servidor (Q6)

**Pré-condição cumprida:** a revisão do `security-specialist` (`.superpowers/sdd/13b-plano1/security-review.md`) está feita e as suas correções para esta task (S1, S2, S8, S9) já estão no texto abaixo; os rulings R10 e R26–R29 são o contexto.

**Files:**
- Create: `suko-jte/src/main/java/io/suko/jte/ReactiveHtmlChecker.java`
- Modify: `suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java`, `suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java`, `suko-jte/src/main/java/io/suko/jte/JteExtension.java`
- Test (create): `suko-core/src/test/java/io/suko/lang/security/ReactiveHtmlCheckerTest.java`

**Interfaces:**
- Consumes: `ClientModel`, `Callees`, `JavaTypes` (Task 8), `CheckContext` (`report`, `diagnostics()`, `fileName()`, `project()`, `activeVocabularies()`).
- Produces:
  - Checker `html-reactive` (`ReactiveHtmlChecker`, registado pelo `io.suko.jte` **depois** do `html-security`): `CLIENT_UNSUPPORTED`/`TYPE_NOT_INFERRED` (os `problems()` do `ClientModel`), `CLIENT_UNSAFE_SINK` (R20), `BIND_TARGET` de nome/elemento/tipo (R21; só quando o alvo é um state simples), `PROP_NOT_SERIALIZABLE` (ERROR), `PROP_SENSITIVE` (WARNING, também nos componentes de records conhecidos), `PROP_MAP_KEYS` (INFO), com o R10 revisto (um ERROR só é omitido por um diagnóstico do par declarado em `SUPPRESSED_BY`; `PROP_*` e `TYPE_NOT_INFERRED` nunca).
  - `public ReactiveHtmlChecker()` (produção, `JavaTypes.NONE`, R13) e `public ReactiveHtmlChecker(JavaTypes)` (formas de records conhecidas; só os testes a usam).
  - `HtmlSecurityChecker`: num reactive component, `trustedUrl`/`trustedStyle` como valor de um atributo atualizado no cliente dá `RESERVED_NAME` (e não `TRUSTED_*`), por isso `security-audit.json` só regista usos do servidor (Q6). O gate do modo "filho reativo" é o teste `@Disabled` abaixo (R29).
  - `HtmlSecurityRules.CONTENT_SINKS`, `ANIMATION` (agora públicos) e `CLIENT_FORBIDDEN_ELEMENTS` (constante única, R27) — `ReactiveHtmlChecker` e `HtmlSecurityChecker` usam-nas, sem cópia.

- [ ] **Step 1: Testes (falham)**

`suko-core/src/test/java/io/suko/lang/security/ReactiveHtmlCheckerTest.java`:

```java
package io.suko.lang.security;

import io.suko.ext.*;
import io.suko.jte.HtmlSecurityRules;
import io.suko.jte.ReactiveHtmlChecker;
import io.suko.jte.client.ClientTypes;
import io.suko.jte.client.JavaTypes;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.ext.ExtensionRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * As regras da 13b no alvo JTE: CLIENT_UNSUPPORTED, TYPE_NOT_INFERRED, CLIENT_UNSAFE_SINK, BIND_TARGET de
 * elemento/tipo, PROP_*, trusted* só no servidor (Q6) e um diagnóstico por sítio. Até ao plano 2 todo o reactive
 * component dá também REACTIVE_UNSUPPORTED (R1), que estes testes ignoram.
 */
class ReactiveHtmlCheckerTest {

    static List<SukoDiagnostic> diagnostics(String sk) {
        return new JteCompiler("T.sk", sk).compile().diagnostics().getDiagnostics().stream()
            .filter(d -> !d.code().equals("REACTIVE_UNSUPPORTED")).toList();
    }

    static Set<String> codes(String sk) {
        return diagnostics(sk).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    static long count(String sk, String code) {
        return diagnostics(sk).stream().filter(d -> d.code().equals(code)).count();
    }

    static final String EXAMPLES = """
        reactive component TodoList(List<String> initial, String placeholder) {
          state var items = initial;
          state var draft = "";
          state boolean showDone = true;
          derived var remaining = items.size();
          <section class="todos">
            <input bind:value=${draft} placeholder="${placeholder}" />
            <button on:click=${() -> { items.add(draft); draft = ""; }}>Adicionar</button>
            <label><input type="checkbox" bind:checked=${showDone} /> Mostrar feitos</label>
            if (showDone) {
              <ul>for (var t : items) { <li>${t}</li> }</ul>
            }
            <p>${remaining} itens</p>
          </section>
        }

        reactive component Stepper(int value, Consumer<Integer> onChange) {
          <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
        }

        reactive component Counter(int start) {
          state var count = start;
          Stepper(value = count, onChange = v -> count = v)
          <button on:click=${() -> count = 0}>reset</button>
        }
        """;

    @Test
    void theSpecExamplesWithoutRecordsOnlyWaitForPlanTwo() {
        assertEquals(List.of(), diagnostics(EXAMPLES));
        assertTrue(new JteCompiler("T.sk", EXAMPLES).compile().diagnostics().getDiagnostics().stream()
            .allMatch(d -> d.code().equals("REACTIVE_UNSUPPORTED")));
    }

    @Test
    void clientUnsupportedPointsAtTheSubexpressionAndOnlyInClientCode() {
        String sk = "reactive component R(String s) { state boolean open = false; if (open) { <p>${s ?: \"x\"}</p> } }";
        SukoDiagnostic d = diagnostics(sk).stream().filter(x -> x.code().equals("CLIENT_UNSUPPORTED")).findFirst().orElseThrow();
        assertEquals(sk.indexOf("s ?:"), d.span().startColumn());
        assertTrue(d.message().contains("?:"), d.message());
        String server = "reactive component R(String s) { state boolean open = false; <p>${s ?: \"x\"}</p>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(Set.of(), codes(server), "no render do servidor o ?: é Java");
    }

    @Test
    void simpleComponentInsideAStateBlock() {
        String sk = "component Card() { <b>c</b> } reactive component R() { state boolean open = false; if (open) { Card() } }";
        SukoDiagnostic d = diagnostics(sk).get(0);
        assertEquals("CLIENT_UNSUPPORTED", d.code());
        assertTrue(d.message().contains("'Card' não é reativo"), d.message());
    }

    @Test
    void typeNotInferred() {
        assertEquals(Set.of("TYPE_NOT_INFERRED"), codes("reactive component R() { state var x = null; <p>${x}</p> }"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>1</script>", "<style>p</style>", "<iframe></iframe>", "<frame/>", "<object></object>",
        "<embed/>", "<base/>", "<meta/>", "<link/>", "<template>x</template>", "<svg><animate/></svg>", "<svg><set/></svg>",
        "<svg><animateMotion/></svg>", "<svg><animateTransform/></svg>", "<svg><foreignObject>x</foreignObject></svg>",
        "<svg><script>1</script></svg>", "<noscript>x</noscript>", "<fencedframe></fencedframe>", "<portal></portal>",
        "<applet></applet>", "<frameset></frameset>"})
    void dangerousElementsAreNeverCreatedOnTheClient(String element) {
        String client = "reactive component R() { state boolean open = false; if (open) { " + element + " } }";
        assertEquals(1, count(client, "CLIENT_UNSAFE_SINK"), client + " -> " + diagnostics(client));
        String server = "reactive component R() { state boolean open = false; " + element
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(0, count(server, "CLIENT_UNSAFE_SINK"), server + " -> " + diagnostics(server));
    }

    @Test
    void oneDiagnosticPerSite() {
        String script = "reactive component R(String x) { state boolean open = false; if (open) { <script>${x}</script> } }";
        assertEquals(Set.of("UNSAFE_SINK"), codes(script), diagnostics(script).toString());
        String origin = "reactive component R() { state String page = \"a\"; <iframe src=\"https://x.example/${page}\"></iframe> }";
        assertEquals(List.of("CLIENT_UNSAFE_SINK"), diagnostics(origin).stream().map(SukoDiagnostic::code).toList(),
            "a origem constante é aceite no servidor (14) mas o cliente não a implementa");
        String bind = "reactive component R() { state String s = \"\"; derived String d = s; <div bind:value=${d}></div> }";
        assertEquals(1, count(bind, "BIND_TARGET"), diagnostics(bind).toString());
    }

    /** R10 (S2): só os pares declarados se fundem; um ERROR que envolve o sítio não esconde o que está dentro. */
    @Test
    void anEnclosingErrorDoesNotHideTheProblemsInsideIt() {
        String upper = "reactive component R() { state boolean open = false; <MAIN> if (open) { <iframe></iframe> } </MAIN>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertTrue(codes(upper).containsAll(Set.of("UPPERCASE_NAME", "CLIENT_UNSAFE_SINK")), diagnostics(upper).toString());
        // se o parse recusar este corpo de <script>, ajustar o fixture mantendo a ideia: um ERROR que envolve + um sink dentro
        String script = "reactive component R(String x) { state boolean open = false;"
            + " <script>${x} if (open) { <iframe></iframe> }</script> <b on:click=${() -> open = !open}>t</b> }";
        assertTrue(codes(script).containsAll(Set.of("UNSAFE_SINK", "CLIENT_UNSAFE_SINK")), diagnostics(script).toString());
    }

    @Test
    void twoDistinctSinksGiveTwoDiagnosticsEvenWhenTheirSpansAreAdjacent() {
        String apart = "reactive component R(String x) { state String s = \"\"; state boolean open = false;"
            + " <b onclick=${x} on:click=${() -> open = !open}>t</b> if (open) { <iframe></iframe> } }";
        assertEquals(Set.of("UNSAFE_SINK", "CLIENT_UNSAFE_SINK"), codes(apart), diagnostics(apart).toString());
        String adjacent = "reactive component R(String x) { state String s = \"\"; <script>${x}</script><iframe title=${s}></iframe>"
            + " <b on:click=${() -> s = \"y\"}>t</b> }";
        assertEquals(1, count(adjacent, "UNSAFE_SINK"), diagnostics(adjacent).toString());
        assertEquals(1, count(adjacent, "CLIENT_UNSAFE_SINK"), diagnostics(adjacent).toString());
    }

    @Test
    void propDiagnosticsAreNeverSuppressedByAnotherError() {
        String sk = "reactive component R(long v, String x) { state var copy = v; <script>${x}</script> }";
        assertEquals(1, count(sk, "PROP_NOT_SERIALIZABLE"), diagnostics(sk).toString());
        assertEquals(1, count(sk, "UNSAFE_SINK"), diagnostics(sk).toString());
    }

    /** R27 (S8): uma só constante, sem cópia que se desvie do 14. */
    @Test
    void theForbiddenElementsAreOneConstantThatIncludesTheRulesOf14() {
        Set<String> forbidden = HtmlSecurityRules.CLIENT_FORBIDDEN_ELEMENTS;
        assertTrue(forbidden.containsAll(HtmlSecurityRules.CONTENT_SINKS), forbidden.toString());
        assertTrue(forbidden.containsAll(HtmlSecurityRules.ANIMATION), forbidden.toString());
        assertTrue(forbidden.containsAll(Set.of("script", "style", "iframe", "frame", "object", "embed", "base", "meta", "link",
            "template", "animate", "set", "animatemotion", "animatetransform", "foreignobject",
            "noscript", "fencedframe", "portal", "applet", "frameset")), forbidden.toString());
    }

    /** R20/S8: o elemento proibido está no render do servidor (14 aceita), mas um atributo seu é atualizado no cliente. */
    @ParameterizedTest
    @ValueSource(strings = {"<iframe src=\"https://h/${s}\"></iframe>", "<script title=${s}>1</script>",
        "<style title=${s}>p</style>", "<frame title=${s}/>", "<object title=${s}></object>", "<embed title=${s}/>",
        "<base title=${s}/>", "<meta title=${s}/>", "<link title=${s}/>", "<template title=${s}>x</template>",
        "<noscript title=${s}>x</noscript>", "<svg><animate title=${s}/></svg>", "<svg><set title=${s}/></svg>",
        "<svg><animateMotion title=${s}/></svg>", "<svg><animateTransform title=${s}/></svg>",
        "<svg><foreignObject title=${s}>x</foreignObject></svg>", "<fencedframe title=${s}></fencedframe>",
        "<portal title=${s}></portal>", "<applet title=${s}></applet>", "<frameset title=${s}></frameset>"})
    void aForbiddenElementNeverGetsAClientUpdatedAttribute(String element) {
        String sk = "reactive component R() { state String s = \"\"; " + element + " <b on:click=${() -> s = \"x\"}>t</b> }";
        assertEquals(1, count(sk, "CLIENT_UNSAFE_SINK"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<div bind:value=${s}></div>", "<input bind:innerHTML=${s}/>", "<input bind:textContent=${s}/>",
        "<select bind:checked=${b}></select>", "<input type=\"checkbox\" bind:checked=${s}/>", "<input bind:value=${list}/>",
    })
    void bindTargetElementNameAndType(String element) {
        String sk = "reactive component R() { state String s = \"\"; state boolean b = false; state int n = 0;"
            + " state List<String> list = null; " + element + " }";
        assertEquals(1, count(sk, "BIND_TARGET"), sk + " -> " + diagnostics(sk));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<input bind:value=${s}/>", "<textarea bind:value=${s}></textarea>", "<select bind:value=${n}></select>",
        "<input type=\"checkbox\" bind:checked=${b}/>",
    })
    void validBinds(String element) {
        String sk = "reactive component R() { state String s = \"\"; state boolean b = false; state int n = 0; " + element + " }";
        assertEquals(Set.of(), codes(sk), diagnostics(sk).toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"long", "char", "float", "Object", "int[]", "Product", "Map<Integer, String>"})
    void propsThatCannotBeSerialized(String type) {
        String sk = "reactive component R(" + type + " v) { state var copy = v; <p>x</p> }";
        assertEquals(List.of("PROP_NOT_SERIALIZABLE"), diagnostics(sk).stream().map(SukoDiagnostic::code).toList(),
            sk + " -> " + diagnostics(sk));
    }

    @Test
    void paramsReadOnlyByTheServerAreNotSerialized() {
        String sk = "reactive component R(long v) { state int n = 0; <p>${v}</p> <b on:click=${() -> n++}>${n}</b> }";
        assertEquals(Set.of(), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void sensitiveNamesAndMapKeys() {
        String sk = "reactive component R(String authToken, String passwordHint, String title, Map<String, String> labels) {"
            + " state var a = authToken; state var b = passwordHint; state var c = title; state var l = labels; <p>x</p> }";
        List<SukoDiagnostic> d = diagnostics(sk);
        assertEquals(2, d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).count(), d.toString());
        assertTrue(d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).allMatch(x -> x.severity() == SukoDiagnostic.Severity.WARNING));
        SukoDiagnostic keys = d.stream().filter(x -> x.code().equals("PROP_MAP_KEYS")).findFirst().orElseThrow();
        assertEquals(SukoDiagnostic.Severity.INFO, keys.severity());
    }

    static long sensitiveCount(String param) {
        return count("reactive component R(String " + param + ") { state var copy = " + param + "; <p>x</p> }", "PROP_SENSITIVE");
    }

    /** R28 (S9): casa por token do nome, incluindo os nomes que faltavam na lista da spec. */
    @ParameterizedTest
    @ValueSource(strings = {"csrf", "csrfToken", "nonce", "otp", "pin", "cvv", "salt", "bearerValue", "refreshToken",
        "api_key", "APIKey", "privateKey", "userSessions", "credentials", "passwordHint"})
    void sensitiveNamesMatchByToken(String param) {
        assertEquals(1, sensitiveCount(param), param);
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "authors", "authority", "authorName", "pinned", "spinner", "tokenizer", "salted", "title"})
    void similarLookingNamesAreNotSensitive(String param) {
        assertEquals(0, sensitiveCount(param), param);
    }

    /** Extensão de teste: o checker com formas de records conhecidas, num alvo com o vocabulário html. */
    static final class RecordsExtension implements SukoExtension {
        public String id() { return "io.suko.test.records"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "records"; }
                public String componentType() { return "java.lang.Object"; }
                public Set<String> vocabularies() { return Set.of("html"); }
                public Set<String> capabilities() { return Set.of(Target.CAPABILITY_REACTIVE); }
                public Emitted emit(ComponentDecl c, EmitContext e) { return new Emitted(c.name() + ".txt", "", List.of()); }
            });
            JavaTypes types = name -> name.equals("Account")
                ? Optional.of(List.of(new JavaTypes.Field("name", ClientTypes.parse("String")),
                    new JavaTypes.Field("sessionId", ClientTypes.parse("String"))))
                : Optional.empty();
            ctx.checker(new ReactiveHtmlChecker(types));
        }
    }

    @Test
    void sensitiveRecordComponents() {
        var result = new JteCompiler("T.sk", "reactive component R(Account acc) { state var a = acc; <p>${a.name()}</p> }",
            ExtensionRegistry.of(List.of(new RecordsExtension())), List.of("records")).compile();
        List<SukoDiagnostic> d = result.diagnostics().getDiagnostics();
        SukoDiagnostic w = d.stream().filter(x -> x.code().equals("PROP_SENSITIVE")).findFirst().orElseThrow(() -> new AssertionError(d));
        assertTrue(w.message().contains("Account.sessionId"), w.message());
        assertTrue(result.success(), d.toString());
    }

    @Test
    void trustedIsOnlyForTheServerRender() {
        String inBlock = "reactive component R(String u) { state boolean open = false; if (open) { <a href=${trustedUrl(u)}>x</a> } }";
        assertEquals(Set.of("RESERVED_NAME"), codes(inBlock), diagnostics(inBlock).toString());
        String updated = "reactive component R() { state String target = \"/a\"; <a href=${trustedUrl(target)}>x</a> }";
        assertEquals(Set.of("RESERVED_NAME"), codes(updated), diagnostics(updated).toString());
        String server = "reactive component R(String u) { state boolean open = false; <a href=${trustedUrl(u)}>x</a>"
            + " <b on:click=${() -> open = !open}>t</b> }";
        assertEquals(Set.of("TRUSTED_URL"), codes(server), diagnostics(server).toString());
        String handler = "reactive component R() { state String s = \"\"; <b on:click=${() -> s = trustedUrl(s)}>t</b> }";
        assertEquals(1, count(handler, "RESERVED_NAME"), diagnostics(handler).toString());
    }

    /**
     * Gate do plano 2 (R29, S1). Hoje os parâmetros de Child são constantes (vista de raiz de ilha, R18), por isso o
     * href é TRUSTED_URL (aceite, só no servidor). Com o modo "filho reativo" um pai que muda 'target' faz o cliente
     * reescrever o href sem passar pelo porte do SukoSafe: tem de ser RESERVED_NAME. O plano 2 remove o @Disabled
     * na mesma task que junta o modo filho e faz este teste passar.
     */
    @Test
    @Disabled("plano 2: modo filho reativo (R29)")
    void trustedOnAParamOfAReactiveChildIsReservedOnceChildModeExists() {
        String sk = "reactive component Child(String u) { <a href=${trustedUrl(u)}>x</a> }"
            + " reactive component Parent() { state String target = \"/a\"; Child(u = target)"
            + " <b on:click=${() -> target = \"/b\"}>t</b> }";
        assertEquals(Set.of("RESERVED_NAME"), codes(sk), diagnostics(sk).toString());
    }

    @Test
    void htmlEventAttributesAreStillUnsafeInsideAReactiveComponent() {
        String sk = "reactive component R(String h) { state int n = 0; <b onclick=${h} on:click=${() -> n++}>t</b> }";
        assertEquals(Set.of("UNSAFE_SINK"), codes(sk), diagnostics(sk).toString());
    }
}
```

Run: `scripts/verify-isolated.sh :suko-core:test --tests 'io.suko.lang.security.ReactiveHtmlCheckerTest'`
Expected: FAIL (compilação: `ReactiveHtmlChecker` não existe).

- [ ] **Step 2: `ReactiveHtmlChecker` e o registo**

`suko-jte/src/main/java/io/suko/jte/ReactiveHtmlChecker.java`:

```java
package io.suko.jte;

import io.suko.ext.CheckContext;
import io.suko.ext.Checker;
import io.suko.jte.client.Callees;
import io.suko.jte.client.ClientModel;
import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JavaTypes;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static io.suko.jte.HtmlSecurityRules.CLIENT_FORBIDDEN_ELEMENTS;
import static io.suko.jte.HtmlSecurityRules.lower;

/**
 * As regras da 13b que dependem do alvo JTE (o cliente é JS): CLIENT_UNSUPPORTED e TYPE_NOT_INFERRED (o que o
 * {@link ClientModel} encontra), CLIENT_UNSAFE_SINK, BIND_TARGET de nome/elemento/tipo e PROP_NOT_SERIALIZABLE,
 * PROP_SENSITIVE e PROP_MAP_KEYS. Corre depois do {@link HtmlSecurityChecker} (o JteExtension regista-o a seguir)
 * e só omite um ERROR quando o span se sobrepõe ao de um diagnóstico do par declarado em {@link #SUPPRESSED_BY}
 * (um diagnóstico por sítio, R10 revisto): um ERROR que envolve o sítio não esconde o que está dentro.
 */
public final class ReactiveHtmlChecker implements Checker {

    /** R10: o código de um ERROR nosso -> os códigos de diagnósticos já presentes que o fundem. Ausente = nunca omitido. */
    static final Map<String, Set<String>> SUPPRESSED_BY = Map.of(
        "CLIENT_UNSAFE_SINK", Set.of("UNSAFE_SINK"),
        "BIND_TARGET", Set.of("BIND_TARGET"),
        "CLIENT_UNSUPPORTED", Set.of("RESERVED_NAME", "UNSAFE_SINK"),
        "RESERVED_NAME", Set.of("RESERVED_NAME"));

    /** Spec 13b, "Parâmetros que atravessam a fronteira" (R28: a lista da spec mais os oito do fim), por tokens do nome. */
    static final Set<String> SENSITIVE_NAMES = Set.of("password", "passwd", "pwd", "secret", "token", "apikey",
        "privatekey", "credential", "hash", "session", "cookie", "jwt", "auth", "ssn",
        "csrf", "nonce", "otp", "pin", "cvv", "salt", "bearer", "refresh");

    private static final Pattern NAME_TOKENS = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_$-]+");

    private final JavaTypes types;

    public ReactiveHtmlChecker() {
        this(JavaTypes.NONE);
    }

    /** Com formas de records conhecidas (testes; em produção é {@link JavaTypes#NONE}, R13). */
    public ReactiveHtmlChecker(JavaTypes types) {
        this.types = types;
    }

    @Override
    public String id() {
        return "html-reactive";
    }

    @Override
    public void check(SukoFile file, CheckContext ctx) {
        if (!ctx.activeVocabularies().contains("html")) {
            return;
        }
        Callees callees = new Callees(file, ctx.project());
        for (ComponentDecl component : file.components()) {
            if (!component.reactive()) {
                continue;
            }
            ClientModel model = ClientModel.of(component, callees, types);
            for (ClientProblem p : model.problems()) {
                report(ctx, Severity.ERROR, p.code(), p.message(), p.span());
            }
            sinks(model, ctx);
            binds(model, ctx);
            props(model, ctx);
        }
    }

    private void sinks(ClientModel model, CheckContext ctx) {
        for (Statement.HtmlElement e : model.clientElements()) {
            if (CLIENT_FORBIDDEN_ELEMENTS.contains(lower(e.tagName()))) {
                report(ctx, Severity.ERROR, "CLIENT_UNSAFE_SINK", "<" + e.tagName() + "> não pode ser criado no cliente"
                    + " (só o render do servidor o emite); tire-o do bloco que depende de state", e.span());
            }
        }
        for (ClientModel.ClientAttribute ca : model.clientAttributes()) {
            if (model.isClientCreated(ca.element())) {
                continue; // o próprio elemento já é verificado acima
            }
            if (CLIENT_FORBIDDEN_ELEMENTS.contains(lower(ca.element().tagName()))) {
                report(ctx, Severity.ERROR, "CLIENT_UNSAFE_SINK", "o atributo '" + ca.attribute().name() + "' de <"
                    + ca.element().tagName() + "> não pode ser atualizado no cliente", ca.attribute().span());
            }
        }
    }

    private void binds(ClientModel model, CheckContext ctx) {
        for (ClientModel.Site site : model.sites()) {
            if (site.kind() != ClientModel.Site.Kind.BIND) {
                continue;
            }
            Statement.Directive d = (Statement.Directive) site.node();
            if (!(d.value() instanceof Expr.PrimaryExpr p) || !model.stateTypes().containsKey(p.text())) {
                continue; // não é um state simples: é o BIND_TARGET do core
            }
            ClientType type = model.stateTypes().get(p.text());
            if (type == ClientType.Special.UNKNOWN) {
                continue;
            }
            String tag = lower(site.element().tagName());
            String problem = switch (d.name()) {
                case "value" -> !(tag.equals("input") || tag.equals("textarea") || tag.equals("select"))
                    ? "bind:value só existe em <input>, <textarea> e <select>"
                    : type == ClientType.Scalar.STRING || type == ClientType.Scalar.INT || type == ClientType.Scalar.DOUBLE
                        ? null : "bind:value precisa de um state String, int ou double (recebeu " + type.java() + ")";
                case "checked" -> !tag.equals("input")
                    ? "bind:checked só existe em <input>"
                    : type == ClientType.Scalar.BOOLEAN ? null : "bind:checked precisa de um state boolean (recebeu " + type.java() + ")";
                default -> "bind:" + d.name() + " não existe: só bind:value e bind:checked (lista fechada)";
            };
            if (problem != null) {
                report(ctx, Severity.ERROR, "BIND_TARGET", problem, d.span());
            }
        }
    }

    private void props(ClientModel model, CheckContext ctx) {
        for (String name : model.clientReadParams()) {
            Param.ValueParam param = model.param(name).orElseThrow();
            ClientType type = model.paramTypes().get(name);
            if (type instanceof ClientType.Callback) {
                continue; // callbacks nunca vão no JSON (vêm de um pai reativo)
            }
            Optional<String> why = notSerializable(type);
            if (why.isPresent()) {
                report(ctx, Severity.ERROR, "PROP_NOT_SERIALIZABLE", "o parâmetro '" + name + "' (" + type.java()
                    + ") é lido no cliente e tem de ir em data-suko-props, mas " + why.get(), param.nameSpan());
                continue;
            }
            sensitive(name, "o parâmetro '" + name + "'", param.nameSpan(), ctx);
            sensitiveFields(type, param.nameSpan(), ctx);
            if (containsMap(type)) {
                report(ctx, Severity.INFO, "PROP_MAP_KEYS", "as chaves do Map em '" + name + "' ficam visíveis no HTML"
                    + " (data-suko-props)", param.nameSpan());
            }
        }
    }

    private static Optional<String> notSerializable(ClientType t) {
        return switch (t) {
            case ClientType.Scalar s -> Optional.empty();
            case ClientType.Boxed b -> Optional.empty();
            case ClientType.ListOf l -> notSerializable(l.element());
            case ClientType.MapOf m -> notSerializable(m.value());
            case ClientType.RecordOf r -> r.fields().stream().map(f -> notSerializable(f.type()))
                .flatMap(Optional::stream).findFirst();
            case ClientType.Named n -> Optional.of("o tipo Java '" + n.name() + "' não é conhecido do compilador (records e"
                + " enums vêm de Java; R13)");
            case ClientType.Unsupported u -> Optional.of(u.reason());
            case ClientType.Callback c -> Optional.of("uma lambda não vai em JSON");
            case ClientType.Special s -> Optional.of("o tipo não é conhecido");
        };
    }

    private void sensitive(String name, String label, SourceSpan span, CheckContext ctx) {
        sensitiveWord(name).ifPresent(word -> report(ctx, Severity.WARNING, "PROP_SENSITIVE", label
            + " parece um dado sensível ('" + word + "') e fica visível no HTML em data-suko-props; não o leia no cliente", span));
    }

    /** R28: por tokens ('author' não casa 'auth'; 'csrfToken' casa); shortcut: 'accesstoken' é um só token e não casa, WARNING apenas. */
    static Optional<String> sensitiveWord(String name) {
        String[] tokens = NAME_TOKENS.split(name);
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i].toLowerCase(Locale.ROOT);
            if (SENSITIVE_NAMES.contains(t) || (t.endsWith("s") && SENSITIVE_NAMES.contains(t.substring(0, t.length() - 1)))) {
                return Optional.of(t);
            }
            if (i + 1 < tokens.length && SENSITIVE_NAMES.contains(t + tokens[i + 1].toLowerCase(Locale.ROOT))) {
                return Optional.of(t + tokens[i + 1].toLowerCase(Locale.ROOT));
            }
        }
        return Optional.empty();
    }

    private void sensitiveFields(ClientType t, SourceSpan span, CheckContext ctx) {
        switch (t) {
            case ClientType.RecordOf r -> {
                for (ClientType.RecordOf.Field f : r.fields()) {
                    sensitive(f.name(), "o componente '" + r.name() + "." + f.name() + "'", span, ctx);
                    sensitiveFields(f.type(), span, ctx);
                }
            }
            case ClientType.ListOf l -> sensitiveFields(l.element(), span, ctx);
            case ClientType.MapOf m -> sensitiveFields(m.value(), span, ctx);
            default -> { }
        }
    }

    private static boolean containsMap(ClientType t) {
        return switch (t) {
            case ClientType.MapOf m -> true;
            case ClientType.ListOf l -> containsMap(l.element());
            case ClientType.RecordOf r -> r.fields().stream().anyMatch(f -> containsMap(f.type()));
            default -> false;
        };
    }

    /** R10: um ERROR nosso só é omitido por um diagnóstico do par declarado (o core e o html-security correm antes). */
    private static void report(CheckContext ctx, Severity severity, String code, String message, SourceSpan span) {
        if (code == null) {
            return;
        }
        if (severity == Severity.ERROR && overlapsOneOf(ctx, span, SUPPRESSED_BY.getOrDefault(code, Set.of()))) {
            return;
        }
        ctx.report(severity, code, message, span);
    }

    /** O endIndex do SourceSpan é inclusivo: spans adjacentes (fim + 1 = início) não se sobrepõem. */
    private static boolean overlapsOneOf(CheckContext ctx, SourceSpan span, Set<String> codes) {
        if (codes.isEmpty() || span == null || span.isNone() || span.startIndex() < 0) {
            return false;
        }
        for (SukoDiagnostic d : ctx.diagnostics().getDiagnostics()) {
            SourceSpan o = d.span();
            if (codes.contains(d.code()) && o != null && !o.isNone() && o.startIndex() >= 0
                    && Objects.equals(d.sourceFile(), ctx.fileName())
                    && o.startIndex() <= span.endIndex() && span.startIndex() <= o.endIndex()) {
                return true;
            }
        }
        return false;
    }
}
```

Em `JteExtension.register`, depois de `ctx.checker(new HtmlSecurityChecker());`:

```java
        // Depois do html-security, de propósito: um diagnóstico por sítio (R10 do plano 1 da 13b).
        ctx.checker(new ReactiveHtmlChecker());
```

- [ ] **Step 3: `HtmlSecurityChecker` — `trusted*` no cliente (Q6)**

1. Imports novos: `io.suko.jte.client.Callees`, `io.suko.jte.client.ClientModel`, `io.suko.jte.client.JavaTypes`.
2. Em `check`, o ciclo dos componentes passa a calcular o modelo dos reactive components:

```java
        Callees callees = new Callees(file, ctx.project());
        for (ComponentDecl component : file.components()) {
            // 13b (Q6): num reactive component, saber que atributos o cliente atualiza (trusted* só no servidor).
            ClientModel model = component.reactive() ? ClientModel.of(component, callees, JavaTypes.NONE) : null;
            reserved(component.name(), component.nameSpan(), "componente", ctx);
```

e a última linha do ciclo passa a `walk(component.body(), slots, ctx, model);`.
3. `walk` ganha o parâmetro: `private void walk(List<Statement> statements, Set<String> slots, CheckContext ctx, ClientModel model)`; dentro dele **todas** as chamadas `walk(..., slots, ctx)` passam a `walk(..., slots, ctx, model)` e `element(e, slots, ctx)` passa a `element(e, slots, ctx, model)` (`grep -n "walk(\|element(e" suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java` → nenhuma chamada sem `model`).
4. `element` ganha o parâmetro (`private void element(Statement.HtmlElement e, Set<String> slots, CheckContext ctx, ClientModel model)`) e a chamada a `trusted` passa a:

```java
            if (trusted(a, tag, name, options, ctx, model != null && model.isClientAttribute(a))) {
```

5. `trusted` ganha `boolean client` (`private boolean trusted(Statement.Attribute a, String tag, String name, SecurityOptions options, CheckContext ctx, boolean client)`) e, logo a seguir a `scan(arg.get(), ctx);`:

```java
            if (ok && client) {
                ctx.report(Severity.ERROR, "RESERVED_NAME", fn + "(...) só é válido no render do servidor: um atributo"
                    + " atualizado no cliente passa sempre pelo porte do SukoSafe (13b, Q6)", a.span());
                return true;
            }
```
6. Constantes partilhadas (R27). Em `HtmlSecurityRules.java` (LF) acrescentar `import java.util.stream.Collectors;` e `import java.util.stream.Stream;` e, junto às outras constantes:

```java
    /** Conteúdo que o browser executa ou reinterpreta (era privado no HtmlSecurityChecker). */
    public static final Set<String> CONTENT_SINKS = Set.of("script", "style", "noscript", "foreignobject");
    /** Elementos de animação SVG cujos attributeName/values/to/from/by o 14 vigia (era privado no HtmlSecurityChecker). */
    public static final Set<String> ANIMATION = Set.of("animate", "set", "animatemotion", "animatetransform");
    /** 13b, R27: elementos que o cliente nunca cria nem cujos atributos atualiza (nome local, minúsculas). */
    public static final Set<String> CLIENT_FORBIDDEN_ELEMENTS = Stream.of(CONTENT_SINKS.stream(), ANIMATION.stream(),
            Stream.of("iframe", "frame", "object", "embed", "base", "meta", "link", "template",
                "fencedframe", "portal", "applet", "frameset"))
        .flatMap(s -> s)
        .collect(Collectors.toUnmodifiableSet());
```

No `HtmlSecurityChecker` apagar as linhas privadas `CONTENT_SINKS` e `ANIMATION` (a `ANIMATION_ATTRS` fica) e acrescentar `import static io.suko.jte.HtmlSecurityRules.ANIMATION;` e `import static io.suko.jte.HtmlSecurityRules.CONTENT_SINKS;` (as utilizações não mudam).

Run: `scripts/verify-isolated.sh :suko-jte:test :suko-core:test`
Expected: BUILD SUCCESSFUL — `ReactiveHtmlCheckerTest` (todos os casos; o `@Disabled` do gate R29 aparece como saltado e é o único), `HtmlSecurityCheckerTest`, `PrefixedAttributeSecurityTest`, `ReactiveRulesTest`, `XssCorpusTest`, `GoldenParityTest` e o resto sem alterações.

- [ ] **Step 4: Commit**

```bash
git status
git add suko-jte/src/main/java/io/suko/jte/ReactiveHtmlChecker.java suko-jte/src/main/java/io/suko/jte/HtmlSecurityChecker.java \
  suko-jte/src/main/java/io/suko/jte/HtmlSecurityRules.java suko-jte/src/main/java/io/suko/jte/JteExtension.java \
  suko-core/src/test/java/io/suko/lang/security/ReactiveHtmlCheckerTest.java
git commit -m "feat(13b): ReactiveHtmlChecker (CLIENT_*, BIND_TARGET, PROP_*) e trusted* só no render do servidor"
```

---

### Task 10: LSP (palavras e `Hint`), documentação, verificação final e revisão do ramo

**Files:**
- Modify (CRLF): `suko-lsp/src/main/java/io/suko/lsp/CompletionService.java` (uma linha)
- Test (create): `suko-lsp/src/test/java/io/suko/lsp/ReactiveLspTest.java`
- Modify: `README.md` (secção "Language Syntax"), `ARCHITECTURE.md`, `suko-api/README.md`, `suko-lsp/README.md`, `docs/security.md`

**Interfaces:**
- Consumes: tudo o que as Tasks 1–9 produzem. O mapeamento `HINT → DiagnosticSeverity.Hint` (`DiagnosticsService`) e o `[HINT]` do Gradle (`SukoCompileTask`/`SukoWatchTask`) já entraram na Task 1 (R25); o `SukoCompileMojo` já imprime `[<severidade>]` e não muda.
- Produces: completion de `state`/`derived` no corpo; documentação da linguagem, da API v2 e das limitações; o ramo verificado.

`on:`/`bind:` **não** entram na completion: o `CompletionContext` não tem contexto de atributo (só `NONE`, `IMPORT`, `BODY`, `CALL_BLOCK`, `ARGUMENT`) e criá-lo é trabalho de editor (fora: "linguagem antes dos editores"). `reactive` também não: só vale antes de `component`, ao nível de topo, que hoje é contexto `NONE`.

- [ ] **Step 1: Testes do LSP (falham)**

`suko-lsp/src/test/java/io/suko/lsp/ReactiveLspTest.java`:

```java
package io.suko.lsp;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 13b no editor: as palavras state/derived na completion e o REACTIVE_UNUSED como Hint. */
class ReactiveLspTest {

    @Test
    void bodyCompletionOffersStateAndDerived(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.open("R.sk", "reactive component R() {\n  \n}\n");
        List<CompletionItem> items = t.server.getTextDocumentService().completion(new CompletionParams(
            new TextDocumentIdentifier(t.uri("R.sk")), new Position(1, 2))).get().getRight().getItems();
        List<String> labels = items.stream().map(CompletionItem::getLabel).toList();
        assertTrue(labels.containsAll(List.of("state", "derived", "if", "for")), labels.toString());
    }

    @Test
    void reactiveUnusedIsAHintInTheEditor(@TempDir Path folder) throws Exception {
        TestSupport t = new TestSupport(folder);
        t.open("R.sk", "reactive component R() {\n  <p>x</p>\n}\n");
        t.scheduler.fire();
        Diagnostic hint = t.client.lastFor(t.uri("R.sk")).getDiagnostics().stream()
            .filter(d -> "REACTIVE_UNUSED".equals(d.getCode().getLeft())).findFirst().orElseThrow();
        assertEquals(DiagnosticSeverity.Hint, hint.getSeverity());
    }
}
```

Run: `scripts/verify-isolated.sh :suko-lsp:test --tests 'io.suko.lsp.ReactiveLspTest'`
Expected: FAIL em `bodyCompletionOffersStateAndDerived` (`state`/`derived` ainda não estão nas palavras); `reactiveUnusedIsAHintInTheEditor` já passa (Tasks 1 e 3).

- [ ] **Step 2: Palavras do corpo**

Em `CompletionService.java` (CRLF), a linha 35 passa a:

```java
    private static final List<String> KEYWORDS = List.of("if", "else", "for", "switch", "case", "default", "state", "derived");
```

Run: `scripts/verify-isolated.sh :suko-lsp:test`
Expected: PASS (incluindo `CompletionTest.inTheBodyOffersVisibleComponentsAndKeywordsAndAutoImportsTheOthers`, que usa `containsAll` e não muda). Se `bodyCompletionOffersStateAndDerived` falhar por o `CompletionContext` não reconhecer o corpo de um `reactive component` como `BODY` (o token `REACTIVE` antes de `component`), corrigir aí — é a única outra mudança autorizada nesta task. `crlf_check suko-lsp/src/main/java/io/suko/lsp/CompletionService.java` → `218/218`.

- [ ] **Step 3: Documentação**

`README.md`, secção "Language Syntax", nova subsecção "Reactive components (subprojeto 13b — em curso)" a seguir a "Slots (Content Parameters)": o exemplo `Counter`/`Stepper` da spec escrito com a sintaxe real (`Stepper(value = count, onChange = v -> count = v)`, R22); `state T x = ...;`/`state var`, `derived`, `on:evento=${lambda}` (lambda com ou sem parâmetro), `bind:value`/`bind:checked`; `prefixo:nome` em atributos (`xlink:href`, `hx-on:click`, `x-on:click`; `@click` continua erro); `for (var x : xs)`; as palavras reservadas novas; o subconjunto do cliente em resumo (com o `Map` só de leitura); a nota sobre os tokens `--`/`++`/`+=` (`a--b`, `${1--1}` e `data--x` são `PARSE_ERROR`, R4; `a - -b` e `data-x` continuam válidos); e a nota **"até ao plano 2, o alvo `jte` recusa `reactive component` com `REACTIVE_UNSUPPORTED`"**.

`ARCHITECTURE.md`:
1. Diagrama: em "Análise semântica" acrescentar "regras da 13b independentes do alvo (`ReactiveChecker`)"; em "Checkers e vocabulários das extensões" acrescentar "`REACTIVE_UNSUPPORTED` por capacidade do alvo (`CapabilityChecker`) e `html-reactive` do `suko-jte`".
2. "Limitações conhecidas": (a) `reactive`, `state`, `derived` e `new` são palavras reservadas (R3) — continuam a valer em nomes HTML e depois de `.`; (b) `++`, `--`, `+=` são tokens (R4): `a--b`, `${1--1}`, `data--x` (nome de atributo com dois hífenes seguidos) e um `--`/`++` solto numa expressão passam a `PARSE_ERROR` em vez de `a - -b`; escreve-se `a - -b` com espaços, e `data-x` (um hífen) e o texto livre não mudam; (c) lambdas só em `on:` e em argumentos de componentes; atribuições só em handlers (R5); (d) `hx-on::evento` (dois `:`) não se escreve; (e) os bullets sobre o `Dialog` sem botão de fecho e sobre a colisão de `$ident` com o Alpine passam a dizer que `x-on:click="..."` **já se escreve** desde a 13b e que a colisão de `$store`/`$dispatch` **morde agora** (mitigação `\$`).
3. Item 13, 13b: estado "plano 1 (linguagem, API v2, tradutor) implementado; plano 2 (ilhas no `jte`) por fazer"; os rulings que mudam promessas: R1, R2, R13 (records/enums de Java desconhecidos até o item 12 ou outra fonte), R14, R15, R16 (int de 32 bits como Java — a "diferença assumida" do overflow deixou de existir), R18, R20, R26 (contrato do `style` reativo: `cssValue` normalizado, só `setProperty`), R27 (lista de elementos proibidos alargada e única), R28 (`PROP_SENSITIVE` por tokens, `PROP_*` por campo no plano 2) e R29 (gate do `trusted*` no modo filho); o R10 revisto (supressão só por pares declarados); e a API v2: o que mudou (lista do `ExtensionApi`) e o que fica decidido para o plano 2 (R11) ou fora da 13b (R12, (c)).
4. Item 14: lacuna (k) **fechada** (ramos `:` vivos, `PrefixedAttributeSecurityTest`); nos "Pendentes do subprojeto 14", o bullet "API v2" passa a dizer que (d) foi resolvido por `Statement.Directive` e que (a)/(b) seguem para o plano 2 e (c) fica por decidir fora da 13b.

`suko-api/README.md`, nova secção "API v2 (13b)": o que muda e **o que parte numa extensão de terceiros**: `switch` exaustivos sobre `Statement` (novos `StateDecl`, `DerivedDecl`), `Expr` (novos `LambdaExpr`, `AssignExpr`, `UpdateExpr`, `NewExpr`) e `SukoDiagnostic.Severity` (novo `HINT`) deixam de compilar; padrões de record e chamadas posicionais aos construtores canónicos de `ComponentDecl`, `HtmlElement` e `ProjectIndexEntry` mudam de aridade (os construtores antigos continuam); uma extensão compilada contra a API 1 dá `EXTENSION_API_MISMATCH` e não é registada. Acrescentar `Target.capabilities()`/`CAPABILITY_REACTIVE`, `ExprNames` e o aviso de que `on:`/`bind:` estão em `HtmlElement.directives()` e nunca em `attributes()`. Atualizar "Limitações conhecidas e checklist" (o "`CheckContext` não expõe os alvos ativos" mantém-se, R12).

`suko-lsp/README.md`, secção "Severidades": `HINT` → `Hint` (`REACTIVE_UNUSED`) e as palavras `state`/`derived` na completion.

`docs/security.md`, secção 2: `on:`/`bind:` são sintaxe Suko, nunca atributos HTML nem emitidos tal como estão; `hx-on:*`/`x-on:*` dinâmicos são `UNSAFE_SINK` e `xlink:href` passa pelo `SukoSafe.url` (agora escrevíveis); `trustedUrl`/`trustedStyle` só no render do servidor de um reactive component (`RESERVED_NAME` no cliente, Q6); as ilhas em si (porte do `SukoSafe`, token de montagem, props) chegam no plano 2. Acrescentar (revisão de segurança): (a) `CLIENT_UNSAFE_SINK` cobre também `noscript`, `fencedframe`, `portal`, `applet` e `frameset` (R27); (b) `PROP_SENSITIVE` compara por tokens do nome, com a lista alargada (`csrf`, `nonce`, `otp`, `pin`, `cvv`, `salt`, `bearer`, `refresh`), e é só um aviso — um nome em minúsculas sem separador (`accesstoken`) não casa (R28); (c) o contrato do `style` reativo (R26): `cssValue` devolve o valor **normalizado** (`<= U+0020` tirado das pontas), o cliente só escreve por `style.setProperty` e, com `style-src 'self'`, o atributo `style` escrito pelo servidor no 1.º render é descartado pelo browser — o valor só aparece depois da montagem (a provar em Playwright no plano 2); (d) **não ligue dados não confiáveis a propriedades de layout** (`position`, `display`, `visibility`, `pointer-events`, `opacity`): a allowlist deixa o utilizador escolher entre identificadores válidos (`fixed`, `none`, `hidden`), redressing de UI, igual ao servidor desde o 14.

- [ ] **Step 4: Verificação final**

Run: `scripts/verify-isolated.sh clean build`
Expected: BUILD SUCCESSFUL — todos os módulos do `settings.gradle.kts`: `suko-api`, `suko-jte`, `suko-core`, `suko-registry`, `suko-registry-generator`, `suko-gradle-plugin`, `suko-maven-plugin`, `suko-components`, `suko-website`, `suko-cli`, `suko-lsp` e `suko-test-ext`. A loja **não** está neste build.

Run: `scripts/verify-isolated.sh -p examples/shop clean test`
Expected: BUILD SUCCESSFUL, sem alterações nos testes da loja (build separado, que usa o plugin do build principal por `includeBuild("../..")`).

Verificações (todas na raiz, sem output esperado salvo onde dito):

```bash
git grep -n "gg.jte\|io.suko.jte" -- suko-core/src/main                      # o core continua sem JTE
git diff bd13359 -- '*/src/test/**' | grep '^-.*assert'                       # nenhuma asserção removida
git log --format=%B bd13359..HEAD | grep -i -E 'co-authored-by|claude-session|generated with'
git ls-files | grep -E '(^|/)bin/'                                            # nenhuma pasta bin/
crlf_check suko-api/src/main/java/io/suko/lang/ast/Statement.java suko-api/src/main/java/io/suko/lang/ast/ComponentDecl.java \
  suko-api/src/main/java/io/suko/lang/project/ProjectIndexEntry.java suko-core/src/main/java/io/suko/lang/SukoAstBuilder.java \
  suko-core/src/main/java/io/suko/lang/semantic/SemanticChecker.java suko-core/src/main/java/io/suko/lang/TolerantParser.java \
  suko-core/src/main/java/io/suko/lang/JteCompiler.java suko-lsp/src/main/java/io/suko/lsp/AstQuery.java \
  suko-lsp/src/main/java/io/suko/lsp/DiagnosticsService.java suko-lsp/src/main/java/io/suko/lsp/CompletionService.java
grep -c -v $'\r$' suko-core/src/main/java/io/suko/lang/project/ProjectIndex.java   # esperado: 3
```

Paridade do site (Review Focus 1; o HTML gerado só pode diferir no `?v=` do cache-buster). Só pelo `scripts/verify-isolated.sh` (nunca `./gradlew` no checkout nem nas cópias à mão): o script copia o repositório do `git rev-parse --show-toplevel` do diretório onde corre para `SUKO_VERIFY_DIR`, por isso a base corre-se num worktree de `bd13359`, com outra pasta de cópia:

```bash
ROOT=$(git rev-parse --show-toplevel)
BASE=$(mktemp -d)/base && git worktree add --detach "$BASE" bd13359
( cd "$BASE" && SUKO_VERIFY_DIR=/tmp/suko-verify-base "$ROOT/scripts/verify-isolated.sh" :suko-website:generateWebsite )
scripts/verify-isolated.sh :suko-website:generateWebsite                       # o ramo, em /tmp/suko-verify
diff -r /tmp/suko-verify-base/suko-website/build/website /tmp/suko-verify/suko-website/build/website | grep -v '?v='
git worktree remove --force "$BASE"
```

Sem diferenças de conteúdo (o `grep -v` só tira as linhas do `?v=`).

- [ ] **Step 5: Commit e revisão do ramo**

```bash
git status
git add README.md ARCHITECTURE.md suko-api/README.md suko-lsp/README.md docs/security.md \
  suko-lsp/src/main/java/io/suko/lsp/CompletionService.java suko-lsp/src/test/java/io/suko/lsp/ReactiveLspTest.java
git commit -m "docs(13b): linguagem reativa, API v2 e limitações; state/derived na completion do LSP"
```

Despachar o `architect` (revisão do ramo inteiro contra a spec, este plano e o `ARCHITECTURE.md`) e o `security-specialist` (revisão de implementação das Tasks 2, 4, 5–9: `prefixo:nome`, a exceção `on:`/`bind:`, o `prelude.js`, o tradutor, o `ClientModel` e o `ReactiveHtmlChecker`). Corrigir o que for material numa ronda; o resto fica registado em `ARCHITECTURE.md`. Sem push e sem PR: isso precisa do OK do utilizador.

---

## Perguntas ao utilizador

Decididas entre a escrita do plano e a sua conclusão (2026-10-17): **R13 = A** (`JavaTypes.NONE` em produção; formas de records em memória só nos testes) e **R14 = B** (o `Map` entra no subconjunto, só de leitura). Continuam em aberto:

1. **Revisão do `security-specialist` às alterações de 2026-10-11 da spec** (Q4/Q5/Q6, um diagnóstico por sítio): **feita** (`.superpowers/sdd/13b-plano1/security-review.md`, nenhum achado bloqueia) e aplicada ao plano — ver o cabeçalho e os rulings R10 e R26–R29. Fica por decidir pelo utilizador o que o R27 decidiu por defeito (os quatro embebimentos novos e o `noscript` entram na lista de `CLIENT_UNSAFE_SINK`).
2. **Origem dos tipos Java (antes do plano 2):** com o R13 = A, records e enums de Java são desconhecidos (`Named`), por isso o `TodoList` da spec (com `record Todo`), `new Todo(...)`, os acessores, a validação de props por forma e o `PROP_SENSITIVE` recursivo só funcionam quando houver uma fonte (item 12, uma declaração `external`/`record` em `.sk`, ou ler as fontes Java). A spec previa o item 12 feito antes da 13b (D8), mas ele foi adiado a 2026-10-06.
3. **Modo "filho reativo" (plano 2):** a spec diz que os parâmetros de um reactive component dentro de outro são reativos; este plano trata-os como constantes (vista de raiz de ilha, R18). Quando o plano 2 juntar o modo filho, toda a expressão que lê um parâmetro num filho reativo passa a ser código do cliente (subconjunto obrigatório e sem minimização). Decidir se é aceitável ou se um componente usado como filho fica restrito. **Gate já escrito (R29):** com o modo filho, `trusted*` cujo argumento lê um parâmetro de um reactive component chamado a partir de outro reactive component passa a `RESERVED_NAME`; o teste `@Disabled` da Task 9 é o critério.
4. **`float`/`Float` (R15)** saem do cliente, ao contrário da letra da spec ("primitivos sem `long`/`char`"). Confirmar.
5. **`int` de 32 bits (R16)**: a spec aceitava o overflow como diferença documentada; o plano faz o JS dar a volta como o Java (`| 0`, `Math.imul`). Confirmar que a mudança de promessa é bem-vinda (só ganha fidelidade).

Para o plano 2 (não bloqueiam este; vêm da revisão de segurança): (1) o porte de `SukoSafe.cssValue` devolve o valor **sem espaços nas pontas** como o Java (`String.valueOf(v).trim()`, regra `<= U+0020`), não "o original" — o oráculo do teste diferencial é o Java; vetores mínimos e escrita só por `style.setProperty` no R26, com a prova em Playwright sob `style-src 'self'`; (2) o serializador de `data-suko-props` emite só os campos que o `ClientModel` lê, e um callback `undefined` dá erro de montagem claro (R28); (3) o gate do `trusted*` no modo filho (R29); (4) o `prelude.js` lê `e.target.value`/`checked`/`key` (só leitura) e o varrimento por allowlist do plano 2 tem de o prever.

## Self-review

### Cobertura da spec (âmbito do plano 1)

| Requisito | Task |
|---|---|
| A — `ExtensionApi.VERSION = 2`; v1 recusada com `EXTENSION_API_MISMATCH` | 1 |
| A — `reactive` (`ComponentDecl.reactive()`), `StateDecl`/`DerivedDecl` (tipo ou `var`) | 1, 2 |
| A — lambda, atribuição, `++`/`--`/`+=`, `new` no AST | 1, 2 |
| A — representação de `on:`/`bind:` (decisão (d): nó próprio `Statement.Directive`, R2) | 1, 2 |
| A — `Severity.HINT` (D6) | 1 |
| A — capacidades do alvo para `REACTIVE_UNSUPPORTED` | 1, 3 |
| A — `ProjectIndexEntry.reactive()` (fronteira entre ficheiros) | 1, 2, 3 |
| A — o que parte para terceiros documentado | 10 |
| B — `reactive component`, `state`/`derived`, `on:evento=${lambda}`, `bind:value|checked`, lambda sem parâmetro (D3) | 2, 7 |
| B — `prefixo:nome` (Q4), tokens adjacentes, `On:`/`Bind:` erro do construtor do AST, `@` continua erro | 2, 4 |
| B — paridade do `TolerantParser`/LSP | 2 |
| B — `<!DOCTYPE html>` num reactive component → `INVALID_DOCTYPE` | 3 |
| C — `REACTIVE_REQUIRED`, `REACTIVE_UNUSED` (HINT), `DERIVED_CYCLE`, `LAMBDA_ACROSS_BOUNDARY`, `REACTIVE_UNSUPPORTED` | 3 |
| C — `BIND_TARGET` (state simples no core; nome/elemento/tipo no `suko-jte`) | 3, 9 |
| C — `TYPE_NOT_INFERRED`, `CLIENT_UNSUPPORTED` (um por sítio, localização exata) | 6–9 |
| C — `CLIENT_UNSAFE_SINK` (lista (a), um diagnóstico por sítio com o 14) | 9 |
| C — `PROP_NOT_SERIALIZABLE`, `PROP_SENSITIVE`, `PROP_MAP_KEYS`, minimização | 8, 9 |
| C — `RESERVED_NAME` para `trusted*` no cliente (Q6), `security-audit.json` só com o servidor | 9 |
| C — ramos `:` do `HtmlSecurityChecker` vivos e exceção `on:`/`bind:` | 1, 2, 4 |
| D — tradutor puro do subconjunto (literais, operadores, `String`, `List`, records, `Math`, evento, `Map` R14) | 6, 7 |
| D — semântica de Java: `int / int`, `==` entre `String` recusado, `long`/`char` fora, `null` → TypeError | 5, 6, 7 |
| D — palavras reservadas JS renomeadas, `constructor`/`__proto__` nunca do protótipo, strings escapadas | 5, 6 |
| D — testes golden e diferenciais executados no Node, que falham sem Node | 5, 6, 7 |
| D — `style="propriedade: ${v}"`: a conversão `String.valueOf` para o porte do `cssValue` | 6, 8 |
| E — README, `ARCHITECTURE.md`, `suko-api`, `suko-lsp`, `docs/security.md`; LSP: palavras e `Hint` | 1, 10 |

### Deixado de propósito para o plano 2

Geração do `.jte` com marcadores/âncoras e do módulo ES por componente (e a capacidade `reactive` no `jte`, R1); `suko.js` (montagem, agendador, reconciliação de listas, contrato `$s`/`$p`/`$u` do R17) com o `prelude.js`; o porte do `SukoSafe` em JS (`url`, `imageUrl`, `srcset`, `imageSrcset`, `ping`, `rel`, `cssValue`), o recurso de vetores partilhados e o teste diferencial Java = JS por conjunto de opções; o serializador de `data-suko-props` (Java gerado) e a validação de props no cliente; o token de montagem; `Emitted.companions` + `ProjectOutput.Kind.CLIENT_ASSET`, o encaminhamento no core, a limpeza por manifesto e os nomes com hash (R11); `js { outputDir; basePath }`, a validação do `basePath` e as pastas de recursos dos plugins Gradle/Maven (Q1, R12 (a)); clonagem de slots `<template>` e o modo "filho reativo"; o `switch` no cliente e os atributos "inteligentes" (null/boolean) e o `style` por CSSOM (contrato fechado no R26: só `setProperty`/`removeProperty`); a API Java de ilhas chamadas a partir de Java (Q3, contrato com o item 12); o varrimento por allowlist das operações DOM; Playwright com CSP/Trusted Types; as ilhas da loja e do perfil `lab`; a revisão de segurança da implementação e a ronda 2 do pentest; a gramática TextMate do VSCode e a página de referência do site.

### Verificação do próprio plano

O código das Tasks 1–9 foi compilado e corrido numa cópia descartável do repositório (fora de `/mnt/c`): `SukoReactiveSyntaxTest` (34), `ReactiveRulesTest` (20), `PrefixedAttributeSecurityTest` (18), os testes do cliente da Task 5, `JsTranslatorTest`, `JsHandlerTest`, os dois diferenciais (cerca de 60 expressões e 13 handlers iguais em Java e no Node), `ClientModelTest` (8) e `ReactiveHtmlCheckerTest` (43) passaram, e as suites existentes do core (615), `suko-jte`, `suko-lsp`, `suko-registry-generator` e `suko-components` ficaram verdes; o site gerado foi igual ao de `bd13359` salvo o `?v=`. O `dstr` do `prelude.js` foi comparado com o `Double.toString` do JDK em cerca de 300 000 valores. A Task 10 (texto de documentação e o teste do LSP) não foi executada antecipadamente, nem o `clean build` completo nem os testes da loja. As correções da revisão de segurança à Task 9 (R10 por pares, R27, R28, o gate R29) são posteriores a esta verificação: o código e os testes novos da Task 9 não foram compilados nem corridos; a execução da Task 9 confirma-os (a contagem de casos de `ReactiveHtmlCheckerTest` deixa de ser 43).
