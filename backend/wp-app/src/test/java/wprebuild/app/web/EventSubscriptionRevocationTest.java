package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.TestConstructor.AutowireMode;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.support.TestcontainersConfiguration;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Closes SSE and long-poll subscriptions when view access is lost or a project is trashed (P6-03).
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
class EventSubscriptionRevocationTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @LocalServerPort
    private int port;

    private final JsonMapper json;

    private final UserService users;

    private final AccessService access;

    private final ProjectEventHub hub;

    private final ProjectEventsController eventsController;

    private final List<AutoCloseable> open = new ArrayList<>();

    EventSubscriptionRevocationTest(
            JsonMapper json,
            UserService users,
            AccessService access,
            ProjectEventHub hub,
            ProjectEventsController eventsController) {
        this.json = json;
        this.users = users;
        this.access = access;
        this.hub = hub;
        this.eventsController = eventsController;
    }

    @AfterEach
    void tearDown() throws Exception {
        eventsController.afterInitialAccessCheck = null;
        for (AutoCloseable client : open) {
            client.close();
        }
        open.clear();
    }

    @Test
    void removing_a_viewer_closes_their_sse_and_blocks_reconnect() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId projectId = createProject(manager);
        grant(manager, projectId, viewer, ProjectRole.CAN_VIEW);

        try (SseStream managerStream = manager.events(projectId);
                SseStream viewerStream = viewer.events(projectId)) {
            removeMember(manager, projectId, viewer);

            List<SseFrame> managerFrames =
                    managerStream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(400));
            assertThat(managerFrames).extracting(SseFrame::event).contains("PermissionsChanged");
            assertThat(managerStream.endedWithin(Duration.ofSeconds(1))).isFalse();

            List<SseFrame> viewerFrames = viewerStream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(300));
            assertThat(viewerFrames).extracting(SseFrame::event).contains("PermissionsChanged");
            assertThat(viewerStream.awaitEnded(Duration.ofSeconds(5))).isTrue();

            HttpResponse<String> reconnect = viewer.get("/api/projects/" + projectId + "/events");
            assertThat(reconnect.statusCode()).isEqualTo(403);
            assertThat(json.readTree(reconnect.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        }
    }

    @Test
    void promoting_a_viewer_to_editor_keeps_their_sse_open() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId projectId = createProject(manager);
        grant(manager, projectId, viewer, ProjectRole.CAN_VIEW);

        try (SseStream viewerStream = viewer.events(projectId)) {
            putRole(manager, projectId, viewer, ProjectRole.CAN_EDIT);
            List<SseFrame> frames = viewerStream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(400));
            assertThat(frames).extracting(SseFrame::event).contains("PermissionsChanged");
            assertThat(viewerStream.endedWithin(Duration.ofSeconds(1))).isFalse();
            assertThat(hub.subscribers(projectId)).contains(new UserId(viewer.userId));
        }
    }

    @Test
    void removing_a_viewer_completes_a_parked_poll_and_forbids_the_next() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId projectId = createProject(manager);
        grant(manager, projectId, viewer, ProjectRole.CAN_VIEW);

        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<HttpResponse<String>> pending =
                    pool.submit(() -> viewer.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=25"));
            awaitParked(projectId);
            long started = System.nanoTime();
            removeMember(manager, projectId, viewer);
            HttpResponse<String> poll = pending.get(10, TimeUnit.SECONDS);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
            assertThat(poll.statusCode()).as(poll.body()).isEqualTo(200);
            JsonNode body = json.readTree(poll.body());
            if (!body.isEmpty()) {
                assertThat(body.get(0).path("type").asString()).isEqualTo("PermissionsChanged");
            }

            HttpResponse<String> again = viewer.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=0");
            assertThat(again.statusCode()).isEqualTo(403);
            assertThat(json.readTree(again.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void revoke_on_one_project_leaves_another_subscription_open() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId first = createProject(manager);
        ProjectId second = createProject(manager);
        grant(manager, first, viewer, ProjectRole.CAN_VIEW);
        grant(manager, second, viewer, ProjectRole.CAN_VIEW);

        try (SseStream firstStream = viewer.events(first);
                SseStream secondStream = viewer.events(second)) {
            removeMember(manager, first, viewer);
            assertThat(firstStream.awaitEnded(Duration.ofSeconds(5))).isTrue();
            assertThat(secondStream.endedWithin(Duration.ofSeconds(1))).isFalse();
            assertThat(hub.subscribers(second)).contains(new UserId(viewer.userId));
            assertThat(hub.subscribers(first)).doesNotContain(new UserId(viewer.userId));
        }
    }

    @Test
    void trashing_a_project_sends_resync_then_closes_all_subscribers() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId projectId = createProject(manager);
        grant(manager, projectId, viewer, ProjectRole.CAN_VIEW);

        try (SseStream managerStream = manager.events(projectId);
                SseStream viewerStream = viewer.events(projectId)) {
            HttpResponse<String> trashed = manager.post("/api/projects/" + projectId + "/trash", Map.of());
            assertThat(trashed.statusCode()).as(trashed.body()).isEqualTo(200);

            List<SseFrame> managerFrames =
                    managerStream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(300));
            List<SseFrame> viewerFrames = viewerStream.collectUntilQuiet(Duration.ofSeconds(5), Duration.ofMillis(300));
            assertThat(managerFrames).extracting(SseFrame::event).contains("resync");
            assertThat(viewerFrames).extracting(SseFrame::event).contains("resync");
            assertThat(managerStream.awaitEnded(Duration.ofSeconds(5))).isTrue();
            assertThat(viewerStream.awaitEnded(Duration.ofSeconds(5))).isTrue();

            HttpResponse<String> stream = manager.get("/api/projects/" + projectId + "/events");
            HttpResponse<String> poll = manager.get("/api/projects/" + projectId + "/events/poll?after=1&timeout=0");
            assertThat(stream.statusCode()).isEqualTo(409);
            assertThat(poll.statusCode()).isEqualTo(409);
            assertThat(json.readTree(stream.body()).path("code").asString()).isEqualTo("PROJECT_TRASHED");
            assertThat(json.readTree(poll.body()).path("code").asString()).isEqualTo("PROJECT_TRASHED");

            HttpResponse<String> restored = manager.delete("/api/projects/" + projectId + "/trash");
            assertThat(restored.statusCode()).as(restored.body()).isEqualTo(200);
            try (SseStream again = manager.events(projectId)) {
                assertThat(again.endedWithin(Duration.ofMillis(500))).isFalse();
            }
        }
    }

    @Test
    void second_access_check_rejects_a_subscription_registered_after_revoke() throws Exception {
        Api manager = account("mgr", "PROJECT_CREATOR");
        Api viewer = account("viewer");
        ProjectId projectId = createProject(manager);
        grant(manager, projectId, viewer, ProjectRole.CAN_VIEW);
        UserId viewerId = new UserId(viewer.userId);

        eventsController.afterInitialAccessCheck = () -> access.removeRole(projectId, viewerId);
        try {
            HttpResponse<String> stream = viewer.get("/api/projects/" + projectId + "/events");
            assertThat(stream.statusCode()).isEqualTo(403);
            assertThat(json.readTree(stream.body()).path("code").asString()).isEqualTo("PERMISSION_DENIED");
            assertThat(hub.subscribers(projectId)).doesNotContain(viewerId);
        } finally {
            eventsController.afterInitialAccessCheck = null;
        }
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

    private Api account(String role, String... appRoles) throws Exception {
        String userId = "p603-" + role + "-" + UUID.randomUUID().toString().substring(0, 8);
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

    private void grant(Api manager, ProjectId projectId, Api member, ProjectRole role) throws Exception {
        HttpResponse<String> granted = putRole(manager, projectId, member, role);
        assertThat(granted.statusCode()).as(granted.body()).isEqualTo(200);
    }

    private HttpResponse<String> putRole(Api manager, ProjectId projectId, Api member, ProjectRole role)
            throws Exception {
        return manager.put("/api/projects/" + projectId + "/members/" + member.userId, Map.of("role", role.name()));
    }

    private void removeMember(Api manager, ProjectId projectId, Api member) throws Exception {
        HttpResponse<String> removed = manager.delete("/api/projects/" + projectId + "/members/" + member.userId);
        assertThat(removed.statusCode()).as(removed.body()).isEqualTo(204);
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

        private HttpResponse<String> get(String path) throws Exception {
            return send(request(path).GET().timeout(Duration.ofSeconds(20)));
        }

        private HttpResponse<String> post(String path, Object body) throws Exception {
            String payload = body instanceof Map<?, ?> map && map.isEmpty() ? "{}" : json.writeValueAsString(body);
            return send(request(path)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header(CSRF_HEADER, csrf())
                    .POST(HttpRequest.BodyPublishers.ofString(payload)));
        }

        private HttpResponse<String> put(String path, Object body) throws Exception {
            return send(request(path)
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json")
                    .header(CSRF_HEADER, csrf())
                    .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))));
        }

        private HttpResponse<String> delete(String path) throws Exception {
            return send(request(path)
                    .timeout(Duration.ofSeconds(20))
                    .header(CSRF_HEADER, csrf())
                    .DELETE());
        }

        private SseStream events(ProjectId projectId) throws Exception {
            HttpRequest.Builder builder = request("/api/projects/" + projectId + "/events")
                    .header("Accept", "text/event-stream")
                    .GET();
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

        private final AtomicBoolean ended = new AtomicBoolean();

        private SseStream(InputStream input) {
            this.input = input;
            this.reader = Thread.ofVirtual().start(this::read);
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

        private boolean awaitEnded(Duration timeout) throws InterruptedException {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (ended.get()) {
                    return true;
                }
                Thread.sleep(20);
            }
            return ended.get();
        }

        private boolean endedWithin(Duration timeout) throws InterruptedException {
            return awaitEnded(timeout);
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
                            // ignore
                        }
                    }
                }
            } catch (IOException ex) {
                // Client or server closed the stream.
            } finally {
                ended.set(true);
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
                // Unblocks the reader.
            }
            reader.join(2_000);
        }
    }

    private record SseFrame(String id, String event, String data) {}
}
