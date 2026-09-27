package io.suko.website;

import gg.jte.CodeResolver;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.TemplateOutput;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.Cardinality;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.registry.ComponentManifest;
import io.suko.registry.ComponentFile;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Gera o site de documentação estático do Suko.
 *
 * Pipeline:
 *   1. Lê componentes da biblioteca `suko-components` a partir do registry.json
 *   2. Gera ficheiros `.sk` de documentação para cada componente (com parâmetros,
 *      descrição, fonte original e requisitos externos)
 *   3. Compila TODOS os `.sk` (estáticos em src/main/suko + gerados) via
 *      `SukoProjectCompiler` → `.jte`
 *   4. Renderiza cada `.jte` com o motor `gg.jte` real para HTML estático
 *   5. Escreve o resultado em `build/website/` (deployável em qualquer hosting
 *      estático: Netlify, Vercel, GitHub Pages, etc.)
 */
public final class WebsiteGenerator {

    private final Path projectDir;
    private final Path sourceRoot;
    private final Path outputDir;
    private final Path registryDir;

    public WebsiteGenerator(Path projectDir, Path sourceRoot, Path outputDir, Path registryDir) {
        this.projectDir = projectDir;
        this.sourceRoot = sourceRoot;
        this.outputDir = outputDir;
        this.registryDir = registryDir;
    }

    public void generate() throws IOException {
        // Clean output
        if (Files.exists(outputDir)) {
            deleteRecursively(outputDir);
        }
        Files.createDirectories(outputDir);

        // Step 1: Read registry data
        RegistryIndex registry = loadRegistry();
        Map<String, ComponentManifest> manifests = new LinkedHashMap<>();
        for (RegistryIndex.Entry entry : registry.components()) {
            manifests.put(entry.name(), loadManifest(entry));
        }

        // Step 2: Prepare temp source directory with static .sk files + generated component pages
        Path tempSourceRoot = Files.createTempDirectory("suko-website-src");
        copyRecursively(sourceRoot, tempSourceRoot);

        // Generate component detail pages as .sk files
        Path componentsDir = tempSourceRoot.resolve("components");
        Files.createDirectories(componentsDir);
        for (RegistryIndex.Entry entry : registry.components()) {
            ComponentManifest manifest = manifests.get(entry.name());
            String skContent = generateComponentDetailPage(entry, manifest);
            Files.writeString(componentsDir.resolve(entry.name() + ".sk"), skContent, StandardCharsets.UTF_8);
        }

        // Generate component index page as .sk file
        String indexContent = generateComponentIndexPage(registry, manifests);
        Files.writeString(componentsDir.resolve("index.sk"), indexContent, StandardCharsets.UTF_8);

        // Step 3: Compile all .sk → .jte
        SukoProjectCompiler.ProjectCompileResult compileResult =
            new SukoProjectCompiler().compile(tempSourceRoot);

        if (!compileResult.success()) {
            StringBuilder sb = new StringBuilder("Compilação do website falhou:\n");
            for (var fileEntry : compileResult.diagnosticsByFile().entrySet()) {
                sb.append("  ").append(fileEntry.getKey()).append(":\n");
                for (var d : fileEntry.getValue().getDiagnostics()) {
                    sb.append("    - ").append(d.message()).append('\n');
                }
            }
            // Do NOT delete tempSourceRoot on failure to allow inspection of generated files
            throw new IllegalStateException(sb.toString());
        }

        // Step 4: Write .jte files to temp directory
        Path jteDir = Files.createTempDirectory("suko-website-jte");
        for (var jteEntry : compileResult.generatedJteSources().entrySet()) {
            Path jteFile = jteDir.resolve(jteEntry.getKey().toString());
            Files.createDirectories(jteFile.getParent());
            Files.writeString(jteFile, jteEntry.getValue(), StandardCharsets.UTF_8);
        }

        // Step 5: Render each template to HTML
        CodeResolver codeResolver = new DirectoryCodeResolver(jteDir);
        TemplateEngine engine = TemplateEngine.create(codeResolver, ContentType.Html);

        // Render index page
        renderAndWrite(engine, "Index", Map.of(), "Suko", outputDir.resolve("index.html"));

        // Render getting-started page
        renderAndWrite(engine, "GettingStarted", Map.of(), "Getting Started · Suko",
            outputDir.resolve("getting-started.html"));

        // Render language-reference page
        renderAndWrite(engine, "LanguageReference", Map.of(), "Language Reference · Suko",
            outputDir.resolve("language-reference.html"));

        // Render roadmap page
        renderAndWrite(engine, "Roadmap", Map.of(), "Roadmap · Suko",
            outputDir.resolve("roadmap.html"));

        // Render component catalog index
        Path componentsOutDir = outputDir.resolve("components");
        Files.createDirectories(componentsOutDir);
        renderAndWrite(engine, "components/Index", Map.of(), "Components · Suko",
            componentsOutDir.resolve("index.html"));

        // Render individual component pages
        for (RegistryIndex.Entry entry : registry.components()) {
            String componentPath = "components/" + capitalize(entry.name());
            renderAndWrite(engine, componentPath, Map.of(),
                capitalize(entry.name()) + " · Suko", componentsOutDir.resolve(entry.name() + ".html"));
        }

        // Clean up temp directories
        deleteRecursively(tempSourceRoot);
        deleteRecursively(jteDir);
    }

