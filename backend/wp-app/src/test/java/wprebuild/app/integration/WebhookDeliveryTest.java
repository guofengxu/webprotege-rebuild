package wprebuild.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.revision.OutboxRelay;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

/**
 * End-to-end webhook delivery against a local {@link HttpServer} (P6-21 / BS-15).
 *
 * <p>GateGuard facts: Surefire invokes {@code WebhookDeliveryTest}; Glob found none beforehand;
 * synthetic HttpServer + Testcontainers only; user asked {@code 完成 下一个任务卡}.
 */
class WebhookDeliveryTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String SECRET = "sixteen-chars!!!";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "wp.webhooks.allow-private-targets=true",
                "wp.webhooks.poll-interval=100ms",
                "wp.webhooks.max-attempts=3",
                "wp.webhooks.retry-base=50ms",
                "wp.webhooks.retry-max=200ms",
                "wp.webhooks.lease=16s",
                "wp.webhooks.connect-timeout=1s",
                "wp.webhooks.request-timeout=2s",
                "wp.outbox.enabled=true",
                "wp.outbox.poll-interval=100ms",
                "wp.projection.enabled=true"
            })
    class Delivery {

        private final MockMvc mockMvc;
        private final JsonMapper json;
        private final UserService users;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;
        private final WebhookDispatcher dispatcher;
        private final JdbcWebhookRepository repository;
        private final WebhookUrlPolicy urlPolicy;
        private final PlatformTransactionManager transactions;
        private final String suffix = UUID.randomUUID().toString().substring(0, 8);
        private final List<HttpServer> servers = new ArrayList<>();
        private final List<WebhookDispatcher> extraDispatchers = new ArrayList<>();

        Delivery(
                MockMvc mockMvc,
                JsonMapper json,
                UserService users,
                JdbcClient jdbc,
                OutboxRelay relay,
                WebhookDispatcher dispatcher,
                JdbcWebhookRepository repository,
                WebhookUrlPolicy urlPolicy,
                PlatformTransactionManager transactions) {
            this.mockMvc = mockMvc;
            this.json = json;
            this.users = users;
            this.jdbc = jdbc;
            this.relay = relay;
            this.dispatcher = dispatcher;
            this.repository = repository;
            this.urlPolicy = urlPolicy;
            this.transactions = transactions;
        }

        @AfterEach
        void cleanup() {
            for (WebhookDispatcher extra : extraDispatchers) {
                extra.destroy();
            }
            extraDispatchers.clear();
            // Drop abandoned retries so later tests are not starved under the concurrency cap.
            jdbc.sql("""
                            update webhook_deliveries
                            set status = 'CANCELLED', finished_at = now(), claim_token = null
                            where status = 'PENDING'
                            """).update();
            for (HttpServer server : servers) {
                server.stop(0);
            }
            servers.clear();
        }

        @Test
        void single_edit_delivers_signed_payload_with_headers() throws Exception {
            RecordingHook hook = startHook(ex -> write(ex, 200, "ok"));
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Hooks"));
            createWebhook(owner, projectId, hook.url(), true, SECRET);

            Created created = createClass(owner, projectId, "Pallet");
            relay.wake();
            dispatcher.wake();

            awaitUntil(() -> hook.requests().size() == 1);
            CapturedRequest request = hook.requests().getFirst();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(header(request, "Content-Type")).startsWith("application/json");
            assertThat(header(request, "User-Agent")).isEqualTo("webprotege-rebuild-webhook");
            assertThat(header(request, "X-WP-Event")).isEqualTo("PROJECT_CHANGED");
            assertThat(header(request, "X-WP-Delivery")).isNotBlank();
            assertThat(header(request, "X-WP-Signature-256"))
                    .isEqualTo(WebhookSigner.headerValue(SECRET, request.body()));

            JsonNode body = json.readTree(request.body());
            assertThat(body.propertyNames())
                    .containsExactlyInAnyOrder("projectId", "userId", "revisionNumber", "timestamp");
            assertThat(body.path("projectId").asString()).isEqualTo(projectId.toString());
            assertThat(body.path("userId").asString()).isEqualTo(owner.userId());
            assertThat(body.path("revisionNumber").asLong()).isEqualTo(created.revision());
            assertThat(body.path("timestamp").asLong()).isPositive();
        }

        @Test
        void five_revisions_arrive_in_order_for_same_webhook() throws Exception {
            RecordingHook hook = startHook(ex -> write(ex, 200, "ok"));
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Ordered"));
            createWebhook(owner, projectId, hook.url(), true, null);

            List<Long> revisions = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                revisions.add(createClass(owner, projectId, "C" + i).revision());
                relay.wake();
                dispatcher.wake();
            }
            awaitUntil(() -> hook.requests().size() == 5);

            List<Long> received = new ArrayList<>();
            for (CapturedRequest request : hook.requests()) {
                received.add(
                        json.readTree(request.body()).path("revisionNumber").asLong());
            }
            assertThat(received).containsExactlyElementsOf(revisions);
        }

        @Test
        void retries_until_success_keep_same_delivery_id_then_fails_permanently() throws Exception {
            AtomicInteger failThenOk = new AtomicInteger();
            RecordingHook flaky = startHook(ex -> {
                if (failThenOk.incrementAndGet() <= 2) {
                    write(ex, 500, "boom");
                } else {
                    write(ex, 200, "ok");
                }
            });
            RecordingHook alwaysFail = startHook(ex -> write(ex, 500, "nope"));

            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Retry"));
            String flakyId = createWebhook(owner, projectId, flaky.url(), true, null);
            String failId = createWebhook(owner, projectId, alwaysFail.url(), true, null);

            createClass(owner, projectId, "Bin");
            relay.wake();
            dispatcher.wake();

            awaitUntil(() -> flaky.requests().size() == 3);
            assertThat(flaky.requests().stream()
                            .map(r -> header(r, "X-WP-Delivery"))
                            .distinct())
                    .hasSize(1);
            awaitUntil(() -> status(UUID.fromString(flakyId)).equals("SUCCEEDED"));

            awaitUntil(() -> status(UUID.fromString(failId)).equals("FAILED"));
            assertThat(alwaysFail.requests()).hasSize(3);
            assertThat(alwaysFail.requests().stream()
                            .map(r -> header(r, "X-WP-Delivery"))
                            .distinct())
                    .hasSize(1);
        }

        @Test
        void slow_webhook_does_not_block_other_webhook_or_projection() throws Exception {
            CountDownLatch hold = new CountDownLatch(1);
            RecordingHook slow = startHook(ex -> {
                awaitLatch(hold);
                write(ex, 200, "slow");
            });
            RecordingHook fast = startHook(ex -> write(ex, 200, "fast"));

            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Parallel"));
            createWebhook(owner, projectId, slow.url(), true, null);
            createWebhook(owner, projectId, fast.url(), true, null);

            long beforeProjection = consumerLastId("projection");
            createClass(owner, projectId, "Carton");
            relay.wake();
            dispatcher.wake();

            awaitUntil(() -> fast.requests().size() == 1);
            awaitUntil(() -> consumerLastId("projection") > beforeProjection);
            assertThat(hold.getCount()).isEqualTo(1);
            hold.countDown();
            awaitUntil(() -> slow.requests().size() == 1);
        }

        @Test
        void redirect_is_treated_as_failure() throws Exception {
            RecordingHook hook = startHook(ex -> {
                ex.getResponseHeaders().add("Location", "http://127.0.0.1/elsewhere");
                write(ex, 302, "");
            });
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Redirect"));
            String webhookId = createWebhook(owner, projectId, hook.url(), true, null);
            createClass(owner, projectId, "Tray");
            relay.wake();
            dispatcher.wake();
            awaitUntil(() -> status(UUID.fromString(webhookId)).equals("FAILED"));
            assertThat(hook.requests()).hasSize(3);
        }

        @Test
        void disabled_trashed_and_historical_revisions_are_skipped() throws Exception {
            RecordingHook hook = startHook(ex -> write(ex, 200, "ok"));
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Skip"));
            createClass(owner, projectId, "Before");
            relay.wake();
            awaitUntil(() -> consumerRegistered("webhooks"));

            createWebhook(owner, projectId, hook.url(), false, null);
            createClass(owner, projectId, "WhileDisabled");
            relay.wake();
            dispatcher.wake();
            Thread.sleep(400);
            assertThat(hook.requests()).isEmpty();

            createWebhook(owner, projectId, hook.url(), true, null);
            createClass(owner, projectId, "AfterEnable");
            relay.wake();
            dispatcher.wake();
            awaitUntil(() -> hook.requests().size() == 1);
            assertThat(json.readTree(hook.requests().getFirst().body())
                            .path("revisionNumber")
                            .asLong())
                    .isGreaterThan(1);

            postPath(owner, "/api/projects/" + projectId + "/trash");
            createClassAllowingFailure(owner, projectId, "InTrash");
            int before = hook.requests().size();
            relay.wake();
            dispatcher.wake();
            Thread.sleep(400);
            assertThat(hook.requests()).hasSize(before);
        }

        @Test
        void dual_dispatchers_send_once_and_stale_claim_is_ignored() throws Exception {
            CountDownLatch hold = new CountDownLatch(1);
            RecordingHook hook = startHook(ex -> {
                awaitLatch(hold);
                write(ex, 200, "ok");
            });
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Dual"));
            String webhookId = createWebhook(owner, projectId, hook.url(), true, null);
            createClass(owner, projectId, "One");
            relay.wake();
            dispatcher.wake();
            awaitUntil(() -> hook.requests().size() == 1);

            WebhookProperties shortLease = new WebhookProperties(
                    true,
                    true,
                    10,
                    Duration.ofMillis(50),
                    8,
                    Duration.ofSeconds(16),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(2),
                    3,
                    Duration.ofMillis(50),
                    Duration.ofMillis(200),
                    Duration.ofDays(30));
            WebhookDispatcher second = new WebhookDispatcher(shortLease, repository, urlPolicy, transactions, true);
            extraDispatchers.add(second);
            second.wake();
            Thread.sleep(300);
            assertThat(hook.requests()).hasSize(1);
            hold.countDown();
            awaitUntil(() -> status(UUID.fromString(webhookId)).equals("SUCCEEDED"));
            assertThat(hook.requests()).hasSize(1);

            long deliveryId = jdbc.sql("""
                            select delivery_id from webhook_deliveries where webhook_id = ?
                            """)
                    .param(UUID.fromString(webhookId))
                    .query((rs, row) -> rs.getLong(1))
                    .single();
            UUID stale = UUID.randomUUID();
            // Reset + reclaim in one transaction so the live poller cannot snatch the row.
            Boolean cas = new org.springframework.transaction.support.TransactionTemplate(transactions)
                    .execute(status -> {
                        jdbc.sql("""
                                        update webhook_deliveries
                                        set status = 'PENDING', finished_at = null, claim_token = ?, attempts = 0,
                                            next_attempt_at = now() - interval '1 second'
                                        where delivery_id = ?
                                        """).param(stale).param(deliveryId).update();
                        List<ClaimedWebhookDelivery> reclaimed =
                                repository.claimDue(1, List.of(), Instant.now().plusSeconds(30));
                        assertThat(reclaimed).hasSize(1);
                        assertThat(repository.completeSuccess(deliveryId, stale, 200))
                                .isFalse();
                        return repository.completeSuccess(
                                deliveryId, reclaimed.getFirst().claimToken(), 200);
                    });
            assertThat(cas).isTrue();
        }

        @Test
        void disable_cancels_pending_and_logs_omit_secret_and_full_url() throws Exception {
            ListAppender<ILoggingEvent> appender = attachLog(WebhookDispatcher.class);
            CountDownLatch hold = new CountDownLatch(1);
            RecordingHook hook = startHook(ex -> {
                awaitLatch(hold);
                write(ex, 200, "ok");
            });
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Cancel"));
            String webhookId = createWebhook(owner, projectId, hook.url(), true, SECRET);
            createClass(owner, projectId, "A");
            createClass(owner, projectId, "B");
            relay.wake();
            dispatcher.wake();
            awaitUntil(() -> hook.requests().size() == 1);
            awaitUntil(() -> pendingCount(UUID.fromString(webhookId)) >= 2);

            putJson(
                    owner,
                    path(projectId, webhookId),
                    Map.of(
                            "payloadUrl",
                            hook.url(),
                            "eventTypes",
                            List.of("PROJECT_CHANGED"),
                            "secretChange",
                            "KEEP",
                            "enabled",
                            false));
            awaitUntil(() -> cancelledCount(UUID.fromString(webhookId)) >= 2);
            hold.countDown();

            putJson(
                    owner,
                    path(projectId, webhookId),
                    Map.of(
                            "payloadUrl",
                            hook.url(),
                            "eventTypes",
                            List.of("PROJECT_CHANGED"),
                            "secretChange",
                            "KEEP",
                            "enabled",
                            true));
            assertThat(pendingCount(UUID.fromString(webhookId))).isZero();

            String logs = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .reduce("", String::concat);
            assertThat(logs).doesNotContain(SECRET);
            assertThat(logs).doesNotContain(hook.url());
        }

        private RecordingHook startHook(ThrowingConsumer<HttpExchange> handler) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingHook hook = new RecordingHook(server);
            server.createContext("/hook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                Map<String, String> headers = new java.util.LinkedHashMap<>();
                exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.getFirst()));
                hook.requests.add(new CapturedRequest(exchange.getRequestMethod(), body, headers));
                handler.accept(exchange);
            });
            server.start();
            servers.add(server);
            return hook;
        }

        private String status(UUID webhookId) {
            return jdbc.sql("""
                            select status from webhook_deliveries
                            where webhook_id = ? order by delivery_id desc limit 1
                            """)
                    .param(webhookId)
                    .query((rs, row) -> rs.getString(1))
                    .optional()
                    .orElse("");
        }

        private int pendingCount(UUID webhookId) {
            return jdbc.sql("select count(*) from webhook_deliveries where webhook_id = ? and status = 'PENDING'")
                    .param(webhookId)
                    .query((rs, row) -> rs.getInt(1))
                    .single();
        }

        private int cancelledCount(UUID webhookId) {
            return jdbc.sql("select count(*) from webhook_deliveries where webhook_id = ? and status = 'CANCELLED'")
                    .param(webhookId)
                    .query((rs, row) -> rs.getInt(1))
                    .single();
        }

        private long consumerLastId(String name) {
            return jdbc.sql("select last_id from outbox_consumers where consumer = ?")
                    .param(name)
                    .query((rs, row) -> rs.getLong(1))
                    .optional()
                    .orElse(0L);
        }

        private boolean consumerRegistered(String name) {
            return jdbc.sql("select count(*) from outbox_consumers where consumer = ?")
                            .param(name)
                            .query((rs, row) -> rs.getInt(1))
                            .single()
                    > 0;
        }

        private SignedIn owner() throws Exception {
            String userId =
                    "p621-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
            users.create(userId, userId, userId + "@example.com", PASSWORD, List.of("PROJECT_CREATOR"));
            MockHttpSession session = new MockHttpSession();
            Cookie csrf = csrfCookie(session);
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .session(session)
                            .cookie(csrf)
                            .header(CSRF_HEADER, csrf.getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(Map.of("username", userId, "password", PASSWORD))))
                    .andReturn();
            assertThat(login.getResponse().getStatus()).isEqualTo(200);
            Cookie updated = login.getResponse().getCookie(CSRF_COOKIE);
            return new SignedIn(userId, session, updated == null ? csrf : updated);
        }

        private MvcResult create(SignedIn user, String name) throws Exception {
            return postJson(user, "/api/projects", Map.of("name", name, "description", "Hooks"));
        }

        private ProjectId projectId(MvcResult result) throws Exception {
            assertThat(result.getResponse().getStatus())
                    .as(result.getResponse().getContentAsString())
                    .isEqualTo(201);
            return ProjectId.of(json.readTree(result.getResponse().getContentAsString())
                    .path("projectId")
                    .asString());
        }

        private String createWebhook(SignedIn user, ProjectId projectId, String url, boolean enabled, String secret)
                throws Exception {
            Map<String, Object> body = secret == null
                    ? Map.of("payloadUrl", url, "eventTypes", List.of("PROJECT_CHANGED"), "enabled", enabled)
                    : Map.of(
                            "payloadUrl",
                            url,
                            "eventTypes",
                            List.of("PROJECT_CHANGED"),
                            "secret",
                            secret,
                            "enabled",
                            enabled);
            MvcResult created = postJson(user, path(projectId), body);
            assertThat(created.getResponse().getStatus())
                    .as(created.getResponse().getContentAsString())
                    .isEqualTo(201);
            return json.readTree(created.getResponse().getContentAsString())
                    .path("webhookId")
                    .asString();
        }

        private Created createClass(SignedIn owner, ProjectId projectId, String name) throws Exception {
            MvcResult created = postJson(
                    owner,
                    "/api/projects/" + projectId + "/entities",
                    Map.of("entityType", "CLASS", "names", List.of(name), "parents", List.of(THING)));
            assertThat(created.getResponse().getStatus())
                    .as(created.getResponse().getContentAsString())
                    .isEqualTo(201);
            JsonNode body = json.readTree(created.getResponse().getContentAsString());
            return new Created(body.path("revision").asLong());
        }

        private void createClassAllowingFailure(SignedIn owner, ProjectId projectId, String name) throws Exception {
            postJson(
                    owner,
                    "/api/projects/" + projectId + "/entities",
                    Map.of("entityType", "CLASS", "names", List.of(name), "parents", List.of(THING)));
        }

        private String path(ProjectId projectId) {
            return "/api/projects/" + projectId + "/webhooks";
        }

        private String path(ProjectId projectId, String webhookId) {
            return path(projectId) + "/" + webhookId;
        }

        private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
            return mockMvc.perform(post(path)
                            .session(user.session())
                            .cookie(user.csrf())
                            .header(CSRF_HEADER, user.csrf().getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andReturn();
        }

        private MvcResult putJson(SignedIn user, String path, Object body) throws Exception {
            return mockMvc.perform(put(path)
                            .session(user.session())
                            .cookie(user.csrf())
                            .header(CSRF_HEADER, user.csrf().getValue())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andReturn();
        }

        private MvcResult postPath(SignedIn user, String path) throws Exception {
            return mockMvc.perform(post(path)
                            .session(user.session())
                            .cookie(user.csrf())
                            .header(CSRF_HEADER, user.csrf().getValue()))
                    .andReturn();
        }

        private Cookie csrfCookie(MockHttpSession session) throws Exception {
            MvcResult primed =
                    mockMvc.perform(get("/api/auth/me").session(session)).andReturn();
            Cookie csrf = primed.getResponse().getCookie(CSRF_COOKIE);
            assertThat(csrf).isNotNull();
            return csrf;
        }
    }

    @Nested
    @DatabaseTest
    @TestPropertySource(properties = {"wp.webhooks.enabled=false"})
    class Disabled {

        private final ObjectProvider<WebhookOutboxConsumer> consumers;
        private final ObjectProvider<WebhookDispatcher> dispatchers;

        Disabled(ObjectProvider<WebhookOutboxConsumer> consumers, ObjectProvider<WebhookDispatcher> dispatchers) {
            this.consumers = consumers;
            this.dispatchers = dispatchers;
        }

        @Test
        void no_consumer_or_dispatcher_beans() {
            assertThat(consumers.getIfAvailable()).isNull();
            assertThat(dispatchers.getIfAvailable()).isNull();
        }
    }

    private static String header(CapturedRequest request, String name) {
        for (Map.Entry<String, String> entry : request.headers().entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static void write(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
        throw new AssertionError("condition not met");
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(20, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
    }

    private static ListAppender<ILoggingEvent> attachLog(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Created(long revision) {}

    private record CapturedRequest(String method, byte[] body, Map<String, String> headers) {}

    private static final class RecordingHook {
        private final HttpServer server;
        private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();

        private RecordingHook(HttpServer server) {
            this.server = server;
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        private List<CapturedRequest> requests() {
            return requests;
        }
    }

    @FunctionalInterface
    private interface ThrowingConsumer<T> {
        void accept(T value) throws IOException;
    }
}
