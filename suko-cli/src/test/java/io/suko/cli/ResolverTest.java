package io.suko.cli;

import io.suko.registry.ComponentManifest;
import io.suko.registry.FileSystemRegistrySource;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistrySource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests {@link Resolver} against synthetic fixture registries under
 * {@code src/test/resources/registry-fixtures/} (Steps 1-3 of the brief),
 * plus one test against the real {@code suko-components/} registry
 * (Step 4), which exercises an actual (non-diamond) dependsOn chain.
 */
class ResolverTest {

    private static final Path FIXTURES_BASE = Path.of("src", "test", "resources", "registry-fixtures");

    private RegistrySource sourceFor(String fixtureName) {
        return new FileSystemRegistrySource(FIXTURES_BASE.resolve(fixtureName));
    }

    private RegistryIndex indexFor(RegistrySource source) {
        try {
            byte[] bytes = source.resolve("registry.json");
            return RegistryJson.readIndex(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void draggingTopPullsInTransitiveDiamondDependenciesMarkedTransitive() {
        RegistrySource source = sourceFor("diamond");
        RegistryIndex index = indexFor(source);

        ResolutionPlan plan = Resolver.resolve(index, source, List.of("top"));

        assertEquals(4, plan.components().size(), "expected leaf, mid-a, mid-b, top: " + names(plan));
        assertDirect(plan, "top", true);
        assertDirect(plan, "mid-a", false);
        assertDirect(plan, "mid-b", false);
        assertDirect(plan, "leaf", false);

        // Diamond: mid-a and mid-b both depend on leaf. The manifest for
        // leaf must appear exactly once in the plan (memoized fetch), not
        // twice.
        long leafCount = plan.components().stream().filter(r -> r.manifest().name().equals("leaf")).count();
        assertEquals(1, leafCount, "leaf's manifest should be fetched/emitted exactly once: " + names(plan));
    }

    @Test
    void requestingATransitiveDependencyExplicitlyMarksItDirect() {
        RegistrySource source = sourceFor("diamond");
        RegistryIndex index = indexFor(source);

        ResolutionPlan plan = Resolver.resolve(index, source, List.of("top", "mid-a"));

        assertDirect(plan, "top", true);
        assertDirect(plan, "mid-a", true);
        assertDirect(plan, "mid-b", false);
        assertDirect(plan, "leaf", false);
    }

    @Test
    void ordersDependenciesBeforeDependents() {
        RegistrySource source = sourceFor("diamond");
        RegistryIndex index = indexFor(source);

        ResolutionPlan plan = Resolver.resolve(index, source, List.of("top"));
        List<String> order = names(plan);

        assertTrue(order.indexOf("leaf") < order.indexOf("mid-a"), order.toString());
        assertTrue(order.indexOf("leaf") < order.indexOf("mid-b"), order.toString());
        assertTrue(order.indexOf("mid-a") < order.indexOf("top"), order.toString());
        assertTrue(order.indexOf("mid-b") < order.indexOf("top"), order.toString());
    }

    @Test
    void requestingAnUnknownComponentNamesItAndSuggestsList() {
        RegistrySource source = sourceFor("diamond");
        RegistryIndex index = indexFor(source);

        CliException e = assertThrows(CliException.class,
                () -> Resolver.resolve(index, source, List.of("does-not-exist")));

        assertTrue(e.getMessage().contains("does-not-exist"), "message: " + e.getMessage());
        assertTrue(e.getMessage().contains("suko list"), "message: " + e.getMessage());
    }

    @Test
    void cycleInARegistryProducesAReadableErrorNamingTheCycle() {
        RegistrySource source = sourceFor("cycle");
        RegistryIndex index = indexFor(source);

        CliException e = assertThrows(CliException.class,
                () -> Resolver.resolve(index, source, List.of("a")));

        assertTrue(e.getMessage().contains("a"), "message: " + e.getMessage());
        assertTrue(e.getMessage().contains("b"), "message: " + e.getMessage());
        assertTrue(e.getMessage().toLowerCase().contains("cycle") || e.getMessage().toLowerCase().contains("ciclo"),
                "message should call out a cycle: " + e.getMessage());
    }

    @Test
    void dependsOnPointingAtANameAbsentFromTheIndexIsAnExplicitError() {
        RegistrySource source = sourceFor("dangling");
        RegistryIndex index = indexFor(source);

        CliException e = assertThrows(CliException.class,
                () -> Resolver.resolve(index, source, List.of("x")));

        assertTrue(e.getMessage().contains("ghost"), "message: " + e.getMessage());
        assertTrue(e.getMessage().contains("x"), "message: " + e.getMessage());
    }

    @Test
    void resolvesFieldAgainstTheRealRegistryWithLoadedManifests() {
        Path realRegistryBase = Path.of("..", "suko-components");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                java.nio.file.Files.isRegularFile(realRegistryBase.resolve("registry.json")),
                "suko-components/registry.json not found relative to suko-cli/ — skipping real-registry test");

        RegistrySource source = new FileSystemRegistrySource(realRegistryBase);
        RegistryIndex index = indexFor(source);

        ResolutionPlan plan = Resolver.resolve(index, source, List.of("field"));

        List<String> order = names(plan);
        assertEquals(3, order.size(), "expected [input, label, field] in some valid topological order: " + order);
        assertTrue(order.contains("input"));
        assertTrue(order.contains("label"));
        assertTrue(order.contains("field"));
        assertTrue(order.indexOf("input") < order.indexOf("field"), order.toString());
        assertTrue(order.indexOf("label") < order.indexOf("field"), order.toString());

        for (ResolutionPlan.Resolved resolved : plan.components()) {
            ComponentManifest manifest = resolved.manifest();
            assertNotNull(manifest, "manifest must be loaded, not left null");
            assertEquals(resolved.manifest().name(), manifest.name());
        }
        assertDirect(plan, "field", true);
        assertDirect(plan, "input", false);
        assertDirect(plan, "label", false);
    }

    private static List<String> names(ResolutionPlan plan) {
        return plan.components().stream().map(r -> r.manifest().name()).toList();
    }

    private static void assertDirect(ResolutionPlan plan, String name, boolean expectedDirect) {
        ResolutionPlan.Resolved resolved = plan.components().stream()
                .filter(r -> r.manifest().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("\"" + name + "\" not found in plan: " + names(plan)));
        assertEquals(expectedDirect, resolved.direct(), "direct flag for \"" + name + "\": " + names(plan));
    }
}
