package io.suko.lang.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.nio.file.Path;

public class SukoExtension {

    private final Property<String> sourceDir;
    private final Property<String> outputDir;

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
    public SukoExtension(ObjectFactory objectFactory) {
        this.sourceDir = objectFactory.property(String.class);
        this.outputDir = objectFactory.property(String.class);
    }

    public Property<String> getSourceDir() {
        return sourceDir;
    }

    public Property<String> getOutputDir() {
        return outputDir;
    }

    public Path getSourceDirAsPath() {
        return Path.of(getSourceDir().get());
    }

    public Path getOutputDirAsPath() {
        return Path.of(getOutputDir().get());
    }
}