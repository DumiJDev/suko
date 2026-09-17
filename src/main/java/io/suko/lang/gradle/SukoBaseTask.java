package io.suko.lang.gradle;

import org.gradle.api.DefaultTask;
import org.gradle.api.tasks.Input;

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

    public Path getSourceDirAsPath() {
        return getExtension().getSourceDirAsPath();
    }

    public Path getOutputDirAsPath() {
        return getExtension().getOutputDirAsPath();
    }

    public SukoExtension getExtension() {
        return extension;
    }
}