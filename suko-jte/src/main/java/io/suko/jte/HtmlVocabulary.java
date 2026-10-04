package io.suko.jte;

import io.suko.ext.TagSpec;
import io.suko.ext.Vocabulary;

import java.util.Optional;

/**
 * HTML aberto: aceita qualquer tag. Na fase 0 não há regras de HTML a impor
 * (o SemanticChecker nunca as teve; o escape é do próprio JTE em runtime).
 */
public final class HtmlVocabulary implements Vocabulary {

    public String id() {
        return "html";
    }

    public boolean open() {
        return true;
    }

    public Optional<TagSpec> tag(String name) {
        return Optional.empty();
    }
}
