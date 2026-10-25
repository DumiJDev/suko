package io.suko.jte.client;

import io.suko.jte.HtmlSecurityRules;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.ExprNames;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.Type;
import io.suko.lang.project.ParamInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * O que corre no cliente num reactive component (13b): os tipos de state/derived, as regiões criadas no cliente
 * (os corpos de if/for/switch que dependem de state), os atributos e textos atualizados no cliente e cada
 * expressão que o JS vai avaliar ({@link Site}), já traduzida. Os parâmetros são constantes vindas de
 * {@code data-suko-props} (vista de raiz de ilha, R18). Usado pelo {@code ReactiveHtmlChecker}, pelo
 * {@code HtmlSecurityChecker} (Q6) e, no plano 2, pelo emissor das ilhas.
 */
public final class ClientModel {

    /** Uma expressão que o JS avalia, com o âmbito em que é avaliada e a tradução. */
    public record Site(Kind kind, Expr expr, ClientScope scope, Statement.HtmlElement element, Object node,
                       JsTranslator.Result result) {
        public enum Kind {
            /** Inicializador de um state. */ STATE_INIT,
            /** Valor de um derived. */ DERIVED,
            /** {@code var} que depende de state (ou dentro de uma região do cliente). */ VAR,
            /** Interpolação num nó de texto. */ TEXT,
            /** {@code ${slot}} numa região do cliente (o plano 2 clona o {@code <template>}; sem tradução). */ SLOT,
            /** Valor de um atributo atualizado no cliente. */ ATTRIBUTE,
            /** Valor de uma declaração {@code propriedade: ${v}} de um {@code style} atualizado no cliente (Q5). */ STYLE_VALUE,
            /** Condição de um if que passa para o cliente. */ CONDITION,
            /** Lista de um for que passa para o cliente. */ ITERABLE,
            /** Sujeito de um switch que passa para o cliente. */ SUBJECT,
            /** Valor de um case desse switch. */ CASE,
            /** Lambda de {@code on:evento}. */ HANDLER,
            /** Alvo de {@code bind:} (as regras são do BIND_TARGET; a tradução não gera problemas). */ BIND,
            /** Argumento (não lambda) para um componente reativo filho. */ CHILD_ARG,
            /** Lambda passada a um componente reativo filho. */ CALLBACK
        }
    }

    /** Um atributo atualizado no cliente e o seu elemento. */
    public record ClientAttribute(Statement.HtmlElement element, Statement.Attribute attribute) {
    }

    private static final String UNSUPPORTED = JsTranslator.CLIENT_UNSUPPORTED;
    private static final ClientType LATER = new ClientType.Unsupported("?",
        "um state só pode usar parâmetros e states declarados antes dele");

    private final ComponentDecl component;
    private final Callees callees;
    private final JavaTypes types;
    private final Map<String, ClientType> paramTypes = new LinkedHashMap<>();
    private final Map<String, Param.ValueParam> params = new LinkedHashMap<>();
    private final Map<String, ClientType> stateTypes = new LinkedHashMap<>();
    private final Map<String, ClientType> derivedTypes = new LinkedHashMap<>();
    private final Set<String> slotNames = new HashSet<>();
    private final List<Site> sites = new ArrayList<>();
    private final List<ClientProblem> problems = new ArrayList<>();
    private final Set<Statement> clientCreated = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Statement> clientBlocks = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Statement.Attribute> clientAttributeSet = Collections.newSetFromMap(new IdentityHashMap<>());
    private final List<Statement.HtmlElement> clientElements = new ArrayList<>();
    private final List<ClientAttribute> clientAttributes = new ArrayList<>();

    private ClientModel(ComponentDecl component, Callees callees, JavaTypes types) {
        this.component = component;
        this.callees = callees;
        this.types = types;
    }

    public static ClientModel of(ComponentDecl component, Callees callees, JavaTypes types) {
        ClientModel model = new ClientModel(component, callees, types);
        model.build();
        return model;
    }

    public ComponentDecl component() {
        return component;
    }

    public List<Site> sites() {
        return Collections.unmodifiableList(sites);
    }

    /** Problemas a reportar (CLIENT_UNSUPPORTED, TYPE_NOT_INFERRED, RESERVED_NAME), sem os silenciosos. */
    public List<ClientProblem> problems() {
        return Collections.unmodifiableList(problems);
    }

