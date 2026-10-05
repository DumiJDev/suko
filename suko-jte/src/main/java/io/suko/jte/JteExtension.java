package io.suko.jte;

import io.suko.ext.ExtensionApi;
import io.suko.ext.ExtensionContext;
import io.suko.ext.SukoExtension;

public final class JteExtension implements SukoExtension {

    public String id() {
        return "io.suko.jte";
    }

    public int apiVersion() {
        return ExtensionApi.VERSION;
    }

    public void register(ExtensionContext ctx) {
        ctx.target(new JteTarget());
        ctx.vocabulary(new HtmlVocabulary());
        ctx.checker(new HtmlSecurityChecker());
    }
}
