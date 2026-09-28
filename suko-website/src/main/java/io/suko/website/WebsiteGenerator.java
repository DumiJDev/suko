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
import io.suko.registry.ExternalRequirement;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Same "line + square station" motif as assets/favicon.svg. */
    private static final String NAV_LOGO_MARK = """
        <svg width="28" height="28" viewBox="0 0 32 32" aria-hidden="true"><rect width="32" height="32" fill="#0b1220"/><path d="M8 24 V14 H24" stroke="#2dd4bf" stroke-width="3" fill="none"/><rect x="6" y="22" width="4" height="4" fill="#2dd4bf"/><rect x="22" y="12" width="4" height="4" fill="#2dd4bf"/></svg>""";

    /**
     * Pages mark where the shared nav/footer go with empty placeholder
     * elements ({@code <nav data-nav="roadmap"></nav>},
     * {@code <footer data-footer="true"></footer>}); they are swapped for
     * the real markup after rendering, so the line map lives in one place
     * instead of being copied into every .sk page.
     */
    private static final Pattern NAV_PLACEHOLDER = Pattern.compile("<nav data-nav=\"([a-z-]+)\"></nav>");
    /**
     * Cache-busts site.css/site.js: GitHub Pages serves them with
     * max-age=600 under a fixed URL, so right after a deploy a browser could
     * pair new HTML with the previous CSS (new classes missing = unstyled
     * page). A per-build query string makes every deploy fetch fresh assets.
     */
    private static final String BUILD_ID = Long.toString(System.currentTimeMillis(), 36);

    private static final String FOOTER_PLACEHOLDER = "<footer data-footer=\"true\"></footer>";

    public void generate() throws IOException {
        // Clean output
        if (Files.exists(outputDir)) {
            deleteRecursively(outputDir);
        }
        Files.createDirectories(outputDir);

        // Static assets (favicon, site.js) are copied verbatim: JS braces and
        // `${}` would otherwise be parsed as Suko if they lived in a .sk page.
        Path staticDir = projectDir.resolve("src/main/static");
        if (Files.isDirectory(staticDir)) {
            copyRecursively(staticDir, outputDir);
        }

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
     * The site nav is the line map: four stops on one drawn line, each
     * marker in the color of the pipeline stage its page documents (CLI
     * lime, Core teal, Registry amber, Website rose). {@code depthPrefix}
     * is "" at the root and "../" inside components/; {@code current} is
     * the stop that gets {@code aria-current="page"}. "home" marks none.
     */
    private static String navHtml(String depthPrefix, String current) {
        StringBuilder sb = new StringBuilder();
        sb.append("<header class=\"bg-stone-50 border-b-2 border-slate-900\">\n");
        boolean home = current.equals("home");
        String layout = home ? "flex-row flex-wrap items-center" : "flex-col md:flex-row md:items-center";
        sb.append("  <div class=\"max-w-6xl mx-auto px-4 sm:px-6 py-4 flex ").append(layout).append(" justify-between gap-4\">\n");
        sb.append("    <a href=\"").append(depthPrefix).append("index.html\" class=\"font-display text-2xl font-extrabold text-slate-900 flex items-center gap-3\">")
            .append(NAV_LOGO_MARK).append("Suko</a>\n");
        if (home) {
            // From md up the home page draws the main line itself, larger, right
            // under the hero; on phones that is below the fold, so the header
            // keeps the line map there.
            sb.append("    <a href=\"https://github.com/DumiJDev/suko\" class=\"font-mono text-xs uppercase text-slate-700 underline underline-offset-4 hover:text-slate-900\">GitHub</a>\n");
        }
        sb.append("    <nav aria-label=\"Secções\" class=\"").append(home ? "w-full md:hidden" : "w-full md:w-[36rem]").append("\">\n");
        sb.append("      <ol class=\"navline\">\n");
        navStop(sb, depthPrefix + "getting-started.html", "Getting Started", "line-cli", current.equals("getting-started"));
        navStop(sb, depthPrefix + "language-reference.html", "Language Reference", "line-core", current.equals("language-reference"));
        navStop(sb, depthPrefix + "components/index.html", "Components", "line-registry", current.equals("components"));
        navStop(sb, depthPrefix + "roadmap.html", "Roadmap", "line-site", current.equals("roadmap"));
        sb.append("      </ol>\n");
        sb.append("    </nav>\n");
        sb.append("  </div>\n");
        sb.append("</header>\n");
        return sb.toString();
    }

    private static void navStop(StringBuilder sb, String href, String label, String line, boolean isCurrent) {
        sb.append("        <li><a href=\"").append(href).append("\" class=\"navstop ").append(line).append("\"")
            .append(isCurrent ? " aria-current=\"page\"" : "").append(">")
            .append("<span class=\"navstop-marker\"></span>").append(label).append("</a></li>\n");
    }

    private static String footerHtml() {
        return """
            <footer class="bg-slate-950 text-slate-400 py-8">
              <div class="max-w-6xl mx-auto px-4 sm:px-6 flex flex-wrap items-center justify-between gap-4 text-xs font-mono">
                <span class="addr-cell">suko 0.1.0-SNAPSHOT · pré-release</span>
                <p>Este site é compilado com Suko · <a href="https://github.com/DumiJDev/suko" class="text-teal-300 underline underline-offset-4 hover:text-white">GitHub</a></p>
              </div>
            </footer>
            """;
    }

    /** Swaps the nav/footer placeholders for the shared markup. */
    static String injectChrome(String html, String depthPrefix) {
        Matcher m = NAV_PLACEHOLDER.matcher(html);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(out, Matcher.quoteReplacement(navHtml(depthPrefix, m.group(1))));
        }
        m.appendTail(out);
        return out.toString().replace(FOOTER_PLACEHOLDER, footerHtml());
    }

    /**
     * Escapes arbitrary text for use as literal text content inside a
     * generated .sk page. Beyond HTML's &lt; &gt; &amp;, Suko itself would
     * interpret {@code $}/{@code {}} as interpolation, {@code //} as a
     * comment, {@code "} as a string start, and a bare {@code =} right after
     * a tag's {@code >} trips the lexer — so all of those become entities.
     */
    static String skText(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '$' -> b.append("&#36;");
                case '{' -> b.append("&#123;");
                case '}' -> b.append("&#125;");
                case '/' -> b.append("&#47;");
                case '=' -> b.append("&#61;");
                case '"' -> b.append("&quot;");
                case '@' -> b.append("&#64;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    /**
     * The component's real source as shipped by the registry, with `//`
     * comment lines dropped (they are maintainer notes; {@code suko add}
     * still copies them) and runs of blank lines collapsed.
     */
    private String displaySource(ComponentManifest manifest) {
        if (manifest == null || manifest.files().isEmpty()) {
            return null;
        }
        try {
            String source = Files.readString(registryDir.resolve(manifest.files().get(0).path()), StandardCharsets.UTF_8);
            StringBuilder out = new StringBuilder();
            boolean lastBlank = true;
            for (String line : source.split("\n", -1)) {
                if (line.strip().startsWith("//")) {
                    continue;
                }
                boolean blank = line.isBlank();
                if (blank && lastBlank) {
                    continue;
                }
                out.append(line.stripTrailing()).append('\n');
                lastBlank = blank;
            }
            return out.toString().strip();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Generates a .sk file for a component detail page. Every component
     * lives on the Registry line (amber); its sections are stations on that
     * line: install, parameters, requirements, real source.
     */
    private String generateComponentDetailPage(RegistryIndex.Entry entry, ComponentManifest manifest) {
        String componentName = entry.name();
        String componentClassName = capitalize(componentName);
        List<ParamInfo> paramInfos = extractParamInfo(manifest);
        boolean hasDeps = manifest != null && !manifest.dependsOn().isEmpty();

        StringBuilder sb = new StringBuilder();
        sb.append("package components;\n\n");
        sb.append("component ").append(componentClassName).append("() {\n");
        sb.append("  <div class=\"min-h-screen flex flex-col line-registry\">\n");
        sb.append("    <nav data-nav=\"components\"></nav>\n");
        sb.append("    <main class=\"flex-1 w-full max-w-5xl mx-auto px-4 sm:px-6 py-14\">\n");
        sb.append("      <a href=\"index.html\" class=\"text-link font-mono text-xs uppercase\">Component Library</a>\n");
        sb.append("      <h1 class=\"font-display text-5xl font-extrabold text-slate-900 mt-6 mb-3\">").append(componentClassName).append("</h1>\n");
        sb.append("      <p class=\"text-xl text-slate-600 max-w-2xl mb-4\">").append(skText(entry.description())).append("</p>\n");
        sb.append("      <p class=\"flex flex-wrap gap-2 mb-14\">");
        sb.append("<span class=\"addr-cell\">v").append(skText(manifest != null ? manifest.version() : "?")).append("</span>");
        sb.append("<span class=\"addr-cell\">categoria ").append(skText(entry.category())).append("</span>");
        sb.append("</p>\n\n");

        sb.append("      <ol class=\"route\">\n");

        // 01 Install: the one command that puts this component's source in your project.
        sb.append("        <li>\n");
        sb.append("          <h2 class=\"station-heading\"><span class=\"station-marker\"></span>Instalar</h2>\n");
        sb.append("          <pre class=\"code-block\">suko add ").append(skText(componentName)).append("</pre>\n");
        sb.append("          <p class=\"text-slate-600 mt-3\">Copia o <code>.sk</code> para o seu <code>sourceRoot</code>");
        if (hasDeps) {
            sb.append(", junto com as dependências (").append(skText(String.join(", ", manifest.dependsOn()))).append(")");
        }
        sb.append(". Como obter a CLI: <a href=\"../getting-started.html\" class=\"text-link\">Getting Started</a>.</p>\n");
        sb.append("        </li>\n");

        // 02 Parameters: a flat, addressed, monospace grid.
        sb.append("        <li>\n");
        sb.append("          <h2 class=\"station-heading\"><span class=\"station-marker\"></span>Parâmetros</h2>\n");
        sb.append("          <div class=\"overflow-x-auto\">\n");
        sb.append("            <table class=\"w-full text-sm bg-white border-2 border-slate-900\">\n");
        sb.append("              <thead>\n");
        sb.append("                <tr class=\"border-b-2 border-slate-900 bg-stone-100 text-left font-display\">\n");
        sb.append("                  <th class=\"py-2 px-4\">Nome</th>\n");
        sb.append("                  <th class=\"py-2 px-4\">Tipo</th>\n");
        sb.append("                  <th class=\"py-2 px-4\">Obrigatório</th>\n");
        sb.append("                  <th class=\"py-2 px-4\">Por omissão</th>\n");
        sb.append("                </tr>\n");
        sb.append("              </thead>\n");
        sb.append("              <tbody class=\"font-mono\">\n");
        if (!paramInfos.isEmpty()) {
            for (ParamInfo info : paramInfos) {
                sb.append("                <tr class=\"border-b border-slate-200\">\n");
                sb.append("                  <td class=\"py-2 px-4 font-medium text-slate-900\">").append(skText(info.name())).append("</td>\n");
                sb.append("                  <td class=\"py-2 px-4 text-slate-600\">").append(skText(info.type())).append("</td>\n");
                sb.append("                  <td class=\"py-2 px-4\">").append(info.required() ? "sim" : "não").append("</td>\n");
                sb.append("                  <td class=\"py-2 px-4 text-slate-600\">").append(skText(info.defaultValue())).append("</td>\n");
                sb.append("                </tr>\n");
            }
        } else {
            sb.append("                <tr><td colspan=\"4\" class=\"py-4 px-4 text-slate-500\">Sem parâmetros</td></tr>\n");
        }
        sb.append("              </tbody>\n");
        sb.append("            </table>\n");
        sb.append("          </div>\n");
        sb.append("        </li>\n");

        // 03 Requirements: what the consumer's own app must load; Suko bundles none of it.
        sb.append("        <li>\n");
        sb.append("          <h2 class=\"station-heading\"><span class=\"station-marker\"></span>Requisitos</h2>\n");
        sb.append("          <dl class=\"grid grid-cols-[9rem_1fr] gap-y-2 text-sm\">\n");
        sb.append("            <dt class=\"text-slate-500\">Dependências</dt>\n");
        sb.append("            <dd class=\"font-mono text-slate-900\">");
        if (hasDeps) {
            for (String dep : manifest.dependsOn()) {
                sb.append("<a href=\"").append(dep).append(".html\" class=\"text-link mr-3\">")
                    .append(skText(capitalize(dep))).append("</a>");
            }
        } else {
            sb.append("nenhuma");
        }
        sb.append("</dd>\n");
        sb.append("            <dt class=\"text-slate-500\">Na sua app</dt>\n");
        sb.append("            <dd class=\"font-mono text-slate-900\">");
        if (manifest != null && manifest.externalRequirements() != null && !manifest.externalRequirements().isEmpty()) {
            List<String> reqs = new ArrayList<>();
            for (ExternalRequirement req : manifest.externalRequirements()) {
                reqs.add(req.id() + " " + req.versionRange());
            }
            sb.append(skText(String.join(" · ", reqs)));
        } else {
            sb.append("nada além do JTE");
        }
        sb.append("</dd>\n");
        sb.append("          </dl>\n");
        sb.append("        </li>\n");

        // 04 Source: the real registry file, so this page can never drift from what `suko add` copies.
        String source = displaySource(manifest);
        if (source != null) {
            sb.append("        <li>\n");
            sb.append("          <h2 class=\"station-heading\"><span class=\"station-marker\"></span>Código-fonte</h2>\n");
            sb.append("          <pre class=\"code-block\">").append(skText(source)).append("</pre>\n");
            sb.append("          <p class=\"text-slate-500 text-sm mt-3\">O ficheiro real do registry, sem os comentários.</p>\n");
            sb.append("        </li>\n");
        }
        sb.append("      </ol>\n");
        sb.append("    </main>\n");
        sb.append("    <footer data-footer=\"true\"></footer>\n");
        sb.append("  </div>\n");
        sb.append("}\n");

        return sb.toString();
    }

    /** The component index: the Registry line, one station per component. */
    private String generateComponentIndexPage(RegistryIndex registry, Map<String, ComponentManifest> manifests) {
        StringBuilder sb = new StringBuilder();
        sb.append("package components;\n\n");
        sb.append("component Index() {\n");
        sb.append("  <div class=\"min-h-screen flex flex-col line-registry\">\n");
        sb.append("    <nav data-nav=\"components\"></nav>\n");
        sb.append("    <main class=\"flex-1 w-full max-w-5xl mx-auto px-4 sm:px-6 py-14\">\n");
        sb.append("      <h1 class=\"font-display text-5xl font-extrabold text-slate-900 mb-4\">Component Library</h1>\n");
        sb.append("      <p class=\"text-xl text-slate-600 max-w-2xl mb-14\">\n");
        sb.append("        ").append(registry.components().size())
            .append(" componentes na linha do registry. Cada um mostra os parâmetros e o código-fonte real, e entra no seu projeto com um comando.\n");
        sb.append("      </p>\n\n");
        sb.append("      <ol class=\"route space-y-10\">\n");
        for (RegistryIndex.Entry entry : registry.components()) {
            ComponentManifest manifest = manifests.get(entry.name());
            sb.append("        <li>\n");
            sb.append("          <span class=\"station-marker\"></span>\n");
            sb.append("          <h2 class=\"font-display text-2xl font-extrabold mb-1\"><a href=\"").append(entry.name())
                .append(".html\" class=\"text-slate-900 underline decoration-2 underline-offset-4 decoration-transparent hover:decoration-amber-600\">")
                .append(capitalize(entry.name())).append("</a></h2>\n");
            sb.append("          <p class=\"text-slate-600 max-w-2xl mb-3\">").append(skText(entry.description())).append("</p>\n");
            sb.append("          <p class=\"flex flex-wrap items-center gap-3 text-sm\"><code class=\"addr-cell\">suko add ").append(skText(entry.name()))
                .append("</code><span class=\"font-mono text-slate-500\">").append(skText(entry.category()))
                .append(" · v").append(skText(manifest != null ? manifest.version() : "?")).append("</span></p>\n");
            sb.append("        </li>\n");
        }
        sb.append("      </ol>\n");
        sb.append("    </main>\n");
        sb.append("    <footer data-footer=\"true\"></footer>\n");
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
        String depthPrefix = "../".repeat(depth);
        String html = wrapDocument(title, depthPrefix + "assets/", injectChrome(output.toString(), depthPrefix));
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
            + "<link rel=\"icon\" type=\"image/svg+xml\" href=\"" + assetsPrefix + "favicon.svg\">\n"
            + "<link rel=\"preconnect\" href=\"https://fonts.googleapis.com\">\n"
            + "<link rel=\"preconnect\" href=\"https://fonts.gstatic.com\" crossorigin>\n"
            + "<link href=\"https://fonts.googleapis.com/css2?family=Overpass:wght@600;700;800&family=IBM+Plex+Sans:wght@400;500;600&family=JetBrains+Mono:wght@400;500&display=swap\" rel=\"stylesheet\">\n"
            + "<link rel=\"stylesheet\" href=\"" + assetsPrefix + "site.css?v=" + BUILD_ID + "\">\n"
            + "<script defer src=\"" + assetsPrefix + "site.js?v=" + BUILD_ID + "\"></script>\n"
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
