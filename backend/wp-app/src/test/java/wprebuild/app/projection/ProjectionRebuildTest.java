package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.testsupport.TestOntologies;

/** Full rebuild from the in-memory model into projection tables (P6-10). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ProjectionRebuildTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String PALLET = WH + "Pallet";

    private static final String HANDLING_UNIT = WH + "HandlingUnit";

    private static final String STORED_AT = WH + "storedAt";

    private static final String CODE = WH + "code";

    private static final String LOCATION = WH + "Location";

    private static final String OWL_THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String RDFS_LABEL =
            OWLRDFVocabulary.RDFS_LABEL.getIRI().toString();

    private static final String XSD_STRING = "http://www.w3.org/2001/XMLSchema#string";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    private final ProjectRuntimeRegistry registry;

    private final ProjectionRebuilder rebuilder;

    ProjectionRebuildTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcClient jdbc,
            ProjectRuntimeRegistry registry,
            ProjectionRebuilder rebuilder) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
        this.registry = registry;
        this.rebuilder = rebuilder;
    }

    @Test
    void warehouse_rebuild_writes_entities_labels_hierarchy_and_domains() throws Exception {
        ProjectId projectId = uploadWarehouse();

        rebuilder.rebuild(projectId);

        assertThat(entityTypes(projectId, PALLET)).containsExactly("CLASS");
        assertThat(labelValues(projectId, PALLET, RDFS_LABEL)).containsExactlyInAnyOrder("托盘", "Pallet");
        assertThat(hierarchyParents(projectId, "CLASS", PALLET)).containsExactly(HANDLING_UNIT);

        assertThat(domainClasses(projectId, STORED_AT)).containsExactly(HANDLING_UNIT);
        assertThat(rangeRows(projectId, STORED_AT)).containsExactly(LOCATION + "|CLASS");
        assertThat(domainClasses(projectId, CODE)).containsExactly(LOCATION);
        assertThat(rangeRows(projectId, CODE)).containsExactly(XSD_STRING + "|DATATYPE");

        assertThat(jdbc.sql("""
                        select count(*) from projection.hierarchy_edges
                        where project_id = ? and parent_iri = ?
                        """)
                        .param(projectId.value())
                        .param(OWL_THING)
                        .query(Long.class)
                        .single())
                .isZero();
        assertThat(revNo(projectId)).isEqualTo(head(projectId));
    }

    @Test
    void anonymous_superclass_does_not_create_extra_hierarchy_edges() throws Exception {
        ProjectId projectId = uploadWarehouse();
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        OWLClass special = data.getOWLClass(IRI.create(WH + "Special"));
        OWLClass location = data.getOWLClass(IRI.create(LOCATION));
        var storedAt = data.getOWLObjectProperty(IRI.create(STORED_AT));
        plant(
                projectId,
                List.of(
                        data.getOWLDeclarationAxiom(special),
                        data.getOWLSubClassOfAxiom(special, data.getOWLObjectSomeValuesFrom(storedAt, location))));

        rebuilder.rebuild(projectId);

        assertThat(entityTypes(projectId, WH + "Special")).containsExactly("CLASS");
        assertThat(hierarchyParents(projectId, "CLASS", WH + "Special")).isEmpty();
        assertThat(hierarchyParents(projectId, "CLASS", PALLET)).containsExactly(HANDLING_UNIT);
    }

    @Test
    void rebuild_is_idempotent_and_reflects_later_edits() throws Exception {
        ProjectId projectId = uploadWarehouse();

        rebuilder.rebuild(projectId);
        ProjectionDump first = dump(projectId);

        rebuilder.rebuild(projectId);
        assertThat(dump(projectId)).isEqualTo(first);
        assertThat(revNo(projectId)).isEqualTo(head(projectId));

        OWLDataFactory data = OWLManager.getOWLDataFactory();
        plant(
                projectId,
                List.of(data.getOWLAnnotationAssertionAxiom(
                        data.getOWLDeprecated(), IRI.create(PALLET), data.getOWLLiteral(true))));

        rebuilder.rebuild(projectId);
        ProjectionDump afterEdit = dump(projectId);
        assertThat(afterEdit).isNotEqualTo(first);
        assertThat(jdbc.sql("""
                        select deprecated from projection.entities
                        where project_id = ? and iri = ? and entity_type = 'CLASS'
                        """)
                        .param(projectId.value())
                        .param(PALLET)
                        .query(Boolean.class)
                        .single())
                .isTrue();
        assertThat(revNo(projectId)).isEqualTo(head(projectId));
        assertThat(revNo(projectId)).isGreaterThan(first.revNo());
    }

    @Test
    void compute_marks_deprecated_entities() throws Exception {
        ProjectId projectId = uploadWarehouse();
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        plant(
                projectId,
                List.of(data.getOWLAnnotationAssertionAxiom(
                        data.getOWLDeprecated(), IRI.create(PALLET), data.getOWLLiteral(true))));

        var snapshot = compute(projectId);

        assertThat(snapshot.entities()).anySatisfy(row -> {
            assertThat(row.iri()).isEqualTo(PALLET);
            assertThat(row.entityType()).isEqualTo("CLASS");
            assertThat(row.deprecated()).isTrue();
        });
    }

    @Test
    void compute_keeps_multilingual_and_skos_labels() throws Exception {
        ProjectId projectId = uploadWarehouse();
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subject = IRI.create(PALLET);
        plant(
                projectId,
                List.of(
                        data.getOWLAnnotationAssertionAxiom(
                                data.getOWLAnnotationProperty(SKOSVocabulary.PREFLABEL.getIRI()),
                                subject,
                                data.getOWLLiteral("Pallet preferred", "en")),
                        data.getOWLAnnotationAssertionAxiom(
                                data.getOWLAnnotationProperty(SKOSVocabulary.ALTLABEL.getIRI()),
                                subject,
                                data.getOWLLiteral("栈板", "zh"))));

        var snapshot = compute(projectId);

        assertThat(snapshot.labels())
                .anySatisfy(row -> {
                    assertThat(row.iri()).isEqualTo(PALLET);
                    assertThat(row.propertyIri())
                            .isEqualTo(SKOSVocabulary.PREFLABEL.getIRI().toString());
                    assertThat(row.lang()).isEqualTo("en");
                    assertThat(row.value()).isEqualTo("Pallet preferred");
                })
                .anySatisfy(row -> {
                    assertThat(row.iri()).isEqualTo(PALLET);
                    assertThat(row.propertyIri())
                            .isEqualTo(SKOSVocabulary.ALTLABEL.getIRI().toString());
                    assertThat(row.lang()).isEqualTo("zh");
                    assertThat(row.value()).isEqualTo("栈板");
                })
                .anySatisfy(row -> {
                    assertThat(row.iri()).isEqualTo(PALLET);
                    assertThat(row.propertyIri()).isEqualTo(RDFS_LABEL);
                    assertThat(row.lang()).isEqualTo("zh");
                    assertThat(row.value()).isEqualTo("托盘");
                });
    }

    @Test
    void compute_skips_non_literal_label_values() throws Exception {
        ProjectId projectId = uploadWarehouse();
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subject = IRI.create(PALLET);
        IRI other = IRI.create(WH + "Other");
        plant(projectId, List.of(data.getOWLAnnotationAssertionAxiom(data.getRDFSLabel(), subject, other)));

        var snapshot = compute(projectId);

        assertThat(snapshot.labels())
                .filteredOn(row -> row.iri().equals(PALLET) && row.propertyIri().equals(RDFS_LABEL))
                .noneMatch(row -> row.value().equals(other.toString()));
        assertThat(snapshot.labels())
                .filteredOn(row -> row.iri().equals(PALLET) && row.propertyIri().equals(RDFS_LABEL))
                .extracting(ProjectionSnapshot.LabelRow::value)
                .contains("托盘", "Pallet");
    }

    private ProjectionSnapshot compute(ProjectId projectId) {
        var handle = registry.get(projectId);
        return handle.gate().read(() -> ProjectionRows.compute(handle.runtime()));
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
    }

    private ProjectId uploadWarehouse() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        Path file = TestOntologies.dir()
                .resolve("warehouse-sample.ttl")
                .toAbsolutePath()
                .normalize();
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
        MvcResult created = mockMvc.perform(multipart("/api/projects/upload")
                        .file(part)
                        .param("name", "Warehouse")
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

    private void account(String userId, String... appRoles) {
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
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

    private long head(ProjectId projectId) {
        return registry.get(projectId).runtime().head().value();
    }

    private long revNo(ProjectId projectId) {
        return jdbc.sql("select rev_no from projection.project_state where project_id = ?")
                .param(projectId.value())
                .query(Long.class)
                .single();
    }

    private List<String> entityTypes(ProjectId projectId, String iri) {
        return jdbc.sql("""
                        select entity_type from projection.entities
                        where project_id = ? and iri = ?
                        order by entity_type
                        """)
                .param(projectId.value())
                .param(iri)
                .query(String.class)
                .list();
    }

    private List<String> labelValues(ProjectId projectId, String iri, String propertyIri) {
        return jdbc.sql("""
                        select value from projection.labels
                        where project_id = ? and iri = ? and property_iri = ?
                        order by value
                        """)
                .param(projectId.value())
                .param(iri)
                .param(propertyIri)
                .query(String.class)
                .list();
    }

    private List<String> hierarchyParents(ProjectId projectId, String hierarchy, String childIri) {
        return jdbc.sql("""
                        select parent_iri from projection.hierarchy_edges
                        where project_id = ? and hierarchy = ? and child_iri = ?
                        order by parent_iri
                        """)
                .param(projectId.value())
                .param(hierarchy)
                .param(childIri)
                .query(String.class)
                .list();
    }

    private List<String> domainClasses(ProjectId projectId, String propertyIri) {
        return jdbc.sql("""
                        select class_iri from projection.property_domains
                        where project_id = ? and property_iri = ?
                        order by class_iri
                        """)
                .param(projectId.value())
                .param(propertyIri)
                .query(String.class)
                .list();
    }

    private List<String> rangeRows(ProjectId projectId, String propertyIri) {
        return jdbc.sql("""
                        select range_iri || '|' || range_kind from projection.property_ranges
                        where project_id = ? and property_iri = ?
                        order by range_iri, range_kind
                        """)
                .param(projectId.value())
                .param(propertyIri)
                .query(String.class)
                .list();
    }

    private ProjectionDump dump(ProjectId projectId) {
        return new ProjectionDump(
                revNo(projectId),
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

    private record ProjectionDump(
            long revNo,
            List<String> entities,
            List<String> labels,
            List<String> edges,
            List<String> domains,
            List<String> ranges) {}

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}
}
