package io.suko.lang.semantic;

import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectAnalysis;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** D3 (subprojeto 11a): as regras de slots e parâmetros valem para
 * componentes de outros ficheiros exatamente como para os do próprio. */
class SemanticCheckerCrossFileTest {

    private static final String CARD = """
        package ui;

        public component Card(String title, Component header, List<Component> items, Component footer = null) {
          <div>${title}</div>
        }
        """;

    private static List<SukoDiagnostic> errorsOf(String callerSource) {
        SukoSources sources = SukoSources.of(Path.of("/root"), Map.of(
            Path.of("ui/Card.sk"), CARD,
            Path.of("Page.sk"), callerSource));
        ProjectAnalysis analysis = new SukoProjectCompiler().analyze(sources);
        return analysis.files().get(Path.of("Page.sk")).diagnostics().getErrors();
    }

    private static List<SukoDiagnostic> sameFileErrors(String body) {
        SukoSources sources = SukoSources.of(Path.of("/root"), Map.of(
            Path.of("Page.sk"), CARD.replace("package ui;\n\n", "").replace("public component", "component") + "\n" + body));
        return new SukoProjectCompiler().analyze(sources).files().get(Path.of("Page.sk")).diagnostics().getErrors();
    }

    private static void assertCode(List<SukoDiagnostic> errors, String code) {
        assertTrue(errors.stream().anyMatch(d -> code.equals(d.code())), code + " esperado em " + errors);
    }

    private static void assertNoErrors(List<SukoDiagnostic> errors) {
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private static final String VALID_CALL = "  Card(title = \"t\") {\n    header { <b>h</b> }\n  }\n";

    @Test
    void validCrossFileCallHasNoErrors() {
        assertNoErrors(errorsOf("import ui.Card;\n\ncomponent Page() {\n" + VALID_CALL + "}\n"));
    }

    @Test
    void requiredSlotMissingAcrossFiles() {
        assertCode(errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\")\n}\n"), "REQUIRED_SLOT_MISSING");
    }

