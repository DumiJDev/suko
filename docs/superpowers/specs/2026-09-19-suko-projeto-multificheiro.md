# Suko — Subprojeto 5: Projeto Multi-Ficheiro (Resolução de Nomes)

Data: 2026-09-19

## Contexto

Subprojetos 1-4 e 6 do roadmap revisto (ver `ARCHITECTURE.md`) estão
concluídos e mergeados em `main`. Este documento é o subprojeto **5**,
desbloqueado (dependia só de 1-4; corre depois do 6 por ordem de
decisão do utilizador, não por dependência real).

**Estado atual confirmado por leitura direta do código antes de
desenhar este documento** (convenção do projeto: não assumir, verificar):

- A gramática **já** tem `package foo.bar;` (`packageDecl`) e
  `import foo.bar.Card as C;` (`importDecl`), e `compilationUnit`
  já aceita **múltiplos** `componentDecl` por ficheiro
  (`componentDecl*`). Nenhuma gramática nova é precisa para isto.
  `SukoFile.imports()` é lido para a lista de strings mas **nunca
  usado** por `SemanticChecker`/`JteEmitter`; o `as X` é descartado
  pelo `SukoAstBuilder`.
- `JteCompiler.compile()` — o pipeline de hoje — é **inteiramente
  por-ficheiro**: um `SukoFile`, uma `SymbolTable` nova, um
  `JteEmitter` construído só com `sukoFile.components()` **desse
  ficheiro**. Não há noção de projeto.
- `SukoCompileTask` (plugin Gradle) usa `Files.list(sourceDir)` — **não
  recursivo** — e escreve cada `.jte` gerado com o nome flat
  `component.name() + ".jte"` direto em `outputDir`, sem subpastas.
- Confirmado por auditoria anterior do agente `architect` (registado em
  `ARCHITECTURE.md`): uma chamada de componente com nome composto
  (`ui.NavLink(...)`) já faz parse e já é emitida literalmente como
  `@template.ui.NavLink(...)`; o `gg.jte` real já lê o ponto como
  separador de path e procura `ui/NavLink.jte` — falha hoje só porque
  esse ficheiro nunca é escrito nessa subpasta. **Isto é evidência
  empírica já existente** de que `gg.jte` resolve chamadas com ponto
  como caminho de pasta; nenhuma sonda nova é precisa para confirmar
  esse mecanismo específico.
- Resolução de "isto é uma chamada de componente" está hoje espalhada
  em **três lugares divergentes**: `JteEmitter.emitComponentCall`
  (statement), `JteEmitter.emitExpr` caso `CallExpr` (componente como
  valor, subprojeto 6), e `SemanticChecker.looksLikeComponentName`
  (verificador) — cada um consulta a sua própria ideia de "nomes
  conhecidos", todos limitados ao ficheiro atual. Achado Minor
  ledgerado no subprojeto 6, explicitamente apontado como relevante
  aqui.

## Decisão central: `SukoProjectCompiler` em duas fases

Um `SukoProjectCompiler` novo (não um fork do `JteCompiler` — reusa-o)
compila um `sourceDir` inteiro em vez de um ficheiro:

**Fase 1 — index:** varre `sourceDir` recursivamente, faz parse de
cada `.sk` encontrado (parse completo é barato e já existe; não vale a
pena um parser "leve" à parte) e constrói, sem correr
`SemanticChecker`/`JteEmitter` ainda, uma tabela:

```java
record ProjectIndexEntry(String qualifiedName, String simpleName,
                          Path sourceFile, boolean isPublic, int paramCount) {}
```

chaveada por `pacote.Componente` (qualifiedName) e também por
`simpleName` agrupado (para detetar colisões — ver `AMBIGUOUS_IMPORT`
abaixo). Resolve o problema de referências entre ficheiros em qualquer
ordem, incluindo ciclos: `A` chamar `B` e `B` chamar `A` é legítimo,
porque `gg.jte` resolve `@template.x(...)` em tempo de render, não em
tempo de compilação Suko — a Fase 1 só precisa de saber que o nome
existe, nunca precisa de compilar `B` antes de `A`.

