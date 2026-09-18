package io.suko.lang;

import io.suko.lang.ast.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Visitor sobre o AST que produz o texto de um template .jte por
 * ComponentDecl. Tradução próxima de 1:1 — expressões e statements sem
 * necessidade de transformação são apenas reconstruídos textualmente.
 */
public class JteEmitter {

    // DESVIO DO BRIEF (documentado, tarefa 17): o brief's Step 2 decide se
    // um grupo de SlotFill deve ser emitido como Content "nu" ou envolvido
    // em `java.util.List.of(...)` olhando SÓ para `fills.size()` no
    // call-site (1 fill = Content, >1 = List). Isto está errado sempre que
    // um param Cardinality.MANY recebe exatamente 1 fill (um cenário
    // válido e semanticamente diferente de Cardinality.ONE) — reproduzido
    // com o próprio teste desta tarefa (`WithoutTitle` só preenche
    // `actions` uma vez, mas `actions` é `List<slot<String>>`): o Java
    // gerado tenta passar um `Content` isolado onde o parâmetro é
    // `List<Content>`, falha a compilar ("incompatible types:
    // <anonymous HtmlContent> cannot be converted to List<Content>"),
    // confirmado contra o compilador real do gg.jte. Corrigido para
    // consultar a Cardinality real do SlotParam do componente-alvo
    // (`componentsByName`, construído a partir de todos os componentes do
    // ficheiro, passados ao construtor por quem orquestra a emissão de
    // múltiplos componentes — ver `JteRenderSupport.renderWithDependencies`).
    // Quando o componente-alvo não é conhecido (ex.: emissão isolada de um
    // único componente via `new JteEmitter()`, sem contexto do ficheiro —
    // usado pelos testes que não exercitam chamadas com múltiplos fills),
    // mantém-se o heurístico original baseado em `fills.size()` como
    // aproximação best-effort, documentado aqui como uma limitação
    // conhecida (não é o caso coberto pelo teste desta tarefa, que usa
    // sempre `renderWithDependencies`).
    private final Map<String, ComponentDecl> componentsByName;

    // DESVIO DO BRIEF (documentado, tarefa 10): ver `shouldWrapInToString`
    // mais abaixo para a razão de existir este campo — não faz parte do
    // brief original, que só previa `isContentTyped`. Reatribuído no início
    // de `emit(component)` a cada chamada (não é constante por instância,
    // ao contrário de `componentsByName`); seguro porque `JteEmitter` é
    // usado sempre de forma sequencial, uma `emit`/`emitWithSourceMap` de
    // cada vez, nunca concorrente (ver `JteRenderSupport.renderWithDependencies`,
    // que itera os componentes de um ficheiro num `for` simples reutilizando
    // a mesma instância).
    private Map<String, Type> currentValueParamTypes = Map.of();

    public JteEmitter() {
        this(List.of());
    }

    public JteEmitter(List<ComponentDecl> allComponents) {
        this.componentsByName = allComponents.stream()
            .collect(Collectors.toMap(ComponentDecl::name, Function.identity()));
    }