    /** Tipos declarados dos parâmetros de valor (sem os slots), mesmo os que ficam fora do subconjunto. */
    public Map<String, ClientType> paramTypes() {
        return Collections.unmodifiableMap(paramTypes);
    }

    public Optional<Param.ValueParam> param(String name) {
        return Optional.ofNullable(params.get(name));
    }

    public Map<String, ClientType> stateTypes() {
        return Collections.unmodifiableMap(stateTypes);
    }

    public Map<String, ClientType> derivedTypes() {
        return Collections.unmodifiableMap(derivedTypes);
    }

    public boolean isClientCreated(Statement s) {
        return clientCreated.contains(s);
    }

    /** if/for/switch cuja avaliação passa para o cliente (a raiz de uma região criada no cliente). */
    public boolean isClientBlock(Statement s) {
        return clientBlocks.contains(s);
    }

    public boolean isClientAttribute(Statement.Attribute a) {
        return clientAttributeSet.contains(a);
    }

    /** Elementos criados no cliente, pela ordem do fonte. */
    public List<Statement.HtmlElement> clientElements() {
        return Collections.unmodifiableList(clientElements);
    }

    /** Atributos atualizados no cliente (de elementos criados no cliente ou do servidor), pela ordem do fonte. */
    public List<ClientAttribute> clientAttributes() {
        return Collections.unmodifiableList(clientAttributes);
    }

