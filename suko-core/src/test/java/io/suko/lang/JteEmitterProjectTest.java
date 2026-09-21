package io.suko.lang;

import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectIndexEntry;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class JteEmitterProjectTest {

    private static SukoFile parse(String source) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        return new SukoAstBuilder(source).build(parser.compilationUnit());
    }

    @Test
    void resolvesImportedShortNameToQualifiedTemplateCall() {
        SukoFile file = parse("""
            import ui.NavLink;

            component Home() {
              NavLink(href="/")
            }
            """);
        ComponentDecl home = file.components().get(0);

        ProjectIndexEntry navLink = new ProjectIndexEntry("ui.NavLink", "NavLink", Path.of("ui/NavLink.sk"), true, 1);
        JteEmitter emitter = new JteEmitter(List.of(home), Map.of("NavLink", navLink));

        String jte = emitter.emit(home);
        assertTrue(jte.contains("@template.ui.NavLink("), jte);
    }

    @Test
    void leavesFullyQualifiedCallUntouched() {
        SukoFile file = parse("""
            component Home() {
              ui.NavLink(href="/")
            }
            """);
        ComponentDecl home = file.components().get(0);

        JteEmitter emitter = new JteEmitter(List.of(home), Map.of());
        String jte = emitter.emit(home);
        assertTrue(jte.contains("@template.ui.NavLink("), jte);
    }

    @Test
    void resolvesImportedShortNameUsedAsExpressionValue() {
        SukoFile file = parse("""
            import ui.CardA;

            component Home() {
              var c = CardA();
              ${c}
            }
            """);
        ComponentDecl home = file.components().get(0);

        ProjectIndexEntry cardA = new ProjectIndexEntry("ui.CardA", "CardA", Path.of("ui/CardA.sk"), true, 0);
        JteEmitter emitter = new JteEmitter(List.of(home), Map.of("CardA", cardA));

        String jte = emitter.emit(home);
        assertTrue(jte.contains("@`@template.ui.CardA("), jte);
    }
}
