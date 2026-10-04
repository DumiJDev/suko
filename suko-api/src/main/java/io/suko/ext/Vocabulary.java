package io.suko.ext;

import java.util.Optional;

public interface Vocabulary {
    /** Ex.: {@code "html"}. */
    String id();

    /** {@code true}: aceita qualquer tag (o HTML do alvo JTE na fase 0). */
    boolean open();

    /** A tag, se for conhecida deste vocabulário. */
    Optional<TagSpec> tag(String name);
}