    /** Os parâmetros que o código do cliente lê: só esses vão em {@code data-suko-props} (minimização da spec). */
    public Set<String> clientReadParams() {
        Set<String> out = new LinkedHashSet<>();
        for (Site s : sites) {
            if (s.expr() == null || s.kind() == Site.Kind.BIND) {
                continue;
            }
            for (String name : ExprNames.of(s.expr())) {
                if (paramTypes.containsKey(name)
                        && s.scope().lookup(name).map(b -> b.kind() == ClientScope.Kind.PARAM).orElse(false)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ construção

    private void build() {
        ClientScope scope = ClientScope.EMPTY;
        for (Param p : component.params()) {
            switch (p) {
                case Param.ValueParam v -> {
                    ClientType t = ClientTypes.of(v.type(), types);
                    paramTypes.put(v.name(), t);
                    params.put(v.name(), v);
                    // Fora do subconjunto fica UNKNOWN (silencioso): quem explica é o PROP_NOT_SERIALIZABLE.
                    scope = scope.with(v.name(), ClientScope.Kind.PARAM,
                        t instanceof ClientType.Unsupported ? ClientType.Special.UNKNOWN : t);
                }
                case Param.SlotParam s -> {
                    slotNames.add(s.name());
                    scope = scope.with(s.name(), ClientScope.Kind.PARAM,
                        new ClientType.Unsupported("Component", "um slot só se insere no cliente com ${" + s.name() + "}"));
                }
            }
        }

        List<Statement.StateDecl> states = new ArrayList<>();
        Map<String, Statement.DerivedDecl> derived = new LinkedHashMap<>();
        for (Statement s : component.body()) {
            if (s instanceof Statement.StateDecl d) {
                states.add(d);
            } else if (s instanceof Statement.DerivedDecl d) {
                derived.putIfAbsent(d.name(), d);
            }
        }

        // R24: um state vê os parâmetros e os states anteriores.
        ClientScope stateScope = scope;
        for (int i = 0; i < states.size(); i++) {
            ClientScope visible = stateScope;
            for (int j = i; j < states.size(); j++) {
                visible = visible.with(states.get(j).name(), ClientScope.Kind.STATE, LATER);
            }
            for (String name : derived.keySet()) {
                visible = visible.with(name, ClientScope.Kind.DERIVED, LATER);
            }
            Statement.StateDecl d = states.get(i);
            ClientType t = declare(d.type(), d.value(), d.name(), d.nameSpan(), visible, Site.Kind.STATE_INIT, d);
            stateTypes.put(d.name(), t);
            stateScope = stateScope.with(d.name(), ClientScope.Kind.STATE, t);
        }

        // R24: um derived vê tudo; ordem topológica; os de um ciclo ficam UNKNOWN (DERIVED_CYCLE é do core).
        ClientScope full = stateScope;
        for (String name : derived.keySet()) {
            full = full.with(name, ClientScope.Kind.DERIVED, ClientType.Special.UNKNOWN);
        }
        for (String name : topological(derived)) {
            Statement.DerivedDecl d = derived.get(name);
            ClientType t = declare(d.type(), d.value(), d.name(), d.nameSpan(), full, Site.Kind.DERIVED, d);
            derivedTypes.put(name, t);
            full = full.with(name, ClientScope.Kind.DERIVED, t);
        }
        for (String name : derived.keySet()) {
            derivedTypes.putIfAbsent(name, ClientType.Special.UNKNOWN);
        }

        Set<String> clientNames = new HashSet<>(stateTypes.keySet());
        clientNames.addAll(derived.keySet());
        walk(component.body(), full, false, clientNames);
    }

    private static List<String> topological(Map<String, Statement.DerivedDecl> derived) {
        Map<String, Set<String>> deps = new LinkedHashMap<>();
        for (Map.Entry<String, Statement.DerivedDecl> e : derived.entrySet()) {
            Set<String> d = new LinkedHashSet<>(ExprNames.of(e.getValue().value()));
            d.retainAll(derived.keySet());
            deps.put(e.getKey(), d);
        }
        List<String> order = new ArrayList<>();
        boolean progress = true;
        while (progress) {
            progress = false;
            for (Map.Entry<String, Set<String>> e : deps.entrySet()) {
                if (!order.contains(e.getKey()) && order.containsAll(e.getValue())) {
                    order.add(e.getKey());
                    progress = true;
                }
            }
        }
        return order;
    }

    private ClientType declare(Optional<Type> declared, Expr value, String name, SourceSpan nameSpan,
                               ClientScope scope, Site.Kind kind, Statement node) {
        JsTranslator.Result r = translator(scope).expression(value);
        site(kind, value, scope, null, node, r);
        if (declared.isPresent()) {
            ClientType t = ClientTypes.of(declared.get(), types);
            if (t instanceof ClientType.Unsupported u) {
                problem(UNSUPPORTED, "'" + name + "' (" + u.javaType() + "): " + u.reason(), nameSpan);
                return ClientType.Special.UNKNOWN;
            }
            if (r instanceof JsTranslator.Ok ok && !ClientTypes.assignable(ok.type(), t)) {
                problem(UNSUPPORTED, "o valor (" + ok.type().java() + ") não é atribuível a '" + name + "' (" + t.java() + ")",
                    value.span());
            }
            return t;
        }
        return r instanceof JsTranslator.Ok ok ? inferred(ok.type(), name, nameSpan) : ClientType.Special.UNKNOWN;
    }

    private ClientType inferred(ClientType t, String name, SourceSpan nameSpan) {
        if (t == ClientType.Special.NULL || t == ClientType.Special.VOID || t == ClientType.Special.EVENT) {
            problem("TYPE_NOT_INFERRED", "não é possível inferir o tipo de '" + name + "' a partir de " + t.java()
                + ": escreva o tipo (ex.: String " + name + " = ...)", nameSpan);
            return ClientType.Special.UNKNOWN;
        }
        return t;
    }

    private void walk(List<Statement> statements, ClientScope scope, boolean client, Set<String> clientNames) {
        for (Statement s : statements) {
            if (client) {
                clientCreated.add(s);
            }
            switch (s) {
                case Statement.StateDecl d -> { }
                case Statement.DerivedDecl d -> { }
                case Statement.TextRun t -> { }
                case Statement.VarDecl v -> {
                    if (client || depends(v.value(), clientNames)) {
                        JsTranslator.Result r = translator(scope).expression(v.value());
                        site(Site.Kind.VAR, v.value(), scope, null, v, r);
                        ClientType t = r instanceof JsTranslator.Ok ok ? inferred(ok.type(), v.name(), v.span())
                            : ClientType.Special.UNKNOWN;
                        scope = scope.with(v.name(), ClientScope.Kind.LOCAL, t);
                        clientNames = plus(clientNames, v.name());
                    } else {
                        scope = scope.with(v.name(), ClientScope.Kind.LOCAL, new ClientType.Unsupported("var",
                            "'" + v.name() + "' é uma variável do servidor; para a usar no cliente declare-a como derived"));
                    }
                }
                case Statement.Interpolation i -> {
                    if (client || depends(i.expr(), clientNames)) {
                        if (i.expr() instanceof Expr.PrimaryExpr p && slotNames.contains(p.text())) {
                            site(Site.Kind.SLOT, i.expr(), scope, null, i, null);
                        } else {
                            site(Site.Kind.TEXT, i.expr(), scope, null, i, translator(scope).text(i.expr()));
                        }
                    }
                }
                case Statement.HtmlElement e -> {
                    if (client) {
                        clientElements.add(e);
                    }
                    for (Statement.Attribute a : e.attributes()) {
                        attribute(e, a, scope, client, clientNames);
                    }
                    for (Statement.Directive d : e.directives()) {
                        directive(e, d, scope);
                    }
                    walk(e.children(), scope, client, clientNames);
                }
                case Statement.IfStmt i -> {
                    boolean moves = client || depends(i.condition(), clientNames);
                    if (moves) {
                        clientBlocks.add(i);
                        site(Site.Kind.CONDITION, i.condition(), scope, null, i,
                            expect(translator(scope).expression(i.condition()), ClientModel::isBoolean,
                                "uma condição tem de ser boolean", i.condition().span()));
                    }
                    walk(i.thenBranch(), scope, moves, clientNames);
                    walk(i.elseBranch(), scope, moves, clientNames);
                }
                case Statement.ForStmt f -> forLoop(f, scope, client, clientNames);
                case Statement.SwitchStmt sw -> {
                    boolean moves = client || depends(sw.subject(), clientNames);
                    if (moves) {
                        clientBlocks.add(sw);
                        JsTranslator.Result subject = translator(scope).expression(sw.subject());
                        site(Site.Kind.SUBJECT, sw.subject(), scope, null, sw, subject);
                        for (Statement.SwitchCase c : sw.cases()) {
                            site(Site.Kind.CASE, c.matchValue(), scope, null, c,
                                caseValue(subject, translator(scope).expression(c.matchValue()), c.matchValue().span()));
                        }
                    }
                    for (Statement.SwitchCase c : sw.cases()) {
                        walk(c.body(), scope, moves, clientNames);
                    }
                    walk(sw.defaultCase(), scope, moves, clientNames);
                }
                case Statement.ComponentCallStmt call -> call(call, scope, client, clientNames);
            }
        }
    }

    private void attribute(Statement.HtmlElement e, Statement.Attribute a, ClientScope scope, boolean client,
                           Set<String> clientNames) {
        if (!(client || depends(a.value(), clientNames))) {
            return;
        }
        clientAttributeSet.add(a);
        clientAttributes.add(new ClientAttribute(e, a));
        if (HtmlSecurityRules.lower(a.name()).equals("style")) {
            Optional<List<HtmlSecurityRules.StyleChunk>> chunks = HtmlSecurityRules.styleDeclarations(a.value());
            if (chunks.isPresent()) {
                for (HtmlSecurityRules.StyleChunk chunk : chunks.get()) {
                    if (chunk.interpolation() != null) {
                        site(Site.Kind.STYLE_VALUE, chunk.interpolation(), scope, e, a,
                            translator(scope).javaString(chunk.interpolation()));
                    }
                }
                return;
            }
        }
        site(Site.Kind.ATTRIBUTE, a.value(), scope, e, a, expect(translator(scope).expression(a.value()),
            ClientModel::isAttributeValue, "o valor de um atributo no cliente tem de ser texto, número ou boolean",
            a.value().span()));
    }

    private void directive(Statement.HtmlElement e, Statement.Directive d, ClientScope scope) {
        if (d.kind() == Statement.Directive.Kind.ON) {
            if (d.value() instanceof Expr.LambdaExpr lambda) {
                site(Site.Kind.HANDLER, lambda, scope, e, d, translator(scope).eventHandler(lambda));
            }
        } else {
            site(Site.Kind.BIND, d.value(), scope, e, d, translator(scope).expression(d.value()));
        }
    }

    private void forLoop(Statement.ForStmt f, ClientScope scope, boolean client, Set<String> clientNames) {
        boolean moves = client || depends(f.iterable(), clientNames) || needsLoopVariable(f.body(), f.itemName(), clientNames);
        if (!moves) {
            walk(f.body(), scope.with(f.itemName(), ClientScope.Kind.LOCAL, new ClientType.Unsupported("var",
                "'" + f.itemName() + "' é a variável de um for do servidor")), false, clientNames);
            return;
        }
        clientBlocks.add(f);
        JsTranslator.Result r = translator(scope).expression(f.iterable());
        ClientType item = ClientType.Special.UNKNOWN;
        if (r instanceof JsTranslator.Ok ok) {
            if (ok.type() instanceof ClientType.ListOf list) {
                if (f.itemType().name().equals("var")) {
                    item = list.element();
                } else {
                    ClientType declared = ClientTypes.of(f.itemType(), types);
                    if (ClientTypes.assignable(list.element(), declared)) {
                        item = declared;
                    } else {
                        r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "os elementos (" + list.element().java()
                            + ") não são atribuíveis a " + f.itemType().name(), f.iterable().span()));
                    }
                }
            } else {
                r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "um for no cliente percorre uma List (recebeu "
                    + ok.type().java() + ")", f.iterable().span()));
            }
        }
        site(Site.Kind.ITERABLE, f.iterable(), scope, null, f, r);
        walk(f.body(), scope.with(f.itemName(), ClientScope.Kind.LOCAL, item), true, plus(clientNames, f.itemName()));
    }

