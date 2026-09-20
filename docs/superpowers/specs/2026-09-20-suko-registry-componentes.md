# Suko — Subprojeto 7: Registry e Biblioteca de Componentes

Data: 2026-09-20

## Contexto

Os subprojetos 1-6 estão concluídos e mergeados em `main` (ver
`ARCHITECTURE.md` → "Roadmap por subprojeto"). Os dois pré-requisitos
deste subprojeto estão prontos: o 5 (resolução de nomes multi-ficheiro
via `package`/`import`, visibilidade `public`) e o 6 (`Component`
substitui `slot<T>`, `children` implícitos, interpolação real
`${expr}`/`$ident`).

A visão original deste item vive em quatro linhas da spec do subprojeto
6 (`docs/superpowers/specs/2026-09-18-suko-modelo-componente.md:28-41`):
"Registry e biblioteca de componentes (Tailwind, um por ficheiro)", com
a decisão de distribuição já fixada — *"a CLI copia o código-fonte `.sk`
para o projeto do consumidor, que passa a possuir e customizar esse
código — não é uma dependência de biblioteca"*. Este documento fecha
tudo o resto: formato do manifesto, layout, versionamento, validação e
fronteira com o subprojeto 8.

Hoje `suko-components/` é um scaffold: `build.gradle.kts`,
`registry.json` com `{"components": []}` e dois `README.md` a apontar
para aqui. Zero `.sk`.

Este documento resulta do scoping arquitetural de 2026-09-20 e das
decisões do utilizador sobre ele (D1, D2, D3, D5, D6 abaixo, marcadas
como **decidido**).

## Constrangimentos do compilador que moldam este desenho

Estes não são preferências de estilo — são o que o compilador atual
impõe. Qualquer desenho que os ignore não compila.

- **Existe um único source root.** `ProjectIndex.build(Path sourceRoot)`
  e `SukoProjectCompiler.compile(Path sourceRoot)` aceitam **um**
  caminho. Não há noção de "source root da aplicação + source root de
  biblioteca". Consequência: **o modelo "biblioteca como dependência"
  não é sequer implementável hoje** — os componentes têm de acabar
  fisicamente dentro do source root do consumidor. O modelo shadcn não é
  só a preferência declarada do utilizador; é o único modelo que o
  compilador suporta. Isto passa a estar registado no `ARCHITECTURE.md`
  como racional (ver "Próximos passos").
- **`PACKAGE_DIRECTORY_MISMATCH`** (subprojeto 5): `package foo.bar;` só
  é válido em `<sourceRoot>/foo/bar/`. Copiar um ficheiro para um sítio
  arbitrário da árvore do consumidor **quebra a compilação** — daí a
  reescrita de namespace ser requisito duro, não conveniência (D2).
- **Visibilidade é `public` ou file-private, sem nível "mesmo pacote".**
  Todo o componente do registry tem de ser `public component`, sem
  exceção, senão é inutilizável a partir do ficheiro do consumidor.
- **O `SemanticChecker` não desce a HTML aninhado**
  (`ARCHITECTURE.md`, limitações): uma chamada escrita dentro de uma tag
  — a forma esmagadoramente comum — escapa a toda a verificação. É o
  único constrangimento desta lista que este subprojeto **corrige** em
  vez de contornar (Risco R2, único item da lista pré-aprovada de D5).
- **Bugs de gramática conhecidos que o autor de componentes vai apanhar
  de frente:** conteúdo solto antes de slot nomeado engole o slot em
  silêncio; `var c = Card() { ... }` é engolido como texto e produz
  `.jte` corrompido; `{slot ?: "fallback"}` não compila; um literal de
  string não pode conter `<` nem `>`. Todos estão em
  `ARCHITECTURE.md` → "Limitações conhecidas"; aqui viram **convenções
  de autoria** (ver "Convenções de autoria da biblioteca").
- **Não há parser de JSON em lado nenhum do build.** `suko-core` depende
  só de ANTLR 4.13.1, jte 3.1.12 e JUnit. Ler/escrever `registry.json`
  exige uma dependência nova e pinada, que **não pode** entrar em
  `suko-core`.
- **Nenhum módulo é publicável.** Não existe `maven-publish` no repo e a
  versão é `0.1.0-SNAPSHOT`. Qualquer transporte por coordenadas Maven
  exigiria montar publicação primeiro — razão principal para D1.

