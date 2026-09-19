package io.suko.lang.project;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SourceSpan;
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

    /**
     * Colisão de nome qualificado detetada na Fase 1 (revisão final do
     * subprojeto 5, achado C): dois componentes que resolvem para o mesmo
     * qualifiedName sobrescreviam-se em silêncio, tanto no índice como no
     * mapa de .jte gerados. A Fase 1 é o único sítio que vê as DUAS
     * declarações antes de uma delas desaparecer — regista-as aqui para
     * que o SukoProjectCompiler possa reportar DUPLICATE_COMPONENT nos
     * dois ficheiros envolvidos.
     */
    public record DuplicateComponent(
        String qualifiedName,
        Path firstFile,
        SourceSpan firstSpan,
        Path secondFile,
        SourceSpan secondSpan
    ) {
    }

    private final Map<String, ProjectIndexEntry> byQualifiedName = new LinkedHashMap<>();
    private final Map<String, SourceSpan> spanByQualifiedName = new LinkedHashMap<>();
    private final List<DuplicateComponent> duplicates = new ArrayList<>();

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
            String source;
            try {
                source = Files.readString(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            SukoFile file = parseQuietly(source);
            if (file == null) {
                // REVISÃO FINAL (achado A): um único .sk malformado NÃO pode
                // abortar a indexação do projeto inteiro. Esta fase usa o
                // parser ANTLR por omissão (sem SukoErrorListener) e recupera
                // de erros de sintaxe "best effort", o que pode produzir uma
                // árvore parcial onde o SukoAstBuilder rebenta (NPE/ISE). O
                // erro de sintaxe real desse ficheiro é reportado pela Fase 2
                // (JteCompiler.parseAndBuild, que já tem SukoErrorListener)
                // quando esse ficheiro for efetivamente compilado — aqui basta
                // não indexar os seus componentes.
                continue;
            }

            // REVISÃO FINAL (achado B): a PASTA relativa do ficheiro é a única
            // fonte de verdade do nome qualificado — não o `package` declarado.
            // Um ficheiro SEM `package` numa subpasta tinha qualifiedName de
            // raiz mas .jte escrito na subpasta, produzindo
            // TemplateNotFoundException em tempo de render com success=true.
            // Para um ficheiro que declara o package correto isto é idêntico
            // ao comportamento anterior (é exatamente o que
            // PACKAGE_DIRECTORY_MISMATCH garante).
            String packagePrefix = relativeDirToPackagePrefix(relativeDirOf(sourceRoot, skFile));
            for (ComponentDecl component : file.components()) {
                String qualifiedName = packagePrefix + component.name();
                ProjectIndexEntry previous = index.byQualifiedName.get(qualifiedName);
                if (previous != null) {
                    index.duplicates.add(new DuplicateComponent(
                        qualifiedName,
                        previous.sourceFile(),
                        index.spanByQualifiedName.get(qualifiedName),
                        skFile,
                        component.span()));
                    continue;
                }
                index.byQualifiedName.put(qualifiedName, new ProjectIndexEntry(
                    qualifiedName, component.name(), skFile, component.isPublic(), component.params().size()));
                index.spanByQualifiedName.put(qualifiedName, component.span());
            }
        }

        return index;
    }

    /** Parse "silencioso" da Fase 1: sem listeners de erro (a Fase 2 é que
     * reporta diagnósticos deste ficheiro) e tolerante a qualquer falha —
     * devolve {@code null} quando o ficheiro não produz um AST utilizável. */
    private static SukoFile parseQuietly(String source) {
        try {
            SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
            lexer.removeErrorListeners();
            SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            return new SukoAstBuilder(source).build(parser.compilationUnit());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Colisões de nome qualificado detetadas durante {@link #build} — ver
     * {@link DuplicateComponent}. */
    public List<DuplicateComponent> duplicates() {
        return List.copyOf(duplicates);
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

    /** Inverso de {@link #packageToRelativeDir}: "foo/bar" -> "foo.bar."
     * (prefixo pronto a concatenar; "" para a raiz). Revisão final, achado
     * B: é esta a fonte de verdade da resolução multi-ficheiro, tanto para
     * o qualifiedName do índice como para o prefixo de @template.* emitido
     * pelo JteEmitter. */
    public static String relativeDirToPackagePrefix(Path relativeDir) {
        if (relativeDir == null || relativeDir.toString().isEmpty()) {
            return "";
        }
        StringBuilder prefix = new StringBuilder();
        for (Path segment : relativeDir) {
            prefix.append(segment).append('.');
        }
        return prefix.toString();
    }

    /** Pasta do ficheiro relativa ao sourceRoot ({@code Path.of("")} na raiz). */
    public static Path relativeDirOf(Path sourceRoot, Path skFile) {
        Path parent = sourceRoot.relativize(skFile).getParent();
        return parent == null ? Path.of("") : parent;
    }
}
