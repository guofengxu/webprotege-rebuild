package wprebuild.app.web.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.semanticweb.owlapi.model.IRI;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.GetRevisionDiff;
import wprebuild.app.ontology.IndividualPage;
import wprebuild.app.ontology.ListEntityRevisions;
import wprebuild.app.ontology.ListIndividualsByType;
import wprebuild.app.ontology.ListRevisions;
import wprebuild.app.ontology.RevisionDiff;
import wprebuild.app.revision.RevisionHistoryQueries;
import wprebuild.app.revision.RevisionPage;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/** Read-only MCP tools for individuals and revision history (P6-16). */
public final class HistoryTools {

    static final String LIST_INDIVIDUALS = "list_individuals";

    static final String LIST_REVISIONS = "list_revisions";

    static final String GET_REVISION_DIFF = "get_revision_diff";

    static final String LIST_ENTITY_REVISIONS = "list_entity_revisions";

    private static final int DEFAULT_INDIVIDUAL_PAGE = 0;

    private static final int DEFAULT_INDIVIDUAL_SIZE = 50;

    private static final int DEFAULT_REVISION_PAGE = 0;

    private static final int DEFAULT_REVISION_SIZE = 20;

    private static final int DEFAULT_ENTITY_REVISION_PAGE = 0;

    private static final int DEFAULT_ENTITY_REVISION_SIZE = 50;

    private static final int DEFAULT_DIFF_LIMIT = 200;

    private final CommandBus bus;

    private final JsonMapper json;

    private final McpResultLimits limits;

    HistoryTools(CommandBus bus, JsonMapper json, McpResultLimits limits) {
        this.bus = Objects.requireNonNull(bus, "command bus");
        this.json = Objects.requireNonNull(json, "json mapper");
        this.limits = Objects.requireNonNull(limits, "result limits");
    }

    List<ToolBinding> bindings() {
        return List.of(
                new ToolBinding(listIndividualsDefinition(), this::listIndividuals),
                new ToolBinding(listRevisionsDefinition(), this::listRevisions),
                new ToolBinding(revisionDiffDefinition(), this::revisionDiff),
                new ToolBinding(entityRevisionsDefinition(), this::entityRevisions));
    }

