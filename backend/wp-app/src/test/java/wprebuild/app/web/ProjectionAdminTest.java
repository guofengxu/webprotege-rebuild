package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.projection.ProjectionRows;
import wprebuild.app.projection.ProjectionSnapshot;
import wprebuild.app.projection.ProjectionWriter;
import wprebuild.app.revision.OutboxRelay;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Admin projection status and rebuild APIs (P6-13). Must not be {@code @Transactional}: the outbox
 * relay and rebuild need real commits.
 */
@DatabaseTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "wp.projection.enabled=true",
            "wp.outbox.enabled=true",
            "wp.outbox.poll-interval=100ms",
            "wp.outbox.batch-size=50"
        })
class ProjectionAdminTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    private final ProjectRuntimeRegistry registry;

    private final ProjectionWriter writer;

    private final OutboxRelay relay;

    ProjectionAdminTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcClient jdbc,
            ProjectRuntimeRegistry registry,
            ProjectionWriter writer,
            OutboxRelay relay) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
        this.registry = registry;
        this.writer = writer;
        this.relay = relay;
    }

    @Test
    void admin_sees_lag_reach_zero_after_edit() throws Exception {
        SignedIn admin = account("admin", "SYSTEM_ADMIN", "PROJECT_CREATOR");
        ProjectId projectId = uploadWarehouse(admin, "LagZero");
        awaitCaughtUp(projectId);

        createClass(admin, projectId, "Carton");
        awaitUntil(
                () -> {
                    try {
                        JsonNode status = status(admin, projectId);
                        return status.path("lag").isNumber()
                                && status.path("lag").asLong() == 0L;
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                10);
        JsonNode body = status(admin, projectId);
        assertThat(body.path("revNo").asLong()).isEqualTo(body.path("head").asLong());
        assertThat(body.path("lag").asLong()).isZero();
    }

    @Test
    void rebuild_restores_deleted_rows_and_updates_rebuilt_at() throws Exception {
        SignedIn admin = account("admin", "SYSTEM_ADMIN", "PROJECT_CREATOR");
        ProjectId projectId = uploadWarehouse(admin, "Rebuild");
        awaitCaughtUp(projectId);

        long entitiesBefore = entityCount(projectId);
        Instant rebuiltBefore = rebuiltAt(projectId);
        assertThat(entitiesBefore).isPositive();

        jdbc.sql("delete from projection.entities where project_id = ?")
                .param(projectId.value())
                .update();
        assertThat(entityCount(projectId)).isZero();

        JsonNode rebuilt = rebuild(admin, projectId);
        assertThat(entityCount(projectId)).isEqualTo(entitiesBefore);
        assertThat(Instant.parse(rebuilt.path("rebuiltAt").asString())).isAfter(rebuiltBefore);
        assertThat(rebuilt.path("lag").asLong()).isZero();
        assertThat(rebuiltAt(projectId)).isAfter(rebuiltBefore);
    }

    @Test
    void trashed_project_allows_status_but_rejects_rebuild() throws Exception {
        SignedIn admin = account("admin", "SYSTEM_ADMIN", "PROJECT_CREATOR");
        ProjectId projectId = uploadWarehouse(admin, "Trash");
        awaitCaughtUp(projectId);
        trash(admin, projectId);

        JsonNode status = status(admin, projectId);
        assertThat(status.path("projectId").asString())
                .isEqualTo(projectId.value().toString());
        assertThat(status.path("head").asLong()).isPositive();

        MvcResult rebuild = postRebuild(admin, projectId);
        assertThat(rebuild.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(rebuild).path("code").asString()).isEqualTo("PROJECT_TRASHED");
    }

    @Test
    void non_admin_manager_and_anonymous_are_rejected_missing_project_is_not_found() throws Exception {
        SignedIn admin = account("admin", "SYSTEM_ADMIN", "PROJECT_CREATOR");
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = uploadWarehouse(owner, "Auth");
        awaitCaughtUp(projectId);

        MvcResult managerStatus = getStatus(owner, projectId);
        assertThat(managerStatus.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(managerStatus).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        MvcResult managerRebuild = postRebuild(owner, projectId);
        assertThat(managerRebuild.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(managerRebuild).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MockHttpSession anonymous = new MockHttpSession();
        assertThat(mockMvc.perform(get("/api/admin/projections/" + projectId).session(anonymous))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        Cookie csrf = csrfCookie(anonymous);
        assertThat(mockMvc.perform(post("/api/admin/projections/" + projectId + "/rebuild")
                                .session(anonymous)
                                .cookie(csrf)
                                .header(CSRF_HEADER, csrf.getValue()))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        ProjectId missing = ProjectId.of(UUID.randomUUID().toString());
        MvcResult notFound = getStatus(admin, missing);
        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(notFound).path("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void concurrent_edit_and_rebuild_match_compute() throws Exception {
        SignedIn admin = account("admin", "SYSTEM_ADMIN", "PROJECT_CREATOR");
        ProjectId projectId = uploadWarehouse(admin, "Concurrent");
        awaitCaughtUp(projectId);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> edit = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return postJson(
                        admin,
                        "/api/projects/" + projectId + "/entities",
                        new CreateEntitiesBody("CLASS", List.of("ConcurrentClass"), null, List.of(THING)));
            });
            Future<MvcResult> rebuild = pool.submit(() -> {
                start.await(5, TimeUnit.SECONDS);
                return postRebuild(admin, projectId);
            });
            start.countDown();
            assertThat(edit.get(30, TimeUnit.SECONDS).getResponse().getStatus())
                    .as(edit.get().getResponse().getContentAsString())
                    .isEqualTo(201);
            assertThat(rebuild.get(30, TimeUnit.SECONDS).getResponse().getStatus())
                    .as(rebuild.get().getResponse().getContentAsString())
                    .isEqualTo(200);
            relay.wake();
            awaitCaughtUp(projectId);
            assertDbEqualsCompute(projectId);
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertDbEqualsCompute(ProjectId projectId) {
        var handle = registry.get(projectId);
        ProjectionSnapshot expected = handle.gate().read(() -> ProjectionRows.compute(handle.runtime()));
        List<String> fromDb =
                jdbc.sql("""
                        select iri || '|' || entity_type || '|' || deprecated::text
                        from projection.entities where project_id = ?
                        """).param(projectId.value()).query(String.class).list();
        List<String> fromCompute = expected.entities().stream()
                .map(r -> r.iri() + "|" + r.entityType() + "|" + r.deprecated())
                .toList();
        assertThat(writer.projectRevNo(projectId)).contains(head(projectId));
        assertThat(fromDb).containsExactlyInAnyOrderElementsOf(fromCompute);
    }

    private void awaitCaughtUp(ProjectId projectId) {
        awaitUntil(
                () -> {
                    Optional<Long> rev = writer.projectRevNo(projectId);
                    return rev.isPresent() && rev.get() == head(projectId);
                },
                10);
    }

    private void awaitUntil(BooleanSupplier condition, int seconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            relay.wake();
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(condition.getAsBoolean())
                .as("condition not met within %s s", seconds)
                .isTrue();
    }

    private long head(ProjectId projectId) {
        return registry.get(projectId).runtime().head().value();
    }

    private long entityCount(ProjectId projectId) {
        return jdbc.sql("select count(*) from projection.entities where project_id = ?")
                .param(projectId.value())
                .query(Long.class)
                .single();
    }

    private Instant rebuiltAt(ProjectId projectId) {
        return jdbc.sql("select rebuilt_at from projection.project_state where project_id = ?")
                .param(projectId.value())
                .query((rs, rowNum) -> rs.getObject("rebuilt_at", java.time.OffsetDateTime.class)
                        .toInstant())
                .single();
    }

    private JsonNode status(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = getStatus(user, projectId);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult getStatus(SignedIn user, ProjectId projectId) throws Exception {
        return mockMvc.perform(get("/api/admin/projections/" + projectId).session(user.session()))
                .andReturn();
    }

    private JsonNode rebuild(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = postRebuild(user, projectId);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult postRebuild(SignedIn user, ProjectId projectId) throws Exception {
        return mockMvc.perform(post("/api/admin/projections/" + projectId + "/rebuild")
                        .session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
    }

    private SignedIn account(String prefix, String... appRoles) throws Exception {
        String userId = prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
        return login(userId);
    }

    private ProjectId uploadWarehouse(SignedIn owner, String name) throws Exception {
        Path file = TestOntologies.dir()
                .resolve("warehouse-sample.ttl")
                .toAbsolutePath()
                .normalize();
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
        MvcResult created = mockMvc.perform(multipart("/api/projects/upload")
                        .file(part)
                        .param("name", name)
                        .param("description", "sample")
                        .session(owner.session())
                        .cookie(owner.csrf())
                        .header(CSRF_HEADER, owner.csrf().getValue()))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        relay.wake();
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private String createClass(SignedIn owner, ProjectId projectId, String name) throws Exception {
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", List.of(name), null, List.of(THING)));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        relay.wake();
        return read(created).path("entities").get(0).path("iri").asString();
    }

    private void trash(SignedIn owner, ProjectId projectId) throws Exception {
        MvcResult result = postJson(owner, "/api/projects/" + projectId + "/trash", Map.of());
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private SignedIn login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(session);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return new SignedIn(session, csrfAfter(login, csrf));
    }

    private Cookie csrfCookie(MockHttpSession session) throws Exception {
        MvcResult primed = mockMvc.perform(get("/api/auth/me").session(session)).andReturn();
        Cookie csrf = primed.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static Cookie csrfAfter(MvcResult result, Cookie previous) {
        Cookie updated = result.getResponse().getCookie(CSRF_COOKIE);
        return updated == null ? previous : updated;
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

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}
}
