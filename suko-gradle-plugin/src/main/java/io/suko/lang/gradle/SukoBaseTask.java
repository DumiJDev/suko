package io.suko.lang.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;

import java.nio.file.Path;

/**
 * Base class for Suko Gradle tasks.
 */
public abstract class SukoBaseTask extends DefaultTask {

    protected SukoExtension extension;

    @Input
    public String getSourceDir() {
        return getExtension().getSourceDir().get();
    }

    @Input
    public String getOutputDir() {
        return getExtension().getOutputDir().get();
    }

    // D11: java-gradle-plugin ativa a validação de tasks do Gradle (que
    // antes não corria, porque não havia metadata de plugin nenhuma). Os
    // getters abaixo são derivados de getSourceDir()/getOutputDir() (já
    // anotados com @Input) — @Internal evita o aviso "missing an input or
    // output annotation" sem duplicar o mesmo input duas vezes.
    @Internal
    public Path getSourceDirAsPath() {
        return getExtension().getSourceDirAsPath();
    }

    @Internal
    public Path getOutputDirAsPath() {
        return getExtension().getOutputDirAsPath();
    }

    @Internal
    public SukoExtension getExtension() {
        return extension;
    }
}