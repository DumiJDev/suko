package io.suko.lsp;

import io.suko.lang.ast.ImportDecl;
import io.suko.lang.project.CallResolver;
import io.suko.lang.project.ParamInfo;
import io.suko.lang.project.ProjectIndexEntry;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Completion de componentes, parâmetros e slots. O contexto vem dos tokens do
 * lexer ({@link CompletionContext}); o que se sugere vem da mesma resolução do
 * compilador ({@link CallResolver} + {@code ProjectIndex}).
 *
 * <p>Um componente {@code public} ainda não importado também aparece, e ao
 * aceitá-lo acrescenta o {@code import} (o import é obrigatório mesmo no mesmo
 * package). Java dentro de {@code ${...}} fica para o 11c.
 */
final class CompletionService {

    private static final List<String> KEYWORDS = List.of("if", "else", "for", "switch", "case", "default");

    private CompletionService() {
    }

    static List<CompletionItem> complete(DocumentContext ctx, Position position) {
        int cursor = ctx.mapper.charOffsetAt(position);
        CompletionContext.Result context = CompletionContext.analyze(ctx.text.substring(0, cursor));

        return switch (context.kind()) {
            case NONE -> List.of();
            case IMPORT -> importItems(ctx, wordRange(ctx, cursor, true), cursor);
            case BODY -> bodyItems(ctx, wordRange(ctx, cursor, false));
            case CALL_BLOCK -> {
                List<CompletionItem> items = new ArrayList<>(slotItems(ctx, context.callName(), wordRange(ctx, cursor, false)));
                items.addAll(bodyItems(ctx, wordRange(ctx, cursor, false)));
                yield items;
            }
            case ARGUMENT -> argumentItems(ctx, context, wordRange(ctx, cursor, false));
        };
    }

    // ---------------------------------------------------------------- import

