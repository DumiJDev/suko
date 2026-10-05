package io.suko.lang.ext;

import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAuditTest {

    @Test
    void writesOnlyTrustedUsesSortedAndEscaped(@TempDir Path dir) throws Exception {
        Map<Path, DiagnosticCollector> byFile = new LinkedHashMap<>();
        DiagnosticCollector b = new DiagnosticCollector();
        b.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO,
            "Uso de trustedUrl(...) dispensa a verificação de URL: x.url(\"q\")", "TRUSTED_URL", "b/B.sk", new SourceSpan(7, 4, 0, 0)));
        b.add(new SukoDiagnostic(SukoDiagnostic.Severity.WARNING, "outra coisa", "CSP_INLINE", "b/B.sk", new SourceSpan(1, 0, 0, 0)));
        DiagnosticCollector a = new DiagnosticCollector();
        a.add(new SukoDiagnostic(SukoDiagnostic.Severity.INFO,
            "Uso de trustedStyle(...) dispensa a verificação de estilo: s", "TRUSTED_STYLE", "a/A.sk", new SourceSpan(2, 9, 0, 0)));
        byFile.put(Path.of("b/B.sk"), b);
        byFile.put(Path.of("a/A.sk"), a);

        Path out = dir.resolve("suko/security-audit.json");
        SecurityAudit.write(out, byFile);

        String json = Files.readString(out);
        assertTrue(json.indexOf("a/A.sk") < json.indexOf("b/B.sk"), json);
        assertTrue(json.contains("\"code\": \"TRUSTED_URL\"") && json.contains("\"code\": \"TRUSTED_STYLE\""), json);
        assertTrue(json.contains("x.url(\\\"q\\\")"), json);
        assertFalse(json.contains("CSP_INLINE"), json);
        assertTrue(json.contains("\"line\": 7") && json.contains("\"column\": 4"), json);
    }

    @Test
    void writesAnEmptyListWhenThereAreNoTrustedUses(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("audit.json");
        SecurityAudit.write(out, Map.of());
        assertTrue(Files.readString(out).replaceAll("\\s", "").contains("\"entries\":[]"));
    }
}