**Fase 2 — compile:** para cada ficheiro, corre o mesmo
`SemanticChecker`/`JteEmitter` de hoje, mas agora com o `ProjectIndex`
da Fase 1 disponível para resolver: `import`s (existe? é `public` ou
estou no mesmo ficheiro?), chamadas de nome curto pós-import, chamadas
de nome totalmente qualificado (sempre válidas, com ou sem import).

Isto implica extensão de API, não reescrita:

- `SemanticChecker` ganha um construtor/campo opcional para receber o
  `ProjectIndex` (ausente = comportamento de hoje, só o ficheiro atual
  — mantém os testes unitários de ficheiro único a funcionar sem
  mudança).
- `JteEmitter` ganha o mesmo: `componentsByName` (hoje só o ficheiro
  atual) passa a ser consultado **primeiro**, e só se não encontrar o
  nome aí é que consulta o `ProjectIndex` do projeto — preserva a
  prioridade "componente no mesmo ficheiro" sobre "componente
  importado" quando os nomes colidem (ver `AMBIGUOUS_IMPORT`).
- As três heurísticas divergentes citadas em Contexto passam a
  consultar a mesma fonte (`componentsByName` local + `ProjectIndex`),
  eliminando a divergência ledgerada no subprojeto 6.

## Package ↔ pasta: obrigatório, à Java

`package foo.bar;` num `.sk` só é válido se esse ficheiro estiver
dentro de `<sourceRoot>/foo/bar/`. Um ficheiro **sem** `package` fica
no pacote raiz (default) e **não tem nenhuma restrição de pasta** —
pode viver em qualquer subpasta de `sourceRoot`, incluída ou não numa
declaração de `package` de outros ficheiros vizinhos. Declarar
`package` é sempre opt-in, e só quem declara fica sujeito à regra de
correspondência de pasta.

**Como se resolve um ficheiro sem `package`** (correção da revisão
final, achado B): é a **pasta** relativa ao `sourceRoot` — nunca o
`package` (ausente) — que determina o nome qualificado do componente, o
prefixo de `@template.*` emitido e a subpasta do `.jte` gerado. Um
ficheiro `sub/Foo.sk` sem `package` declara componentes com o nome
qualificado `sub.Foo`, exatamente como se tivesse escrito `package
sub;`. Isto é o que mantém as três noções de "onde vive este
componente" (índice, emissão, output) alinhadas; antes desta correção
divergiam precisamente neste caso, gerando `sub/Foo.jte` mas emitindo
`@template.Foo(...)` — `TemplateNotFoundException` em tempo de render,
com `success=true` e zero diagnósticos. Para um ficheiro que declara o
`package` correto o resultado é idêntico ao do `package` declarado, já
que `PACKAGE_DIRECTORY_MISMATCH` garante que coincidem.

**Nota sobre `examples/`** (correção factual da revisão final, achado
K): `examples/` **não** foi migrado para as convenções multi-ficheiro
neste subprojeto. Os `.sk` que lá estão declaram `package`s que não
correspondem às suas pastas reais, por isso correr o
`SukoProjectCompiler` sobre `examples/` hoje produz vários
`PACKAGE_DIRECTORY_MISMATCH`/`COMPONENT_NOT_VISIBLE`/`IMPORT_NOT_FOUND`.
Migrar `examples/` para um projeto multi-ficheiro válido está fora de
âmbito deste subprojeto e fica adiado para um futuro.

**Diagnóstico novo:** `PACKAGE_DIRECTORY_MISMATCH` quando um ficheiro
**declara** `package foo.bar;` mas o caminho relativo real do ficheiro
a partir do `sourceRoot` conhecido pela Fase 1 não é `foo/bar/`. Nunca
disparado para um ficheiro sem `package`. Isto só é verificável a nível
de projeto (a Fase 1 sabe o `sourceRoot`; um `JteCompiler` isolado, que
recebe só `fileName` como string solta, não sabe onde esse ficheiro
"vive" na árvore) — por isso este diagnóstico só existe quando
compilado via `SukoProjectCompiler`, nunca via `JteCompiler` direto.

## Visibilidade: `public` como única keyword nova

