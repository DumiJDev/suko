package io.suko.lang.gradle;

import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.nio.file.Path;

public class SukoExtension {

    private final ProjectLayout projectLayout;
    private final Property<String> sourceDir;
    private final Property<String> outputDir;
    private final ListProperty<String> targets;
    private final Property<String> generatedPackage;
    private final Property<String> generatedJavaDir;
    private final SukoSecurity security;

    // D11: o construtor sem argumentos que existia aqui (com uma
    // implementação manual de Property<T>, TestProperty, só para servir
    // esse construtor) foi removido. Nenhum teste do módulo instancia
    // SukoExtension diretamente (grep confirmou) — todos passam pelo
    // Project real (ProjectBuilder ou TestKit), que já injeta o
    // ObjectFactory através do construtor @Inject abaixo. Manter o
    // construtor sem argumentos era pior do que inútil: era ativamente
    // perigoso, porque o instanciador de extensões do Gradle preferia-o ao
    // construtor @Inject sempre que ambos existiam, e a TestProperty.
    // convention(...) era um no-op silencioso — foi isso, e não uma falha
    // de configuração, que fez as convenções de sourceDir/outputDir fixadas
    // em SukoGradlePlugin.apply não pegarem (isPresent()==false mesmo
    // depois de convention(...)).
    @Inject
    public SukoExtension(ObjectFactory objectFactory, ProjectLayout projectLayout) {
        this.projectLayout = projectLayout;
        this.sourceDir = objectFactory.property(String.class);
        this.outputDir = objectFactory.property(String.class);
        this.targets = objectFactory.listProperty(String.class);
        this.generatedPackage = objectFactory.property(String.class);
        this.generatedJavaDir = objectFactory.property(String.class);
        this.security = objectFactory.newInstance(SukoSecurity.class);
    }

    public Property<String> getSourceDir() {
        return sourceDir;
    }

    public ListProperty<String> getTargets() {
        return targets;
    }

    public Path getExtensionManifestPath() {
        return projectLayout.getBuildDirectory().file("suko/extensions.json").get().getAsFile().toPath();
    }

    public Property<String> getOutputDir() {
        return outputDir;
    }

    /**
     * Resolvido via {@link ProjectLayout#getProjectDirectory()}, não via
     * {@code Path.of(String)} bruto — um {@code Path.of} bruto é relativo ao
     * {@code user.dir} do processo (o daemon do Gradle), não ao diretório do
     * projeto, e diverge dele sempre que o consumidor define
     * {@code suko { sourceDir = "..." }} no seu próprio build script com uma
     * string relativa (confirmado por um teste funcional real com o plugin
     * aplicado por ID via TestKit — a convenção por omissão, definida uma
     * vez aqui dentro com o mesmo {@code projectDirectory.dir(...)}, sempre
     * escapou ao bug só porque nunca sofria esse override). {@code
     * Directory.dir(String)} resolve tanto caminhos relativos como
     * absolutos corretamente, por isso este método serve os dois casos.
     */
    public Path getSourceDirAsPath() {
        return projectLayout.getProjectDirectory().dir(getSourceDir().get()).getAsFile().toPath();
    }

    public Path getOutputDirAsPath() {
        return projectLayout.getProjectDirectory().dir(getOutputDir().get()).getAsFile().toPath();
    }

    public Property<String> getGeneratedPackage() {
        return generatedPackage;
    }

    public Property<String> getGeneratedJavaDir() {
        return generatedJavaDir;
    }

    public SukoSecurity getSecurity() {
        return security;
    }

    public void security(org.gradle.api.Action<SukoSecurity> action) {
        action.execute(security);
    }

    public Path getGeneratedJavaDirAsPath() {
        return projectLayout.getProjectDirectory().dir(getGeneratedJavaDir().get()).getAsFile().toPath();
    }

    public Path getSecurityAuditPath() {
        return projectLayout.getBuildDirectory().file("suko/security-audit.json").get().getAsFile().toPath();
    }

    public io.suko.ext.SecurityOptions securityOptions() {
        try {
            io.suko.ext.SecurityOptions o = io.suko.ext.SecurityOptions.DEFAULT
                .withGeneratedPackage(generatedPackage.get())
                .withStrictCsp(security.getStrictCsp().getOrElse(false))
                .withImageDataTypes(new java.util.TreeSet<>(security.getImageDataTypes().getOrElse(java.util.List.of())))
                .withCodeAttributes(new java.util.TreeSet<>(security.getCodeAttributes().getOrElse(java.util.List.of())))
                .withUrlAttributes(new java.util.TreeSet<>(security.getUrlAttributes().getOrElse(java.util.List.of())));
            java.util.List<String> schemes = security.getUrlSchemes().getOrElse(java.util.List.of());
            return schemes.isEmpty() ? o : o.withUrlSchemes(new java.util.TreeSet<>(schemes));
        } catch (IllegalArgumentException e) {
            throw new org.gradle.api.GradleException("Configuração suko.security inválida: " + e.getMessage(), e);
        }
    }
}