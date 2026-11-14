package wprebuild.app.web.mcp;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Serializes {@link McpToolResult} payloads and enforces {@code wp.mcp.max-result-bytes}. Arrays and paged
 * list fields are truncated to complete elements; other objects that exceed the limit become {@code
 * RESULT_TOO_LARGE}.
 */
public final class McpResultLimits {

    public static final String RESULT_TOO_LARGE = "RESULT_TOO_LARGE";

    public static final String HINT_HIERARCHY_ARRAY =
            "Result truncated; use get_hierarchy_children to expand one level at a time";

    public static final String HINT_PROJECT_ARRAY = "Result truncated; list fewer projects or open one by projectId";

    public static final String HINT_SEARCH_ARRAY =
            "Result truncated; reduce limit or narrow the query and types filters";

    public static final String HINT_PAGE = "Result truncated; reduce size or turn the page";

    private final JsonMapper json;

    private final int maxBytes;

    McpResultLimits(JsonMapper json, McpProperties properties) {
        this(json, Objects.requireNonNull(properties, "properties").maxResultBytes());
    }

    /** Visible for unit tests that set an explicit byte budget. */
    public McpResultLimits(JsonMapper json, int maxBytes) {
        this.json = Objects.requireNonNull(json, "json mapper");
        if (maxBytes < 1) {
            throw new IllegalArgumentException("max bytes must be at least 1");
        }
        this.maxBytes = maxBytes;
    }

    public CallToolResult encode(Object result, String arrayTruncationHint) {
        Objects.requireNonNull(arrayTruncationHint, "array truncation hint");
        McpToolResult full = McpToolResult.of(result);
        String fullPayload = write(full);
        if (utf8Length(fullPayload) <= maxBytes) {
            return ok(fullPayload);
        }
        int fullBytes = utf8Length(fullPayload);
        JsonNode tree = json.valueToTree(result);
        if (tree != null && tree.isArray()) {
            return truncateArray((ArrayNode) tree, arrayTruncationHint, fullBytes);
        }
        if (tree instanceof ObjectNode object && isPaged(object)) {
            return truncatePaged(object, fullBytes);
        }
        return tooLarge(fullBytes);
    }

    private CallToolResult truncateArray(ArrayNode elements, String hint, int fullBytes) {
        ArrayNode kept = json.createArrayNode();
        for (JsonNode element : elements) {
            kept.add(element);
            if (utf8Length(write(new McpToolResult(kept, true, hint))) > maxBytes) {
                kept.remove(kept.size() - 1);
                break;
            }
        }
        String payload = write(new McpToolResult(kept, true, hint));
        if (utf8Length(payload) > maxBytes) {
            return tooLarge(fullBytes);
        }
        return ok(payload);
    }

    private CallToolResult truncatePaged(ObjectNode page, int fullBytes) {
        String listField = listFieldName(page);
        ArrayNode items = (ArrayNode) page.get(listField);
        ObjectNode truncated = page.deepCopy();
        ArrayNode kept = json.createArrayNode();
        truncated.set(listField, kept);
        for (JsonNode element : items) {
            kept.add(element);
            if (utf8Length(write(new McpToolResult(truncated, true, HINT_PAGE))) > maxBytes) {
                kept.remove(kept.size() - 1);
                break;
            }
        }
        String payload = write(new McpToolResult(truncated, true, HINT_PAGE));
        if (utf8Length(payload) > maxBytes) {
            return tooLarge(fullBytes);
        }
        return ok(payload);
    }

    private static boolean isPaged(ObjectNode object) {
        return object.has("page") && object.has("size") && object.has("total") && listFieldNameOrNull(object) != null;
    }

    private static String listFieldName(ObjectNode object) {
        String name = listFieldNameOrNull(object);
        if (name == null) {
            throw new IllegalStateException("paged object has no list field");
        }
        return name;
    }

    private static String listFieldNameOrNull(ObjectNode object) {
        String found = null;
        for (String name : object.propertyNames()) {
            JsonNode value = object.get(name);
            if (value != null && value.isArray()) {
                if (found != null) {
                    return null;
                }
                found = name;
            }
        }
        return found;
    }

    private CallToolResult tooLarge(int actualBytes) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", RESULT_TOO_LARGE);
        body.put("message", "Result is " + actualBytes + " bytes; limit is " + maxBytes + " (wp.mcp.max-result-bytes)");
        return CallToolResult.builder()
                .addTextContent(writeRaw(body))
                .isError(true)
                .build();
    }

    private CallToolResult ok(String payload) {
        return CallToolResult.builder().addTextContent(payload).isError(false).build();
    }

    private String write(McpToolResult payload) {
        return writeRaw(payload);
    }

    private String writeRaw(Object value) {
        return json.writeValueAsString(value);
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