## Decisão central: o 7 define os dados, o 8 define a ferramenta

A fronteira entre este subprojeto e o seguinte é a linha mais importante
deste documento, porque é a que mais facilmente derrapa.

- O **subprojeto 7** produz: os componentes `.sk`, o manifesto que os
  descreve, e a garantia (por teste) de que manifesto e fontes nunca
  divergem. Não faz download, não copia ficheiros, não reescreve
  namespaces, não tem interface de linha de comandos.
- O **subprojeto 8** consome esse manifesto e faz o trabalho de
  ferramenta: resolver o transporte, resolver o grafo de dependências,
  reescrever `package`/`import`, escrever no disco do consumidor,
  manter o lockfile, detetar edições locais.

A obrigação deste subprojeto perante o 8 é: **o manifesto tem de
carregar informação suficiente para o 8 fazer tudo isso, e nada mais.**

## D1 — Transporte: JSON estático sobre HTTPS, com fallback local (**decidido**)

O registry é um conjunto de ficheiros JSON estáticos servidos por HTTPS
(GitHub raw, versionado por tag git), com o sistema de ficheiros local
(`--registry <path>` no subprojeto 8) **sempre** suportado como caminho
de desenvolvimento e de teste.

Racional: corresponde literalmente à intenção shadcn declarada; não
exige montar publicação (que não existe no repo); o cliente usa
`java.net.http.HttpClient` do JDK, sem dependência nova além do JSON; e
não cria dependência circular com o subprojeto 9 — o site poderá servir
o registry mais tarde, mas o 7 não pode esperar pelo 9.

Consequência de desenho: **todos os caminhos dentro do manifesto são
relativos a uma base**, nunca absolutos e nunca URLs completos. A base é
uma URL `https://.../` ou um diretório local; a resolução é a mesma
operação nos dois casos. É isto que faz o fallback local funcionar com o
mesmo código do caminho HTTP, em vez de ser um modo à parte.

Caminho de evolução declarado, não implementado aqui: publicar
`suko-components` como jar de recursos `.sk` em coordenadas Maven. Dá
versionamento, checksums, mirrors e cache offline de borla, mas obriga a
CLI a embutir um resolvedor Maven (pesado) ou a virar tarefa de build —
o que a acoplaria a plugins que ainda não têm ID descobrível. Fica como
opção futura, e o manifesto é agnóstico ao transporte precisamente para
a manter aberta.

## D2 — Namespace no destino: reescrito para uma base do consumidor (**decidido**)

A CLI do subprojeto 8 reescreve a linha `package` e as linhas `import`
do ficheiro copiado para uma base escolhida pelo consumidor, em vez de
impor um destino fixo. A alternativa (destino fixo `io/suko/ui/`) era
mais simples mas impunha a árvore de pacotes do Suko na árvore de outra
gente, o que contradiz "o consumidor passa a ser dono do código".

O que isto obriga **neste** subprojeto:

1. Os fontes da biblioteca declaram uma base real e autocompilável:
   `package io.suko.ui;` em `suko-components/src/main/suko/io/suko/ui/`.
   Tem de ser uma base real (não um placeholder) porque o módulo
   compila-se a si próprio como validação, e
   `PACKAGE_DIRECTORY_MISMATCH` não aceita placeholders.
2. O manifesto regista a base e o sufixo **separadamente**
   (`basePackage: "io.suko"`, `packageSuffix: "ui"`), para a reescrita
   ser uma substituição mecânica de prefixo e não um parse.
3. Os `import` entre componentes da biblioteca são escritos na forma
   totalmente qualificada e sem alias (`import io.suko.ui.Label;`), para
   que a mesma substituição de prefixo os corrija.
4. O manifesto declara o grafo `dependsOn` entre componentes, para o 8
   saber que `field` arrasta `label` e `input` — sem isso a reescrita de
   imports fica com referências a componentes que não foram copiados.

Destino final no projeto do consumidor, calculado pelo 8:
`<sourceRoot>/<baseDoConsumidor como pastas>/<packageSuffix>/Nome.sk`.

## D3 — `suko-components` é conteúdo puro; o modelo vive em `suko-registry` (**decidido**)

O scaffold atual declara `implementation(project(":suko-core"))` num
módulo que não tem código Java nenhum, e `suko-website` declara
`implementation(project(":suko-components"))` sobre esse mesmo módulo
vazio. Sob o modelo shadcn isto está invertido: um módulo de conteúdo
não expõe API para ninguém depender.

