package wprebuild.app.web.openapi;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Discriminator;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Fills response schemas that controller annotations cannot express, and closes enums the domain
 * records intentionally leave as plain strings.
 *
 * <p>The web module must not depend on {@code ontology.dto} or {@code ontology.frame}. Frame and
 * {@code EntityNode} responses stay {@code $ref}s. Nested arrays and the five frame-update results
 * are assembled here.
 */
public final class ResponseSchemasCustomizer implements OpenApiCustomizer {

    private static final String JSON = "application/json";

    private static final String EVENT_STREAM = "text/event-stream";

    private static final List<EventType> EVENTS = List.of(
            new EventType("ProjectChanged", "ProjectChanged"),
            new EventType("EntityHierarchyChanged", "EntityHierarchyChanged"),
            new EventType("EntityFrameChanged", "EntityFrameChanged"),
            new EventType("BrowserTextChanged", "BrowserTextChanged"),
            new EventType("PermissionsChanged", "PermissionsChanged"),
            new EventType("ResyncSignal", "resync"));

    private static final Map<String, List<String>> REQUIRED = requiredFields();

    private static final Map<String, String> FRAME_UPDATES = Map.of(
            "updateClassFrame", "ClassFrame",
            "updateObjectPropertyFrame", "ObjectPropertyFrame",
            "updateDataPropertyFrame", "DataPropertyFrame",
            "updateAnnotationPropertyFrame", "AnnotationPropertyFrame",
            "updateNamedIndividualFrame", "NamedIndividualFrame");

    @Override
    public void customise(OpenAPI api) {
        Components components = components(api);
        documentResponses(api, components);
        documentValueNode(components);
        documentEntityNode(components);
        documentEvents(api, components);
        documentSettings(components);
        REQUIRED.forEach((name, fields) -> schema(components, name).setRequired(new ArrayList<>(fields)));
    }

    private static Map<String, List<String>> requiredFields() {
        Map<String, List<String>> required = new LinkedHashMap<>();
        required.put("EntityNode", List.of("iri", "entityType", "displayName", "hasChildren", "deprecated"));
        required.put("RevisionPage", List.of("revisions", "page", "size", "total"));
        required.put("RevisionSummary", List.of("revNo", "userId", "ts", "description", "changeCount"));
        required.put("RevisionDiff", List.of("revNo", "changes", "limit", "total"));
        required.put("RevisionChange", List.of("op", "subject", "text"));
        required.put("IndividualPage", List.of("individuals", "page", "size", "total"));
        required.put("CreatedEntities", List.of("entities", "revision"));
        required.put("RevisionResponse", List.of("revision"));
        required.put("ProjectChanged", List.of("type", "revNo", "user", "timestamp", "description", "changeCount"));
        required.put("EntityHierarchyChanged", List.of("type", "revNo", "hierarchy", "added", "removed"));
        required.put("EntityFrameChanged", List.of("type", "revNo", "entity"));
        required.put("BrowserTextChanged", List.of("type", "revNo", "entity", "displayName"));
        required.put("PermissionsChanged", List.of("type", "revNo", "projectId"));
        required.put("ResyncSignal", List.of("type"));
        required.put(
                "NewEntitySettings", List.of("iriPrefix", "kit", "labelProperty", "defaultLang", "targetOntology"));
        required.put("UuidEntityCrudKit", List.of("type"));
        required.put("SuppliedNameEntityCrudKit", List.of("type", "whiteSpace"));
        required.put("OboEntityCrudKit", List.of("type", "totalDigits", "userRanges"));
        required.put("OboUserRange", List.of("userId", "start", "end"));
        required.put("DisplayNameSetting", List.of("annotationProperty", "lang"));
        return Map.copyOf(required);
    }