    private Tool listIndividualsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("cls", stringProp("Absolute IRI of the class whose direct individuals to list"));
        properties.put("page", intProp("Zero-based page", DEFAULT_INDIVIDUAL_PAGE, 0, null));
        properties.put(
                "size",
                intProp(
                        "Page size (1–" + ListIndividualsByType.MAX_SIZE + ")",
                        DEFAULT_INDIVIDUAL_SIZE,
                        1,
                        ListIndividualsByType.MAX_SIZE));
        return Tool.builder(LIST_INDIVIDUALS, objectSchema(properties, List.of("projectId", "cls")))
                .description(
                        "List direct named individuals of one class (same payload as GET /api/projects/{id}/individuals). Does not include individuals of subclasses.")
                .build();
    }

    private Tool listRevisionsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("page", intProp("Zero-based page", DEFAULT_REVISION_PAGE, 0, null));
        properties.put(
                "size",
                intProp(
                        "Page size (1–" + RevisionHistoryQueries.MAX_SIZE + ")",
                        DEFAULT_REVISION_SIZE,
                        1,
                        RevisionHistoryQueries.MAX_SIZE));
        return Tool.builder(LIST_REVISIONS, objectSchema(properties, List.of("projectId")))
                .description(
                        "List project revisions newest first (same payload as GET /api/projects/{id}/revisions). Requires VIEW_CHANGES.")
                .build();
    }

    private Tool revisionDiffDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("revision", intProp("Revision number (> 0)", null, 1, null));
        properties.put(
                "limit",
                intProp(
                        "Maximum changes returned (1–" + GetRevisionDiff.MAX_LIMIT + ")",
                        DEFAULT_DIFF_LIMIT,
                        1,
                        GetRevisionDiff.MAX_LIMIT));
        return Tool.builder(GET_REVISION_DIFF, objectSchema(properties, List.of("projectId", "revision")))
                .description(
                        "Show axiom changes in one revision (same payload as GET /api/projects/{id}/revisions/{rev}). Requires VIEW_CHANGES.")
                .build();
    }

    private Tool entityRevisionsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("iri", stringProp("Absolute IRI of the entity"));
        properties.put("page", intProp("Zero-based page", DEFAULT_ENTITY_REVISION_PAGE, 0, null));
        properties.put(
                "size",
                intProp(
                        "Page size (1–" + RevisionHistoryQueries.MAX_SIZE + ")",
                        DEFAULT_ENTITY_REVISION_SIZE,
                        1,
                        RevisionHistoryQueries.MAX_SIZE));
        return Tool.builder(LIST_ENTITY_REVISIONS, objectSchema(properties, List.of("projectId", "iri")))
                .description(
                        "List revisions that mention one entity (same payload as GET /api/projects/{id}/entities/history). Requires VIEW_CHANGES.")
                .build();
    }

    private CallToolResult listIndividuals(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            IRI cls = entityIri(requireText(arguments.get("cls"), "cls"));
            int page = optionalInt(arguments.get("page"), DEFAULT_INDIVIDUAL_PAGE, "page");
            int size = optionalInt(arguments.get("size"), DEFAULT_INDIVIDUAL_SIZE, "size");
            IndividualPage body = bus.execute(user, new ListIndividualsByType(projectId, cls, page, size));
            return limits.encode(body, McpResultLimits.HINT_PAGE);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult listRevisions(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            int page = optionalInt(arguments.get("page"), DEFAULT_REVISION_PAGE, "page");
            int size = optionalInt(arguments.get("size"), DEFAULT_REVISION_SIZE, "size");
            RevisionPage body = bus.execute(user, new ListRevisions(projectId, page, size));
            return limits.encode(body, McpResultLimits.HINT_PAGE);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult revisionDiff(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            long revision = requireLong(arguments.get("revision"), "revision");
            int limit = optionalInt(arguments.get("limit"), DEFAULT_DIFF_LIMIT, "limit");
            RevisionDiff body = bus.execute(user, new GetRevisionDiff(projectId, RevisionNumber.of(revision), limit));
            return limits.encode(body, McpResultLimits.HINT_PAGE);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult entityRevisions(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            IRI iri = entityIri(requireText(arguments.get("iri"), "iri"));
            int page = optionalInt(arguments.get("page"), DEFAULT_ENTITY_REVISION_PAGE, "page");
            int size = optionalInt(arguments.get("size"), DEFAULT_ENTITY_REVISION_SIZE, "size");
            RevisionPage body = bus.execute(user, new ListEntityRevisions(projectId, iri, page, size));
            return limits.encode(body, McpResultLimits.HINT_PAGE);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private static Map<String, Object> arguments(CallToolRequest request) {
        return request.arguments() == null ? Map.of() : request.arguments();
    }

    private static ProjectId projectId(Map<String, Object> arguments) {
        return ProjectId.of(requireText(arguments.get("projectId"), "projectId"));
    }

    private static IRI entityIri(String raw) {
        final IRI iri;
        try {
            iri = IRI.create(raw);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("IRI is invalid");
        }
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        return iri;
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

    private static int optionalInt(Object raw, int defaultValue, String field) {
        if (raw == null) {
            return defaultValue;
        }
        return parseInt(raw, field);
    }

    private static long requireLong(Object raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return parseLong(raw, field);
    }

    private static int parseInt(Object raw, String field) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text.strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static long parseLong(Object raw, String field) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(field + " must be an integer");
            }
        }
        throw new IllegalArgumentException(field + " must be an integer");
    }

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> intProp(
            String description, Integer defaultValue, Integer minimum, Integer maximum) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("description", description);
        if (defaultValue != null) {
            property.put("default", defaultValue);
        }
        if (minimum != null) {
            property.put("minimum", minimum);
        }
        if (maximum != null) {
            property.put("maximum", maximum);
        }
        return property;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    record ToolBinding(Tool definition, BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {}
}
