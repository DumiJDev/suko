package io.suko.ext;

import java.util.Map;

/** {@code attributeTypes}: nome do atributo → tipo Java esperado (ex.: "spacing" → "int"). */
public record TagSpec(String name, Map<String, String> attributeTypes, boolean allowsChildren) {
}
