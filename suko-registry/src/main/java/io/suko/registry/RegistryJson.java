package io.suko.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.lang.reflect.RecordComponent;

/**
 * Reads and writes {@link ComponentManifest} and {@link RegistryIndex}
 * documents as JSON.
 * <p>
 * The {@code schemaVersion} field is validated <strong>before</strong> the
 * rest of the document is deserialized, so an unknown/future schema version
 * produces a readable message naming the found and supported versions
 * instead of surfacing whatever Gson happens to do with an unrecognized
 * shape. Missing required fields are likewise reported by name, never as a
 * raw {@code NullPointerException} downstream.
 * </p>
 * <p>
 * Schema version 1 documents are read by this version of Suko. Earlier
 * versions (e.g. 0) are also accepted for backward compatibility. Later
 * versions are rejected with a clear error message.
 * </p>
 * <p>
 * <strong>Maintenance note:</strong> When creating a future schema version 2,
 * any <em>new required field</em> added to the record definition will
 * immediately break the ability to read schema version 1 documents, since
 * {@link #requireFields(JsonObject, Class, String)} uses reflection to require
 * all {@code RecordComponent} fields. To preserve backward compatibility,
 * all new fields in schema 2 records must be optional in the JSON (with
 * default values or nullable semantics), even if the corresponding record
 * field is not optional. Alternatively, handle schema version 1 documents with
 * a separate deserialization path.
 * </p>
 * <p>
 * Output is pretty-printed with a key order that matches the declaration
 * order of the corresponding record's components (Gson's reflective field
 * order for a {@code record} compiled by {@code javac} follows declaration
 * order), so generated documents (e.g. the golden files of Task 9) do not
 * oscillate between runs.
 * </p>
 */
public final class RegistryJson {

    /**
     * The single schema-version number shared by both the registry index and
     * every component manifest document. Both formats version together; see
     * {@link RegistryIndex#SCHEMA_VERSION}.
     */
    static final int SUPPORTED_SCHEMA_VERSION = RegistryIndex.SCHEMA_VERSION;

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private RegistryJson() {
    }

    public static String writeManifest(ComponentManifest manifest) {
        return GSON.toJson(manifest);
    }

    public static ComponentManifest readManifest(String json) {
        JsonObject object = parseObject(json, "component manifest");
        validateSchemaVersion(object, "component manifest");
        requireFields(object, ComponentManifest.class, "component manifest");
        return convert(object, ComponentManifest.class, "component manifest");
    }

    public static String writeIndex(RegistryIndex index) {
        return GSON.toJson(index);
    }

    public static RegistryIndex readIndex(String json) {
        JsonObject object = parseObject(json, "registry index");
        validateSchemaVersion(object, "registry index");
        requireFields(object, RegistryIndex.class, "registry index");
        return convert(object, RegistryIndex.class, "registry index");
    }

    private static <T> T convert(JsonObject object, Class<T> type, String documentKind) {
        try {
            return GSON.fromJson(object, type);
        } catch (RuntimeException e) {
            throw new RegistryJsonException(
                    "Could not read " + documentKind + " document: " + e.getMessage(), e);
        }
    }

    private static JsonObject parseObject(String json, String documentKind) {
        JsonElement element;
        try {
            element = JsonParser.parseString(json);
        } catch (JsonSyntaxException e) {
            throw new RegistryJsonException(
                    "Malformed JSON in " + documentKind + " document: " + e.getMessage(), e);
        }
        if (!element.isJsonObject()) {
            throw new RegistryJsonException(
                    "Expected a JSON object at the top level of the " + documentKind
                            + " document, found: " + element);
        }
        return element.getAsJsonObject();
    }

    private static void validateSchemaVersion(JsonObject object, String documentKind) {
        if (!object.has("schemaVersion") || object.get("schemaVersion").isJsonNull()) {
            throw new RegistryJsonException(
                    "Missing required field \"schemaVersion\" in " + documentKind + " document");
        }
        JsonElement versionElement = object.get("schemaVersion");
        if (!versionElement.isJsonPrimitive() || !versionElement.getAsJsonPrimitive().isNumber()) {
            throw new RegistryJsonException(
                    "Field \"schemaVersion\" in " + documentKind
                            + " document must be an integer, found: " + versionElement);
        }
        int found = versionElement.getAsInt();
        if (found > SUPPORTED_SCHEMA_VERSION) {
            throw new RegistryJsonException(
                    "Unsupported schemaVersion " + found + " in " + documentKind
                            + " document; this version of Suko only supports schemaVersion "
                            + SUPPORTED_SCHEMA_VERSION);
        }
    }

    private static void requireFields(JsonObject object, Class<?> recordType, String documentKind) {
        // IMPORTANT: This method uses reflection to check all RecordComponent fields.
        // If a new required field is added to a record in a future schema version,
        // it will cause all documents of the previous schema version to be rejected.
        // See the class javadoc for guidance on maintaining backward compatibility.
        for (RecordComponent component : recordType.getRecordComponents()) {
            String field = component.getName();
            if (!object.has(field) || object.get(field).isJsonNull()) {
                throw new RegistryJsonException(
                        "Missing required field \"" + field + "\" in " + documentKind + " document");
            }
        }
    }
}
