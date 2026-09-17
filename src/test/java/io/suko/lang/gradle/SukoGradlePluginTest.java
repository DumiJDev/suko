package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes para o plugin Gradle Suko.
 */
class SukoGradlePluginTest {

    @Test
    void shouldCompileSkFileUsingJteCompiler() throws Exception {
        String source = """
            component Card(String title) {
              <div class="card"><h2>{title}</h2></div>
            }
            """;
        Path sourceDir = Files.createTempDirectory("suko-test-source");
        Path skFile = sourceDir.resolve("Card.sk");
        Files.writeString(skFile, source);

        String input = Files.readString(skFile);
        JteCompiler compiler = new JteCompiler("Card.sk", input);
        JteCompiler.CompileResult result = compiler.compile();

        assertTrue(result.success(), "Compilação deve ter sucesso");
        assertFalse(result.diagnostics().hasErrors());
        assertTrue(result.generatedJteSources().containsKey("Card.jte"));
    }

    @Test
    void shouldReportErrorForInvalidSkFile() throws Exception {
        String source = "invalid syntax here\n";
        Path sourceDir = Files.createTempDirectory("suko-test-source");
        Path skFile = sourceDir.resolve("Invalid.sk");
        Files.writeString(skFile, source);

        String input = Files.readString(skFile);
        JteCompiler compiler = new JteCompiler("Invalid.sk", input);
        JteCompiler.CompileResult result = compiler.compile();

        assertFalse(result.success(), "Compilação deve falhar para código inválido");
        assertTrue(result.diagnostics().hasErrors());
    }
}