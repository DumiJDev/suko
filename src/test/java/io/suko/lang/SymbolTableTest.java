package io.suko.lang;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.symbol.SymbolTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class SymbolTableTest {

    @Test
    void registersAndLooksUpComponent() {
        SymbolTable table = new SymbolTable();
        ComponentDecl decl = new ComponentDecl("Card", List.of(), List.of(), List.of(),
                new SourceSpan(1, 1, 0, 10));
        table.register(decl);
        assertEquals(decl, table.lookup("Card"));
    }

    @Test
    void lookupReturnsNullForUnknownComponent() {
        SymbolTable table = new SymbolTable();
        assertNull(table.lookup("Unknown"));
    }

    @Test
    void getAllComponentsReturnsRegisteredComponents() {
        SymbolTable table = new SymbolTable();
        table.register(new ComponentDecl("A", List.of(), List.of(), List.of(),
                new SourceSpan(1, 1, 0, 5)));
        table.register(new ComponentDecl("B", List.of(), List.of(), List.of(),
                new SourceSpan(1, 1, 0, 5)));
        assertEquals(2, table.getAllComponents().size());
        assertTrue(table.getAllComponents().stream().anyMatch(c -> c.name().equals("A")));
        assertTrue(table.getAllComponents().stream().anyMatch(c -> c.name().equals("B")));
    }
}