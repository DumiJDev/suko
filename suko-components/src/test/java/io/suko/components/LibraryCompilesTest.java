package io.suko.components;

import gg.jte.Content;
import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.Cardinality;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.project.SukoProjectCompiler;
import io.suko.lang.support.JteRenderSupport;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compila TODA a biblioteca ({@code src/main/suko}) com o
 * {@link SukoProjectCompiler} real (a mesma Fase 1 + Fase 2 usada pelo
 * compilador de projetos multi-ficheiro) e exige zero diagnósticos, de
 * qualquer severidade. Depois renderiza cada componente público com o
 * motor {@code gg.jte} real ({@link JteRenderSupport#renderProject}) e
 * parâmetros mínimos, confirmando que o HTML produzido não está vazio —
 * nunca só compara o {@code .jte} gerado (convenção do projeto desde o
 * subprojeto 1: motor real, não simulado).
 *
 * <p>Tem de passar com a pasta vazia (0 ficheiros .sk): é o que prova que
 * o harness está corretamente ligado antes de haver conteúdo (Tarefa 5,
 * Passo 5 do plano).
 */
class LibraryCompilesTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "suko");

    @Test
    void wholeLibraryCompilesWithZeroDiagnostics() {
        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(SOURCE_ROOT);

        assertTrue(result.success(), () -> "compilação da biblioteca falhou: " + result.diagnosticsByFile());
        for (var entry : result.diagnosticsByFile().entrySet()) {
            DiagnosticCollector diagnostics = entry.getValue();
            assertTrue(diagnostics.getDiagnostics().isEmpty(),
                () -> entry.getKey() + ": esperados zero diagnósticos, obtidos " + diagnostics);
        }
    }

    @TestFactory
    Stream<DynamicTest> everyPublicComponentRendersNonEmptyHtml() {
        SukoProjectCompiler.ProjectCompileResult result = new SukoProjectCompiler().compile(SOURCE_ROOT);
        assertTrue(result.success(), () -> "compilação da biblioteca falhou: " + result.diagnosticsByFile());

        return listSkFiles().stream()
            .flatMap(skFile -> parse(skFile).components().stream()
                .filter(ComponentDecl::isPublic)
                .map(component -> new ComponentUnderTest(skFile, component)))
            .map(unit -> DynamicTest.dynamicTest(unit.component.name(), () -> {
                String entryPath = entryRelativePath(unit.skFile, unit.component);
                Map<String, Object> params = minimalParams(unit.component);

                String html = JteRenderSupport.renderProject(SOURCE_ROOT, entryPath, params);

                assertFalse(html.isBlank(), () -> unit.component.name() + ": render produziu HTML vazio");
            }));
    }

    private record ComponentUnderTest(Path skFile, ComponentDecl component) {
    }

    /** Teste focado (Fix round 1, tarefa 6): exercita, num único
     * componente sintético, as 4 combinações de {@code cardinality()}/
     * {@code renderProp()} de {@code Param.SlotParam} sem valor por
     * omissão — a árvore exata que {@code JteEmitter.jteParamDeclaration}
     * usa para escolher o tipo Java do {@code .jte} gerado
     * (linhas 242-253). Antes da correção desta ronda, {@code
     * minimalParams} sintetizava sempre um {@code Function<Object,
     * Content>}, o que batia só com o caso ONE+renderProp e produzia
     * {@code ClassCastException} nos outros três — este teste falha (RED)
     * contra a versão antiga e passa (GREEN) contra a corrigida, sem
     * depender de nenhum componente real da biblioteca. */
    @Test
    void minimalParamsMatchGeneratedJteTypesForAllFourSlotShapes() throws IOException {
        String source = """
            component AllSlotShapes(
              Component one,
              List<Component> many,
              Function<String, Component> oneRenderProp,
              List<Function<String, Component>> manyRenderProp
            ) {
              {one}
              for (Content c : many) {
                {c}
              }
              {oneRenderProp("x")}
              for (Function<String, Content> f : manyRenderProp) {
                {f.apply("y")}
              }
            }
            """;

        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());
        ComponentDecl component = file.components().get(0);

        Map<String, Object> params = minimalParams(component);

        // As 4 asserções de tipo abaixo são o próprio ponto do teste:
        // falham (com ClassCastException do JVM, não do JUnit) antes da
        // correção, porque os 3 últimos casos recebiam um Function em vez
        // do tipo que o .jte gerado realmente declara.
        assertTrue(params.get("one") instanceof Content, "ONE sem renderProp tem de ser Content puro");
        assertTrue(params.get("many") instanceof List<?> manyList && manyList.isEmpty(),
            "MANY sem renderProp tem de ser List<Content> (vazia)");
        assertTrue(params.get("oneRenderProp") instanceof Function<?, ?>, "ONE+renderProp tem de ser Function");
        assertTrue(params.get("manyRenderProp") instanceof List<?> manyRenderPropList && manyRenderPropList.isEmpty(),
            "MANY+renderProp tem de ser List<Function<...>> (vazia)");

        // E a prova de ponta-a-ponta: o motor gg.jte real aceita este mapa
        // sem exceção nenhuma (nem ClassCastException, nem
        // TemplateException) — a asserção real é a própria chamada não
        // lançar; `html` só é inspecionado para garantir que não é null,
        // provando que a chamada de facto devolveu (não é um no-op).
        String html = JteRenderSupport.render(source, "AllSlotShapes", params);
        assertTrue(html != null, "renderProject tem de devolver o HTML produzido, nunca null");
    }

    /** "io/suko/ui/Button" a partir do package declarado + nome do componente
     * — o mesmo esquema de caminho que SukoProjectCompiler usa para escrever
     * o .jte gerado (espelha a pasta do ficheiro .sk de origem). */
    private static String entryRelativePath(Path skFile, ComponentDecl component) {
        SukoFile file = parse(skFile);
        String packageName = file.packageName().orElse("");
        if (packageName.isEmpty()) {
            return component.name();
        }
        return packageName.replace('.', '/') + "/" + component.name();
    }

    /** Valor mínimo (mas válido) por parâmetro, só para os que não têm
     * default: primitivos/String recebem um valor "neutro"; slots recebem
     * um valor cujo tipo Java bate exatamente com o que
     * {@code JteEmitter.jteParamDeclaration} declara no {@code .jte}
     * gerado — que depende de {@code cardinality()}/{@code renderProp()},
     * não só de "é um SlotParam" (ver {@code JteEmitter.java:242-253}):
     * ONE+renderProp → {@code Function<T, Content>}; ONE sem renderProp
     * (caso de {@code children}) → {@code Content} puro; MANY+renderProp
     * → {@code List<Function<T, Content>>}; MANY sem renderProp →
     * {@code List<Content>}. Antes desta correção (Fix round 1, tarefa 6),
     * os 3 últimos casos recebiam sempre um {@code Function<Object,
     * Content>}, o que causava {@code ClassCastException} em runtime
     * para qualquer slot ONE não-render-prop obrigatório (ex.: `Component
     * children` sem `= null`) — bloqueava a tarefa 7 (`Card.children`,
     * obrigatório por desenho). */
    private static Map<String, Object> minimalParams(ComponentDecl component) {
        Map<String, Object> params = new HashMap<>();
        for (Param param : component.params()) {
            switch (param) {
                case Param.ValueParam valueParam -> {
                    if (valueParam.defaultValue().isEmpty()) {
                        params.put(valueParam.name(), minimalValueFor(valueParam));
                    }
                }
                case Param.SlotParam slotParam -> {
                    if (slotParam.defaultValue().isEmpty()) {
                        params.put(slotParam.name(), minimalSlotValueFor(slotParam));
                    }
                }
            }
        }
        return params;
    }

    private static Object minimalSlotValueFor(Param.SlotParam slotParam) {
        boolean many = slotParam.cardinality() == Cardinality.MANY;
        if (slotParam.renderProp()) {
            // Lista vazia é um valor válido para cardinalidade MANY sem
            // fills (o .jte gerado declara List<Function<T, Content>>).
            if (many) {
                return List.<Function<Object, Content>>of();
            }
            Function<Object, Content> emptyRenderProp = it -> output -> {
            };
            return emptyRenderProp;
        }
        if (many) {
            return List.<Content>of();
        }
        Content emptyContent = output -> {
        };
        return emptyContent;
    }

    private static Object minimalValueFor(Param.ValueParam valueParam) {
        return switch (valueParam.type().name()) {
            case "String" -> "";
            case "int", "Integer", "long", "Long" -> 0;
            case "double", "Double", "float", "Float" -> 0.0;
            case "boolean", "Boolean" -> false;
            case "List" -> List.of();
            default -> null;
        };
    }

    private static List<Path> listSkFiles() {
        if (!Files.isDirectory(SOURCE_ROOT)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(SOURCE_ROOT)) {
            return walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static SukoFile parse(Path skFile) {
        try {
            String source = Files.readString(skFile);
            SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
            SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
            return new SukoAstBuilder(source).build(parser.compilationUnit());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
