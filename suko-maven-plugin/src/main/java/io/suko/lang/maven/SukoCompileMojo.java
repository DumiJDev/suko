package io.suko.lang.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Maven Mojo that compiles .sk (Suko) files to .jte templates.
 *
 * <p>Usage: mvn suko:compile</p>
 */
@Mojo(name = "compile", defaultPhase = LifecyclePhase.GENERATE_SOURCES,
      threadSafe = true, requiresProject = true)
public class SukoCompileMojo extends AbstractMojo {

    /**
     * The Maven project.
     */
    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    MavenProject project;

    /**
     * Directory containing .sk source files.
     */
    @Parameter(property = "suko.sourceDir", defaultValue = "${project.basedir}/src/main/suko")
    File sourceDir;

    /**
     * Output directory for generated .jte files.
     */
    @Parameter(property = "suko.outputDir", defaultValue = "${project.build.directory}/generated-sources/suko")
    File outputDir;

    /**
     * Package name for generated classes.
     */
    @Parameter(property = "suko.package")
    String generatedPackage;

    /**
     * Output directory for generated Java sources (SukoSafe.java); registered as a compile source root.
     * Kept separate from outputDir so Java never lands in the root of the .jte templates.
     */
    @Parameter(property = "suko.generatedJavaDir", defaultValue = "${project.build.directory}/generated-sources/suko-java")
    File generatedJavaDir;

    /** Security configuration (urlSchemes, imageDataTypes, codeAttributes, urlAttributes, strictCsp). */
    @Parameter
    Security security;

    /** Só lido quando {@code project == null} (Mojo construída à mão em testes). Não é um parâmetro. */
    String artifactIdForTests;

    /** POJO de configuração {@code <security>}. */
    public static class Security {
        public java.util.List<String> urlSchemes;
        public java.util.List<String> imageDataTypes;
        public java.util.List<String> codeAttributes;
        public java.util.List<String> urlAttributes;
        public boolean strictCsp;
    }

    @Parameter(property = "suko.targets", defaultValue = "jte")
    java.util.List<String> targets;

    @Parameter(defaultValue = "${project.build.directory}", readonly = true)
    File buildDirectory;

    String effectiveGeneratedPackage() {
        if (generatedPackage != null && !generatedPackage.isBlank()) {
            return generatedPackage;
        }
        String artifactId = project != null ? project.getArtifactId() : artifactIdForTests;
        return "io.suko.generated." + io.suko.ext.SecurityOptions.sanitizePackageSegment(artifactId == null ? "app" : artifactId);
    }

    void registerSourceRoot(File dir) {
        if (project != null) {
            project.addCompileSourceRoot(dir.getAbsolutePath());
        }
    }

    io.suko.ext.SecurityOptions securityOptions() throws MojoExecutionException {
        try {
            io.suko.ext.SecurityOptions o = io.suko.ext.SecurityOptions.DEFAULT.withGeneratedPackage(effectiveGeneratedPackage());
            if (security != null) {
                o = o.withStrictCsp(security.strictCsp);
                if (security.imageDataTypes != null) o = o.withImageDataTypes(new java.util.TreeSet<>(security.imageDataTypes));
                if (security.codeAttributes != null) o = o.withCodeAttributes(new java.util.TreeSet<>(security.codeAttributes));
                if (security.urlAttributes != null) o = o.withUrlAttributes(new java.util.TreeSet<>(security.urlAttributes));
                if (security.urlSchemes != null && !security.urlSchemes.isEmpty()) o = o.withUrlSchemes(new java.util.TreeSet<>(security.urlSchemes));
            }
            return o;
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Configuração suko security inválida: " + e.getMessage(), e);
        }
    }

    /** O jte-maven-plugin não é alterado: só se avisa quando falta a política HTML do JTE. */
    static void warnIfJtePolicyMissing(java.util.List<org.apache.maven.model.Plugin> plugins,
                                       org.apache.maven.plugin.logging.Log log) {
        if (plugins == null) {
            return;
        }
        for (org.apache.maven.model.Plugin p : plugins) {
            if (!"gg.jte".equals(p.getGroupId()) || !"jte-maven-plugin".equals(p.getArtifactId())) {
                continue;
            }
            boolean has = hasHtmlPolicy(p.getConfiguration());
            if (!has && p.getExecutions() != null) {
                for (org.apache.maven.model.PluginExecution ex : p.getExecutions()) {
                    has |= hasHtmlPolicy(ex.getConfiguration());
                }
            }
            if (!has) {
                log.warn("htmlPolicyClass não encontrado na configuração do jte-maven-plugin: configure <htmlPolicyClass>gg.jte.html.OwaspHtmlPolicy</htmlPolicyClass> "
                    + "para que o JTE rejeite em runtime atributos/tags HTML perigosos (defesa em profundidade do Suko).");
            }
        }
    }

