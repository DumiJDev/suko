package io.suko.lang.project;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Fase 1 (indexação) do SukoProjectCompiler: varre um sourceRoot
 * recursivamente e regista a assinatura pública de cada componente —
 * nunca corre SemanticChecker/JteEmitter aqui. Isto resolve referências
 * entre ficheiros em qualquer ordem, incluindo ciclos (A chama B e B
 * chama A é legítimo — gg.jte resolve @template.x(...) em tempo de
 * render, não em tempo de compilação Suko).
 *
 * LIMITAÇÃO ACEITE (descoberta ao escrever este plano): só a assinatura
 * superficial é indexada (nome, pacote, public, nº de params) — não os
 * slots. Um ComponentCallStmt que resolve contra este índice (alvo
 * noutro ficheiro) não tem verificação de slot fills entre ficheiros;
 * só existência/visibilidade. Ver Tarefa 4.
 */
public class ProjectIndex {

    private final Map<String, ProjectIndexEntry> byQualifiedName = new LinkedHashMap<>();

    private ProjectIndex() {
    }

    public static ProjectIndex build(Path sourceRoot) {
        ProjectIndex index = new ProjectIndex();

        List<Path> skFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            skFiles = walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".sk"))
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        for (Path skFile : skFiles) {
            // NOTA (descoberta ao escrever este plano): esta fase usa o parser
            // ANTLR por omissão (sem BailErrorStrategy nem SukoErrorListener) —
            // recupera de erros de sintaxe "best effort" e pode produzir um AST
            // parcial para um .sk malformado. Aceite: a Fase 1 só existe para
            // descobrir nomes; o erro de sintaxe real é reportado pela Fase 2
            // (JteCompiler, que já tem SukoErrorListener) quando esse ficheiro
            // for efetivamente compilado.
            String source;
            try {
                source = Files.readString(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
            SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
            SukoFile file = new SukoAstBuilder(source).build(parser.compilationUnit());

            String packagePrefix = file.packageName().map(p -> p + ".").orElse("");
            for (ComponentDecl component : file.components()) {
                String qualifiedName = packagePrefix + component.name();
                index.byQualifiedName.put(qualifiedName, new ProjectIndexEntry(
                    qualifiedName, component.name(), skFile, component.isPublic(), component.params().size()));
            }
        }

        return index;
    }

    public Optional<ProjectIndexEntry> resolveQualified(String qualifiedName) {
        return Optional.ofNullable(byQualifiedName.get(qualifiedName));
    }

    /** Resolve os imports de um ficheiro contra este índice: chave = alias
     * quando existe, senão o nome curto do alvo. Imports que não resolvem
     * (alvo inexistente) são simplesmente omitidos aqui — a Tarefa 4
     * (SemanticChecker) é quem decide reportar IMPORT_NOT_FOUND. */
    public Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports) {
        Map<String, ProjectIndexEntry> byShortName = new LinkedHashMap<>();
        for (ImportDecl imp : imports) {
            resolveQualified(imp.qualifiedName()).ifPresent(entry -> {
                String key = imp.alias().orElse(entry.simpleName());
                byShortName.putIfAbsent(key, entry);
            });
        }
        return byShortName;
    }

    /** "foo.bar" -> "foo/bar" (Path com segmentos, não string) — regra
     * package↔pasta usada tanto na indexação como na verificação. */
    public static Path packageToRelativeDir(String packageName) {
        Path dir = Path.of("");
        for (String segment : packageName.split("\\.")) {
            dir = dir.resolve(segment);
        }
        return dir;
    }
}
