package io.suko.components;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Expr;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guarda as oito convenções de autoria da biblioteca (spec do subprojeto
 * 7, secção "Convenções de autoria da biblioteca") sobre TODO ficheiro em
 * {@code src/main/suko}. As verificações são sobre o AST, não sobre o
 * texto do ficheiro — em particular a convenção 3 (nenhum {@code ${...}}
 * dentro de um atributo {@code class}) tem de olhar para
 * {@code Statement.Attribute} com {@code name().equals("class")} cujo
 * valor seja um {@code Expr.StringLiteralExpr} com partes não-literais,
 * para não gerar falsos positivos em atributos como {@code x-data} que
 * também podem conter chaves.
 *
 * <p>Esta suite tem de passar com a pasta vazia (0 ficheiros .sk) — é o
 * que prova que o harness e o wiring de módulos estão corretos antes de
 * haver conteúdo (Tarefa 5, Passo 5 do plano).
 */
class LibraryConventionsTest {

    private static final Path SOURCE_ROOT = Path.of("src", "main", "suko");

    @TestFactory
    Stream<DynamicTest> everyComponentFollowsLibraryConventions() {
        return listSkFiles().stream().map(skFile ->
            DynamicTest.dynamicTest(SOURCE_ROOT.relativize(skFile).toString(), () -> checkFile(skFile)));
    }

    private static void checkFile(Path skFile) throws IOException {
        SukoFile file = parse(skFile);

        // Convenção 1 + 2: exatamente um componente por ficheiro, e é
        // `public`.
        assertEquals(1, file.components().size(),
            skFile + ": um componente por ficheiro (convenção 2)");
        ComponentDecl component = file.components().get(0);
        assertTrue(component.isPublic(),
            skFile + ": todo componente da biblioteca é `public` (convenção 1)");

        // Convenção 2: nome do ficheiro == nome do componente.
        String fileNameWithoutExt = skFile.getFileName().toString().replaceFirst("\\.sk$", "");
        assertEquals(component.name(), fileNameWithoutExt,
            skFile + ": nome do ficheiro tem de bater com o nome do componente (convenção 2)");

        // Convenção implícita de organização de pacotes: o `package`
        // declarado bate com a pasta. Redundante com o diagnóstico
        // PACKAGE_DIRECTORY_MISMATCH (verificado por LibraryCompilesTest),
        // mas falha mais cedo e com melhor mensagem.
        Path relativeDir = SOURCE_ROOT.relativize(skFile).getParent();
        String expectedPackage = relativeDir == null
            ? ""
            : String.join(".", relativeDir.toString().split("[/\\\\]"));
        assertTrue(file.packageName().isPresent(),
            skFile + ": componente de biblioteca declara `package`");
        assertEquals(expectedPackage, file.packageName().orElseThrow(),
            skFile + ": package declarado tem de bater com a pasta");

        // Convenção 3 (a mais importante): nenhum `${...}` dentro de um
        // atributo `class` — quebra o scanner estático do Tailwind em
        // silêncio. Variantes são strings de classe completas dentro de
        // switch/if.
        List<Statement.Attribute> classAttributesWithInterpolation = new ArrayList<>();
        collectClassAttributesWithInterpolation(component.body(), classAttributesWithInterpolation);
        assertTrue(classAttributesWithInterpolation.isEmpty(),
            skFile + ": atributo `class` não pode conter interpolação (convenção 3): "
                + classAttributesWithInterpolation);

        // Convenção 8: imports totalmente qualificados, sem alias.
        for (ImportDecl importDecl : file.imports()) {
            assertTrue(importDecl.alias().isEmpty(),
                skFile + ": import '" + importDecl.qualifiedName() + "' não pode ter alias (convenção 8)");
        }
    }

    private static void collectClassAttributesWithInterpolation(List<Statement> statements,
                                                                  List<Statement.Attribute> out) {
        for (Statement statement : statements) {
            switch (statement) {
                case Statement.HtmlElement html -> {
                    for (Statement.Attribute attribute : html.attributes()) {
                        if (attribute.name().equals("class") && hasInterpolation(attribute.value())) {
                            out.add(attribute);
                        }
                    }
                    collectClassAttributesWithInterpolation(html.children(), out);
                }
                case Statement.IfStmt ifStmt -> {
                    collectClassAttributesWithInterpolation(ifStmt.thenBranch(), out);
                    collectClassAttributesWithInterpolation(ifStmt.elseBranch(), out);
                }
                case Statement.ForStmt forStmt -> collectClassAttributesWithInterpolation(forStmt.body(), out);
                case Statement.SwitchStmt switchStmt -> {
                    for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                        collectClassAttributesWithInterpolation(switchCase.body(), out);
                    }
                    collectClassAttributesWithInterpolation(switchStmt.defaultCase(), out);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.SlotFill slotFill : call.slotFills()) {
                        collectClassAttributesWithInterpolation(slotFill.body(), out);
                    }
                }
                default -> {
                }
            }
        }
    }

    private static boolean hasInterpolation(Expr value) {
        return value instanceof Expr.StringLiteralExpr stringLiteral
            && stringLiteral.parts().stream().anyMatch(part -> !(part instanceof Expr.StringPart.Literal));
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

    private static SukoFile parse(Path skFile) throws IOException {
        String source = Files.readString(skFile);
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }
}
