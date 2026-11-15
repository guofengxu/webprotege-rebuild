package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.revision.OutboxMessage;
import wprebuild.app.revision.OutboxRelay;
import wprebuild.app.revision.OutboxRetryLater;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Outbox-driven incremental projection updates (P6-12). Must not be {@code @Transactional}: the
 * relay and after-commit ANALYZE need real commits.
 */
@DatabaseTest
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "wp.projection.enabled=true",
            "wp.projection.full-rebuild-threshold=3",
            "wp.outbox.enabled=true",
            "wp.outbox.poll-interval=100ms",
            "wp.outbox.batch-size=50"
        })
class ProjectionIncrementalTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String PALLET = WH + "Pallet";

    private static final String HANDLING_UNIT = WH + "HandlingUnit";

    private static final String STORED_AT = WH + "storedAt";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    private final ProjectRuntimeRegistry registry;

    private final ProjectionWriter writer;

    private final ProjectionOutboxConsumer consumer;

    private final OutboxRelay relay;

    private final TransactionTemplate transactions;

    ProjectionIncrementalTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcClient jdbc,
            ProjectRuntimeRegistry registry,
            ProjectionWriter writer,
            ProjectionOutboxConsumer consumer,
            OutboxRelay relay,
            PlatformTransactionManager transactionManager) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
        this.registry = registry;
        this.writer = writer;
        this.consumer = consumer;
        this.relay = relay;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void edits_match_full_compute_and_first_rebuild_refreshes_statistics() throws Exception {
        long analyzeBefore = hierarchyAnalyzeCount();
        SignedIn owner = owner();
        ProjectId projectId = uploadWarehouse(owner, "Warehouse");

        awaitCaughtUp(projectId);
        awaitAnalyzeIncrease(analyzeBefore);
        assertDbEqualsCompute(projectId);

        String createdIri = createClass(owner, projectId, "Carton");
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        OWLDataFactory data = OWLManager.getOWLDataFactory();
        plant(
                projectId,
                List.of(data.getOWLSubClassOfAxiom(
                        data.getOWLClass(IRI.create(createdIri)), data.getOWLClass(IRI.create(HANDLING_UNIT)))));
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        plant(
                projectId,
                List.of(data.getOWLAnnotationAssertionAxiom(
                        data.getRDFSLabel(), IRI.create(createdIri), data.getOWLLiteral("纸箱", "zh"))));
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        plant(
                projectId,
                List.of(data.getOWLObjectPropertyDomainAxiom(
                        data.getOWLObjectProperty(IRI.create(STORED_AT)), data.getOWLClass(IRI.create(PALLET)))));
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        deleteEntities(owner, projectId, List.of(createdIri));
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        long beforeRevert = head(projectId);
        revert(owner, projectId, beforeRevert);
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);
    }

    @Test
    void small_edit_keeps_rebuilt_at_large_edit_full_rebuilds() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = uploadWarehouse(owner, "Threshold");
        awaitCaughtUp(projectId);
        Instant afterInitial = rebuiltAt(projectId);

        OWLDataFactory data = OWLManager.getOWLDataFactory();
        plant(
                projectId,
                List.of(data.getOWLAnnotationAssertionAxiom(
                        data.getRDFSLabel(), IRI.create(PALLET), data.getOWLLiteral("Pallet-extra", "en"))));
        awaitCaughtUp(projectId);
        assertThat(rebuiltAt(projectId)).isEqualTo(afterInitial);
        assertDbEqualsCompute(projectId);

        List<OWLClass> classes = new ArrayList<>();
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            OWLClass cls = data.getOWLClass(IRI.create(WH + "Extra" + i));
            classes.add(cls);
            axioms.add(data.getOWLDeclarationAxiom(cls));
        }
        axioms.add(data.getOWLDisjointClassesAxiom(classes.toArray(OWLClass[]::new)));
        plant(projectId, axioms);
        awaitCaughtUp(projectId);
        assertThat(rebuiltAt(projectId)).isAfter(afterInitial);
        assertDbEqualsCompute(projectId);
    }

    @Test
    void historical_outbox_rows_full_rebuild_then_skip() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = uploadWarehouse(owner, "Backlog");
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        jdbc.sql("delete from outbox_consumers where consumer = ?")
                .param(ProjectionOutboxConsumer.CONSUMER_NAME)
                .update();
        clearProjection(projectId);

        relay.wake();
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);

        long lastId = consumerLastId();
        relay.wake();
        awaitUntil(() -> consumerLastId() >= lastId, 5);
        assertDbEqualsCompute(projectId);
    }

    @Test
    void two_projects_needing_full_rebuild_complete_across_batches() throws Exception {
        SignedIn owner = owner();
        ProjectId first = uploadWarehouse(owner, "One");
        ProjectId second = uploadWarehouse(owner, "Two");

        jdbc.sql("delete from outbox_consumers where consumer = ?")
                .param(ProjectionOutboxConsumer.CONSUMER_NAME)
                .update();
        clearProjection(first);
        clearProjection(second);

        relay.wake();
        awaitCaughtUp(first);
        awaitCaughtUp(second);
        assertDbEqualsCompute(first);
        assertDbEqualsCompute(second);
    }

    @Test
    void runtime_behind_head_throws_retry_later_without_changing_projection() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = uploadWarehouse(owner, "Lag");
        awaitCaughtUp(projectId);
        ProjectionDump before = dump(projectId);
        long head = head(projectId);

        OutboxMessage future = new OutboxMessage(
                9_999_999L, projectId, head + 1, "REVISION_COMMITTED", "{}", Instant.parse("2026-01-15T12:00:00Z"));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> consumer.accept(List.of(future))))
                .isInstanceOf(OutboxRetryLater.class);
        assertThat(dump(projectId)).isEqualTo(before);
    }

    @Test
    void trash_skips_then_restore_and_edit_catch_up() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = uploadWarehouse(owner, "Trash");
        awaitCaughtUp(projectId);

        trash(owner, projectId);
        MvcResult blocked = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", List.of("ShouldFail"), null, List.of(THING)));
        assertThat(blocked.getResponse().getStatus()).isGreaterThanOrEqualTo(400);

        restore(owner, projectId);
        createClass(owner, projectId, "AfterRestore");
        awaitCaughtUp(projectId);
        assertDbEqualsCompute(projectId);
    }

    private void clearProjection(ProjectId projectId) {
        jdbc.sql("delete from projection.project_state where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projection.entities where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projection.labels where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projection.hierarchy_edges where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projection.property_domains where project_id = ?")
                .param(projectId.value())
                .update();
        jdbc.sql("delete from projection.property_ranges where project_id = ?")
                .param(projectId.value())
                .update();
    }

    private void plant(ProjectId projectId, List<OWLAxiom> axioms) {
        var handle = registry.get(projectId);
        Set<OntologyKey> keys = handle.runtime().ontologies().keys();
        assertThat(keys).hasSize(1);
        OntologyKey key = keys.iterator().next();
        List<OntologyChange> changes = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            changes.add(new AddAxiom(key, axiom));
        }
        handle.pipeline().apply(new UserId("owner"), context -> new GeneratedChanges<>(changes, "plant"));
        relay.wake();
    }

    private void assertDbEqualsCompute(ProjectId projectId) {
        var handle = registry.get(projectId);
        ProjectionSnapshot expected = handle.gate().read(() -> ProjectionRows.compute(handle.runtime()));
        ProjectionDump fromDb = dump(projectId);
        ProjectionDump fromCompute = new ProjectionDump(
                head(projectId),
                expected.entities().stream()
                        .map(r -> r.iri() + "|" + r.entityType() + "|" + r.deprecated())
                        .toList(),
                expected.labels().stream()
                        .map(r -> r.iri() + "|" + r.propertyIri() + "|" + r.lang() + "|" + r.value())
                        .toList(),
                expected.hierarchyEdges().stream()
                        .map(r -> r.hierarchy() + "|" + r.childIri() + "|" + r.parentIri())
                        .toList(),
                expected.domains().stream()
                        .map(r -> r.propertyIri() + "|" + r.classIri())
                        .toList(),
                expected.ranges().stream()
                        .map(r -> r.propertyIri() + "|" + r.rangeIri() + "|" + r.rangeKind())
                        .toList());
        // PostgreSQL order by uses a locale collation; Java list order is code-point order — compare
        // as bags so equivalence does not depend on collation.
        assertThat(fromDb.revNo()).isEqualTo(fromCompute.revNo());
        assertThat(fromDb.entities()).containsExactlyInAnyOrderElementsOf(fromCompute.entities());
        assertThat(fromDb.labels()).containsExactlyInAnyOrderElementsOf(fromCompute.labels());
        assertThat(fromDb.edges()).containsExactlyInAnyOrderElementsOf(fromCompute.edges());
        assertThat(fromDb.domains()).containsExactlyInAnyOrderElementsOf(fromCompute.domains());
        assertThat(fromDb.ranges()).containsExactlyInAnyOrderElementsOf(fromCompute.ranges());
    }

    private void awaitCaughtUp(ProjectId projectId) {
        awaitUntil(
                () -> {
                    Optional<Long> rev = writer.projectRevNo(projectId);
                    return rev.isPresent() && rev.get() == head(projectId);
                },
                10);
    }

    private void awaitAnalyzeIncrease(long before) {
        awaitUntil(() -> hierarchyAnalyzeCount() > before, 5);
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

    private long hierarchyAnalyzeCount() {
        return jdbc.sql("""
                        select analyze_count
                        from pg_stat_user_tables
                        where schemaname = 'projection' and relname = 'hierarchy_edges'
                        """).query(Long.class).single();
    }

    private long consumerLastId() {
        return jdbc.sql("select last_id from outbox_consumers where consumer = ?")
                .param(ProjectionOutboxConsumer.CONSUMER_NAME)
                .query(Long.class)
                .optional()
                .orElse(0L);
    }

    private Instant rebuiltAt(ProjectId projectId) {
        return jdbc.sql("select rebuilt_at from projection.project_state where project_id = ?")
                .param(projectId.value())
                .query((rs, rowNum) -> rs.getObject("rebuilt_at", java.time.OffsetDateTime.class)
                        .toInstant())
                .single();
    }

    private long head(ProjectId projectId) {
        return registry.get(projectId).runtime().head().value();
    }

    private ProjectionDump dump(ProjectId projectId) {
        return new ProjectionDump(
                writer.projectRevNo(projectId).orElse(-1L),
                jdbc.sql("""
                                select iri || '|' || entity_type || '|' || deprecated::text
                                from projection.entities where project_id = ?
                                order by iri, entity_type
                                """).param(projectId.value()).query(String.class).list(),
                jdbc.sql("""
                                select iri || '|' || property_iri || '|' || lang || '|' || value
                                from projection.labels where project_id = ?
                                order by iri, property_iri, lang, value
                                """).param(projectId.value()).query(String.class).list(),
                jdbc.sql("""
                                select hierarchy || '|' || child_iri || '|' || parent_iri
                                from projection.hierarchy_edges where project_id = ?
                                order by hierarchy, child_iri, parent_iri
                                """).param(projectId.value()).query(String.class).list(),
                jdbc.sql("""
                                select property_iri || '|' || class_iri
                                from projection.property_domains where project_id = ?
                                order by property_iri, class_iri
                                """).param(projectId.value()).query(String.class).list(),
                jdbc.sql("""
                                select property_iri || '|' || range_iri || '|' || range_kind
                                from projection.property_ranges where project_id = ?
                                order by property_iri, range_iri, range_kind
                                """).param(projectId.value()).query(String.class).list());
    }

    private SignedIn owner() {
        String userId = "u" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of("PROJECT_CREATOR"));
        try {
            return login(userId);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
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

    private void deleteEntities(SignedIn owner, ProjectId projectId, List<String> iris) throws Exception {
        MvcResult deleted = mockMvc.perform(delete("/api/projects/" + projectId + "/entities")
                        .session(owner.session())
                        .cookie(owner.csrf())
                        .header(CSRF_HEADER, owner.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new DeleteEntitiesBody(iris))))
                .andReturn();
        assertThat(deleted.getResponse().getStatus())
                .as(deleted.getResponse().getContentAsString())
                .isEqualTo(200);
        relay.wake();
    }

    private void revert(SignedIn owner, ProjectId projectId, long revision) throws Exception {
        MvcResult result =
                postJson(owner, "/api/projects/" + projectId + "/revisions/" + revision + "/revert", Map.of());
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        relay.wake();
    }

    private void trash(SignedIn owner, ProjectId projectId) throws Exception {
        MvcResult result = postJson(owner, "/api/projects/" + projectId + "/trash", Map.of());
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void restore(SignedIn owner, ProjectId projectId) throws Exception {
        MvcResult result = mockMvc.perform(delete("/api/projects/" + projectId + "/trash")
                        .session(owner.session())
                        .cookie(owner.csrf())
                        .header(CSRF_HEADER, owner.csrf().getValue()))
                .andReturn();
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

    private record ProjectionDump(
            long revNo,
            List<String> entities,
            List<String> labels,
            List<String> edges,
            List<String> domains,
            List<String> ranges) {}

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}

    private record DeleteEntitiesBody(List<String> iris) {}
}
