package wprebuild.app.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.revision.OutboxRelay;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

/**
 * Outbox → Kafka publisher against Testcontainers PostgreSQL + apache/kafka (P6-35).
 *
 * <p>GateGuard facts: Surefire runs {@code KafkaPublisherTest} (card acceptance); Glob found none
 * beforehand; synthetic HttpServer + Testcontainers only; user asked {@code 完成下一个任务卡}.
 */
class KafkaPublisherTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static final String TOPIC = "wp.revisions";

    /** Official Apache Kafka image, pinned (not {@code latest}). */
    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("apache/kafka:3.9.1");

    private static final KafkaContainer KAFKA = new KafkaContainer(KAFKA_IMAGE)
            .waitingFor(Wait.forLogMessage(".*Transitioning from RECOVERY to RUNNING.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    private static void ensureKafkaRunning(DynamicPropertyRegistry registry) {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void stopKafka() {
        if (KAFKA.isRunning()) {
            KAFKA.stop();
        }
    }

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "wp.kafka.enabled=true",
                "wp.kafka.topic=wp.revisions",
                "wp.kafka.start-from=EARLIEST",
                "wp.kafka.send-timeout=2s",
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
                "wp.projection.enabled=false"
            })
    class Publishing {

        @DynamicPropertySource
        static void kafkaBootstrap(DynamicPropertyRegistry registry) {
            ensureKafkaRunning(registry);
        }

        private final MockMvc mockMvc;
        private final JsonMapper json;
        private final UserService users;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;
        private final WebhookDispatcher dispatcher;
        private final String suffix = UUID.randomUUID().toString().substring(0, 8);
        private final List<HttpServer> servers = new ArrayList<>();
        private final List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

        Publishing(
                MockMvc mockMvc,
                JsonMapper json,
                UserService users,
                JdbcClient jdbc,
                OutboxRelay relay,
                WebhookDispatcher dispatcher) {
            this.mockMvc = mockMvc;
            this.json = json;
            this.users = users;
            this.jdbc = jdbc;
            this.relay = relay;
            this.dispatcher = dispatcher;
        }

        @BeforeEach
        void createTopic() throws Exception {
            ensureTopic(KAFKA);
        }

        @AfterEach
        void cleanup() {
            closeConsumers(consumers);
            for (HttpServer server : servers) {
                server.stop(0);
            }
            servers.clear();
            jdbc.sql("""
                            update webhook_deliveries
                            set status = 'CANCELLED', finished_at = now(), claim_token = null
                            where status = 'PENDING'
                            """).update();
        }

        @Test
        void same_project_three_revisions_arrive_in_order_with_full_fields() throws Exception {
            SignedIn owner = owner();
            ProjectId projectA = projectId(create(owner, "Kafka-A"));
            ProjectId projectB = projectId(create(owner, "Kafka-B"));
            KafkaConsumer<String, String> reader = subscribe(KAFKA, consumers);

            List<Long> revA = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                revA.add(createClass(owner, projectA, "A" + i).revision());
                relay.wake();
            }
            long revB = createClass(owner, projectB, "B0").revision();
            relay.wake();

            // Project creation also emits revisions; wait until the three class edits + B0 are visible.
            List<ConsumerRecord<String, String>> forA = new ArrayList<>();
            List<ConsumerRecord<String, String>> forB = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline && (forA.size() < 3 || forB.isEmpty())) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(500))) {
                    JsonNode body = json.readTree(record.value());
                    long revNo = body.path("revNo").asLong();
                    if (projectA.toString().equals(record.key()) && revA.contains(revNo)) {
                        forA.add(record);
                    } else if (projectB.toString().equals(record.key()) && revNo == revB) {
                        forB.add(record);
                    }
                }
            }
            assertThat(forA).hasSize(3);
            assertThat(forB).hasSize(1);
            assertThat(forA.get(0).key())
                    .isEqualTo(forA.get(1).key())
                    .isEqualTo(forA.get(2).key());
            assertThat(forA.get(0).key()).isNotEqualTo(forB.get(0).key());

            long prevOutbox = -1;
            for (int i = 0; i < 3; i++) {
                JsonNode body = json.readTree(forA.get(i).value());
                assertThat(body.propertyNames())
                        .containsExactlyInAnyOrder("outboxId", "type", "projectId", "revNo", "payload", "createdAt");
                assertThat(body.path("type").asString()).isEqualTo("REVISION_COMMITTED");
                assertThat(body.path("projectId").asString()).isEqualTo(projectA.toString());
                assertThat(body.path("revNo").asLong()).isEqualTo(revA.get(i));
                assertThat(body.path("payload").isObject()).isTrue();
                assertThat(body.path("createdAt").asString()).isNotBlank();
                long outboxId = body.path("outboxId").asLong();
                assertThat(outboxId).isGreaterThan(prevOutbox);
                prevOutbox = outboxId;
                assertThat(header(forA.get(i), KafkaOutboxConsumer.HEADER_OUTBOX_ID))
                        .isEqualTo(Long.toString(outboxId));
                assertThat(header(forA.get(i), KafkaOutboxConsumer.HEADER_EVENT_TYPE))
                        .isEqualTo("REVISION_COMMITTED");
            }
        }

        @Test
        void broker_pause_holds_kafka_cursor_while_webhooks_advance_then_recovers() throws Exception {
            RecordingHook hook = startHook();
            SignedIn owner = owner();
            ProjectId projectId = projectId(create(owner, "Pause"));
            createWebhook(owner, projectId, hook.url());

            createClass(owner, projectId, "Warm");
            relay.wake();
            dispatcher.wake();
            awaitUntil(() -> consumerLastId(jdbc, KafkaOutboxConsumer.CONSUMER_NAME) > 0);
            awaitUntil(() -> hook.requests().size() >= 1);
            long kafkaCursor = consumerLastId(jdbc, KafkaOutboxConsumer.CONSUMER_NAME);
            long webhookCursor = consumerLastId(jdbc, WebhookOutboxConsumer.CONSUMER_NAME);
            assertThat(webhookCursor).isGreaterThanOrEqualTo(kafkaCursor);

            KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
            try {
                long editStarted = System.nanoTime();
                createClass(owner, projectId, "WhileDown");
                long newOutboxId = outboxIdsFor(jdbc, projectId).getLast();
                relay.wake();
                dispatcher.wake();

                // Webhooks must pass the new row even while Kafka is wedged on send-timeout.
                awaitUntil(() -> consumerLastId(jdbc, WebhookOutboxConsumer.CONSUMER_NAME) >= newOutboxId);
                awaitUntil(() -> hook.requests().size() >= 2);
                Duration webhookLag = Duration.ofNanos(System.nanoTime() - editStarted);
                assertThat(webhookLag).isLessThan(Duration.ofSeconds(5));
                assertThat(consumerLastId(jdbc, KafkaOutboxConsumer.CONSUMER_NAME))
                        .isEqualTo(kafkaCursor);
                assertThat(consumerLastId(jdbc, WebhookOutboxConsumer.CONSUMER_NAME))
                        .isGreaterThan(webhookCursor);
            } finally {
                KAFKA.getDockerClient()
                        .unpauseContainerCmd(KAFKA.getContainerId())
                        .exec();
            }

            KafkaConsumer<String, String> reader = subscribe(KAFKA, consumers);
            relay.wake();
            awaitUntil(() -> consumerLastId(jdbc, KafkaOutboxConsumer.CONSUMER_NAME) > kafkaCursor);

            Set<Long> seen = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline && seen.size() < 2) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(500))) {
                    if (projectId.toString().equals(record.key())) {
                        seen.add(json.readTree(record.value()).path("outboxId").asLong());
                    }
                }
            }
            assertThat(seen).containsExactlyInAnyOrderElementsOf(outboxIdsFor(jdbc, projectId));
        }

        private RecordingHook startHook() throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            RecordingHook hook = new RecordingHook(server);
            server.createContext("/hook", exchange -> {
                byte[] body = exchange.getRequestBody().readAllBytes();
                hook.requests.add(body);
                byte[] ok = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, ok.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(ok);
                }
            });
            server.start();
            servers.add(server);
            return hook;
        }

        private void createWebhook(SignedIn user, ProjectId projectId, String url) throws Exception {
            MvcResult created = postJson(
                    user,
                    "/api/projects/" + projectId + "/webhooks",
                    Map.of("payloadUrl", url, "eventTypes", List.of("PROJECT_CHANGED"), "enabled", true));
            assertThat(created.getResponse().getStatus())
                    .as(created.getResponse().getContentAsString())
                    .isEqualTo(201);
        }

        private SignedIn owner() throws Exception {
            return signIn(users, mockMvc, json, suffix);
        }

        private MvcResult create(SignedIn user, String name) throws Exception {
            return postJson(user, "/api/projects", Map.of("name", name, "description", "Kafka"));
        }

        private ProjectId projectId(MvcResult result) throws Exception {
            return readProjectId(json, result);
        }

        private Created createClass(SignedIn owner, ProjectId projectId, String name) throws Exception {
            return createEntity(mockMvc, json, owner, projectId, name);
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
    }

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "wp.kafka.enabled=true",
                "wp.kafka.topic=wp.revisions",
                "wp.kafka.start-from=LATEST",
                "wp.kafka.send-timeout=2s",
                "wp.outbox.enabled=true",
                "wp.outbox.poll-interval=1h",
                "wp.projection.enabled=false",
                "wp.webhooks.enabled=false"
            })
    class StartFromLatest {

        @DynamicPropertySource
        static void kafkaBootstrap(DynamicPropertyRegistry registry) {
            ensureKafkaRunning(registry);
        }

        private final MockMvc mockMvc;
        private final JsonMapper json;
        private final UserService users;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;
        private final String suffix = UUID.randomUUID().toString().substring(0, 8);
        private final List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

        StartFromLatest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcClient jdbc, OutboxRelay relay) {
            this.mockMvc = mockMvc;
            this.json = json;
            this.users = users;
            this.jdbc = jdbc;
            this.relay = relay;
        }

        @BeforeEach
        void createTopic() throws Exception {
            ensureTopic(KAFKA);
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
        }

        @AfterEach
        void cleanup() {
            closeConsumers(consumers);
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
        }

        @Test
        void latest_skips_rows_present_before_registration() throws Exception {
            SignedIn owner = signIn(users, mockMvc, json, suffix);
            ProjectId projectId = readProjectId(
                    json,
                    postJson(mockMvc, json, owner, "/api/projects", Map.of("name", "Latest", "description", "x")));
            createEntity(mockMvc, json, owner, projectId, "Before1");
            createEntity(mockMvc, json, owner, projectId, "Before2");
            // Drop the cursor so the next wake re-runs start-from=LATEST against existing rows.
            awaitUntil(() -> consumerRegistered(jdbc, KafkaOutboxConsumer.CONSUMER_NAME));
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
            List<Long> before = outboxIdsFor(jdbc, projectId);
            assertThat(before).hasSizeGreaterThanOrEqualTo(2);
            long maxBefore = before.getLast();

            KafkaConsumer<String, String> reader = subscribe(KAFKA, consumers);
            relay.wake();
            awaitUntil(() -> consumerRegistered(jdbc, KafkaOutboxConsumer.CONSUMER_NAME)
                    && consumerLastId(jdbc, KafkaOutboxConsumer.CONSUMER_NAME) >= maxBefore);
            Thread.sleep(400);
            // Ignore other tests' leftovers on the shared topic; only this project's new ids matter.
            List<ConsumerRecord<String, String>> premature = pollAvailable(reader).stream()
                    .filter(r -> projectId.toString().equals(r.key()))
                    .filter(r -> json.readTree(r.value()).path("outboxId").asLong() > maxBefore)
                    .toList();
            assertThat(premature).isEmpty();

            createEntity(mockMvc, json, owner, projectId, "After");
            long expected = outboxIdsFor(jdbc, projectId).getLast();
            relay.wake();
            Long published = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (published == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(500))) {
                    if (!projectId.toString().equals(record.key())) {
                        continue;
                    }
                    long id = json.readTree(record.value()).path("outboxId").asLong();
                    if (id > maxBefore) {
                        published = id;
                        break;
                    }
                }
            }
            assertThat(published).isEqualTo(expected);
        }
    }

    @Nested
    @DatabaseTest
    @AutoConfigureMockMvc
    @TestPropertySource(
            properties = {
                "wp.kafka.enabled=true",
                "wp.kafka.topic=wp.revisions",
                "wp.kafka.start-from=EARLIEST",
                "wp.kafka.send-timeout=2s",
                "wp.outbox.enabled=true",
                "wp.outbox.poll-interval=1h",
                "wp.projection.enabled=false",
                "wp.webhooks.enabled=false"
            })
    class StartFromEarliest {

        @DynamicPropertySource
        static void kafkaBootstrap(DynamicPropertyRegistry registry) {
            ensureKafkaRunning(registry);
        }

        private final MockMvc mockMvc;
        private final JsonMapper json;
        private final UserService users;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;
        private final String suffix = UUID.randomUUID().toString().substring(0, 8);
        private final List<KafkaConsumer<String, String>> consumers = new ArrayList<>();

        StartFromEarliest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcClient jdbc, OutboxRelay relay) {
            this.mockMvc = mockMvc;
            this.json = json;
            this.users = users;
            this.jdbc = jdbc;
            this.relay = relay;
        }

        @BeforeEach
        void createTopic() throws Exception {
            ensureTopic(KAFKA);
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
        }

        @AfterEach
        void cleanup() {
            closeConsumers(consumers);
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
        }

        @Test
        void earliest_publishes_history() throws Exception {
            SignedIn owner = signIn(users, mockMvc, json, suffix);
            ProjectId projectId = readProjectId(
                    json,
                    postJson(mockMvc, json, owner, "/api/projects", Map.of("name", "Earliest", "description", "x")));
            createEntity(mockMvc, json, owner, projectId, "H1");
            createEntity(mockMvc, json, owner, projectId, "H2");
            awaitUntil(() -> consumerRegistered(jdbc, KafkaOutboxConsumer.CONSUMER_NAME));
            jdbc.sql("delete from outbox_consumers where consumer = ?")
                    .param(KafkaOutboxConsumer.CONSUMER_NAME)
                    .update();
            List<Long> history = outboxIdsFor(jdbc, projectId);
            assertThat(history).hasSizeGreaterThanOrEqualTo(2);

            KafkaConsumer<String, String> reader = subscribe(KAFKA, consumers);
            relay.wake();
            Set<Long> published = new HashSet<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline && !published.containsAll(history)) {
                for (ConsumerRecord<String, String> record : reader.poll(Duration.ofMillis(500))) {
                    published.add(json.readTree(record.value()).path("outboxId").asLong());
                }
            }
            assertThat(published).containsAll(history);
        }
    }

    @Nested
    @DatabaseTest
    @TestPropertySource(
            properties = {"wp.kafka.enabled=false", "wp.outbox.enabled=true", "wp.outbox.poll-interval=100ms"})
    class Disabled {

        private final ObjectProvider<KafkaOutboxConsumer> consumers;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;

        Disabled(ObjectProvider<KafkaOutboxConsumer> consumers, JdbcClient jdbc, OutboxRelay relay) {
            this.consumers = consumers;
            this.jdbc = jdbc;
            this.relay = relay;
        }

        @Test
        void no_consumer_bean_and_no_outbox_row() throws Exception {
            assertThat(consumers.getIfAvailable()).isNull();
            relay.wake();
            Thread.sleep(400);
            assertThat(consumerRegistered(jdbc, KafkaOutboxConsumer.CONSUMER_NAME))
                    .isFalse();
        }
    }

    private static void ensureTopic(KafkaContainer kafka) throws Exception {
        try (AdminClient admin =
                AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            if (ex.getCause() != null
                    && ex.getCause().getMessage() != null
                    && ex.getCause().getMessage().contains("already exists")) {
                return;
            }
            throw ex;
        }
    }

    private static SignedIn signIn(UserService users, MockMvc mockMvc, JsonMapper json, String suffix)
            throws Exception {
        String userId = "p635-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of("PROJECT_CREATOR"));
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(mockMvc, session);
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

    private static Cookie csrfCookie(MockMvc mockMvc, MockHttpSession session) throws Exception {
        MvcResult primed = mockMvc.perform(get("/api/auth/me").session(session)).andReturn();
        Cookie csrf = primed.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static ProjectId readProjectId(JsonMapper json, MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(json.readTree(result.getResponse().getContentAsString())
                .path("projectId")
                .asString());
    }

    private static Created createEntity(
            MockMvc mockMvc, JsonMapper json, SignedIn owner, ProjectId projectId, String name) throws Exception {
        MvcResult created = postJson(
                mockMvc,
                json,
                owner,
                "/api/projects/" + projectId + "/entities",
                Map.of("entityType", "CLASS", "names", List.of(name), "parents", List.of(THING)));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return new Created(json.readTree(created.getResponse().getContentAsString())
                .path("revision")
                .asLong());
    }

    private static MvcResult postJson(MockMvc mockMvc, JsonMapper json, SignedIn user, String path, Object body)
            throws Exception {
        return mockMvc.perform(post(path)
                        .session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private static long consumerLastId(JdbcClient jdbc, String name) {
        return jdbc.sql("select last_id from outbox_consumers where consumer = ?")
                .param(name)
                .query((rs, row) -> rs.getLong(1))
                .optional()
                .orElse(0L);
    }

    private static boolean consumerRegistered(JdbcClient jdbc, String name) {
        return jdbc.sql("select count(*) from outbox_consumers where consumer = ?")
                        .param(name)
                        .query((rs, row) -> rs.getInt(1))
                        .single()
                > 0;
    }

    private static List<Long> outboxIdsFor(JdbcClient jdbc, ProjectId projectId) {
        return jdbc.sql("select id from outbox where project_id = ? order by id")
                .param(projectId.value())
                .query(Long.class)
                .list();
    }

    private static KafkaConsumer<String, String> subscribe(
            KafkaContainer broker, List<KafkaConsumer<String, String>> sink) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, broker.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "p635-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(List.of(TOPIC));
        sink.add(consumer);
        return consumer;
    }

    private static void closeConsumers(List<KafkaConsumer<String, String>> consumers) {
        for (KafkaConsumer<String, String> consumer : consumers) {
            consumer.close();
        }
        consumers.clear();
    }

    private static List<ConsumerRecord<String, String>> pollUntil(KafkaConsumer<String, String> consumer, int min) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && collected.size() < min) {
            ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
            batch.forEach(collected::add);
        }
        assertThat(collected.size()).isGreaterThanOrEqualTo(min);
        return collected;
    }

    private static List<ConsumerRecord<String, String>> pollAvailable(KafkaConsumer<String, String> consumer) {
        List<ConsumerRecord<String, String>> collected = new ArrayList<>();
        consumer.poll(Duration.ofSeconds(1)).forEach(collected::add);
        return collected;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
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

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Created(long revision) {}

    private static final class RecordingHook {
        private final HttpServer server;
        private final List<byte[]> requests = new CopyOnWriteArrayList<>();

        private RecordingHook(HttpServer server) {
            this.server = server;
        }

        private String url() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
        }

        private List<byte[]> requests() {
            return requests;
        }
    }
}
