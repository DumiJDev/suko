package io.suko.ext;

import io.suko.lang.ast.SukoFile;

/** Regra extra do verificador. Corre depois do SemanticChecker, por ordem de id da extensão. */
public interface Checker {
    String id();

    void check(SukoFile file, CheckContext ctx);
}
