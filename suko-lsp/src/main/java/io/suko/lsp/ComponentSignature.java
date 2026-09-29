package io.suko.lsp;

import io.suko.lang.ast.Cardinality;
import io.suko.lang.project.CallResolver;
import io.suko.lang.project.ParamInfo;
import io.suko.lang.project.ProjectIndexEntry;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Um componente visto de fora, seja declarado no ficheiro aberto ou noutro:
 * o que hover e completion mostram. {@code file} é relativo ao source root.
 */
record ComponentSignature(String name, String qualifiedName, String packageName, boolean isPublic,
                          List<ParamInfo> params, Path file) {

    static Optional<ComponentSignature> of(DocumentContext ctx, CallResolver.Resolution resolution) {
        return switch (resolution) {
            case CallResolver.Resolution.Local local -> {
                String pkg = ctx.ast.packageName().orElse("");
                yield Optional.of(new ComponentSignature(local.decl().name(),
                    pkg.isEmpty() ? local.decl().name() : pkg + "." + local.decl().name(), pkg,
                    local.decl().isPublic(), local.decl().params().stream().map(ParamInfo::of).toList(),
                    ctx.relative));
            }
            case CallResolver.Resolution.Project project -> Optional.of(of(ctx, project.entry()));
            case CallResolver.Resolution.NotVisible hidden -> Optional.of(of(ctx, hidden.entry()));
            case CallResolver.Resolution.NotFound notFound -> Optional.empty();
        };
    }

    static ComponentSignature of(DocumentContext ctx, ProjectIndexEntry entry) {
        String qualified = entry.qualifiedName();
        int dot = qualified.lastIndexOf('.');
        return new ComponentSignature(entry.simpleName(), qualified, dot < 0 ? "" : qualified.substring(0, dot),
            entry.isPublic(), entry.params(), ctx.project.root().relativize(entry.sourceFile()));
    }

    /** {@code public component Card(String title, Component header = null)}, quebrado em linhas se comprido. */
    String signature() {
        String head = (isPublic ? "public " : "") + "component " + name + "(";
        String inline = head + params.stream().map(ComponentSignature::paramText).collect(Collectors.joining(", ")) + ")";
        if (inline.length() <= 90 || params.isEmpty()) {
            return inline;
        }
        return head + "\n" + params.stream().map(p -> "    " + paramText(p)).collect(Collectors.joining(",\n")) + "\n)";
    }

    static String paramText(ParamInfo p) {
        return p.type() + " " + p.name() + p.defaultText().map(d -> " = " + d).orElse("");
    }

    /** Uma linha por slot, com cardinalidade e se é obrigatório. */
    List<String> slotLines() {
        return params.stream().filter(ParamInfo::slot).map(ComponentSignature::slotLine).toList();
    }

    static String slotLine(ParamInfo slot) {
        boolean many = slot.cardinality().orElse(Cardinality.ONE) == Cardinality.MANY;
        String how;
        if (slot.name().equals("children")) {
            // o slot implícito: o dev Java não sabe que é o corpo `{ … }` da chamada
            how = many ? "o corpo da chamada `{ … }`, repetível" : "o corpo da chamada `{ … }`";
        } else if (slot.renderProp()) {
            how = "bloco que recebe um valor (render-prop)";
        } else {
            how = many ? "vários blocos" : "um bloco";
        }
        String required = slot.requiredSlot() ? "obrigatório" : "opcional";
        return "`" + slot.name() + "` — " + how + ", " + required;
    }
}
