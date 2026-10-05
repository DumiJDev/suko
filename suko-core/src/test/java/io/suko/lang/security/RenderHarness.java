package io.suko.lang.security;

import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import io.suko.ext.SecurityOptions;
import io.suko.lang.JteCompiler;
import io.suko.lang.ext.ExtensionRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Compila .sk -> .jte (com a SukoSafe gerada) e renderiza com o motor JTE real. */
final class RenderHarness {

    /** Marcador convertido em {@code null} (Map.of não aceita null). */
    static final Object NULL = new Object();

    private RenderHarness() {
    }

    static String render(String sk, String name, Map<String, Object> params) throws Exception {
        SecurityOptions options = SecurityOptions.DEFAULT;
        var result = new JteCompiler(name + ".sk", sk, ExtensionRegistry.defaults(), List.of("jte"), options).compile();
        if (!result.success()) {
            throw new IllegalStateException(result.diagnostics().toString());
        }
        Path tmp = Files.createTempDirectory("suko-render-sec");
        for (var e : result.generatedJteSources().entrySet()) {
            Files.writeString(tmp.resolve(e.getKey()), e.getValue());
        }
        TemplateEngine engine = io.suko.lang.support.SukoSafeSupport.engine(tmp, options);
        {
            Map<String, Object> converted = new HashMap<>();
            params.forEach((k, v) -> converted.put(k, v == NULL ? null : v));
            StringOutput out = new StringOutput();
            engine.render(name + ".jte", converted, out);
            return out.toString();
        }
    }
}
