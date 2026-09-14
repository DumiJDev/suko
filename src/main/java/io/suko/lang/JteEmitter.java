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
        StringBuilder out = new StringBuilder();
        for (Param param : component.params()) {
            out.append("@param ").append(jteParamDeclaration(param)).append('\n');
        }
        out.append('\n');
        for (Statement statement : component.body()) {
            emitStatement(statement, out);
        }
        return out.toString();
    }

    private String jteParamDeclaration(Param param) {
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
            case Param.ValueParam p -> javaType(p.type()) + " " + p.name();
            case Param.SlotParam p when p.cardinality() == Cardinality.ONE ->
                "gg.jte.Content " + p.name() + (p.defaultValue().isPresent() ? " = null" : "");
            case Param.SlotParam p ->
                "java.util.List<gg.jte.Content> " + p.name() + (p.defaultValue().isPresent() ? " = java.util.List.of()" : "");
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
        String baseName = "Content".equals(type.name()) && type.typeArguments().isEmpty()
            ? "gg.jte.Content"
            : type.name();
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

    private void emitStatement(Statement statement, StringBuilder out) {
        switch (statement) {
            case Statement.TextRun textRun -> out.append(textRun.text());
            case Statement.Interpolation interpolation ->
                out.append("${").append(emitExpr(interpolation.expr())).append('}');
            case Statement.HtmlElement element -> emitHtmlElement(element, out);
            case Statement.IfStmt ifStmt -> emitIfStmt(ifStmt, out);
            case Statement.ForStmt forStmt -> emitForStmt(forStmt, out);
            case Statement.SwitchStmt switchStmt -> emitSwitchStmt(switchStmt, out);
            case Statement.ComponentCallStmt call -> emitComponentCall(call, out);
        }
    }

    private void emitComponentCall(Statement.ComponentCallStmt call, StringBuilder out) {
        out.append("@template.").append(call.componentName()).append('(');
        boolean first = true;
        for (Statement.Arg arg : call.args()) {
            if (!first) out.append(", ");
            arg.name().ifPresent(name -> out.append(name).append(" = "));
            out.append(emitExpr(arg.value()));
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
            if (!wrapAsList) {
                out.append(entry.getKey()).append(" = ");
                emitSlotFillContent(fills.get(0), out);
            } else {
                out.append(entry.getKey()).append(" = java.util.List.of(");
                for (int i = 0; i < fills.size(); i++) {
                    if (i > 0) out.append(", ");
                    emitSlotFillContent(fills.get(i), out);
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

    private void emitSlotFillContent(Statement.SlotFill slotFill, StringBuilder out) {
        out.append("@`");
        for (Statement statement : slotFill.body()) {
            emitStatement(statement, out);
        }
        out.append('`');
    }

    private void emitSwitchStmt(Statement.SwitchStmt switchStmt, StringBuilder out) {
        String subject = emitExpr(switchStmt.subject());
        boolean first = true;
        for (Statement.SwitchCase switchCase : switchStmt.cases()) {
            out.append(first ? "@if(" : "@elseif(").append(subject).append(".equals(")
                .append(emitExpr(switchCase.matchValue())).append("))\n");
            for (Statement statement : switchCase.body()) {
                emitStatement(statement, out);
            }
            out.append('\n');
            first = false;
        }
        if (!switchStmt.defaultCase().isEmpty()) {
            out.append("@else\n");
            for (Statement statement : switchStmt.defaultCase()) {
                emitStatement(statement, out);
            }
            out.append('\n');
        }
        out.append("@endif\n");
    }

    private void emitForStmt(Statement.ForStmt forStmt, StringBuilder out) {
        out.append("@for(").append(javaType(forStmt.itemType())).append(' ').append(forStmt.itemName())
            .append(" : ").append(emitExpr(forStmt.iterable())).append(")\n");
        for (Statement statement : forStmt.body()) {
            emitStatement(statement, out);
        }
        out.append("\n@endfor\n");
    }

    private void emitIfStmt(Statement.IfStmt ifStmt, StringBuilder out) {
        out.append("@if(").append(emitExpr(ifStmt.condition())).append(")\n");
        for (Statement statement : ifStmt.thenBranch()) {
            emitStatement(statement, out);
        }
        if (!ifStmt.elseBranch().isEmpty()) {
            out.append("\n@else\n");
            for (Statement statement : ifStmt.elseBranch()) {
                emitStatement(statement, out);
            }
        }
        out.append("\n@endif\n");
    }

    private void emitHtmlElement(Statement.HtmlElement element, StringBuilder out) {
        out.append('<').append(element.tagName());
        for (Statement.Attribute attribute : element.attributes()) {
            out.append(' ').append(attribute.name()).append("=\"")
                .append("${").append(emitExpr(attribute.value())).append('}').append('"');
        }
        if (element.selfClosing()) {
            out.append("/>");
            return;
        }
        out.append('>');
        for (Statement child : element.children()) {
            emitStatement(child, out);
        }
        out.append("</").append(element.tagName()).append('>');
    }

    String emitExpr(Expr expr) {
        return switch (expr) {
            case Expr.PrimaryExpr primary -> primary.text();
            case Expr.StringLiteralExpr stringLiteral -> emitStringLiteral(stringLiteral);
            case Expr.AccessExpr access -> emitExpr(access.target()) + "." + access.memberName();
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
                String target = emitExpr(safeAccess.target());
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName()
                    + "(" + emitArgs(call.args()) + "))";
            }
            case Expr.CallExpr call -> emitExpr(call.callee()) + "(" + emitArgs(call.args()) + ")";
            case Expr.NotExpr not -> "!" + emitExpr(not.operand());
            case Expr.UnaryMinusExpr unaryMinus -> "-" + emitExpr(unaryMinus.operand());
            case Expr.BinaryExpr binary ->
                emitExpr(binary.left()) + " " + binary.operator() + " " + emitExpr(binary.right());
            case Expr.TernaryExpr ternary -> emitExpr(ternary.condition()) + " ? "
                + emitExpr(ternary.whenTrue()) + " : " + emitExpr(ternary.whenFalse());
            case Expr.ParenExpr paren -> "(" + emitExpr(paren.inner()) + ")";
            case Expr.SafeAccessExpr safeAccess -> {
                String target = emitExpr(safeAccess.target());
                yield "(" + target + " == null ? null : " + target + "." + safeAccess.memberName() + ")";
            }
            case Expr.ElvisExpr elvis -> {
                String left = emitExpr(elvis.left());
                yield "(" + left + " == null ? " + emitExpr(elvis.right()) + " : " + left + ")";
            }
        };
    }

    private String emitArgs(java.util.List<Expr> args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(emitExpr(args.get(i)));
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