    public String emit(ComponentDecl component) {
        java.util.Set<String> slotNames = slotNamesOf(component);
        this.currentValueParamTypes = component.params().stream()
            .filter(p -> p instanceof Param.ValueParam)
            .map(p -> (Param.ValueParam) p)
            .collect(Collectors.toMap(Param.ValueParam::name, Param.ValueParam::type));

        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param, slotNames)).append('\n');
        }
        out.append('\n');
        for (Statement statement : component.body()) {
            emitStatement(statement, out, slotNames);
        }
        return out.toString();
    }

    public record EmitResult(String jteSource, java.util.List<io.suko.lang.ast.SourceMapEntry> sourceMap) {
    }

    public EmitResult emitWithSourceMap(ComponentDecl component) {
        java.util.List<io.suko.lang.ast.SourceMapEntry> entries = new ArrayList<>();
        String jteSource = emit(component);

        // Reconstrói o mapeamento percorrendo as mesmas statements de novo,
        // desta vez só para registar em que linha do .jte cada Statement de
        // topo começou a ser escrito. Suficiente para localizar erros por
        // linha (não por coluna) nos subprojetos 2/3.
        // TAREFA 22: slotNames precisa de estar disponível já aqui (antes só
        // era calculado depois do probe) porque jteParamDeclaration agora
        // também passa por emitExpr para o valor por omissão de ValueParam.
        java.util.Set<String> slotNames = slotNamesOf(component);
        StringBuilder probe = new StringBuilder();
        for (Param param : component.params()) {
            probe.append("@param ").append(jteParamDeclaration(param, slotNames)).append('\n');
        }
        probe.append('\n');
        int lineSoFar = countLines(probe.toString());

        for (Statement statement : component.body()) {
            entries.add(new io.suko.lang.ast.SourceMapEntry(lineSoFar + 1, statement.span()));
            StringBuilder single = new StringBuilder();
            emitStatement(statement, single, slotNames);
            lineSoFar += countLines(single.toString());
        }

        return new EmitResult(jteSource, entries);
    }

    private int countLines(String text) {
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        return lines;
    }

    private java.util.Set<String> slotNamesOf(ComponentDecl component) {
        java.util.Set<String> slotNames = new java.util.HashSet<>();
        for (Param param : component.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                slotNames.add(slotParam.name());
            }
        }
        return slotNames;
    }

    private String jteParamDeclaration(Param param, java.util.Set<String> slotNames) {
        // DESVIO DO BRIEF (documentado, tarefa 17): o valor por omissão de um
        // SlotParam vindo do .sk é ignorado aqui de propósito. A gramática
        // `param: type Identifier (EQ expression)?` só aceita `expression`
        // comum — não há forma válida em Suko de escrever um content block
        // (`@`...``) como valor por omissão de um slot. Por isso o valor por
        // omissão é sintetizado diretamente no Java gerado (`null` para
        // Cardinality.ONE, `java.util.List.of()` para MANY) sempre que o
        // AST tem `defaultValue` presente, independentemente do que esse
        // Expr realmente contém. O teste desta tarefa usa `?:` (tarefa 11)
        // para expressar "sem título" em vez de depender de um valor por
        // omissão real de slot — essa lacuna de sintaxe fica marcada para
        // o subprojeto 2, não bloqueia esta tarefa.
        return switch (param) {
            // TAREFA 22 (correção pós-plano): ao contrário de SlotParam (tarefas
            // 17/18), aqui o valor por omissão é uma expressão Suko normal, já
            // totalmente suportada pela gramática — não há gap de sintaxe a
            // contornar. Emitimos o Expr real via emitExpr, não um valor
            // sintetizado.
            //
            // DECISÃO DE DESIGN (tarefa 22, ponto levantado pelo próprio
            // brief): jteParamDeclaration passou a receber o `slotNames` real
            // do componente, em vez de um `Set.of()` fixo, apesar de na
            // prática o valor por omissão de um ValueParam nunca poder
            // referenciar de forma útil o nome de um slot do mesmo
            // componente — no ponto em que o valor por omissão é avaliado
            // (parâmetros do template ainda não estão todos "ligados"
            //
            //
            //
            //
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name()
                + p.defaultValue().map(expr -> " = " + emitExpr(expr, slotNames)).orElse("");
            // DESVIO DO BRIEF (documentado, tarefa 18, Step 1): o brief propõe
            // `= (T it) -> null` como valor por omissão. Verificado (RED
            // genuíno) contra o compilador Java real embutido no gg.jte que
            // isto falha a compilar SÓ no modo de render "sem tipos"
            // (`TemplateEngine.render(nome, Map<String,Object>, out)`, usado
            // por `JteRenderSupport`) — nesse modo, o próprio gg.jte gera
            // `params.getOrDefault("title", (T it) -> null)` e faz o cast
            // do RESULTADO para `Function<T, Content>`; mas `Map.getOrDefault`
            // tem assinatura `V getOrDefault(Object, V)`, e o tipo-alvo do
            // lambda no segundo argumento é inferido a partir de `V` (que
            // resolve a `Object`, o tipo do Map), não do cast externo — daí
            // "incompatible types: Object is not a functional interface".
            // Um literal `null` simples (usado nas tarefas 16/17 para
            // Content/List) não sofre disto, porque `null` é atribuível a
            // qualquer tipo de referência independentemente do tipo-alvo
            // inferido. A correção é dar ao próprio lambda um cast explícito
            // ANTES de ele ser passado como valor por omissão — assim, seja
            // qual for o `V` que `getOrDefault` infira, o lambda já tem o seu
            // tipo funcional fixado pelo cast interno, e o cast externo
            // gerado pelo gg.jte passa a ser sobre um valor já bem tipado
            // (Function<T,Content> -> Function<T,Content>, sem-op).
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE && p.renderProp() ->
                "java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content> " + p.name()
                    + (p.defaultValue().isPresent() ? " = (java.util.function.Function<" + javaType(p.elementType())
                        + ", gg.jte.Content>) (" + javaType(p.elementType()) + " it) -> null" : "");
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
            case Param.SlotParam p when p.renderProp() ->
                "java.util.List<java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content>> " + p.name()
                    + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
            case Param.SlotParam p ->
                "java.util.List<gg.jte.Content> " + p.name()
                    + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
        };
    }

    private String javaType(Type type) {
        // DESVIO DO BRIEF (documentado, tarefa 17): "Content" desqualificado
        // não resolve no Java gerado pelo gg.jte — verificado com um probe
        // direto contra o TemplateEngine real (`@param List<Content> x` +
        // `@for(Content i : x)` produz "cannot find symbol: class Content"
        // e "class List" na compilação do .jte gerado; gg.jte não injeta
        // nenhum import por omissão para tipos fora de java.lang, e o
        // JteEmitter não tem, até esta tarefa, nenhum mecanismo de import
        // (ver ruling da tarefa 13 sobre `type` não aceitar nomes
        // qualificados como `gg.jte.Content` no .sk — "extraneous input
        // '.'"). Como não há forma de escrever "gg.jte.Content" no .sk,
        // aplicamos aqui o mesmo padrão que `jteParamDeclaration` já usa
        // para SlotParam: sintetizar o nome totalmente qualificado no Java
        // emitido a partir de um nome simples e bem conhecido do .sk, só
        // para este tipo específico do domínio JTE (necessário para
        // `for (Content x : list)` iterar sobre um slot múltiplo). Isto
        // NÃO é um mecanismo geral de resolução de imports — nomes
        // qualificados continuam por resolver no subprojeto 2/3, tal como
        // a ruling da tarefa 13 já documentou.
        // DESVIO DO BRIEF (documentado, tarefa 18): estende o mesmo padrão de
        // síntese de nome qualificado que a tarefa 17 introduziu para
        // "Content" também a "List" desqualificado. O teste desta tarefa
        // (`rendersRenderPropSlot`) usa `List<String> items` como
        // ValueParam comum (não-slot) — a ruling da tarefa 13 já provou que
        // `type` não aceita nomes qualificados (`java.util.List<String>`
        // falha com "extraneous input '.'"), então o `.sk` só pode escrever
        // "List" desqualificado. Confirmado por probe direto contra o
        // TemplateEngine real (ver `probeBareListDoesNotCompile`/
        // `probeBareListSynthesisCompiles` em JteEmitterTest) que
        // `@param List<String> items` gerado por gg.jte falha a compilar
        // ("cannot find symbol: class List", sem import automático — mesmo
        // sintoma já documentado para "Content" na tarefa 17), e que
        // qualificar para `java.util.List<String>` resolve. Diferente de
        // "Content" (nunca genérico em uso real neste emitter), "List" é
        // sempre usado com argumento de tipo, mas a condição de guarda
        // (`typeArguments().isEmpty()`) não se aplica aqui propositadamente
        // — qualificamos "List" com ou sem argumentos, já que
        // `java.util.List` sem argumentos também é válido e não há
        // ambiguidade com outro "List" no escopo (mesmo raciocínio de
        // "não é um mecanismo geral de resolução de imports" da tarefa 17:
        // continua a ser uma lista fechada de nomes bem conhecidos do
        // domínio, não uma tentativa de resolver imports arbitrários).
        // "Function" entra na mesma lista fechada pela mesma razão (tarefa
        // 18): desde que todo slot<T> passa a ser emitido como
        // `Function<T, Content>` (Step 1 abaixo), iterar sobre um
        // `List<slot<T>>` num `for` do .sk precisa de escrever o item como
        // `Function<T, Content>` — e "Function" desqualificado tem
        // exatamente o mesmo problema de "cannot find symbol" que "List" e
        // "Content" já tinham (confirmado pelo mesmo tipo de probe).
        String baseName = switch (type.name()) {
            case "Content" -> type.typeArguments().isEmpty() ? "gg.jte.Content" : type.name();
            case "List" -> "java.util.List";
            case "Function" -> "java.util.function.Function";
            default -> type.name();
        };
        StringBuilder sb = new StringBuilder(baseName);
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(javaType(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(type.arrayDimensions()));
        return sb.toString();
    }

    private void emitStatement(Statement statement, StringBuilder out, java.util.Set<String> slotNames) {
        switch (statement) {
            case Statement.TextRun textRun -> out.append(textRun.text());
            case Statement.Interpolation interpolation -> {
                Expr expr = interpolation.expr();
                if (shouldWrapInToString(expr, slotNames)) {
                    String emitted = emitExpr(expr, slotNames);
                    out.append("${").append(emitted).append(" == null ? null : (").append(emitted).append(").toString()}");
                } else {
                    out.append("${").append(emitExpr(expr, slotNames)).append('}');
                }
            }
            case Statement.HtmlElement element -> emitHtmlElement(element, out, slotNames);
            case Statement.VarDecl varDecl ->
                out.append("!{var ").append(varDecl.name()).append(" = ")
                    .append(emitExpr(varDecl.value(), slotNames)).append(";}\n");
            case Statement.IfStmt ifStmt -> emitIfStmt(ifStmt, out, slotNames);
            case Statement.ForStmt forStmt -> emitForStmt(forStmt, out, slotNames);
            case Statement.SwitchStmt switchStmt -> emitSwitchStmt(switchStmt, out, slotNames);
            case Statement.ComponentCallStmt call -> emitComponentCall(call, out, slotNames);
        }
    }

    /** Não embrulhar em .toString() quando a expressão já é um slot (identificador
     * cujo nome está em slotNames) — .toString() num slot imprimiria a
     * identidade do objeto Java, não o conteúdo (ver spec, secção auto-toString). */
    private boolean isContentTyped(Expr expr, java.util.Set<String> slotNames) {
        return expr instanceof Expr.PrimaryExpr p && slotNames.contains(p.text());
    }

    // DESVIO DO BRIEF (documentado, tarefa 10): o brief propõe embrulhar
    // TODA interpolação (exceto slots) num ternário `expr == null ? null :
    // (expr).toString()`. Reproduzido (RED genuíno, com o resto da tarefa
    // já implementado): isto quebra 8 testes já verdes antes desta tarefa,
    // por duas razões distintas, ambas confirmadas contra o compilador real
    // do gg.jte:
    //
    // 1) Expressões cujo tipo Java resultante é primitivo (`int`, `boolean`,
    //    etc. — ex.: `{a + b}`, `{a > b}` em rendersArithmeticAndComparisonExpressions,
    //    `{label?.length() ?: -1}` em rendersNullSafeAccessAndElvis) não
    //    compilam quando comparadas a `null` ("bad operand types for binary
    //    operator '=='... first type: int, second type: <null>"). Não há
    //    forma de saber, sem inferência de tipo real (que o projeto já
    //    decidiu não fazer — ver ARCHITECTURE.md), que tipo Java uma
    //    expressão composta (aritmética, elvis, ternário, chamada) produz.
    //
    // 2) Expressões que produzem Content por um caminho que não é a leitura
    //    direta de um slot (`{header}`) — ex.: chamada de render-prop
    //    (`{row(item)}`), `.apply(...)` explícito sobre uma variável Function
    //    de loop (`{row.apply("x")}`), ou uma variável local atribuída a
    //    partir de uma chamada de componente como valor (`var c = useA ?
    //    CardA() : CardB(); {c}`, tarefa 5) — ficam incorretamente
    //    embrulhadas em `.toString()`, que imprime a identidade do objeto
    //    Java em vez de renderizar o conteúdo.
    //
    // Sem inferência de tipo real, a única forma segura de saber que uma
    // expressão passa por `writeUserContent` sem overload dedicado (e por
    // isso precisa do `.toString()` auto) é quando ela é um identificador
    // simples (`Expr.PrimaryExpr`) que referencia diretamente um
    // `Param.ValueParam` do próprio componente, cujo tipo Suko declarado
    // (lido do AST, não inferido) não está na lista fechada de tipos já
    // servidos por overloads de `TemplateOutput.writeUserContent` (mesma
    // lista fechada confirmada pela sonda da spec, "V1"). Qualquer outra
    // forma de expressão (composta, ou identificador sem tipo declarado
    // conhecido — variável local `var`, variável de `for`, parâmetro de
    // lambda de render-prop) é deixada tal como estava antes desta tarefa:
    // não embrulhada. Isto é estritamente mais conservador que o brief —
    // cobre o caso confirmado pela sonda da spec (`Object id` interpolado
    // diretamente) sem reintroduzir nenhuma das 8 regressões acima.
    //
    // Limitação aceite (mesma natureza da já documentada no brief): uma
    // expressão composta que produza um valor Java arbitrário sem overload
    // dedicado (ex.: `{obj.getAlgumaCoisaArbitraria()}`) não é
    // auto-toString'd por esta tarefa — precisaria de inferência de tipo
    // real, fora de âmbito (ver ARCHITECTURE.md, limitações conhecidas).
    private boolean shouldWrapInToString(Expr expr, java.util.Set<String> slotNames) {
        if (!(expr instanceof Expr.PrimaryExpr p)) return false;
        return shouldWrapIdentifierInToString(p.text(), slotNames);
    }

    /** Núcleo da decisão de auto-toString, partilhado pelas duas posições em
     * que uma interpolação pode aparecer: `Statement.Interpolation` (`{expr}`,
     * tarefa 10) e `Expr.StringPart.Interp`/`SimpleInterp` (`"...${expr}..."`,
     * tarefa 9 — inclui os valores de atributo). Antes da revisão final do
     * subprojeto 6 esta lógica só existia no primeiro caso, o que fazia o
     * mesmo `Object id` compilar como `{id}` e falhar como `href="${id}"`. */
    private boolean shouldWrapIdentifierInToString(String identifier, java.util.Set<String> slotNames) {
        if (slotNames.contains(identifier)) return false;
        Type declaredType = currentValueParamTypes.get(identifier);
        if (declaredType == null) return false;
        return isArbitraryObjectType(declaredType);
    }

    // Lista fechada dos tipos Suko/Java que já têm overload dedicado em
    // gg.jte.TemplateOutput.writeUserContent (confirmado por sonda na spec,
    // "V1") — quando o tipo declarado do param não está aqui (e não é
    // genérico nem array, casos deixados de fora por segurança, sem teste
    // que os exercite), a interpolação é embrulhada em .toString().
    private static final java.util.Set<String> KNOWN_TOSTRING_FREE_TYPE_NAMES = java.util.Set.of(
        "int", "long", "short", "byte", "double", "float", "boolean", "char",
        "Integer", "Long", "Short", "Byte", "Double", "Float", "Boolean", "Character",
        "String", "Number", "Content"
    );

    private boolean isArbitraryObjectType(Type type) {
        return type.arrayDimensions() == 0 && type.typeArguments().isEmpty()
            && !KNOWN_TOSTRING_FREE_TYPE_NAMES.contains(type.name());
    }

    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out, java.util.Set<String> slotNames) {
        out.append("@template.").append(call.componentName()).append('(');
        boolean first = true;
        for (Statement.Arg arg : call.args()) {
            if (!first) out.append(", ");
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value(), slotNames));
            first = false;
        }
        java.util.Map<String, java.util.List<Statement.SlotFill>> grouped = new java.util.LinkedHashMap<>();
        for (Statement.SlotFill slotFill : call.slotFills()) {
            grouped.computeIfAbsent(slotFill.paramName(), k -> new ArrayList<>()).add(slotFill);
        }

        for (var entry : grouped.entrySet()) {
            if (!first) out.append(", ");
            java.util.List<Statement.SlotFill> fills = entry.getValue();
            boolean wrapAsList = fills.size() > 1
                || resolveSlotCardinality(call.componentName(), entry.getKey()) == Cardinality.MANY;
            Boolean isRenderProp = resolveSlotIsRenderProp(call.componentName(), entry.getKey());
            if (!wrapAsList) {
                out.append(entry.getKey()).append(" = ");
                emitSlotFillContent(fills.get(0), out, slotNames, isRenderProp);
            } else {
                out.append(entry.getKey()).append(" = java.util.List.of(");
                for (int i = 0; i < fills.size(); i++) {
                    if (i > 0) out.append(", ");
                    emitSlotFillContent(fills.get(i), out, slotNames, isRenderProp);
                }
                out.append(")");
            }
            first = false;
        }

        out.append(")\n");
    }

