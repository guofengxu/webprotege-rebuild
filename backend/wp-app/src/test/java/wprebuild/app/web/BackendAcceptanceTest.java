package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
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
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Milestone M3: the backend MVP exercised only over HTTP (P3-24).
 *
 * <p>Default display-name settings index {@code rdfs:label} with an empty language, so a
 * language-tagged English label is invisible to search until that language is configured.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-p3-24",
            "wp.bootstrap.admin.username=admin",
            "wp.bootstrap.admin.password=admin-password",
            "wp.bootstrap.admin.email=admin@example.com",
            "server.shutdown=immediate"
        })
class BackendAcceptanceTest {

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String EDITOR_PASSWORD = "editor-password";

    private static final String ENGLISH_LABEL = "Turnover pallet";

    private static final String SUBCLASS = "周转托盘";

    private static final String PALLET = "http://example.org/wp-test/warehouse#Pallet";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String LANG_STRING = "http://www.w3.org/1999/02/22-rdf-syntax-ns#langString";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String API_KEY_HEADER = "X-API-Key";

    private final JsonMapper json = JsonMapper.builder().build();

    private final List<AutoCloseable> open = new ArrayList<>();

    @LocalServerPort
    private int port;

    @AfterEach
    void closeClients() throws Exception {
        for (AutoCloseable client : open) {
            client.close();
        }
    }

