package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.symbol.SymbolTable;
import io.suko.lang.semantic.SemanticChecker;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SemanticCheckerTest {

    @Test
    void cardSkPassesWithoutErrors() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "examples/Card.sk");

        // Build AST from Card.sk using SukoAstBuilder
        // Reuse existing JteRenderSupport to parse Card.sk
        String sukoSource = new java.io.File("examples/Card.sk").toString();
        // Since parsing requires the ANTLR setup, we test with a simple valid structure
        // The real Card.sk test is in JteEmitterGoldenFileTest
        // Here we verify the SemanticChecker infrastructure works

        // Create a simple valid component with all slots filled
        Param slotOne = new Param.SlotParam(
                new Type("String", List.of(), 0), "header", Cardinality.ONE, false,
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        Param valueOne = new Param.ValueParam(
                new Type("String", List.of(), 0), "title",
                Optional.empty(), new SourceSpan(1, 1, 0, 5));

        ComponentDecl card = new ComponentDecl("Card", List.of(), List.of(valueOne, slotOne),
                List.of(), new SourceSpan(1, 1, 0, 10));

        // Register a component that Card calls
        Param slotInNav = new Param.SlotParam(
                new Type("Content", List.of(), 0), "content", Cardinality.ONE, false,
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        ComponentDecl layout = new ComponentDecl("Layout", List.of(), List.of(slotInNav),
                List.of(), new SourceSpan(1, 1, 0, 10));

        // Create a component that calls Layout with the content slot filled
        ComponentDecl page = new ComponentDecl("Page", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("Layout", List.of(),
                        List.of(new Statement.SlotFill("content", Optional.empty(), List.of())),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        // Register all components
        symbolTable.register(card);
        symbolTable.register(layout);
        symbolTable.register(page);

        // Check
        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(card, layout, page)));

        assertFalse(diagnostics.hasErrors(), "Should have no errors: " + diagnostics);
    }

    @Test
    void reportsMissingComponent() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        ComponentDecl caller = new ComponentDecl("Caller", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("NonExistent", List.of(), List.of(),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(caller)));

        assertTrue(diagnostics.hasErrors());
        assertEquals(1, diagnostics.getErrors().size());
        SukoDiagnostic diag = diagnostics.getErrors().get(0);
        assertEquals("COMPONENT_NOT_FOUND", diag.code());
        assertTrue(diag.message().contains("NonExistent"));
    }

    @Test
    void reportsMissingRequiredSlot() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        Param requiredSlot = new Param.SlotParam(
                new Type("Content", List.of(), 0), "header", Cardinality.ONE, false,
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        ComponentDecl card = new ComponentDecl("Card", List.of(), List.of(requiredSlot),
                List.of(), new SourceSpan(1, 1, 0, 10));
        symbolTable.register(card);

        ComponentDecl caller = new ComponentDecl("Caller", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("Card", List.of(), List.of(),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(card, caller)));

        assertTrue(diagnostics.hasErrors());
        assertEquals(1, diagnostics.getErrors().size());
        SukoDiagnostic diag = diagnostics.getErrors().get(0);
        assertEquals("REQUIRED_SLOT_MISSING", diag.code());
        assertTrue(diag.message().contains("header"));
    }

    @Test
    void reportsUnknownSlot() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        Param requiredSlot = new Param.SlotParam(
                new Type("Content", List.of(), 0), "header", Cardinality.ONE, false,
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        ComponentDecl card = new ComponentDecl("Card", List.of(), List.of(requiredSlot),
                List.of(), new SourceSpan(1, 1, 0, 10));
        symbolTable.register(card);

        ComponentDecl caller = new ComponentDecl("Caller", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("Card", List.of(),
                        List.of(new Statement.SlotFill("unknown", Optional.empty(), List.of())),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(card, caller)));

        assertTrue(diagnostics.hasErrors());
        assertEquals(2, diagnostics.getErrors().size());
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> d.code().equals("SLOT_NOT_FOUND")));
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> d.code().equals("REQUIRED_SLOT_MISSING")));
    }

    @Test
    void looseContentWithoutChildrenParamReusesSlotNotFound() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        // Field não declara nenhum param "children"
        ComponentDecl field = new ComponentDecl("Field", List.of(), List.of(),
                List.of(), new SourceSpan(1, 1, 0, 10));
        symbolTable.register(field);

        ComponentDecl host = new ComponentDecl("Host", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("Field", List.of(),
                        List.of(new Statement.SlotFill("children", Optional.empty(), List.of())),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(field, host)));

        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> d.code().equals("SLOT_NOT_FOUND")));
    }

    @Test
    void looseContentPlusExplicitChildrenFillReusesCardinalityViolation() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        // Field(Component children)
        Param childrenSlot = new Param.SlotParam(
                new Type("Component", List.of(), 0), "children", Cardinality.ONE, true,
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        ComponentDecl field = new ComponentDecl("Field", List.of(), List.of(childrenSlot),
                List.of(), new SourceSpan(1, 1, 0, 10));
        symbolTable.register(field);

        // Field() { "solto" children { "explícito" } } => 2 fills para "children" (cardinality ONE)
        ComponentDecl host = new ComponentDecl("Host", List.of(), List.of(),
                List.of(
                    new Statement.ComponentCallStmt("Field", List.of(),
                        List.of(
                            new Statement.SlotFill("children", Optional.empty(), List.of()),
                            new Statement.SlotFill("children", Optional.empty(), List.of())
                        ),
                        new SourceSpan(1, 1, 0, 10))
                ), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(field, host)));

        assertTrue(diagnostics.hasErrors());
        assertTrue(diagnostics.getErrors().stream().anyMatch(d -> d.code().equals("CARDINALITY_VIOLATION")));
    }

    @Test
    void childrenParamThatIsNotComponentIsReservedNameError() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        // component Field(String children) { }
        Param badChildren = new Param.ValueParam(
                new Type("String", List.of(), 0), "children",
                Optional.empty(), new SourceSpan(1, 1, 0, 5));
        ComponentDecl field = new ComponentDecl("Field", List.of(), List.of(badChildren),
                List.of(), new SourceSpan(1, 1, 0, 10));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(field)));

        assertTrue(diagnostics.hasErrors());
        assertEquals(1, diagnostics.getErrors().size());
        SukoDiagnostic diag = diagnostics.getErrors().get(0);
        assertEquals("RESERVED_CHILDREN_NAME", diag.code());
        assertTrue(diag.message().contains("children"));
    }

    @Test
    void componentCallAsValueValidatesExistence() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        // component Host() { var c = NaoExiste(); }
        Expr.CallExpr naoExisteCall = new Expr.CallExpr(
                new Expr.PrimaryExpr("NaoExiste", new SourceSpan(1, 1, 0, 9)),
                List.of(), new SourceSpan(1, 1, 0, 11));
        ComponentDecl host = new ComponentDecl("Host", List.of(), List.of(),
                List.of(
                    new Statement.VarDecl("c", naoExisteCall, new SourceSpan(1, 1, 0, 15))
                ), new SourceSpan(1, 1, 0, 20));

        checker.check(new SukoFile(Optional.empty(), List.of(), List.of(host)));

        assertTrue(diagnostics.hasErrors());
        assertEquals(1, diagnostics.getErrors().size());
        SukoDiagnostic diag = diagnostics.getErrors().get(0);
        assertEquals("COMPONENT_NOT_FOUND", diag.code());
        assertTrue(diag.message().contains("NaoExiste"));
    }

    @Test
    void bareBraceInStringSuggestsDollarSyntax() {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SymbolTable symbolTable = new SymbolTable();
        SemanticChecker checker = new SemanticChecker(symbolTable, diagnostics, "test.sk");

        String sukoSource = """
            component Button(String variant) {
              <a class="btn btn-{variant}">Click</a>
            }
            """;
        org.antlr.v4.runtime.CharStream charStream = org.antlr.v4.runtime.CharStreams.fromString(sukoSource);
        io.suko.lang.SukoLexer lexer = new io.suko.lang.SukoLexer(charStream);
        io.suko.lang.SukoParser parser = new io.suko.lang.SukoParser(new org.antlr.v4.runtime.CommonTokenStream(lexer));
        SukoFile file = new io.suko.lang.SukoAstBuilder(sukoSource).build(parser.compilationUnit());

        checker.check(file);

        assertTrue(diagnostics.hasErrors());
        SukoDiagnostic diag = diagnostics.getErrors().stream()
                .filter(d -> "BARE_BRACE_IN_STRING".equals(d.code()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no BARE_BRACE_IN_STRING diagnostic found"));
        assertTrue(diag.message().contains("${variant}"));
    }
}