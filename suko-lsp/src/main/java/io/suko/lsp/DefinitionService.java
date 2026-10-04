package io.suko.lsp;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.project.CallResolver;
import io.suko.lang.project.ParamInfo;
import io.suko.lang.project.ProjectIndexEntry;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Go-to-definition, com a resolução do compilador ({@link CallResolver}):
 * nome de componente (numa chamada, como valor, ou num {@code import}) → o
 * nome na declaração; nome de argumento ou de slot → o parâmetro.
 */
final class DefinitionService {

    private DefinitionService() {
    }

    static List<Location> definition(DocumentContext ctx, Position position) {
        Optional<AstQuery.Cursor> cursor = AstQuery.at(ctx.ast, ctx.codePointAt(position));
        if (cursor.isEmpty()) {
            return List.of();
        }
        Location location = switch (cursor.get()) {
            case AstQuery.Cursor.CallName call -> componentTarget(ctx, ctx.resolver.resolve(call.name()));
            case AstQuery.Cursor.ImportName imp -> ctx.snapshot.analysis().index()
                .resolveQualified(imp.decl().qualifiedName())
                .map(entry -> ctx.locationOf(entry.sourceFile(), entry.nameSpan())).orElse(null);
            case AstQuery.Cursor.ArgName arg -> paramTarget(ctx, arg.callName(), arg.argName());
            case AstQuery.Cursor.SlotName slot -> paramTarget(ctx, slot.callName(), slot.slotName());
        };
        return location == null ? List.of() : List.of(location);
    }

    private static Location componentTarget(DocumentContext ctx, CallResolver.Resolution resolution) {
        return switch (resolution) {
            case CallResolver.Resolution.Local local -> ctx.locationOf(ctx.absolute(), local.decl().nameSpan());
            case CallResolver.Resolution.Project project ->
                ctx.locationOf(project.entry().sourceFile(), project.entry().nameSpan());
            // Existe mas não é `public`: o compilador já o diagnostica; navegar ajuda a perceber porquê.
            case CallResolver.Resolution.NotVisible hidden ->
                ctx.locationOf(hidden.entry().sourceFile(), hidden.entry().nameSpan());
            case CallResolver.Resolution.NotFound notFound -> null;
        };
    }

    private static Location paramTarget(DocumentContext ctx, String callName, String paramName) {
        CallResolver.Resolution resolution = ctx.resolver.resolve(callName);
        Path file;
        List<ParamInfo> params;
        switch (resolution) {
            case CallResolver.Resolution.Local local -> {
                file = ctx.absolute();
                params = local.decl().params().stream().map(ParamInfo::of).toList();
            }
            case CallResolver.Resolution.Project project -> {
                file = project.entry().sourceFile();
                params = project.entry().params();
            }
            case CallResolver.Resolution.NotVisible hidden -> {
                file = hidden.entry().sourceFile();
                params = hidden.entry().params();
            }
            case CallResolver.Resolution.NotFound notFound -> {
                return null;
            }
        }
        for (ParamInfo param : params) {
            if (param.name().equals(paramName)) {
                SourceSpan span = param.nameSpan();
                return ctx.locationOf(file, span);
            }
        }
        return null;
    }
}
