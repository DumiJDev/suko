package io.suko.lang.maven;

import io.suko.lang.JteCompiler;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
        getLog().info("Source directory: {}", sourceDir);
        getLog().info("Output directory: {}", outputDir);

        if (!sourceDir.exists()) {
            getLog().warn("Source directory does not exist: {}", sourceDir);
            return;
        }

        try {
            Files.createDirectories(outputDir.toPath());

            List<File> skFiles = findSkFiles(sourceDir);
            if (skFiles.isEmpty()) {
                getLog().info("No .sk files found in {}", sourceDir);
                return;
            }

            for (File skFile : skFiles) {
                compileFile(skFile, outputDir);
            }

            getLog().info("Successfully compiled {} .sk files", skFiles.size());

        } catch (Exception e) {
            throw new MojoExecutionException("Failed to compile Suko files", e);
        }
    }

    private List<File> findSkFiles(File sourceDir) {
        try (Stream<Path> paths = Files.walk(sourceDir.toPath())) {
            return paths
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .map(Path::toFile)
                .collect(Collectors.toList());
        } catch (Exception e) {
            throw new RuntimeException("Failed to list .sk files", e);
        }
    }

    private void compileFile(File skFile, File outputDir) throws Exception {
        String source = Files.readString(skFile.toPath());
        String fileName = skFile.getName();

        getLog().info("Compiling: {}", fileName);

        JteCompiler compiler = new JteCompiler(fileName, source);
        JteCompiler.CompileResult result = compiler.compile();

        if (result.success()) {
            for (var entry : result.generatedJteSources().entrySet()) {
                Path jtePath = outputDir.toPath().resolve(entry.getKey());
                Files.createDirectories(jtePath.getParent());
                Files.writeString(jtePath, entry.getValue());
                getLog().info("Generated: {}", jtePath);
            }
        } else {
            getLog().error("Compilation failed for " + fileName + ": " + result.diagnostics().getErrors().size() + " errors");
            for (var diag : result.diagnostics().getErrors()) {
                getLog().error("  [" + diag.severity() + "] " + diag.code() + ": " + diag.message() + " (" + diag.span() + ")");
            }
        }
    }
}