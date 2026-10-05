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
    protected org.gradle.api.file.FileCollection extensionClasspath;

    @org.gradle.api.tasks.Classpath
    public org.gradle.api.file.FileCollection getExtensionClasspath() {
        return extensionClasspath;
    }

    @Input
    public java.util.List<String> getTargets() {
        return resolvedTargets();
    }

    /** Alvos pedidos; sem extensão Gradle (tasks soltas em testes) vale o default "jte". */
    protected java.util.List<String> resolvedTargets() {
        return extension == null ? java.util.List.of("jte") : io.suko.lang.JteCompiler.normalizeTargets(extension.getTargets().get());
    }

    protected java.util.List<java.io.File> extensionFiles() {
        return extensionClasspath == null ? java.util.List.of()
            : new java.util.ArrayList<>(extensionClasspath.getFiles());
    }

    /** ClassLoader com os jars de sukoExtensions por cima do classpath do plugin; quem chama fecha. */
    protected java.net.URLClassLoader openExtensionLoader() {
        java.util.List<java.net.URL> urls = new java.util.ArrayList<>();
        for (java.io.File f : extensionFiles()) {
            try {
                urls.add(f.toURI().toURL());
            } catch (java.net.MalformedURLException e) {
                throw new RuntimeException(e);
            }
        }
        return new java.net.URLClassLoader(urls.toArray(new java.net.URL[0]), SukoBaseTask.class.getClassLoader());
    }

    @Input
    public String getSourceDir() {
        return getExtension().getSourceDir().get();
    }

    @Input
    public String getGeneratedJavaDir() {
        return extension == null ? "" : extension.getGeneratedJavaDir().get();
    }

    /** As opções de segurança entram nos inputs da task (valor inválido: o erro surge na execução). */
    @Input
    public String getSecurityOptionsSignature() {
        if (extension == null) {
            return io.suko.ext.SecurityOptions.DEFAULT.toString();
        }
        try {
            return extension.securityOptions().toString();
        } catch (org.gradle.api.GradleException e) {
            return "invalid:" + e.getMessage();
        }
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