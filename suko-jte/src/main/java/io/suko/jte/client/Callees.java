package io.suko.jte.client;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ParamInfo;
import io.suko.lang.project.ProjectIndexEntry;
import io.suko.lang.project.ProjectView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolve o nome de uma chamada de componente para saber se é reativo e quais são os seus parâmetros, como o
 * emissor JTE resolve (o próprio ficheiro, os imports, nomes qualificados). Visibilidade e "não encontrado" são
 * do core (SemanticChecker), que já os reporta; aqui um nome que não resolve dá {@code Optional.empty()}.
 */
public final class Callees {

    public record Callee(boolean reactive, List<ParamInfo> params) {
    }

    private final Map<String, ComponentDecl> local = new HashMap<>();
    private final Map<String, ProjectIndexEntry> imported;
    private final ProjectView project;

    public Callees(SukoFile file, ProjectView project) {
        for (ComponentDecl c : file.components()) {
            local.putIfAbsent(c.name(), c);
        }
        this.imported = project.resolveImports(file.imports());
        this.project = project;
    }

    public Optional<Callee> resolve(String name) {
        ComponentDecl decl = local.get(name);
        if (decl != null) {
            return Optional.of(new Callee(decl.reactive(), decl.params().stream().map(ParamInfo::of).toList()));
        }
        ProjectIndexEntry entry = name.contains(".") ? project.resolveQualified(name).orElse(null) : imported.get(name);
        return Optional.ofNullable(entry).map(e -> new Callee(e.reactive(), e.params()));
    }
}
