package io.suko.lang.support;

import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.resolve.DirectoryCodeResolver;
import io.suko.ext.SecurityOptions;
import io.suko.jte.SukoSafeSource;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Os .jte emitidos chamam a SukoSafe gerada; os testes de renderização têm de
 * a compilar (javax.tools, só JDK) e pô-la no class loader pai do motor JTE.
 */
public final class SukoSafeSupport {

    private static final java.util.Map<SecurityOptions, ClassLoader> LOADERS = new java.util.concurrent.ConcurrentHashMap<>();

    private SukoSafeSupport() {
    }

    public static ClassLoader loader(SecurityOptions options) {
        return LOADERS.computeIfAbsent(options, o -> {
            try {
                return compile(o);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** Motor JTE sobre {@code jteDir} com a SukoSafe (opções por omissão) visível. */
    public static TemplateEngine engine(Path jteDir) throws IOException {
        return engine(jteDir, SecurityOptions.DEFAULT);
    }

    public static TemplateEngine engine(Path jteDir, SecurityOptions options) throws IOException {
        Path classes = Files.createTempDirectory("suko-jte-classes");
        return TemplateEngine.create(new DirectoryCodeResolver(jteDir), classes, ContentType.Html, loader(options));
    }

    private static ClassLoader compile(SecurityOptions options) throws IOException {
        String source = SukoSafeSource.generate(options);
        Path out = Files.createTempDirectory("suko-safe-classes");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(
            URI.create("string:///" + SukoSafeSource.relativePath(options)), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignore) {
                return source;
            }
        };
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            if (!javac.getTask(null, fm, null, List.of("-d", out.toString(), "--release", "21"), null, List.of(file)).call()) {
                throw new IllegalStateException("A SukoSafe gerada não compila");
            }
        }
        return new URLClassLoader(new URL[] {out.toUri().toURL()}, SukoSafeSupport.class.getClassLoader());
    }
}
