package io.suko.registry;

/**
 * Thrown by {@link RegistryGenerator} when the sources under a source root
 * do not follow the distribution conventions of the library (subprojeto 7
 * spec, "Convenções de autoria da biblioteca") or are missing metadata the
 * generator needs (description, version, category). The message always
 * names the concrete file/component/cycle involved, never a raw exception
 * from the underlying parser or reflection.
 */
public class RegistryGeneratorException extends RuntimeException {

    public RegistryGeneratorException(String message) {
        super(message);
    }

    public RegistryGeneratorException(String message, Throwable cause) {
        super(message, cause);
    }
}
