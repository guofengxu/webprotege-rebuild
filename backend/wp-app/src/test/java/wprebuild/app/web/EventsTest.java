package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.RoleAssignmentsChanged;
import wprebuild.app.access.UserService;
import wprebuild.app.events.EventsProperties;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.ontology.RevisionCommittedEvent;
import wprebuild.app.support.TestcontainersConfiguration;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;

/**
 * High-level project events over SSE and long poll (BS-11, P3-21).
 *
 * <p>The ring is shrunk to two revisions so a resync does not need two hundred commits. The
 * production default stays 200.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(
        properties = {
            "wp.data-dir=${java.io.tmpdir}/webprotege-rebuild-test",
            "wp.events.buffer-size=2",
            "server.shutdown=immediate"
        })
@TestConstructor(autowireMode = AutowireMode.ALL)
class EventsTest {

    private static final String PASSWORD = "correct-password";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @LocalServerPort
    private int port;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectEventHub hub;

    private final ApplicationEventPublisher events;

    private final List<AutoCloseable> open = new ArrayList<>();

    EventsTest(
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectEventHub hub,
            ApplicationEventPublisher events) {
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.hub = hub;
        this.events = events;
    }

    @AfterEach
    void closeClients() throws Exception {
        for (AutoCloseable client : open) {
            client.close();
        }
    }

    @Test
    void default_buffer_is_two_hundred_revisions() {
        assertThat(EventsProperties.DEFAULT_BUFFER_SIZE).isEqualTo(200);
        boolean found = false;
        for (Constructor<?> constructor : EventsProperties.class.getDeclaredConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                DefaultValue value = parameter.getAnnotation(DefaultValue.class);
                if (value != null && "bufferSize".equals(parameter.getName())) {
                    assertThat(value.value()).containsExactly("200");
                    found = true;
                }
            }
        }
        assertThat(found).isTrue();
    }

    @Test
    void subscribed_create_class_receives_project_hierarchy_and_browser_text() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        try (SseStream stream = owner.events(projectId, null)) {
            Created created = createClass(owner, projectId, "Pallet");
            List<SseFrame> frames = stream.collectUntilQuiet(Duration.ofSeconds(8), Duration.ofMillis(400));
            assertThat(frames)
                    .extracting(SseFrame::event)
                    .startsWith("ProjectChanged", "EntityHierarchyChanged", "BrowserTextChanged");
            assertThat(frames).extracting(SseFrame::event).contains("EntityFrameChanged");
            assertThat(frames).allSatisfy(frame -> assertThat(frame.id()).isEqualTo(Long.toString(created.revision())));

            JsonNode project = json.readTree(frames.get(0).data());
            assertThat(project.path("revNo").asLong()).isEqualTo(created.revision());
            assertThat(project.path("user").asString()).isEqualTo(owner.userId);
            assertThat(project.path("changeCount").asInt()).isPositive();
            assertThat(project.path("timestamp").asString()).isNotBlank();

            JsonNode hierarchy = json.readTree(frames.get(1).data());
            assertThat(hierarchy.path("hierarchy").asString()).isEqualTo("class");
            List<String> children = new ArrayList<>();
            hierarchy
                    .path("added")
                    .forEach(edge -> children.add(edge.path("child").asString()));
            assertThat(children).contains(created.iri());

            JsonNode browser = json.readTree(frames.get(2).data());
            assertThat(browser.path("entity").path("iri").asString()).isEqualTo(created.iri());
            assertThat(browser.path("displayName").asString()).isEqualTo("Pallet");
        }
    }

    @Test
    void last_event_id_replays_events_after_that_revision() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        Created created = createClass(owner, projectId, "Bin");

        try (SseStream stream = owner.events(projectId, "1")) {
            List<SseFrame> frames = stream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(300));
            assertThat(frames).isNotEmpty();
            assertThat(frames)
                    .extracting(SseFrame::event)
                    .startsWith("ProjectChanged", "EntityHierarchyChanged", "BrowserTextChanged");
            assertThat(frames).allSatisfy(frame -> assertThat(frame.id()).isEqualTo(Long.toString(created.revision())));
        }
    }

    @Test
    void cursor_outside_the_buffer_receives_resync() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        createClass(owner, projectId, "A");
        createClass(owner, projectId, "B");

        try (SseStream stream = owner.events(projectId, "0")) {
            SseFrame frame = stream.await(Duration.ofSeconds(5));
            assertThat(frame.event()).isEqualTo("resync");
            assertThat(json.readTree(frame.data()).path("type").asString()).isEqualTo("resync");
        }

        HttpResponse<String> poll = owner.get("/api/projects/" + projectId + "/events/poll?after=0&timeout=1");
        assertThat(poll.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(poll.body());
        assertThat(body).hasSize(1);
        assertThat(body.get(0).path("type").asString()).isEqualTo("resync");
    }

    @Test
    void user_without_a_project_role_is_forbidden() throws Exception {
        Api owner = owner();
        Api stranger = account("stranger");
        ProjectId projectId = createProject(owner);

        HttpResponse<String> stream = stranger.get("/api/projects/" + projectId + "/events");
        HttpResponse<String> poll = stranger.get("/api/projects/" + projectId + "/events/poll?after=0&timeout=0");
        assertThat(stream.statusCode()).isEqualTo(403);
        assertThat(poll.statusCode()).isEqualTo(403);
        assertThat(json.readTree(stream.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(json.readTree(poll.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void viewer_can_long_poll() throws Exception {
        Api owner = owner();
        Api viewer = account("viewer");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId(viewer.userId), ProjectRole.CAN_VIEW);

        HttpResponse<String> poll = viewer.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=0");
        assertThat(poll.statusCode()).as(poll.body()).isEqualTo(200);
        assertThat(json.readTree(poll.body())).isEmpty();
    }

    @Test
    void long_poll_returns_empty_array_after_timeout() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        long started = System.nanoTime();
        HttpResponse<String> poll = owner.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=1");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(poll.statusCode()).as(poll.body()).isEqualTo(200);
        assertThat(json.readTree(poll.body())).isEmpty();
        assertThat(elapsed).isGreaterThanOrEqualTo(Duration.ofMillis(700));
        assertThat(elapsed).isLessThan(Duration.ofSeconds(15));
    }

    @Test
    void long_poll_returns_the_revision_committed_while_waiting() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<HttpResponse<String>> pending =
                    pool.submit(() -> owner.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=25"));
            awaitParked(projectId);
            long started = System.nanoTime();
            Created created = createClass(owner, projectId, "Carton");
            HttpResponse<String> poll = pending.get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(poll.statusCode()).as(poll.body()).isEqualTo(200);
            List<String> types = new ArrayList<>();
            json.readTree(poll.body())
                    .forEach(node -> types.add(node.path("type").asString()));
            assertThat(types).startsWith("ProjectChanged", "EntityHierarchyChanged", "BrowserTextChanged");
            assertThat(json.readTree(poll.body()).get(0).path("revNo").asLong()).isEqualTo(created.revision());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void silent_revision_emits_only_project_changed() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        createClass(owner, projectId, "Tray");
        try (SseStream stream = owner.events(projectId, null)) {
            events.publishEvent(new RevisionCommittedEvent(
                    projectId,
                    new Revision(
                            RevisionNumber.of(40),
                            new UserId(owner.userId),
                            Instant.parse("2026-11-10T00:00:00Z"),
                            "silent",
                            List.of()),
                    true));
            List<SseFrame> frames = stream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(400));
            assertThat(frames).extracting(SseFrame::event).containsExactly("ProjectChanged");
            JsonNode body = json.readTree(frames.get(0).data());
            assertThat(body.path("description").asString()).isEqualTo("silent");
            assertThat(body.path("changeCount").asInt()).isZero();
            assertThat(body.path("revNo").asLong()).isEqualTo(40);
        }
    }

    @Test
    void role_assignment_emits_permissions_changed() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        try (SseStream stream = owner.events(projectId, null)) {
            events.publishEvent(new RoleAssignmentsChanged(projectId));
            List<SseFrame> frames = stream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(300));
            assertThat(frames).extracting(SseFrame::event).containsExactly("PermissionsChanged");
            JsonNode body = json.readTree(frames.get(0).data());
            assertThat(body.path("projectId").asString()).isEqualTo(projectId.toString());
            assertThat(body.path("revNo").asLong()).isEqualTo(1);
        }
    }

    @Test
    void long_poll_receives_permissions_changed_while_waiting() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<HttpResponse<String>> pending =
                    pool.submit(() -> owner.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=25"));
            awaitParked(projectId);
            events.publishEvent(new RoleAssignmentsChanged(projectId));
            HttpResponse<String> poll = pending.get(10, TimeUnit.SECONDS);
            assertThat(poll.statusCode()).as(poll.body()).isEqualTo(200);
            JsonNode body = json.readTree(poll.body());
            assertThat(body).isNotEmpty();
            assertThat(body.get(0).path("type").asString()).isEqualTo("PermissionsChanged");
            assertThat(body.get(0).path("projectId").asString()).isEqualTo(projectId.toString());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void negative_cursor_and_bad_last_event_id_are_rejected() throws Exception {
        Api owner = owner();
        ProjectId projectId = createProject(owner);
        HttpResponse<String> after = owner.get("/api/projects/" + projectId + "/events/poll?after=-1&timeout=1");
        HttpResponse<String> timeout = owner.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=-1");
        HttpResponse<String> header = owner.get("/api/projects/" + projectId + "/events", "Last-Event-ID", "nope");
        assertThat(after.statusCode()).isEqualTo(400);
        assertThat(timeout.statusCode()).isEqualTo(400);
        assertThat(header.statusCode()).isEqualTo(400);
        assertThat(json.readTree(after.body()).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(json.readTree(header.body()).path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private void awaitParked(ProjectId projectId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (hub.parkedPolls(projectId) > 0) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("long poll was not parked");
    }

    private Api owner() throws Exception {
        return account("owner", "PROJECT_CREATOR");
    }

    private Api account(String role, String... appRoles) throws Exception {
        String userId = "p321-" + role + "-" + UUID.randomUUID().toString().substring(0, 8);
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
        Api api = new Api(userId);
        open.add(api);
        api.login();
        return api;
    }

    private ProjectId createProject(Api owner) throws Exception {
        HttpResponse<String> created = owner.post("/api/projects", Map.of("name", "Warehouse", "description", "Stock"));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return ProjectId.of(json.readTree(created.body()).path("projectId").asString());
    }

    private Created createClass(Api owner, ProjectId projectId, String name) throws Exception {
        HttpResponse<String> created = owner.post(
                "/api/projects/" + projectId + "/entities",
                Map.of("entityType", "CLASS", "names", List.of(name), "parents", List.of(THING)));
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode body = json.readTree(created.body());
        return new Created(
                body.path("entities").get(0).path("iri").asString(),
                body.path("revision").asLong());
    }

    private final class Api implements AutoCloseable {

        private final String userId;

        private final HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        private final Map<String, String> cookies = new LinkedHashMap<>();

        private Api(String userId) {
            this.userId = userId;
        }

        private void login() throws Exception {
            send(request("/api/auth/me").GET());
            HttpResponse<String> logged = post("/api/auth/login", Map.of("username", userId, "password", PASSWORD));
            assertThat(logged.statusCode()).as(logged.body()).isEqualTo(200);
        }

        private HttpResponse<String> get(String path, String... header) throws Exception {
            HttpRequest.Builder builder = request(path).GET().timeout(Duration.ofSeconds(20));
            if (header.length == 2) {
                builder.header(header[0], header[1]);
            }
            return send(builder);
        }

        private HttpResponse<String> post(String path, Object body) throws Exception {
            return send(request(path)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header(CSRF_HEADER, csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        }

        private SseStream events(ProjectId projectId, String lastEventId) throws Exception {
            HttpRequest.Builder builder = request("/api/projects/" + projectId + "/events")
                    .header("Accept", "text/event-stream")
                    .GET();
            if (lastEventId != null) {
                builder.header("Last-Event-ID", lastEventId);
            }
            HttpResponse<InputStream> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
            store(response);
            if (response.statusCode() != 200) {
                String body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                response.body().close();
                throw new AssertionError("sse status " + response.statusCode() + " " + body);
            }
            SseStream stream = new SseStream(response.body());
            open.add(stream);
            return stream;
        }

        private HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            store(response);
            return response;
        }

        private HttpRequest.Builder request(String path) {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (!cookies.isEmpty()) {
                builder.header(
                        "Cookie",
                        cookies.entrySet().stream()
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .reduce((left, right) -> left + "; " + right)
                                .orElseThrow());
            }
            return builder;
        }

        private void store(HttpResponse<?> response) {
            for (String header : response.headers().allValues("set-cookie")) {
                String pair = header.split(";", 2)[0];
                int eq = pair.indexOf('=');
                if (eq > 0) {
                    cookies.put(
                            pair.substring(0, eq).strip(),
                            pair.substring(eq + 1).strip());
                }
            }
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

    private static final class SseStream implements AutoCloseable {

        private final InputStream input;

        private final BlockingQueue<SseFrame> frames = new LinkedBlockingQueue<>();

        private final Thread reader;

        private final AtomicBoolean closed = new AtomicBoolean();

        private SseStream(InputStream input) {
            this.input = input;
            this.reader = Thread.ofVirtual().start(this::read);
        }

        private SseFrame await(Duration timeout) throws InterruptedException {
            SseFrame frame = frames.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(frame).isNotNull();
            return frame;
        }

        private List<SseFrame> collectUntilQuiet(Duration first, Duration quiet) throws InterruptedException {
            List<SseFrame> collected = new ArrayList<>();
            SseFrame frame = frames.poll(first.toMillis(), TimeUnit.MILLISECONDS);
            if (frame == null) {
                return collected;
            }
            collected.add(frame);
            while (true) {
                SseFrame next = frames.poll(quiet.toMillis(), TimeUnit.MILLISECONDS);
                if (next == null) {
                    return collected;
                }
                collected.add(next);
            }
        }

        private void read() {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String id = null;
                String event = null;
                StringBuilder data = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (event != null || !data.isEmpty()) {
                            frames.add(new SseFrame(id, event, data.toString()));
                        }
                        id = null;
                        event = null;
                        data.setLength(0);
                        continue;
                    }
                    if (line.startsWith(":")) {
                        continue;
                    }
                    int colon = line.indexOf(':');
                    if (colon < 0) {
                        continue;
                    }
                    String field = line.substring(0, colon);
                    String value = line.substring(colon + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    switch (field) {
                        case "id" -> id = value;
                        case "event" -> event = value;
                        case "data" -> {
                            if (!data.isEmpty()) {
                                data.append('\n');
                            }
                            data.append(value);
                        }
                        default -> {
                            // retry and unknown fields are not part of the event payload
                        }
                    }
                }
            } catch (IOException ex) {
                // The test closes the stream after it has the frames it needs.
            }
        }

        @Override
        public void close() throws InterruptedException {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                input.close();
            } catch (IOException ex) {
                // Closing the socket unblocks the reader.
            }
            reader.join(2_000);
        }
    }

    private record SseFrame(String id, String event, String data) {}

    private record Created(String iri, long revision) {}
}