    private RegistryIndex loadRegistry() throws IOException {
        Path registryJson = registryDir.resolve("registry.json");
        String json = Files.readString(registryJson, StandardCharsets.UTF_8);
        return RegistryJson.readIndex(json);
    }

    private ComponentManifest loadManifest(RegistryIndex.Entry entry) throws IOException {
        Path manifestPath = registryDir.resolve(entry.manifest());
        String json = Files.readString(manifestPath, StandardCharsets.UTF_8);
        return RegistryJson.readManifest(json);
    }

    /**
     * Generates a .sk file for a component detail page.
     * The generated .sk file, when compiled and rendered, produces HTML documentation for the component.
     */
    private String generateComponentDetailPage(RegistryIndex.Entry entry, ComponentManifest manifest) {
        String componentName = entry.name();
        String componentClassName = capitalize(componentName);
        String description = entry.description();
        String category = entry.category();

        // Extract parameter information from the component's .sk source
        List<ParamInfo> paramInfos = extractParamInfo(manifest);

        // Dependencies
        String deps = manifest != null && !manifest.dependsOn().isEmpty()
            ? String.join(", ", manifest.dependsOn())
            : "None";

        StringBuilder sb = new StringBuilder();
        sb.append("package components;\n\n");
        sb.append("component ").append(componentClassName).append("() {\n");
        sb.append("  <div class=\"min-h-screen bg-gray-50\">\n");
        sb.append("    <nav class=\"bg-teal-600 text-white py-6\">\n");
        sb.append("      <div class=\"max-w-7xl mx-auto px-4 flex flex-wrap items-center justify-between gap-4\">\n");
        sb.append("        <a href=\"../index.html\" class=\"text-2xl font-bold text-white\">Suko</a>\n");
        sb.append("        <div class=\"flex flex-wrap gap-6 text-sm font-medium text-teal-100\">\n");
        sb.append("          <a href=\"../getting-started.html\" class=\"hover:text-white\">Getting Started</a>\n");
        sb.append("          <a href=\"../language-reference.html\" class=\"hover:text-white\">Language Reference</a>\n");
        sb.append("          <a href=\"index.html\" class=\"text-white underline\">Components</a>\n");
        sb.append("          <a href=\"../roadmap.html\" class=\"hover:text-white\">Roadmap</a>\n");
        sb.append("        </div>\n");
        sb.append("      </div>\n");
        sb.append("    </nav>\n\n");
        sb.append("    <main class=\"max-w-7xl mx-auto px-4 py-12\">\n");
        sb.append("      <h2 class=\"text-4xl font-bold text-gray-900 mb-8\">").append(componentClassName).append("</h2>\n");
        sb.append("      <p class=\"text-xl text-gray-600 mb-8\">").append(description).append("</p>\n\n");

        // Parameter table
        sb.append("      <div class=\"bg-white rounded-lg border border-gray-200 p-6 mb-8\">\n");
        sb.append("        <h3 class=\"text-2xl font-bold text-gray-900 mb-4\">Parameters</h3>\n");
        sb.append("        <div class=\"overflow-x-auto\">\n");
        sb.append("          <table class=\"min-w-full divide-y divide-gray-200\">\n");
        sb.append("            <thead class=\"bg-gray-50\">\n");
        sb.append("              <tr>\n");
        sb.append("                <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Name</th>\n");
        sb.append("                <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Type</th>\n");
        sb.append("                <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Required</th>\n");
        sb.append("                <th class=\"px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider\">Default</th>\n");
        sb.append("              </tr>\n");
        sb.append("            </thead>\n");
        sb.append("            <tbody class=\"divide-y divide-gray-200\">\n");
        if (!paramInfos.isEmpty()) {
            for (ParamInfo info : paramInfos) {
                sb.append("                    <tr>\n");
                sb.append("                        <td class=\"pb-2 border-b py-2\">").append(info.name()).append("</td>\n");
                sb.append("                        <td class=\"pb-2 border-b py-2\">").append(info.type()).append("</td>\n");
                sb.append("                        <td class=\"pb-2 border-b py-2\">").append(info.required() ? "Yes" : "No").append("</td>\n");
                String defaultVal = info.defaultValue();
                sb.append("                        <td class=\"pb-2 border-b py-2\">").append(defaultVal).append("</td>\n");
                sb.append("                    </tr>\n");
            }
        } else {
            sb.append("                    <tr><td colspan=\"4\" class=\"pb-2 border-b py-2 text-center text-gray-500\">No parameters</td></tr>\n");
        }
        sb.append("            </tbody>\n");
        sb.append("          </table>\n");
        sb.append("        </div>\n");
        sb.append("      </div>\n\n");

        // Info section
        sb.append("      <div class=\"bg-white rounded-lg border border-gray-200 p-6\">\n");
        sb.append("        <h3 class=\"text-2xl font-bold text-gray-900 mb-4\">Info</h3>\n");
        sb.append("        <div class=\"space-y-4\">\n");
        sb.append("          <div class=\"flex\">\n");
        sb.append("            <span class=\"w-24 text-sm font-medium text-gray-700\">Category:</span>\n");
        sb.append("            <span class=\"text-sm text-gray-500\">").append(capitalize(category)).append("</span>\n");
        sb.append("          </div>\n");
        sb.append("          <div class=\"flex\">\n");
        sb.append("            <span class=\"w-24 text-sm font-medium text-gray-700\">Dependencies:</span>\n");
        sb.append("            <span class=\"text-sm text-gray-500\">").append(deps).append("</span>\n");
        sb.append("          </div>\n");
        sb.append("          <div class=\"flex\">\n");
        sb.append("            <span class=\"w-24 text-sm font-medium text-gray-700\">Version:</span>\n");
        sb.append("            <span class=\"text-sm text-gray-500\">").append(manifest != null ? manifest.version() : "unknown").append("</span>\n");
        sb.append("          </div>\n");
        sb.append("        </div>\n");
        sb.append("      </div>\n");
        sb.append("    </main>\n\n");
        sb.append("    <footer class=\"border-t border-gray-200 bg-white py-8\">\n");
        sb.append("      <div class=\"max-w-7xl mx-auto px-4 text-center text-gray-500 text-sm\">\n");
        sb.append("        <p>© 2026 Suko. Documentation generated with Suko.</p>\n");
        sb.append("      </div>\n");
        sb.append("    </footer>\n");
        sb.append("  </div>\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateComponentIndexPage(RegistryIndex registry, Map<String, ComponentManifest> manifests) {
        StringBuilder componentCards = new StringBuilder();
        String currentCategory = null;

        for (RegistryIndex.Entry entry : registry.components()) {
            String category = entry.category();
            if (!category.equals(currentCategory)) {
                if (currentCategory != null) {
                    componentCards.append("        </div>\n");
                    componentCards.append("      </section>\n");
                }
                currentCategory = category;
                componentCards.append("      <section class=\"mb-12\">\n");
                componentCards.append("        <h3 class=\"text-2xl font-bold text-gray-900 mb-6\">").append(capitalize(category)).append("</h3>\n");
                componentCards.append("        <div class=\"grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6\">\n");
            }

            componentCards.append("          <a href=\"").append(entry.name()).append(".html\" class=\"block bg-white rounded-lg border border-gray-200 p-6 hover:shadow-lg transition-shadow group\">\n");
            componentCards.append("            <div class=\"space-y-4\">\n");
            componentCards.append("              <h4 class=\"text-xl font-bold text-gray-900\">").append(capitalize(entry.name())).append("</h4>\n");
            componentCards.append("              <p class=\"text-gray-600\">").append(entry.description()).append("</p>\n");
            componentCards.append("            </div>\n");
            componentCards.append("          </a>\n");
        }

        if (currentCategory != null) {
            componentCards.append("        </div>\n");
            componentCards.append("      </section>\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("package components;\n\n");
        sb.append("component Index() {\n");
        sb.append("  <div class=\"min-h-screen bg-gray-50\">\n");
        sb.append("    <nav class=\"bg-teal-600 text-white py-6\">\n");
        sb.append("      <div class=\"max-w-7xl mx-auto px-4 flex flex-wrap items-center justify-between gap-4\">\n");
        sb.append("        <a href=\"../index.html\" class=\"text-2xl font-bold text-white\">Suko</a>\n");
        sb.append("        <div class=\"flex flex-wrap gap-6 text-sm font-medium text-teal-100\">\n");
        sb.append("          <a href=\"../getting-started.html\" class=\"hover:text-white\">Getting Started</a>\n");
        sb.append("          <a href=\"../language-reference.html\" class=\"hover:text-white\">Language Reference</a>\n");
        sb.append("          <a href=\".\" class=\"text-white underline\">Components</a>\n");
        sb.append("          <a href=\"../roadmap.html\" class=\"hover:text-white\">Roadmap</a>\n");
        sb.append("        </div>\n");
        sb.append("      </div>\n");
        sb.append("    </nav>\n\n");
        sb.append("    <main class=\"max-w-7xl mx-auto px-4 py-12\">\n");
        sb.append("      <h2 class=\"text-4xl font-bold text-gray-900 mb-8\">Component Library</h2>\n\n");
        sb.append("      <p class=\"text-xl text-gray-600 mb-8\">\n");
        sb.append("        Componentes reutilizáveis prontos para usar na sua aplicação.\n");
        sb.append("      </p>\n\n");
        sb.append("      <div class=\"mb-8 text-sm text-gray-500 text-center\">\n");
        sb.append("        Total: ").append(registry.components().size()).append(" componentes\n");
        sb.append("      </p>\n\n");
        sb.append(componentCards);
        sb.append("    </main>\n\n");
        sb.append("    <footer class=\"border-t border-gray-200 bg-white py-8\">\n");
        sb.append("      <div class=\"max-w-7xl mx-auto px-4 text-center text-gray-500 text-sm\">\n");
        sb.append("        <p>© 2026 Suko. Documentation generated with Suko.</p>\n");
        sb.append("      </div>\n");
        sb.append("    </footer>\n");
        sb.append("  </div>\n");
        sb.append("}\n");

        return sb.toString();
    }

    private List<ParamInfo> extractParamInfo(ComponentManifest manifest) {
        List<ParamInfo> paramInfos = new ArrayList<>();
        if (manifest == null || manifest.files().isEmpty()) {
            return paramInfos;
        }

        try {
            ComponentFile file = manifest.files().get(0);
            String source = Files.readString(registryDir.resolve(file.path()), StandardCharsets.UTF_8);
            SukoFile sukoFile = parseSk(source);
            for (ComponentDecl component : sukoFile.components()) {
                for (Param param : component.params()) {
                    paramInfos.add(extractParamInfo(param));
                }
            }
        } catch (Exception e) {
            // If parsing fails, return empty list - the UI will show "No parameters"
        }
        return paramInfos;
    }

    private ParamInfo extractParamInfo(Param param) {
        String name = param.name();
        String type = "-";
        boolean required = true;
        String defaultValue = "-";

        if (param instanceof Param.ValueParam vp) {
            type = formatJavaType(vp.type());
            required = !vp.defaultValue().isPresent();
            defaultValue = vp.defaultValue()
                .map(Expr::pretty)
                .orElse("-");
        } else if (param instanceof Param.SlotParam sp) {
            String cardinality = sp.cardinality() == Cardinality.MANY ? "Component[]" : "Component";
            type = cardinality;
            required = false;
            defaultValue = "-";
        }

        return new ParamInfo(name, type, required, defaultValue);
    }

    private static String formatJavaType(io.suko.lang.ast.Type type) {
        StringBuilder sb = new StringBuilder(type.name());
        if (!type.typeArguments().isEmpty()) {
            sb.append("<");
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(formatJavaType(type.typeArguments().get(i)));
            }
            sb.append(">");
        }
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static SukoFile parseSk(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoParser.CompilationUnitContext tree = parser.compilationUnit();
        return new SukoAstBuilder(source).build(tree);
    }

    private void renderAndWrite(TemplateEngine engine, String templateName, Map<String, Object> params,
            String title, Path outputFile) {
        TemplateOutput output = new StringOutput();
        engine.render(templateName + ".jte", params, output);
        int depth = outputDir.relativize(outputFile).getNameCount() - 1;
        String assetsPrefix = "../".repeat(depth) + "assets/";
        String html = wrapDocument(title, assetsPrefix, output.toString());
        try {
            Files.writeString(outputFile, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Error writing " + outputFile + ": " + e.getMessage(), e);
        }
    }

    /**
     * Envolve o fragmento renderizado num documento HTML5 completo. O CSS é
     * pré-compilado pelo Tailwind (tarefa Gradle `buildTailwindCss`, depois
     * deste programa correr) em vez de servido via CDN/JIT — a própria
     * Tailwind desaconselha o CDN em produção; o link aqui só assume que
     * `assets/site.css` vai existir quando essa tarefa correr a seguir.
     * O Alpine.js continua via CDN (exigido como externalRequirement pelo
     * Dialog — ver registry.json — e não vale a pena pré-compilar 15KB de JS
     * estático).
     */
    private static String wrapDocument(String title, String assetsPrefix, String bodyHtml) {
        return "<!doctype html>\n"
            + "<html lang=\"pt\">\n"
            + "<head>\n"
            + "<meta charset=\"UTF-8\">\n"
            + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
            + "<title>" + title + "</title>\n"
            + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n"
            + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n"
            + "<link href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">\n"
            + "<link rel=\"stylesheet\" href=\"" + assetsPrefix + "site.css\">\n"
            + "<script defer src=\"https://cdn.jsdelivr.net/npm/alpinejs@3.x.x/dist/cdn.min.js\"></script>\n"
            + "</head>\n"
            + "<body>\n"
            + bodyHtml
            + "\n</body>\n"
            + "</html>\n";
    }

    private void copyRecursively(Path src, Path dest) throws IOException {
        Files.walk(src).forEach(srcPath -> {
            try {
                Path relative = src.relativize(srcPath);
                Path destPath = dest.resolve(relative);
                if (Files.isDirectory(srcPath)) {
                    Files.createDirectories(destPath);
                } else {
                    Files.createDirectories(destPath.getParent());
                    Files.copy(srcPath, destPath);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void deleteRecursively(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted((a, b) -> b.toString().compareTo(a.toString()))
                .forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
        }
    }

    public static void main(String[] args) {
        if (args.length < 3) {
            System.err.println("Usage: WebsiteGenerator <projectDir> <sourceRoot> <outputDir> [<registryDir>]");
            System.exit(1);
        }
        try {
            Path projectDir = Path.of(args[0]);
            Path sourceRoot = Path.of(args[1]);
            Path outputDir = Path.of(args[2]);
            Path registryDir = args.length > 3
                ? Path.of(args[3])
                : projectDir.resolve("../suko-components");
            new WebsiteGenerator(projectDir, sourceRoot, outputDir, registryDir).generate();
            System.out.println("Website generated successfully at: " + outputDir);
        } catch (Exception e) {
            System.err.println("Error generating website: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Simple data class to hold parameter information */
    private static class ParamInfo {
        private final String name;
        private final String type;
        private final boolean required;
        private final String defaultValue;

        ParamInfo(String name, String type, boolean required, String defaultValue) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.defaultValue = defaultValue;
        }

        String name() { return name; }
        String type() { return type; }
        boolean required() { return required; }
        String defaultValue() { return defaultValue; }
    }
}