Grafo de módulos resultante deste subprojeto:

| Módulo | Papel | Depende de |
|---|---|---|
| `suko-registry` (**novo**) | Modelo, leitor e escritor do manifesto. Sem I/O de rede, sem CLI. | `suko-core` (para ler `.sk` ao gerar), JSON |
| `suko-components` | **Conteúdo puro**: `.sk` + JSON gerado. Sem `src/main/java`. | `suko-registry` **em escopo de teste apenas** |
| `suko-cli` (subprojeto 8) | `suko add` | `suko-registry` |
| `suko-website` (subprojeto 9) | site | `suko-registry` (para ler o manifesto), não `suko-components` |

Notas e pontos a confirmar:

- `suko-components/build.gradle.kts`: `implementation(project(":suko-core"))`
  é **removido**; entra `testImplementation(project(":suko-registry"))`.
  O módulo mantém `id("java")` só por causa de `src/test/java` (a
  geração/validação do manifesto e os testes de render). Se a intenção
  do utilizador ao dizer "conteúdo puro" era *nenhuma* dependência,
  incluindo teste, então a geração/validação tem de mudar-se para
  `suko-registry` a ler `suko-components/` por caminho de ficheiro —
  funciona, mas é uma referência cross-módulo por path, pior. **A
  interpretação adotada aqui é "sem código nem dependências em `main`";
  teste local permitido** — confirmada pelo utilizador em 2026-09-20.
- `suko-website/build.gradle.kts` continua a declarar
  `implementation(project(":suko-components"))` sobre um módulo sem
  código — uma dependência que não significa nada. Esta spec **não** a
  mexe (o módulo é âmbito do subprojeto 9), mas regista-a como achado
  para o scoping do 9: a dependência correta é sobre `suko-registry`.
- **Dependência JSON:** `com.google.code.gson:gson:2.11.0`, pinada,
  declarada **só** em `suko-registry`. Escolhido sobre Jackson por ser
  um único jar sem transitivas — relevante para o fat-jar da CLI do
  subprojeto 8. Nunca entra em `suko-core`.

## Formato do manifesto

