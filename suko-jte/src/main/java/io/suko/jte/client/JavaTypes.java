package io.suko.jte.client;

import io.suko.lang.ast.Type;

import java.util.List;
import java.util.Optional;

/**
 * De onde vêm as formas dos records Java (R13 do plano 1 da 13b). Em produção é {@link #NONE} até o
 * utilizador decidir a origem dos tipos Java (item 12); os testes usam uma implementação em memória.
 */
@FunctionalInterface
public interface JavaTypes {

    /** Um componente de record: nome e tipo como se escreve em Suko. */
    record Field(String name, Type type) {
    }

    /** Os componentes do record com este nome simples, por ordem; vazio se não é um record conhecido. */
    Optional<List<Field>> record(String simpleName);

    JavaTypes NONE = simpleName -> Optional.empty();
}
