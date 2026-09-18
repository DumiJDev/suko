package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes end-to-end para o modo watch (SukoWatchTask).
 */
class SukoWatchTaskE2ETest {

    @TempDir
    Path tempDir;

    @Test
    void testBasicCompilation() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("jte-out");
        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);

        Path skFile = sourceDir.resolve("Card.sk");
        String source = """
            component Card(String title) {
              <div class="card"><h2>{title}</h2></div>
            }
            """;
        Files.writeString(skFile, source);

        // Compile directly
        String fileName = skFile.getFileName().toString();
        JteCompiler compiler = new JteCompiler(fileName, source);
        var result = compiler.compile();

        System.out.println("Success: " + result.success());
        System.out.println("Diags: " + result.diagnostics());
        for (var diag : result.diagnostics().getDiagnostics()) {
            System.out.println("  " + diag);
        }
        System.out.println("Generated: " + result.generatedJteSources());

assertTrue(result.success(), "Compilação deve ter sucesso");
         
        for (var entry : result.generatedJteSources().entrySet()) {
            Path jtePath = outputDir.resolve(entry.getKey());
            Files.createDirectories(jtePath.getParent());
            Files.writeString(jtePath, entry.getValue());
        }

        Path jteFile = outputDir.resolve("Card.jte");
        assertTrue(Files.exists(jteFile), "Arquivo .jte deve ser gerado");
        String content = Files.readString(jteFile);
        System.out.println("JTE content: " + content);
        assertTrue(content.contains("@param String title"), "Conteúdo deve conter parâmetro title");
    }

    @Test
    void watchModeCompilesSkFileAndRecompilesOnChange() throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("jte-out");
        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);

        Path skFile = sourceDir.resolve("Card.sk");
        String source = """
            component Card(String title) {
              <div class="card"><h2>{title}</h2></div>
            }
            """;
        Files.writeString(skFile, source);

        // Do initial compilation
        compileSingleFile(skFile, outputDir);

        // Verify initial compilation
        Path jteFile = outputDir.resolve("Card.jte");
        assertTrue(Files.exists(jteFile), "Arquivo .jte deve ser gerado inicialmente");
        String originalContent = Files.readString(jteFile);
        assertTrue(originalContent.contains("@param String title"), "Conteúdo inicial deve conter parâmetro title");

        // Modify the .sk file
        Files.writeString(skFile, """
            component Card(String title) {
              <div class="card"><h2>{title}</h2><p>Atualizado!</p></div>
            }
            """);

        // Recompile
        compileSingleFile(skFile, outputDir);

        // Verify recompilation
        assertTrue(Files.exists(jteFile), "Arquivo .jte deve ser gerado após modificação");
        String updatedContent = Files.readString(jteFile);
        assertTrue(updatedContent.contains("Atualizado!"), "Conteúdo atualizado deve conter 'Atualizado!'");
    }

    private void compileSingleFile(Path skFile, Path outputDir) {
        try {
            String source = Files.readString(skFile);
            String fileName = skFile.getFileName().toString();
            JteCompiler compiler = new JteCompiler(fileName, source);
            var result = compiler.compile();

            if (!result.success()) {
                System.out.println("Compilation failed for " + fileName);
                for (var diag : result.diagnostics().getDiagnostics()) {
                    System.out.println("  " + diag);
                }
            }

            for (var entry : result.generatedJteSources().entrySet()) {
                Path jtePath = outputDir.resolve(entry.getKey());
                Files.createDirectories(jtePath.getParent());
                Files.writeString(jtePath, entry.getValue());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to compile " + skFile, e);
        }
    }
}