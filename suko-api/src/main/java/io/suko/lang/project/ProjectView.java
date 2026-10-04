package io.suko.lang.project;

import io.suko.lang.ast.ImportDecl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Vista só de leitura do índice do projeto, para extensões. Implementada pelo ProjectIndex do core. */
public interface ProjectView {

    Collection<ProjectIndexEntry> entries();

    Optional<ProjectIndexEntry> resolveQualified(String qualifiedName);

    Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports);

    ProjectView EMPTY = new ProjectView() {
        public Collection<ProjectIndexEntry> entries() { return List.of(); }
        public Optional<ProjectIndexEntry> resolveQualified(String qualifiedName) { return Optional.empty(); }
        public Map<String, ProjectIndexEntry> resolveImports(List<ImportDecl> imports) { return Map.of(); }
    };
}
