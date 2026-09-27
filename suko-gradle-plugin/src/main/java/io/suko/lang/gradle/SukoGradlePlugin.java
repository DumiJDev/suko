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
        // Strings relativas simples: SukoExtension.getSourceDirAsPath()/
        // getOutputDirAsPath() agora resolvem sempre via
        // ProjectLayout.getProjectDirectory().dir(...), que trata caminhos
        // relativos E absolutos corretamente — não há razão para pré-resolver
        // para absoluto aqui. (Antes desta correção, essas duas funções
        // faziam Path.of(string) bruto — relativo ao user.dir do processo,
        // não ao diretório do projeto — e só a convenção escapava ao bug por
        // vir pré-resolvida para absoluto; qualquer `suko { sourceDir = "..." }`
        // no build script do consumidor, com uma string relativa, quebrava
        // silenciosamente. Descoberto por um smoke test real de integração
        // Spring Boot que precisava de outputDir = "src/main/jte".)
        extension.getSourceDir().convention("src/main/suko");
        extension.getOutputDir().convention("build/generated-src/suko");

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