    /** R24: um for do servidor cujo corpo tem código do cliente que lê a variável do ciclo passa para o cliente. */
    private boolean needsLoopVariable(List<Statement> body, String var, Set<String> clientNames) {
        for (Statement s : body) {
            boolean hit = switch (s) {
                case Statement.HtmlElement e -> e.directives().stream().anyMatch(d -> ExprNames.of(d.value()).contains(var))
                    || e.attributes().stream().anyMatch(a -> uses(a.value(), var, clientNames))
                    || needsLoopVariable(e.children(), var, clientNames);
                case Statement.Interpolation i -> uses(i.expr(), var, clientNames);
                case Statement.IfStmt i -> uses(i.condition(), var, clientNames)
                    || needsLoopVariable(i.thenBranch(), var, clientNames) || needsLoopVariable(i.elseBranch(), var, clientNames);
                case Statement.ForStmt f -> uses(f.iterable(), var, clientNames) || needsLoopVariable(f.body(), var, clientNames);
                case Statement.SwitchStmt sw -> uses(sw.subject(), var, clientNames)
                    || sw.cases().stream().anyMatch(c -> needsLoopVariable(c.body(), var, clientNames))
                    || needsLoopVariable(sw.defaultCase(), var, clientNames);
                case Statement.ComponentCallStmt c -> c.args().stream().anyMatch(a -> a.value() instanceof Expr.LambdaExpr
                        ? ExprNames.of(a.value()).contains(var) : uses(a.value(), var, clientNames))
                    || c.slotFills().stream().anyMatch(fill -> needsLoopVariable(fill.body(), var, clientNames));
                case Statement.VarDecl v -> uses(v.value(), var, clientNames);
                case Statement.TextRun t -> false;
                case Statement.StateDecl d -> false;
                case Statement.DerivedDecl d -> false;
            };
            if (hit) {
                return true;
            }
        }
        return false;
    }

