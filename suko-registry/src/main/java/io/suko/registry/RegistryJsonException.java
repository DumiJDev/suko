package io.suko.registry;

/**
 * Thrown by {@link RegistryJson} when a manifest or index document cannot be
 * read or written. The message always names the concrete problem (found vs.
 * supported schema version, or the missing field) rather than surfacing a raw
 * {@code JsonSyntaxException}/{@code NullPointerException} from Gson.
 */
public class RegistryJsonException extends RuntimeException {

    public RegistryJsonException(String message) {
        super(message);
    }

    public RegistryJsonException(String message, Throwable cause) {
        super(message, cause);
    }
}
