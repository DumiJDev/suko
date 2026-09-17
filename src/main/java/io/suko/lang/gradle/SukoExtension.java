package io.suko.lang.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;

import javax.inject.Inject;
import java.nio.file.Path;

public class SukoExtension {

    private final Property<String> sourceDir;
    private final Property<String> outputDir;

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