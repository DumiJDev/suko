# Suko — Subprojeto 3: Verificação Java — Plano de Implementação

Data: 2026-09-17

## Fase 1: JteCompiler (Orquestrador do Pipeline)

### 1.1 Criar `JteCompiler.java`
- Localização: `src/main/java/io/suko/lang/JteCompiler.java`
- Implementa `compile()` que:
  1. Parse o .sk usando ANTLR com `SukoErrorListener`
  2. Se erros de parse: retorna falha com diagnostics
  3. Constrói AST usando `SukoAstBuilder`
  4. Executa `SemanticChecker` para validação
  5. Se erros semânticos: retorna falha com diagnostics
  6. Gera .jte usando `JteEmitter` com source map
  7. Se sucesso: retorna `CompileResult.success(jteSources)`

### 1.2 Testes de JteCompiler
- `src/test/java/io/suko/lang/JteCompilerTest.java`
  - Teste `compilesValidSukoFile`: compila componente válido
  - Teste `reportsMissingComponent`: detecta componente inexistente
  - Teste `reportsMissingRequiredSlot`: detecta slot obrigatório faltando
  - Teste `compilesMultipleComponents`: múltiplos componentes válidos

## Fase 2: JavacTask (Verificação Java)

### 2.1 Criar `JavacTask.java`
- Localização: `src/main/java/io/suko/lang/JavacTask.java`
- Construtor: `(SukoFile sukoFile, Map<String, List<SourceMapEntry>> jteSourceMaps)`
- Método `compile()`:
  1. Cria diretório temporário para stubs Java
  2. Para cada componente: gera stub Java com fields
  3. Usa `javax.tools.JavaCompiler` para compilar stubs
  4. Para cada erro: mapeia linha .java → .sk usando source map
  5. Retorna `DiagnosticCollector` com erros mapeados
  6. Limpa diretório temporário

### 2.2 Geração de Stubs Java
Template para cada componente:
```java
public class NomeComponente {
    public Tipo param1 tipo1;
    public Tipo param2 tipo2;
    // ... mais fields
}
```

Para SlotParam ONE: `public Tipo elemento;`
Para SlotParam MANY: `public java.util.List<Tipo> nome;`

### 2.3 Mapeamento de Erros
- Usa `Diagnostic.getLineNumber()` do javac
- Mapeia para `SourceMapEntry` no `.jte`
- Extrai `SourceSpan` no `.sk` original
- Cria `SukoDiagnostic` com localização mapeada

### 2.4 Testes de JavacTask
- `src/test/java/io/suko/lang/JavacTaskTest.java`
  - Teste `compilesSimpleComponent`: stub simples compila
  - Teste `compilesMultipleComponents`: múltiplos stubs compilam
  - Teste `reportsErrorWhenJavacNotAvailable`: javac indisponível
  - Teste `compilesComponentWithSlotParam`: stub com slot compila

## Fase 3: Integração e Documentação

### 3.1 Testes de Integração
- Atualizar `SukoEndToEndTest` para usar `JteCompiler`
- Testes de erro mapeados (verificar que posições devolvem .sk correto)

### 3.2 Documentação
- `docs/superpowers/specs/2026-09-17-suko-verificacao-java.md` ✅ criado
- Atualizar `ARCHITECTURE.md`:
  - Seção "Pipeline de compilação" com subprojeto 3
  - Seção "Limitações conhecidas" atualizada
  - Roadmap atualizado (subprojeto 3 concluído)

### 3.3 Clean-up
- Remover código duplicado
- Verificar imports desnecessários
- Garantir consistência de estilo

## Entregáveis

1. `JteCompiler.java` — Orquestrador do pipeline completo
2. `JavacTask.java` — Verificação Java com mapeamento de erros
3. `JteCompilerTest.java` — Testes de integração
4. `JavacTaskTest.java` — Testes unitários
5. `docs/superpowers/specs/2026-09-17-suko-verificacao-java.md` — Especificação
6. `ARCHITECTURE.md` atualizado

## Critérios de Conclusão

1. ✅ `JteCompiler.compile()` executa pipeline completo
2. ✅ Detecta erros de parse com `SukoErrorListener`
3. ✅ Detecta erros semânticos com `SemanticChecker`
4. ✅ Gera .jte com source map via `JteEmitter`
5. ✅ `JavacTask.compile()` compila stubs Java
6. ✅ Mapa erros decompilação para .sk
7. ✅ Todos os testes passam (`rtk gradlew test`)
8. ✅ `ARCHITECTURE.md` atualizado