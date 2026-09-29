package io.suko.lsp;

import io.suko.lang.project.CallResolver;
import io.suko.lang.project.ParamInfo;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Hover sobre um componente (chamada, valor ou {@code import}): assinatura com
 * tipos e valores por omissão, slots e cardinalidade, visibilidade, package e
 * ficheiro. Sobre o nome de um argumento ou de um slot: esse parâmetro.
 */
final class HoverService {

    private HoverService() {
    }

    static Hover hover(DocumentContext ctx, Position position) {
        Optional<AstQuery.Cursor> cursor = AstQuery.at(ctx.ast, ctx.codePointAt(position));
        if (cursor.isEmpty()) {
            return null;
        }
        Optional<String> markdown = switch (cursor.get()) {
            case AstQuery.Cursor.CallName call -> componentMarkdown(ctx, ctx.resolver.resolve(call.name()));
            case AstQuery.Cursor.ImportName imp -> ctx.snapshot.analysis().index()
                .resolveQualified(imp.decl().qualifiedName())
                .map(entry -> componentMarkdown(ctx, ComponentSignature.of(ctx, entry)));
            case AstQuery.Cursor.ArgName arg -> paramMarkdown(ctx, arg.callName(), arg.argName());
            case AstQuery.Cursor.SlotName slot -> paramMarkdown(ctx, slot.callName(), slot.slotName());
        };
        if (markdown.isEmpty()) {
            return null;
        }
        Hover hover = new Hover(new MarkupContent(MarkupKind.MARKDOWN, markdown.get()));
        hover.setRange(ctx.mapper.rangeOf(cursor.get().span()));
        return hover;
    }

    private static Optional<String> componentMarkdown(DocumentContext ctx, CallResolver.Resolution resolution) {
        return ComponentSignature.of(ctx, resolution).map(sig -> componentMarkdown(ctx, sig));
    }

    static String componentMarkdown(DocumentContext ctx, ComponentSignature sig) {
        List<String> parts = new ArrayList<>();
        parts.add("```suko\n" + sig.signature() + "\n```");
        List<String> slots = sig.slotLines();
        if (!slots.isEmpty()) {
            parts.add("**Slots**\n\n" + String.join("\n", slots.stream().map(s -> "- " + s).toList()));
        }
        String where = (sig.isPublic() ? "`public`" : "privado a este ficheiro")
            + (sig.packageName().isEmpty() ? "" : " · package `" + sig.packageName() + "`")
            + " · `" + sig.file().toString().replace('\\', '/') + "`";
        parts.add(where);
        return String.join("\n\n", parts);
    }

    private static Optional<String> paramMarkdown(DocumentContext ctx, String callName, String paramName) {
        Optional<ComponentSignature> sig = ComponentSignature.of(ctx, ctx.resolver.resolve(callName));
        if (sig.isEmpty()) {
            return Optional.empty();
        }
        for (ParamInfo param : sig.get().params()) {
            if (param.name().equals(paramName)) {
                String detail = "```suko\n" + ComponentSignature.paramText(param) + "\n```";
                String extra = param.slot() ? "\n\n" + ComponentSignature.slotLine(param) : "";
                return Optional.of(detail + extra + "\n\nparâmetro de `" + sig.get().name() + "`");
            }
        }
        return Optional.empty();
    }
}
