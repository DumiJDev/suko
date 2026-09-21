package io.suko.cli;

import io.suko.registry.ComponentManifest;

import java.util.List;

/**
 * The result of {@link Resolver#resolve}: every component needed to satisfy
 * a set of requested components, in a topological order (dependencies
 * before the components that depend on them), each tagged with whether it
 * was requested directly or pulled in transitively via {@code dependsOn}.
 */
public record ResolutionPlan(List<Resolved> components) {

    /**
     * @param manifest the fully loaded manifest (never {@code null})
     * @param direct   {@code true} if the caller asked for this component by
     *                 name; {@code false} if it was only pulled in because
     *                 some other requested component's {@code dependsOn}
     *                 named it. A component reachable both ways (e.g.
     *                 requested explicitly and also depended on by another
     *                 requested component) is {@code direct}.
     */
    public record Resolved(ComponentManifest manifest, boolean direct) { }
}