    @Test
    void editor_completes_upload_edit_revert_and_trash() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);
        String editorId = accountId("editor");
        admin.json(
                HttpMethod.POST,
                "/api/admin/users",
                201,
                Map.of(
                        "userId",
                        editorId,
                        "displayName",
                        "Editor B",
                        "email",
                        editorId + "@example.com",
                        "initialPassword",
                        EDITOR_PASSWORD));

        String projectId = uploadWarehouse(admin);
        // Empty-language labels stay indexed; en is what makes "Turnover pallet" searchable.
        admin.json(HttpMethod.PUT, "/api/projects/" + projectId + "/settings/display-names", 200, displayNames());
        admin.json(
                HttpMethod.PUT, "/api/projects/" + projectId + "/members/" + editorId, 200, Map.of("role", "CAN_EDIT"));

        Api editor = session();
        editor.login(editorId, EDITOR_PASSWORD);
        JsonNode issued = editor.json(HttpMethod.POST, "/api/me/api-keys", 201, Map.of("name", "acceptance"));
        String apiKey = issued.path("apiKey").asString();
        assertThat(apiKey).isNotBlank();

        long head = editor.get("/api/projects/" + projectId + "/revisions", Map.of("page", "0", "size", "1"))
                .path("revisions")
                .get(0)
                .path("revNo")
                .asLong();
        Api subscriber = apiKey(apiKey);
        String iri;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch sent = new CountDownLatch(1);
            subscriber.onSend(sent);
            Future<JsonNode> pending = pool.submit(() -> subscriber.get(
                    "/api/projects/" + projectId + "/events/poll",
                    Map.of("after", Long.toString(head), "timeout", "25")));
            assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(400);
            JsonNode created = editor.json(
                    HttpMethod.POST,
                    "/api/projects/" + projectId + "/entities",
                    201,
                    Map.of("entityType", "CLASS", "names", List.of(SUBCLASS), "parents", List.of(PALLET)));
            iri = created.path("entities").get(0).path("iri").asString();
            assertThat(iri).startsWith("http://");
            JsonNode events = pending.get(20, TimeUnit.SECONDS);
            assertThat(types(events)).contains("ProjectChanged", "EntityHierarchyChanged");
            assertThat(events.toString()).contains(iri);
        }

        JsonNode children =
                editor.get("/api/projects/" + projectId + "/hierarchies/class/children", Map.of("parent", PALLET));
        assertThat(names(children)).contains(SUBCLASS);

        JsonNode updated = editor.json(
                HttpMethod.PUT, "/api/projects/" + projectId + "/frames/class", 200, frameEdit(editor, projectId, iri));
        assertThat(literals(updated.path("frame"))).contains(ENGLISH_LABEL);

        JsonNode hits = editor.get("/api/projects/" + projectId + "/search", Map.of("q", "pallet", "limit", "20"));
        assertThat(iris(hits)).contains(iri);

        JsonNode history = editor.get("/api/projects/" + projectId + "/revisions", Map.of("page", "0", "size", "20"));
        assertThat(descriptions(history.path("revisions")))
                .contains("Created class " + SUBCLASS, "Edited class " + SUBCLASS);
        assertThat(history.path("revisions"))
                .filteredOn(node -> node.path("description").asString().startsWith("Edited class"))
                .allSatisfy(node -> assertThat(node.path("userId").asString()).isEqualTo(editorId));
        long edited = history.path("revisions").get(0).path("revNo").asLong();

        JsonNode reverted = editor.json(
                HttpMethod.POST, "/api/projects/" + projectId + "/revisions/" + edited + "/revert", 200, Map.of());
        assertThat(reverted.path("revision").asLong()).isEqualTo(edited + 1);
        JsonNode after = editor.get("/api/projects/" + projectId + "/revisions", Map.of("page", "0", "size", "5"));
        assertThat(after.path("revisions").get(0).path("description").asString())
                .isEqualTo("Reverted revision " + edited);

        byte[] turtle = editor.download("/api/projects/" + projectId + "/download", Map.of("format", "ttl"));
        assertThat(editor.lastHeaders().getFirst(HttpHeaders.CONTENT_TYPE)).contains("text/turtle");
        assertThat(labels(turtle)).contains(SUBCLASS, "托盘", "堆垛机").doesNotContain(ENGLISH_LABEL);
        JsonNode afterRollback =
                editor.get("/api/projects/" + projectId + "/search", Map.of("q", "pallet", "limit", "20"));
        assertThat(iris(afterRollback)).doesNotContain(iri);

        JsonNode trashed = admin.json(HttpMethod.POST, "/api/projects/" + projectId + "/trash", 200, Map.of());
        assertThat(trashed.path("inTrash").asBoolean()).isTrue();
        assertThat(projectIds(admin.get("/api/projects", Map.of()))).doesNotContain(projectId);
        assertThat(projectIds(admin.get("/api/projects/trash", Map.of()))).contains(projectId);
    }

    @Test
    void mobile_client_long_polls_then_rotates_the_refresh_token() throws Exception {
        Api admin = session();
        admin.login("admin", ADMIN_PASSWORD);
        String editorId = accountId("mobile");
        admin.json(
                HttpMethod.POST,
                "/api/admin/users",
                201,
                Map.of(
                        "userId",
                        editorId,
                        "displayName",
                        "Editor B",
                        "email",
                        editorId + "@example.com",
                        "initialPassword",
                        EDITOR_PASSWORD));
        JsonNode project = admin.json(
                HttpMethod.POST, "/api/projects", 201, Map.of("name", "Mobile", "description", "acceptance"));
        String projectId = project.path("projectId").asString();
        admin.json(
                HttpMethod.PUT, "/api/projects/" + projectId + "/members/" + editorId, 200, Map.of("role", "CAN_VIEW"));

        Api anonymous = bearer();
        JsonNode tokens = anonymous.json(
                HttpMethod.POST,
                "/api/auth/tokens",
                200,
                Map.of("username", editorId, "password", EDITOR_PASSWORD, "deviceName", "phone"));
        String accessToken = tokens.path("accessToken").asString();
        String refreshToken = tokens.path("refreshToken").asString();
        assertThat(accessToken).isNotBlank();
        assertThat(refreshToken).isNotEqualTo(accessToken);

        Api phone = bearer(accessToken);
        JsonNode roots = phone.get("/api/projects/" + projectId + "/hierarchies/class/roots", Map.of());
        assertThat(roots.isArray()).isTrue();
        assertThat(iris(roots)).contains(THING);

        long head = phone.get("/api/projects/" + projectId + "/revisions", Map.of("page", "0", "size", "1"))
                .path("revisions")
                .get(0)
                .path("revNo")
                .asLong();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            CountDownLatch sent = new CountDownLatch(1);
            phone.onSend(sent);
            Future<JsonNode> pending = pool.submit(() -> phone.get(
                    "/api/projects/" + projectId + "/events/poll",
                    Map.of("after", Long.toString(head), "timeout", "25")));
            assertThat(sent.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(400);
            JsonNode created = admin.json(
                    HttpMethod.POST,
                    "/api/projects/" + projectId + "/entities",
                    201,
                    Map.of("entityType", "CLASS", "names", List.of("MobileBin"), "parents", List.of(THING)));
            String iri = created.path("entities").get(0).path("iri").asString();
            JsonNode polled = pending.get(20, TimeUnit.SECONDS);
            assertThat(polled.isArray()).isTrue();
            assertThat(polled.toString()).contains(iri);
            assertThat(types(polled)).contains("ProjectChanged", "EntityHierarchyChanged");
        }

        JsonNode rotated =
                anonymous.json(HttpMethod.POST, "/api/auth/tokens/refresh", 200, Map.of("refreshToken", refreshToken));
        String nextAccess = rotated.path("accessToken").asString();
        assertThat(nextAccess).isNotEqualTo(accessToken);
        assertThat(rotated.path("refreshToken").asString()).isNotEqualTo(refreshToken);
        JsonNode me = bearer(nextAccess).get("/api/auth/me", Map.of());
        assertThat(me.path("userId").asString()).isEqualTo(editorId);

        HttpResult reused = anonymous.send(
                HttpMethod.POST, "/api/auth/tokens/refresh", Map.of("refreshToken", refreshToken), false);
        assertThat(reused.status()).isEqualTo(401);
        assertThat(json.readTree(reused.text()).path("code").asString()).isEqualTo("REFRESH_TOKEN_REUSED");
    }

    private String uploadWarehouse(Api admin) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("name", "Warehouse");
        parts.add("description", "M3 acceptance");
        parts.add("file", new FileSystemResource(corpus("warehouse-sample.ttl")));
        HttpResult uploaded = admin.send(HttpMethod.POST, "/api/projects/upload", parts, true);
        assertThat(uploaded.status()).as(uploaded.text()).isEqualTo(201);
        return json.readTree(uploaded.text()).path("projectId").asString();
    }

    private ObjectNode frameEdit(Api editor, String projectId, String iri) {
        JsonNode from = editor.get("/api/projects/" + projectId + "/frames/class", Map.of("iri", iri));
        ObjectNode to = (ObjectNode) json.readTree(json.writeValueAsString(from));
        ObjectNode english = (ObjectNode) labelAnnotation(from).deepCopy();
        english.put("lang", "en");
        ObjectNode value = (ObjectNode) english.get("value");
        value.put("lexical", ENGLISH_LABEL);
        value.put("lang", "en");
        value.put("datatype", LANG_STRING);
        ((ArrayNode) to.get("annotations")).add(english);
        ObjectNode body = json.createObjectNode();
        body.set("from", from);
        body.set("to", to);
        return body;
    }

    private JsonNode labelAnnotation(JsonNode frame) {
        for (JsonNode annotation : frame.path("annotations")) {
            if (LABEL.equals(annotation.path("property").path("iri").asString())) {
                return annotation;
            }
        }
        throw new AssertionError("class frame has no rdfs:label: " + frame);
    }

    private ArrayNode displayNames() {
        ArrayNode names = json.createArrayNode();
        for (String lang : List.of("", "zh", "en")) {
            ObjectNode item = names.addObject();
            item.put("annotationProperty", LABEL);
            item.put("lang", lang);
        }
        return names;
    }

    private static List<String> labels(byte[] turtle) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new ByteArrayInputStream(turtle));
        List<String> values = new ArrayList<>();
        for (OWLAxiom axiom : ontology.getAxioms(AxiomType.ANNOTATION_ASSERTION)) {
            if (axiom instanceof OWLAnnotationAssertionAxiom assertion
                    && assertion.getProperty().isLabel()
                    && assertion.getValue() instanceof OWLLiteral literal) {
                values.add(literal.getLiteral());
            }
        }
        return values;
    }

    private static List<String> names(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<String> iris(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("iri").asString()));
        return values;
    }

    private static List<String> projectIds(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("projectId").asString()));
        return values;
    }

    private static List<String> descriptions(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("description").asString()));
        return values;
    }

    private static List<String> literals(JsonNode frame) {
        List<String> values = new ArrayList<>();
        frame.path("annotations")
                .forEach(node -> values.add(node.path("value").path("lexical").asString()));
        return values;
    }

    private static List<String> types(JsonNode events) {
        List<String> values = new ArrayList<>();
        events.forEach(node -> values.add(node.path("type").asString()));
        return values;
    }

    private static String accountId(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    private Api session() {
        Api api = new Api(true);
        open.add(api);
        return api;
    }

    private Api bearer() {
        return bearer(null);
    }

    private Api bearer(String accessToken) {
        Api api = new Api(false);
        api.accessToken = accessToken;
        open.add(api);
        return api;
    }

    private Api apiKey(String key) {
        Api api = new Api(false);
        api.apiKey = key;
        open.add(api);
        return api;
    }

    private final class Api implements AutoCloseable, ClientHttpRequestInterceptor {

        private final boolean cookiesEnabled;

        private final HttpClient http;

        private final RestClient rest;

        private final Map<String, String> cookies = new LinkedHashMap<>();

        private final AtomicReference<CountDownLatch> sent = new AtomicReference<>();

        private volatile String accessToken;

        private volatile String apiKey;

        private volatile HttpHeaders lastHeaders = new HttpHeaders();

        private Api(boolean cookiesEnabled) {
            this.cookiesEnabled = cookiesEnabled;
            this.http = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
            // Compression wraps the body and waits for the first byte. An SSE response has no body
            // byte until the next event, so the subscribe call would never observe the headers.
            factory.enableCompression(false);
            factory.setReadTimeout(Duration.ofSeconds(40));
            this.rest = RestClient.builder()
                    .baseUrl("http://127.0.0.1:" + port)
                    .requestFactory(factory)
                    .requestInterceptor(this)
                    .build();
        }

        private void login(String username, String password) {
            send(HttpMethod.GET, "/api/auth/me", null, false);
            HttpResult logged =
                    send(HttpMethod.POST, "/api/auth/login", Map.of("username", username, "password", password), true);
            assertThat(logged.status()).as(logged.text()).isEqualTo(200);
        }

        private JsonNode json(HttpMethod method, String path, int status, Object body) {
            HttpResult result = send(method, path, body, true);
            assertThat(result.status()).as(result.text()).isEqualTo(status);
            if (result.body().length == 0) {
                return json.nullNode();
            }
            return json.readTree(result.text());
        }

        private JsonNode get(String path, Map<String, String> query) {
            HttpResult result = exchange(HttpMethod.GET, path, query, null, false);
            assertThat(result.status()).as(result.text()).isEqualTo(200);
            return json.readTree(result.text());
        }

        private byte[] download(String path, Map<String, String> query) {
            HttpResult result = exchange(HttpMethod.GET, path, query, null, false);
            assertThat(result.status()).as(result.text()).isEqualTo(200);
            return result.body();
        }

        private HttpHeaders lastHeaders() {
            return lastHeaders;
        }

        private void onSend(CountDownLatch latch) {
            sent.set(latch);
        }

        private HttpResult send(HttpMethod method, String path, Object body, boolean csrf) {
            return exchange(method, path, Map.of(), body, csrf);
        }

        private HttpResult exchange(
                HttpMethod method, String path, Map<String, String> query, Object body, boolean csrf) {
            RestClient.RequestBodySpec spec = rest.method(method).uri(builder -> {
                builder.path(path);
                query.forEach(builder::queryParam);
                return builder.build();
            });
            if (accessToken != null) {
                spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken);
            }
            if (apiKey != null) {
                spec.header(API_KEY_HEADER, apiKey);
            }
            if (csrf && cookiesEnabled) {
                spec.header(CSRF_HEADER, csrf());
            }
            if (body instanceof MultiValueMap<?, ?> parts) {
                spec.body(parts);
            } else if (body != null && !(body instanceof Map<?, ?> map && map.isEmpty() && method == HttpMethod.POST)) {
                spec.contentType(MediaType.APPLICATION_JSON).body(json.writeValueAsString(body));
            }
            return spec.exchange((request, response) -> {
                byte[] bytes = response.getBody().readAllBytes();
                lastHeaders = response.getHeaders();
                return new HttpResult(response.getStatusCode().value(), lastHeaders, bytes);
            });
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
                throws IOException {
            if (cookiesEnabled && !cookies.isEmpty()) {
                request.getHeaders().set(HttpHeaders.COOKIE, cookieHeader());
            }
            CountDownLatch latch = sent.getAndSet(null);
            if (latch != null) {
                latch.countDown();
            }
            ClientHttpResponse response = execution.execute(request, body);
            if (cookiesEnabled) {
                store(response.getHeaders());
            }
            return response;
        }

        private void store(HttpHeaders headers) {
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
                    .orElseThrow();
        }

        private String csrf() {
            String token = cookies.get("XSRF-TOKEN");
            assertThat(token).isNotBlank();
            return token;
        }

        @Override
        public void close() {
            http.close();
        }
    }

    private record HttpResult(int status, HttpHeaders headers, byte[] body) {

        private String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
