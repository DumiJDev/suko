package io.suko.lang.ext;

import io.suko.ext.*;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.*;

/**
 * As extensões carregadas para um build (ou um projeto no LSP): alvos e
 * vocabulários por id, checkers por ordem estável de id da extensão.
 * Problemas de carregamento viram diagnósticos de projeto, nunca exceções.
 */
public final class ExtensionRegistry {

    /** Id da extensão JTE embutida; o LSP e o manifesto ignoram cópias dela. */
    public static final String BUILT_IN_JTE = "io.suko.jte";

    private final Map<String, Target> targets = new LinkedHashMap<>();
    private final Map<String, Vocabulary> vocabularies = new LinkedHashMap<>();
    private final List<Checker> checkers = new ArrayList<>();
    private final Map<Object, String> owners = new IdentityHashMap<>();
    private final List<SukoDiagnostic> loadDiagnostics = new ArrayList<>();

    private ExtensionRegistry() {
    }

    public static ExtensionRegistry load(ClassLoader loader) {
        List<SukoExtension> found = new ArrayList<>();
        List<SukoDiagnostic> problems = new ArrayList<>();
        Iterator<SukoExtension> it = ServiceLoader.load(SukoExtension.class, loader).iterator();
        while (true) {
            try {
                if (!it.hasNext()) {
                    break;
                }
            } catch (ServiceConfigurationError e) {
                problems.add(failed("Falhou a descoberta de extensões: " + describe(e)));
                break; // hasNext() a falhar pode repetir-se para sempre
            }
            try {
                found.add(it.next());
            } catch (ServiceConfigurationError e) {
                problems.add(failed("Falhou a descoberta de extensões: " + describe(e)));
            }
        }
        ExtensionRegistry registry = of(found);
        registry.loadDiagnostics.addAll(0, problems);
        return registry;
    }

    /** O registo do próprio classpath do compilador: o caso de quem não declara extensões. */
    public static ExtensionRegistry defaults() {
        return load(ExtensionRegistry.class.getClassLoader());
    }

    public static ExtensionRegistry of(List<SukoExtension> extensions) {
        ExtensionRegistry registry = new ExtensionRegistry();
        record Entry(String id, int apiVersion, SukoExtension extension) {
        }
        List<Entry> entries = new ArrayList<>();
        for (SukoExtension extension : extensions) {
            try {
                String id = extension.id();
                if (id == null || id.isBlank()) {
                    registry.error("EXTENSION_FAILED", "Uma extensão devolveu um id vazio: " + extension.getClass().getName());
                    continue;
                }
                entries.add(new Entry(id, extension.apiVersion(), extension));
            } catch (RuntimeException | LinkageError e) {
                registry.error("EXTENSION_FAILED", "Uma extensão (" + extension.getClass().getName()
                    + ") falhou ao ler id/apiVersion: " + describe(e));
            }
        }
        entries.sort(Comparator.comparing(Entry::id));
        Set<String> seenIds = new HashSet<>();
        for (Entry entry : entries) {
            if (!seenIds.add(entry.id())) {
                continue; // a mesma extensão vista duas vezes no classpath
            }
            registry.add(entry.extension(), entry.id(), entry.apiVersion());
        }
        return registry;
    }

    private static String describe(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    private static SukoDiagnostic failed(String message) {
        return new SukoDiagnostic(SukoDiagnostic.Severity.ERROR, message, "EXTENSION_FAILED", null, SourceSpan.NONE);
    }

    private void add(SukoExtension extension, String id, int apiVersion) {
        if (apiVersion != ExtensionApi.VERSION) {
            error("EXTENSION_API_MISMATCH", "A extensão '" + id + "' foi compilada para a API de extensões "
                + apiVersion + ", mas este compilador usa a versão " + ExtensionApi.VERSION);
            return;
        }
        try {
            extension.register(new ExtensionContext() {
                public void target(Target target) {
                    claim(targets, target.id(), target, id, "alvo");
                }

                public void vocabulary(Vocabulary vocabulary) {
                    claim(vocabularies, vocabulary.id(), vocabulary, id, "vocabulário");
                }

                public void checker(Checker checker) {
                    checkers.add(checker);
                    owners.put(checker, id);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            error("EXTENSION_FAILED", "A extensão '" + id + "' falhou ao registar-se: " + describe(e));
        }
    }

    private <T> void claim(Map<String, T> map, String key, T value, String extensionId, String kind) {
        T existing = map.get(key);
        if (existing != null) {
            error("EXTENSION_CONFLICT", "O " + kind + " '" + key + "' é fornecido por '" + owners.get(existing)
                + "' e por '" + extensionId + "'; fica o de '" + owners.get(existing) + "'");
            return;
        }
        map.put(key, value);
        owners.put(value, extensionId);
    }

    private void error(String code, String message) {
        loadDiagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR, message, code, null, SourceSpan.NONE));
    }

    public Optional<Target> target(String id) {
        return Optional.ofNullable(targets.get(id));
    }

    public Set<String> targetIds() {
        return Collections.unmodifiableSet(targets.keySet());
    }

    public Optional<Vocabulary> vocabulary(String id) {
        return Optional.ofNullable(vocabularies.get(id));
    }

    /** Ordenados pelo id da extensão (as extensões são registadas por essa ordem). */
    public List<Checker> checkers() {
        return Collections.unmodifiableList(checkers);
    }

    public String ownerOf(Object contribution) {
        return owners.getOrDefault(contribution, "?");
    }

    public List<SukoDiagnostic> loadDiagnostics() {
        return Collections.unmodifiableList(loadDiagnostics);
    }
}
