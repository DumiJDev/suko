# Suko — Subprojeto 3: Verificação Java

Data: 2026-09-17

## Contexto e visão do produto

O Subprojeto 3 introduz a verificação Java no pipeline de compilação Suko. Após a análise semântica (subprojeto 2) garantir que componentes existem e slots são preenchidos corretamente, o Subprojeto 3 garante que o código Java gerado pelo `JteEmitter` compila corretamente. Isso é feito através de:

1. **JteCompiler**: Orquestra todo o pipeline de compilação (.sk → parse → análise semântica → .jte)
2. **JavacTask**: Gera stubs Java por componente, compila com `javac`, e mapeia erros de volta ao `.sk` original usando source maps

## Estado herdado (do Subprojeto 2)

O Subprojeto 2 já entrega:
- `DiagnosticCollector` para acumular diagnostics
- `SukoErrorListener` para capturar erros de parse do ANTLR
- `SymbolTable` para registro e lookup de componentes
- `SemanticChecker` para validação semântica (existência de componentes, slots, cardinalidade)
- Pipeline completo até geração de `.jte`

## Escopo do Subprojeto 3

### 1. JteCompiler — Orquestrador do Pipeline

Implementar `JteCompiler` que:
- Toma um arquivo `.sk` como input
- Executa o pipeline completo: Parse → Semantic Check → JTE Emit
- Retorna `CompileResult` com sucesso/falha e diagnostics
- Integra `SukoErrorListener`, `SymbolTable`, `SemanticChecker` e `JteEmitter`

#### 1.1 Compilação Completa
- Parse: `SukoLexer` + `SukoParser` com `SukoErrorListener`
- AST: `SukoAstBuilder` converte ParseTree em `SukoFile`
- Semântica: `SymbolTable` registra componentes, `SemanticChecker` valida
- Emissão: `JteEmitter.emitWithSourceMap()` gera `.jte` com source map
- Retorna `CompileResult.success(jteSources)` ou `CompileResult.failure(diagnostics)`

### 2. JavacTask — Verificação Java

Implementar `JavacTask` que:
- Toma um `SukoFile` e os source maps de `.jte`
- Gera stubs Java para cada componente (classe com fields correspondentes aos parâmetros)
- Compila os stubs com `javax.tools.JavaCompiler` (javac)
- Mapeia erros de compilação de volta ao `.sk` original usando o source map
- Retorna `DiagnosticCollector` com erros e warnings mapeados

#### 2.1 Geração de Stubs
Para cada `ComponentDecl`, gera uma classe Java:
- ValueParams → fields com o tipo Java correspondente
- SlotParams ONE → fields com o tipo Java do elemento
- SlotParams MANY → `List<tipo>` fields

#### 2.2 Compilação e Mapeamento de Erros
- Usa `ToolProvider.getSystemJavaCompiler()` para compilar stubs
- Para cada erro de compilação:
  - Obtém o número da linha do arquivo `.java`
  - Procura no `jteSourceMaps` o `SourceMapEntry` correspondente
  - Extrai o `SourceSpan` no `.sk` original
  - Cria um `SukoDiagnostic` com a localização mapeada

### 3. Testes

- `JteCompilerTest`: testes do pipeline completo
  - Compilação de arquivo válido (sucesso)
  - Componente inexistente (falha semântica)
  - Slot obrigatório faltando (falha semântica)
  - Múltiplos componentes (sucesso)
- `JavacTaskTest`: testes da compilação Java
  - Stub simples (sucesso)
  - Múltiplos componentes (sucesso)
  - Componente com SlotParam (sucesso)

### 4. Documentação

- Atualizar `ARCHITECTURE.md` com subprojeto 3 concluído
- Atualizar pipeline na seção de arquitetura
- Atualizar roadmap de subprojetos

## Limitações conhecidas (fim do subprojeto 3)

Este subprojeto deliberadamente **não cobre**:
- Compilação real do `.jte` gerado para Java bytecode (isso é feito pelo `gg.jte`)
- Verificação de tipos profundos em expressões Java (deixada para subprojeto 4)
- Compilação de componentes genéricos (deixada para subprojeto dedicado)
- Validação de URLs perigosas e escape de HTML (deixada para expansões futuras)

## Testes

- Testes unitários para `JteCompiler` e `JavacTask`
- Testes de integração com `.sk` reais
- Testes edge case (componentes sem parâmetros, slots MANY, etc.)

## Próximos passos

Após este subprojeto, o pipeline terá:
```
.Sk (fonte Suko)
  ├─ ANTLR + SukoErrorListener → Parse
  ├─ SukoAstBuilder → AST
  ├─ SymbolTable + SemanticChecker → Validação semântica
  ├─ JteEmitter → .jte (com source map)
  ├─ JavacTask → Verificação Java (stubs + javac)
  └─ gg.jte → Renderização (subprojeto 4)
```

## Referências

- ARCHITECTURE.md: descreve o pipeline completo
- docs/superpowers/plans/2026-09-17-suko-verificador.md: plano de implementação do subprojeto 2
- docs/superpowers/specs/2026-09-13-suko-nucleo-linguagem-design.md: design da linguagem