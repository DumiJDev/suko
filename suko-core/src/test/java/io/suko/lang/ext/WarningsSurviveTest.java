package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.JteCompiler;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.SukoProjectCompiler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WarningsSurviveTest {

    static final class WarnExtension implements SukoExtension {
        public String id() { return "io.suko.test.warn"; }
        public int apiVersion() { return ExtensionApi.VERSION; }
        public void register(ExtensionContext ctx) {
            ctx.checker(new Checker() {
                public String id() { return "warn"; }
                public void check(io.suko.lang.ast.SukoFile file, CheckContext c) {
                    c.report(SukoDiagnostic.Severity.WARNING, "TEST_WARN", "aviso de teste", io.suko.lang.ast.SourceSpan.NONE);
                    c.report(SukoDiagnostic.Severity.INFO, "TEST_INFO", "info de teste", io.suko.lang.ast.SourceSpan.NONE);
                }
            });
        }
    }

    @Test
    void singleFileCompileKeepsWarningsOnSuccess() {
        ExtensionRegistry registry = ExtensionRegistry.of(List.of(new WarnExtension(), jte()));
        var result = new JteCompiler("A.sk", "component A() { <p>x</p> }", registry, List.of("jte")).compile();
        assertTrue(result.success());
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("TEST_WARN")));
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(d -> d.code().equals("TEST_INFO")));
    }

    @Test
    void projectCompileKeepsWarningsOnSuccess(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("A.sk"), "component A() { <p>x</p> }");
        ExtensionRegistry registry = ExtensionRegistry.of(List.of(new WarnExtension(), jte()));
        var result = new SukoProjectCompiler(registry, List.of("jte")).compile(dir);
        assertTrue(result.success());
        var diags = result.diagnosticsByFile().get(Path.of("A.sk")).getDiagnostics();
        assertTrue(diags.stream().anyMatch(d -> d.code().equals("TEST_WARN")), diags.toString());
    }

    @Test
    void infoIsNotAnError() {
        var c = new io.suko.lang.diagnostic.DiagnosticCollector();
        c.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO, "m", "X", "f", io.suko.lang.ast.SourceSpan.NONE));
        assertFalse(c.hasErrors());
    }

    private static SukoExtension jte() {
        return new io.suko.jte.JteExtension();
    }
}
