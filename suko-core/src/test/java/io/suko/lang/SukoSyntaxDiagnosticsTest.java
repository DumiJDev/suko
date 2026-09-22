package io.suko.lang;

import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Diagnósticos de SINTAXE do SemanticChecker, exercitados pelo pipeline
 * real (JteCompiler: parse -> check), não por AST construído à mão.
 *
 * C3 do plano do subprojeto 9: o percurso que emite estes diagnósticos
 * tinha `default -> {}` e não descia a if/for/switch nem a corpos de slot
 * fill — e os três componentes mais usados da biblioteca (Alert, Badge,
 * Button) têm o corpo INTEIRO dentro de um switch.
 */
class SukoSyntaxDiagnosticsTest {

    private static List<SukoDiagnostic> diagnosticsOf(String source) {
        return new JteCompiler("test.sk", source).compile().diagnostics().getDiagnostics();
    }

    private static long countOf(String source, String code) {
        return diagnosticsOf(source).stream().filter(d -> code.equals(d.code())).count();
    }

    @Test
    void bareBraceInStringIsFoundAtTopLevel() {
        String source = """
            component Button(String variant) {
              <a class="btn btn-{variant}">Click</a>
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideSwitchCase() {
        String source = """
            component Badge(String variant) {
              switch (variant) {
                case "success" -> {
                  <span class="badge badge-{variant}">ok</span>
                }
                default -> {
                  <span>x</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideIfBranch() {
        String source = """
            component Card(String variant, boolean shown) {
              if (shown) {
                <div class="card card-{variant}">x</div>
              } else {
                <div class="card card-{variant}">y</div>
              }
            }
            """;
        assertEquals(2, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideForBody() {
        String source = """
            component ListView(String variant, List<String> items) {
              for (String item : items) {
                <li class="row row-{variant}">z</li>
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void bareBraceInStringIsFoundInsideSlotFillBody() {
        String source = """
            component Box(Component header) {
              <div>{header}</div>
            }

            component Page(String variant) {
              Box() {
                header {
                  <span class="h h-{variant}">t</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "BARE_BRACE_IN_STRING"));
    }

    @Test
    void cleanComponentProducesNoSyntaxDiagnostics() {
        String source = """
            component Badge(String variant) {
              switch (variant) {
                case "success" -> {
                  <span class="badge badge-success">${variant}</span>
                }
                default -> {
                  <span class="badge">${variant}</span>
                }
              }
            }
            """;
        assertTrue(diagnosticsOf(source).isEmpty(), "sem diagnósticos: " + diagnosticsOf(source));
    }

    @Test
    void legacyBraceInterpolationIsAnErrorWithTheExactFix() {
        String source = """
            component Label(String text) {
              <label>{text}</label>
            }
            """;
        SukoDiagnostic diag = diagnosticsOf(source).stream()
                .filter(d -> "LEGACY_BRACE_INTERPOLATION".equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sem LEGACY_BRACE_INTERPOLATION: " + diagnosticsOf(source)));
        assertEquals(SukoDiagnostic.Severity.ERROR, diag.severity());
        assertTrue(diag.message().contains("${text}"), diag.message());
    }

    @Test
    void legacyBraceAttributeIsAnErrorWithTheExactFix() {
        String source = """
            component Input(String id) {
              <input id={id} />
            }
            """;
        SukoDiagnostic diag = diagnosticsOf(source).stream()
                .filter(d -> "LEGACY_BRACE_ATTRIBUTE".equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("sem LEGACY_BRACE_ATTRIBUTE: " + diagnosticsOf(source)));
        assertEquals(SukoDiagnostic.Severity.ERROR, diag.severity());
        assertTrue(diag.message().contains("id=${id}"), diag.message());
    }

    @Test
    void legacyBraceIsFoundInsideSwitchCase() {
        // C3 outra vez: Alert/Badge/Button têm o corpo inteiro num switch.
        String source = """
            component Badge(String variant, Component children) {
              switch (variant) {
                case "success" -> {
                  <span class="badge">{children}</span>
                }
                default -> {
                  <span class="badge">${children}</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
    }

    @Test
    void legacyBraceIsFoundInsideSlotFillBody() {
        String source = """
            component Box(Component header) {
              <div>${header}</div>
            }

            component Page(String t) {
              Box() {
                header {
                  <span>{t}</span>
                }
              }
            }
            """;
        assertEquals(1, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
    }

    @Test
    void modernSyntaxProducesNoLegacyDiagnostic() {
        String source = """
            component Input(String id, boolean required) {
              <input id=${id} required=${required} class="field-${id}" title="ola $id" />
            }
            """;
        assertEquals(0, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
        assertEquals(0, countOf(source, "LEGACY_BRACE_ATTRIBUTE"));
    }

    @Test
    void bareBracesInsideStringAreNotFlaggedAsLegacy() {
        // C5: chavetas dentro de string nunca foram interpolação e continuam
        // texto literal — Alpine. O diagnóstico novo não pode tocar nelas.
        String source = """
            component Dlg() {
              <div x-data="{ open: false }">x</div>
            }
            """;
        assertEquals(0, countOf(source, "LEGACY_BRACE_INTERPOLATION"));
        assertEquals(0, countOf(source, "LEGACY_BRACE_ATTRIBUTE"));
    }
}