/** Ver o comentário sobre `componentsByName` no construtor: devolve
      * {@code null} (tratado como "desconhecido") quando o componente-alvo
      * ou o param não são conhecidos nesta emissão. */
    private Cardinality resolveSlotCardinality(String componentName, String paramName) {
        ComponentDecl target = componentsByName.get(componentName);
        if (target == null) {
            return null;
        }
        for (Param param : target.params()) {
            if (param.name().equals(paramName) && param instanceof Param.SlotParam slotParam) {
                return slotParam.cardinality();
            }
        }
        return null;
    }

    /** Mesma limitação documentada em resolveSlotCardinality: devolve null
     * quando o componente-alvo não é conhecido nesta emissão.
     *
     * TAREFA 2 (subprojeto 6): já não faz scan do corpo do componente-alvo à
     * procura de uma chamada que coincida com o nome do slot — "é
     * render-prop" passou a ser um facto estrutural gravado diretamente em
     * `Param.SlotParam.renderProp()` no momento em que o AST é construído
     * (`SukoAstBuilder.tryBuildSlotParam`), a partir da forma da própria
     * declaração (`Function<T, Component>` vs. `Component`). Basta ler o
     * campo. */
    private Boolean resolveSlotIsRenderProp(String componentName, String paramName) {
        ComponentDecl target = componentsByName.get(componentName);
        if (target == null) {
            return null;
        }
        for (Param param : target.params()) {
            if (param.name().equals(paramName) && param instanceof Param.SlotParam slotParam) {
                return slotParam.renderProp();
            }
        }
        return null;
    }

    /** Quando isRenderProp é null (componente-alvo desconhecido nesta
     * emissão — ver comentário de resolveSlotCardinality), o melhor
     * heurístico disponível sem tabela de símbolos é olhar para o próprio
     * ponto de chamada: se o .sk escreveu "nome -> ..." (lambdaParamName
     * presente), o autor claramente pretendia um render-prop. */
    private void emitSlotFillContent(Statement.SlotFill slotFill, StringBuilder out,
            java.util.Set<String> slotNames, Boolean isRenderProp) {
        boolean renderProp = isRenderProp != null ? isRenderProp : slotFill.lambdaParamName().isPresent();
        if (renderProp) {
            String lambdaParam = slotFill.lambdaParamName().orElse("__ignored");
            out.append(lambdaParam).append(" -> @`");
        } else {
            out.append("@`");
        }
        for (Statement statement : slotFill.body()) {
            emitStatement(statement, out, slotNames);
        }
        out.append('`');
    }

    private void emitSwitchStmt(Statement.SwitchStmt switchStmt, StringBuilder out, java.util.Set<String> slotNames) {
        String subject = emitExpr(switchStmt.subject(), slotNames);
        boolean first = true;
        for (Statement.SwitchCase switchCase : switchStmt.cases()) {
            out.append(first ? "@if(" : "@elseif(").append(subject).append(".equals(")
                .append(emitExpr(switchCase.matchValue(), slotNames)).append("))\n");
            for (Statement statement : switchCase.body()) {
                emitStatement(statement, out, slotNames);
            }
            out.append('\n');
            first = false;
        }
        if (!switchStmt.defaultCase().isEmpty()) {
            out.append("@else\n");
            for (Statement statement : switchStmt.defaultCase()) {
                emitStatement(statement, out, slotNames);
            }
            out.append('\n');
        }
        out.append("@endif\n");
    }

    private void emitForStmt(Statement.ForStmt forStmt, StringBuilder out, java.util.Set<String> slotNames) {
        out.append("@for(").append(javaType(forStmt.itemType())).append(' ').append(forStmt.itemName())
            .append(" : ").append(emitExpr(forStmt.iterable(), slotNames)).append(")\n");
        for (Statement statement : forStmt.body()) {
            emitStatement(statement, out, slotNames);
        }
        out.append("\n@endfor\n");
    }

    private void emitIfStmt(Statement.IfStmt ifStmt, StringBuilder out, java.util.Set<String> slotNames) {
        out.append("@if(").append(emitExpr(ifStmt.condition(), slotNames)).append(")\n");
        for (Statement statement : ifStmt.thenBranch()) {
            emitStatement(statement, out, slotNames);
        }
        if (!ifStmt.elseBranch().isEmpty()) {
            out.append("\n@else\n");
            for (Statement statement : ifStmt.elseBranch()) {
                emitStatement(statement, out, slotNames);
            }
        }
        out.append("\n@endif\n");
    }

    private void emitHtmlElement(Statement.HtmlElement element, StringBuilder out, java.util.Set<String> slotNames) {
        out.append('<').append(element.tagName());
        for (Statement.Attribute attribute : element.attributes()) {
            out.append(' ').append(attribute.name()).append("=\"")
                .append("${").append(emitExpr(attribute.value(), slotNames)).append('}').append('"');
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

    String emitExpr(Expr expr, java.util.Set<String> slotNames) {
        return switch (expr) {
            case Expr.PrimaryExpr primary -> primary.text();
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral, slotNames);
            case Expr.AccessExpr access -> emitExpr(access.target(), slotNames) + "." + access.memberName();
            // DESVIO DO BRIEF: quando o callee de uma chamada é ele próprio um
            // SafeAccessExpr (ex.: `label?.length()`), a gramática produz
            // CallExpr(callee=SafeAccessExpr(target, member), args) — a chamada
            // "()" tem de aplicar-se DENTRO do ramo não-nulo do ternário de
            // desaçucaramento, não ao resultado do ternário já fechado. Emitir
            // cada nó independentemente e concatenar "()" a seguir (como o
            // brief propunha literalmente) produziria
            // "(alvo == null ? null : alvo.membro)()", que não compila (não se
            // pode invocar o resultado de um ternário) e, mesmo que compilasse,
            // não protegeria a própria chamada do método contra NPE. Por isso
            // este caso é tratado aqui, como uma forma só sua, antes do caso
            // genérico de CallExpr.
            case Expr.CallExpr call when call.callee() instanceof Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target(), slotNames);
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName()
                    + "(" + emitArgs(call.args(), slotNames) + "))";
            }
            // Tarefa 18, Step 3: uma chamada `nome(args)` sobre um único
            // identificador simples que coincide com o nome de um SlotParam
            // do componente atual é a forma que o .sk usa para LER um slot
            // (que passou a ser sempre `Function<T, Content>` — ver nota da
            // classe). O JteEmitter não tem tabela de símbolos própria
            // (isso é o verificador do subprojeto 2), mas o conjunto de
            // nomes de slot do componente sendo emitido está sempre
            // disponível localmente (vem dos `Param.SlotParam` do próprio
            // `ComponentDecl`), então basta esta verificação sintática —
            // sem ambiguidade real, porque não há outra forma de "chamar"
            // um identificador solto em Suko além de invocar uma função ou
            // ler um slot.
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text()) ->
                p.text() + ".apply(" + emitArgs(call.args(), slotNames) + ")";
            // Tarefa 5 (subprojeto 6): um `CallExpr` cujo callee é um nome
            // simples presente em `componentsByName` é uma chamada de
            // componente usada como VALOR de expressão (ex.: dentro de um
            // ternário atribuído a `var`), não uma chamada Java comum — tem
            // de ser emitida como bloco de conteúdo JTE
            // (`@`@template.Nome(args)``), a mesma forma confirmada
            // empiricamente pela sonda da Tarefa 1. Este `case` vem DEPOIS
            // do `case` de leitura de slot acima (que já captura os nomes
            // em `slotNames` primeiro, por ordem do switch) e ANTES do
            // `case` genérico de CallExpr abaixo, para que os dois nunca
            // colidam: um nome não pode simultaneamente ser slot do
            // componente atual e nome de outro componente conhecido.
            //
            // DESVIO DO BRIEF: o brief refere um campo `callResolver` já
            // existente no `JteEmitter` para resolução cross-file do nome
            // do template. Não existe tal campo — confirmado por pesquisa
            // no código-fonte (`grep -rn callResolver src/` não encontra
            // nada) — e a forma equivalente já usada pelo emissor para
            // chamadas de componente em posição de statement
            // (`emitComponentCall`, statement form) também não o usa:
            // emite `@template.` + `call.componentName()` diretamente. Por
            // consistência com esse precedente já estabelecido no próprio
            // ficheiro, usa-se aqui `p.text()` diretamente, sem indireção
            // por resolver. Se uma futura tarefa cross-file precisar de tal
            // resolução, deve introduzir o campo nesse ponto, atualizando
            // ambos os locais.
            case Expr.CallExpr call when call.callee() instanceof Expr.PrimaryExpr p && componentsByName.containsKey(p.text()) ->
                "@`@template." + p.text() + "(" + emitArgs(call.args(), slotNames) + ")`";
            case Expr.CallExpr call -> emitExpr(call.callee(), slotNames) + "(" + emitArgs(call.args(), slotNames) + ")";
            case Expr.NotExpr not -> "!" + emitExpr(not.operand(), slotNames);
            case Expr.UnaryMinusExpr unaryMinus -> "-" + emitExpr(unaryMinus.operand(), slotNames);
            case Expr.BinaryExpr binary ->
                emitExpr(binary.left(), slotNames) + " " + binary.operator() + " " + emitExpr(binary.right(), slotNames);
            case Expr.TernaryExpr ternary -> emitExpr(ternary.condition(), slotNames) + " ? "
                + emitExpr(ternary.whenTrue(), slotNames) + " : " + emitExpr(ternary.whenFalse(), slotNames);
            case Expr.ParenExpr paren -> "(" + emitExpr(paren.inner(), slotNames) + ")";
            case Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target(), slotNames);
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName() + ")";
            }
            case Expr.ElvisExpr elvis -> {
                String left = emitExpr(elvis.left(), slotNames);
                yield "(" + left + " == null ? " + emitExpr(elvis.right(), slotNames) + " : " + left + ")";
            }
        };
    }

    private String emitArgs(java.util.List<Expr> args, java.util.Set<String> slotNames) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpr(args.get(i), slotNames));
        }
        return sb.toString();
    }

    private String emitStringLiteral(Expr.StringLiteralExpr stringLiteral, java.util.Set<String> slotNames) {
        List<Expr.StringPart> parts = stringLiteral.parts();
        boolean hasInterpolation = parts.stream().anyMatch(p -> !(p instanceof Expr.StringPart.Literal));
        if (!hasInterpolation) {
            StringBuilder sb = new StringBuilder("\"");
            for (Expr.StringPart part : parts) {
                sb.append(((Expr.StringPart.Literal) part).javaEscapedText());
            }
            return sb.append('"').toString();
        }

        // CORREÇÃO (revisão final do subprojeto 6, achado 3): a concatenação
        // tem de estar ANCORADA num valor de tipo String à esquerda. Sem
        // âncora, `"${a}${b}"` emitia `(a) + (b)` — para `a`/`b` numéricos
        // isso é SOMA em Java (`1 + 2` → `3`), não concatenação de texto; e
        // uma string que seja puramente uma interpolação (`"$count"`) emitia
        // a expressão nua, que não compila onde se espera um `String`
        // (ex.: `String label = count;`). Como `+` é associativo à esquerda,
        // basta que a PRIMEIRA parte seja String para que todas as seguintes
        // concatenem como texto — por isso o `"" + ` só é necessário quando
        // a primeira parte não é um literal.
        //
        // Exceção deliberada: quando a primeira parte é a leitura direta de
        // um slot (`isContentTyped`), NÃO se ancora — ancorar forçaria
        // `gg.jte.Content.toString()`, que imprime a identidade do objeto
        // Java em vez de renderizar o conteúdo (mesmo raciocínio de
        // `isContentTyped` em `shouldWrapInToString`).
        boolean needsAnchor = !(parts.get(0) instanceof Expr.StringPart.Literal) && !firstPartIsContentTyped(parts.get(0), slotNames);

        // CORREÇÃO (fix pós-revisão-final: regressão do achado 3): quando a
        // string é uma ÚNICA parte interpolada, a âncora incondicional
        // `"" + X` transforma um `X` null (tipo referência, ex. `String`) no
        // TEXTO LITERAL "null" (semântica de concatenação Java, `"" + null`
        // == "null") — regressão real face ao comportamento correto que já
        // existia para esse caso antes da âncora ser introduzida (um `String`
        // simples interpolado sozinho já compilava e renderizava null como
        // vazio via o próprio gg.jte). Só é seguro comparar a `null` quando
        // sabemos que o identificador resolve a um `ValueParam` de tipo
        // referência conhecido (nunca primitivo — `int == null` nem compila).
        // Para qualquer outra forma (expressão composta, identificador não
        // resolvível, ex. variável de for-loop) mantém-se a âncora
        // incondicional de sempre, sem arriscar "X == null" sobre um valor
        // que pode ser primitivo.
        if (parts.size() == 1 && needsAnchor) {
            String identifier = singlePartBareIdentifier(parts.get(0));
            if (isKnownNullableIdentifier(identifier)) {
                String emitted = emitStringPart(parts.get(0), slotNames);
                return "(" + emitted + " == null ? \"\" : \"\" + " + emitted + ")";
            }
        }

        StringBuilder sb = new StringBuilder();
        if (needsAnchor) {
            sb.append("\"\" + ");
        }
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" + ");
            sb.append(emitStringPart(parts.get(i), slotNames));
        }
        return sb.toString();
    }

    private boolean firstPartIsContentTyped(Expr.StringPart part, java.util.Set<String> slotNames) {
        return switch (part) {
            case Expr.StringPart.Literal ignored -> false;
            case Expr.StringPart.Interp interp -> isContentTyped(interp.expr(), slotNames);
            case Expr.StringPart.SimpleInterp simple -> slotNames.contains(simple.identifier());
        };
    }

    /** Java gerado para uma parte de string literal — partilhado entre o
     * caminho normal de concatenação e o caminho null-safe de parte única
     * (ver `emitStringLiteral`). */
    private String emitStringPart(Expr.StringPart part, java.util.Set<String> slotNames) {
        return switch (part) {
            case Expr.StringPart.Literal literal -> "\"" + literal.javaEscapedText() + "\"";
            // CORREÇÃO (revisão final do subprojeto 6, achado 4): a decisão
            // de auto-toString da tarefa 10 (antes só aplicada a
            // `Statement.Interpolation`) aplica-se agora também à
            // interpolação DENTRO de uma string literal — sem isto,
            // `<a href="${id}">` não compilava para o mesmo `Object id`
            // que `{id}` (statement) já compilava.
            case Expr.StringPart.Interp interp -> {
                String emitted = emitExpr(interp.expr(), slotNames);
                yield shouldWrapInToString(interp.expr(), slotNames)
                    ? toStringWrapped(emitted)
                    : "(" + emitted + ")";
            }
            case Expr.StringPart.SimpleInterp simple ->
                shouldWrapIdentifierInToString(simple.identifier(), slotNames)
                    ? toStringWrapped(simple.identifier())
                    : simple.identifier();
        };
    }

    /** Identificador de um `StringPart.SimpleInterp`, ou de um
     * `StringPart.Interp` cuja expressão é um identificador simples — `null`
     * para qualquer outra forma (expressão composta, literal), usado pelo
     * caminho null-safe de parte única em `emitStringLiteral`. */
    private String singlePartBareIdentifier(Expr.StringPart part) {
        return switch (part) {
            case Expr.StringPart.SimpleInterp simple -> simple.identifier();
            case Expr.StringPart.Interp interp when interp.expr() instanceof Expr.PrimaryExpr p -> p.text();
            default -> null;
        };
    }

    private static final java.util.Set<String> PRIMITIVE_TYPE_NAMES = java.util.Set.of(
        "int", "long", "short", "byte", "double", "float", "boolean", "char"
    );

    /** Verdadeiro só quando o identificador resolve a um `ValueParam` do
     * componente atual com tipo declarado conhecido e NÃO primitivo — só aí
     * é seguro comparar o valor Java emitido a `null` sem arriscar um erro
     * de compilação (`int == null` não compila). */
    private boolean isKnownNullableIdentifier(String identifier) {
        if (identifier == null) return false;
        Type declaredType = currentValueParamTypes.get(identifier);
        return declaredType != null && declaredType.arrayDimensions() == 0
            && declaredType.typeArguments().isEmpty()
            && !PRIMITIVE_TYPE_NAMES.contains(declaredType.name());
    }

    /** Forma parentetizada do auto-toString null-safe (tarefa 10), segura para
     * ser usada como operando de uma concatenação. */
    private String toStringWrapped(String emitted) {
        return "(" + emitted + " == null ? null : (" + emitted + ").toString())";
    }
}
