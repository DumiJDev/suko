package io.suko.cli;

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
 * class: guessing it from folder structure would fail silently and only
 * surface much later, from the compiler, as {@code PACKAGE_DIRECTORY_MISMATCH}
 * — a diagnostic that does not point back at the real cause. It is always
 * either explicitly configured (file or flag) or the caller is told to run
 * {@code suko init} / pass {@code --base-package}.
 * </p>
 */
public record ProjectConfig(int schemaVersion, String sourceRoot, String basePackage, Registry registry) {

    public static final int SCHEMA_VERSION = 1;
    public static final String DEFAULT_SOURCE_ROOT = "src/main/suko";
    public static final String FILE_NAME = "suko.json";

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
        Object parsed;
        try {
            parsed = SimpleJson.parse(json);
        } catch (SimpleJson.JsonSyntaxException e) {
            throw new CliException("Malformed JSON in " + sourceDescription + ": " + e.getMessage());
        }
        if (!(parsed instanceof java.util.Map<?, ?> root)) {
            throw new CliException("Expected a JSON object at the top level of " + sourceDescription);
        }

        Object schemaVersionValue = requireField(root, "schemaVersion", sourceDescription);
        if (!(schemaVersionValue instanceof Number)) {
            throw new CliException("Field \"schemaVersion\" in " + sourceDescription + " must be an integer, found: " + schemaVersionValue);
        }
        int foundSchemaVersion = ((Number) schemaVersionValue).intValue();
        if (foundSchemaVersion > SCHEMA_VERSION) {
            throw new CliException(
                    "Unsupported schemaVersion " + foundSchemaVersion + " in " + sourceDescription
                            + "; this version of the suko CLI only supports schemaVersion " + SCHEMA_VERSION
                            + ". Upgrade the suko CLI, or edit suko.json by hand.");
        }

        String sourceRoot = requireStringField(root, "sourceRoot", sourceDescription);
        String basePackage = requireStringField(root, "basePackage", sourceDescription);

        Object registryValue = requireField(root, "registry", sourceDescription);
        if (!(registryValue instanceof java.util.Map<?, ?> registryMap)) {
            throw new CliException("Field \"registry\" in " + sourceDescription + " must be an object with \"base\" and \"ref\".");
        }
        String base = requireStringField(registryMap, "base", sourceDescription + " (registry)");
        String ref = requireStringField(registryMap, "ref", sourceDescription + " (registry)");

        return new ProjectConfig(foundSchemaVersion, sourceRoot, basePackage, new Registry(base, ref));
    }

    private static Object requireField(java.util.Map<?, ?> map, String field, String sourceDescription) {
        Object value = map.get(field);
        if (value == null) {
            throw new CliException("Missing required field \"" + field + "\" in " + sourceDescription);
        }
        return value;
    }

    private static String requireStringField(java.util.Map<?, ?> map, String field, String sourceDescription) {
        Object value = requireField(map, field, sourceDescription);
        if (!(value instanceof String)) {
            throw new CliException("Field \"" + field + "\" in " + sourceDescription + " must be a string, found: " + value);
        }
        return (String) value;
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
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"schemaVersion\": ").append(schemaVersion).append(",\n");
        sb.append("  \"sourceRoot\": ").append(SimpleJson.quote(sourceRoot)).append(",\n");
        sb.append("  \"basePackage\": ").append(SimpleJson.quote(basePackage)).append(",\n");
        sb.append("  \"registry\": {\n");
        sb.append("    \"base\": ").append(SimpleJson.quote(registry.base())).append(",\n");
        sb.append("    \"ref\": ").append(SimpleJson.quote(registry.ref())).append("\n");
        sb.append("  }\n");
        sb.append("}\n");
        return sb.toString();
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
