# Suko — Pipeline de compilação

```
.sk (fonte Suko)
      │
      ▼
 ANTLR Lexer/Parser (gerado a partir de Suko.g4)
      │  produz: ParseTree
      ▼
 SukoAstBuilder (Visitor)
      │  produz: AST tipado (SukoFile, ComponentDecl, Statement, Expr, ...)
      ▼
 Análise semântica
      │  - resolve imports/alias
      │  - checa slots nomeados obrigatórios/proibidos
      │  - checa abertura/fechamento de tag coincidindo
      │  - checa tipos de generics em uso (checagem leve, delega
      │    tipagem profunda ao javac na próxima fase)
      ▼
 JteEmitter (Visitor sobre o AST)
      │  produz: arquivo .jte equivalente, 1:1 por componente
      ▼
 .jte (arquivo intermediário, gerado e versionável)
      │
      ▼
 Compilador JTE padrão (gg.jte) — inalterado
      │
      ▼
 Classe Java compilada, renderização em runtime
```

## Decisões de design que moldam o pipeline

- **Geração de JTE puro como intermediário** (não bytecode direto):
  o `.jte` gerado fica no `build/generated-src`, é legível e
  debugável, e o Suko não precisa reimplementar nada que o JTE já
  resolve bem (cache de template, performance, integração com
  Spring/Quarkus).
- **Escopo do Suko é só a camada de view.** Rotas, controllers,
  DI etc. continuam exatamente como no ecossistema JTE hoje
  (jte-spring-boot-starter, jte-quarkus, etc.) — o Suko não tenta
  competir nesse espaço, só substitui a sintaxe do `.jte` em si.
- **1 componente Suko → 1 template JTE.** Mantém rastreabilidade
  simples: erro de renderização em produção aponta pro `.jte`
  gerado, que por sua vez mapeia 1:1 de volta pro `.sk` de origem
  via source maps (a implementar).
- **Slots nomeados** (`Layout(...) { Sidebar { } Content { } }`)
  viram parâmetros de `Content`/`Content<T>` do próprio JTE
  (o JTE já suporta parâmetros de conteúdo/`@Content` nativamente),
  então a tradução é direta — não é preciso inventar um mecanismo
  de runtime novo, só desaçucarar a sintaxe.
- **Texto dentro de tags resolvido no parser, não no lexer.** A
  primeira tentativa usava um modo léxico `TEXT` (entrado via ação
  do parser logo após o `>` de uma tag de abertura) para lexar
  texto puro sem competir com palavras-chave. Isso quebrava
  exatamente o caso que o Suko precisa: `for`/`if` como filhos
  diretos de uma tag (`<ul> for (item : items) { <li>...</li> } </ul>`),
  porque, uma vez dentro do modo TEXT, o lexer fica cego para
  `for`/`if` como token — eles virariam texto literal por engano.
  A solução adotada: `for`/`if`/`var`/`switch`/`componentCall`
  continuam sendo tokens normais o tempo todo (nenhum modo léxico
  extra), e um `textRun` (`(~(LBRACE|RBRACE|LT))+`) funciona como
  alternativa de último recurso dentro de `templateStatement`. O
  ANTLR resolve a ambiguidade certa via lookahead completo: só cai
  em `textRun` quando a sequência de tokens não fecha um `forStmt`/
  `ifStmt`/etc. de verdade — ou seja, "for" como palavra solta em
  texto continua funcionando. O texto literal final (com
  espaçamento e hífens preservados) é recuperado no AST builder
  pela posição de caractere no fonte, não por concatenação de
  tokens.

## Validação feita até agora

Rodei o ANTLR (4.11.1, disponível localmente neste ambiente) contra
`SukoLexer.g4` + `SukoParser.g4` — geração limpa, sem erros nem
avisos de ambiguidade, inclusive depois do pivô acima. Esse
ambiente não tem um JDK completo instalado (só JRE, sem `javac`) e
não tem acesso à rede para instalar um, então não consegui
compilar as classes geradas nem rodar um parse de verdade contra o
`Card.sk`. Revisei manualmente token a token os trechos mais
arriscados do exemplo (texto misturado com `for`/interpolação/tags
aninhadas) e a estrutura bate com a gramática — mas o próximo passo
real é rodar `./gradlew generateGrammarSource compileJava` num
ambiente com JDK completo pra confirmar em código.

## Próximos passos técnicos (em ordem)

1. Rodar `./gradlew generateGrammarSource compileJava` (ou
   `compileTestJava` com um teste simples) num ambiente com JDK
   completo, usando `Card.sk` como smoke test — isso ainda não foi
   validado de fato em código, só na análise estática do ANTLR.
2. Implementar `SukoAstBuilder` cobrindo o exemplo `Card.sk`.
3. Implementar `JteEmitter` para o subconjunto do `Card.sk`
   (sem generics ainda) e validar o `.jte` gerado compilando de
   verdade com `gg.jte`.
4. Adicionar generics, slots nomeados múltiplos e switch no emitter.
5. Escrever testes golden-file: `.sk` de entrada → `.jte` esperado.
