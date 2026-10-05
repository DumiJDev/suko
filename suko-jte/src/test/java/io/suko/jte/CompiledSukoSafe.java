package io.suko.jte;

import io.suko.ext.SecurityOptions;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Compila a SukoSafe gerada com o javac do JDK e chama os seus métodos por reflexão. */
final class CompiledSukoSafe implements AutoCloseable {

    private final URLClassLoader loader;
    private final Class<?> type;

    CompiledSukoSafe(SecurityOptions options) throws Exception {
        String source = SukoSafeSource.generate(options);
        Path out = Files.createTempDirectory("suko-safe");
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        JavaFileObject file = new SimpleJavaFileObject(
            URI.create("string:///" + SukoSafeSource.relativePath(options)), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };
        try (StandardJavaFileManager fm = javac.getStandardFileManager(null, null, null)) {
            boolean ok = javac.getTask(null, fm, null, List.of("-d", out.toString(), "--release", "21"), null, List.of(file)).call();
            if (!ok) {
                throw new IllegalStateException("A SukoSafe gerada não compila:\n" + source);
            }
        }
        loader = new URLClassLoader(new URL[] {out.toUri().toURL()});
        type = loader.loadClass(options.generatedPackage() + ".SukoSafe");
    }

    Object call(String method, Object arg) throws Exception {
        Method m = type.getMethod(method, Object.class);
        return m.invoke(null, arg);
    }

    String url(Object v) throws Exception {
        return (String) call("url", v);
    }

    @Override
    public void close() throws Exception {
        loader.close();
    }
}
