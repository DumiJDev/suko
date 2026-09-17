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

    public JteEmitter() {
        this(List.of());
    }

    public JteEmitter(List<ComponentDecl> allComponents) {
        this.componentsByName = allComponents.stream()
            .collect(Collectors.toMap(ComponentDecl::name, Function.identity()));
    }

    public String emit(ComponentDecl component) {
        java.util.Set<String> slotNames = slotNamesOf(component);
        java.util.Set<String> renderPropSlotNames = renderPropSlotNames(component, slotNames);

        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param, slotNames, renderPropSlotNames)).append('\n');
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
        java.util.Set<String> renderPropSlotNames = renderPropSlotNames(component, slotNames);
        StringBuilder probe = new StringBuilder();
        for (Param param : component.params()) {
            probe.append("@param ").append(jteParamDeclaration(param, slotNames, renderPropSlotNames)).append('\n');
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

    private java.util.Set<String> renderPropSlotNames(ComponentDecl component, java.util.Set<String> slotNames) {
        java.util.Set<String> renderProp = new java.util.HashSet<>();
        for (Statement statement : component.body()) {
            collectRenderPropSlots(statement, slotNames, renderProp);
        }
        return renderProp;
    }

    private void collectRenderPropSlots(Statement statement, java.util.Set<String> slotNames,
            java.util.Set<String> renderProp) {
        switch (statement) {
            case Statement.HtmlElement element -> {
                for (Statement.Attribute attribute : element.attributes()) {
                    collectRenderPropSlots(attribute.value(), slotNames, renderProp);
                }
                for (Statement child : element.children()) {
                    collectRenderPropSlots(child, slotNames, renderProp);
                }
            }
            case Statement.TextRun ignored -> {
            }
            case Statement.VarDecl varDecl -> {
            }
            case Statement.Interpolation interpolation ->
                collectRenderPropSlots(interpolation.expr(), slotNames, renderProp);
            case Statement.IfStmt ifStmt -> {
                collectRenderPropSlots(ifStmt.condition(), slotNames, renderProp);
                for (Statement s : ifStmt.thenBranch()) {
                    collectRenderPropSlots(s, slotNames, renderProp);
                }
                for (Statement s : ifStmt.elseBranch()) {
                    collectRenderPropSlots(s, slotNames, renderProp);
                }
            }
            case Statement.ForStmt forStmt -> {
                collectRenderPropSlots(forStmt.iterable(), slotNames, renderProp);
                for (Statement s : forStmt.body()) {
                    collectRenderPropSlots(s, slotNames, renderProp);
                }
            }
            case Statement.SwitchStmt switchStmt -> {
                collectRenderPropSlots(switchStmt.subject(), slotNames, renderProp);
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    collectRenderPropSlots(switchCase.matchValue(), slotNames, renderProp);
                    for (Statement s : switchCase.body()) {
                        collectRenderPropSlots(s, slotNames, renderProp);
                    }
                }
                for (Statement s : switchStmt.defaultCase()) {
                    collectRenderPropSlots(s, slotNames, renderProp);
                }
            }
            case Statement.ComponentCallStmt call -> {
                for (Statement.Arg arg : call.args()) {
                    collectRenderPropSlots(arg.value(), slotNames, renderProp);
                }
                for (Statement.SlotFill fill : call.slotFills()) {
                    for (Statement s : fill.body()) {
                        collectRenderPropSlots(s, slotNames, renderProp);
                    }
                }
            }
        }
    }

    private void collectRenderPropSlots(Expr expr, java.util.Set<String> slotNames,
            java.util.Set<String> renderProp) {
        switch (expr) {
            case Expr.CallExpr call -> {
                if (call.callee() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text())) {
                    renderProp.add(p.text());
                }
                collectRenderPropSlots(call.callee(), slotNames, renderProp);
                for (Expr arg : call.args()) {
                    collectRenderPropSlots(arg, slotNames, renderProp);
                }
            }
            case Expr.PrimaryExpr ignored -> {
            }
            case Expr.StringLiteralExpr ignored -> {
            }
            case Expr.AccessExpr access -> collectRenderPropSlots(access.target(), slotNames, renderProp);
            case Expr.NotExpr not -> collectRenderPropSlots(not.operand(), slotNames, renderProp);
            case Expr.UnaryMinusExpr unaryMinus -> collectRenderPropSlots(unaryMinus.operand(), slotNames, renderProp);
            case Expr.BinaryExpr binary -> {
                collectRenderPropSlots(binary.left(), slotNames, renderProp);
                collectRenderPropSlots(binary.right(), slotNames, renderProp);
            }
            case Expr.TernaryExpr ternary -> {
                collectRenderPropSlots(ternary.condition(), slotNames, renderProp);
                collectRenderPropSlots(ternary.whenTrue(), slotNames, renderProp);
                collectRenderPropSlots(ternary.whenFalse(), slotNames, renderProp);
            }
            case Expr.ParenExpr paren -> collectRenderPropSlots(paren.inner(), slotNames, renderProp);
            case Expr.SafeAccessExpr safeAccess -> collectRenderPropSlots(safeAccess.target(), slotNames, renderProp);
            case Expr.ElvisExpr elvis -> {
                collectRenderPropSlots(elvis.left(), slotNames, renderProp);
                collectRenderPropSlots(elvis.right(), slotNames, renderProp);
            }
        }
    }

    private String jteParamDeclaration(Param param, java.util.Set<String> slotNames,
            java.util.Set<String> renderPropSlotNames) {
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
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE && renderPropSlotNames.contains(p.name()) ->
                "java.util.function.Function<" + javaType(p.elementType()) + ", gg.jte.Content> " + p.name()
                    + (p.defaultValue().isPresent() ? " = (java.util.function.Function<" + javaType(p.elementType())
                        + ", gg.jte.Content>) (" + javaType(p.elementType()) + " it) -> null" : "");
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
            case Param.SlotParam p when renderPropSlotNames.contains(p.name()) ->
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
            case Statement.Interpolation interpolation ->
                out.append("${").append(emitExpr(interpolation.expr(), slotNames)).append('}');
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
     * quando o componente-alvo não é conhecido nesta emissão. */
    private Boolean resolveSlotIsRenderProp(String componentName, String paramName) {
        ComponentDecl target = componentsByName.get(componentName);
        if (target == null) {
            return null;
        }
        java.util.Set<String> targetSlotNames = slotNamesOf(target);
        return renderPropSlotNames(target, targetSlotNames).contains(paramName);
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
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral);
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

    private String emitStringLiteral(Expr.StringLiteralExpr stringLiteral) {
        StringBuilder sb = new StringBuilder("\"");
        for (Expr.StringPart part : stringLiteral.parts()) {
            if (part instanceof Expr.StringPart.Literal literal) {
                sb.append(literal.javaEscapedText());
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
