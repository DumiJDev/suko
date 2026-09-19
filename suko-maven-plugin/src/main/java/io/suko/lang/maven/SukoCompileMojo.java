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
    private MavenProject project;

    /**
     * Directory containing .sk source files.
     */
    @Parameter(property = "suko.sourceDir", defaultValue = "${project.basedir}/src/main/suko")
    private File sourceDir;

    /**
     * Output directory for generated .jte files.
     */
    @Parameter(property = "suko.outputDir", defaultValue = "${project.build.directory}/generated-sources/suko")
    private File outputDir;

    /**
     * Package name for generated classes.
     */
    @Parameter(property = "suko.package", defaultValue = "io.suko.generated")
    private String generatedPackage;

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

        if (!sourceDir.exists()) {
            getLog().warn("Source directory does not exist: " + sourceDir);
            return;
        }

        try {
            Files.createDirectories(outputDir.toPath());

            io.suko.lang.project.SukoProjectCompiler.ProjectCompileResult result =
                new io.suko.lang.project.SukoProjectCompiler().compile(sourceDir.toPath());

            for (var entry : result.generatedJteSources().entrySet()) {
                Path jtePath = outputDir.toPath().resolve(entry.getKey());
                Files.createDirectories(jtePath.getParent());
                Files.writeString(jtePath, entry.getValue());
                getLog().info("Generated: " + jtePath);
            }

            for (var fileEntry : result.diagnosticsByFile().entrySet()) {
                for (var diag : fileEntry.getValue().getErrors()) {
                    getLog().error("[" + diag.severity() + "] " + fileEntry.getKey() + " - " + diag.code() + ": " + diag.message());
                }
            }

            if (!result.success()) {
                throw new MojoExecutionException("Suko compilation failed — see diagnostics above");
            }

            getLog().info("Successfully compiled project");
        } catch (java.io.IOException e) {
            throw new MojoExecutionException("Failed to compile Suko files", e);
        }
    }
}