    /** A expressão corre no cliente (lê state) e lê a variável. */
    private static boolean uses(Expr e, String var, Set<String> clientNames) {
        Set<String> names = ExprNames.of(e);
        return names.contains(var) && names.stream().anyMatch(clientNames::contains);
    }

    private void call(Statement.ComponentCallStmt call, ClientScope scope, boolean client, Set<String> clientNames) {
        Optional<Callees.Callee> callee = callees.resolve(call.componentName());
        boolean dependentArgs = call.args().stream()
            .anyMatch(a -> !(a.value() instanceof Expr.LambdaExpr) && depends(a.value(), clientNames));
        boolean simple = callee.isPresent() && !callee.get().reactive();
        if (simple && (client || dependentArgs)) {
            problem(UNSUPPORTED, "'" + call.componentName() + "' não é reativo e não pode ser "
                + (client ? "criado" : "atualizado") + " no cliente (num bloco que depende de state só entram componentes reativos)",
                call.nameSpan().isNone() ? call.span() : call.nameSpan());
        }
        List<ParamInfo> calleeParams = callee.map(Callees.Callee::params).orElse(List.of());
        for (int i = 0; i < call.args().size(); i++) {
            Statement.Arg arg = call.args().get(i);
            Optional<ParamInfo> param = parameter(calleeParams, arg, i);
            if (arg.value() instanceof Expr.LambdaExpr lambda) {
                if (callee.isEmpty() || simple || param.isEmpty()) {
                    continue; // core: LAMBDA_ACROSS_BOUNDARY / COMPONENT_NOT_FOUND / PARAM_NOT_FOUND
                }
                ClientType type = ClientTypes.of(ClientTypes.parse(param.get().type()), types);
                JsTranslator.Result r = type instanceof ClientType.Callback cb
                    ? translator(scope).callback(lambda, cb.params())
                    : new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "o parâmetro '" + param.get().name() + "' ("
                        + param.get().type() + ") de " + call.componentName() + " não é Consumer<T> nem Runnable", lambda.span()));
                site(Site.Kind.CALLBACK, lambda, scope, null, arg, r);
            } else if (!simple && (client || depends(arg.value(), clientNames))) {
                JsTranslator.Result r = translator(scope).expression(arg.value());
                if (r instanceof JsTranslator.Ok ok && param.isPresent() && !param.get().slot()) {
                    ClientType type = ClientTypes.of(ClientTypes.parse(param.get().type()), types);
                    if (!(type instanceof ClientType.Unsupported) && !ClientTypes.assignable(ok.type(), type)) {
                        r = new JsTranslator.Err(new ClientProblem(UNSUPPORTED, ok.type().java() + " não é atribuível ao parâmetro '"
                            + param.get().name() + "' (" + param.get().type() + ")", arg.value().span()));
                    }
                }
                site(Site.Kind.CHILD_ARG, arg.value(), scope, null, arg, r);
            }
        }
        for (Statement.SlotFill fill : call.slotFills()) {
            walk(fill.body(), scope, client, clientNames);
        }
    }

    private static Optional<ParamInfo> parameter(List<ParamInfo> params, Statement.Arg arg, int index) {
        if (arg.name().isPresent()) {
            return params.stream().filter(p -> p.name().equals(arg.name().get())).findFirst();
        }
        return index < params.size() ? Optional.of(params.get(index)) : Optional.empty();
    }

    private static JsTranslator.Result caseValue(JsTranslator.Result subject, JsTranslator.Result value, SourceSpan span) {
        if (subject instanceof JsTranslator.Ok s && value instanceof JsTranslator.Ok v) {
            boolean text = (s.type() == ClientType.Scalar.STRING || s.type() == ClientType.Special.NULL)
                && (v.type() == ClientType.Scalar.STRING || v.type() == ClientType.Special.NULL);
            ClientType.Scalar ps = ClientTypes.primitive(s.type());
            ClientType.Scalar pv = ClientTypes.primitive(v.type());
            boolean numbers = (ps == ClientType.Scalar.INT || ps == ClientType.Scalar.DOUBLE)
                && (pv == ClientType.Scalar.INT || pv == ClientType.Scalar.DOUBLE);
            boolean booleans = ps == ClientType.Scalar.BOOLEAN && pv == ClientType.Scalar.BOOLEAN;
            if (!(text || numbers || booleans)) {
                return new JsTranslator.Err(new ClientProblem(UNSUPPORTED, "o case (" + v.type().java()
                    + ") não é comparável com o switch (" + s.type().java() + ") no cliente", span));
            }
        }
        return value;
    }

    private static JsTranslator.Result expect(JsTranslator.Result r, Predicate<ClientType> accepted, String message,
                                              SourceSpan span) {
        if (r instanceof JsTranslator.Ok ok && !accepted.test(ok.type())) {
            return new JsTranslator.Err(new ClientProblem(UNSUPPORTED, message + " (recebeu " + ok.type().java() + ")", span));
        }
        return r;
    }

    private static boolean isBoolean(ClientType t) {
        return t == ClientType.Scalar.BOOLEAN || t.equals(new ClientType.Boxed(ClientType.Scalar.BOOLEAN));
    }

    private static boolean isAttributeValue(ClientType t) {
        return t instanceof ClientType.Scalar || t instanceof ClientType.Boxed || t == ClientType.Special.NULL;
    }

    private static boolean depends(Expr e, Set<String> clientNames) {
        for (String name : ExprNames.of(e)) {
            if (clientNames.contains(name)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> plus(Set<String> names, String name) {
        Set<String> out = new HashSet<>(names);
        out.add(name);
        return out;
    }

    private JsTranslator translator(ClientScope scope) {
        return new JsTranslator(scope, types);
    }

    private void site(Site.Kind kind, Expr expr, ClientScope scope, Statement.HtmlElement element, Object node,
                      JsTranslator.Result result) {
        sites.add(new Site(kind, expr, scope, element, node, result));
        if (kind != Site.Kind.BIND && result instanceof JsTranslator.Err err && !err.problem().silent()) {
            problems.add(err.problem());
        }
    }

    private void problem(String code, String message, SourceSpan span) {
        problems.add(new ClientProblem(code, message, span));
    }
}
