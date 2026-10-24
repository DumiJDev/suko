package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.SukoSources;
import io.suko.lang.support.JteRenderSupport;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Sintaxe da 13b: reactive component, state/derived, on:/bind:, lambdas, new, prefixo:nome, for (var ...). */
class SukoReactiveSyntaxTest {

    /** Parse estrito sem verificador: falha se o ANTLR ou o construtor do AST encontrarem erros. */
    static SukoFile parse(String source) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(source))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(source);
        SukoFile file = builder.build(parser.compilationUnit());
        assertEquals(0, parser.getNumberOfSyntaxErrors(), "erro de sintaxe do ANTLR em: " + source);
        assertEquals(List.of(), builder.syntaxIssues(), "problemas do construtor do AST em: " + source);
        return file;
    }

    static List<SukoDiagnostic> diagnostics(String source) {
        return new JteCompiler("T.sk", source).compile().diagnostics().getDiagnostics();
    }

    static Set<String> codes(String source) {
        return diagnostics(source).stream().map(SukoDiagnostic::code).collect(Collectors.toSet());
    }

    @Test
    void reactiveComponentWithStateDerivedAndDirectives() {
        ComponentDecl c = parse("""
            reactive component Counter(int start) {
              state var count = start;
              state String label = "Total";
              derived int twice = count * 2;
              <button on:click=${() -> count++} class="b">${label}</button>
              <input bind:value=${label} />
            }
            """).components().get(0);
        assertTrue(c.reactive());

        Statement.StateDecl count = assertInstanceOf(Statement.StateDecl.class, c.body().get(0));
        assertEquals("count", count.name());
        assertTrue(count.type().isEmpty(), "state var: tipo por inferir");
        Statement.StateDecl label = assertInstanceOf(Statement.StateDecl.class, c.body().get(1));
        assertEquals("String", label.type().orElseThrow().name());
        Statement.DerivedDecl twice = assertInstanceOf(Statement.DerivedDecl.class, c.body().get(2));
        assertEquals("int", twice.type().orElseThrow().name());

        Statement.HtmlElement button = assertInstanceOf(Statement.HtmlElement.class, c.body().get(3));
        assertEquals(List.of("class"), button.attributes().stream().map(Statement.Attribute::name).toList(),
            "on:click não é um atributo HTML");
        Statement.Directive on = button.directives().get(0);
        assertEquals(Statement.Directive.Kind.ON, on.kind());
        assertEquals("click", on.name());
        Expr.LambdaExpr handler = assertInstanceOf(Expr.LambdaExpr.class, on.value());
        assertEquals(List.of(), handler.params());
        Expr.UpdateExpr inc = assertInstanceOf(Expr.UpdateExpr.class, handler.body().get(0));
        assertEquals("++", inc.operator());
        assertFalse(inc.prefix());

        Statement.HtmlElement input = assertInstanceOf(Statement.HtmlElement.class, c.body().get(4));
        assertTrue(input.attributes().isEmpty());
        Statement.Directive bind = input.directives().get(0);
        assertEquals(Statement.Directive.Kind.BIND, bind.kind());
        assertEquals("value", bind.name());
        assertEquals("label", assertInstanceOf(Expr.PrimaryExpr.class, bind.value()).text());
    }

    @Test
    void lambdaForms() {
        ComponentDecl c = parse("""
            reactive component R() {
              state int a = 0;
              state int b = 0;
              state int n = 0;
              state String draft = "";
              <i on:input=${e -> draft = e.value()} on:keyup=${(e) -> { a = 1; b += 2; ++n; n--; }} on:blur=${() -> { }}></i>
            }
            """).components().get(0);
        List<Statement.Directive> ds = ((Statement.HtmlElement) c.body().get(4)).directives();

        Expr.LambdaExpr first = (Expr.LambdaExpr) ds.get(0).value();
        assertEquals(List.of("e"), first.params());
        assertFalse(first.block());
        Expr.AssignExpr set = assertInstanceOf(Expr.AssignExpr.class, first.body().get(0));
        assertEquals("=", set.operator());
        assertInstanceOf(Expr.CallExpr.class, set.value());

        Expr.LambdaExpr second = (Expr.LambdaExpr) ds.get(1).value();
        assertTrue(second.block());
        assertEquals(List.of("=", "+="), second.body().subList(0, 2).stream()
            .map(x -> ((Expr.AssignExpr) x).operator()).toList());
        assertTrue(((Expr.UpdateExpr) second.body().get(2)).prefix());
        assertEquals("--", ((Expr.UpdateExpr) second.body().get(3)).operator());

        Expr.LambdaExpr third = (Expr.LambdaExpr) ds.get(2).value();
        assertTrue(third.block());
        assertTrue(third.body().isEmpty());
    }

    @Test
    void lambdaAsComponentArgument() {
        SukoFile file = parse("""
            reactive component Stepper(int value, Consumer<Integer> onChange) {
              <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
            }

            reactive component C() {
              state int n = 0;
              Stepper(value = n, onChange = v -> n = v)
              Stepper(n, v -> n = v)
            }
            """);
        List<Statement> body = file.components().get(1).body();
        Statement.ComponentCallStmt named = (Statement.ComponentCallStmt) body.get(1);
        assertEquals("onChange", named.args().get(1).name().orElseThrow());
        Expr.LambdaExpr lambda = assertInstanceOf(Expr.LambdaExpr.class, named.args().get(1).value());
        assertEquals(List.of("v"), lambda.params());
        assertInstanceOf(Expr.AssignExpr.class, lambda.body().get(0));
        Statement.ComponentCallStmt positional = (Statement.ComponentCallStmt) body.get(2);
        assertTrue(positional.args().get(1).name().isEmpty());
        assertInstanceOf(Expr.LambdaExpr.class, positional.args().get(1).value());
    }

    @Test
    void newExpressionAndKeywordsAsMemberAndAttributeNames() {
        ComponentDecl c = parse("""
            component A(Order order) {
              <p data-state="open" data-new="1">${order.state()}</p>
              <p>${new String("ab").length()}</p>
            }
            """).components().get(0);
        Statement.HtmlElement p = (Statement.HtmlElement) c.body().get(0);
        assertEquals(List.of("data-state", "data-new"), p.attributes().stream().map(Statement.Attribute::name).toList());
        Expr.CallExpr call = (Expr.CallExpr) ((Statement.Interpolation) p.children().get(0)).expr();
        assertEquals("state", ((Expr.AccessExpr) call.callee()).memberName());
        Expr.CallExpr length = (Expr.CallExpr) ((Statement.Interpolation)
            ((Statement.HtmlElement) c.body().get(1)).children().get(0)).expr();
        Expr.NewExpr created = assertInstanceOf(Expr.NewExpr.class, ((Expr.AccessExpr) length.callee()).target());
        assertEquals("String", created.typeName());
        assertEquals(1, created.args().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "component A() { <svg><use xlink:href=\"#a\"/></svg> }",
        "component A() { <a hx-on:click=\"go()\">a</a> }",
        "component A() { <a x-on:click=\"open = false\">a</a> }",
        "component A(String u) { <svg><use xlink:href=${u}/></svg> }",
    })
    void prefixedAttributeNamesParse(String source) {
        Statement.Attribute a = firstAttribute(parse(source).components().get(0).body());
        assertNotNull(a, source);
        assertTrue(a.name().contains(":"), a.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "reactive component A() { state int n = 0; <b On:click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b ON:click=${() -> n++}>x</b> }",
        "reactive component A() { state String s = \"\"; <input Bind:value=${s}/> }",
        "reactive component A() { state int n = 0; <b on : click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b on:=${() -> n++}>x</b> }",
        "component A() { <b :click=\"x\">x</b> }",
        "component A() { <b a:b:c=\"x\">x</b> }",
        "component A(String x) { <b on:${x}=\"y\">x</b> }",
        "component A() { <b @click=\"x\">x</b> }",
        "reactive component A() { state int n = 0; <b onclick=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b x-on:click=${() -> n++}>x</b> }",
        "reactive component A() { state int n = 0; <b on:click=\"n++\">x</b> }",
        "reactive component A() { state int n = 0; <b on:click=${n}>x</b> }",
        "reactive component A() { state String s = \"\"; <input bind:value=${() -> s}/> }",
        "reactive component A() { state String s = \"\"; <input bind:value=\"s\"/> }",
        "reactive component A() { <p>x</p> state int n = 0; }",
        "reactive component A() { if (true) { state int n = 0; } }",
        "component A() { <p>${new String(value = \"x\")}</p> }",
        "component A(String s) { <p title=\"${() -> s}\">x</p> }",
        "reactive component A() { state int n = 0; <b on :click=\"n++\">x</b> }",
        "reactive component A() { state int n = 0; <b on: click=\"n++\">x</b> }",
    })
    void malformedReactiveSyntaxIsOnlyAParseError(String source) {
        Set<String> codes = codes(source);
        assertEquals(Set.of("PARSE_ERROR"), codes, source + " -> " + diagnostics(source));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<b On:click=${() -> n++}>x</b>",
        "<b ON:click=${() -> n++}>x</b>",
        "<b on :click=\"a\">x</b>",
        "<b on: click=\"a\">x</b>",
    })
    void builderLevelMistakesGiveExactlyOneDiagnostic(String tag) {
        String source = "reactive component A() { state int n = 0; " + tag + " }";
        List<SukoDiagnostic> found = diagnostics(source);
        assertEquals(1, found.size(), source + " -> " + found);
        assertEquals("PARSE_ERROR", found.get(0).code());
    }

    @ParameterizedTest
    @ValueSource(strings = {"hx-on:click", "x-on:click", "online:x", "HX-ON:click"})
    void dynamicEventLikePrefixedAttributesStayUnsafeSinks(String attr) {
        assertTrue(codes("component A(String u) { <b " + attr + "=${u}>x</b> }").contains("UNSAFE_SINK"), attr);
    }

    @Test
    void uppercaseReservedPrefixPointsAtTheName() {
        String source = "reactive component A() { state int n = 0; <b On:click=${() -> n++}>x</b> }";
        SukoDiagnostic d = diagnostics(source).get(0);
        assertEquals(1, d.span().startLine());
        assertEquals(source.indexOf("On:click"), d.span().startColumn());
        assertTrue(d.message().contains("on:click"), d.message());
    }

    @Test
    void stateInASimpleComponentIsNotASyntaxError() {
        assertFalse(codes("component A() { state int n = 0; <p>x</p> }").contains("PARSE_ERROR"));
    }

    @Test
    void forVarParsesAndRenders() throws Exception {
        String source = "component A(List<String> xs) { <ul>for (var x : xs) { <li>${x}</li> }</ul> }";
        Statement.ForStmt loop = (Statement.ForStmt) firstElement(parse(source).components().get(0).body()).children().get(0);
        assertEquals("var", loop.itemType().name());
        assertEquals("<ul><li>a</li><li>b</li></ul>",
            JteRenderSupport.render(source, "A", Map.of("xs", List.of("a", "b"))).replaceAll("\\s+", ""));
    }

    @Test
    void newIsJavaOnTheServer() throws Exception {
        String source = "component A() { <p>${new String(\"ab\").length()}</p> }";
        assertEquals("<p>2</p>", JteRenderSupport.render(source, "A", Map.of()).replaceAll("\\s+", ""));
    }

    @Test
    void textWithNewTokensRendersUnchanged() throws Exception {
        String source = "component A() { <pre>suko init --yes; i++; a += b; x = new Y(z)</pre> }";
        assertEquals("<pre>suko init --yes; i++; a += b; x = new Y(z)</pre>",
            JteRenderSupport.render(source, "A", Map.of()).trim());
    }

    @Test
    void tolerantParserRecoversAReactiveComponentAfterABrokenOne() {
        SukoFile file = TolerantParser.parse("""
            component Broken() {
              <p>${t.</p>
            }

            reactive component Counter(int start) {
              state int n = 0;
              <b on:click=${() -> n++}>${n}</b>
            }
            """);
        ComponentDecl counter = file.components().stream().filter(c -> c.name().equals("Counter")).findFirst().orElseThrow();
        assertTrue(counter.reactive());
        assertInstanceOf(Statement.StateDecl.class, counter.body().get(0));
    }

    @Test
    void projectIndexRemembersReactiveComponents() {
        ProjectIndex index = ProjectIndex.build(SukoSources.of(Path.of("/virtual"), Map.of(
            Path.of("ui/C.sk"), "package ui;\n\npublic reactive component C() {\n  state int n = 0;\n  <b>${n}</b>\n}\n",
            Path.of("ui/S.sk"), "package ui;\n\npublic component S() {\n  <b>s</b>\n}\n")));
        ProjectIndexEntry c = index.resolveQualified("ui.C").orElseThrow();
        assertTrue(c.reactive());
        assertFalse(index.resolveQualified("ui.S").orElseThrow().reactive());
    }

    private static Statement.Attribute firstAttribute(List<Statement> body) {
        for (Statement s : body) {
            if (s instanceof Statement.HtmlElement e) {
                if (!e.attributes().isEmpty()) {
                    return e.attributes().get(0);
                }
                Statement.Attribute inner = firstAttribute(e.children());
                if (inner != null) {
                    return inner;
                }
            }
        }
        return null;
    }

    private static Statement.HtmlElement firstElement(List<Statement> body) {
        return body.stream().filter(Statement.HtmlElement.class::isInstance).map(Statement.HtmlElement.class::cast)
            .findFirst().orElseThrow();
    }

    /** Fixa os exemplos `suko` da secção "Reactive components" do README: tem de fazer parse. */
    @Test
    void readmeReactiveExamplesParse() throws Exception {
        String readme = java.nio.file.Files.readString(Path.of("..", "README.md"));
        int from = readme.indexOf("### Reactive components");
        assertTrue(from >= 0, "secção em falta no README");
        int to = readme.indexOf("\n### ", from + 1);
        String section = readme.substring(from, to < 0 ? readme.length() : to);
        var m = java.util.regex.Pattern.compile("```suko\\n(.*?)```", java.util.regex.Pattern.DOTALL).matcher(section);
        int blocks = 0;
        while (m.find()) {
            ComponentDecl c = parse(m.group(1)).components().get(0);
            // `derived x = ...` sem tipo parseava como texto livre: exige-se que as declarações sejam reconhecidas.
            assertEquals(2, c.body().stream().filter(Statement.StateDecl.class::isInstance).count());
            assertEquals(1, c.body().stream().filter(Statement.DerivedDecl.class::isInstance).count());
            blocks++;
        }
        assertTrue(blocks >= 1, "nenhum bloco suko no README");
    }

    /** As palavras da 13b continuam a servir de segmento de package/import/nome qualificado. */
    @Test
    void reservedWordsAreValidPackageAndQualifiedNameSegments() {
        SukoFile f = parse("""
            package app.state;
            import app.reactive.Card;
            import derived.new.Other as O;
            component Page() {
              app.state.Card()
            }
            """);
        assertEquals("app.state", f.packageName().orElseThrow());
        assertEquals(List.of("app.reactive.Card", "derived.new.Other"),
            f.imports().stream().map(ImportDecl::qualifiedName).toList());
        assertEquals("app.state.Card",
            assertInstanceOf(Statement.ComponentCallStmt.class, f.components().get(0).body().get(0)).componentName());
    }
}
