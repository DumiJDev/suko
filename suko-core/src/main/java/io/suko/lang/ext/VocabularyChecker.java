package io.suko.lang.ext;

import io.suko.ext.Target;
import io.suko.ext.Vocabulary;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;

/**
 * Para cada alvo pedido, cada tag tem de ser aceite por pelo menos um dos
 * seus vocabulários; um vocabulário aberto aceita tudo. Só com o alvo jte
 * (HTML aberto) nunca dispara.
 */
public final class VocabularyChecker {

    private VocabularyChecker() {
    }

    public static void check(SukoFile file, String fileName, List<Target> targets, ExtensionRegistry registry,
                      DiagnosticCollector diagnostics) {
        for (Target target : targets) {
            List<Vocabulary> vocabularies = target.vocabularies().stream()
                .flatMap(id -> registry.vocabulary(id).stream()).toList();
            if (vocabularies.stream().anyMatch(Vocabulary::open)) {
                continue;
            }
            for (ComponentDecl component : file.components()) {
                walk(component.body(), target, vocabularies, fileName, diagnostics);
            }
        }
    }

    private static void walk(List<Statement> statements, Target target, List<Vocabulary> vocabularies,
                             String fileName, DiagnosticCollector diagnostics) {
        for (Statement statement : statements) {
            switch (statement) {
                case Statement.HtmlElement e -> {
                    boolean known = vocabularies.stream().anyMatch(v -> v.tag(e.tagName()).isPresent());
                    if (!known) {
                        diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                            "A tag '" + e.tagName() + "' não existe no alvo '" + target.id() + "' (vocabulários: "
                                + String.join(", ", target.vocabularies()) + ")",
                            "UNKNOWN_TAG", fileName, e.span()));
                    }
                    walk(e.children(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.IfStmt s -> {
                    walk(s.thenBranch(), target, vocabularies, fileName, diagnostics);
                    walk(s.elseBranch(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.ForStmt s -> walk(s.body(), target, vocabularies, fileName, diagnostics);
                case Statement.SwitchStmt s -> {
                    for (Statement.SwitchCase c : s.cases()) {
                        walk(c.body(), target, vocabularies, fileName, diagnostics);
                    }
                    walk(s.defaultCase(), target, vocabularies, fileName, diagnostics);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), target, vocabularies, fileName, diagnostics);
                    }
                }
                default -> {
                }
            }
        }
    }
}