    private static void documentResponses(OpenAPI api, Components components) {
        setSchema(api, "getHierarchyRoots", "200", JSON, entityArray());
        setSchema(api, "getHierarchyChildren", "200", JSON, entityArray());
        setSchema(api, "searchEntities", "200", JSON, entityArray());
        setSchema(api, "getHierarchyPaths", "200", JSON, new ArraySchema().items(entityArray()));
        setSchema(api, "getClassFrame", "200", JSON, ref("ClassFrame"));
        setSchema(api, "getObjectPropertyFrame", "200", JSON, ref("ObjectPropertyFrame"));
        setSchema(api, "getDataPropertyFrame", "200", JSON, ref("DataPropertyFrame"));
        setSchema(api, "getAnnotationPropertyFrame", "200", JSON, ref("AnnotationPropertyFrame"));
        setSchema(api, "getNamedIndividualFrame", "200", JSON, ref("NamedIndividualFrame"));
        FRAME_UPDATES.forEach((operationId, frame) -> {
            String name = frame + "UpdateResult";
            components.addSchemas(name, frameUpdate(frame));
            setSchema(api, operationId, "200", JSON, ref(name));
        });
        setSchema(api, "listRevisions", "200", JSON, ref("RevisionPage"));
        setSchema(api, "listEntityRevisions", "200", JSON, ref("RevisionPage"));
        setSchema(api, "getRevisionDiff", "200", JSON, ref("RevisionDiff"));
        setSchema(api, "revertRevision", "200", JSON, ref("RevisionResponse"));
        setSchema(api, "deleteEntities", "200", JSON, ref("RevisionResponse"));
        setSchema(api, "createEntities", "201", JSON, ref("CreatedEntities"));
        setSchema(api, "listIndividualsByType", "200", JSON, ref("IndividualPage"));
        setSchema(api, "getPrefixSettings", "200", JSON, ref("PrefixSettings"));
        setSchema(api, "putPrefixSettings", "200", JSON, ref("PrefixSettings"));
        setSchema(api, "getNewEntitySettings", "200", JSON, ref("NewEntitySettings"));
        setSchema(api, "putNewEntitySettings", "200", JSON, ref("NewEntitySettings"));
        setSchema(api, "getDisplayNameSettings", "200", JSON, nameArray());
        setSchema(api, "putDisplayNameSettings", "200", JSON, nameArray());
    }

    private static ObjectSchema frameUpdate(String frame) {
        ObjectSchema schema = new ObjectSchema();
        schema.addProperty("frame", ref(frame));
        schema.addProperty("revision", new IntegerSchema().format("int64"));
        schema.setRequired(List.of("frame", "revision"));
        return schema;
    }

    private static ArraySchema entityArray() {
        return new ArraySchema().items(ref("EntityNode"));
    }

    private static ArraySchema nameArray() {
        return new ArraySchema().items(ref("DisplayNameSetting"));
    }

    private static void documentValueNode(Components components) {
        Schema<?> valueNode = schema(components, "ValueNode");
        Discriminator discriminator = valueNode.getDiscriminator();
        if (discriminator == null) {
            discriminator = new Discriminator();
            valueNode.setDiscriminator(discriminator);
        }
        discriminator.setPropertyName("type");
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("iri", pointer("IriValue"));
        mapping.put("literal", pointer("LiteralValue"));
        discriminator.setMapping(mapping);
        setSubtypeType(schema(components, "IriValue"), "iri");
        setSubtypeType(schema(components, "LiteralValue"), "literal");
    }

    private static void documentEntityNode(Components components) {
        setEnum(
                schema(components, "EntityNode"),
                "entityType",
                List.of(
                        "CLASS",
                        "OBJECT_PROPERTY",
                        "DATA_PROPERTY",
                        "ANNOTATION_PROPERTY",
                        "NAMED_INDIVIDUAL",
                        "DATATYPE",
                        "IRI",
                        "ONTOLOGY"));
    }

    private static void documentEvents(OpenAPI api, Components components) {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (EventType event : EVENTS) {
            setEnum(schema(components, event.schema()), "type", List.of(event.jsonValue()));
            mapping.put(event.jsonValue(), pointer(event.schema()));
        }
        setSchema(api, "pollProjectEvents", "200", JSON, new ArraySchema().items(eventUnion(mapping)));
        setSchema(api, "streamProjectEvents", "200", EVENT_STREAM, eventUnion(mapping));
    }

    private static Schema<?> eventUnion(Map<String, String> mapping) {
        Schema<?> union = new Schema<>();
        List<Schema> members = new ArrayList<>();
        for (EventType event : EVENTS) {
            members.add(ref(event.schema()));
        }
        union.setOneOf(members);
        Discriminator discriminator = new Discriminator().propertyName("type");
        discriminator.setMapping(new LinkedHashMap<>(mapping));
        union.setDiscriminator(discriminator);
        return union;
    }

