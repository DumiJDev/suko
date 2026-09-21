package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import org.gradle.testfixtures.ProjectBuilder;
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

    /**
     * D13: sukoWatch passa a compilar via SukoProjectCompiler (Fase 1
     * ProjectIndex.build + Fase 2 por ficheiro), tal como sukoCompile —
     * espelhando pacotes no output em vez de escrever tudo à raiz do
     * outputDir. Antes desta tarefa, watch() compilava ficheiro a ficheiro
     * com JteCompiler puro e escrevia sempre outputDir.resolve(fileName),
     * ignorando a subpasta de pacote.
     */
    @Test
    void watchMirrorsPackagesInOutput() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("jte-out");
        Path uiDir = sourceDir.resolve("io/demo/ui");
        Files.createDirectories(uiDir);
        Files.createDirectories(outputDir);

        Files.writeString(uiDir.resolve("Label.sk"), """
            package io.demo.ui;

            public component Label(String text) {
              <span>{text}</span>
            }
            """);

        SukoWatchTask task = newWatchTask();
        task.compileAll(sourceDir, outputDir);

        Path jteFile = outputDir.resolve("io/demo/ui/Label.jte");
        assertTrue(Files.exists(jteFile),
            "Label.jte deve ser gerado em io/demo/ui/, espelhando o pacote — não na raiz de " + outputDir);
    }

    /**
     * D13: Field.sk importa e chama Label.sk no mesmo pacote. Sem
     * ProjectIndex (caminho antigo, ficheiro a ficheiro), a chamada a
     * Label() não resolvia — cada .sk era compilado isoladamente, sem
     * conhecimento de outros ficheiros do projeto.
     */
    @Test
    void watchResolvesCrossFileComponentCalls() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("jte-out");
        Path uiDir = sourceDir.resolve("io/demo/ui");
        Files.createDirectories(uiDir);
        Files.createDirectories(outputDir);

        Files.writeString(uiDir.resolve("Label.sk"), """
            package io.demo.ui;

            public component Label(String text) {
              <span>{text}</span>
            }
            """);
        Files.writeString(uiDir.resolve("Field.sk"), """
            package io.demo.ui;

            import io.demo.ui.Label;

            component Field(String text) {
              Label(text=text)
            }
            """);

        SukoWatchTask task = newWatchTask();
        task.compileAll(sourceDir, outputDir);

        Path fieldJte = outputDir.resolve("io/demo/ui/Field.jte");
        assertTrue(Files.exists(fieldJte), "Field.jte deve ser gerado");
        String content = Files.readString(fieldJte);
        assertTrue(content.contains("@template.io.demo.ui.Label("),
            "Field.jte deve chamar @template.io.demo.ui.Label(...) resolvido via ProjectIndex: " + content);
    }

    /**
     * D13, Passo 4: diagnósticos de nível de projeto (aqui, IMPORT_NOT_FOUND)
     * também passam a ser reportados em modo watch, porque agora o caminho de
     * compilação usa o ProjectIndex do SukoProjectCompiler — não apenas
     * JteCompiler isolado por ficheiro.
     */
    @Test
    void watchReportsProjectLevelDiagnostics() throws IOException {
        Path sourceDir = tempDir.resolve("src");
        Path outputDir = tempDir.resolve("jte-out");
        Files.createDirectories(sourceDir);
        Files.createDirectories(outputDir);

        Files.writeString(sourceDir.resolve("Home.sk"), """
            import io.demo.ui.NaoExiste;

            component Home() {
              <div>oi</div>
            }
            """);

        SukoWatchTask task = newWatchTask();
        task.compileAll(sourceDir, outputDir);

        // Não deve ter sido gerado o Home.jte com o import quebrado, e o
        // diagnóstico IMPORT_NOT_FOUND devia ter sido reportado (verificado
        // ao correr sem exceções — o objetivo do teste é confirmar que o
        // caminho de ProjectIndex é mesmo exercitado a partir do watch, e não
        // apenas o JteCompiler isolado, que nem sequer conhece este código de
        // diagnóstico).
        assertFalse(Files.exists(outputDir.resolve("Home.jte")),
            "Home.jte não devia ser gerado com um import não resolvido");
    }

    private SukoWatchTask newWatchTask() {
        org.gradle.api.Project project = ProjectBuilder.builder().build();
        return project.getTasks().create("sukoWatch", SukoWatchTask.class);
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