    @Test
    void slotNotFoundAcrossFiles() {
        assertCode(errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\") {\n    header { <b>h</b> }\n    sidebar { <b>s</b> }\n  }\n}\n"),
            "SLOT_NOT_FOUND");
    }

    @Test
    void cardinalityViolationAcrossFiles() {
        assertCode(errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\") {\n    header { <b>1</b> }\n    header { <b>2</b> }\n  }\n}\n"),
            "CARDINALITY_VIOLATION");
    }

    @Test
    void manyCardinalitySlotAcceptsSeveralFills() {
        assertNoErrors(errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\") {\n    header { <b>h</b> }\n    items { <i>1</i> }\n    items { <i>2</i> }\n  }\n}\n"));
    }

    @Test
    void paramNotFoundAcrossFilesNamesTheArgumentAndListsValidOnes() {
        List<SukoDiagnostic> errors = errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(titel = \"t\") {\n    header { <b>h</b> }\n  }\n}\n");
        assertCode(errors, "PARAM_NOT_FOUND");
        SukoDiagnostic d = errors.stream().filter(e -> "PARAM_NOT_FOUND".equals(e.code())).findFirst().orElseThrow();
        assertTrue(d.message().contains("'titel'"), d.message());
        assertTrue(d.message().contains("title"), d.message());
        assertEquals(4, d.span().startLine());
    }

    @Test
    void typosGetADidYouMeanSuggestionAndOtherNamesGetTheValidList() {
        String head = "import ui.Card;\n\ncomponent Page() {\n";
        SukoDiagnostic param = errorsOf(head + "  Card(titel = \"t\") { header { <b>h</b> } }\n}\n").stream()
            .filter(e -> "PARAM_NOT_FOUND".equals(e.code())).findFirst().orElseThrow();
        assertTrue(param.message().endsWith("— quis dizer 'title'?"), param.message());

        SukoDiagnostic slot = errorsOf(head + "  Card(title = \"t\") { header { <b>h</b> } foter { <b>f</b> } }\n}\n").stream()
            .filter(e -> "SLOT_NOT_FOUND".equals(e.code())).findFirst().orElseThrow();
        assertTrue(slot.message().endsWith("— quis dizer 'footer'?"), slot.message());

        SukoDiagnostic far = errorsOf(head + "  Card(zzzzzz = 1) { header { <b>h</b> } }\n}\n").stream()
            .filter(e -> "PARAM_NOT_FOUND".equals(e.code())).findFirst().orElseThrow();
        assertTrue(far.message().contains("— parâmetros: title, header, items, footer"), far.message());
    }

    @Test
    void qualifiedCallWithoutImportIsAlsoValidated() {
        assertCode(errorsOf("component Page() {\n  ui.Card(title = \"t\")\n}\n"), "REQUIRED_SLOT_MISSING");
    }

    @Test
    void sameFileRulesStillHold() {
        assertCode(sameFileErrors("component Page() {\n  Card(title = \"t\")\n}\n"), "REQUIRED_SLOT_MISSING");
        assertCode(sameFileErrors("component Page() {\n  Card(title = \"t\") {\n    header { <b>h</b> }\n    x { <b>s</b> }\n  }\n}\n"), "SLOT_NOT_FOUND");
        assertCode(sameFileErrors("component Page() {\n  Card(title = \"t\") {\n    header { <b>1</b> }\n    header { <b>2</b> }\n  }\n}\n"), "CARDINALITY_VIOLATION");
    }

    @Test
    void paramNotFoundInTheSameFile() {
        assertCode(sameFileErrors("component Page() {\n  Card(nope = 1) {\n    header { <b>h</b> }\n  }\n}\n"), "PARAM_NOT_FOUND");
        assertNoErrors(sameFileErrors("component Page() {\n" + VALID_CALL + "}\n"));
    }

    @Test
    void aSlotPassedAsANamedArgumentCountsAsFilled() {
        // `Component h` como valor: forma prevista no subprojeto 6, o JteEmitter passa-a tal como está
        String page = "import ui.Card;\n\ncomponent Page(Component h) {\n  Card(title = \"t\", header = h)\n}\n";
        assertNoErrors(errorsOf(page));
        assertNoErrors(sameFileErrors("component Page(Component h) {\n  Card(title = \"t\", header = h)\n}\n"));
    }

    @Test
    void aSlotGivenAsArgumentAndAsBlockIsRejected() {
        String body = "component Page(Component h) {\n  Card(title = \"t\", header = h) {\n    header { <b>x</b> }\n  }\n}\n";
        assertCode(errorsOf("import ui.Card;\n\n" + body), "CARDINALITY_VIOLATION");
        assertCode(sameFileErrors(body), "CARDINALITY_VIOLATION");
    }

    @Test
    void slotDiagnosticsPointAtTheSlotOrCallNameNotTheWholeCall() {
        List<SukoDiagnostic> notFound = errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\") {\n    header { <b>h</b> }\n    sidebar { <b>s</b> }\n  }\n}\n");
        SukoDiagnostic d = notFound.stream().filter(e -> "SLOT_NOT_FOUND".equals(e.code())).findFirst().orElseThrow();
        assertEquals(6, d.span().startLine(), "aponta para `sidebar`, não para `Card(`");

        List<SukoDiagnostic> missing = errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(title = \"t\")\n}\n");
        SukoDiagnostic m = missing.stream().filter(e -> "REQUIRED_SLOT_MISSING".equals(e.code())).findFirst().orElseThrow();
        assertEquals("Card".length() - 1, m.span().endIndex() - m.span().startIndex());
    }

    @Test
    void positionalArgumentsAreNotCheckedByName() {
        assertNoErrors(errorsOf("import ui.Card;\n\ncomponent Page() {\n  Card(\"t\") {\n    header { <b>h</b> }\n  }\n}\n"));
    }
}
