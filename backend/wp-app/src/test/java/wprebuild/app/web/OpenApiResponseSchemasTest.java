package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.support.DatabaseTest;

/**
 * Web-facing success responses must name a real schema. An untyped {@code {"type":"object"}} is not
 * a contract the generated TypeScript client can use.
 */
@DatabaseTest
@AutoConfigureMockMvc
class OpenApiResponseSchemasTest {

    private static final String JSON = "application/json";

    private static final List<String> ENTITY_TYPES = List.of(
            "CLASS",
            "OBJECT_PROPERTY",
            "DATA_PROPERTY",
            "ANNOTATION_PROPERTY",
            "NAMED_INDIVIDUAL",
            "DATATYPE",
            "IRI",
            "ONTOLOGY");

    private static final List<Success> SUCCESSES = List.of(
            new Success("getHierarchyRoots", "200", JSON),
            new Success("getHierarchyChildren", "200", JSON),
            new Success("getHierarchyPaths", "200", JSON),
            new Success("searchEntities", "200", JSON),
            new Success("getClassFrame", "200", JSON),
            new Success("getObjectPropertyFrame", "200", JSON),
            new Success("getDataPropertyFrame", "200", JSON),
            new Success("getAnnotationPropertyFrame", "200", JSON),
            new Success("getNamedIndividualFrame", "200", JSON),
            new Success("updateClassFrame", "200", JSON),
            new Success("updateObjectPropertyFrame", "200", JSON),
            new Success("updateDataPropertyFrame", "200", JSON),
            new Success("updateAnnotationPropertyFrame", "200", JSON),
            new Success("updateNamedIndividualFrame", "200", JSON),
            new Success("listRevisions", "200", JSON),
            new Success("listEntityRevisions", "200", JSON),
            new Success("getRevisionDiff", "200", JSON),
            new Success("revertRevision", "200", JSON),
            new Success("deleteEntities", "200", JSON),
            new Success("createEntities", "201", JSON),
            new Success("listIndividualsByType", "200", JSON),
            new Success("getPrefixSettings", "200", JSON),
            new Success("putPrefixSettings", "200", JSON),
            new Success("getNewEntitySettings", "200", JSON),
            new Success("putNewEntitySettings", "200", JSON),
            new Success("getDisplayNameSettings", "200", JSON),
            new Success("putDisplayNameSettings", "200", JSON),
            new Success("pollProjectEvents", "200", JSON),
            new Success("streamProjectEvents", "200", "text/event-stream"),
            new Success("getMyProjectPermissions", "200", JSON),
            new Success("searchMemberCandidates", "200", JSON));

    private final MockMvc mockMvc;

    private final JsonMapper json;

    OpenApiResponseSchemasTest(MockMvc mockMvc, JsonMapper json) {
        this.mockMvc = mockMvc;
        this.json = json;
    }

    @Test
    void success_responses_name_a_concrete_schema() throws Exception {
        JsonNode spec = fetchLiveSpec();
        List<String> untyped = new ArrayList<>();
        for (Success success : SUCCESSES) {
            JsonNode operation = operation(spec, success.operationId());
            JsonNode response = operation.path("responses").path(success.code());
            JsonNode content = response.path("content");
            if (content.has("*/*")) {
                untyped.add(success.operationId() + " still documents */*");
            }
            JsonNode schema = content.path(success.mediaType()).path("schema");
            if (!isConcrete(schema)) {
                untyped.add(success.operationId() + " " + success.code() + " " + success.mediaType() + " schema is "
                        + schema);
            }
        }
        assertThat(untyped).isEmpty();
    }

    @Test
    void entity_node_fields_and_entity_type_are_closed() throws Exception {
        JsonNode entity = schema(fetchLiveSpec(), "EntityNode");
        assertThat(texts(entity.path("required")))
                .contains("iri", "entityType", "displayName", "hasChildren", "deprecated");
        assertThat(texts(entity.path("properties").path("entityType").path("enum")))
                .containsExactlyElementsOf(ENTITY_TYPES);
    }

