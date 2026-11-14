package wprebuild.app.web.mcp;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import org.semanticweb.owlapi.model.IRI;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.ontology.GetAnnotationPropertyFrame;
import wprebuild.app.ontology.GetClassFrame;
import wprebuild.app.ontology.GetDataPropertyFrame;
import wprebuild.app.ontology.GetHierarchyChildren;
import wprebuild.app.ontology.GetHierarchyPaths;
import wprebuild.app.ontology.GetHierarchyRoots;
import wprebuild.app.ontology.GetNamedIndividualFrame;
import wprebuild.app.ontology.GetObjectPropertyFrame;
import wprebuild.app.ontology.HierarchyKind;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.project.ListMyProjects;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.web.ProjectResponse;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Read-only MCP browse tools: projects, hierarchies, and entity frames (P6-15). */
public final class BrowseTools {

    static final String LIST_PROJECTS = "list_projects";

    static final String GET_HIERARCHY_ROOTS = "get_hierarchy_roots";

    static final String GET_HIERARCHY_CHILDREN = "get_hierarchy_children";

    static final String GET_HIERARCHY_PATHS = "get_hierarchy_paths";

    static final String GET_ENTITY_FRAME = "get_entity_frame";

    private static final List<String> HIERARCHY_KINDS =
            List.of("class", "object-property", "data-property", "annotation-property");

    private static final List<String> ENTITY_TYPES =
            List.of("class", "object-property", "data-property", "annotation-property", "named-individual");

    private final CommandBus bus;

    private final JsonMapper json;

    private final McpResultLimits limits;

    BrowseTools(CommandBus bus, JsonMapper json, McpResultLimits limits) {
        this.bus = Objects.requireNonNull(bus, "command bus");
        this.json = Objects.requireNonNull(json, "json mapper");
        this.limits = Objects.requireNonNull(limits, "result limits");
    }

    List<ToolBinding> bindings() {
        return List.of(
                new ToolBinding(listProjectsDefinition(), this::listProjects),
                new ToolBinding(hierarchyRootsDefinition(), this::hierarchyRoots),
                new ToolBinding(hierarchyChildrenDefinition(), this::hierarchyChildren),
                new ToolBinding(hierarchyPathsDefinition(), this::hierarchyPaths),
                new ToolBinding(entityFrameDefinition(), this::entityFrame));
    }

    private Tool listProjectsDefinition() {
        return Tool.builder(LIST_PROJECTS, objectSchema(Map.of(), List.of()))
                .description("List projects the caller can access (same payload as GET /api/projects)")
                .build();
    }

    private Tool hierarchyRootsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("kind", enumProp("Hierarchy kind", HIERARCHY_KINDS));
        return Tool.builder(GET_HIERARCHY_ROOTS, objectSchema(properties, List.of("projectId", "kind")))
                .description(
                        "List hierarchy roots for a project. kind selects class, object-property, data-property, or annotation-property.")
                .build();
    }

    private Tool hierarchyChildrenDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("kind", enumProp("Hierarchy kind", HIERARCHY_KINDS));
        properties.put("parent", stringProp("Absolute IRI of the parent node"));
        return Tool.builder(GET_HIERARCHY_CHILDREN, objectSchema(properties, List.of("projectId", "kind", "parent")))
                .description(
                        "List direct children of a hierarchy node. parent must be an absolute IRI from a previous roots/children call.")
                .build();
    }

    private Tool hierarchyPathsDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("kind", enumProp("Hierarchy kind", HIERARCHY_KINDS));
        properties.put("entity", stringProp("Absolute IRI of the entity"));
        return Tool.builder(GET_HIERARCHY_PATHS, objectSchema(properties, List.of("projectId", "kind", "entity")))
                .description("Return paths from hierarchy roots down to one entity. entity must be an absolute IRI.")
                .build();
    }

    private Tool entityFrameDefinition() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("projectId", stringProp("Project id"));
        properties.put("entityType", enumProp("OWL entity type", ENTITY_TYPES));
        properties.put("iri", stringProp("Absolute IRI of the entity"));
        return Tool.builder(GET_ENTITY_FRAME, objectSchema(properties, List.of("projectId", "entityType", "iri")))
                .description(
                        "Read one entity frame. entityType must match the entity (class, object-property, data-property, annotation-property, or named-individual); a mismatch yields NOT_FOUND.")
                .build();
    }

    private CallToolResult listProjects(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            List<ProjectDetails> projects = bus.execute(user, new ListMyProjects());
            List<ProjectResponse> body =
                    projects.stream().map(ProjectResponse::from).toList();
            return limits.encode(body, McpResultLimits.HINT_PROJECT_ARRAY);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult hierarchyRoots(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            HierarchyKind kind = HierarchyKind.parse(requireText(arguments.get("kind"), "kind"));
            List<EntityNode> roots = bus.execute(user, new GetHierarchyRoots(projectId, kind));
            return limits.encode(roots, McpResultLimits.HINT_HIERARCHY_ARRAY);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult hierarchyChildren(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            HierarchyKind kind = HierarchyKind.parse(requireText(arguments.get("kind"), "kind"));
            IRI parent = entityIri(requireText(arguments.get("parent"), "parent"));
            List<EntityNode> children = bus.execute(user, new GetHierarchyChildren(projectId, kind, parent));
            return limits.encode(children, McpResultLimits.HINT_HIERARCHY_ARRAY);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult hierarchyPaths(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            HierarchyKind kind = HierarchyKind.parse(requireText(arguments.get("kind"), "kind"));
            IRI entity = entityIri(requireText(arguments.get("entity"), "entity"));
            List<List<EntityNode>> paths = bus.execute(user, new GetHierarchyPaths(projectId, kind, entity));
            return limits.encode(paths, McpResultLimits.HINT_HIERARCHY_ARRAY);
        } catch (Exception failure) {
            return McpToolErrors.from(failure, json);
        }
    }

    private CallToolResult entityFrame(McpTransportContext context, CallToolRequest request) {
        try {
            UserId user = McpUserContext.requireUser(context);
            Map<String, Object> arguments = arguments(request);
            ProjectId projectId = projectId(arguments);
            String entityType =
                    requireText(arguments.get("entityType"), "entityType").toLowerCase(Locale.ROOT);
            IRI iri = entityIri(requireText(arguments.get("iri"), "iri"));
            Object frame = switch (entityType) {
                case "class" -> bus.execute(user, new GetClassFrame(projectId, iri));
                case "object-property" -> bus.execute(user, new GetObjectPropertyFrame(projectId, iri));
                case "data-property" -> bus.execute(user, new GetDataPropertyFrame(projectId, iri));
                case "annotation-property" -> bus.execute(user, new GetAnnotationPropertyFrame(projectId, iri));
                case "named-individual" -> bus.execute(user, new GetNamedIndividualFrame(projectId, iri));
                default ->
                    throw new IllegalArgumentException(
                            "entityType must be class, object-property, data-property, annotation-property, or named-individual");
            };
            return limits.encode(frame, McpResultLimits.HINT_HIERARCHY_ARRAY);
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

    private static Map<String, Object> stringProp(String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> enumProp(String description, List<String> values) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "string");
        property.put("description", description);
        property.put("enum", values);
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
