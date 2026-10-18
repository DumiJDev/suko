package io.suko.jte.client;

import io.suko.lang.ast.Type;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static io.suko.jte.client.ClientType.Scalar.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientTypesTest {

    static final JavaTypes TODO = name -> switch (name) {
        case "Todo" -> Optional.of(List.of(new JavaTypes.Field("title", ClientTypes.parse("String")),
            new JavaTypes.Field("done", ClientTypes.parse("boolean"))));
        case "Node" -> Optional.of(List.of(new JavaTypes.Field("next", ClientTypes.parse("Node"))));
        default -> Optional.empty();
    };

    static ClientType of(String text) {
        return ClientTypes.of(ClientTypes.parse(text), TODO);
    }

    @Test
    void scalarsAndWrappers() {
        assertEquals(INT, of("int"));
        assertEquals(INT, of("short"));
        assertEquals(INT, of("byte"));
        assertEquals(DOUBLE, of("double"));
        assertEquals(BOOLEAN, of("boolean"));
        assertEquals(STRING, of("String"));
        assertEquals(new ClientType.Boxed(INT), of("Integer"));
        assertEquals(new ClientType.Boxed(DOUBLE), of("Double"));
        assertEquals(new ClientType.Boxed(BOOLEAN), of("Boolean"));
    }

    @Test
    void collectionsCallbacksAndRecords() {
        assertEquals(new ClientType.ListOf(new ClientType.Boxed(INT)), of("List<Integer>"));
        assertEquals(new ClientType.ListOf(STRING), of("List<String>"));
        assertEquals(new ClientType.MapOf(new ClientType.ListOf(STRING)), of("Map<String, List<String>>"));
        assertEquals(new ClientType.Callback(List.of(new ClientType.Boxed(INT))), of("Consumer<Integer>"));
        assertEquals(new ClientType.Callback(List.of()), of("Runnable"));
        assertEquals(new ClientType.RecordOf("Todo", List.of(new ClientType.RecordOf.Field("title", STRING),
            new ClientType.RecordOf.Field("done", BOOLEAN))), of("Todo"));
        assertEquals(new ClientType.Named("Product"), of("Product"));
    }

    @Test
    void outsideTheSubset() {
        for (String t : new String[] {"long", "Long", "char", "Character", "float", "Float", "int[]", "Object",
                                      "Map<Integer, String>", "Optional<String>", "Node", "Component"}) {
            assertInstanceOf(ClientType.Unsupported.class, of(t), t);
        }
        assertTrue(((ClientType.Unsupported) of("long")).reason().contains("2^53"));
        assertTrue(((ClientType.Unsupported) of("float")).reason().contains("double"));
        assertTrue(((ClientType.Unsupported) of("Node")).reason().contains("recursivo"));
    }

    @Test
    void parseIsTheInverseOfParamInfoText() {
        Type t = ClientTypes.parse("Map<String, List<Integer>>");
        assertEquals("Map", t.name());
        assertEquals("List", t.typeArguments().get(1).name());
        assertEquals("Integer", t.typeArguments().get(1).typeArguments().get(0).name());
        assertEquals(2, ClientTypes.parse("String[][]").arrayDimensions());
    }

    @Test
    void assignabilityFollowsJava() {
        assertTrue(ClientTypes.assignable(INT, DOUBLE));
        assertFalse(ClientTypes.assignable(DOUBLE, INT));
        assertTrue(ClientTypes.assignable(ClientType.Special.NULL, STRING));
        assertFalse(ClientTypes.assignable(ClientType.Special.NULL, INT));
        assertTrue(ClientTypes.assignable(INT, new ClientType.Boxed(INT)));
        assertTrue(ClientTypes.assignable(new ClientType.Boxed(INT), INT));
        assertFalse(ClientTypes.assignable(INT, new ClientType.Boxed(DOUBLE)));
        assertTrue(ClientTypes.assignable(new ClientType.Boxed(INT), DOUBLE));
        assertFalse(ClientTypes.assignable(new ClientType.ListOf(STRING), new ClientType.ListOf(new ClientType.Boxed(INT))));
    }

    @Test
    void ternaryTypes() {
        assertEquals(DOUBLE, ClientTypes.unify(INT, DOUBLE));
        assertEquals(new ClientType.Boxed(INT), ClientTypes.unify(ClientType.Special.NULL, INT));
        assertEquals(STRING, ClientTypes.unify(STRING, ClientType.Special.NULL));
        assertEquals(INT, ClientTypes.unify(new ClientType.Boxed(INT), INT));
        assertNull(ClientTypes.unify(STRING, INT));
    }
}