    @Test
    void value_node_discriminator_uses_json_type_names() throws Exception {
        JsonNode spec = fetchLiveSpec();
        JsonNode mapping = schema(spec, "ValueNode").path("discriminator").path("mapping");
        assertThat(mapping.path("iri").asString()).contains("IriValue");
        assertThat(mapping.path("literal").asString()).contains("LiteralValue");
        assertThat(texts(typeEnum(schema(spec, "IriValue")))).containsExactly("iri");
        assertThat(texts(typeEnum(schema(spec, "LiteralValue")))).containsExactly("literal");
    }

    @Test
    void event_schemas_use_a_single_type_value() throws Exception {
        JsonNode spec = fetchLiveSpec();
        assertThat(texts(typeEnum(schema(spec, "ProjectChanged")))).containsExactly("ProjectChanged");
        assertThat(texts(typeEnum(schema(spec, "EntityHierarchyChanged")))).containsExactly("EntityHierarchyChanged");
        assertThat(texts(typeEnum(schema(spec, "EntityFrameChanged")))).containsExactly("EntityFrameChanged");
        assertThat(texts(typeEnum(schema(spec, "BrowserTextChanged")))).containsExactly("BrowserTextChanged");
        assertThat(texts(typeEnum(schema(spec, "PermissionsChanged")))).containsExactly("PermissionsChanged");
        assertThat(texts(typeEnum(schema(spec, "ResyncSignal")))).containsExactly("resync");
        JsonNode pollItems = operation(spec, "pollProjectEvents")
                .path("responses")
                .path("200")
                .path("content")
                .path(JSON)
                .path("schema")
                .path("items");
        JsonNode stream = operation(spec, "streamProjectEvents")
                .path("responses")
                .path("200")
                .path("content")
                .path("text/event-stream")
                .path("schema");
        assertThat(pollItems.path("oneOf").size()).isEqualTo(6);
        assertThat(stream.path("oneOf").size()).isEqualTo(6);
        assertThat(pollItems
                        .path("discriminator")
                        .path("mapping")
                        .path("resync")
                        .asString())
                .contains("ResyncSignal");
        assertThat(stream.path("discriminator").path("propertyName").asString()).isEqualTo("type");
        assertThat(stream.path("discriminator")
                        .path("mapping")
                        .path("EntityFrameChanged")
                        .asString())
                .contains("EntityFrameChanged");
    }

    @Test
    void constructor_fields_are_required() throws Exception {
        JsonNode spec = fetchLiveSpec();
        assertThat(texts(schema(spec, "RevisionPage").path("required"))).contains("revisions", "page", "size", "total");
        assertThat(texts(schema(spec, "RevisionSummary").path("required")))
                .contains("revNo", "userId", "ts", "description", "changeCount");
        assertThat(texts(schema(spec, "RevisionDiff").path("required"))).contains("revNo", "changes", "limit", "total");
        assertThat(texts(schema(spec, "RevisionChange").path("required"))).contains("op", "subject", "text");
        assertThat(texts(schema(spec, "IndividualPage").path("required")))
                .contains("individuals", "page", "size", "total");
        assertThat(texts(schema(spec, "CreatedEntities").path("required"))).contains("entities", "revision");
        assertThat(texts(schema(spec, "RevisionResponse").path("required"))).contains("revision");
        assertThat(texts(schema(spec, "ProjectChanged").path("required")))
                .contains("type", "revNo", "user", "timestamp", "description", "changeCount");
    }

