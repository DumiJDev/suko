package io.suko.lang.ext;

import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Escreve build/suko/security-audit.json com cada uso de trustedUrl/trustedStyle/trustedHtml. */
public final class SecurityAudit {

    private static final Set<String> CODES = Set.of("TRUSTED_URL", "TRUSTED_STYLE", "TRUSTED_HTML");

    private SecurityAudit() {
    }

    private record Entry(String file, int line, int column, String code, String expression) {
    }

    public static void write(Path file, Map<Path, DiagnosticCollector> diagnosticsByFile) {
        List<Entry> entries = new ArrayList<>();
        diagnosticsByFile.forEach((path, collector) -> {
            for (SukoDiagnostic d : collector.getDiagnostics()) {
                if (CODES.contains(d.code())) {
                    String message = d.message();
                    // O prefixo fixo das mensagens não contém ": "; a expressão pode contê-lo (c ? a : b).
                    int cut = message.indexOf(": ");
                    entries.add(new Entry(path.toString().replace('\\', '/'),
                        d.span() == null ? 0 : d.span().startLine(), d.span() == null ? 0 : d.span().startColumn(),
                        d.code(), cut < 0 ? message : message.substring(cut + 2)));
                }
            }
        });
        entries.sort(Comparator.comparing(Entry::file).thenComparingInt(Entry::line).thenComparingInt(Entry::column));

        StringBuilder json = new StringBuilder("{\n  \"entries\": [");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            json.append(i == 0 ? "\n" : ",\n").append("    {\"file\": ").append(quote(e.file()))
                .append(", \"line\": ").append(e.line()).append(", \"column\": ").append(e.column())
                .append(", \"code\": ").append(quote(e.code())).append(", \"expression\": ").append(quote(e.expression())).append('}');
        }
        json.append(entries.isEmpty() ? "]" : "\n  ]").append("\n}\n");
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path tmp = Files.createTempFile(parent, "audit", ".tmp");
            try {
                Files.writeString(tmp, json, StandardCharsets.UTF_8);
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
