package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.support.TestcontainersConfiguration;
import wprebuild.app.web.mcp.McpResultLimits;
import wprebuild.app.web.security.ApiKeyAuthenticationFilter;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * MCP browse tools (P6-15): project list, hierarchy navigation, entity frames, and result limits.
 *
 * <p>Invoked by {@code python tools/task.py test P6-15} and the card acceptance Surefire filters.
 */
class McpBrowseToolsTest {

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String PALLET = WH + "Pallet";

    private static final List<String> EXPECTED_TOOLS = List.of(
            "search_entities",
            "list_projects",
            "get_hierarchy_roots",
            "get_hierarchy_children",
            "get_hierarchy_paths",
            "get_entity_frame");

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(
            properties = {
                "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p6-15",
                "wp.bootstrap.admin.username=admin",
                "wp.bootstrap.admin.password=admin-password",
                "wp.bootstrap.admin.email=admin@example.com",
                "wp.cors.allowed-origins=http://127.0.0.1:5173",
                "server.shutdown=immediate"
            })
    class BrowseIntegration {

        private final JsonMapper json = JsonMapper.builder().build();

        private final List<AutoCloseable> open = new ArrayList<>();

        @LocalServerPort
        private int port;

        @AfterEach
        void closeClients() throws Exception {
            for (AutoCloseable client : open) {
                client.close();
            }
            open.clear();
        }

