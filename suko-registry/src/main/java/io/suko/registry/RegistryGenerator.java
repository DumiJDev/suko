package io.suko.registry;

import io.suko.lang.SukoAstBuilder;
import io.suko.lang.SukoLexer;
import io.suko.lang.SukoParser;
import io.suko.lang.ast.ComponentDecl;
import io.suko.lang.ast.ImportDecl;
import io.suko.lang.ast.SukoFile;
import io.suko.lang.project.ProjectIndex;
import io.suko.lang.project.ProjectIndexEntry;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Generates the registry manifest (index + per-component manifests) from the
 * {@code .sk} sources under a source root.
 * <p>
 * Reuses {@link ProjectIndex#build(Path)} and {@link SukoAstBuilder} — this
 * class never re-implements Suko parsing. {@code ProjectIndex} only exposes
 * the shallow signature it indexes (name, package, visibility, param count),
 * not the full {@link ComponentDecl}/{@link ImportDecl} trees this generator
 * needs (to enumerate a file's components for the "one per file" convention,
 * and to read its imports for {@code dependsOn}); this class therefore also
 * parses each file directly through {@link SukoAstBuilder}, exactly the way
 * {@code ProjectIndex} itself does internally — not a parallel parsing path.
 * </p>
 * <p>
 * <strong>Decided by the user (2026-09-20):</strong> component descriptions
 * come only from {@link GeneratorConfig#descriptionsFile()} (a
 * {@code descriptions.properties} file next to the sources), never from
 * {@code //} comments in the {@code .sk} source. See {@link GeneratorConfig}
 * for the full rationale.
 * </p>
 */
public final class RegistryGenerator {

    private RegistryGenerator() {
    }

    public static GeneratedRegistry generate(Path sourceRoot, GeneratorConfig config) {
        ProjectIndex index = ProjectIndex.build(sourceRoot);
        if (!index.duplicates().isEmpty()) {
            ProjectIndex.DuplicateComponent duplicate = index.duplicates().get(0);
            throw new RegistryGeneratorException(
                    "Duplicate component '" + duplicate.qualifiedName() + "' declared in both '"
                            + duplicate.firstFile() + "' and '" + duplicate.secondFile() + "'");
        }

        List<Path> skFiles;
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            skFiles = walk.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".sk"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        // Pass 1: parse every file, enforce "one public component per file",
        // and collect enough per-component data (source bytes, package,
        // imports) to resolve dependsOn in pass 2 — the dependsOn graph
        // needs every component's registry name known before it can resolve
        // any single component's imports against it.
        record ParsedComponent(
                ComponentDecl decl,
                Path sourceFile,
                byte[] sourceBytes,
                List<ImportDecl> imports
        ) {
        }

        Map<String, ParsedComponent> byRegistryName = new LinkedHashMap<>();

        for (Path skFile : skFiles) {
            String relative = sourceRoot.relativize(skFile).toString().replace('\\', '/');
            byte[] sourceBytes;
            try {
                sourceBytes = Files.readAllBytes(skFile);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            String source = new String(sourceBytes, StandardCharsets.UTF_8);

            SukoFile file = parse(source, relative);

            if (file.components().size() != 1) {
                throw new RegistryGeneratorException(
                        "'" + relative + "' declares " + file.components().size()
                                + " component(s); the registry requires exactly one component per file");
            }

            ComponentDecl decl = file.components().get(0);
            if (!decl.isPublic()) {
                throw new RegistryGeneratorException(
                        "Component '" + decl.name() + "' in '" + relative
                                + "' is not declared 'public'; every registry component must be public");
            }

            String registryName = decl.name().toLowerCase(Locale.ROOT);
            // index.duplicates() (checked above) only catches an identical
            // qualifiedName (same package AND name). Two components with the
            // same name in DIFFERENT packages (e.g. io.suko.ui.Field and
            // io.suko.other.Field) have distinct qualifiedNames but collide
            // here, because the registry name is only the lowercase simple
            // name — without this check the second one silently overwrites
            // the first in byRegistryName and the generation "succeeds" with
            // one of the two components discarded, no error at all.
            ParsedComponent previous = byRegistryName.get(registryName);
            if (previous != null) {
                String previousRelative = sourceRoot.relativize(previous.sourceFile()).toString().replace('\\', '/');
                throw new RegistryGeneratorException(
                        "Duplicate registry name '" + registryName + "' (component '" + decl.name()
                                + "'): declared in both '" + previousRelative + "' and '" + relative
                                + "'; registry names are case-insensitive and package-independent, so two"
                                + " components in different packages cannot share the same name");
            }
            byRegistryName.put(registryName, new ParsedComponent(decl, skFile, sourceBytes, file.imports()));
        }

        // Pass 2: resolve dependsOn for every component, then validate the
        // resulting graph is acyclic before touching description/version
        // metadata (a cyclic-dependency fixture need not supply either).
        Map<String, List<String>> dependsOnByRegistryName = new LinkedHashMap<>();
        for (var entry : byRegistryName.entrySet()) {
            ParsedComponent parsed = entry.getValue();
            Map<String, ProjectIndexEntry> resolvedImports = index.resolveImports(parsed.imports());
            Set<String> dependsOn = new LinkedHashSet<>();
            for (ProjectIndexEntry imported : resolvedImports.values()) {
                String importedRegistryName = imported.simpleName().toLowerCase(Locale.ROOT);
                if (byRegistryName.containsKey(importedRegistryName)) {
                    dependsOn.add(importedRegistryName);
                }
                // An import that does not resolve to a component of this
                // same registry (e.g. a component outside sourceRoot, or
                // one that failed to resolve at all) is deliberately
                // ignored here, not an error — see spec D2/Task 4 brief.
            }
            dependsOnByRegistryName.put(entry.getKey(), List.copyOf(dependsOn));
        }

        validateAcyclic(dependsOnByRegistryName);

        // Loaded only now, after every structural validation (one component
        // per file, public, acyclic dependsOn) has passed — a fixture
        // exercising one of those failures need not also supply a
        // descriptions file.
        Properties descriptions = loadDescriptions(config.descriptionsFile());

        // Pass 3: build the manifests, now that structure is validated.
        Map<String, ComponentManifest> manifestsByName = new LinkedHashMap<>();
        for (var entry : byRegistryName.entrySet()) {
            String registryName = entry.getKey();
            ParsedComponent parsed = entry.getValue();
            ComponentDecl decl = parsed.decl();

            String packageSuffix = packageSuffixOf(sourceRoot, parsed.sourceFile(), config.basePackage());

            String description = descriptions.getProperty(decl.name());
            if (description == null) {
                throw new RegistryGeneratorException(
                        "No description for component '" + decl.name() + "' in '"
                                + config.descriptionsFile() + "'; add a '" + decl.name() + "=...' entry");
            }

            GeneratorConfig.ComponentConfig componentConfig = config.componentConfigs().get(decl.name());
            if (componentConfig == null) {
                throw new RegistryGeneratorException(
                        "No GeneratorConfig.ComponentConfig entry for component '" + decl.name() + "'");
            }

            String fileName = parsed.sourceFile().getFileName().toString();
            String relativePath = sourceRoot.relativize(parsed.sourceFile()).toString().replace('\\', '/');
            String path = config.sourceRootPrefix().isEmpty()
                    ? relativePath
                    : config.sourceRootPrefix() + "/" + relativePath;
            String targetDir = packageSuffix.isEmpty() ? "" : packageSuffix.replace('.', '/') + "/";
            String target = targetDir + fileName;
            ComponentFile componentFile = new ComponentFile(path, target, sha256Of(parsed.sourceBytes()));

            ComponentManifest manifest = new ComponentManifest(
                    RegistryIndex.SCHEMA_VERSION,
                    registryName,
                    componentConfig.version(),
                    description,
                    componentConfig.category(),
                    config.basePackage(),
                    packageSuffix,
                    decl.name(),
                    List.of(componentFile),
                    dependsOnByRegistryName.get(registryName),
                    componentConfig.externalRequirements());

            manifestsByName.put(registryName, manifest);
        }

        List<RegistryIndex.Entry> entries = new ArrayList<>();
        for (String registryName : new TreeMap<>(manifestsByName).keySet()) {
            ComponentManifest manifest = manifestsByName.get(registryName);
            entries.add(new RegistryIndex.Entry(
                    manifest.name(), manifest.version(), manifest.description(),
                    manifest.category(), "components/" + registryName + ".json"));
        }

        RegistryIndex registryIndex = new RegistryIndex(
                RegistryIndex.SCHEMA_VERSION, config.registryVersion(), config.basePackage(), entries);

        return new GeneratedRegistry(registryIndex, manifestsByName);
    }

    /** Parse via {@link SukoAstBuilder} only — no {@code SukoErrorListener}
     * here (mirrors {@code ProjectIndex.parseQuietly}'s lexer/parser setup),
     * but unlike that method a malformed registry file is a hard failure:
     * there is no "phase 2" downstream to report it instead. */
    private static SukoFile parse(String source, String relativePathForMessage) {
        try {
            SukoLexer lexer = new SukoLexer(CharStreams.fromString(source));
            lexer.removeErrorListeners();
            SukoParser parser = new SukoParser(new CommonTokenStream(lexer));
            parser.removeErrorListeners();
            return new SukoAstBuilder(source).build(parser.compilationUnit());
        } catch (RuntimeException e) {
            throw new RegistryGeneratorException("Could not parse '" + relativePathForMessage + "': " + e.getMessage(), e);
        }
    }

    /**
     * The component's full package, minus {@code basePackage}. Derived from
     * the file's folder relative to the source root — the same authoritative
     * source {@link ProjectIndex} itself uses (see its "achado B" comment),
     * not the declared {@code package} line, so this agrees with whatever
     * qualifiedName {@code ProjectIndex}/{@code SemanticChecker} computed for
     * this same file.
     */
    private static String packageSuffixOf(Path sourceRoot, Path sourceFile, String basePackage) {
        Path relativeDir = ProjectIndex.relativeDirOf(sourceRoot, sourceFile);
        String prefix = ProjectIndex.relativeDirToPackagePrefix(relativeDir); // "" or "io.suko.ui."
        String fullPackage = prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1);

        if (fullPackage.equals(basePackage)) {
            return "";
        }
        if (fullPackage.startsWith(basePackage + ".")) {
            return fullPackage.substring(basePackage.length() + 1);
        }
        throw new RegistryGeneratorException(
                "Component at '" + sourceRoot.relativize(sourceFile) + "' has package '" + fullPackage
                        + "', which is not under the configured basePackage '" + basePackage + "'");
    }

    private static void validateAcyclic(Map<String, List<String>> dependsOnByRegistryName) {
        Set<String> visited = new LinkedHashSet<>();
        Set<String> inStack = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();

        for (String start : dependsOnByRegistryName.keySet()) {
            if (visited.contains(start)) {
                continue;
            }
            detectCycle(start, dependsOnByRegistryName, visited, inStack, stack);
        }
    }

    private static void detectCycle(
            String node,
            Map<String, List<String>> graph,
            Set<String> visited,
            Set<String> inStack,
            Deque<String> stack) {
        visited.add(node);
        inStack.add(node);
        stack.push(node);

        for (String next : graph.getOrDefault(node, List.of())) {
            if (inStack.contains(next)) {
                List<String> cyclePath = new ArrayList<>(stack.reversed());
                cyclePath.add(next);
                throw new RegistryGeneratorException(
                        "Cyclic dependsOn: " + String.join(" -> ", cyclePath));
            }
            if (!visited.contains(next)) {
                detectCycle(next, graph, visited, inStack, stack);
            }
        }

        stack.pop();
        inStack.remove(node);
    }

    private static Properties loadDescriptions(Path descriptionsFile) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(descriptionsFile)) {
            properties.load(in);
        } catch (IOException e) {
            throw new RegistryGeneratorException(
                    "Could not read descriptions file '" + descriptionsFile + "': " + e.getMessage(), e);
        }
        return properties;
    }

    private static String sha256Of(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }
}
