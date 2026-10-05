package io.suko.registry;

/**
 * A registry integrity/authenticity failure. {@link #code()} is one of
 * {@code REGISTRY_UNSIGNED}, {@code REGISTRY_BAD_SIGNATURE}, {@code REGISTRY_ROLLBACK},
 * {@code REGISTRY_EXPIRED}, {@code REGISTRY_MANIFEST_HASH}, {@code REGISTRY_MISMATCH}.
 */
public final class RegistrySecurityException extends RuntimeException {
    private final String code;

    public RegistrySecurityException(String code, String message) {
        super(code + ": " + message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
