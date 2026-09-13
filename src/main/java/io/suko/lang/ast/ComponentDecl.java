package io.suko.lang.ast;

import java.util.List;

public record ComponentDecl(String name, List<String> typeParameters, List<Param> params,
                             List<Statement> body, SourceSpan span) {
}
