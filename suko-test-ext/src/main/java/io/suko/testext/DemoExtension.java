package io.suko.testext;

import io.suko.ext.*;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.diagnostic.SukoDiagnostic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class DemoExtension implements SukoExtension {

    public String id() {
        return "io.suko.testext";
    }

    public int apiVersion() {
        return ExtensionApi.VERSION;
    }

    public void register(ExtensionContext ctx) {
        ctx.target(new Target() {
            public String id() { return "demo"; }
            public String componentType() { return "demo.Node"; }
            public Set<String> vocabularies() { return Set.of("demo"); }
            public Emitted emit(ComponentDecl c, EmitContext e) {
                return new Emitted(c.name() + ".demo", "demo:" + c.name() + "\n", List.of());
            }
        });
        ctx.vocabulary(new Vocabulary() {
            public String id() { return "demo"; }
            public boolean open() { return false; }
            public Optional<TagSpec> tag(String name) {
                return Set.of("box", "label").contains(name)
                    ? Optional.of(new TagSpec(name, Map.of(), true)) : Optional.empty();
            }
        });
        ctx.checker(new Checker() {
            public String id() { return "demo-check"; }
            public void check(SukoFile file, CheckContext ctx) {
                for (ComponentDecl c : file.components()) {
                    if (c.name().equals("Boom")) {
                        // Para os testes de robustez (Task 8): uma extensão que rebenta.
                        throw new IllegalStateException("boom");
                    }
                    ctx.report(SukoDiagnostic.Severity.WARNING, "DEMO_CHECK", "visto: " + c.name(), c.span());
                }
            }
        });
    }
}
