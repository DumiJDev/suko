package io.suko.lang.client;

import io.suko.jte.client.Callees;
import io.suko.jte.client.ClientModel;
import io.suko.jte.client.ClientProblem;
import io.suko.jte.client.ClientType;
import io.suko.jte.client.JsTranslator;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectView;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static io.suko.jte.client.ClientModel.Site.Kind.*;
import static io.suko.jte.client.ClientType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

/** O que corre no cliente num reactive component: regiões, sites, tipos e parâmetros lidos (13b). */
class ClientModelTest {

    /** O modelo do componente {@code name} do ficheiro (os outros componentes do ficheiro resolvem-se como filhos). */
    static ClientModel model(String sk, String name) {
        SukoParser parser = new SukoParser(new CommonTokenStream(new SukoLexer(CharStreams.fromString(sk))));
        parser.removeErrorListeners();
        SukoAstBuilder builder = new SukoAstBuilder(sk);
        SukoFile file = builder.build(parser.compilationUnit());
        assertEquals(0, parser.getNumberOfSyntaxErrors(), sk);
        assertEquals(List.of(), builder.syntaxIssues(), sk);
        ComponentDecl c = file.components().stream().filter(x -> x.name().equals(name)).findFirst().orElseThrow();
        return ClientModel.of(c, new Callees(file, ProjectView.EMPTY), ClientTestSupport.TYPES);
    }

    static List<ClientModel.Site.Kind> kinds(ClientModel m) {
        return m.sites().stream().map(ClientModel.Site::kind).toList();
    }

    static ClientProblem onlyProblem(ClientModel m) {
        assertEquals(1, m.problems().size(), m.problems().toString());
        return m.problems().get(0);
    }

    static final String TODO = """
        reactive component TodoList(List<String> initial, String placeholder, String footer) {
          state var items = initial;
          state var draft = "";
          state boolean showDone = true;
          derived var remaining = items.size();
          <section>
            <input bind:value=${draft} placeholder="${placeholder}" />
            <button on:click=${() -> { items.add(draft); draft = ""; }}>Adicionar</button>
            if (showDone) {
              <ul>for (var t : items) { <li>${t}</li> }</ul>
            }
            <p>${remaining} itens</p>
            <footer>${footer}</footer>
          </section>
        }
        """;

    @Test
    void todoListWithoutRecords() {
        ClientModel m = model(TODO, "TodoList");
        assertEquals(List.of(), m.problems());
        assertEquals(new ClientType.ListOf(STRING), m.stateTypes().get("items"));
        assertEquals(STRING, m.stateTypes().get("draft"));
        assertEquals(BOOLEAN, m.stateTypes().get("showDone"));
        assertEquals(INT, m.derivedTypes().get("remaining"));
        assertEquals(Set.of("initial"), m.clientReadParams(), "placeholder e footer ficam só no servidor (minimização)");
        assertEquals(List.of(STATE_INIT, STATE_INIT, STATE_INIT, DERIVED, BIND, HANDLER, CONDITION, ITERABLE, TEXT, TEXT),
            kinds(m));

        Statement.HtmlElement section = (Statement.HtmlElement) m.component().body().get(4);
        Statement.HtmlElement input = (Statement.HtmlElement) section.children().get(0);
        Statement.IfStmt ifStmt = (Statement.IfStmt) section.children().get(2);
        Statement.HtmlElement ul = (Statement.HtmlElement) ifStmt.thenBranch().get(0);
        Statement.ForStmt loop = (Statement.ForStmt) ul.children().get(0);
        Statement.HtmlElement li = (Statement.HtmlElement) loop.body().get(0);
        assertFalse(m.isClientCreated(section));
        assertFalse(m.isClientAttribute(input.attributes().get(0)), "placeholder não depende de state");
        assertTrue(m.isClientBlock(ifStmt));
        assertTrue(m.isClientCreated(ul) && m.isClientCreated(li));
        assertEquals(List.of(ul, li), m.clientElements());

        JsTranslator.Ok handler = (JsTranslator.Ok) m.sites().get(5).result();
        assertEquals("() => { $s.items.push($s.draft); $u(\"items\"); $s.draft = \"\"; $u(\"draft\"); }", handler.js());
    }

