package io.suko.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The consumer-side project configuration, read from and written to
 * {@code suko.json} (spec D6). Created by {@code suko init}; every other
 * command reads it and lets a command-line flag override any individual
 * field (precedence: flag &gt; file &gt; default).
 * <p>
 * {@code basePackage} deliberately has no built-in default anywhere in this
 * class: guessing it from folder structure would fail silently, and the
 * wrong guess would only surface much later as a confusing
 * package/folder-mismatch error from the compiler — one that does not
 * point back at the real cause. It is always either explicitly configured
 * (file or flag) or the caller is told to run {@code suko init} / pass
 * {@code --base-package}.
 * </p>
 */
public record ProjectConfig(int schemaVersion, String sourceRoot, String basePackage, Registry registry) {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_SOURCE_ROOT = "src/main/suko";
    public static final String FILE_NAME = "suko.json";

    // Gson, not a hand-rolled parser: suko-registry already brings Gson
    // 2.11.0 into the resolved dependency graph (declared there as
    // `implementation`, so only on suko-cli's runtime classpath by
    // default); this module declares the same pinned version explicitly
    // (see build.gradle.kts) so it is also visible at compile time here.
    // Task 10 of the subprojeto 8 plan explicitly requires a single JSON
    // library in the fat jar for the lockfile (`suko.lock.json`) — using
    // Gson for `suko.json` too, instead of a second hand-rolled parser, is
    // what keeps that true from the start rather than requiring a later
    // migration.
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public record Registry(String base, String ref) {
    }

