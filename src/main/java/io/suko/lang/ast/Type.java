package io.suko.lang.ast;

import java.util.List;

public record Type(String name, List<Type> typeArguments, int arrayDimensions) {

    public boolean isSlot() {
        return "slot".equals(name);
    }
}
