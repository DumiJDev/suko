package io.suko.cli;

import io.suko.registry.ComponentManifest;
import io.suko.registry.RegistryIndex;
import io.suko.registry.RegistryJson;
import io.suko.registry.RegistryJsonException;
import io.suko.registry.RegistrySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves the {@code dependsOn} graph of a set of requested component
 * names into a {@link ResolutionPlan}: every transitively required
 * component's manifest, in topological order (a dependency always appears
 * before whatever depends on it), tagged {@code direct}/{@code transitive}.
 * <p>
 * <strong>What {@code dependsOn} does NOT guarantee:</strong> the field is
 * derived, at registry-generation time (subprojeto 7), from the imports of
 * a component's {@code .sk} source that happen to resolve to another
 * component <em>inside the same registry</em>; imports that don't resolve
 * that way are silently dropped from {@code dependsOn} by that generator
 * (spec C6). This resolver therefore only ever sees — and can only ever
 * pull in — dependencies the generator was able to see. It must not be
 * read as "everything this component's source needs to compile"; a
 * consumer that also needs, say, an external library referenced by
 * {@code externalRequirements} still has to handle that separately.
 * </p>
 * <p>
 * Each manifest is fetched and parsed at most once per {@link #resolve}
 * call (memoized by component name): the graph can contain diamonds
 * (two components that both depend on a third), and re-reading a shared
 * dependency's manifest once per path to it would be wasted I/O at best
 * and a source of inconsistent instances at worst.
 * </p>
 */
public final class Resolver {

    private Resolver() {
    }

    public static ResolutionPlan resolve(RegistryIndex index, RegistrySource source, List<String> requested) {
        Map<String, RegistryIndex.Entry> entriesByName = new LinkedHashMap<>();
        for (RegistryIndex.Entry entry : index.components()) {
            entriesByName.put(entry.name(), entry);
        }

        for (String name : requested) {
            if (!entriesByName.containsKey(name)) {
                throw new CliException("Component \"" + name
                        + "\" not found in this registry. Run `suko list` to see available components.");
            }
        }

        // Fixed regardless of DFS traversal order: a component requested
        // both directly and as someone else's transitive dependency is
        // still "direct".
        Set<String> directlyRequested = new LinkedHashSet<>(requested);

        Map<String, ComponentManifest> manifestCache = new HashMap<>();
        Set<String> visited = new HashSet<>();
        List<String> visitingStack = new ArrayList<>();
        List<String> order = new ArrayList<>();

        for (String name : requested) {
            visit(name, entriesByName, source, manifestCache, visitingStack, visited, order);
        }

        List<ResolutionPlan.Resolved> resolved = new ArrayList<>(order.size());
        for (String name : order) {
            resolved.add(new ResolutionPlan.Resolved(manifestCache.get(name), directlyRequested.contains(name)));
        }
        return new ResolutionPlan(resolved);
    }

    private static void visit(String name, Map<String, RegistryIndex.Entry> entriesByName, RegistrySource source,
            Map<String, ComponentManifest> manifestCache, List<String> visitingStack, Set<String> visited,
            List<String> order) {
        if (visitingStack.contains(name)) {
            List<String> cycle = new ArrayList<>(visitingStack.subList(visitingStack.indexOf(name), visitingStack.size()));
            cycle.add(name);
            throw new CliException("Dependency cycle detected in this registry: " + String.join(" -> ", cycle));
        }
        if (visited.contains(name)) {
            return;
        }

        visitingStack.add(name);
        ComponentManifest manifest = loadManifest(name, entriesByName.get(name), source, manifestCache);
        for (String dependencyName : manifest.dependsOn()) {
            if (!entriesByName.containsKey(dependencyName)) {
                // The generator that produces dependsOn (subprojeto 7)
                // only ever names components it saw resolve inside the
                // same registry, so this should not happen with the
                // official registry — but --registry accepts any registry,
                // hand-edited or otherwise inconsistent ones included, so
                // this must be an explicit error, never a silent null.
                throw new CliException("Component \"" + name + "\" declares dependsOn \"" + dependencyName
                        + "\", but \"" + dependencyName + "\" is not present in this registry's index (registry.json). "
                        + "The registry is inconsistent.");
            }
            visit(dependencyName, entriesByName, source, manifestCache, visitingStack, visited, order);
        }
        visitingStack.remove(visitingStack.size() - 1);

        visited.add(name);
        order.add(name);
    }

    private static ComponentManifest loadManifest(String name, RegistryIndex.Entry entry, RegistrySource source,
            Map<String, ComponentManifest> manifestCache) {
        ComponentManifest cached = manifestCache.get(name);
        if (cached != null) {
            return cached;
        }
        byte[] bytes;
        try {
            bytes = source.resolve(entry.manifest());
        } catch (IOException e) {
            throw new CliException(
                    "Could not read manifest for \"" + name + "\" (" + entry.manifest() + "): " + e.getMessage());
        }
        ComponentManifest manifest;
        try {
            manifest = RegistryJson.readManifest(new String(bytes, StandardCharsets.UTF_8));
        } catch (RegistryJsonException e) {
            throw new CliException(
                    "Could not parse manifest for \"" + name + "\" (" + entry.manifest() + "): " + e.getMessage());
        }
        manifestCache.put(name, manifest);
        return manifest;
    }
}
