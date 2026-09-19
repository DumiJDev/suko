package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JteCompilerTest {

    @Test
    void compilesValidSukoFile() {
        String source = """
            component Card(String title, List<String> items) {
              <div class="card">
                <h2>{title}</h2>
                <ul>
                  for (String item : items) {
                    <li>{item}</li>
                  }
                </ul>
              </div>
            }
            """;

        JteCompiler compiler = new JteCompiler("test.sk", source);
        JteCompiler.CompileResult result = compiler.compile();

        assertTrue(result.success());
        assertFalse(result.diagnostics().hasErrors());
        assertEquals(1, result.generatedJteSources().size());
        assertTrue(result.generatedJteSources().containsKey("Card.jte"));
    }

    @Test
    void reportsMissingComponent() {
        String source = """
            component Page() {
              NonExistent()
            }
            """;

        JteCompiler compiler = new JteCompiler("test.sk", source);
        JteCompiler.CompileResult result = compiler.compile();

        assertFalse(result.success());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals(1, result.diagnostics().getErrors().size());
        assertEquals("COMPONENT_NOT_FOUND", result.diagnostics().getErrors().get(0).code());
    }

    @Test
    void reportsMissingRequiredSlot() {
        // MIGRADO (tarefa 2, subprojeto 6): `slot<String>` -> `Component`.
        String source = """
            component Card(Component header) {
              <div>{header}</div>
            }
            component Page() {
              Card()
            }
            """;

        JteCompiler compiler = new JteCompiler("test.sk", source);
        JteCompiler.CompileResult result = compiler.compile();

        assertFalse(result.success());
        assertTrue(result.diagnostics().hasErrors());
        assertEquals("REQUIRED_SLOT_MISSING", result.diagnostics().getErrors().get(0).code());
    }

    @Test
    void compilesMultipleComponents() {
        String source = """
            component Card(String title) {
              <div class="card"><h2>{title}</h2></div>
            }
            component Page() {
              Card(title = "Hello")
            }
            """;

        JteCompiler compiler = new JteCompiler("test.sk", source);
        JteCompiler.CompileResult result = compiler.compile();

        assertTrue(result.success());
        assertEquals(2, result.generatedJteSources().size());
        assertTrue(result.generatedJteSources().containsKey("Card.jte"));
        assertTrue(result.generatedJteSources().containsKey("Page.jte"));
    }
}