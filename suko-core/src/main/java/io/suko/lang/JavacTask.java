package io.suko.lang;

import io.suko.lang.ast.SourceMapEntry;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.Type;
import io.suko.lang.ast.Cardinality;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.diagnostic.SukoDiagnostic.Severity;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Verificação Java: compila cada componente Suko via JavacTask,
 * gerando um stub Java e mapeando erros de volta ao .sk original.
 *
 * <p>Para cada componente do SukoFile, gera um stub Java representando
 * a assinatura do componente (parâmetros e tipos), compila com javac,
 * e mapeia quaisquer erros de compilação para as linhas do .sk
 * original usando o SourceMapEntry gerado por JteEmitter.emitWithSourceMap().
 *
 * <p>Fluxo:
 * <ol>
 *   <li>SukoFile (AST) com source map</li>
 *   <li>Gera stub Java por componente</li>
 *   <li>Compila stubs com javac</li>
 *   <li>Mapeia erros para .sk usando SourceMapEntry</li>
 *   <li>Retorna DiagnosticCollector com diagnostics mapeados</li>
 * </ol>
 */
public class JavacTask {

    private final SukoFile sukoFile;
    private final Map<String, List<SourceMapEntry>> jteSourceMaps;

    public JavacTask(SukoFile sukoFile, Map<String, List<SourceMapEntry>> jteSourceMaps) {
        this.sukoFile = sukoFile;
        this.jteSourceMaps = jteSourceMaps;
    }

    /**
     * Gera stubs Java para todos os componentes, compila e coleta diagnostics.
     *
     * @return DiagnosticCollector com erros/warnings de compilação,
     *         mapeados para o .sk original quando possível
     */
    public DiagnosticCollector compile() {
        DiagnosticCollector collector = new DiagnosticCollector();

        Path tempDir;
        try {
            tempDir = Files.createTempDirectory("suko-javac-task");
        } catch (IOException e) {
            collector.add(new SukoDiagnostic(
                Severity.ERROR,
                "Falha ao criar diretório temporário: " + e.getMessage(),
                "JAVAC_IO_ERROR",
                "suko://system",
                new SourceSpan(0, 0, 0, 0)
            ));
            return collector;
        }

        try {
            for (ComponentDecl component : sukoFile.components()) {
                generateStub(component, tempDir);
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                collector.add(new SukoDiagnostic(
                    Severity.ERROR,
                    "Compilador Java (javac) não disponível deste ambiente",
                    "JAVAC_NOT_AVAILABLE",
                    "suko://system",
                    new SourceSpan(0, 0, 0, 0)
                ));
                return collector;
            }

            StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null);
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(tempDir.toFile()));

            List<File> javaFiles = listJavaFiles(tempDir);
            if (javaFiles.isEmpty()) {
                return collector;
            }

            Iterable<? extends javax.tools.JavaFileObject> fileObjects = fileManager.getJavaFileObjects(javaFiles.toArray(new File[0]));
            List<javax.tools.Diagnostic<? extends javax.tools.JavaFileObject>> diagnostics = new ArrayList<>();
            JavaCompiler.CompilationTask task = compiler.getTask(
                null,
                fileManager,
                jdv -> diagnostics.add(jdv),
                List.of("-d", tempDir.toString()),
                null,
                fileObjects
            );

            Boolean success = task.call();

            for (javax.tools.Diagnostic jdv : diagnostics) {
                String message = jdv.getMessage(Locale.getDefault());
                String code = jdv.getCode() != null ? jdv.getCode() : "JAVAC";
                Severity severity = jdv.getKind() == javax.tools.Diagnostic.Kind.ERROR
                    ? Severity.ERROR
                    : Severity.WARNING;

                SourceSpan mappedSpan = null;
                long lineNumber = jdv.getLineNumber();
                if (lineNumber > 0) {
                    int stubLine = (int) lineNumber - 1;
                    mappedSpan = findMappedSpan(stubLine, javaFiles);
                }

                collector.add(new SukoDiagnostic(
                    severity,
                    message,
                    code,
                    null,
                    mappedSpan
                ));
            }

            try {
                fileManager.close();
            } catch (IOException e) {
                // Ignora erro de fechamento
            }

        } catch (IOException e) {
            collector.add(new SukoDiagnostic(
                Severity.ERROR,
                "Erro de I/O durante compilação javac: " + e.getMessage(),
                "JAVAC_IO_ERROR",
                "suko://system",
                new SourceSpan(0, 0, 0, 0)
            ));
        } finally {
            try {
                Files.walk(tempDir)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(java.io.File::delete);
            } catch (IOException e) {
                // Ignora erro de limpeza
            }
        }

        return collector;
    }

    private void generateStub(ComponentDecl component, Path tempDir) throws IOException {
        StringBuilder stub = new StringBuilder();
        stub.append("public class ").append(component.name()).append(" {\n");

        for (Param param : component.params()) {
            if (param instanceof Param.ValueParam valueParam) {
                String javaType = toJavaType(valueParam.type());
                stub.append("    public ").append(javaType).append(" ").append(valueParam.name()).append(";\n");
            } else if (param instanceof Param.SlotParam slotParam) {
                String javaType = toJavaType(slotParam.elementType());
                if (slotParam.cardinality() == Cardinality.ONE) {
                    stub.append("    public ").append(javaType).append(" ").append(slotParam.name()).append(";\n");
                } else {
                    stub.append("    public java.util.List<").append(javaType).append("> ").append(slotParam.name()).append(";\n");
                }
            }
        }

        stub.append("}\n");

        Files.writeString(tempDir.resolve(component.name() + ".java"), stub.toString());
    }

    private static String toJavaType(Type type) {
        String baseName = switch (type.name()) {
            case "Content" -> type.typeArguments().isEmpty() ? "gg.jte.Content" : "java.lang.Object";
            case "List" -> "java.util.List";
            case "String" -> "java.lang.String";
            default -> type.name();
        };
        StringBuilder sb = new StringBuilder(baseName);
        if (!type.typeArguments().isEmpty()) {
            sb.append('<');
            for (int i = 0; i < type.typeArguments().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(toJavaType(type.typeArguments().get(i)));
            }
            sb.append('>');
        }
        sb.append("[]".repeat(type.arrayDimensions()));
        return sb.toString();
    }

    private static List<File> listJavaFiles(Path dir) throws IOException {
        List<File> files = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> files.add(p.toFile()));
        }
        return files;
    }

    private SourceSpan findMappedSpan(int stubLine, List<File> javaFiles) {
        if (javaFiles.isEmpty()) return null;
        String fileName = javaFiles.get(0).getName();
        String componentName = fileName.substring(0, fileName.length() - 5);
        String key = componentName + ".jte";
        List<SourceMapEntry> entries = jteSourceMaps.get(key);
        if (entries == null || stubLine >= entries.size()) return null;
        return entries.get(stubLine).sukoSpan();
    }
}