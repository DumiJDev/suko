package io.suko.lang.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeRunnerTest {

    @Test
    void runsAModule() throws Exception {
        assertEquals("ok 3", NodeRunner.run("console.log('ok', 1 + 2);").trim());
    }

    @Test
    void aMissingNodeFailsInsteadOfSkipping() {
        AssertionError e = assertThrows(AssertionError.class,
            () -> NodeRunner.run("/nonexistent/suko-node", "console.log(1);"));
        assertTrue(e.getMessage().contains("Node 18+"), e.getMessage());
    }
}
