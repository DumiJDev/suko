package io.suko.lang.gradle;

import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskAction;

/**
 * Plugin Gradle para compilação de arquivos .sk (Suko).
 */
public class SukoGradlePlugin implements Plugin<Project> {

    @Override
    public void apply(Project project) {
        project.getExtensions().create("suko", SukoExtension.class);

        project.getTasks().register("sukoCompile", SukoCompileTask.class, task -> {
            task.setDescription("Compila arquivos .sk para .jte");
            task.setGroup("build");
            task.extension = project.getExtensions().getByType(SukoExtension.class);
        });

        project.getTasks().register("sukoWatch", SukoWatchTask.class, task -> {
            task.setDescription("Modo watch: recompila .sk em alterações");
            task.setGroup("build");
            task.extension = project.getExtensions().getByType(SukoExtension.class);
        });
    }
}