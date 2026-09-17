package io.suko.lang.semantic;

import io.suko.lang.ast.*;
import io.suko.lang.diagnostic.DiagnosticCollector;
import io.suko.lang.diagnostic.SukoDiagnostic;
import io.suko.lang.symbol.SymbolTable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Verificador semântico para o Subprojeto 2 — Verificador Suko.
 * Percorre o AST e verifica:
 * - Componentes chamados existem
 * - Slot fills correspondem a slots declarados
 * - Cardinalidade de slots é respeitada
 * - Slots obrigatórios são preenchidos
 */
public class SemanticChecker {

    private final SymbolTable symbolTable;
    private final DiagnosticCollector diagnostics;
    private final String sourceFile;

    public SemanticChecker(SymbolTable symbolTable, DiagnosticCollector diagnostics, String sourceFile) {
        this.symbolTable = symbolTable;
        this.diagnostics = diagnostics;
        this.sourceFile = sourceFile;
    }

    /** Executa a verificação semântica completa em um SukoFile. */
    public void check(SukoFile sukoFile) {
        registerComponents(sukoFile);
        for (ComponentDecl component : sukoFile.components()) {
            checkComponent(component);
        }
    }

    private void registerComponents(SukoFile sukoFile) {
        for (ComponentDecl component : sukoFile.components()) {
            symbolTable.register(component);
        }
    }

    private void checkComponent(ComponentDecl component) {
        Map<String, Param.SlotParam> declaredSlots = new HashMap<>();
        for (Param param : component.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                declaredSlots.put(slotParam.name(), slotParam);
            }
        }
        for (Statement statement : component.body()) {
            checkStatement(statement, declaredSlots);
        }
    }

    private void checkStatement(Statement statement, Map<String, Param.SlotParam> currentScopeSlots) {
        switch (statement) {
            case Statement.ComponentCallStmt call -> checkComponentCall(call, currentScopeSlots);
            case Statement.IfStmt ifStmt -> {
                checkStatementList(ifStmt.thenBranch(), currentScopeSlots);
                checkStatementList(ifStmt.elseBranch(), currentScopeSlots);
            }
            case Statement.ForStmt forStmt -> checkStatementList(forStmt.body(), currentScopeSlots);
            case Statement.SwitchStmt switchStmt -> {
                for (Statement.SwitchCase switchCase : switchStmt.cases()) {
                    checkStatementList(switchCase.body(), currentScopeSlots);
                }
                checkStatementList(switchStmt.defaultCase(), currentScopeSlots);
            }
            default -> {}
        }
    }

    private void checkStatementList(List<Statement> statements, Map<String, Param.SlotParam> currentScopeSlots) {
        for (Statement statement : statements) {
            checkStatement(statement, currentScopeSlots);
        }
    }

    private void checkComponentCall(Statement.ComponentCallStmt call, Map<String, Param.SlotParam> currentScopeSlots) {
        ComponentDecl calledComponent = symbolTable.lookup(call.componentName());
        if (calledComponent == null) {
            diagnostics.add(new SukoDiagnostic(
                    SukoDiagnostic.Severity.ERROR,
                    "Componente '" + call.componentName() + "' não encontrado",
                    "COMPONENT_NOT_FOUND",
                    sourceFile,
                    call.span()
            ));
            return;
        }

        Map<String, Param.SlotParam> calledSlots = new HashMap<>();
        for (Param param : calledComponent.params()) {
            if (param instanceof Param.SlotParam slotParam) {
                calledSlots.put(slotParam.name(), slotParam);
            }
        }

        Map<String, List<Statement.SlotFill>> fillsBySlot = new HashMap<>();
        for (Statement.SlotFill fill : call.slotFills()) {
            fillsBySlot.computeIfAbsent(fill.paramName(), k -> new ArrayList<>()).add(fill);
        }

        for (Map.Entry<String, List<Statement.SlotFill>> entry : fillsBySlot.entrySet()) {
            String slotName = entry.getKey();
            List<Statement.SlotFill> fills = entry.getValue();

            Param.SlotParam declaredSlot = calledSlots.get(slotName);
            if (declaredSlot == null) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' não encontrado no componente '" + call.componentName() + "'",
                        "SLOT_NOT_FOUND",
                        sourceFile,
                        call.span()
                ));
                continue;
            }

            if (declaredSlot.cardinality() == Cardinality.ONE && fills.size() > 1) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot '" + slotName + "' é obrigatório (cardinality ONE) mas recebeu " + fills.size() + " fills",
                        "CARDINALITY_VIOLATION",
                        sourceFile,
                        call.span()
                ));
            }
        }

        for (Map.Entry<String, Param.SlotParam> entry : calledSlots.entrySet()) {
            String slotName = entry.getKey();
            Param.SlotParam slotParam = entry.getValue();

            boolean hasFills = fillsBySlot.containsKey(slotName);
            int fillCount = hasFills ? fillsBySlot.get(slotName).size() : 0;

            boolean isRequired = !slotParam.defaultValue().isPresent() && slotParam.cardinality() == Cardinality.ONE;

            if (isRequired && fillCount == 0) {
                diagnostics.add(new SukoDiagnostic(
                        SukoDiagnostic.Severity.ERROR,
                        "Slot obrigatório '" + slotName + "' não foi preenchido no componente '" + call.componentName() + "'",
                        "REQUIRED_SLOT_MISSING",
                        sourceFile,
                        call.span()
                ));
            }
        }
    }
}