    // Java reserved words (keywords, contextual reserved literals, and the
    // reserved-for-future-use "_" identifier) — none of these is a legal
    // identifier for a package segment, per the JLS.
    private static final Set<String> JAVA_RESERVED_WORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            "true", "false", "null", "var", "yield", "record", "sealed", "permits", "_");

    private static final Pattern JAVA_IDENTIFIER_START = Pattern.compile("[A-Za-z_$]");

    /**
     * Validates {@code basePackage} as a dot-separated sequence of Java
     * identifiers, none of which may be a reserved word.
     *
     * @throws CliException naming the exact offending segment (or the fact
     *                       that the whole package is empty), never a
     *                       generic "invalid package" message
     */
    public static void validateBasePackage(String basePackage) {
        if (basePackage == null || basePackage.isBlank()) {
            throw new CliException("basePackage cannot be empty.");
        }
        String[] segments = basePackage.split("\\.", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                throw new CliException(
                        "Invalid basePackage \"" + basePackage + "\": segment " + (i + 1)
                                + " is empty (check for a leading, trailing, or doubled \".\").");
            }
            char first = segment.charAt(0);
            if (!JAVA_IDENTIFIER_START.matcher(String.valueOf(first)).matches()) {
                throw new CliException(
                        "Invalid basePackage \"" + basePackage + "\": segment \"" + segment
                                + "\" cannot start with \"" + first + "\" (a package segment must start with a letter, \"_\" or \"$\").");
            }
            for (int c = 1; c < segment.length(); c++) {
                char ch = segment.charAt(c);
                if (!Character.isJavaIdentifierPart(ch)) {
                    throw new CliException(
                            "Invalid basePackage \"" + basePackage + "\": segment \"" + segment
                                    + "\" contains an invalid character \"" + ch + "\".");
                }
            }
            if (JAVA_RESERVED_WORDS.contains(segment)) {
                throw new CliException(
                        "Invalid basePackage \"" + basePackage + "\": segment \"" + segment
                                + "\" is a Java reserved word and cannot be used as a package segment.");
            }
        }
    }

    /**
     * Reads {@code suko.json} from {@code projectDir}, if it exists.
     *
     * @throws CliException if the file exists but cannot be read or parsed
     */
    public static Optional<ProjectConfig> load(Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        String json;
        try {
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("Could not read " + file + ": " + e.getMessage());
        }
        return Optional.of(parse(json, file.toString()));
    }

    /** Parses a {@code suko.json} document already read into memory. */
    public static ProjectConfig parse(String json, String sourceDescription) {
        JsonObject root = parseObject(json, sourceDescription);

        JsonElement schemaVersionElement = requireField(root, "schemaVersion", sourceDescription);
        if (!schemaVersionElement.isJsonPrimitive() || !schemaVersionElement.getAsJsonPrimitive().isNumber()) {
            throw new CliException(
                    "Field \"schemaVersion\" in " + sourceDescription + " must be an integer, found: " + schemaVersionElement);
        }
        int foundSchemaVersion = schemaVersionElement.getAsInt();
        if (foundSchemaVersion > SCHEMA_VERSION) {
            throw new CliException(
                    "Unsupported schemaVersion " + foundSchemaVersion + " in " + sourceDescription
                            + "; this version of the suko CLI only supports schemaVersion " + SCHEMA_VERSION
                            + ". Upgrade the suko CLI, or edit suko.json by hand.");
        }

        String sourceRoot = requireStringField(root, "sourceRoot", sourceDescription);
        String basePackage = requireStringField(root, "basePackage", sourceDescription);

        JsonElement registryElement = requireField(root, "registry", sourceDescription);
        if (!registryElement.isJsonObject()) {
            throw new CliException(
                    "Field \"registry\" in " + sourceDescription + " must be an object with \"base\" and \"ref\".");
        }
        JsonObject registryObject = registryElement.getAsJsonObject();
        String base = requireStringField(registryObject, "base", sourceDescription + " (registry)");
        String ref = requireStringField(registryObject, "ref", sourceDescription + " (registry)");

        return new ProjectConfig(foundSchemaVersion, sourceRoot, basePackage, new Registry(base, ref));
    }

    private static JsonObject parseObject(String json, String sourceDescription) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new CliException("Malformed JSON in " + sourceDescription + ": " + e.getMessage());
        }
        if (!element.isJsonObject()) {
            throw new CliException("Expected a JSON object at the top level of " + sourceDescription);
        }
        return element.getAsJsonObject();
    }

    private static JsonElement requireField(JsonObject object, String field, String sourceDescription) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            throw new CliException("Missing required field \"" + field + "\" in " + sourceDescription);
        }
        return object.get(field);
    }

    private static String requireStringField(JsonObject object, String field, String sourceDescription) {
        JsonElement value = requireField(object, field, sourceDescription);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new CliException("Field \"" + field + "\" in " + sourceDescription + " must be a string, found: " + value);
        }
        return value.getAsString();
    }

    /** Writes this configuration to {@code suko.json} in {@code projectDir}, in LF, UTF-8. */
    public void write(Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        String json = toJson();
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("Could not write " + file + ": " + e.getMessage());
        }
    }

    /** Renders this configuration as pretty-printed JSON, field order matching D6's example. */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);
        root.addProperty("sourceRoot", sourceRoot);
        root.addProperty("basePackage", basePackage);

        JsonObject registryObject = new JsonObject();
        registryObject.addProperty("base", registry.base());
        registryObject.addProperty("ref", registry.ref());
        root.add("registry", registryObject);

        // GSON's pretty printer emits "\n" line breaks (not
        // System.lineSeparator()), so this is already LF-only (D8); the
        // trailing newline below just gives the file a POSIX-style final
        // newline, matching every other generated JSON document in this
        // repo (see RegistryJson's golden files).
        return GSON.toJson(root) + "\n";
    }

    /**
     * Resolves the effective configuration for a command, applying
     * precedence flag &gt; file &gt; default (spec D6).
     *
     * @throws CliException if {@code basePackage} cannot be resolved from
     *                       either source, is invalid, or if {@code
     *                       suko.json} itself is malformed
     */
    public static ProjectConfig resolve(Args args, Path projectDir) {
        Optional<ProjectConfig> fileConfig = load(projectDir);

        String sourceRoot = firstNonNull(args.sourceRoot(),
                fileConfig.map(ProjectConfig::sourceRoot).orElse(null), DEFAULT_SOURCE_ROOT);

        String basePackage = firstNonNull(args.basePackage(),
                fileConfig.map(ProjectConfig::basePackage).orElse(null), null);
        if (basePackage == null) {
            throw new CliException(missingBasePackageMessage(fileConfig.isPresent(), projectDir));
        }
        validateBasePackage(basePackage);

        String registryBase = firstNonNull(args.registryBase(),
                fileConfig.map(c -> c.registry().base()).orElse(null), null);
        String registryRef = firstNonNull(args.registryRef(),
                fileConfig.map(c -> c.registry().ref()).orElse(null), null);

        return new ProjectConfig(SCHEMA_VERSION, sourceRoot, basePackage, new Registry(registryBase, registryRef));
    }

    private static String missingBasePackageMessage(boolean fileExists, Path projectDir) {
        if (fileExists) {
            return "suko.json exists at " + projectDir.resolve(FILE_NAME)
                    + " but has no usable basePackage, and --base-package was not given either.\n"
                    + "Run `suko init` again, or pass --base-package <pkg> explicitly.";
        }
        return "No " + FILE_NAME + " found in " + projectDir
                + ", and --base-package was not given either.\n"
                + "Run `suko init` to create one, or pass --base-package <pkg> explicitly.";
    }

    private static String firstNonNull(String flagValue, String fileValue, String defaultValue) {
        if (flagValue != null) {
            return flagValue;
        }
        if (fileValue != null) {
            return fileValue;
        }
        return defaultValue;
    }
}