    private static boolean hasHtmlPolicy(Object cfg) {
        if (cfg instanceof org.codehaus.plexus.util.xml.Xpp3Dom dom) {
            org.codehaus.plexus.util.xml.Xpp3Dom child = dom.getChild("htmlPolicyClass");
            return child != null && child.getValue() != null && !child.getValue().isBlank();
        }
        return false;
    }

    private void deleteStaleAudit() throws java.io.IOException {
        if (buildDirectory != null) {
            Files.deleteIfExists(buildDirectory.toPath().resolve("suko/security-audit.json"));
        }
    }

    /** O classloader do plugin: as extensões declaradas em {@code <plugin><dependencies>} já estão nele. */
    ClassLoader extensionLoader() {
        return SukoCompileMojo.class.getClassLoader();
    }

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("Suko Maven Plugin - Compiling .sk files to .jte");
        // NOTE (subprojeto 5, Tarefa 7): org.apache.maven.plugin.logging.Log só tem
        // info(CharSequence)/info(CharSequence, Throwable)/info(Throwable) — não
        // suporta placeholders "{}" ao estilo SLF4J. O código anterior a esta tarefa
        // (incluindo o texto literal do plano) usava esse estilo e nunca tinha
        // compilado (este módulo nunca esteve ligado ao build — ver settings.gradle.kts
        // novo e o comentário em suko-maven-plugin/build.gradle.kts). Usa-se
        // concatenação de String em vez de placeholders.
        getLog().info("Source directory: " + sourceDir);
        getLog().info("Output directory: " + outputDir);

        if (project != null) {
            warnIfJtePolicyMissing(project.getBuildPlugins(), getLog());
        }

        if (!sourceDir.exists()) {
            getLog().warn("Source directory does not exist: " + sourceDir);
            try {
                deleteStaleAudit(); // não deixar uma auditoria antiga a parecer atual
            } catch (java.io.IOException e) {
                throw new MojoExecutionException("Failed to delete stale security audit", e);
            }
            return;
        }

        try {
            deleteStaleAudit();
            io.suko.ext.SecurityOptions securityOptions = securityOptions();
            Files.createDirectories(outputDir.toPath());

            java.util.List<String> requested = io.suko.lang.JteCompiler.normalizeTargets(targets == null ? java.util.List.of() : targets);
            ClassLoader loader = extensionLoader();
            io.suko.lang.ext.ExtensionRegistry registry = io.suko.lang.ext.ExtensionRegistry.load(loader);
            // Maven injeta sempre buildDirectory; null só ocorre em testes que instanciam a Mojo à mão.
            if (buildDirectory != null) {
                io.suko.lang.ext.ExtensionManifest.write(buildDirectory.toPath().resolve("suko/extensions.json"),
                    io.suko.lang.ext.ExtensionManifest.extensionJars(loader), requested);
            }
            var compiler = new io.suko.lang.project.SukoProjectCompiler(registry, requested, securityOptions);
            for (var d : compiler.projectDiagnostics()) {
                getLog().error("[" + d.severity() + "] " + d.code() + ": " + d.message());
            }
            io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
                compiler.compile(sourceDir.toPath());

            for (var entry : result.generatedJteSources().entrySet()) {
                Path jtePath = outputDir.toPath().resolve(entry.getKey());
                Files.createDirectories(jtePath.getParent());
                Files.writeString(jtePath, entry.getValue());
                getLog().info("Generated: " + jtePath);
            }

            Path javaDir = generatedJavaDir != null ? generatedJavaDir.toPath()
                : outputDir.toPath().resolveSibling("suko-java");
            Path manifest = (buildDirectory != null ? buildDirectory.toPath().resolve("suko")
                : outputDir.toPath().resolveSibling("suko-state")).resolve("java-outputs.txt");
            io.suko.lang.project.ProjectOutputWriter.write(result, outputDir.toPath(), javaDir, manifest);
            registerSourceRoot(javaDir.toFile());

            for (var fileEntry : result.diagnosticsByFile().entrySet()) {
                for (var diag : fileEntry.getValue().getDiagnostics()) {
                    String line = "[" + diag.severity() + "] " + fileEntry.getKey() + " - " + diag.code() + ": " + diag.message();
                    switch (diag.severity()) {
                        case ERROR -> getLog().error(line);
                        case WARNING -> getLog().warn(line);
                        default -> getLog().info(line);
                    }
                }
            }

            // Escrita mesmo em falha: reflete o estado atual (nunca uma auditoria de uma execução anterior).
            if (buildDirectory != null) {
                io.suko.lang.ext.SecurityAudit.write(buildDirectory.toPath().resolve("suko/security-audit.json"),
                    result.diagnosticsByFile());
            }

            if (!result.success()) {
                throw new MojoExecutionException("Suko compilation failed — see diagnostics above");
            }

            getLog().info("Successfully compiled project");
        } catch (java.io.IOException | java.io.UncheckedIOException | IllegalStateException e) {
            throw new MojoExecutionException("Failed to compile Suko files", e);
        }
    }
}
