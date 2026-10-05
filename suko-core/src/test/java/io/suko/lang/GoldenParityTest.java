package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceMapEntry;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.project.ProjectAnalysis;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.project.SukoSources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden do output do compilador (os .jte, os source maps e os diagnósticos) para as
 * raízes invalid, components, shop (a loja de examples/shop) e website. Regenerado na
 * Task 4 do subprojeto 14 e, com a loja, na Task 14. Regenerar só com
 * -Dsuko.updateGolden=true.
 */
class GoldenParityTest {

    private static final Path GOLDEN = Path.of("src/test/resources/golden");
    private static final boolean UPDATE = Boolean.getBoolean("suko.updateGolden");

    @ParameterizedTest
    @CsvSource({
        "invalid, src/test/resources/fixtures/invalid",
        "components, ../suko-components/src/main/suko",
        "shop, ../examples/shop/src/main/suko",
        "website, ../suko-website/src/main/suko"
    })
    void outputMatchesGolden(String name, String root) throws IOException {
        Map<String, String> actual = snapshot(Path.of(root));
        Path dir = GOLDEN.resolve(name);
        if (UPDATE) {
            write(dir, actual);
            return;
        }
        assertEquals(read(dir), actual, "output diferente do golden em " + name);
    }

    static Map<String, String> snapshot(Path root) {
        Map<String, String> out = new TreeMap<>();
        SukoSources sources = SukoSources.fromDirectory(root);
        var result = new SukoProjectCompiler().compile(sources);
        result.generatedJteSources().forEach((path, jte) -> out.put("jte/" + slash(path), jte));

        List<String> diagnostics = new ArrayList<>();
        result.diagnosticsByFile().forEach((file, collector) -> {
            for (SukoDiagnostic d : collector.getDiagnostics()) {
                diagnostics.add(slash(file) + "|" + d.severity() + "|" + d.code() + "|"
                    + (d.span() == null ? "-" : d.span().startLine() + ":" + d.span().startColumn())
                    + "|" + d.message());
            }
        });
        diagnostics.sort(null);
        out.put("diagnostics.txt", String.join("\n", diagnostics) + "\n");

        ProjectAnalysis analysis = new SukoProjectCompiler().analyze(sources);
        StringBuilder maps = new StringBuilder();
        analysis.files().forEach((file, fa) -> {
            if (fa.ast() == null || fa.diagnostics().hasErrors()) {
                return;
            }
            Path parent = file.getParent() == null ? Path.of("") : file.getParent();
            JteEmitter emitter = new JteEmitter(fa.ast().components(),
                analysis.index().resolveImports(fa.ast().imports()),
                ProjectIndex.relativeDirToPackagePrefix(parent));
            for (ComponentDecl c : fa.ast().components()) {
                for (SourceMapEntry e : emitter.emitWithSourceMap(c).sourceMap()) {
                    maps.append(slash(file)).append('#').append(c.name()).append(' ')
                        .append(e.jteLine()).append(" -> ")
                        .append(e.sukoSpan().startLine()).append(':').append(e.sukoSpan().startColumn())
                        .append('\n');
                }
            }
        });
        out.put("sourcemaps.txt", maps.toString());
        return out;
    }

    private static String slash(Path p) {
        return p.toString().replace('\\', '/');
    }

    private static void write(Path dir, Map<String, String> files) throws IOException {
        if (Files.exists(dir)) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        for (var e : files.entrySet()) {
            Path file = dir.resolve(e.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, e.getValue(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> read(Path dir) throws IOException {
        Map<String, String> files = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.filter(Files::isRegularFile).toList()) {
                files.put(slash(dir.relativize(p)), Files.readString(p, StandardCharsets.UTF_8));
            }
        }
        return files;
    }
}
