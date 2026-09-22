package io.suko.lang.ast;

import java.util.List;

public sealed interface Statement permits Statement.HtmlElement, Statement.TextRun, Statement.Interpolation,
        Statement.VarDecl, Statement.IfStmt, Statement.ForStmt, Statement.SwitchStmt, Statement.ComponentCallStmt {

    SourceSpan span();

    record HtmlElement(String tagName, List<Attribute> attributes, List<Statement> children,
                        boolean selfClosing, SourceSpan span) implements Statement {
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

    record ComponentCallStmt(String componentName, List<Arg> args, List<SlotFill> slotFills,
                             SourceSpan span) implements Statement {
    }

    record Arg(java.util.Optional<String> name, Expr value) {
    }

    /** paramName é o nome do slot; lambdaParamName só é usado por slots render-prop (tarefa 18). */
    record SlotFill(String paramName, java.util.Optional<String> lambdaParamName,
                    List<Statement> body) {
    }
}
