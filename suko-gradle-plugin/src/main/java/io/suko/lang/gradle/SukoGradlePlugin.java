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
        SukoExtension extension = project.getExtensions().create("suko", SukoExtension.class);

        // D11: convenções fixas em vez de deixar a extensão sem default.
        // Até aqui não existia no código um "source root canónico" para
        // projetos Suko; é desse default que a CLI (Tarefa 7, `suko init`)
        // depende para propor uma estrutura em vez de perguntar às cegas.
        //
        // Resolvidas para caminho absoluto (via project.getLayout()) e não
        // deixadas como a string literal relativa "src/main/suko": a task
        // (SukoExtension.getSourceDirAsPath()) faz Path.of(string), que é
        // relativo ao user.dir do processo — não necessariamente o
        // diretório do projeto (confirmado pelo teste funcional via
        // TestKit, onde os dois divergem). Resolver aqui, uma vez, evita
        // esse desalinhamento sem mudar a convenção nominal.
        extension.getSourceDir().convention(
            project.getLayout().getProjectDirectory().dir("src/main/suko").getAsFile().getPath());
        extension.getOutputDir().convention(
            project.getLayout().getBuildDirectory().dir("generated-src/suko").get().getAsFile().getPath());

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