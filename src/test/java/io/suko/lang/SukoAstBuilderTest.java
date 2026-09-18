package io.suko.lang;

import io.suko.lang.ast.Cardinality;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.Param;
import io.suko.lang.ast.SukoFile;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste unitário de {@link SukoAstBuilder#buildParam}, direto sobre o AST
 * (sem passar por JteEmitter/gg.jte) — cobre as quatro combinações
 * estruturais de {@code Cardinality x renderProp} introduzidas pela
 * tarefa 2 (subprojeto 6: "Component substitui slot<T>"):
 * <ul>
 *   <li>{@code Component} → SlotParam(ONE, renderProp=false)</li>
 *   <li>{@code List<Component>} → SlotParam(MANY, renderProp=false)</li>
 *   <li>{@code Function<T, Component>} → SlotParam(ONE, renderProp=true)</li>
 *   <li>{@code List<Function<T, Component>>} → SlotParam(MANY, renderProp=true)</li>
 * </ul>
 * A última combinação (MANY + renderProp) não tinha nenhuma cobertura, nem
 * a este nível nem a nível de render — achado "Important" da revisão da
 * tarefa 2, corrigido aqui.
 */
class SukoAstBuilderTest {

    private List<Param> buildParams(String componentSource) {
        SukoLexer lexer = new SukoLexer(CharStreams.fromString(componentSource));
        SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
        SukoFile file = new SukoAstBuilder(componentSource).build(parser.compilationUnit());
        ComponentDecl component = file.components().get(0);
        return component.params();
    }

    @Test
    void componentParamBecomesOneNonRenderPropSlotParam() {
        List<Param> params = buildParams("""
            component Card(Component header) {
            }
            """);

        assertEquals(1, params.size());
        Param.SlotParam slot = assertInstanceOf(Param.SlotParam.class, params.get(0));
        assertEquals("header", slot.name());
        assertEquals(Cardinality.ONE, slot.cardinality());
        assertFalse(slot.renderProp());
    }

    @Test
    void listOfComponentParamBecomesManyNonRenderPropSlotParam() {
        List<Param> params = buildParams("""
            component Toolbar(List<Component> actions) {
            }
            """);

        assertEquals(1, params.size());
        Param.SlotParam slot = assertInstanceOf(Param.SlotParam.class, params.get(0));
        assertEquals("actions", slot.name());
        assertEquals(Cardinality.MANY, slot.cardinality());
        assertFalse(slot.renderProp());
    }

    @Test
    void functionOfComponentParamBecomesOneRenderPropSlotParam() {
        List<Param> params = buildParams("""
            component Row(Function<String, Component> label) {
            }
            """);

        assertEquals(1, params.size());
        Param.SlotParam slot = assertInstanceOf(Param.SlotParam.class, params.get(0));
        assertEquals("label", slot.name());
        assertEquals(Cardinality.ONE, slot.cardinality());
        assertTrue(slot.renderProp());
        assertEquals("String", slot.elementType().name());
    }

    @Test
    void listOfFunctionOfComponentParamBecomesManyRenderPropSlotParam() {
        // Achado "Important" da revisão da tarefa 2: esta combinação
        // (MANY + renderProp) não tinha nenhum teste, apesar do código em
        // SukoAstBuilder.tryBuildSlotParam já a tratar (ramo
        // isRenderProp(inner) dentro do caso "List"). Fechado aqui.
        List<Param> params = buildParams("""
            component RowList(List<Function<String, Component>> rows) {
            }
            """);

        assertEquals(1, params.size());
        Param.SlotParam slot = assertInstanceOf(Param.SlotParam.class, params.get(0));
        assertEquals("rows", slot.name());
        assertEquals(Cardinality.MANY, slot.cardinality());
        assertTrue(slot.renderProp());
        assertEquals("String", slot.elementType().name());
    }
}