    @Test
    void hierarchy_paths_and_frame_updates_have_documented_shapes() throws Exception {
        JsonNode spec = fetchLiveSpec();
        JsonNode paths = operation(spec, "getHierarchyPaths")
                .path("responses")
                .path("200")
                .path("content")
                .path(JSON)
                .path("schema");
        assertThat(paths.path("items").path("items").path("$ref").asString()).contains("EntityNode");
        assertThat(operation(spec, "updateClassFrame")
                        .path("responses")
                        .path("200")
                        .path("content")
                        .path(JSON)
                        .path("schema")
                        .path("$ref")
                        .asString())
                .contains("ClassFrameUpdateResult");
        for (String name : List.of(
                "ClassFrameUpdateResult",
                "ObjectPropertyFrameUpdateResult",
                "DataPropertyFrameUpdateResult",
                "AnnotationPropertyFrameUpdateResult",
                "NamedIndividualFrameUpdateResult")) {
            JsonNode result = schema(spec, name);
            assertThat(texts(result.path("required"))).contains("frame", "revision");
            assertThat(result.path("properties").path("revision").path("format").asString())
                    .isEqualTo("int64");
            assertThat(result.path("properties").path("frame").path("$ref").asString())
                    .contains(name.replace("UpdateResult", ""));
        }
    }

    @Test
    void settings_schemas_match_stored_documents() throws Exception {
        JsonNode spec = fetchLiveSpec();
        JsonNode prefixes = schema(spec, "PrefixSettings");
        assertThat(prefixes.path("type").asString()).isEqualTo("object");
        assertThat(prefixes.path("additionalProperties").path("type").asString())
                .isEqualTo("string");
        JsonNode kit = schema(spec, "EntityCrudKitSettings");
        assertThat(kit.path("oneOf").size()).isEqualTo(3);
        assertThat(kit.path("discriminator").path("propertyName").asString()).isEqualTo("type");
        assertThat(kit.path("discriminator").path("mapping").path("UUID").asString())
                .contains("UuidEntityCrudKit");
        assertThat(kit.path("discriminator")
                        .path("mapping")
                        .path("SUPPLIED_NAME")
                        .asString())
                .contains("SuppliedNameEntityCrudKit");
        assertThat(kit.path("discriminator").path("mapping").path("OBO").asString())
                .contains("OboEntityCrudKit");
        assertThat(schema(spec, "NewEntitySettings").path("properties").propertyNames())
                .contains("iriPrefix", "kit", "labelProperty", "defaultLang", "targetOntology");
        assertThat(texts(schema(spec, "SuppliedNameEntityCrudKit")
                        .path("properties")
                        .path("whiteSpace")
                        .path("enum")))
                .contains("CAMEL_CASE", "UNDERSCORES");
        assertThat(schema(spec, "DisplayNameSetting").path("properties").propertyNames())
                .contains("annotationProperty", "lang");
        assertThat(schema(spec, "OboUserRange").path("properties").propertyNames())
                .contains("userId", "start", "end");
    }

    private JsonNode fetchLiveSpec() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static JsonNode operation(JsonNode spec, String operationId) {
        JsonNode paths = spec.path("paths");
        for (String path : paths.propertyNames()) {
            JsonNode item = paths.get(path);
            for (String method : item.propertyNames()) {
                JsonNode operation = item.get(method);
                if (operationId.equals(operation.path("operationId").asString())) {
                    return operation;
                }
            }
        }
        throw new AssertionError("missing operation " + operationId);
    }

    private static JsonNode schema(JsonNode spec, String name) {
        JsonNode schema = spec.path("components").path("schemas").path(name);
        assertThat(schema.isObject()).as("schema %s", name).isTrue();
        return schema;
    }

    private static boolean isConcrete(JsonNode schema) {
        if (schema == null || !schema.isObject()) {
            return false;
        }
        if (schema.hasNonNull("$ref") || schema.has("items") || schema.has("oneOf")) {
            return true;
        }
        JsonNode properties = schema.get("properties");
        return properties != null && properties.isObject() && !properties.isEmpty();
    }

    private static JsonNode typeEnum(JsonNode schema) {
        JsonNode direct = schema.path("properties").path("type").path("enum");
        if (direct.isArray()) {
            return direct;
        }
        for (JsonNode part : schema.path("allOf")) {
            JsonNode nested = part.path("properties").path("type").path("enum");
            if (nested.isArray()) {
                return nested;
            }
        }
        return direct;
    }

    private static List<String> texts(JsonNode array) {
        assertThat(array.isArray()).as(array.toString()).isTrue();
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.asString());
        }
        return values;
    }

    private record Success(String operationId, String code, String mediaType) {}
}
