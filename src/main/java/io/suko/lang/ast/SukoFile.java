package io.suko.lang.ast;

import java.util.List;
import java.util.Optional;

public record SukoFile(Optional<String> packageName, List<String> imports, List<ComponentDecl> components) {
}
