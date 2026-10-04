package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionPipelineTest {

    static final class Demo implements SukoExtension {
        final boolean throwInCheck;
        final boolean throwInEmit;

        Demo(boolean throwInCheck, boolean throwInEmit) {
            this.throwInCheck = throwInCheck;
            this.throwInEmit = throwInEmit;
        }

        public String id() { return "test.demo"; }
        public int apiVersion() { return ExtensionApi.VERSION; }

        public void register(ExtensionContext ctx) {
            ctx.target(new Target() {
                public String id() { return "demo"; }
                public String componentType() { return "demo.Node"; }
                public Set<String> vocabularies() { return Set.of("demo"); }
                public Emitted emit(ComponentDecl c, EmitContext e) {
                    if (throwInEmit) throw new IllegalStateException("emit boom");
                    return new Emitted(c.name() + ".demo", "demo:" + c.name() + "\n", List.of());
                }
            });
            ctx.vocabulary(new Vocabulary() {
                public String id() { return "demo"; }
                public boolean open() { return false; }
                public Optional<TagSpec> tag(String name) {
                    return Set.of("box", "label").contains(name)
                        ? Optional.of(new TagSpec(name, Map.of(), true)) : Optional.empty();
                }
            });
            ctx.checker(new Checker() {
                public String id() { return "demo-check"; }
                public void check(SukoFile file, CheckContext ctx) {
                    if (throwInCheck) throw new IllegalStateException("check boom");
                    for (ComponentDecl c : file.components()) {
                        ctx.report(SukoDiagnostic.Severity.WARNING, "DEMO_CHECK", "visto: " + c.name(), c.span());
                    }
                }
            });
        }
    }

    static ExtensionRegistry registry(SukoExtension... extra) {
        List<SukoExtension> all = new ArrayList<>(List.of(new io.suko.jte.JteExtension()));
        all.addAll(List.of(extra));
        return ExtensionRegistry.of(all);
    }

    static Path write(Path root, String rel, String text) throws Exception {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        return f;
    }

    @Test
    void defaultCompilerStillEmitsJteWithTheOldPaths(@TempDir Path root) throws Exception {
        write(root, "a/b/Hello.sk", "package a.b;\n\ncomponent Hello() {\n  <p>Olá</p>\n}\n");
        var result = new SukoProjectCompiler().compile(root);
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertEquals(Set.of(Path.of("a/b/Hello.jte")), result.generatedJteSources().keySet());
        assertTrue(result.projectDiagnostics().isEmpty());
    }

    @Test
    void multipleTargetsPrefixTheTargetBeforeThePackageDir(@TempDir Path root) throws Exception {
        write(root, "a/b/Hello.sk", "package a.b;\n\ncomponent Hello() {\n  <box><label>x</label></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"))
            .compile(root);
        assertTrue(result.success(), result.diagnosticsByFile().toString());
        assertEquals(Set.of(Path.of("jte/a/b/Hello.jte"), Path.of("demo/a/b/Hello.demo")),
            result.generatedJteSources().keySet());
        assertEquals("demo:Hello\n", result.generatedJteSources().get(Path.of("demo/a/b/Hello.demo")));
    }

    @Test
    void checkerWarningsAppearInAnalyzeAndCompile(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var compiler = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"));
        var analysis = compiler.analyze(SukoSources.fromDirectory(root));
        var codes = analysis.files().get(Path.of("Hello.sk")).diagnostics().getDiagnostics()
            .stream().map(SukoDiagnostic::code).toList();
        assertTrue(codes.contains("DEMO_CHECK"), codes.toString());
        assertTrue(compiler.compile(root).success());
    }

    @Test
    void tagOutsideAClosedVocabularyIsUnknownTag(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <div></div>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, false)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var d = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors().get(0);
        assertEquals("UNKNOWN_TAG", d.code());
        assertTrue(d.message().contains("div") && d.message().contains("demo"), d.message());
    }

    @Test
    void onlyJteNeverReportsUnknownTag(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <made-up-tag></made-up-tag>\n}\n");
        assertTrue(new SukoProjectCompiler().compile(root).success());
    }

    @Test
    void unknownTargetIsAProjectError(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <p>x</p>\n}\n");
        var result = new SukoProjectCompiler(registry(), List.of("jte", "swing")).compile(root);
        assertFalse(result.success());
        var d = result.projectDiagnostics().get(0);
        assertEquals("TARGET_NOT_FOUND", d.code());
        assertTrue(d.message().contains("swing") && d.message().contains("jte"), d.message());
    }

    @Test
    void checkerThatThrowsBecomesExtensionFailed(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(true, false)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var d = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors().get(0);
        assertEquals("EXTENSION_FAILED", d.code());
        assertTrue(d.message().contains("test.demo") && d.message().contains("check boom"), d.message());
    }

    @Test
    void targetThatThrowsBecomesExtensionFailed(@TempDir Path root) throws Exception {
        write(root, "Hello.sk", "component Hello() {\n  <box></box>\n}\n");
        var result = new SukoProjectCompiler(registry(new Demo(false, true)), List.of("jte", "demo"))
            .compile(root);
        assertFalse(result.success());
        var codes = result.diagnosticsByFile().get(Path.of("Hello.sk")).getErrors()
            .stream().map(SukoDiagnostic::code).toList();
        assertEquals(List.of("EXTENSION_FAILED"), codes);
    }

    @Test
    void singleFileCompilerKeepsItsOldContract() {
        var result = new JteCompiler("Hello.sk", "component Hello() {\n  <p>x</p>\n}\n").compile();
        assertTrue(result.success());
        assertEquals(Set.of("Hello.jte"), result.generatedJteSources().keySet());
    }
}
