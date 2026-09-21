package io.suko.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The consumer-side lockfile, read from and written to
 * {@code suko.lock.json} (spec D7). Kept in a file separate from
 * {@code suko.json}: one is meant to be hand-edited, the other is
 * generated and reconciled by the CLI, and the two disciplines don't mix.
 * <p>
 * Deliberately has <strong>no timestamp or other machine-derived field</strong>
 * (spec D7): this file is meant to be committed and reviewed, and a
 * timestamp would produce a spurious diff on every single install/update,
 * turning the lockfile into a permanent source of merge conflicts.
 * {@code LockfileTest} asserts this directly against the generated text,
 * so that field cannot be reintroduced "for convenience" without that test
 * turning red first.
 * </p>
 * <p>
 * {@link #components()} is always kept sorted alphabetically by name —
 * enforced in the canonical constructor, not just at write time, so it
 * holds regardless of the order callers hand in.
 * </p>
 */
public record Lockfile(int schemaVersion, Registry registry, String basePackage, String sourceRoot,
                        List<LockEntry> components) {

    public static final int SCHEMA_VERSION = 1;
    public static final String FILE_NAME = "suko.lock.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Lockfile {
        components = components.stream()
                .sorted(Comparator.comparing(LockEntry::name))
                .toList();
    }

    /** The registry this lockfile's components were installed from. */
    public record Registry(String base, String ref, String registryVersion) {
    }

    /** Reads {@code suko.lock.json} from {@code projectDir}, if it exists. */
    public static Optional<Lockfile> load(Path projectDir) {
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

    /** Parses a {@code suko.lock.json} document already read into memory. */
    public static Lockfile parse(String json, String sourceDescription) {
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
                            + ". Upgrade the suko CLI, or edit suko.lock.json by hand.");
        }

        JsonElement registryElement = requireField(root, "registry", sourceDescription);
        if (!registryElement.isJsonObject()) {
            throw new CliException(
                    "Field \"registry\" in " + sourceDescription
                            + " must be an object with \"base\", \"ref\" and \"registryVersion\".");
        }
        JsonObject registryObject = registryElement.getAsJsonObject();
        String base = requireStringField(registryObject, "base", sourceDescription + " (registry)");
        String ref = requireStringField(registryObject, "ref", sourceDescription + " (registry)");
        String registryVersion = requireStringField(registryObject, "registryVersion", sourceDescription + " (registry)");

        String basePackage = requireStringField(root, "basePackage", sourceDescription);
        String sourceRoot = requireStringField(root, "sourceRoot", sourceDescription);

        JsonElement componentsElement = requireField(root, "components", sourceDescription);
        if (!componentsElement.isJsonArray()) {
            throw new CliException("Field \"components\" in " + sourceDescription + " must be an array.");
        }
        List<LockEntry> components = new ArrayList<>();
        for (JsonElement componentElement : componentsElement.getAsJsonArray()) {
            components.add(parseComponent(componentElement, sourceDescription));
        }

        return new Lockfile(foundSchemaVersion, new Registry(base, ref, registryVersion), basePackage, sourceRoot, components);
    }

    private static LockEntry parseComponent(JsonElement element, String sourceDescription) {
        if (!element.isJsonObject()) {
            throw new CliException("Each entry of \"components\" in " + sourceDescription + " must be an object.");
        }
        JsonObject object = element.getAsJsonObject();
        String name = requireStringField(object, "name", sourceDescription + " (components)");
        String version = requireStringField(object, "version", sourceDescription + " (components)");
        String reason = requireStringField(object, "reason", sourceDescription + " (components)");

        JsonElement filesElement = requireField(object, "files", sourceDescription + " (components)");
        if (!filesElement.isJsonArray()) {
            throw new CliException(
                    "Field \"files\" of component \"" + name + "\" in " + sourceDescription + " must be an array.");
        }
        List<LockEntry.FileEntry> files = new ArrayList<>();
        for (JsonElement fileElement : filesElement.getAsJsonArray()) {
            files.add(parseFile(fileElement, name, sourceDescription));
        }
        return new LockEntry(name, version, reason, files);
    }

    private static LockEntry.FileEntry parseFile(JsonElement element, String componentName, String sourceDescription) {
        if (!element.isJsonObject()) {
            throw new CliException(
                    "Each file of component \"" + componentName + "\" in " + sourceDescription + " must be an object.");
        }
        JsonObject object = element.getAsJsonObject();
        String context = sourceDescription + " (component \"" + componentName + "\")";
        String target = requireStringField(object, "target", context);
        String upstreamSha256 = requireStringField(object, "upstreamSha256", context);
        String localSha256 = requireStringField(object, "localSha256", context);
        return new LockEntry.FileEntry(target, upstreamSha256, localSha256);
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

    /** Writes this lockfile to {@code suko.lock.json} in {@code projectDir}, in LF, UTF-8. */
    public void write(Path projectDir) {
        Path file = projectDir.resolve(FILE_NAME);
        String json = toJson();
        try {
            Files.writeString(file, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CliException("Could not write " + file + ": " + e.getMessage());
        }
    }

    /**
     * Renders this lockfile as pretty-printed JSON, field order matching
     * D7's example, {@link #components()} already alphabetically sorted by
     * the canonical constructor, and — deliberately — no timestamp field
     * anywhere in the output.
     */
    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", schemaVersion);

        JsonObject registryObject = new JsonObject();
        registryObject.addProperty("base", registry.base());
        registryObject.addProperty("ref", registry.ref());
        registryObject.addProperty("registryVersion", registry.registryVersion());
        root.add("registry", registryObject);

        root.addProperty("basePackage", basePackage);
        root.addProperty("sourceRoot", sourceRoot);

        JsonArray componentsArray = new JsonArray();
        for (LockEntry component : components) {
            JsonObject componentObject = new JsonObject();
            componentObject.addProperty("name", component.name());
            componentObject.addProperty("version", component.version());
            componentObject.addProperty("reason", component.reason());

            JsonArray filesArray = new JsonArray();
            for (LockEntry.FileEntry file : component.files()) {
                JsonObject fileObject = new JsonObject();
                fileObject.addProperty("target", file.target());
                fileObject.addProperty("upstreamSha256", file.upstreamSha256());
                fileObject.addProperty("localSha256", file.localSha256());
                filesArray.add(fileObject);
            }
            componentObject.add("files", filesArray);

            componentsArray.add(componentObject);
        }
        root.add("components", componentsArray);

        // GSON's pretty printer emits "\n" line breaks (not
        // System.lineSeparator()), so this is already LF-only (D8); the
        // trailing newline below just gives the file a POSIX-style final
        // newline, matching every other generated JSON document in this
        // repo.
        return GSON.toJson(root) + "\n";
    }
}