    @Test
    void aServerLoopMovesToTheClientWhenAHandlerReadsItsVariable() {
        ClientModel m = model("""
            reactive component Picker(List<String> options) {
              state String chosen = "";
              <ul>for (String o : options) { <li on:click=${() -> chosen = o}>${o}</li> }</ul>
              <p>${chosen}</p>
            }
            """, "Picker");
        assertEquals(List.of(), m.problems());
        Statement.ForStmt loop = (Statement.ForStmt) ((Statement.HtmlElement) m.component().body().get(1)).children().get(0);
        assertTrue(m.isClientBlock(loop));
        assertEquals(Set.of("options"), m.clientReadParams());
    }

    @Test
    void reactiveChildrenGetArgumentsAndCallbacks() {
        ClientModel m = model("""
            reactive component Stepper(int value, Consumer<Integer> onChange) {
              <b on:click=${() -> onChange.accept(value + 1)}>${value}</b>
            }

            reactive component C() {
              state int n = 0;
              Stepper(value = n, onChange = v -> n = v)
            }
            """, "C");
        assertEquals(List.of(), m.problems());
        assertEquals(List.of(STATE_INIT, CHILD_ARG, CALLBACK), kinds(m));
        assertEquals("(v) => { $s.n = $suko.nn(v); $u(\"n\"); }", ((JsTranslator.Ok) m.sites().get(2).result()).js());
    }

    @Test
    void styleDeclarationsAreTranslatedToJavaStrings() {
        ClientModel m = model("reactive component R() { state int pct = 50; <div style=\"width: ${pct}%\">x</div> }", "R");
        assertEquals(List.of(), m.problems());
        ClientModel.Site site = m.sites().get(1);
        assertEquals(STYLE_VALUE, site.kind());
        assertEquals("String($s.pct)", ((JsTranslator.Ok) site.result()).js());
    }

    @Test
    void typeNotInferred() {
        ClientProblem p = onlyProblem(model("reactive component R() { state var x = null; <p>${x}</p> }", "R"));
        assertEquals("TYPE_NOT_INFERRED", p.code());
        assertTrue(p.message().contains("'x'"), p.message());
    }

    @Test
    void simpleComponentInsideAStateBlock() {
        ClientProblem p = onlyProblem(model("""
            component Card() { <b>c</b> }
            reactive component R() { state boolean open = false; if (open) { Card() } }
            """, "R"));
        assertEquals("CLIENT_UNSUPPORTED", p.code());
        assertTrue(p.message().contains("'Card' não é reativo"), p.message());
    }

    @Test
    void wrongShapesAreClientUnsupported() {
        assertTrue(onlyProblem(model("reactive component R(String p) { state int n = 0; var label = p.trim(); <p>${n + label}</p> }", "R"))
            .message().contains("derived"));
        assertTrue(onlyProblem(model("reactive component R() { state int a = b; state int b = 1; <p>${a}</p> }", "R"))
            .message().contains("declarados antes"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = \"x\"; <p>${n}</p> }", "R"))
            .message().contains("não é atribuível"));
        assertTrue(onlyProblem(model("reactive component R() { state long big = 0; <p>x</p> }", "R"))
            .message().contains("2^53"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = 0; if (n) { <p>x</p> } }", "R"))
            .message().contains("condição"));
        assertTrue(onlyProblem(model("reactive component R() { state int n = 0; for (var x : n) { <p>x</p> } }", "R"))
            .message().contains("List"));
        assertTrue(onlyProblem(model("""
            reactive component Child(int value) { <b>${value}</b> }
            reactive component C() { state int n = 0; Child(value = () -> n++) }
            """, "C")).message().contains("Consumer<T> nem Runnable"));
    }

    @Test
    void parameterTypesKeepWhatTheyAre() {
        ClientModel m = model("reactive component R(long id, Todo t, Product p, Component header) { state int n = 0; <p>${n}</p> }", "R");
        assertInstanceOf(ClientType.Unsupported.class, m.paramTypes().get("id"));
        assertInstanceOf(ClientType.RecordOf.class, m.paramTypes().get("t"));
        assertEquals(new ClientType.Named("Product"), m.paramTypes().get("p"));
        assertFalse(m.paramTypes().containsKey("header"), "slots não são parâmetros de valor");
    }
}
