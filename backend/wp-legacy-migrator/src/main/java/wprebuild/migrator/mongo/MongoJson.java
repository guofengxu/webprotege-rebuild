package wprebuild.migrator.mongo;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Shared parsing for {@code mongoexport --jsonArray} documents. */
final class MongoJson {

    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private MongoJson() {}

    static List<JsonNode> array(Path file) {
        JsonNode root = read(file);
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException(file.getFileName() + " is not a JSON array");
        }
        List<JsonNode> nodes = new ArrayList<>();
        root.forEach(nodes::add);
        return List.copyOf(nodes);
    }

    static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " is not a string");
        }
        return node.asText();
    }

    static String requiredText(JsonNode parent, String field) {
        String value = text(parent, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is blank");
        }
        return value;
    }

    static Optional<String> optionalText(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return Optional.empty();
        }
        if (!node.isTextual()) {
            throw new IllegalArgumentException(field + " is not a string");
        }
        String value = node.asText();
        if (value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    static boolean bool(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException(field + " is not a boolean");
        }
        return node.booleanValue();
    }

    static long epochMillis(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return 0L;
        }
        return readEpoch(node, field);
    }

    static List<String> strings(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException(field + " is not an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(field + " contains a non-string");
            }
            values.add(item.asText());
        }
        return List.copyOf(values);
    }

    static JsonNode emptyObject() {
        return JsonNodeFactory.instance.objectNode();
    }

    static JsonNode objectOrEmpty(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || node.isNull()) {
            return emptyObject();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException(field + " is not an object");
        }
        return node.deepCopy();
    }

    /**
     * Prefix names in stored documents end with {@code :}. The interchange map omits that colon.
     */
    static Map<String, String> prefixes(JsonNode parent) {
        JsonNode node = parent.get("prefixes");
        if (node == null || node.isNull()) {
            return Map.of();
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("prefixes is not an object");
        }
        Map<String, String> prefixes = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> putPrefix(prefixes, entry.getKey(), entry.getValue()));
        return Collections.unmodifiableMap(prefixes);
    }

    private static JsonNode read(Path file) {
        try {
            return JSON.readTree(file.toFile());
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read " + file.getFileName(), exception);
        }
    }

    private static void putPrefix(Map<String, String> prefixes, String name, JsonNode value) {
        if (!value.isTextual()) {
            throw new IllegalArgumentException("prefix value is not a string: " + name);
        }
        String stripped = name.endsWith(":") ? name.substring(0, name.length() - 1) : name;
        if (prefixes.containsKey(stripped)) {
            throw new IllegalArgumentException("duplicate prefix '" + stripped + "'");
        }
        prefixes.put(stripped, value.asText());
    }

    private static long readEpoch(JsonNode node, String field) {
        if (node.isNumber()) {
            if (!node.isIntegralNumber() || !node.canConvertToLong()) {
                throw new IllegalArgumentException(field + " is not a timestamp: " + node.asText());
            }
            return node.longValue();
        }
        if (node.isTextual()) {
            return parseTemporal(node.asText(), field);
        }
        if (node.isObject()) {
            JsonNode date = node.get("$date");
            if (date != null && !date.isNull()) {
                return readEpoch(date, field);
            }
            JsonNode numberLong = node.get("$numberLong");
            if (numberLong != null && numberLong.isTextual()) {
                return parseLong(numberLong.asText(), field);
            }
        }
        throw new IllegalArgumentException(field + " is not a timestamp");
    }

    private static long parseTemporal(String text, String field) {
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException exception) {
            return parseLong(text, field);
        }
    }

    private static long parseLong(String text, String field) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(field + " is not a timestamp: " + text, exception);
        }
    }
}
