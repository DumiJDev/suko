package io.suko.lang.symbol;

import io.suko.lang.ast.ComponentDecl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Tabela de símbolos para o Subprojeto 2 — Verificador Suko.
 * Registra componentes declarados e permite lookup por nome.
 */
public class SymbolTable {

    /** Mapeia o nome do componente (ex: "Card") para o ComponentDecl do AST. */
    private final Map<String, ComponentDecl> components = new HashMap<>();

    /** Componentes já registrados. */
    public Set<ComponentDecl> getAllComponents() {
        return new HashSet<>(components.values());
    }

    /** Registra um componente no tabela. Se já existir, substitui o anterior. */
    public void register(ComponentDecl component) {
        components.put(component.name(), component);
    }

    /** Busca um componente pelo nome. Retorna null se não encontrar. */
    public ComponentDecl lookup(String name) {
        return components.get(name);
    }

    /** Remove um componente da tabela. */
    public void remove(String name) {
        components.remove(name);
    }

    /** Obtém o número total de componentes registrados. */
    public int size() {
        return components.size();
    }
}
