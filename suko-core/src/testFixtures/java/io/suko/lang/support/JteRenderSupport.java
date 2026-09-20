package io.suko.lang.support;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import io.suko.lang.JteEmitter;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.SukoProjectCompiler;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Compila um .sk (texto Suko) para .jte via SukoAstBuilder + JteEmitter,
 * escreve o resultado num diretório temporário e renderiza-o com o
 * motor gg.jte real (não simulado), devolvendo o HTML de saída. Usado
 * por todos os testes de emitter/end-to-end deste subprojeto.
 */
public final class JteRenderSupport {

    private JteRenderSupport() {
    }

    public static String compileToJte(String sukoSource, String componentName) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(sukoSource).build(parser.compilationUnit());

        ComponentDecl component = file.components().stream()
            .filter(c -> c.name().equals(componentName))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Componente não encontrado: " + componentName));

        return new JteEmitter().emit(component);
    }

    public static String render(String sukoSource, String componentName, Map<String, Object> params) throws IOException {
        String jteSource = compileToJte(sukoSource, componentName);

        Path tempDir = Files.createTempDirectory("suko-jte-render");
        Files.writeString(tempDir.resolve(componentName + ".jte"), jteSource);

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(componentName + ".jte", params, output);
        return output.toString();
    }

    /** Compila TODOS os componentes do ficheiro para .jte (necessário quando um
     * componente chama outro) e renderiza o indicado por entryComponent. */
    public static String renderWithDependencies(String sukoSource, String entryComponent, Map<String, Object> params) throws IOException {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(sukoSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(sukoSource).build(parser.compilationUnit());

        Path tempDir = Files.createTempDirectory("suko-jte-render-multi");
        JteEmitter emitter = new JteEmitter(file.components());
        for (ComponentDecl component : file.components()) {
            Files.writeString(tempDir.resolve(component.name() + ".jte"), emitter.emit(component));
        }

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(entryComponent + ".jte", params, output);
        return output.toString();
    }

    /** Compila um projeto multi-ficheiro real (SukoProjectCompiler) e
     * renderiza o template pelo caminho relativo, sem ".jte" (ex.
     * "ui/NavLink" ou "Home"). Usado pelos testes do subprojeto 5. */
    public static String renderProject(Path sourceRoot, String entryRelativePath, Map<String, Object> params) throws IOException {
        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(sourceRoot);
        if (!result.success()) {
            throw new IllegalStateException("Compilação do projeto falhou: " + result.diagnosticsByFile());
        }

        Path tempDir = Files.createTempDirectory("suko-jte-render-project");
        for (var entry : result.generatedJteSources().entrySet()) {
            Path jteFile = tempDir.resolve(entry.getKey().toString());
            Files.createDirectories(jteFile.getParent());
            Files.writeString(jteFile, entry.getValue());
        }

        CodeResolver codeResolver = new DirectoryCodeResolver(tempDir);
        TemplateEngine templateEngine = TemplateEngine.create(codeResolver, ContentType.Html);

        TemplateOutput output = new StringOutput();
        templateEngine.render(entryRelativePath + ".jte", params, output);
        return output.toString();
    }
}
