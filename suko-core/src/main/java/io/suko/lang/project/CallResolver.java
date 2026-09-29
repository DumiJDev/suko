package io.suko.lang.project;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Resolve o nome de uma chamada de componente para a sua declaração, com
 * exatamente as regras do compilador: primeiro o próprio ficheiro, depois os
 * imports (nome curto ou alias) e por fim nomes qualificados contra o
 * {@link ProjectIndex}. Existe como classe própria para que o
 * {@code SemanticChecker} e o language server (go-to-definition, hover)
 * resolvam da mesma maneira — o editor não pode saltar para um sítio
 * diferente daquele contra o qual o compilador valida.
 *
 * <p>Sem efeitos: não reporta diagnósticos. O {@code SemanticChecker}
 * transforma {@link #imports()} e {@link Resolution} em diagnósticos.
 */
public final class CallResolver {

    public sealed interface Resolution {
        /** Componente declarado no próprio ficheiro. */
        record Local(ComponentDecl decl) implements Resolution {
        }

        /** Componente de outro ficheiro, visível a partir daqui. */
        record Project(ProjectIndexEntry entry) implements Resolution {
        }

        /**
         * Existe mas não é {@code public}. {@code reportedAtImport} indica que
         * o nome vem de um {@code import}, onde o problema já é diagnosticado —
         * quem consome não deve repeti-lo em cada chamada.
         */
        record NotVisible(ProjectIndexEntry entry, boolean reportedAtImport) implements Resolution {
        }

        record NotFound() implements Resolution {
        }
    }

    public enum ImportKind { OK, NOT_FOUND, NOT_VISIBLE, AMBIGUOUS }

    /** Resultado de resolver uma linha {@code import}; {@code entry} é {@code null} em NOT_FOUND. */
    public record ImportStatus(ImportDecl decl, ImportKind kind, ProjectIndexEntry entry) {
    }

    private final Function<String, ComponentDecl> localLookup;
    private final ProjectIndex projectIndex;
    private final List<ImportStatus> imports = new ArrayList<>();
    private final Map<String, ProjectIndexEntry> importedByShortName = new HashMap<>();
    private final Map<String, ProjectIndexEntry> nonVisibleImported = new HashMap<>();

    /**
     * @param localLookup componentes do próprio ficheiro, por nome simples
     * @param projectIndex {@code null} fora de um projeto (compilação de um ficheiro isolado)
     * @param imports imports do ficheiro, em ordem de escrita
     */
    public CallResolver(Function<String, ComponentDecl> localLookup, ProjectIndex projectIndex,
                        List<ImportDecl> imports) {
        this.localLookup = localLookup;
        this.projectIndex = projectIndex;
        if (projectIndex != null) {
            for (ImportDecl imp : imports) {
                this.imports.add(resolveImport(imp));
            }
        }
    }

    private ImportStatus resolveImport(ImportDecl imp) {
        var found = projectIndex.resolveQualified(imp.qualifiedName());
        if (found.isEmpty()) {
            return new ImportStatus(imp, ImportKind.NOT_FOUND, null);
        }
        ProjectIndexEntry entry = found.get();
        String key = imp.alias().orElse(entry.simpleName());
        if (!entry.isPublic()) {
            nonVisibleImported.put(key, entry);
            return new ImportStatus(imp, ImportKind.NOT_VISIBLE, entry);
        }
        if (imp.alias().isEmpty() && importedByShortName.containsKey(key)) {
            return new ImportStatus(imp, ImportKind.AMBIGUOUS, entry);
        }
        importedByShortName.put(key, entry);
        return new ImportStatus(imp, ImportKind.OK, entry);
    }

    /** Estado de cada import, na ordem do ficheiro. */
    public List<ImportStatus> imports() {
        return List.copyOf(imports);
    }

    /** Nomes curtos/aliases introduzidos pelos imports visíveis. */
    public Map<String, ProjectIndexEntry> importedByShortName() {
        return Map.copyOf(importedByShortName);
    }

    /** Nomes de imports que existem mas não são {@code public}. */
    public Set<String> nonVisibleImportedNames() {
        return new HashSet<>(nonVisibleImported.keySet());
    }

    public Resolution resolve(String name) {
        ComponentDecl local = localLookup.apply(name);
        if (local != null) {
            return new Resolution.Local(local);
        }
        if (projectIndex == null) {
            return new Resolution.NotFound();
        }
        if (name.contains(".")) {
            return projectIndex.resolveQualified(name)
                .<Resolution>map(entry -> entry.isPublic()
                    ? new Resolution.Project(entry)
                    : new Resolution.NotVisible(entry, false))
                .orElseGet(Resolution.NotFound::new);
        }
        ProjectIndexEntry imported = importedByShortName.get(name);
        if (imported != null) {
            return new Resolution.Project(imported);
        }
        ProjectIndexEntry hidden = nonVisibleImported.get(name);
        if (hidden != null) {
            return new Resolution.NotVisible(hidden, true);
        }
        return new Resolution.NotFound();
    }
}
