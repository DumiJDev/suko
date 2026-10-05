package io.suko.cli;

import io.suko.registry.TrustedKeys;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Chaves públicas embutidas no CLI (recurso {@code trusted-keys.json}, cada uma ligada a um {@code registryId}).
 * Vazio até à primeira release (R6 do plano do subprojeto 14): gerar o par e embutir a pública faz parte da
 * checklist de release. Com a lista vazia, um registry remoto só passa com uma chave configurada em
 * {@code suko.json} ({@code registry.publicKeys}) — falha fechada.
 */
final class Trust {

    private static final TrustedKeys EMBEDDED = load();

    private Trust() {
    }

    static TrustedKeys keys() {
        return EMBEDDED;
    }

    private static TrustedKeys load() {
        try (InputStream in = Trust.class.getResourceAsStream("/trusted-keys.json")) {
            if (in == null) {
                return TrustedKeys.empty();
            }
            return TrustedKeys.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
