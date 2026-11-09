package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.GeneratedChanges;

/** Class frame read: annotations, parents, the three restriction kinds, and one unread axiom (P3-15). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ClassFrameReadTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String NS = "http://example.org/frame#";

    private static final String LABEL = OWLRDFVocabulary.RDFS_LABEL.getIRI().toString();

    private static final String LANG_STRING =
            OWL2Datatype.RDF_LANG_STRING.getIRI().toString();

    private static final String XSD_STRING = OWL2Datatype.XSD_STRING.getIRI().toString();

    private static final String XSD_INTEGER = OWL2Datatype.XSD_INTEGER.getIRI().toString();

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    ClassFrameReadTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectRuntimeRegistry registry) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.registry = registry;
    }

    @Test
    void class_frame_lists_annotations_parents_property_values_and_one_other_axiom() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        addFrame(projectId);

        JsonNode frame = frame(owner, projectId, NS + "Pallet");
        assertNode(frame.path("subject"), NS + "Pallet", "CLASS", "Pallet", true, false);

        JsonNode annotations = frame.path("annotations");
        assertThat(annotations).hasSize(2);
        assertAnnotation(annotations.get(0), "en", "Pallet");
        assertAnnotation(annotations.get(1), "zh", "托盘");

        JsonNode parents = frame.path("parents");
        assertThat(parents).hasSize(2);
        assertNode(parents.get(0), NS + "Aisle", "CLASS", "Aisle", true, false);
        assertNode(parents.get(1), NS + "Zone", "CLASS", "Zone", true, false);

        JsonNode values = frame.path("propertyValues");
        assertThat(values).hasSize(4);
        assertSome(values.get(0));
        assertDataHasValue(values.get(1));
        assertObjectHasValue(values.get(2));
        assertDataSome(values.get(3));

        assertThat(texts(frame.path("otherAxioms"))).containsExactly("Pallet SubClassOf storedAt only Location");

        assertThat(frame(viewer, projectId, NS + "Pallet")
                        .path("subject")
                        .path("iri")
                        .asString())
                .isEqualTo(NS + "Pallet");

        MvcResult forbidden = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", NS + "Pallet")
                        .session(stranger.session()))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MvcResult anonymous = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", NS + "Pallet"))
                .andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);

        MvcResult missing = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", NS + "Missing")
                        .session(owner.session()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(missing).path("code").asString()).isEqualTo("NOT_FOUND");

        MvcResult relative = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", "Pallet")
                        .session(owner.session()))
                .andReturn();
        assertThat(relative.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(relative).path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private void assertAnnotation(JsonNode annotation, String lang, String lexical) {
        assertNode(annotation.path("property"), LABEL, "ANNOTATION_PROPERTY", "rdfs:label", false, false);
        assertThat(annotation.path("lang").asString()).isEqualTo(lang);
        JsonNode value = annotation.path("value");
        assertThat(value.path("type").asString()).isEqualTo("literal");
        assertThat(value.path("lexical").asString()).isEqualTo(lexical);
        assertThat(value.path("datatype").asString()).isEqualTo(LANG_STRING);
        assertThat(value.path("lang").asString()).isEqualTo(lang);
    }

    private void assertSome(JsonNode value) {
        assertThat(value.path("kind").asString()).isEqualTo("SOME");
        assertNode(value.path("property"), NS + "hasPart", "OBJECT_PROPERTY", "hasPart", false, false);
        JsonNode filler = value.path("filler");
        assertThat(filler.path("type").asString()).isEqualTo("iri");
        assertNode(filler.path("entity"), NS + "Part", "CLASS", "Part", false, false);
    }

    private void assertObjectHasValue(JsonNode value) {
        assertThat(value.path("kind").asString()).isEqualTo("HAS_VALUE");
        assertNode(value.path("property"), NS + "hasStatus", "OBJECT_PROPERTY", "hasStatus", false, false);
        JsonNode filler = value.path("filler");
        assertThat(filler.path("type").asString()).isEqualTo("iri");
        assertNode(filler.path("entity"), NS + "Active", "NAMED_INDIVIDUAL", "Active", false, false);
    }

    private void assertDataHasValue(JsonNode value) {
        assertThat(value.path("kind").asString()).isEqualTo("HAS_VALUE");
        assertNode(value.path("property"), NS + "code", "DATA_PROPERTY", "code", false, false);
        JsonNode filler = value.path("filler");
        assertThat(filler.path("type").asString()).isEqualTo("literal");
        assertThat(filler.path("lexical").asString()).isEqualTo("P-1");
        assertThat(filler.path("datatype").asString()).isEqualTo(XSD_STRING);
        assertThat(filler.path("lang").isMissingNode()).isTrue();
    }

    private void assertDataSome(JsonNode value) {
        assertThat(value.path("kind").asString()).isEqualTo("DATA_SOME");
        assertNode(value.path("property"), NS + "quantity", "DATA_PROPERTY", "quantity", false, false);
        JsonNode filler = value.path("filler");
        assertThat(filler.path("type").asString()).isEqualTo("iri");
        assertNode(filler.path("entity"), XSD_INTEGER, "DATATYPE", "xsd:integer", false, false);
    }

    private void addFrame(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var pallet = data.getOWLClass(IRI.create(NS + "Pallet"));
        var aisle = data.getOWLClass(IRI.create(NS + "Aisle"));
        var zone = data.getOWLClass(IRI.create(NS + "Zone"));
        var part = data.getOWLClass(IRI.create(NS + "Part"));
        var location = data.getOWLClass(IRI.create(NS + "Location"));
        var dock = data.getOWLClass(IRI.create(NS + "Dock"));
        var hasPart = data.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        var hasStatus = data.getOWLObjectProperty(IRI.create(NS + "hasStatus"));
        var storedAt = data.getOWLObjectProperty(IRI.create(NS + "storedAt"));
        var code = data.getOWLDataProperty(IRI.create(NS + "code"));
        var quantity = data.getOWLDataProperty(IRI.create(NS + "quantity"));
        var active = data.getOWLNamedIndividual(IRI.create(NS + "Active"));
        var label = data.getRDFSLabel();
        List<OWLAxiom> axioms = List.of(
                data.getOWLAnnotationAssertionAxiom(label, pallet.getIRI(), data.getOWLLiteral("Pallet", "en")),
                data.getOWLAnnotationAssertionAxiom(label, pallet.getIRI(), data.getOWLLiteral("托盘", "zh")),
                data.getOWLSubClassOfAxiom(pallet, aisle),
                data.getOWLSubClassOfAxiom(pallet, zone),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectSomeValuesFrom(hasPart, part)),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectHasValue(hasStatus, active)),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLDataHasValue(code, data.getOWLLiteral("P-1"))),
                data.getOWLSubClassOfAxiom(
                        pallet, data.getOWLDataSomeValuesFrom(quantity, data.getIntegerOWLDatatype())),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectAllValuesFrom(storedAt, location)),
                data.getOWLSubClassOfAxiom(dock, pallet));
        var handle = registry.get(projectId);
        Set<OntologyKey> keys = handle.runtime().ontologies().keys();
        assertThat(keys).hasSize(1);
        OntologyKey key = keys.iterator().next();
        List<OntologyChange> changes = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            changes.add(new AddAxiom(key, axiom));
        }
        handle.pipeline().apply(new UserId("owner"), context -> new GeneratedChanges<>(changes, "frame"));
    }

    private JsonNode frame(SignedIn user, ProjectId projectId, String iri) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", iri)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        return read(result);
    }

    private static void assertNode(
            JsonNode node, String iri, String type, String name, boolean hasChildren, boolean deprecated) {
        assertThat(node.path("iri").asString()).isEqualTo(iri);
        assertThat(node.path("entityType").asString()).isEqualTo(type);
        assertThat(node.path("displayName").asString()).isEqualTo(name);
        assertThat(node.path("hasChildren").asBoolean()).isEqualTo(hasChildren);
        assertThat(node.path("deprecated").asBoolean()).isEqualTo(deprecated);
    }

    private static List<String> texts(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asString()));
        return values;
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects")
                        .session(owner.session())
                        .cookie(owner.csrf())
                        .header(CSRF_HEADER, owner.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateProject("Warehouse", "Stock", null))))
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

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}
}
