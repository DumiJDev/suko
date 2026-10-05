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
        extension.getTargets().convention(java.util.List.of("jte"));
        extension.getGeneratedPackage().convention(
            "io.suko.generated." + io.suko.ext.SecurityOptions.sanitizePackageSegment(project.getName()));
        extension.getGeneratedJavaDir().convention("build/generated-src/suko-java");
        extension.getSecurity().getJtePolicy().convention(true);

        project.getPluginManager().withPlugin("java", p -> {
            var sourceSets = project.getExtensions().getByType(org.gradle.api.tasks.SourceSetContainer.class);
            sourceSets.getByName("main").getJava().srcDir(
                project.files((java.util.concurrent.Callable<Object>) () -> extension.getGeneratedJavaDirAsPath().toFile())
                    .builtBy("sukoCompile"));
            project.getTasks().named("compileJava", t -> t.dependsOn("sukoCompile"));
        });

        // M4: política do JTE (defesa em profundidade).
        project.getPluginManager().withPlugin("gg.jte.gradle", p -> applyJtePolicy(project, extension));

        var sukoExtensions = project.getConfigurations().create("sukoExtensions", c -> {
            c.setCanBeConsumed(false);
            c.setCanBeResolved(true);
            c.setDescription("Extensões de compile-time do Suko (alvos, vocabulários, checkers)");
        });

        project.getTasks().register("sukoCompile", SukoCompileTask.class, task -> {
            task.setDescription("Compila arquivos .sk para .jte");
            task.setGroup("build");
            task.extension = project.getExtensions().getByType(SukoExtension.class);
            task.extensionClasspath = sukoExtensions;
        });

        project.getTasks().register("sukoWatch", SukoWatchTask.class, task -> {
            task.setDescription("Modo watch: recompila .sk em alterações");
            task.setGroup("build");
            task.extension = project.getExtensions().getByType(SukoExtension.class);
            task.extensionClasspath = sukoExtensions;
        });
    }

    static final String JTE_POLICY_CLASS = "gg.jte.html.OwaspHtmlPolicy";

    /**
     * Liga a política HTML do JTE como convenção preguiçosa (lê {@code jtePolicy} só quando o valor é
     * pedido): um valor do utilizador em {@code jte { htmlPolicyClass = ... }} vence sempre e
     * {@code jtePolicy = false} desliga a convenção. Reflexão para o plugin Suko não depender do plugin do JTE.
     */
    @SuppressWarnings("unchecked")
    static void applyJtePolicy(Project project, SukoExtension extension) {
        Object jte = project.getExtensions().findByName("jte");
        if (jte == null) {
            project.getLogger().warn("suko: o plugin gg.jte.gradle não registou a extensão 'jte'; política HTML do JTE não aplicada");
            return;
        }
        try {
            var getter = jte.getClass().getMethod("getHtmlPolicyClass");
            var prop = (org.gradle.api.provider.Property<String>) getter.invoke(jte);
            prop.convention(extension.getSecurity().getJtePolicy().map(on -> on ? JTE_POLICY_CLASS : null));
        } catch (NoSuchMethodException e) {
            project.getLogger().warn("suko: esta versão do plugin do JTE não tem 'htmlPolicyClass'; "
                + "política HTML do JTE não aplicada (suko.security.jtePolicy)");
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Só é erro se o utilizador pediu a política (jtePolicy, por omissão true).
            Runnable check = () -> {
                if (extension.getSecurity().getJtePolicy().get()) {
                    throw new org.gradle.api.GradleException(
                        "suko: não foi possível ativar a política HTML do JTE (suko.security.jtePolicy=true): " + e, e);
                }
            };
            if (project.getState().getExecuted()) {
                check.run();
            } else {
                project.afterEvaluate(pr -> check.run());
            }
        }
    }
}