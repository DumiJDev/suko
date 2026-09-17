# Suko — Subprojeto 2: Verificador Suko — Plano de Implementação

Data: 2026-09-17

## Fase 1: Infraestrutura de Diagnóstico

### 1.1 Criar `SukoDiagnostic`
- Localização: `src/main/java/io/suko/lang/diagnostic/SukoDiagnostic.java`
- Classe que representa um erro ou aviso com:
  - `severity`: enum `ERROR`, `WARNING`
  - `message`: String amigável
  - `sourceSpan`: SourceSpan do `.sk`
  - `code`: String identificador do erro
- Método `toString()` para formatar como `[ERROR] file.sk:5:10 - mensagem`

### 1.2 Criar `DiagnosticCollector`
- Localização: `src/main/java/io/suko/lang/diagnostic/DiagnosticCollector.java`
- Classe que acumula diagnostics
- Método `add(SukoDiagnostic)` para adicionar um diagnóstico
- Método `hasErrors()` para verificar se há erros
- Método `getErrors()` para listar todos os erros
- Método `toString()` para formatar todos os diagnostics

### 1.3 Criar `SukoErrorListener`
- Localização: `src/main/java/io/suko/lang/diagnostic/SukoErrorListener.java`
- Implementa `ANTLRErrorListener`
- Captura erros de parse e cria `SukoDiagnostic` para cada um
- Adiciona ao `DiagnosticCollector`

### 1.4 Testes da infraestrutura de diagnóstico
- Testes unitários para `SukoDiagnostic` e `DiagnosticCollector`
- Teste que verifica que `SukoErrorListener` captura erros do ANTLR

## Fase 2: Tabela de Símbolos de Componentes

### 2.1 Criar `SymbolTable`
- Localização: `src/main/java/io/suko/lang/symbol/SymbolTable.java`
- Classe que armazena componentes registrados
- Métodos:
  - `register(ComponentDecl)`: registra um componente
  - `lookup(String name)`: busca um componente pelo nome
  - `contains(String name)`: verifica se componente existe
  - `getAll()`: lista todos os componentes registrados
- Cada entrada armazena:
  - Nome do componente
  - Lista de parâmetros (`List<Param>`)
  - Fonte (SourceSpan) para localização de erros

### 2.2 Testes da Tabela de Símbolos
- Teste que registra e busca componentes
- Teste que verifica `lookup` para componente inexistente
- Teste com múltiplos componentes no mesmo arquivo

## Fase 3: Validador Semântico

### 3.1 Criar `SemanticChecker`
- Localização: `src/main/java/io/suko/lang/semantic/SemanticChecker.java`
- Visitor que percorre o AST e valida semântica
- Recebe `SymbolTable` e `DiagnosticCollector`
- Método principal: `check(SukoFile)`: executa a verificação completa
- Lógica de verificação:
  1. Registrar todos os componentes na tabela de símbolos
  2. Para cada componente, verificar seu corpo:
     - Component calls: verificar que o componente chamado existe
     - Slot fills: verificar cardinalidade e obrigatoriedade
     - Type checks básicos em expressões simples
  3. Reportar diagnostics para todos os problemas encontrados

#### 3.1.1 Validação de Chamadas de Componente
- Para cada `Statement.ComponentCallStmt`:
  - Verificar que `call.componentName()` existe em `SymbolTable`
  - Se não existir: `COMPONENT_NOT_FOUND`
  - Verificar que o número de argumentos posicionais está correto
  - Verificar que cada `Arg` corresponde a um `Param` declarado
  - Verificar que slots obrigatórios são preenchidos
  - Verificar que `slot` fills correspondem a slots declarados

#### 3.1.2 Validação de Slot Fills
- Para cada `ComponentCallStmt`, agrupar `SlotFill` por `paramName`
- Para cada slot fill:
  - Verificar que o slot existe no componente chamado
  - Verificar cardinalidade (`ONE` vs `MANY`)
  - Verificar que slots obrigatórios são preenchidos
  - Verificar que nenhum slot é preenchido mais de uma vez (exceto `MANY`)
- Se slot não existe: `SLOT_NOT_FOUND`
- Se cardinalidade violada: `CARDINALITY_VIOLATION`
- Se slot obrigatório não preenchido: `REQUIRED_SLOT_MISSING`

### 3.2 Testes do Validador Semântico
- Teste com `Card.sk` existente (deve passar sem erros)
- Teste com componente chamado inexistente (deve reportar `COMPONENT_NOT_FOUND`)
- Teste com slot obrigatório não preenchido (deve reportar `REQUIRED_SLOT_MISSING`)
- Teste com cardinalidade violada (deve reportar `CARDINALITY_VIOLATION`)
- Teste com slot não existente no componente (deve reportar `SLOT_NOT_FOUND`)

## Fase 4: Integração no Pipeline

### 4.1 Criar `SukoCompiler` (ou `JteCompiler`)
- Localização: `src/main/java/io/suko/lang/JteCompiler.java`
- Classe que orquestra todo o pipeline:
  1. Parse → ANTLR com `SukoErrorListener`
  2. Se erros de parse: abortar e reportar
  3. AST → `SukoAstBuilder`
  4. Semântica: `SymbolTable` + `SemanticChecker`
  5. Se erros semânticos: abortar e reportar
  6. Senão: `JteEmitter` → `.jte`
- Método `compile(String sukoSource)` retorna `CompileResult` (sucesso ou falha com diagnostics)

### 4.2 Atualizar Testes de Integração
- `SukoEndToEndTest`: adicionar verificação que passa sem erros
- Criar testes que verificam que código com erros semânticos falha corretamente

## Fase 5: Documentação e Finalização

### 5.1 Atualizar `ARCHITECTURE.md`
- Adicionar seção sobre o Verificador Suko
- Atualizar diagrama do pipeline para incluir análise semântica
- Mover limitações conhecidas para a seção adequada
- Atualizar "Validação feita até agora"
- Atualizar roadmap

### 5.2 Atualizar `docs/superpowers/plans/`
- Registrar que o plano foi concluído

## Entregáveis

Ao final deste plano, o pipeline terá:
```
.sk → ANTLR → SemanticChecker → SymbolTable → SemanticError? → abort
              ↓ sem erros
              ↓
       JteEmitter → .jte → gg.jte → render
```

## Critérios de Conclusão

1. `SymbolTable` funciona com registro e lookup de componentes
2. `SemanticChecker` detecta componentes inexistentes, slots faltantes e cardinalidade errada
3. `SukoErrorListener` captura erros de parse com localização precisa
4. `DiagnosticCollector` acumula e reporta diagnostics
5. Pipeline completo aborta em caso de erros semânticos
6. Todos os testes passam (`rtk gradlew test`)
7. `ARCHITECTURE.md` atualizado