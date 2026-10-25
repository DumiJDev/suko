package io.suko.jte.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** O texto de {@code prelude.js} (recurso do suko-jte): as funções {@code $suko.*} que o JS traduzido chama. */
public final class ClientPrelude {

    public static final String RESOURCE = "/io/suko/jte/client/prelude.js";

    private ClientPrelude() {
    }

    public static String source() {
        try (InputStream in = Objects.requireNonNull(ClientPrelude.class.getResourceAsStream(RESOURCE),
                "recurso em falta: " + RESOURCE)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
