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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.support.TestcontainersConfiguration;
import wprebuild.app.web.security.ApiKeyAuthenticationFilter;

/**
 * MCP history tools (P6-16): individuals, revisions, diffs, entity history, and docs/api/mcp.md tool list.
 *
 * <p>Invoked by {@code python tools/task.py test P6-16} and the card acceptance Surefire filters.
 */
class McpHistoryToolsTest {

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final Pattern DOC_TOOL = Pattern.compile("^\\|\\s*`([a-z][a-z0-9_]*)`\\s*\\|", Pattern.MULTILINE);

    private static final List<String> EXPECTED_TOOLS = List.of(
            "search_entities",
            "list_projects",
            "get_hierarchy_roots",
            "get_hierarchy_children",
            "get_hierarchy_paths",
            "get_entity_frame",
            "list_individuals",
            "list_revisions",
            "get_revision_diff",
            "list_entity_revisions");

    @Nested
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
    @Import(TestcontainersConfiguration.class)
    @TestPropertySource(
            properties = {
                "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p6-16",
                "wp.bootstrap.admin.username=admin",
                "wp.bootstrap.admin.password=admin-password",
                "wp.bootstrap.admin.email=admin@example.com",
                "wp.cors.allowed-origins=http://127.0.0.1:5173",
                "server.shutdown=immediate"
            })
    class HistoryIntegration {

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
        void tools_list_matches_mcp_doc() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String apiKey = issueApiKey(admin, "mcp-history-list");