Dado que **já é possível hoje** ter vários `componentDecl` no mesmo
ficheiro (`componentDecl*`), o par público/privado mais útil não é
"mesmo pacote vs. fora do pacote" (Java) mas **"mesmo ficheiro vs.
resto do projeto"** — permite um ficheiro com um componente exportado
e vários componentes auxiliares internos (`Table` público, `Row`
interno, só usado dentro do mesmo `.sk`).

- **Sem modificador (default):** o componente só é chamável de dentro
  do próprio ficheiro `.sk` que o declara. Nenhum `.sk` existente muda
  de comportamento (hoje já não é possível chamar um componente de
  outro ficheiro de forma nenhuma — isto só passa a ser **explícito e
  verificado**, onde hoje seria só uma falha de runtime do `gg.jte`).
- **`public`:** visível de qualquer ficheiro do projeto, sujeito a
  `import` (ou nome totalmente qualificado, sem import).
- Sem nível intermédio "mesmo pacote" — decisão explícita do
  utilizador nesta sessão: mais simples que um modelo de três níveis, e
  sem caso de uso concreto identificado para esse nível.

**Gramática:**

```antlr
componentDecl
    : PUBLIC? COMPONENT Identifier typeParameters? LPAREN paramList? RPAREN templateBlock
    ;
```

Token novo no lexer: `PUBLIC : 'public';`. `ComponentDecl` (record)
ganha um campo `boolean isPublic`.

**Diagnóstico novo:** `COMPONENT_NOT_VISIBLE` — chamada (via import ou
nome qualificado) a um componente que existe no índice mas não é
`public`, a partir de um ficheiro diferente do que o declara.

## Resolução de import

- `import foo.ui.NavLink;` (sem alias) — depois disto, `NavLink(...)`
  (nome curto, sem prefixo) resolve para `foo.ui.NavLink`. O `as` já
  existente na gramática (`import foo.ui.NavLink as Nav;`) continua a
  funcionar, `Nav(...)` resolve para o mesmo alvo.
- `foo.ui.NavLink(...)` totalmente qualificado **sempre** resolve,
  com ou sem import — já faz parse hoje (`CallExpr`/`ComponentCallStmt`
  sobre um `PrimaryExpr` composto por `.`), só faltava a resolução real
  em vez do path literal que falha em runtime.
- **Diagnóstico novo `IMPORT_NOT_FOUND`:** `import` aponta para um
  `pacote.Componente` que não existe no `ProjectIndex` — hoje isto é
  silenciosamente ignorado (`SukoFile.imports()` nunca lido).
- **Diagnóstico novo `AMBIGUOUS_IMPORT`:** dois `import`s sem alias
  trazem o mesmo nome curto para o mesmo ficheiro (`import a.Card;
  import b.Card;`) — exige `as` para desambiguar em pelo menos um dos
  dois. Um componente declarado no **próprio ficheiro** tem sempre
  prioridade sobre um nome importado com o mesmo nome curto (nunca é
  ambíguo — sombra o import, como variável local sombra import em
  Java).

## Reestruturação do diretório de saída

`SukoProjectCompiler` (Fase 2) escreve cada `.jte` gerado em
`<outputDir>/<caminho-do-pacote>/<Componente>.jte` — espelha
exatamente a estrutura de pastas de entrada, já imposta pela regra
package↔pasta acima. Como já estabelecido em Contexto, `gg.jte` já lê
o ponto de `@template.foo.ui.NavLink(...)` como separador de path — com
os ficheiros `.jte` nas subpastas certas, o bug de
`TemplateNotFoundException` documentado em `ARCHITECTURE.md` fecha-se
como efeito colateral desta reestruturação, sem exigir nenhuma mudança
adicional na forma como `JteEmitter` já emite chamadas com nome
composto.

`SukoCompileTask` (Gradle) e o equivalente Maven passam a chamar
`SukoProjectCompiler` (varrimento recursivo de `sourceDir`) em vez de
`Files.list` + `JteCompiler` por ficheiro solto.

## Diagnósticos novos (resumo)

