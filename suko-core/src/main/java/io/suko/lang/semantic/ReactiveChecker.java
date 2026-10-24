package io.suko.lang.semantic;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;
import io.suko.lang.project.CallResolver;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Regras de linguagem da 13b que não dependem do alvo: REACTIVE_REQUIRED, REACTIVE_UNUSED (HINT),
 * DERIVED_CYCLE, BIND_TARGET (o alvo de bind: tem de ser um state simples), LAMBDA_ACROSS_BOUNDARY e o
 * INVALID_DOCTYPE de um reactive component. O subconjunto do cliente, os sinks e as props são do alvo
 * (suko-jte), porque o cliente é JS (R9 do plano 1 da 13b).
 */
final class ReactiveChecker {

    private final DiagnosticCollector diagnostics;
    private final String sourceFile;
    private final CallResolver resolver;

    ReactiveChecker(DiagnosticCollector diagnostics, String sourceFile, CallResolver resolver) {
        this.diagnostics = diagnostics;
        this.sourceFile = sourceFile;
        this.resolver = resolver;
    }

    void check(SukoFile file) {
        for (ComponentDecl component : file.components()) {
            check(component);
        }
    }

    private void check(ComponentDecl component) {
        Set<String> states = new HashSet<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.StateDecl d) {
                states.add(d.name());
            }
        }
        boolean[] used = {false};
        walk(component.body(), component, states, used);
        if (!component.reactive()) {
            return;
        }
        if (!component.body().isEmpty() && component.body().get(0) instanceof Statement.TextRun t
                && t.text().equals("<!DOCTYPE html>")) {
            report(Severity.ERROR, "INVALID_DOCTYPE", "um reactive component é sempre um fragmento dentro de uma página:"
                + " o <!DOCTYPE html> pertence ao layout (um component simples)", t.span());
        }
        if (!used[0]) {
            report(Severity.HINT, "REACTIVE_UNUSED", "'" + component.name() + "' não usa state, derived, on:, bind:"
                + " nem lambdas: pode ser um component simples", component.nameSpan());
        }
        duplicateAndReservedNames(component);
        derivedCycles(component);
    }

    /** `state` e `derived` partilham `$s.nome` no cliente e os parâmetros `$p.nome`: um nome repetido geraria JS errado. */
    private void duplicateAndReservedNames(ComponentDecl component) {
        Set<String> seen = new HashSet<>();
        for (Param p : component.params()) {
            seen.add(p.name());
        }
        for (Statement s : component.body()) {
            String name;
            SourceSpan span;
            if (s instanceof Statement.StateDecl d) {
                name = d.name();
                span = d.nameSpan();
            } else if (s instanceof Statement.DerivedDecl d) {
                name = d.name();
                span = d.nameSpan();
            } else {
                continue;
            }
            if (PROTOTYPE_NAMES.contains(name)) {
                report(Severity.ERROR, "RESERVED_NAME", "'" + name + "' não pode ser o nome de um state/derived"
                    + " (colide com o protótipo de objetos do JavaScript)", span);
            }
            if (!seen.add(name)) {
                report(Severity.ERROR, "DUPLICATE_NAME", "o nome '" + name + "' já é usado por um parâmetro, state ou"
                    + " derived deste componente", span);
            }
        }
    }

    private static final Set<String> PROTOTYPE_NAMES = Set.of("__proto__", "constructor", "prototype");

    private void walk(List<Statement> statements, ComponentDecl component, Set<String> states, boolean[] used) {
        for (Statement s : statements) {
            switch (s) {
                case Statement.StateDecl d -> {
                    used[0] = true;
                    required(component, "state", d.nameSpan());
                }
                case Statement.DerivedDecl d -> {
                    used[0] = true;
                    required(component, "derived", d.nameSpan());
                }
                case Statement.HtmlElement e -> {
                    for (Statement.Directive d : e.directives()) {
                        used[0] = true;
                        boolean on = d.kind() == Statement.Directive.Kind.ON;
                        if (!component.reactive()) {
                            required(component, (on ? "on:" : "bind:") + d.name(), d.nameSpan());
                        } else if (!on) {
                            bindTarget(d, states);
                        }
                    }
                    walk(e.children(), component, states, used);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.Arg arg : call.args()) {
                        if (arg.value() instanceof Expr.LambdaExpr lambda) {
                            used[0] = true;
                            lambdaArgument(component, call, lambda);
                        }
                    }
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), component, states, used);
                    }
                }
                case Statement.IfStmt i -> {
                    walk(i.thenBranch(), component, states, used);
                    walk(i.elseBranch(), component, states, used);
                }
                case Statement.ForStmt f -> walk(f.body(), component, states, used);
                case Statement.SwitchStmt sw -> {
                    for (Statement.SwitchCase c : sw.cases()) {
                        walk(c.body(), component, states, used);
                    }
                    walk(sw.defaultCase(), component, states, used);
                }
                case Statement.VarDecl v -> { }
                case Statement.Interpolation i -> { }
                case Statement.TextRun t -> { }
            }
        }
    }

    private void required(ComponentDecl component, String what, SourceSpan span) {
        if (!component.reactive()) {
            report(Severity.ERROR, "REACTIVE_REQUIRED", "'" + what + "' só existe num reactive component:"
                + " escreva 'reactive component " + component.name() + "(...)'", span);
        }
    }

    private void bindTarget(Statement.Directive d, Set<String> states) {
        if (d.value() instanceof Expr.PrimaryExpr p && states.contains(p.text())) {
            return;
        }
        report(Severity.ERROR, "BIND_TARGET", "bind:" + d.name() + " precisa de um state simples deste componente"
            + " (não um derived, um parâmetro nem a.b)", d.span());
    }

    /** R19: lambdas só passam entre componentes reativos. */
    private void lambdaArgument(ComponentDecl component, Statement.ComponentCallStmt call, Expr.LambdaExpr lambda) {
        Optional<Boolean> calleeReactive = switch (resolver.resolve(call.componentName())) {
            case CallResolver.Resolution.Local local -> Optional.of(local.decl().reactive());
            case CallResolver.Resolution.Project project -> Optional.of(project.entry().reactive());
            case CallResolver.Resolution.NotVisible notVisible -> Optional.empty();
            case CallResolver.Resolution.NotFound notFound -> Optional.empty();
        };
        if (calleeReactive.isEmpty()) {
            return; // COMPONENT_NOT_FOUND / COMPONENT_NOT_VISIBLE já foram reportados
        }
        boolean target = calleeReactive.get();
        if (component.reactive() && target) {
            return;
        }
        if (component.reactive()) {
            report(Severity.ERROR, "LAMBDA_ACROSS_BOUNDARY", "'" + call.componentName() + "' não é reativo (renderiza"
                + " no servidor): uma lambda só pode ser passada a um reactive component", lambda.span());
        } else if (target) {
            report(Severity.ERROR, "LAMBDA_ACROSS_BOUNDARY", "uma lambda não atravessa a fronteira servidor → cliente:"
                + " '" + component.name() + "' renderiza no servidor e '" + call.componentName() + "' corre no cliente",
                lambda.span());
        } else {
            report(Severity.ERROR, "REACTIVE_REQUIRED", "lambdas só existem em componentes reativos: escreva"
                + " 'reactive component " + component.name() + "(...)'", lambda.span());
        }
    }

    private void derivedCycles(ComponentDecl component) {
        Map<String, Statement.DerivedDecl> derived = new LinkedHashMap<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.DerivedDecl d) {
                derived.putIfAbsent(d.name(), d);
            }
        }
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Statement.DerivedDecl d : derived.values()) {
            List<String> deps = new ArrayList<>();
            for (String name : ExprNames.of(d.value())) {
                if (derived.containsKey(name)) {
                    deps.add(name);
                }
            }
            edges.put(d.name(), deps);
        }
        Set<String> reported = new HashSet<>();
        for (String start : derived.keySet()) {
            if (reported.contains(start)) {
                continue;
            }
            List<String> cycle = new ArrayList<>(List.of(start));
            if (!backTo(start, start, edges, cycle, new HashSet<>())) {
                continue;
            }
            reported.addAll(cycle);
            report(Severity.ERROR, "DERIVED_CYCLE", "ciclo entre derived: " + String.join(" → ", cycle) + " → " + start
                + " (um derived não pode depender de si próprio)", derived.get(start).nameSpan());
        }
    }

    /** Procura um caminho de {@code node} de volta a {@code start}; {@code path} fica com o ciclo. */
    private static boolean backTo(String node, String start, Map<String, List<String>> edges, List<String> path,
                                  Set<String> seen) {
        for (String next : edges.getOrDefault(node, List.of())) {
            if (next.equals(start)) {
                return true;
            }
            if (seen.add(next)) {
                path.add(next);
                if (backTo(next, start, edges, path, seen)) {
                    return true;
                }
                path.remove(path.size() - 1);
            }
        }
        return false;
    }

    private void report(Severity severity, String code, String message, SourceSpan span) {
        diagnostics.add(new SukoDiagnostic(severity, message, code, sourceFile, span));
    }
}
