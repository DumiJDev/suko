package io.suko.lsp;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SourceSpan;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.CallResolver;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tudo o que um pedido sobre um documento precisa, calculado uma vez: o
 * projecto, o snapshot (texto + verificação do mesmo instante), o AST tolerante
 * e o {@link CallResolver} — o mesmo que o compilador usa, para que o editor
 * nunca navegue para um sítio diferente daquele contra o qual valida.
 */
final class DocumentContext {

    final Project project;
    final Path relative;
    final Project.Snapshot snapshot;
    final String text;
    final PositionMapper mapper;
    final SukoFile ast;
    final CallResolver resolver;
    private final DocumentUris uris;

    private DocumentContext(Project project, Path relative, Project.Snapshot snapshot, String text,
                            SukoFile ast, DocumentUris uris) {
        this.project = project;
        this.relative = relative;
        this.snapshot = snapshot;
        this.text = text;
        this.mapper = new PositionMapper(text);
        this.ast = ast;
        this.uris = uris;

        Map<String, ComponentDecl> local = new HashMap<>();
        ast.components().forEach(component -> local.put(component.name(), component));
        this.resolver = new CallResolver(local::get, snapshot.analysis().index(), ast.imports());
    }

    /** Vazio se o documento não pertence a nenhum source root ou não é conhecido. */
    static Optional<DocumentContext> of(Workspace workspace, DocumentUris uris, String uri) {
        Path file = Workspace.pathOf(uri);
        Optional<Project> project = workspace.projectFor(file);
        if (project.isEmpty()) {
            return Optional.empty();
        }
        Path relative = project.get().root().relativize(file);
        Project.Snapshot snapshot = project.get().snapshot();
        String text = snapshot.sources().files().get(relative);
        SukoFile ast = project.get().tolerantAst(relative);
        if (text == null || ast == null) {
            return Optional.empty();
        }
        return Optional.of(new DocumentContext(project.get(), relative, snapshot, text, ast, uris));
    }

    int codePointAt(Position position) {
        return mapper.codePointIndexAt(position);
    }

    /** Localização de um span num ficheiro do projecto (caminho absoluto), ou {@code null} se o ficheiro não existe no snapshot. */
    Location locationOf(Path absoluteFile, SourceSpan span) {
        Path relativeFile = project.root().relativize(absoluteFile.toAbsolutePath().normalize());
        String fileText = snapshot.sources().files().get(relativeFile);
        if (fileText == null) {
            return null;
        }
        PositionMapper fileMapper = relativeFile.equals(relative) ? mapper : new PositionMapper(fileText);
        return new Location(uris.uriOf(absoluteFile), fileMapper.rangeOf(span));
    }

    Path absolute() {
        return project.root().resolve(relative);
    }
}
