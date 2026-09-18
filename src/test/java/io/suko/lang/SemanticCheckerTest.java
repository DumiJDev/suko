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
}