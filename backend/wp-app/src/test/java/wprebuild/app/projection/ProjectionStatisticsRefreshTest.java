package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Statistics refresh after projection rebuild (P6-17). Must not be {@code @Transactional}: afterCommit
 * callbacks only run when the rebuild transaction actually commits.
 */
@DatabaseTest
@AutoConfigureMockMvc
class ProjectionStatisticsRefreshTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final List<String> TABLES =
            List.of("entities", "labels", "hierarchy_edges", "property_domains", "property_ranges");

    private static final List<String> QUALIFIED_TABLES = List.of(
            "projection.entities",
            "projection.labels",
            "projection.hierarchy_edges",
            "projection.property_domains",
            "projection.property_ranges");

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    private final JdbcTemplate jdbcTemplate;

    private final ProjectRuntimeRegistry registry;

    private final ProjectionWriter writer;

    private final ProjectionRebuilder rebuilder;

    private final PlatformTransactionManager transactionManager;

    private final TransactionTemplate requiresNew;

    ProjectionStatisticsRefreshTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcClient jdbc,
            JdbcTemplate jdbcTemplate,
            ProjectRuntimeRegistry registry,
            ProjectionWriter writer,
            ProjectionRebuilder rebuilder,
            PlatformTransactionManager transactionManager) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
        this.registry = registry;
        this.writer = writer;
        this.rebuilder = rebuilder;
        this.transactionManager = transactionManager;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    void refreshStatistics_increments_analyze_count_on_all_five_tables() {
        Map<String, Long> before = analyzeCounts();

        writer.refreshStatistics();

        awaitAnalyzeIncrease(before, 1);
    }

    @Test
    void rebuild_without_outer_transaction_refreshes_statistics() throws Exception {
        ProjectId projectId = uploadWarehouse();
        Map<String, Long> before = analyzeCounts();

        rebuilder.rebuild(projectId);

        awaitAnalyzeIncrease(before, 1);
        long edgeCount = jdbc.sql("select count(*) from projection.hierarchy_edges where project_id = ?")
                .param(projectId.value())
                .query(Long.class)
                .single();
        assertThat(hierarchyRelTuples()).isGreaterThanOrEqualTo(edgeCount);
    }

    @Test
    void rebuild_inside_outer_transaction_refreshes_only_after_commit() throws Exception {
        ProjectId projectId = uploadWarehouse();
        Map<String, Long> before = analyzeCounts();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            rebuilder.rebuild(projectId);
            assertThat(analyzeCounts()).isEqualTo(before);
        });

        awaitAnalyzeIncrease(before, 1);
    }

    @Test
    void rebuild_inside_rolled_back_outer_transaction_does_not_refresh() throws Exception {
        ProjectId projectId = uploadWarehouse();
        Map<String, Long> before = analyzeCounts();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            rebuilder.rebuild(projectId);
            status.setRollbackOnly();
        });

        assertAnalyzeUnchanged(before);
    }

    @Test
    void two_rebuilds_in_same_outer_transaction_refresh_statistics_once() throws Exception {
        ProjectId first = uploadWarehouse("first");
        ProjectId second = uploadWarehouse("second");
        Map<String, Long> before = analyzeCounts();
        TransactionTemplate outer = new TransactionTemplate(transactionManager);

        outer.executeWithoutResult(status -> {
            rebuilder.rebuild(first);
            rebuilder.rebuild(second);
        });

        awaitAnalyzeIncrease(before, 1);
        assertThat(analyzeCounts().get("hierarchy_edges")).isEqualTo(before.get("hierarchy_edges") + 1);
    }

    @Test
    void tablesMissingMaintain_reports_tables_for_unprivileged_role() {
        String role = "p6_17_" + UUID.randomUUID().toString().replace("-", "");
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            status.setRollbackOnly();
            jdbc.sql("create role " + role + " nologin").update();
            jdbc.sql("set local role " + role).update();
            assertThat(writer.tablesMissingMaintain()).containsExactlyInAnyOrderElementsOf(QUALIFIED_TABLES);
        });
        assertThat(writer.tablesMissingMaintain()).isEmpty();
    }

    @Test
    void rebuild_skips_refresh_when_replaceProject_returns_false() throws Exception {
        ProjectId projectId = uploadWarehouse();
        long head = registry.get(projectId).runtime().head().value();
        jdbc.sql("""
                        insert into projection.project_state (project_id, rev_no, rebuilt_at, updated_at)
                        values (?, ?, now(), now())
                        on conflict (project_id) do update set
                            rev_no = excluded.rev_no,
                            rebuilt_at = excluded.rebuilt_at,
                            updated_at = excluded.updated_at
                        """).param(projectId.value()).param(head + 100).update();
        Map<String, Long> before = analyzeCounts();

        rebuilder.rebuild(projectId);

        assertAnalyzeUnchanged(before);
    }

    @Test
    void rebuild_swallows_refresh_failure_after_projection_is_committed() throws Exception {
        ProjectId projectId = uploadWarehouse();
        ProjectionWriter failingWriter = new ProjectionWriter(jdbc, jdbcTemplate) {
            @Override
            public void refreshStatistics() {
                throw new DataAccessResourceFailureException("forced statistics refresh failure");
            }
        };
        ProjectionRebuilder failingRebuilder = new ProjectionRebuilder(failingWriter, registry, transactionManager);

        failingRebuilder.rebuild(projectId);

        assertThat(jdbc.sql("select rev_no from projection.project_state where project_id = ?")
                        .param(projectId.value())
                        .query(Long.class)
                        .optional())
                .isPresent();
        assertThat(jdbc.sql("select count(*) from projection.entities where project_id = ?")
                        .param(projectId.value())
                        .query(Long.class)
                        .single())
                .isPositive();
    }

    private Map<String, Long> analyzeCounts() {
        return requiresNew.execute(status -> {
            Map<String, Long> counts = new LinkedHashMap<>();
            for (String table : TABLES) {
                counts.put(table, jdbc.sql("""
                                        select analyze_count
                                        from pg_stat_user_tables
                                        where schemaname = 'projection' and relname = ?
                                        """).param(table).query(Long.class).single());
            }
            return counts;
        });
    }

    private double hierarchyRelTuples() {
        return requiresNew.execute(status -> jdbc.sql("""
                        select c.reltuples
                        from pg_class c
                        join pg_namespace n on n.oid = c.relnamespace
                        where n.nspname = 'projection' and c.relname = 'hierarchy_edges'
                        """).query(Double.class).single());
    }

    private void awaitAnalyzeIncrease(Map<String, Long> before, long minDelta) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Map<String, Long> last = before;
        while (System.nanoTime() < deadline) {
            last = analyzeCounts();
            if (allIncreasedByAtLeast(before, last, minDelta)) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        assertThat(last)
                .as(
                        "analyze_count did not increase by at least %s within 5s; before=%s after=%s",
                        minDelta, before, last)
                .satisfies(after -> assertThat(allIncreasedByAtLeast(before, after, minDelta))
                        .isTrue());
    }

    private void assertAnalyzeUnchanged(Map<String, Long> before) throws InterruptedException {
        Thread.sleep(1000);
        assertThat(analyzeCounts()).isEqualTo(before);
    }

    private static boolean allIncreasedByAtLeast(Map<String, Long> before, Map<String, Long> after, long minDelta) {
        for (String table : TABLES) {
            if (after.get(table) < before.get(table) + minDelta) {
                return false;
            }
        }
        return true;
    }

    private ProjectId uploadWarehouse() throws Exception {
        return uploadWarehouse("Warehouse");
    }

    private ProjectId uploadWarehouse(String name) throws Exception {
        String userId = "u" + UUID.randomUUID().toString().replace("-", "");
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of("PROJECT_CREATOR"));
        SignedIn owner = login(userId);
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
        return ProjectId.of(read(created).path("projectId").asString());
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

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}
}