    private static List<CompletionItem> importItems(DocumentContext ctx, Range range, int cursor) {
        Set<String> alreadyImported = new HashSet<>();
        for (ImportDecl imp : ctx.ast.imports()) {
            alreadyImported.add(imp.qualifiedName());
        }
        boolean semicolonFollows = ctx.text.substring(cursor).stripLeading().startsWith(";")
            && ctx.text.substring(cursor).indexOf('\n') != 0;
        List<CompletionItem> items = new ArrayList<>();
        for (ProjectIndexEntry entry : ctx.snapshot.analysis().index().entries()) {
            if (!entry.isPublic() || alreadyImported.contains(entry.qualifiedName())
                    || sameFile(ctx, entry)) {
                continue;
            }
            CompletionItem item = new CompletionItem(entry.qualifiedName());
            item.setKind(CompletionItemKind.Module);
            item.setDetail(ComponentSignature.of(ctx, entry).signature().lines().findFirst().orElse(""));
            item.setFilterText(entry.qualifiedName());
            item.setTextEdit(Either.forLeft(new TextEdit(range,
                entry.qualifiedName() + (semicolonFollows ? "" : ";"))));
            item.setSortText("0" + entry.qualifiedName());
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------ body/slots

    private static List<CompletionItem> bodyItems(DocumentContext ctx, Range range) {
        List<CompletionItem> items = new ArrayList<>();
        Set<String> visibleNames = new HashSet<>();
        Set<String> importedQualified = new HashSet<>();

        for (var component : ctx.ast.components()) {
            visibleNames.add(component.name());
            var signature = ComponentSignature.of(ctx, ctx.resolver.resolve(component.name())).orElse(null);
            items.add(componentItem(ctx, component.name(), signature, range, "0", null));
        }
        for (Map.Entry<String, ProjectIndexEntry> imported : ctx.resolver.importedByShortName().entrySet()) {
            if (!visibleNames.add(imported.getKey())) {
                continue;
            }
            importedQualified.add(imported.getValue().qualifiedName());
            items.add(componentItem(ctx, imported.getKey(), ComponentSignature.of(ctx, imported.getValue()),
                range, "1", null));
        }
        for (ProjectIndexEntry entry : ctx.snapshot.analysis().index().entries()) {
            if (!entry.isPublic() || sameFile(ctx, entry) || importedQualified.contains(entry.qualifiedName())
                    || visibleNames.contains(entry.simpleName())) {
                continue;
            }
            items.add(componentItem(ctx, entry.simpleName(), ComponentSignature.of(ctx, entry), range, "2",
                importEdit(ctx, entry.qualifiedName())));
        }
        for (String keyword : KEYWORDS) {
            CompletionItem item = new CompletionItem(keyword);
            item.setKind(CompletionItemKind.Keyword);
            item.setTextEdit(Either.forLeft(new TextEdit(range, keyword)));
            item.setSortText("3" + keyword);
            items.add(item);
        }
        return items;
    }

    private static CompletionItem componentItem(DocumentContext ctx, String label, ComponentSignature signature,
                                                Range range, String sortGroup, TextEdit autoImport) {
        CompletionItem item = new CompletionItem(label);
        item.setKind(CompletionItemKind.Class);
        item.setInsertTextFormat(InsertTextFormat.Snippet);
        item.setTextEdit(Either.forLeft(new TextEdit(range, label + "($0)")));
        item.setSortText(sortGroup + label);
        if (signature != null) {
            item.setDetail(autoImport == null
                ? signature.signature().lines().findFirst().orElse("")
                : signature.qualifiedName() + " — acrescenta o import");
            item.setDocumentation(new MarkupContent(MarkupKind.MARKDOWN, HoverService.componentMarkdown(ctx, signature)));
        }
        if (autoImport != null) {
            item.setAdditionalTextEdits(List.of(autoImport));
        }
        return item;
    }

    private static List<CompletionItem> slotItems(DocumentContext ctx, String callName, Range range) {
        Optional<ComponentSignature> signature = ComponentSignature.of(ctx, ctx.resolver.resolve(callName));
        List<CompletionItem> items = new ArrayList<>();
        for (ParamInfo param : signature.map(ComponentSignature::params).orElse(List.of())) {
            if (!param.slot()) {
                continue;
            }
            CompletionItem item = new CompletionItem(param.name());
            item.setKind(CompletionItemKind.Field);
            item.setDetail(ComponentSignature.slotLine(param));
            item.setInsertTextFormat(InsertTextFormat.Snippet);
            item.setTextEdit(Either.forLeft(new TextEdit(range, param.name() + " { $0 }")));
            item.setSortText("0" + param.name());
            items.add(item);
        }
        return items;
    }

    // ------------------------------------------------------------- arguments

    private static List<CompletionItem> argumentItems(DocumentContext ctx, CompletionContext.Result context, Range range) {
        Optional<ComponentSignature> signature = ComponentSignature.of(ctx, ctx.resolver.resolve(context.callName()));
        List<CompletionItem> items = new ArrayList<>();
        for (ParamInfo param : signature.map(ComponentSignature::params).orElse(List.of())) {
            if (context.usedArguments().contains(param.name())) {
                continue;
            }
            CompletionItem item = new CompletionItem(param.name());
            item.setKind(param.slot() ? CompletionItemKind.Field : CompletionItemKind.Property);
            item.setDetail(ComponentSignature.paramText(param));
            item.setTextEdit(Either.forLeft(new TextEdit(range, param.name() + " = ")));
            item.setSortText((param.slot() ? "1" : "0") + param.name());
            items.add(item);
        }
        return items;
    }

    // ----------------------------------------------------------- auto-import

    /** Edição que acrescenta {@code import q;}: depois do último import, senão do package, senão no topo. */
    private static TextEdit importEdit(DocumentContext ctx, String qualifiedName) {
        String statement = "import " + qualifiedName + ";";
        List<ImportDecl> imports = ctx.ast.imports();
        if (!imports.isEmpty()) {
            int end = imports.get(imports.size() - 1).span().endIndex() + 1;
            return new TextEdit(rangeAt(ctx.mapper.positionOfCodePoint(end)), "\n" + statement);
        }
        if (ctx.ast.packageSpan().isPresent()) {
            int end = ctx.ast.packageSpan().get().endIndex() + 1;
            return new TextEdit(rangeAt(ctx.mapper.positionOfCodePoint(end)), "\n\n" + statement);
        }
        return new TextEdit(rangeAt(new Position(0, 0)), statement + "\n\n");
    }

    private static Range rangeAt(Position position) {
        return new Range(position, position);
    }

    // --------------------------------------------------------------- helpers

    /** O intervalo já escrito da palavra sob o cursor (ou, num import, do nome qualificado). */
    private static Range wordRange(DocumentContext ctx, int cursor, boolean qualified) {
        int start = cursor;
        while (start > 0) {
            char c = ctx.text.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_' || (qualified && c == '.')) {
                start--;
            } else {
                break;
            }
        }
        return new Range(ctx.mapper.positionOfChar(start), ctx.mapper.positionOfChar(cursor));
    }

    private static boolean sameFile(DocumentContext ctx, ProjectIndexEntry entry) {
        return entry.sourceFile().toAbsolutePath().normalize().equals(ctx.absolute());
    }
}
