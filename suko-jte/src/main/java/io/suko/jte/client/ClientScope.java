package io.suko.jte.client;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Os nomes visíveis numa expressão do cliente e o que são (imutável). */
public final class ClientScope {

    /** STATE/DERIVED leem-se de {@code $s}, PARAM de {@code $p}, LOCAL é uma variável JS (R17). */
    public enum Kind { STATE, DERIVED, PARAM, LOCAL }

    public record Binding(Kind kind, ClientType type) {
    }

    public static final ClientScope EMPTY = new ClientScope(Map.of());

    private final Map<String, Binding> bindings;

    private ClientScope(Map<String, Binding> bindings) {
        this.bindings = bindings;
    }

    public ClientScope with(String name, Kind kind, ClientType type) {
        Map<String, Binding> copy = new LinkedHashMap<>(bindings);
        copy.put(name, new Binding(kind, type));
        return new ClientScope(Collections.unmodifiableMap(copy));
    }

    public Optional<Binding> lookup(String name) {
        return Optional.ofNullable(bindings.get(name));
    }

    public Map<String, Binding> bindings() {
        return bindings;
    }
}
