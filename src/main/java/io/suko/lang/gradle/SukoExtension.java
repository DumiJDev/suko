package io.suko.lang.gradle;

import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.Provider;

import javax.inject.Inject;
import java.nio.file.Path;

public class SukoExtension {

    private final Property<String> sourceDir;
    private final Property<String> outputDir;

    // No-arg constructor for testing - creates simple properties
    public SukoExtension() {
        this.sourceDir = new TestProperty<>();
        this.outputDir = new TestProperty<>();
    }

    // Gradle constructor - uses ObjectFactory
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

    // Simple test implementation - only implements what's needed for tests
    private static class TestProperty<T> implements Property<T> {
        private T value;

        @Override public T get() { return value; }
        @Override public void set(T value) { this.value = value; }
        @Override public void set(Provider<? extends T> provider) { this.value = provider.get(); }
        @Override public Property<T> value(T value) { this.value = value; return this; }
        @Override public Property<T> value(Provider<? extends T> provider) { this.value = provider.get(); return this; }
        @Override public Property<T> convention(T value) { return this; }
        @Override public Property<T> convention(Provider<? extends T> provider) { return this; }
        @Override public Property<T> unsetConvention() { return this; }
        @Override public Property<T> unset() { this.value = null; return this; }
        @Override public void finalizeValue() {}
        @Override public void finalizeValueOnRead() {}
        @Override public void disallowChanges() {}
        @Override public void disallowUnsafeRead() {}
        @Override public T getOrNull() { return value; }
        @Override public T getOrElse(T defaultValue) { return value != null ? value : defaultValue; }
        @Override public boolean isPresent() { return value != null; }
        @Override public <S> org.gradle.api.provider.Provider<S> map(org.gradle.api.Transformer<? extends S, ? super T> t) { return null; }
        @Override public org.gradle.api.provider.Provider<T> filter(org.gradle.api.specs.Spec<? super T> s) { return null; }
        @Override public <S> org.gradle.api.provider.Provider<S> flatMap(org.gradle.api.Transformer<? extends org.gradle.api.provider.Provider<? extends S>, ? super T> t) { return null; }
        @Override public org.gradle.api.provider.Provider<T> orElse(T t) { return this; }
        @Override public org.gradle.api.provider.Provider<T> orElse(Provider<? extends T> p) { return this; }
        @Override public <U, R> org.gradle.api.provider.Provider<R> zip(Provider<U> p, java.util.function.BiFunction<? super T, ? super U, ? extends R> bf) { return null; }
    }
}