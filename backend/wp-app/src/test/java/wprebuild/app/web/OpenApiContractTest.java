package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.support.DatabaseTest;

/**
 * The live {@code /v3/api-docs} document must match {@code docs/api/openapi.json}.
 *
 * <p>When they drift, run {@code ./mvnw -pl :wp-app -Dwp.openapi.update=true test
 * -Dtest=OpenApiContractTest} to refresh the snapshot.
 */
@DatabaseTest
@AutoConfigureMockMvc
class OpenApiContractTest {

    static final String UPDATE_COMMAND = "./mvnw -pl :wp-app -Dwp.openapi.update=true test -Dtest=OpenApiContractTest";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    OpenApiContractTest(MockMvc mockMvc, JsonMapper json) {
        this.mockMvc = mockMvc;
        this.json = json;
    }

    @Test
    void live_spec_matches_committed_snapshot() throws Exception {
        JsonNode live = canonicalize(fetchLiveSpec());
        Path snapshot = snapshotPath();
        if (updateRequested()) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, pretty(live), StandardCharsets.UTF_8);
            return;
        }
        assertThat(Files.isRegularFile(snapshot))
                .as("OpenAPI snapshot is missing at %s. Generate it with:%n%s", snapshot, UPDATE_COMMAND)
                .isTrue();
        JsonNode committed = canonicalize(json.readTree(Files.readString(snapshot, StandardCharsets.UTF_8)));
        assertThat(live)
                .as("OpenAPI snapshot is out of date at %s. Update it with:%n%s", snapshot, UPDATE_COMMAND)
                .isEqualTo(committed);
    }

    @Test
    void problem_detail_is_the_shared_error_schema() throws Exception {
        JsonNode spec = fetchLiveSpec();
        assertThat(spec.path("components").path("schemas").path("ProblemDetail").isObject())
                .as("ProblemDetail must be the shared error schema")
                .isTrue();
        JsonNode responses =
                spec.path("paths").path("/api/auth/login").path("post").path("responses");
        assertThat(errorSchemaRef(responses.path("400")))
                .as("login documents 400 as ProblemDetail")
                .contains("ProblemDetail");
        assertThat(errorSchemaRef(responses.path("401")))
                .as("login documents 401 as ProblemDetail")
                .contains("ProblemDetail");
    }

    @Test
    void every_operation_has_a_tag_and_summary() throws Exception {
        JsonNode paths = fetchLiveSpec().path("paths");
        assertThat(paths.isObject()).isTrue();
        List<String> missing = new ArrayList<>();
        for (String path : paths.propertyNames()) {
            JsonNode item = paths.get(path);
            for (String method : item.propertyNames()) {
                JsonNode operation = item.get(method);
                if (!operation.isObject() || !operation.path("operationId").isString()) {
                    continue;
                }
                String id = path + " " + method;
                if (operation.path("tags").isEmpty()) {
                    missing.add(id + " has no tag");
                }
                if (operation.path("summary").asString().isBlank()) {
                    missing.add(id + " has no summary");
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    private JsonNode fetchLiveSpec() throws Exception {
        MvcResult result =
                mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn();
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sorted = json.createObjectNode();
            List<String> names = new ArrayList<>();
            for (String name : node.propertyNames()) {
                if ("servers".equals(name)) {
                    continue;
                }
                names.add(name);
            }
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode copy = json.createArrayNode();
            for (JsonNode item : node) {
                copy.add(canonicalize(item));
            }
            return copy;
        }
        return node;
    }

    private String pretty(JsonNode node) {
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(node) + "\n";
    }

    private static String errorSchemaRef(JsonNode response) {
        JsonNode schema =
                response.path("content").path("application/problem+json").path("schema");
        if (schema.has("$ref")) {
            return schema.path("$ref").asString();
        }
        return schema.toString();
    }

    private static boolean updateRequested() {
        return Boolean.parseBoolean(System.getProperty("wp.openapi.update", "false"));
    }

    static Path snapshotPath() {
        String multiModule = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModule != null && !multiModule.isBlank()) {
            return Path.of(multiModule).resolve("docs/api/openapi.json");
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("tools/task.py"))) {
                return dir.resolve("docs/api/openapi.json");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repository root not found from " + Path.of("").toAbsolutePath());
    }
}