| Código | Condição |
|---|---|
| `IMPORT_NOT_FOUND` | `import` aponta para `pacote.Componente` inexistente no índice |
| `COMPONENT_NOT_VISIBLE` | chamada a componente existente mas não `public`, de outro ficheiro |
| `PACKAGE_DIRECTORY_MISMATCH` | `package` declarado não bate com a pasta real do ficheiro |
| `AMBIGUOUS_IMPORT` | dois imports sem alias trazem o mesmo nome curto |
| `DUPLICATE_COMPONENT` | dois ficheiros declaram o mesmo nome qualificado (colisão detetada na Fase 1, reportada pela Fase 2 nos dois ficheiros) |

Todos os cinco só são detetáveis a nível de projeto — nenhum deles
pode ser adicionado ao `SemanticChecker` de ficheiro único sem o
`ProjectIndex` da Fase 1.

## Fora de escopo (decisões explícitas desta sessão)

- **Sem `const`/`type` Suko partilhado.** Um tipo Java (enum, classe)
  já é usável em qualquer `.sk` pelo nome totalmente qualificado sem
  nenhuma feature nova — "partilhar tipos" já funciona por essa via.
  Só componentes são resolvidos por este subprojeto.
- **Sem nível de visibilidade "mesmo pacote".** Só `public`/file-private
  (ver acima).
- **Sem enforcement de "um componente por ficheiro".** Isso é uma
  convenção de distribuição do subprojeto 7 (registry estilo
  shadcn/ui), não uma regra da linguagem — a gramática já suporta
  `componentDecl*` e este subprojeto não restringe isso.
- **Sem resolução de ciclos como erro.** Ciclos entre ficheiros são
  aceites (ver Fase 1 acima) — só uma referência a um nome que não
  existe em lado nenhum é erro (`IMPORT_NOT_FOUND`/`COMPONENT_NOT_FOUND`
  já existente).
- **Migração completa de `examples/` para multi-ficheiro** não é
  âmbito de aceitação obrigatório — só o suficiente para provar o
  mecanismo ponta-a-ponta (ver Testes).

## Testes

Convenção do projeto: render real via `gg.jte` (`JteRenderSupport`),
não só compilação/parse, sempre que o achado depender de código Java
gerado.

- **Fixture de projeto multi-ficheiro** (pelo menos 3 ficheiros, 2
  pacotes) sob um diretório de teste dedicado (ex.
  `src/test/resources/multifile-fixtures/`): um componente `public`
  importado e chamado por nome curto; um componente `public` chamado
  por nome totalmente qualificado sem import; um componente
  file-private tentado chamar de outro ficheiro (`COMPONENT_NOT_VISIBLE`);
  dois componentes no mesmo ficheiro, um `public` um default, o
  file-private não aparece no índice como alvo válido de import externo.
- `IMPORT_NOT_FOUND`, `PACKAGE_DIRECTORY_MISMATCH`, `AMBIGUOUS_IMPORT`:
  um caso golden cada, verificando o diagnóstico exato.
- Ciclo entre dois ficheiros (`A` chama `B`, `B` chama `A`) compila e
  **renderiza** com sucesso via `gg.jte` real (prova de que a Fase 1
  não impõe ordem).
- Renderização ponta-a-ponta do bug de nome composto já documentado:
  `ui.NavLink(...)` chamado de outro pacote, `.jte` gerado na subpasta
  certa, `JteRenderSupport` renderiza sem `TemplateNotFoundException`.
- `SukoCompileTask`/Maven: pelo menos um teste de integração que
  aponta a um `sourceDir` com subpastas e confirma que os `.jte`
  gerados aparecem nas subpastas espelhadas em `outputDir`.
- Unificação das três heurísticas: teste que confirma que
  `SemanticChecker` e `JteEmitter` concordam sobre "é componente" para
  o mesmo nome importado (evita a divergência ledgerada no subprojeto 6).

## Próximos passos

Após este subprojeto, `ARCHITECTURE.md` é atualizado: a limitação "Não
há imports automáticos" é removida (resolvida); a limitação "Chamada
de componente com nome composto falha em runtime" é removida
(resolvida); as três heurísticas divergentes ledgeradas no subprojeto 6
ficam documentadas como unificadas. Os subprojetos 7 (registry) e 8
(CLI `suko add`), que dependem deste, ficam desbloqueados para
escrever as suas próprias specs.
