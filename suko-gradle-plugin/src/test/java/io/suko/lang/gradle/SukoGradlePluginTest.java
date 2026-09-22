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
              <div class="card"><h2>${title}</h2></div>
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

    /** Revisão final, achado J: o tratamento amigável de "sem fontes"
     * perdeu-se quando a SukoCompileTask passou a usar o SukoProjectCompiler
     * — um sourceDir inexistente rebentava com UncheckedIOException vindo de
     * Files.walk, em vez da mensagem que o lado Maven sempre teve. */
    @Test
    void noSourcesMessageHandlesMissingAndEmptyDirectories() throws Exception {
        Path missing = Files.createTempDirectory("suko-missing").resolve("does-not-exist");
        String missingMessage = assertDoesNotThrow(() -> SukoCompileTask.noSourcesMessage(missing));
        assertNotNull(missingMessage);
        assertTrue(missingMessage.startsWith("No .sk files found in "), missingMessage);

        Path empty = Files.createTempDirectory("suko-empty");
        assertEquals("No .sk files found in " + empty, SukoCompileTask.noSourcesMessage(empty));

        Path withSources = Files.createTempDirectory("suko-with-sources");
        Files.createDirectories(withSources.resolve("ui"));
        Files.writeString(withSources.resolve("ui").resolve("Card.sk"), "component Card() {\n  <p>x</p>\n}\n");
        assertNull(SukoCompileTask.noSourcesMessage(withSources),
            "com .sk (mesmo em subpasta) a compilação tem de prosseguir");
    }
}