    private static void documentSettings(Components components) {
        Schema<?> prefixes = schema(components, "PrefixSettings");
        prefixes.setType("object");
        prefixes.setAdditionalProperties(new StringSchema());
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("UUID", pointer("UuidEntityCrudKit"));
        mapping.put("SUPPLIED_NAME", pointer("SuppliedNameEntityCrudKit"));
        mapping.put("OBO", pointer("OboEntityCrudKit"));
        Schema<?> kit = new Schema<>();
        kit.setOneOf(List.of(ref("UuidEntityCrudKit"), ref("SuppliedNameEntityCrudKit"), ref("OboEntityCrudKit")));
        Discriminator discriminator = new Discriminator().propertyName("type");
        discriminator.setMapping(mapping);
        kit.setDiscriminator(discriminator);
        components.addSchemas("EntityCrudKitSettings", kit);
        setEnum(schema(components, "UuidEntityCrudKit"), "type", List.of("UUID"));
        setEnum(schema(components, "SuppliedNameEntityCrudKit"), "type", List.of("SUPPLIED_NAME"));
        setEnum(schema(components, "OboEntityCrudKit"), "type", List.of("OBO"));
        setEnum(schema(components, "SuppliedNameEntityCrudKit"), "whiteSpace", List.of("CAMEL_CASE", "UNDERSCORES"));
    }

    private static void setSchema(OpenAPI api, String operationId, String code, String mediaType, Schema<?> body) {
        ApiResponse response = response(api, operationId, code);
        Content content = response.getContent();
        if (content == null) {
            content = new Content();
            response.setContent(content);
        }
        MediaType media = content.get(mediaType);
        if (media == null) {
            media = new MediaType();
            content.addMediaType(mediaType, media);
        }
        media.setSchema(body);
        content.remove("*/*");
    }

    private static ApiResponse response(OpenAPI api, String operationId, String code) {
        Operation operation = operation(api, operationId);
        if (operation.getResponses() == null || operation.getResponses().get(code) == null) {
            throw new IllegalStateException(operationId + " has no " + code + " response");
        }
        return operation.getResponses().get(code);
    }

    private static Operation operation(OpenAPI api, String operationId) {
        if (api.getPaths() == null) {
            throw new IllegalStateException("OpenAPI document has no paths");
        }
        for (PathItem item : api.getPaths().values()) {
            for (Operation operation : item.readOperations()) {
                if (operationId.equals(operation.getOperationId())) {
                    return operation;
                }
            }
        }
        throw new IllegalStateException("OpenAPI operation is missing: " + operationId);
    }

    @SuppressWarnings("unchecked")
    private static void setSubtypeType(Schema<?> schema, String value) {
        Schema<?> branch = schema;
        List<Schema> allOf = schema.getAllOf();
        if (allOf != null) {
            for (Schema part : allOf) {
                if (part.get$ref() == null) {
                    branch = part;
                    break;
                }
            }
        }
        if (branch.getProperties() == null) {
            branch.setProperties(new LinkedHashMap<>());
        }
        StringSchema type = new StringSchema();
        type.setEnum(List.of(value));
        branch.getProperties().put("type", type);
    }

    @SuppressWarnings("unchecked")
    private static void setEnum(Schema<?> schema, String property, List<String> values) {
        if (schema.getProperties() == null || !schema.getProperties().containsKey(property)) {
            throw new IllegalStateException("schema has no property " + property);
        }
        Schema<String> propertySchema = schema.getProperties().get(property);
        propertySchema.setType("string");
        propertySchema.setEnum(new ArrayList<>(values));
    }

    private static Schema<?> schema(Components components, String name) {
        Schema<?> found =
                components.getSchemas() == null ? null : components.getSchemas().get(name);
        if (found == null) {
            throw new IllegalStateException("OpenAPI schema is missing: " + name);
        }
        return found;
    }

    private static Components components(OpenAPI api) {
        if (api.getComponents() == null) {
            api.setComponents(new Components());
        }
        return api.getComponents();
    }

    private static Schema<?> ref(String name) {
        Schema<?> schema = new Schema<>();
        schema.set$ref(pointer(name));
        return schema;
    }

    private static String pointer(String name) {
        return "#/components/schemas/" + name;
    }

    private record EventType(String schema, String jsonValue) {}
}
