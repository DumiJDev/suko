package io.suko.lang.ast;

import java.util.List;

public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation,
        Statement.VarDecl, Statement.IfStmt, Statement.ForStmt, Statement.SwitchStmt, Statement.ComponentCallStmt,
        Statement.StateDecl, Statement.DerivedDecl {

    SourceSpan span();

    /** {@code directives} (13b): os {@code on:}/{@code bind:} do elemento, que nunca são atributos HTML. */
    record HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                        boolean selfClosing, SourceSpan span, List<Directive> directives) implements Statement {
        /** Sem diretivas (antes da 13b e AST construído à mão). */
        public HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                           boolean selfClosing, SourceSpan span) {
            this(tagName, attributes, children, selfClosing, span, List.of());
        }
    }

    /** `legacyBraceForm` = o valor sem aspas foi escrito `attr={expr}`,
     * removido pelo subprojeto 9 (D4). Mesma mecânica de Interpolation. */
    record Attribute(String name, Expr value, boolean legacyBraceForm, SourceSpan span) {
    }

    record TextRun(String text, SourceSpan span) implements Statement {
    }

    /** `legacyBraceForm` = a interpolação foi escrita com a chaveta nua
     * `{expr}`, removida pelo subprojeto 9 (D1). Marcada no AST, e não
     * rejeitada no parser, porque a gramática tem de continuar a aceitá-la
     * para não haver recuperação silenciosa nos caminhos de parse sem
     * error listener (ProjectIndex, RegistryGenerator) — quem a rejeita é
     * o SemanticChecker. */
    record Interpolation(Expr expr, boolean legacyBraceForm, SourceSpan span) implements Statement {
    }

    record VarDecl(String name, Expr value, SourceSpan span) implements Statement {
    }

    record IfStmt(Expr condition, List<Statement> thenBranch, List<Statement> elseBranch,
                  SourceSpan span) implements Statement {
    }

    record ForStmt(Type itemType, String itemName, Expr iterable, List<Statement> body,
                   SourceSpan span) implements Statement {
    }

    // `defaultCase` vazio = `default` ausente; a checagem de exaustividade é
    // semântica, fora deste subprojeto.
    record SwitchStmt(Expr subject, List<SwitchCase> cases, List<Statement> defaultCase,
                      SourceSpan span) implements Statement {
    }

    record SwitchCase(Expr matchValue, List<Statement> body) {
    }

    /** `nameSpan` cobre só o nome (possivelmente qualificado) do componente. */
    record ComponentCallStmt(String componentName, List<Arg> args, List<SlotFill> slotFills,
                             SourceSpan span, SourceSpan nameSpan) implements Statement {
        /** Sem posição própria do nome (AST construído à mão): usa o span da chamada. */
        public ComponentCallStmt(String componentName, List<Arg> args, List<SlotFill> slotFills,
                                 SourceSpan span) {
            this(componentName, args, slotFills, span, span);
        }
    }

    /** `span` cobre o nome do argumento quando é nomeado (`title = ...`), senão o valor. */
    record Arg(java.util.Optional<String> name, Expr value, SourceSpan span) {
        public Arg(java.util.Optional<String> name, Expr value) {
            this(name, value, SourceSpan.NONE);
        }
    }

    /** paramName é o nome do slot; lambdaParamName só é usado por slots render-prop (tarefa 18). */
    record SlotFill(String paramName, java.util.Optional<String> lambdaParamName,
                    List<Statement> body, SourceSpan nameSpan) {
        /** `children` implícito e AST construído à mão: sem posição do nome. */
        public SlotFill(String paramName, java.util.Optional<String> lambdaParamName,
                        List<Statement> body) {
            this(paramName, lambdaParamName, body, SourceSpan.NONE);
        }
    }

    /** {@code on:evento=${lambda}} ({@link Kind#ON}; {@code value} é um {@link Expr.LambdaExpr}) ou
     * {@code bind:nome=${state}} ({@link Kind#BIND}). Sintaxe Suko, não HTML (13b, API v2 (d)): fica fora de
     * {@link HtmlElement#attributes()}, por isso nenhum checker de atributos a confunde com um {@code on*} e
     * nenhum alvo a escreve tal como está. {@code name} é o que vem depois de {@code :} ({@code click},
     * {@code value}); {@code nameSpan} cobre {@code on:click}.
     */
    record Directive(Kind kind, String name, Expr value, SourceSpan span, SourceSpan nameSpan) {
        public enum Kind { ON, BIND }
    }

    /** {@code state T nome = valor;} ou {@code state var nome = valor;} ({@code type} vazio = {@code var}). 13b. */
    record StateDecl(java.util.Optional<Type> type, String name, Expr value, SourceSpan span,
                     SourceSpan nameSpan) implements Statement {
    }

    /** {@code derived T nome = valor;}: recalculado quando um state/derived de que depende muda; não atribuível. 13b. */
    record DerivedDecl(java.util.Optional<Type> type, String name, Expr value, SourceSpan span,
                       SourceSpan nameSpan) implements Statement {
    }
}