        @Test
        void tools_list_includes_browse_tools_with_schemas() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String apiKey = issueApiKey(admin, "mcp-browse-list");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();
                List<Tool> tools = client.listTools().tools();
                assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
                assertThat(tools).allSatisfy(tool -> {
                    assertThat(tool.inputSchema()).isNotNull();
                    assertThat(tool.inputSchema().get("properties")).isInstanceOf(Map.class);
                    assertThat(tool.description()).isNotBlank();
                });
            }
        }

        @Test
        void browses_hierarchy_to_pallet_and_frame_matches_rest() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = importWarehouse(admin);
            configureDisplayNames(admin, projectId);
            String apiKey = issueApiKey(admin, "mcp-browse-nav");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();

                CallToolResult rootsResult = client.callTool(CallToolRequest.builder("get_hierarchy_roots")
                        .arguments(Map.of("projectId", projectId, "kind", "class"))
                        .build());
                assertThat(rootsResult.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode roots = readToolJson(rootsResult).path("result");
                assertThat(roots).hasSize(1);
                assertThat(roots.get(0).path("iri").asString()).isEqualTo(THING);

                CallToolResult topChildren = client.callTool(CallToolRequest.builder("get_hierarchy_children")
                        .arguments(Map.of("projectId", projectId, "kind", "class", "parent", THING))
                        .build());
                assertThat(topChildren.isError()).isNotEqualTo(Boolean.TRUE);
                String handlingUnit =
                        findIriByDisplayName(readToolJson(topChildren).path("result"), "搬运单元");
                assertThat(handlingUnit).isEqualTo(WH + "HandlingUnit");

                CallToolResult unitChildren = client.callTool(CallToolRequest.builder("get_hierarchy_children")
                        .arguments(Map.of("projectId", projectId, "kind", "class", "parent", handlingUnit))
                        .build());
                assertThat(unitChildren.isError()).isNotEqualTo(Boolean.TRUE);
                String pallet = findIriByDisplayName(readToolJson(unitChildren).path("result"), "托盘");
                assertThat(pallet).isEqualTo(PALLET);

                CallToolResult pathsResult = client.callTool(CallToolRequest.builder("get_hierarchy_paths")
                        .arguments(Map.of("projectId", projectId, "kind", "class", "entity", pallet))
                        .build());
                assertThat(pathsResult.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode paths = readToolJson(pathsResult).path("result");
                assertThat(paths).hasSize(1);
                assertThat(iris(paths.get(0))).containsExactly(THING, WH + "HandlingUnit", PALLET);
                assertThat(displayNames(paths.get(0))).containsExactly("owl:Thing", "搬运单元", "托盘");

                CallToolResult frameResult = client.callTool(CallToolRequest.builder("get_entity_frame")
                        .arguments(Map.of("projectId", projectId, "entityType", "class", "iri", pallet))
                        .build());
                assertThat(frameResult.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpFrame = readToolJson(frameResult).path("result");
                JsonNode restFrame = admin.getClassFrame(projectId, pallet);
                assertThat(mcpFrame).isEqualTo(restFrame);
            }
        }

        @Test
        void permission_and_validation_errors() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = importWarehouse(admin);
            String ownerKey = issueApiKey(admin, "mcp-browse-owner");

            String strangerId = "stranger-" + UUID.randomUUID().toString().substring(0, 8);
            admin.json(
                    HttpMethod.POST,
                    "/api/admin/users",
                    201,
                    Map.of(
                            "userId",
                            strangerId,
                            "displayName",
                            "Stranger",
                            "email",
                            strangerId + "@example.com",
                            "initialPassword",
                            "stranger-password"));
            Api stranger = session();
            stranger.login(strangerId, "stranger-password");
            String strangerKey = issueApiKey(stranger, "mcp-browse-stranger");

            try (McpSyncClient owner = mcpClient(ownerKey);
                    McpSyncClient visitor = mcpClient(strangerKey)) {
                owner.initialize();
                visitor.initialize();

                CallToolResult listed = visitor.callTool(CallToolRequest.builder("list_projects")
                        .arguments(Map.of())
                        .build());
                assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode projects = readToolJson(listed).path("result");
                assertThat(projects.isArray()).isTrue();
                assertThat(projectIds(projects)).doesNotContain(projectId);

                CallToolResult denied = visitor.callTool(CallToolRequest.builder("get_hierarchy_roots")
                        .arguments(Map.of("projectId", projectId, "kind", "class"))
                        .build());
                assertThat(denied.isError()).isTrue();
                assertThat(readToolJson(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");

                CallToolResult missing = owner.callTool(CallToolRequest.builder("get_hierarchy_roots")
                        .arguments(Map.of("kind", "class"))
                        .build());
                assertThat(missing.isError()).isTrue();
                assertThat(readToolJson(missing).path("code").asString()).isEqualTo("VALIDATION_FAILED");

                CallToolResult badKind = owner.callTool(CallToolRequest.builder("get_hierarchy_roots")
                        .arguments(Map.of("projectId", projectId, "kind", "not-a-kind"))
                        .build());
                assertThat(badKind.isError()).isTrue();
                assertThat(readToolJson(badKind).path("code").asString()).isEqualTo("VALIDATION_FAILED");

                CallToolResult relativeIri = owner.callTool(CallToolRequest.builder("get_entity_frame")
                        .arguments(Map.of("projectId", projectId, "entityType", "class", "iri", "Pallet"))
                        .build());
                assertThat(relativeIri.isError()).isTrue();
                assertThat(readToolJson(relativeIri).path("code").asString()).isEqualTo("VALIDATION_FAILED");
            }
        }

        private McpSyncClient mcpClient(String apiKey) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequestHeader.header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            open.add(client);
            return client;
        }

        private String importWarehouse(Api api) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("name", "Warehouse MCP Browse");
            parts.add("file", new FileSystemResource(corpus("warehouse-sample.ttl")));
            JsonNode created = api.multipart("/api/projects/upload", parts, 201);
            return created.path("projectId").asString();
        }

        private void configureDisplayNames(Api api, String projectId) {
            ArrayNode names = json.createArrayNode();
            for (String lang : List.of("", "zh", "en")) {
                ObjectNode item = names.addObject();
                item.put("annotationProperty", LABEL);
                item.put("lang", lang);
            }
            api.json(HttpMethod.PUT, "/api/projects/" + projectId + "/settings/display-names", 200, names);
        }

        private String issueApiKey(Api api, String name) {
            JsonNode issued = api.json(HttpMethod.POST, "/api/me/api-keys", 201, Map.of("name", name));
            String key = issued.path("apiKey").asString();
            assertThat(key).isNotBlank();
            return key;
        }

        private JsonNode readToolJson(CallToolResult result) {
            assertThat(result.content()).isNotEmpty();
            assertThat(result.content().getFirst()).isInstanceOf(TextContent.class);
            TextContent text = (TextContent) result.content().getFirst();
            return json.readTree(text.text());
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + port;
        }

        private Api session() {
            Api api = new Api(baseUrl(), json);
            open.add(api);
            return api;
        }
    }

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(
            properties = {
                "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p6-15-limits",
                "wp.bootstrap.admin.username=admin",
                "wp.bootstrap.admin.password=admin-password",
                "wp.bootstrap.admin.email=admin@example.com",
                "wp.cors.allowed-origins=http://127.0.0.1:5173",
                "wp.mcp.max-result-bytes=180",
                "server.shutdown=immediate"
            })
    class LimitsIntegration {

        private final JsonMapper json = JsonMapper.builder().build();

        private final List<AutoCloseable> open = new ArrayList<>();

        @LocalServerPort
        private int port;

        @AfterEach
        void closeClients() throws Exception {
            for (AutoCloseable client : open) {
                client.close();
            }
            open.clear();
        }

        @Test
        void tiny_limit_truncates_arrays_and_rejects_large_frames() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = importWarehouse(admin);
            configureDisplayNames(admin, projectId);
            String apiKey = issueApiKey(admin, "mcp-browse-limits");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();

                CallToolResult children = client.callTool(CallToolRequest.builder("get_hierarchy_children")
                        .arguments(Map.of("projectId", projectId, "kind", "class", "parent", THING))
                        .build());
                assertThat(children.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode body = readToolJson(children);
                assertThat(body.path("truncated").asBoolean()).isTrue();
                assertThat(body.path("hint").asString()).isNotBlank();
                assertThat(body.path("result").isArray()).isTrue();
                assertThat(body.toString().getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(180);
                body.path("result").forEach(node -> {
                    assertThat(node.path("iri").asString()).isNotBlank();
                    assertThat(node.path("displayName").asString()).isNotBlank();
                });

                CallToolResult frame = client.callTool(CallToolRequest.builder("get_entity_frame")
                        .arguments(Map.of("projectId", projectId, "entityType", "class", "iri", PALLET))
                        .build());
                assertThat(frame.isError()).isTrue();
                JsonNode error = readToolJson(frame);
                assertThat(error.path("code").asString()).isEqualTo("RESULT_TOO_LARGE");
                assertThat(error.path("message").asString()).contains("180");
            }
        }

        private McpSyncClient mcpClient(String apiKey) {
            HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                    .endpoint("/mcp")
                    .requestBuilder(HttpRequestHeader.header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                    .build();
            McpSyncClient client = McpClient.sync(transport)
                    .requestTimeout(Duration.ofSeconds(30))
                    .build();
            open.add(client);
            return client;
        }

        private String importWarehouse(Api api) {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            parts.add("name", "Warehouse MCP Limits");
            parts.add("file", new FileSystemResource(corpus("warehouse-sample.ttl")));
            JsonNode created = api.multipart("/api/projects/upload", parts, 201);
            return created.path("projectId").asString();
        }

        private void configureDisplayNames(Api api, String projectId) {
            ArrayNode names = json.createArrayNode();
            for (String lang : List.of("", "zh", "en")) {
                ObjectNode item = names.addObject();
                item.put("annotationProperty", LABEL);
                item.put("lang", lang);
            }
            api.json(HttpMethod.PUT, "/api/projects/" + projectId + "/settings/display-names", 200, names);
        }

        private String issueApiKey(Api api, String name) {
            JsonNode issued = api.json(HttpMethod.POST, "/api/me/api-keys", 201, Map.of("name", name));
            return issued.path("apiKey").asString();
        }

        private JsonNode readToolJson(CallToolResult result) {
            assertThat(result.content()).isNotEmpty();
            TextContent text = (TextContent) result.content().getFirst();
            return json.readTree(text.text());
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + port;
        }

        private Api session() {
            Api api = new Api(baseUrl(), json);
            open.add(api);
            return api;
        }
    }

    @Test
    void result_limits_keep_complete_array_elements_and_page_fields() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        McpResultLimits limits = new McpResultLimits(json, 220);
        List<EntityNode> nodes = List.of(
                new EntityNode("http://ex/A", "CLASS", "Alpha", true, false),
                new EntityNode("http://ex/B", "CLASS", "Beta", false, false),
                new EntityNode("http://ex/C", "CLASS", "Gamma", false, false),
                new EntityNode("http://ex/D", "CLASS", "Delta", false, false),
                new EntityNode("http://ex/E", "CLASS", "Epsilon", false, false));

        CallToolResult truncated = limits.encode(nodes, McpResultLimits.HINT_HIERARCHY_ARRAY);
        assertThat(truncated.isError()).isNotEqualTo(Boolean.TRUE);
        JsonNode body = json.readTree(((TextContent) truncated.content().getFirst()).text());
        assertThat(body.path("truncated").asBoolean()).isTrue();
        assertThat(body.path("hint").asString()).isEqualTo(McpResultLimits.HINT_HIERARCHY_ARRAY);
        assertThat(body.path("result").isArray()).isTrue();
        assertThat(body.path("result").size()).isGreaterThan(0);
        assertThat(body.path("result").size()).isLessThan(nodes.size());
        assertThat(body.toString().getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(220);

        ObjectNode page = json.createObjectNode();
        ArrayNode revisions = page.putArray("revisions");
        for (int i = 0; i < 20; i++) {
            ObjectNode item = revisions.addObject();
            item.put("revisionNumber", i);
            item.put("userId", "user-" + i);
            item.put("msg", "change-" + i + "-with-padding-xxxxxxxx");
        }
        page.put("page", 0);
        page.put("size", 20);
        page.put("total", 100);

        CallToolResult paged = limits.encode(page, McpResultLimits.HINT_PAGE);
        JsonNode pagedBody = json.readTree(((TextContent) paged.content().getFirst()).text());
        assertThat(pagedBody.path("truncated").asBoolean()).isTrue();
        assertThat(pagedBody.path("result").path("page").asInt()).isEqualTo(0);
        assertThat(pagedBody.path("result").path("size").asInt()).isEqualTo(20);
        assertThat(pagedBody.path("result").path("total").asLong()).isEqualTo(100);
        assertThat(pagedBody.path("result").path("revisions").size()).isLessThan(20);
    }

    @Test
    void result_limits_reject_oversized_non_array_objects() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        McpResultLimits limits = new McpResultLimits(json, 80);
        ObjectNode frame = json.createObjectNode();
        frame.put("subject", "http://example.org/wp-test/warehouse#Pallet");
        frame.put("padding", "x".repeat(200));

        CallToolResult rejected = limits.encode(frame, McpResultLimits.HINT_HIERARCHY_ARRAY);
        assertThat(rejected.isError()).isTrue();
        JsonNode error = json.readTree(((TextContent) rejected.content().getFirst()).text());
        assertThat(error.path("code").asString()).isEqualTo(McpResultLimits.RESULT_TOO_LARGE);
        assertThat(error.path("message").asString()).contains("80");
    }

    @Test
    void result_limits_reject_when_empty_truncated_envelope_exceeds_budget() throws Exception {
        JsonMapper json = JsonMapper.builder().build();
        McpResultLimits limits = new McpResultLimits(json, 40);
        List<EntityNode> nodes = List.of(new EntityNode("http://ex/A", "CLASS", "Alpha", true, false));

        CallToolResult rejected = limits.encode(nodes, McpResultLimits.HINT_HIERARCHY_ARRAY);
        assertThat(rejected.isError()).isTrue();
        JsonNode error = json.readTree(((TextContent) rejected.content().getFirst()).text());
        assertThat(error.path("code").asString()).isEqualTo(McpResultLimits.RESULT_TOO_LARGE);
    }

    private static String findIriByDisplayName(JsonNode nodes, String displayName) {
        for (JsonNode node : nodes) {
            if (displayName.equals(node.path("displayName").asString())) {
                return node.path("iri").asString();
            }
        }
        throw new AssertionError("missing display name: " + displayName);
    }

    private static List<String> displayNames(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<String> iris(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("iri").asString()));
        return values;
    }

    private static List<String> projectIds(JsonNode projects) {
        List<String> ids = new ArrayList<>();
        projects.forEach(node -> ids.add(node.path("projectId").asString()));
        return ids;
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    /** Adapter so the MCP transport builder can take a single header without a raw HttpRequest import clash. */
    private static final class HttpRequestHeader {
        private HttpRequestHeader() {}

        static java.net.http.HttpRequest.Builder header(String name, String value) {
            return java.net.http.HttpRequest.newBuilder().header(name, value);
        }
    }

    private static final class Api implements AutoCloseable {

        private final RestClient client;

        private final JsonMapper json;

        private final Map<String, String> cookies = new LinkedHashMap<>();

        private Api(String baseUrl, JsonMapper json) {
            this.json = json;
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(60));
            this.client = RestClient.builder()
                    .baseUrl(baseUrl)
                    .requestFactory(factory)
                    .requestInterceptor((request, body, execution) -> {
                        if (!cookies.isEmpty()) {
                            request.getHeaders().set(HttpHeaders.COOKIE, cookieHeader());
                        }
                        String csrf = cookies.get("XSRF-TOKEN");
                        if (csrf != null
                                && request.getMethod() != HttpMethod.GET
                                && request.getMethod() != HttpMethod.HEAD) {
                            request.getHeaders().set(CSRF_HEADER, csrf);
                        }
                        return execution.execute(request, body);
                    })
                    .build();
        }

        void login(String username, String password) {
            exchange(HttpMethod.GET, "/api/auth/me", null, null);
            var response = exchange(
                    HttpMethod.POST,
                    "/api/auth/login",
                    MediaType.APPLICATION_JSON,
                    Map.of("username", username, "password", password));
            assertThat(response.status()).as(response.body()).isEqualTo(200);
        }

        JsonNode json(HttpMethod method, String path, int status, Object body) {
            MediaType contentType = body == null ? null : MediaType.APPLICATION_JSON;
            Object payload = body == null ? null : body instanceof String text ? text : json.writeValueAsString(body);
            var response = exchange(method, path, contentType, payload);
            assertThat(response.status()).as(response.body()).isEqualTo(status);
            if (response.body() == null || response.body().isBlank()) {
                return json.missingNode();
            }
            return json.readTree(response.body());
        }

        JsonNode getClassFrame(String projectId, String iri) {
            return client.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/api/projects/{id}/frames/class")
                            .queryParam("iri", iri)
                            .build(projectId))
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody().readAllBytes();
                        captureCookies(response.getHeaders());
                        assertThat(response.getStatusCode().value())
                                .as(new String(bytes, StandardCharsets.UTF_8))
                                .isEqualTo(200);
                        return json.readTree(new String(bytes, StandardCharsets.UTF_8));
                    });
        }

        JsonNode multipart(String path, MultiValueMap<String, Object> parts, int status) {
            var response = exchange(HttpMethod.POST, path, MediaType.MULTIPART_FORM_DATA, parts);
            assertThat(response.status()).as(response.body()).isEqualTo(status);
            return json.readTree(response.body());
        }

        private HttpExchange exchange(HttpMethod method, String path, MediaType contentType, Object body) {
            RestClient.RequestBodySpec spec = client.method(method).uri(path);
            if (contentType != null && body != null) {
                spec.contentType(contentType).body(body);
            }
            return spec.exchange((request, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                captureCookies(response.getHeaders());
                return new HttpExchange(response.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
            });
        }

        private void captureCookies(HttpHeaders headers) {
            List<String> raw = headers.get(HttpHeaders.SET_COOKIE);
            if (raw == null) {
                return;
            }
            for (String header : raw) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(
                            pair.substring(0, eq).strip(),
                            pair.substring(eq + 1).strip());
                }
            }
        }

        private String cookieHeader() {
            return cookies.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .reduce((left, right) -> left + "; " + right)
                    .orElse("");
        }

        @Override
        public void close() {
            // RestClient holds no connection pool that must be closed here.
        }
    }

    private record HttpExchange(int status, String body) {}
}
