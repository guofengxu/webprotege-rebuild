package wprebuild.app.web.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.search.SearchEntities;
import wprebuild.app.search.SearchTypes;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Read-only MCP tool that dispatches {@link SearchEntities} on the command bus. */
public final class SearchEntitiesTool {

    static final String NAME = "search_entities";

    private static final int DEFAULT_LIMIT = 20;

    private final CommandBus bus;

    private final JsonMapper json;

    SearchEntitiesTool(CommandBus bus, JsonMapper json) {
        this.bus = Objects.requireNonNull(bus, "command bus");
        this.json = Objects.requireNonNull(json, "json mapper");
    }

    Tool definition() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", Map.of("type", "string", "description", "Project id"));
        properties.put("query", Map.of("type", "string", "description", "Search query"));
        properties.put(
                "types",
                Map.of(
                        "type",
                        "array",
                        "description",
                        "Entity types (same tokens as the REST search endpoint)",
                        "items",
                        Map.of(
                                "type",
                                "string",
                                "enum",
                                List.of(
                                        "class",
                                        "object-property",
                                        "data-property",
                                        "annotation-property",
                                        "named-individual",
                                        "datatype"))));
        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("minimum", 1);
        limit.put("maximum", SearchEntities.MAX_LIMIT);
        limit.put("default", DEFAULT_LIMIT);
        limit.put("description", "Maximum hits (1–" + SearchEntities.MAX_LIMIT + ")");
        properties.put("limit", limit);
        schema.put("properties", properties);
        schema.put("required", List.of("projectId", "query"));
        schema.put("additionalProperties", false);
        return Tool.builder(NAME, schema)
                .description("Search ontology entities by display name or local name")
                .build();
    }

    CallToolResult call(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
            SearchEntities command = parse(arguments);
            List<EntityNode> hits = bus.execute(user, command);
            String payload = json.writeValueAsString(McpToolResult.of(hits));
            return CallToolResult.builder()
                    .addTextContent(payload)
                    .isError(false)
                    .build();
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private static SearchEntities parse(Map<String, Object> arguments) {
        String projectId = requireText(arguments.get("projectId"), "projectId");
        String query = requireText(arguments.get("query"), "query");
        Set<EntityType<?>> types = parseTypes(arguments.get("types"));
        int limit = parseLimit(arguments.get("limit"));
        return new SearchEntities(ProjectId.of(projectId), query, types, limit);
    }

    private static Set<EntityType<?>> parseTypes(Object raw) {
        if (raw == null) {
            return Set.of();
        }
        if (raw instanceof String text) {
            return SearchTypes.parse(text);
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return Set.of();
            }
            Set<EntityType<?>> types = new LinkedHashSet<>();
            for (Object item : list) {
                if (item == null) {
                    throw new IllegalArgumentException("types entries must be strings");
                }
                types.addAll(SearchTypes.parse(String.valueOf(item)));
            }
            return Set.copyOf(types);
        }
        throw new IllegalArgumentException("types must be a string or an array of strings");
    }

    private static int parseLimit(Object raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            return Integer.parseInt(text.strip());
        }
        throw new IllegalArgumentException("limit must be an integer");
    }

    private static String requireText(Object raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        String text = String.valueOf(raw).strip();
        if (text.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return text;
    }
}
