package io.suko.lang.ext;

import io.suko.ext.Target;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;
import java.util.Set;

/**
 * REACTIVE_UNSUPPORTED (13b): um reactive component pedido para um alvo que não declara a capacidade
 * {@link Target#CAPABILITY_REACTIVE}. Vive no core porque é o core que conhece os alvos pedidos (o
 * CheckContext das extensões só expõe vocabulários).
 */
public final class CapabilityChecker {

    private CapabilityChecker() {
    }

    public static void check(SukoFile file, String fileName, List<Target> targets, ExtensionRegistry registry,
                             DiagnosticCollector diagnostics) {
        List<ComponentDecl> reactive = file.components().stream().filter(ComponentDecl::reactive).toList();
        if (reactive.isEmpty()) {
            return;
        }
        for (Target target : targets) {
            String id;
            Set<String> capabilities;
            try {
                id = target.id();
                capabilities = Set.copyOf(target.capabilities());
            } catch (Throwable e) {
                ExtensionFailures.rethrowFatal(e);
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "A extensão '" + registry.ownerOf(target) + "' falhou em " + fileName + " (capacidades do alvo): "
                        + VocabularyChecker.describe(e), "EXTENSION_FAILED", fileName, SourceSpan.NONE));
                continue;
            }
            if (capabilities.contains(Target.CAPABILITY_REACTIVE)) {
                continue;
            }
            for (ComponentDecl component : reactive) {
                diagnostics.add(new SukoDiagnostic(SukoDiagnostic.Severity.ERROR,
                    "O alvo '" + id + "' não suporta componentes reativos: '" + component.name()
                        + "' não pode ser compilado para ele", "REACTIVE_UNSUPPORTED", fileName, component.nameSpan()));
            }
        }
    }
}
