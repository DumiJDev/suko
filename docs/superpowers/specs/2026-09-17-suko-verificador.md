# Suko — Subprojeto 2: Verificador Suko

Data: 2026-09-17

## Contexto e visão do produto

O Subprojeto 2 do Suko introduz a análise semântica e verificação estática no pipeline de compilação. Enquanto o Subprojeto 1 (núcleo da linguagem) garante que o código faz parse e gera um `.jte` válido, o Verificador Suko vai além, garantindo que o código seja não apenas sintaticamente correto, mas também semanticamente sólido antes mesmo de chegar ao `gg.jte`.

Este subprojeto traz:

1. **Diagnóstico precoce**: Erros de compilação apontam exatamente para a linha e coluna do arquivo `.sk`
2. **Validação de componentes**: Garantia de que componentes são declarados antes de serem usados
3. **Verificação de slots**: Garantia de que slots obrigatórios são preenchidos, com cardinalidade correta
4. **Validação de tipos**: Verificação básica de tipos em chamadas de componente e atribuições de slots
5. **Infraestrutura de diagnóstico**: Base para futuras melhorias (como autocomplete, refatoração, etc.)

## Estado herdado (do Subprojeto 1)

O Subprojeto 1 já entrega:
- Gramática corrigida que aceita a sintaxe completa do Suko (incluindo slots, `var`, etc.)
- `SukoAstBuilder` que converte a árvore de parse em AST tipado
- `JteEmitter` que gera `.jte` válido a partir do AST
- Source map em memória (linha do `.jte` → span do `.sk`)
- Testes de integração que verificam que o `.jte` gerado compila e renderiza corretamente

Falta ainda:
- Validação de que componentes referenciados realmente existem
- Verificação de que slots declarados em um componente são preenchidos corretamente
- Detecção de uso indevido de tipos (como usar um `List<slot<T>>` onde se espera um `slot<T>`)
- Infraestrutura para relatar erros semânticos com localização precisa

## Escopo do Subprojeto 2

### 1. Tabela de Símbolos de Componentes

Implementar um `SymbolTable` que:
- Registra todos os componentes declarados em um arquivo `.sk`
- Permite lookup de componentes pelo nome (com suporte a namespaces futuros)
- Armazena metadados de cada componente:
  - Nome e tipo dos parâmetros (`ValueParam` e `SlotParam`)
  - Cardinalidade dos slots (`ONE` vs `MANY`)
  - Se um slot é render-prop (detectado pelo `JteEmitter` já)
  - Tipo de retorno (sempre `void` por enquanto, já que componentes Suko não retornam valores)

### 2. Validação Semântica

Implementar um `SemanticChecker` visitor que percorre o AST e verifica:

#### Validação de Chamadas de Componente
- O componente chamado existe na tabela de símbolos
- O número e tipos de argumentos correspondem aos parâmetros declarados
- Valores por omissão são aplicados corretamente
- Slot fills correspondem a slots declarados no componente

#### Validação de Slot Fills
- Cada slot fill corresponde a um slot declarado no componente chamado
- Cardinalidade respeitada (`ONE` slot recebe no máximo um fill, `MANY` pode receber vários)
- Se o slot é render-prop, o fill deve ser um bloco de template (não texto simples)
- Slots obrigatórios (sem valor por omissão) são preenchidos
- Nenhum slot é preenchido mais de uma vez (a menos que seja `MANY`)

#### Validação de Expressões e Statements
- Variáveis declaradas com `var` são inicializadas antes do uso
- Tipos básicos são compatíveis em atribuições (por enquanto, foco em componentes e slots)

### 3. Infraestrutura de Diagnóstico

Implementar:
- `SukoErrorListener`: substitui o `ErrorListener` padrão do ANTLR para capturar erros de parse com localização precisa
- `SukoDiagnostic`: classe que representa um erro ou aviso, contendo:
  - Mensagem amigável
  - Localização (arquivo, linha, coluna)
  - Severidade (erro ou aviso)
  - Código do erro (para futura documentação)
- `DiagnosticCollector`: acumula todos os diagnostics encontrados durante a verificação
- Integração com o build para abortar se houver erros e imprimir diagnostics formatados

### 4. Integração no Pipeline

Modificar o pipeline para:
1. Parse → AST (já existente)
2. **Análise semântica** (nova etapa)
3. Se houver erros semânticos: abortar e reportar
4. Senão: continuar para `JteEmitter` → `.jte` → `gg.jte`

## Limitações conhecidas (fim do subprojeto 2)

Este subprojeto deliberadamente **não cobre**:
- Verificação de tipos Java profunda (deixada para o subprojeto 3)
- Verificação de expressão `{slot ?: "fallback"}` para slots (limitado pelo tipo `Content` vs tipos primitivos no `?:` do Java)
- Verificação de acesso a propriedades em objetos (ex: `user.name` onde `user` é parâmetro)
- Verificação de URLs perigosas ou escape de HTML (deixada para expansões futuras)

## Testes

- Testes unitários para `SymbolTable` e `SemanticChecker`
- Testes de integração que verificam que erros semânticos são detectados e reportedos com localização precisa
- Testes de golden-file para mensagens de erro esperadas
- Extensão dos testes ponta-a-ponta existentes para cobrir cenários de falha semântica

## Próximos passos

Após este subprojeto, o pipeline terá:
```
.sk (fonte Suko)
       │
       ▼
 ANTLR Lexer/Parser (com ErrorListener customizado)
       │  produz: ParseTree + diagnostics de parse
       ▼
 SukoAstBuilder (Visitor)
       │  produz: AST tipado
       ▼
 Análise semântica (SymbolTable + SemanticChecker)
       │  produz: diagnostics semânticos (aborta se houver erros)
       ▼
 JteEmitter (Visitor sobre o AST)
       │  produz: arquivo .jte equivalente
       ▼
 .jte (arquivo intermediário, gerado e versionável)
       ▼
 Compilador JTE padrão (gg.jte) — inalterado
       ▼
 Classe Java compilada, renderização em runtime
```

## Referências

- ARCHITECTURE.md: descreve o pipeline completo e limitações conhecidas
- docs/superpowers/plans/2026-09-17-suko-verificador.md: plano de implementação