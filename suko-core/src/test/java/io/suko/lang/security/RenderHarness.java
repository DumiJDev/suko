package io.suko.lang.security;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import io.suko.ext.SecurityOptions;
import io.suko.jte.SukoSafeSource;
import io.suko.lang.JteCompiler;
import io.suko.lang.ext.ExtensionRegistry;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.net.URI;
import java.net.URLClassLoader;
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
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        String source = SukoSafeSource.generate(options);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(
            URI.create("string:///" + SukoSafeSource.relativePath(options)), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        };
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            if (!javac.getTask(null, fm, null, List.of("-d", classes.toString(), "--release", "21"), null, List.of(file)).call()) {
                throw new IllegalStateException("SukoSafe não compila");
            }
        }
        try (URLClassLoader loader = new URLClassLoader(new java.net.URL[] {classes.toUri().toURL()},
                RenderHarness.class.getClassLoader())) {
            TemplateEngine engine = TemplateEngine.create(new DirectoryCodeResolver(tmp),
                tmp.resolve("jte-classes"), ContentType.Html, loader);
            Map<String, Object> converted = new HashMap<>();
            params.forEach((k, v) -> converted.put(k, v == NULL ? null : v));
            StringOutput out = new StringOutput();
            engine.render(name + ".jte", converted, out);
            return out.toString();
        }
    }
}