            Set<String> documented = documentedToolNames();
            assertThat(documented).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();
                List<Tool> tools = client.listTools().tools();
                assertThat(tools).extracting(Tool::name).containsExactlyInAnyOrderElementsOf(EXPECTED_TOOLS);
                assertThat(tools).allSatisfy(tool -> {
                    assertThat(tool.inputSchema()).isNotNull();
                    assertThat(tool.description()).isNotBlank();
                });
            }
        }

        @Test
        void list_individuals_pages_match_rest() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = createProject(admin, "MCP Individuals");
            String classIri = createEntity(admin, projectId, "CLASS", "Pallet", List.of(THING));
            createEntity(admin, projectId, "NAMED_INDIVIDUAL", "box", List.of(classIri));
            createEntity(admin, projectId, "NAMED_INDIVIDUAL", "crate", List.of(classIri));
            createEntity(admin, projectId, "NAMED_INDIVIDUAL", "drum", List.of(classIri));
            String apiKey = issueApiKey(admin, "mcp-individuals");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();

                CallToolResult page0 = client.callTool(CallToolRequest.builder("list_individuals")
                        .arguments(Map.of("projectId", projectId, "cls", classIri, "page", 0, "size", 2))
                        .build());
                assertThat(page0.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpPage0 = readToolJson(page0).path("result");
                JsonNode restPage0 = admin.get(
                        "/api/projects/" + projectId + "/individuals",
                        Map.of("type", classIri, "page", "0", "size", "2"));
                assertThat(mcpPage0).isEqualTo(restPage0);
                assertThat(mcpPage0.path("page").asInt()).isZero();
                assertThat(mcpPage0.path("size").asInt()).isEqualTo(2);
                assertThat(mcpPage0.path("total").asLong()).isEqualTo(3);
                assertThat(displayNames(mcpPage0.path("individuals"))).containsExactly("box", "crate");

                CallToolResult page1 = client.callTool(CallToolRequest.builder("list_individuals")
                        .arguments(Map.of("projectId", projectId, "cls", classIri, "page", 1, "size", 2))
                        .build());
                assertThat(page1.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpPage1 = readToolJson(page1).path("result");
                JsonNode restPage1 = admin.get(
                        "/api/projects/" + projectId + "/individuals",
                        Map.of("type", classIri, "page", "1", "size", "2"));
                assertThat(mcpPage1).isEqualTo(restPage1);
                assertThat(displayNames(mcpPage1.path("individuals"))).containsExactly("drum");
            }
        }

        @Test
        void revisions_diff_and_entity_history_match_rest() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = createProject(admin, "MCP Revisions");
            String pallet = createEntity(admin, projectId, "CLASS", "Pallet", List.of(THING));
            createEntity(admin, projectId, "CLASS", "Bin", List.of(THING));
            String apiKey = issueApiKey(admin, "mcp-revisions");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();

                CallToolResult listed = client.callTool(CallToolRequest.builder("list_revisions")
                        .arguments(Map.of("projectId", projectId, "page", 0, "size", 2))
                        .build());
                assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpRevisions = readToolJson(listed).path("result");
                JsonNode restRevisions =
                        admin.get("/api/projects/" + projectId + "/revisions", Map.of("page", "0", "size", "2"));
                assertThat(mcpRevisions).isEqualTo(restRevisions);
                assertThat(mcpRevisions.path("revisions")).hasSize(2);
                long newest =
                        mcpRevisions.path("revisions").get(0).path("revNo").asLong();

                CallToolResult diff = client.callTool(CallToolRequest.builder("get_revision_diff")
                        .arguments(Map.of("projectId", projectId, "revision", newest))
                        .build());
                assertThat(diff.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpDiff = readToolJson(diff).path("result");
                JsonNode restDiff = admin.get("/api/projects/" + projectId + "/revisions/" + newest, Map.of());
                assertThat(mcpDiff).isEqualTo(restDiff);

                CallToolResult entityHistory = client.callTool(CallToolRequest.builder("list_entity_revisions")
                        .arguments(Map.of("projectId", projectId, "iri", pallet, "page", 0, "size", 20))
                        .build());
                assertThat(entityHistory.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode mcpHistory = readToolJson(entityHistory).path("result");
                JsonNode restHistory = admin.get(
                        "/api/projects/" + projectId + "/entities/history",
                        Map.of("iri", pallet, "page", "0", "size", "20"));
                assertThat(mcpHistory).isEqualTo(restHistory);
                assertThat(revNos(mcpHistory.path("revisions"))).containsExactly(2L);
                assertThat(mcpHistory.path("total").asLong()).isEqualTo(1);
            }
        }

        @Test
        void non_member_is_denied_on_history_tools() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = createProject(admin, "MCP Denied");
            String classIri = createEntity(admin, projectId, "CLASS", "Pallet", List.of(THING));

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
            String strangerKey = issueApiKey(stranger, "mcp-history-stranger");

            try (McpSyncClient visitor = mcpClient(strangerKey)) {
                visitor.initialize();
                assertDenied(visitor.callTool(CallToolRequest.builder("list_individuals")
                        .arguments(Map.of("projectId", projectId, "cls", classIri))
                        .build()));
                assertDenied(visitor.callTool(CallToolRequest.builder("list_revisions")
                        .arguments(Map.of("projectId", projectId))
                        .build()));
                assertDenied(visitor.callTool(CallToolRequest.builder("get_revision_diff")
                        .arguments(Map.of("projectId", projectId, "revision", 1))
                        .build()));
                assertDenied(visitor.callTool(CallToolRequest.builder("list_entity_revisions")
                        .arguments(Map.of("projectId", projectId, "iri", classIri))
                        .build()));
            }
        }

        private void assertDenied(CallToolResult result) {
            assertThat(result.isError()).isTrue();
            assertThat(readToolJson(result).path("code").asString()).isEqualTo("PERMISSION_DENIED");
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

        private String createProject(Api api, String name) {
            JsonNode created =
                    api.json(HttpMethod.POST, "/api/projects", 201, Map.of("name", name, "description", "p6-16"));
            return created.path("projectId").asString();
        }

        private String createEntity(Api api, String projectId, String entityType, String name, List<String> parents) {
            JsonNode created = api.json(
                    HttpMethod.POST,
                    "/api/projects/" + projectId + "/entities",
                    201,
                    Map.of("entityType", entityType, "names", List.of(name), "parents", parents));
            return created.path("entities").get(0).path("iri").asString();
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
                "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p6-16-limits",
                "wp.bootstrap.admin.username=admin",
                "wp.bootstrap.admin.password=admin-password",
                "wp.bootstrap.admin.email=admin@example.com",
                "wp.cors.allowed-origins=http://127.0.0.1:5173",
                "wp.mcp.max-result-bytes=280",
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
        void tiny_limit_truncates_revision_entries_and_keeps_page_fields() throws Exception {
            Api admin = session();
            admin.login("admin", ADMIN_PASSWORD);
            String projectId = createProject(admin, "MCP Revision Limits");
            for (int i = 0; i < 12; i++) {
                createEntity(admin, projectId, "CLASS", "Entity" + i, List.of(THING));
            }
            String apiKey = issueApiKey(admin, "mcp-revision-limits");

            try (McpSyncClient client = mcpClient(apiKey)) {
                client.initialize();
                CallToolResult listed = client.callTool(CallToolRequest.builder("list_revisions")
                        .arguments(Map.of("projectId", projectId, "page", 0, "size", 20))
                        .build());
                assertThat(listed.isError()).isNotEqualTo(Boolean.TRUE);
                JsonNode body = readToolJson(listed);
                assertThat(body.path("truncated").asBoolean()).isTrue();
                assertThat(body.path("hint").asString()).isNotBlank();
                JsonNode result = body.path("result");
                assertThat(result.path("page").asInt()).isZero();
                assertThat(result.path("size").asInt()).isEqualTo(20);
                assertThat(result.path("total").asLong())
                        .isGreaterThan(result.path("revisions").size());
                assertThat(result.path("revisions").size()).isGreaterThan(0);
                assertThat(result.path("revisions").size()).isLessThan(20);
                assertThat(body.toString().getBytes(StandardCharsets.UTF_8).length)
                        .isLessThanOrEqualTo(280);
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

        private String createProject(Api api, String name) {
            JsonNode created =
                    api.json(HttpMethod.POST, "/api/projects", 201, Map.of("name", name, "description", "p6-16"));
            return created.path("projectId").asString();
        }

        private void createEntity(Api api, String projectId, String entityType, String name, List<String> parents) {
            api.json(
                    HttpMethod.POST,
                    "/api/projects/" + projectId + "/entities",
                    201,
                    Map.of("entityType", entityType, "names", List.of(name), "parents", parents));
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

    private static Set<String> documentedToolNames() throws Exception {
        String markdown = Files.readString(mcpDocPath());
        Matcher matcher = DOC_TOOL.matcher(markdown);
        Set<String> names = new LinkedHashSet<>();
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static Path mcpDocPath() {
        String multiModule = System.getProperty("maven.multiModuleProjectDirectory");
        if (multiModule != null && !multiModule.isBlank()) {
            return Path.of(multiModule).resolve("docs/api/mcp.md");
        }
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("tools/task.py"))) {
                return dir.resolve("docs/api/mcp.md");
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "repository root not found from " + Path.of("").toAbsolutePath());
    }

    private static List<String> displayNames(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<Long> revNos(JsonNode nodes) {
        List<Long> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("revNo").asLong()));
        return values;
    }

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

        JsonNode get(String path, Map<String, String> query) {
            return client.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path(path);
                        query.forEach(uriBuilder::queryParam);
                        return uriBuilder.build();
                    })
                    .exchange((request, response) -> {
                        byte[] bytes = response.getBody().readAllBytes();
                        captureCookies(response.getHeaders());
                        assertThat(response.getStatusCode().value())
                                .as(new String(bytes, StandardCharsets.UTF_8))
                                .isEqualTo(200);
                        return json.readTree(new String(bytes, StandardCharsets.UTF_8));
                    });
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
                    .collect(Collectors.joining("; "));
        }

        @Override
        public void close() {
            // RestClient holds no connection pool that must be closed here.
        }
    }

    private record HttpExchange(int status, String body) {}
}
