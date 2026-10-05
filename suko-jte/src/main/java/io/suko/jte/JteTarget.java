package io.suko.jte;

import io.suko.ext.EmitContext;
import io.suko.ext.Emitted;
import io.suko.ext.Target;
import io.suko.lang.JteEmitter;
import io.suko.lang.ast.ComponentDecl;

import java.util.Set;

/** O alvo de sempre: cada componente vira um .jte, com o emitter do subprojeto 1 intacto. */
public final class JteTarget implements Target {

    public String id() {
        return "jte";
    }

    public String componentType() {
        return "gg.jte.Content";
    }

    public Set<String> vocabularies() {
        return Set.of("html");
    }

    public Emitted emit(ComponentDecl component, EmitContext ctx) {
        JteEmitter emitter = new JteEmitter(ctx.file().components(), ctx.importedByShortName(), ctx.packagePrefix(), ctx.options());
        JteEmitter.EmitResult result = emitter.emitWithSourceMap(component);
        return new Emitted(component.name() + ".jte", result.jteSource(), result.sourceMap());
    }

    @Override
    public java.util.List<io.suko.ext.ProjectOutput> emitProject(io.suko.ext.ProjectEmitContext ctx) {
        return java.util.List.of(new io.suko.ext.ProjectOutput(io.suko.ext.ProjectOutput.Kind.JAVA_SOURCE,
            SukoSafeSource.relativePath(ctx.options()), SukoSafeSource.generate(ctx.options())));
    }
}