Dois níveis, ambos **gerados** a partir dos fontes (ver "Geração e
validação"), ambos commitados no repositório.

### `suko-components/registry.json` — índice

```json
{
  "schemaVersion": 1,
  "registryVersion": "0.1.0",
  "basePackage": "io.suko",
  "components": [
    {
      "name": "button",
      "version": "1.0.0",
      "description": "Botão com variantes de estilo.",
      "category": "form",
      "manifest": "components/button.json"
    }
  ]
}
```

- `schemaVersion` — versão do **formato**, inteiro monotónico. É o que
  permite ao subprojeto 8 recusar-se a ler um registry de futuro com uma
  mensagem legível em vez de rebentar.
- `registryVersion` — versão do conteúdo publicado como um todo
  (corresponde a uma tag git).
- `description` — uma linha. Existe já a pensar no subprojeto 9, que vai
  querer descrições curtas por componente sem reparsear os `.sk`.
- `manifest` — caminho **relativo à base** (ver D1).

### `suko-components/components/<name>.json` — manifesto por componente

```json
{
  "schemaVersion": 1,
  "name": "field",
  "version": "1.0.0",
  "description": "Label + input emparelhados, com mensagem de erro.",
  "category": "form",
  "basePackage": "io.suko",
  "packageSuffix": "ui",
  "component": "Field",
  "files": [
    {
      "path": "src/main/suko/io/suko/ui/Field.sk",
      "target": "ui/Field.sk",
      "sha256": "..."
    }
  ],
  "dependsOn": ["label", "input"],
  "externalRequirements": [
    { "kind": "css", "id": "tailwindcss", "versionRange": "3.x" }
  ]
}
```

- `files[].path` — caminho relativo à base, de onde buscar o conteúdo.
- `files[].target` — caminho relativo ao pacote de destino, para o 8
  saber onde escrever depois de aplicar a base do consumidor.
- `files[].sha256` — hash do conteúdo. É o que permite ao subprojeto 8
  dizer "modificaste este ficheiro localmente, queres mesmo
  sobrescrever". **O 7 fornece o dado; o lockfile que o usa é do 8.**
- `dependsOn` — nomes de componentes do próprio registry (grafo de
  composição). Acíclico e validado por teste.
- `externalRequirements` — o que o consumidor tem de ter no projeto dele
  para o componente funcionar (Tailwind, Alpine). É promessa
  consumer-facing, não detalhe interno.

### Conteúdo por referência, não embutido

O manifesto **não** embute o código-fonte (ao contrário do shadcn, que
mete o ficheiro dentro do JSON). Razões, por ordem de peso:

1. A resolução de `files[].path` relativa a uma base é a **mesma
   operação** em HTTPS e em sistema de ficheiros — é isto que faz o
   fallback local de D1 ser o mesmo código, não um modo paralelo.
2. O `.sk` continua a ser a única fonte de verdade e continua
   diretamente legível/`curl`-ável, o que o subprojeto 9 vai querer.
3. Evita escapar `.sk` inteiros dentro de JSON — com backticks, aspas e
   `${...}` lá dentro, é exatamente o tipo de coisa que produz corrupção
   silenciosa.

Custo aceite: o subprojeto 8 faz N+1 pedidos em vez de 1.

## Geração e validação do manifesto

O manifesto é **gerado a partir dos `.sk`** e verificado por teste, na
mesma disciplina de golden-file já usada no projeto. Escrito à mão,
apodrece na primeira semana.

- `suko-registry` expõe o gerador (parseia os `.sk` com o
  `SukoAstBuilder`/`ProjectIndex` já existentes e produz o modelo) e o
  escritor/leitor JSON.
- `suko-components/src/test/java` tem um teste que regenera em memória e
  compara com o JSON commitado; divergência = falha, com instrução de
  como regenerar.
- Uma tarefa Gradle (`:suko-components:generateRegistry`) reescreve os
  ficheiros. **Não** é uma tarefa que corra no `build` normal — o
  commitado é a referência.

## Layout de `suko-components/`

```
suko-components/
  registry.json                        (gerado, commitado)
  components/
    button.json                        (gerado, commitado)
    ...
  src/main/suko/
    io/suko/ui/
      Button.sk
      Input.sk
      Label.sk
      Badge.sk
      Alert.sk
      Card.sk
      Field.sk
      Dialog.sk
  src/test/java/...                    (geração/validação + render)
  build.gradle.kts
  README.md
```

**Um componente `public` por ficheiro**, imposto por um teste deste
módulo — **não** pela linguagem. A spec do subprojeto 5 decidiu
deliberadamente não impor isso na gramática
(`docs/superpowers/specs/2026-09-19-suko-projeto-multificheiro.md:226-230`:
"é uma convenção de distribuição do subprojeto 7, não uma regra da
linguagem"); esta spec é o sítio onde a convenção passa a ser
verificada, e o âmbito da verificação é a biblioteca, não o projeto do
consumidor.

## Conjunto v1 de componentes

Oito componentes, escolhidos não por cobertura de UI mas por
**exercitarem ponta-a-ponta as features que o subprojeto 6 entregou**.
Cada um é, na prática, um teste de regressão do compilador.

| Componente | O que prova |
|---|---|
| `Button` | Variantes por `switch` com classes Tailwind completas (ver A3 nas convenções) |
| `Input` | Atributos com `${expr}`, valores por omissão (`= null`) |
| `Label` | O caso mínimo; alvo de `dependsOn` |
| `Badge` | Variantes + `children` |
| `Alert` | `children` implícitos com conteúdo misto (texto + HTML) |
| `Card` | `children` implícitos **mais** slots nomeados opcionais (`Component header = null`) |
| `Field` | Composição cross-ficheiro: importa e chama `Label` + `Input`; prova o grafo `dependsOn` e a reescrita de imports de D2 |
| `Dialog` | Alpine `x-data="{ open: false }"` ao lado de `${expr}` — prova empírica da decisão de sintaxe do subprojeto 6 de rejeitar chaveta nua |

D6 (**decidido**): **Tailwind 3.x** e **Alpine 3.x**, declarados em
`externalRequirements`. O consumidor tem de configurar o `content` do
Tailwind para varrer os `.sk` (ou os `.jte` gerados — ficam em `build/`,
o que tem consequências), e tem de carregar Alpine para os componentes
interativos. Isto vai documentado no `README.md` do módulo: é promessa
consumer-facing, não detalhe de implementação.

## Convenções de autoria da biblioteca

Regras de escrita, cada uma derivada de um constrangimento real acima.
Verificadas por teste sempre que seja possível verificá-las.

1. **Sempre `public component`.** Sem isto, o componente copiado é
   invisível a partir de qualquer outro ficheiro do consumidor
   (`COMPONENT_NOT_VISIBLE`).
2. **Um componente por ficheiro**, com o nome do ficheiro igual ao do
   componente.
3. **Nunca fragmentos de classe interpolados.** `class="btn btn-${variant}"`
   compila e renderiza, mas **quebra o Tailwind**: o scanner do Tailwind é
   estático e nunca vê `btn-primary` composto em runtime, logo a classe
   não chega ao CSS gerado. Variantes escrevem-se como strings de classe
   **completas** dentro de um `switch`/`if`. É a convenção mais
   importante do subprojeto e é contra-intuitiva face à feature de
   interpolação que o subprojeto 6 acabou de entregar — falha
   silenciosa e visual, das mais caras de diagnosticar. Verificável por
   teste: nenhum `.sk` da biblioteca tem `${` dentro de um atributo
   `class`.
4. **Slots nomeados antes de conteúdo solto** num bloco de chamada —
   contorna o bug de ordem do `textRun` que engole o slot em silêncio.
5. **Nada de `{slot ?: "fallback"}`** (não compila: `Content` e `String`
   não têm supertipo comum).
6. **Nada de `var c = Componente() { ... }`** (engolido como texto,
   produz `.jte` corrompido).
7. **Nada de `<` ou `>` dentro de literais de string.**
8. **Imports totalmente qualificados e sem alias**, para a substituição
   de prefixo de D2 funcionar mecanicamente.

## D5 — Bugs de compilador: registar por omissão (**decidido**)

Escrever oito componentes reais é o primeiro uso a sério da linguagem;
espera-se genuinamente que destape bugs. A regra é:

- **Por omissão, registar, não corrigir.** Um bug descoberto vira uma
  entrada no ledger e, se mudar uma promessa da linguagem, uma limitação
  no `ARCHITECTURE.md`.
- **Lista fechada e pré-aprovada de correções admissíveis** — só o que
  estiver nesta secção pode ser corrigido dentro deste subprojeto.
- **Escape hatch:** um bug que impeça escrever um componente do v1 e não
  tenha contorno **não** é corrigido aqui — o componente sai do v1 e
  fica registado o porquê.

Sem esta regra, o subprojeto 7 converte-se em subprojeto 1 outra vez.

### Lista pré-aprovada

**Um único item.** Qualquer outro bug descoberto é registado, não
corrigido.

1. **R2 — `SemanticChecker` não desce a HTML aninhado** (decidido pelo
   utilizador em 2026-09-20: *sim*, entra na lista). Ver o detalhe
   imediatamente abaixo. É a **primeira** tarefa do plano, antes de
   existir um único componente da biblioteca, precisamente para que o
   custo (diagnósticos hoje silenciados a aparecer de uma vez em
   `examples/` e nos fixtures) seja pago numa tarefa isolada e
   reversível, em vez de contaminar as tarefas de conteúdo.

Fora desta lista, e para eliminar ambiguidade, ficam explicitamente
**não corrigíveis aqui** (todos já registados no `ARCHITECTURE.md`): o
bug de ordem do `textRun` que engole slot nomeado depois de conteúdo
solto; `var c = Componente() { ... }` engolido como texto; `{slot ?:
"fallback"}` sem supertipo comum; `<`/`>` em literais de string;
ausência de `ErrorListener` em `src/main`; espaço órfão entre statements
irmãos; backtick não escapado dentro de slot fill. Cada um destes tem
uma convenção de autoria correspondente (ver "Convenções de autoria da
biblioteca") — a biblioteca contorna-os, não os resolve.

### Detalhe de R2 (decidido: entra na lista)

**R2** é a limitação já registada no `ARCHITECTURE.md`: o
`SemanticChecker.checkStatement` não tem caso para
`Statement.HtmlElement`, logo nunca percorre os `children()` de uma tag.
Como quase toda a chamada real é escrita dentro de uma tag
(`<div>Label()</div>`), `COMPONENT_NOT_FOUND`, `COMPONENT_NOT_VISIBLE`,
`SLOT_NOT_FOUND` e `CARDINALITY_VIOLATION` nunca disparam no caso comum.
A correção é conhecida e pequena: adicionar o caso `HtmlElement` a
`checkStatement`, chamando `checkStatementList` sobre `children()`.

Razões pelas quais entra:

- `Field` a compor `Label` + `Input` dentro de um `<div>` é exatamente o
  padrão que não é verificado. Uma **biblioteca** é o código onde a
  ausência de verificação custa mais, porque é o código que outros vão
  copiar e editar sem rede de segurança.
- Sem isto, o que faz a biblioteca parecer correta é o emitter, não o
  verificador — e a spec do subprojeto 5 já registou que, nestas
  condições, o modelo de visibilidade é "verificado apenas no nível de
  topo", não "verificado".

**Custo aceite ao dizer sim** (o utilizador decidiu com este custo à
vista): a correção destapa de uma vez todos os diagnósticos que estavam
silenciados em `examples/` e nos fixtures de teste existentes — limpeza
de tamanho imprevisível dentro de um subprojeto cujo objetivo declarado
é conteúdo. Mitigação estrutural adotada no plano: é a Tarefa 1,
isolada, com um passo de inventário do estrago **antes** de qualquer
correção, e com um critério explícito de paragem — se o número de
diagnósticos novos em `examples/`/fixtures exceder o que uma tarefa
consegue absorver, a tarefa pára, reverte, e R2 vira subprojeto próprio
(o resto do plano não depende dela para avançar; só perde verificação).

## D7 — Versionamento

Não há resolução de versões em runtime: o modelo é copy-source, o
consumidor fica dono do ficheiro. O versionamento serve exclusivamente
para o subprojeto 8 poder responder a duas perguntas: "há versão nova
deste componente?" e "modificaste isto localmente?".

- `schemaVersion` — inteiro monotónico, só sobe quando o **formato**
  muda de forma incompatível.
- `version` por componente — semântica, sobe quando o `.sk` muda.
  Verificada por teste contra o `sha256`: se o conteúdo mudou e a versão
  não, o build falha.
- `registryVersion` — o conjunto publicado, corresponde a uma tag git.
- O **lockfile** do lado do consumidor é do subprojeto 8. Aqui só se
  garante que os dados necessários existem.

## Fora de escopo (decisões explícitas desta spec)

- **A CLI `suko add`** e tudo o que é ferramenta: download, resolução do
  grafo, reescrita efetiva de `package`/`import`, escrita no disco,
  lockfile, `suko diff`/`suko update`, e como a CLI é instalada
  (jbang? fat jar? tarefa de build?). Subprojeto 8.
- **Site, previews renderizados, documentação navegável.** Subprojeto 9.
  Este subprojeto contribui apenas `description` e `category` no
  manifesto.
- **Fechar a lacuna do plugin Gradle** (`gradlePlugin{}` + ID
  descobrível via `plugins { id(...) }`). A spec da migração monorepo
  adiou para aqui o "wiring de compilação `.sk`→`.jte` para
  `suko-components`", mas fazê-lo *através do plugin* obrigaria a
  resolver primeiro a lacuna registada no `ARCHITECTURE.md` (o plugin
  existe só como classe `Plugin<Project>`, nunca foi aplicável por ID).
  **Decisão: a validação deste módulo invoca `SukoProjectCompiler`
  diretamente a partir de teste/tarefa ad-hoc.** Fechar o plugin como
  deve ser (ID, TestKit, `plugins {}` a sério) é meio subprojeto por si
  só e mistura duas coisas.
- **Publicação Maven de qualquer módulo.** Ver D1.
- **Temas, dark mode, tokens de design, API de variantes tipo CVA.**
  Variantes são `switch` com classes completas; nada mais.
- **Alterações ao `suko-website`.** Registada acima como achado para o
  scoping do subprojeto 9.
- **Correções de compilador** fora da lista pré-aprovada de D5.

## Riscos

- **R1 (alto) — o conjunto v1 destapa um bug bloqueante.** É o primeiro
  uso a sério da linguagem. Mitigação: regra D5 (registar, não corrigir,
  com escape hatch) e escolha do v1 para exercitar caminhos que o
  subprojeto 6 já provou por teste de render.
- **R2 (alto, mitigado) — verificação não desce a HTML aninhado.**
  Componentes compostos da biblioteca não seriam verificados; a
  biblioteca *pareceria* correta porque o emitter resolve bem, mas um
  consumidor que edite o código copiado não teria rede de segurança.
  **Decidido corrigir dentro deste subprojeto** (único item da lista
  pré-aprovada de D5), como Tarefa 1 isolada e com critério de paragem.
  O risco residual muda de natureza: deixa de ser "a biblioteca não é
  verificada" e passa a ser "a correção destapa mais trabalho do que
  cabe numa tarefa" — coberto pelo critério de paragem.
- **R3 (médio) — Tailwind × interpolação.** Convenção 3 acima. Produz
  falhas silenciosas e puramente visuais, as mais caras de diagnosticar.
  Mitigação: teste que proíbe `${` dentro de atributos `class` nos `.sk`
  da biblioteca.
- **R4 (médio) — scope creep para o subprojeto 8.** A reescrita de
  namespace é tentadora de implementar aqui porque parece "só um sed".
  Não é: atrás dela vêm o grafo de dependências resolvido, o destino no
  disco e o lockfile. Mitigação: a fronteira "o 7 define os dados, o 8
  define a ferramenta" é critério de revisão, não sugestão.
- **R5 (baixo) — dependência JSON nova.** Trivial desde que fique
  confinada a `suko-registry` e nunca toque `suko-core`.

## Testes

Convenção do projeto: render real via `gg.jte` (`JteRenderSupport`), não
smoke de parse, sempre que o achado dependa de código Java gerado.

- **Compilação e render de toda a biblioteca:** os oito `.sk` compilam
  via `SukoProjectCompiler` sobre `suko-components/src/main/suko` sem
  nenhum diagnóstico, e cada um renderiza com `gg.jte` 3.1.12 real.
- **`Field` end-to-end:** prova de composição cross-ficheiro (importa e
  chama `Label` + `Input`), renderizado, não só compilado.
- **`Dialog` com Alpine:** o `x-data="{ open: false }"` sobrevive ao
  emit sem ser confundido com interpolação, ao lado de um `${expr}` real
  no mesmo componente.
- **Manifesto vs. fontes (golden):** regeneração em memória comparada
  com o JSON commitado; divergência falha o build.
- **Grafo `dependsOn`:** todos os nomes referidos existem no índice; o
  grafo é acíclico.
- **Convenções:** todo o componente é `public`; um componente por
  ficheiro; nome do ficheiro = nome do componente; nenhum `${` dentro de
  atributo `class`.
- **Versionamento:** conteúdo mudado sem `version` mudada falha
  (comparação `sha256` × `version`).
- **Round-trip do leitor:** `suko-registry` lê o JSON commitado e
  reproduz o modelo; `schemaVersion` desconhecido produz erro legível,
  não exceção crua.
- **Independência de transporte:** a resolução de caminhos relativos
  funciona igual com base de sistema de ficheiros e com base de URL
  (a parte HTTP real é do subprojeto 8; aqui testa-se a resolução).

## Próximos passos

Depois deste subprojeto, `ARCHITECTURE.md` é atualizado:

- **"Estrutura de módulos"**: `suko-components` deixa de ser scaffold e
  passa a descrito como conteúdo puro; entra `suko-registry`; fica
  registado que `suko-website` ainda declara uma dependência sobre um
  módulo sem código, como achado para o subprojeto 9.
- **Racional completo do modelo copy-source** — já feito pelo utilizador
  em 2026-09-20, no item 8 do roadmap (`ARCHITECTURE.md:427-437`): além
  de "o consumidor fica dono do código", fica escrito que
  `ProjectIndex.build(Path sourceRoot)`/`SukoProjectCompiler.compile(Path
  sourceRoot)` só aceitam **um** source root, o que torna
  biblioteca-como-dependência não implementável sem trabalho de
  compilador. Nada a fazer aqui; registado para não ser reescrito em
  duplicado.
- **"Limitações conhecidas"**: **sai** a limitação do `SemanticChecker`
  não descer a HTML aninhado (resolvida pela Tarefa 1 — é a única
  limitação que este subprojeto fecha); **entra** a convenção
  Tailwind × interpolação (é uma limitação de autoria real, não só uma
  convenção de biblioteca) e qualquer bug registado sob a regra D5.
- **"Roadmap por subprojeto"**: item 7 passa a CONCLUÍDO com ponteiro
  para esta spec.

O subprojeto 8 fica desbloqueado assim que o manifesto estiver estável —
antes mesmo de os oito componentes estarem todos escritos, porque o que
o 8 consome é o formato, não o conteúdo.
