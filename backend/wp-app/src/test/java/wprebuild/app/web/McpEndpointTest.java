package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
import wprebuild.app.support.TestcontainersConfiguration;
import wprebuild.app.web.security.ApiKeyAuthenticationFilter;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * MCP Streamable HTTP PoC (P6-14): API-key auth, {@code search_entities}, and permission checks.
 *
 * <p>Invoked by {@code python tools/task.py test P6-14} and the card acceptance Surefire filters.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p6-14",
            "wp.bootstrap.admin.username=admin",
            "wp.bootstrap.admin.password=admin-password",
            "wp.bootstrap.admin.email=admin@example.com",
            "wp.cors.allowed-origins=http://127.0.0.1:5173",
            "server.shutdown=immediate"
        })
class McpEndpointTest {

    private static final Logger log = LoggerFactory.getLogger(McpEndpointTest.class);

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

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
    void initialize_lists_search_entities_and_finds_pallet() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);
        String projectId = importWarehouse(admin);
        configureDisplayNames(admin, projectId);
        String apiKey = issueApiKey(admin, "mcp-owner");

        try (McpSyncClient client = mcpClient(apiKey)) {
            client.initialize();
            List<Tool> tools = client.listTools().tools();
            assertThat(tools).extracting(Tool::name).contains("search_entities");
            Tool search = tools.stream()
                    .filter(tool -> "search_entities".equals(tool.name()))
                    .findFirst()
                    .orElseThrow();
            assertThat(search.inputSchema()).isNotNull();
            assertThat(search.inputSchema().get("properties")).isInstanceOf(Map.class);

            CallToolResult result = client.callTool(CallToolRequest.builder("search_entities")
                    .arguments(Map.of("projectId", projectId, "query", "托盘", "limit", 20))
                    .build());
            assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            JsonNode body = readToolJson(result);
            assertThat(body.path("truncated").asBoolean()).isFalse();
            assertThat(body.path("hint").isNull()).isTrue();
            assertThat(displayNames(body.path("result"))).anyMatch(name -> name.contains("托盘"));
        }
    }

    @Test
    void rejects_missing_key_session_cookie_and_disallowed_origin() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);

        HttpResponse<String> anonymous = rawPost(null, null, null);
        assertThat(anonymous.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        String sessionCookie = admin.sessionCookieHeader();
        HttpResponse<String> cookieOnly = rawPost(null, sessionCookie, null);
        assertThat(cookieOnly.statusCode()).isEqualTo(HttpStatus.UNAUTHORIZED.value());

        String apiKey = issueApiKey(admin, "mcp-origin");
        HttpResponse<String> badOrigin = rawPost(apiKey, null, "https://evil.example");
        assertThat(badOrigin.statusCode()).isEqualTo(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void permission_denied_for_stranger_and_stateless_uses_caller_key() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);
        String projectId = importWarehouse(admin);
        configureDisplayNames(admin, projectId);
        String ownerKey = issueApiKey(admin, "mcp-a");

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
        String strangerKey = issueApiKey(stranger, "mcp-b");

        try (McpSyncClient owner = mcpClient(ownerKey);
                McpSyncClient visitor = mcpClient(strangerKey)) {
            owner.initialize();
            CallToolResult allowed = owner.callTool(CallToolRequest.builder("search_entities")
                    .arguments(Map.of("projectId", projectId, "query", "托盘"))
                    .build());
            assertThat(allowed.isError()).isNotEqualTo(Boolean.TRUE);
            assertThat(displayNames(readToolJson(allowed).path("result"))).isNotEmpty();

            visitor.initialize();
            CallToolResult denied = visitor.callTool(CallToolRequest.builder("search_entities")
                    .arguments(Map.of("projectId", projectId, "query", "托盘"))
                    .build());
            assertThat(denied.isError()).isTrue();
            JsonNode error = readToolJson(denied);
            assertThat(error.path("code").asString()).isEqualTo("PERMISSION_DENIED");
            assertThat(error.path("result").isMissingNode()
                            || error.path("result").isNull())
                    .isTrue();
            assertThat(error.toString()).doesNotContain("托盘");
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "bench", matches = "true")
    void search_entities_p95_is_at_most_100ms() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);
        String projectId = importWarehouse(admin);
        configureDisplayNames(admin, projectId);
        String apiKey = issueApiKey(admin, "mcp-bench");

        try (McpSyncClient client = mcpClient(apiKey)) {
            client.initialize();
            for (int i = 0; i < 10; i++) {
                client.callTool(CallToolRequest.builder("search_entities")
                        .arguments(Map.of("projectId", projectId, "query", "托盘", "limit", 20))
                        .build());
            }
            List<Long> nanos = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                long start = System.nanoTime();
                CallToolResult result = client.callTool(CallToolRequest.builder("search_entities")
                        .arguments(Map.of("projectId", projectId, "query", "托盘", "limit", 20))
                        .build());
                nanos.add(System.nanoTime() - start);
                assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
            }
            nanos.sort(Comparator.naturalOrder());
            long p95 = nanos.get(94);
            double p95Ms = p95 / 1_000_000.0;
            log.info("MCP search_entities p95={} ms (n=100)", String.format("%.2f", p95Ms));
            assertThat(p95).as("p95 ns").isLessThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(100));
        }
    }

    private McpSyncClient mcpClient(String apiKey) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport.builder(baseUrl())
                .endpoint("/mcp")
                .requestBuilder(HttpRequest.newBuilder().header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .build();
        McpSyncClient client =
                McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        open.add(client);
        return client;
    }

    private HttpResponse<String> rawPost(String apiKey, String cookieHeader, String origin) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/mcp"))
                .timeout(Duration.ofSeconds(10))
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                + "\"protocolVersion\":\"2025-06-18\","
                                + "\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"wp-test\",\"version\":\"0\"}}}"));
        if (apiKey != null) {
            builder.header(ApiKeyAuthenticationFilter.HEADER, apiKey);
        }
        if (cookieHeader != null) {
            builder.header(HttpHeaders.COOKIE, cookieHeader);
        }
        if (origin != null) {
            builder.header(HttpHeaders.ORIGIN, origin);
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String importWarehouse(Api api) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("name", "Warehouse MCP");
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

    private JsonNode readToolJson(CallToolResult result) throws Exception {
        assertThat(result.content()).isNotEmpty();
        assertThat(result.content().getFirst()).isInstanceOf(TextContent.class);
        TextContent text = (TextContent) result.content().getFirst();
        return json.readTree(text.text());
    }

    private static List<String> displayNames(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        if (nodes != null && nodes.isArray()) {
            nodes.forEach(node -> values.add(node.path("displayName").asString()));
        }
        return values;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    private Api session() {
        Api api = new Api();
        open.add(api);
        return api;
    }

    private final class Api implements AutoCloseable {

        private final RestClient client;

        private final Map<String, String> cookies = new LinkedHashMap<>();

        private Api() {
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
            factory.setReadTimeout(Duration.ofSeconds(60));
            this.client = RestClient.builder()
                    .baseUrl(baseUrl())
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
            var response = exchange(
                    method,
                    path,
                    MediaType.APPLICATION_JSON,
                    body instanceof String ? body : McpEndpointTest.this.json.writeValueAsString(body));
            assertThat(response.status()).as(response.body()).isEqualTo(status);
            if (response.body() == null || response.body().isBlank()) {
                return McpEndpointTest.this.json.missingNode();
            }
            return McpEndpointTest.this.json.readTree(response.body());
        }

        JsonNode multipart(String path, MultiValueMap<String, Object> parts, int status) {
            var response = exchange(HttpMethod.POST, path, MediaType.MULTIPART_FORM_DATA, parts);
            assertThat(response.status()).as(response.body()).isEqualTo(status);
            return McpEndpointTest.this.json.readTree(response.body());
        }

        private HttpExchange exchange(HttpMethod method, String path, MediaType contentType, Object body) {
            RestClient.RequestBodySpec spec = client.method(method).uri(path);
            if (contentType != null && body != null) {
                spec.contentType(contentType).body(body);
            }
            return spec.exchange((request, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                captureCookies(response.getHeaders());
                return new HttpExchange(
                        response.getStatusCode().value(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            });
        }

        String sessionCookieHeader() {
            assertThat(cookieHeader()).isNotBlank();
            return cookieHeader();
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
