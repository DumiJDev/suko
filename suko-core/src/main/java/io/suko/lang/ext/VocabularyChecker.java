package io.suko.lang.ext;

import io.suko.ext.Target;
import io.suko.ext.Vocabulary;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.Statement;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Para cada alvo pedido, cada tag tem de ser aceite por pelo menos um dos
 * seus vocabulários; um vocabulário aberto aceita tudo. Só com o alvo jte
 * (HTML aberto) nunca dispara.
 */
public final class VocabularyChecker {

    private VocabularyChecker() {
    }

    /** Falha de uma contribuição (alvo/vocabulário) a meio da verificação de um alvo. */
    private static final class ContributionFailure extends RuntimeException {
        final transient Object contribution;

        ContributionFailure(Object contribution, Throwable cause) {
            super(cause);
            this.contribution = contribution;
        }
    }

    public static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    public static void check(SukoFile file, String fileName, List<Target> targets, ExtensionRegistry registry,
                      DiagnosticCollector diagnostics) {
        for (Target target : targets) {
            try {
                Set<String> ids;
                try {
                    ids = new TreeSet<>(target.vocabularies());
                } catch (Throwable e) {
                    io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                    throw new ContributionFailure(target, e);
                }
                List<Vocabulary> vocabularies = ids.stream()
                    .flatMap(id -> registry.vocabulary(id).stream()).toList();
                for (Vocabulary v : vocabularies) {
                    boolean open;
                    try {
                        open = v.open();
                    } catch (Throwable e) {
                        io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                        throw new ContributionFailure(v, e);
                    }
                    if (open) {
                        ids = null;
                        break;
                    }
                }
                if (ids == null || vocabularies.isEmpty()) {
                    continue; // aberto, ou nenhum vocabulário resolvido (já é VOCABULARY_NOT_FOUND)
                }
                for (ComponentDecl component : file.components()) {
                    walk(component.body(), target, ids, vocabularies, fileName, diagnostics);
                }
            } catch (ContributionFailure f) {
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "A extensão '" + registry.ownerOf(f.contribution) + "' falhou em " + fileName
                        + " (alvo '" + target.id() + "'): " + describe(f.getCause()),
                    "EXTENSION_FAILED", fileName, SourceSpan.NONE));
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "A extensão '" + registry.ownerOf(target) + "' falhou em " + fileName
                        + " (alvo '" + target.id() + "'): " + describe(e),
                    "EXTENSION_FAILED", fileName, SourceSpan.NONE));
            }
        }
    }

    private static boolean known(List<Vocabulary> vocabularies, String tag) {
        for (Vocabulary v : vocabularies) {
            try {
                if (v.tag(tag).isPresent()) {
                    return true;
                }
            } catch (Throwable e) {
                io.suko.lang.ext.ExtensionFailures.rethrowFatal(e);
                throw new ContributionFailure(v, e);
            }
        }
        return false;
    }

    private static void walk(List<Statement> statements, Target target, Set<String> vocabularyIds,
                             List<Vocabulary> vocabularies,
                             String fileName, DiagnosticCollector diagnostics) {
        for (Statement statement : statements) {
            switch (statement) {
                case Statement.HtmlElement e -> {
                    boolean known = known(vocabularies, e.tagName());
                    if (!known) {
                        diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                            "A tag '" + e.tagName() + "' não existe no alvo '" + target.id() + "' (vocabulários: "
                                + String.join(", ", vocabularyIds) + ")",
                            "UNKNOWN_TAG", fileName, e.span()));
                    }
                    walk(e.children(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                }
                case Statement.IfStmt s -> {
                    walk(s.thenBranch(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                    walk(s.elseBranch(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                }
                case Statement.ForStmt s -> walk(s.body(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                case Statement.SwitchStmt s -> {
                    for (Statement.SwitchCase c : s.cases()) {
                        walk(c.body(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                    }
                    walk(s.defaultCase(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                }
                case Statement.ComponentCallStmt call -> {
                    for (Statement.SlotFill fill : call.slotFills()) {
                        walk(fill.body(), target, vocabularyIds, vocabularies, fileName, diagnostics);
                    }
                }
                default -> {
                }
            }
        }
    }
}
