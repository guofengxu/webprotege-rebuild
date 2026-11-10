package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLObjectPropertyAssertionAxiom;
import org.semanticweb.owlapi.model.OWLSameIndividualAxiom;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.ontology.frame.AnnotationValue;
import wprebuild.app.ontology.frame.DataPropertyAssertion;
import wprebuild.app.ontology.frame.IndividualFrameAxioms;
import wprebuild.app.ontology.frame.LiteralValue;
import wprebuild.app.ontology.frame.NamedIndividualFrame;
import wprebuild.app.ontology.frame.ObjectPropertyAssertion;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.GeneratedChanges;

/** Individual frames: read, property-assertion edit, conflict, and direct-individual paging (P3-18). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class IndividualFrameTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String NS = "http://example.org/ind#";

    private static final String BOX = NS + "box";

    private static final String CRATE = NS + "crate";

    private static final String DRUM = NS + "drum";

    private static final String LID = NS + "lid";

    private static final String PALLET = NS + "pallet";

    private static final String CONTAINER = NS + "container";

    private static final String PART = NS + "part";

    private static final String HAS_PART = NS + "hasPart";

    private static final String CODE = NS + "code";

    private static final String COMMENT = OWLRDFVocabulary.RDFS_COMMENT.getIRI().toString();

    private static final String XSD_STRING = OWL2Datatype.XSD_STRING.getIRI().toString();

    private static final String LANG_STRING =
            OWL2Datatype.RDF_LANG_STRING.getIRI().toString();

    private final MockMvc mockMvc;

    private final tools.jackson.databind.json.JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    IndividualFrameTest(
            MockMvc mockMvc,
            tools.jackson.databind.json.JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectRuntimeRegistry registry,
            RevisionLog revisions) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.registry = registry;
        this.revisions = revisions;
    }

    @Test
    void to_axioms_converts_editable_fields_and_ignores_other_axioms() {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        AnnotationValue annotation = new AnnotationValue(
                entity(COMMENT, "ANNOTATION_PROPERTY", "rdfs:comment"),
                new LiteralValue("sealed", LANG_STRING, Optional.of("en")),
                Optional.of("en"));
        NamedIndividualFrame frame = new NamedIndividualFrame(
                entity(BOX, "NAMED_INDIVIDUAL", "box"),
                List.of(annotation),
                List.of(entity(PALLET, "CLASS", "pallet")),
                List.of(new ObjectPropertyAssertion(
                        entity(HAS_PART, "OBJECT_PROPERTY", "hasPart"), entity(LID, "NAMED_INDIVIDUAL", "lid"))),
                List.of(new DataPropertyAssertion(
                        entity(CODE, "DATA_PROPERTY", "code"), new LiteralValue("A1", XSD_STRING, Optional.empty()))),
                List.of(entity(CRATE, "NAMED_INDIVIDUAL", "crate")),
                List.of("unread"));
        Set<OWLAxiom> axioms = IndividualFrameAxioms.toAxioms(frame);
        assertThat(axioms).hasSize(5);
        assertThat(axioms).anyMatch(OWLClassAssertionAxiom.class::isInstance);
        assertThat(axioms).anyMatch(OWLObjectPropertyAssertionAxiom.class::isInstance);
        assertThat(axioms).anyMatch(OWLDataPropertyAssertionAxiom.class::isInstance);
        assertThat(axioms).anyMatch(OWLSameIndividualAxiom.class::isInstance);
        assertThat(axioms).anyMatch(OWLAnnotationAssertionAxiom.class::isInstance);
        assertThat(axioms).noneMatch(axiom -> axiom.toString().contains("unread"));
        assertThat(axioms)
                .contains(data.getOWLClassAssertionAxiom(
                        data.getOWLClass(IRI.create(PALLET)), data.getOWLNamedIndividual(IRI.create(BOX))));
        assertThat(axioms)
                .contains(data.getOWLDataPropertyAssertionAxiom(
                        data.getOWLDataProperty(IRI.create(CODE)),
                        data.getOWLNamedIndividual(IRI.create(BOX)),
                        data.getOWLLiteral("A1", data.getOWLDatatype(IRI.create(XSD_STRING)))));

        NamedIndividualFrame badType = new NamedIndividualFrame(
                frame.subject(),
                List.of(),
                List.of(entity(BOX, "NAMED_INDIVIDUAL", "box")),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        assertThatThrownBy(() -> IndividualFrameAxioms.toAxioms(badType))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CLASS");
        NamedIndividualFrame self = new NamedIndividualFrame(
                frame.subject(), List.of(), List.of(), List.of(), List.of(), List.of(frame.subject()), List.of());
        assertThatThrownBy(() -> IndividualFrameAxioms.toAxioms(self))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("same individual");
    }

    @Test
    void individual_frame_reads_one_edit_and_rejects_a_stale_update() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        OWLAxiom different = plant(projectId);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var box = data.getOWLNamedIndividual(IRI.create(BOX));
        var lid = data.getOWLNamedIndividual(IRI.create(LID));
        var drum = data.getOWLNamedIndividual(IRI.create(DRUM));
        var code = data.getOWLDataProperty(IRI.create(CODE));
        OWLAxiom original = data.getOWLDataPropertyAssertionAxiom(code, box, data.getOWLLiteral("A1"));
        OWLAxiom editedAxiom =
                data.getOWLDataPropertyAssertionAxiom(code, box, data.getOWLLiteral("A2", data.getStringOWLDatatype()));
        OWLAxiom sameGroup = data.getOWLSameIndividualAxiom(Set.of(box, lid, drum));

        JsonNode frame = frame(owner, projectId, BOX);
        assertNode(frame.path("subject"), BOX, "NAMED_INDIVIDUAL", "box", false, false);
        assertThat(lexicals(frame.path("annotations"))).containsExactly("sealed");
        assertNode(frame.path("types").get(0), PALLET, "CLASS", "pallet", false, false);
        assertThat(names(frame.path("types"))).containsExactly("pallet");
        assertNode(
                frame.path("objectPropertyAssertions").get(0).path("property"),
                HAS_PART,
                "OBJECT_PROPERTY",
                "hasPart",
                false,
                false);
        assertNode(
                frame.path("objectPropertyAssertions").get(0).path("individual"),
                LID,
                "NAMED_INDIVIDUAL",
                "lid",
                false,
                false);
        assertThat(frame.path("dataPropertyAssertions")
                        .get(0)
                        .path("value")
                        .path("lexical")
                        .asString())
                .isEqualTo("A1");
        assertThat(names(frame.path("sameIndividuals"))).containsExactly("crate");
        assertThat(texts(frame.path("otherAxioms"))).hasSize(3);
        assertThat(frame(editor, projectId, BOX)).isEqualTo(frame);

        MvcResult missing = mockMvc.perform(get("/api/projects/{id}/frames/individual", projectId.toString())
                        .param("iri", NS + "missing")
                        .session(owner.session()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        MvcResult forbiddenGet = mockMvc.perform(get("/api/projects/{id}/frames/individual", projectId.toString())
                        .param("iri", BOX)
                        .session(stranger.session()))
                .andReturn();
        assertThat(forbiddenGet.getResponse().getStatus()).isEqualTo(403);

        ObjectNode edited = copy(frame);
        ((ObjectNode) edited.path("dataPropertyAssertions").get(0).path("value")).put("lexical", "A2");
        MvcResult updated = putFrame(owner, projectId, frame, edited);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(updated);
        assertThat(body.path("revision").asLong()).isEqualTo(3);
        assertThat(body.path("frame")
                        .path("dataPropertyAssertions")
                        .get(0)
                        .path("value")
                        .path("lexical")
                        .asString())
                .isEqualTo("A2");
        assertThat(names(body.path("frame").path("types"))).containsExactly("pallet");
        assertThat(names(body.path("frame").path("sameIndividuals"))).containsExactly("crate");
        assertThat(texts(body.path("frame").path("otherAxioms"))).hasSize(3);
        assertThat(containsAxiom(projectId, different)).isTrue();
        assertThat(containsAxiom(projectId, sameGroup)).isTrue();
        assertThat(containsAxiom(projectId, original)).isFalse();
        assertThat(containsAxiom(projectId, editedAxiom)).isTrue();
        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.description()).isEqualTo("Edited named individual box");
        assertThat(revision.changes()).hasSize(2);
        assertThat(revision.changes())
                .noneMatch(change -> change.axiom().orElseThrow() instanceof OWLSameIndividualAxiom);

        ObjectNode stale = copy(frame);
        ((ArrayNode) stale.get("types")).add(entityNode(PART, "CLASS", "part"));
        MvcResult conflict = putFrame(editor, projectId, frame, stale);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).path("code").asString()).isEqualTo("FRAME_CHANGED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));
        assertThat(names(frame(owner, projectId, BOX).path("types"))).containsExactly("pallet");

        MvcResult forbidden = putFrame(viewer, projectId, frame, edited);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void individuals_by_type_are_direct_and_paged() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        plant(projectId);

        JsonNode first = individuals(owner, projectId, PALLET, 0, 2);
        assertThat(first.path("page").asInt()).isEqualTo(0);
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("total").asInt()).isEqualTo(3);
        assertThat(names(first.path("individuals"))).containsExactly("box", "crate");
        assertNode(first.path("individuals").get(0), BOX, "NAMED_INDIVIDUAL", "box", false, false);

        JsonNode second = individuals(owner, projectId, PALLET, 1, 2);
        assertThat(names(second.path("individuals"))).containsExactly("drum");
        assertThat(second.path("total").asInt()).isEqualTo(3);

        JsonNode rest = individuals(owner, projectId, PALLET, 2, 2);
        assertThat(rest.path("individuals")).isEmpty();
        assertThat(rest.path("total").asInt()).isEqualTo(3);

        assertThat(names(individuals(owner, projectId, CONTAINER, 0, 50).path("individuals")))
                .containsExactly("lid");
        assertThat(individuals(owner, projectId, PART, 0, 50).path("total").asInt())
                .isZero();

        JsonNode defaults = individuals(owner, projectId, PALLET, null, null);
        assertThat(defaults.path("page").asInt()).isZero();
        assertThat(defaults.path("size").asInt()).isEqualTo(50);
        assertThat(names(defaults.path("individuals"))).containsExactly("box", "crate", "drum");

        MvcResult missing = mockMvc.perform(get("/api/projects/{id}/individuals", projectId.toString())
                        .param("type", NS + "missing")
                        .session(owner.session()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        MvcResult forbidden = mockMvc.perform(get("/api/projects/{id}/individuals", projectId.toString())
                        .param("type", PALLET)
                        .session(stranger.session()))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        MvcResult invalid = mockMvc.perform(get("/api/projects/{id}/individuals", projectId.toString())
                        .param("type", PALLET)
                        .param("size", "0")
                        .session(owner.session()))
                .andReturn();
        assertThat(invalid.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(invalid).path("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    private OWLAxiom plant(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var pallet = data.getOWLClass(IRI.create(PALLET));
        var container = data.getOWLClass(IRI.create(CONTAINER));
        var part = data.getOWLClass(IRI.create(PART));
        var box = data.getOWLNamedIndividual(IRI.create(BOX));
        var crate = data.getOWLNamedIndividual(IRI.create(CRATE));
        var drum = data.getOWLNamedIndividual(IRI.create(DRUM));
        var lid = data.getOWLNamedIndividual(IRI.create(LID));
        var hasPart = data.getOWLObjectProperty(IRI.create(HAS_PART));
        var code = data.getOWLDataProperty(IRI.create(CODE));
        var comment = data.getOWLAnnotationProperty(IRI.create(COMMENT));
        OWLAxiom different = data.getOWLDifferentIndividualsAxiom(box, drum);
        plantAxioms(
                projectId,
                List.of(
                        data.getOWLSubClassOfAxiom(pallet, container),
                        data.getOWLClassAssertionAxiom(pallet, box),
                        data.getOWLClassAssertionAxiom(pallet, crate),
                        data.getOWLClassAssertionAxiom(pallet, drum),
                        data.getOWLClassAssertionAxiom(container, lid),
                        data.getOWLClassAssertionAxiom(pallet, data.getOWLAnonymousIndividual()),
                        data.getOWLClassAssertionAxiom(data.getOWLObjectSomeValuesFrom(hasPart, part), box),
                        data.getOWLObjectPropertyAssertionAxiom(hasPart, box, lid),
                        data.getOWLDataPropertyAssertionAxiom(code, box, data.getOWLLiteral("A1")),
                        data.getOWLAnnotationAssertionAxiom(comment, box.getIRI(), data.getOWLLiteral("sealed")),
                        data.getOWLSameIndividualAxiom(box, crate),
                        data.getOWLSameIndividualAxiom(Set.of(box, lid, drum)),
                        different));
        return different;
    }

    private void plantAxioms(ProjectId projectId, List<OWLAxiom> axioms) {
        var handle = registry.get(projectId);
        Set<OntologyKey> keys = handle.runtime().ontologies().keys();
        assertThat(keys).hasSize(1);
        OntologyKey key = keys.iterator().next();
        List<OntologyChange> changes = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            changes.add(new AddAxiom(key, axiom));
        }
        handle.pipeline().apply(new UserId("owner"), context -> new GeneratedChanges<>(changes, "frame"));
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(2));
    }

    private boolean containsAxiom(ProjectId projectId, OWLAxiom axiom) {
        var handle = registry.get(projectId);
        return handle.gate()
                .read(() -> handle.runtime().ontologies().keys().stream()
                        .anyMatch(key -> handle.runtime().ontologies().containsAxiom(key, axiom)));
    }

    private JsonNode frame(SignedIn user, ProjectId projectId, String iri) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/frames/individual", projectId.toString())
                        .param("iri", iri)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode individuals(SignedIn user, ProjectId projectId, String type, Integer page, Integer size)
            throws Exception {
        var request =
                get("/api/projects/{id}/individuals", projectId.toString()).param("type", type);
        if (page != null) {
            request.param("page", page.toString());
        }
        if (size != null) {
            request.param("size", size.toString());
        }
        MvcResult result = mockMvc.perform(request.session(user.session())).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult putFrame(SignedIn user, ProjectId projectId, JsonNode from, JsonNode to) throws Exception {
        return send(
                user,
                put("/api/projects/{id}/frames/individual", projectId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateBody(from, to))));
    }

    private ObjectNode copy(JsonNode node) throws Exception {
        return (ObjectNode) json.readTree(json.writeValueAsString(node));
    }

    private ObjectNode entityNode(String iri, String type, String name) {
        ObjectNode node = json.createObjectNode();
        node.put("iri", iri);
        node.put("entityType", type);
        node.put("displayName", name);
        node.put("hasChildren", false);
        node.put("deprecated", false);
        return node;
    }

    private static EntityNode entity(String iri, String type, String name) {
        return new EntityNode(iri, type, name, false, false);
    }

    private static void assertNode(
            JsonNode node, String iri, String type, String name, boolean hasChildren, boolean deprecated) {
        assertThat(node.path("iri").asString()).isEqualTo(iri);
        assertThat(node.path("entityType").asString()).isEqualTo(type);
        assertThat(node.path("displayName").asString()).isEqualTo(name);
        assertThat(node.path("hasChildren").asBoolean()).isEqualTo(hasChildren);
        assertThat(node.path("deprecated").asBoolean()).isEqualTo(deprecated);
    }

    private static List<String> lexicals(JsonNode annotations) {
        List<String> values = new ArrayList<>();
        annotations.forEach(
                node -> values.add(node.path("value").path("lexical").asString()));
        return values;
    }

    private static List<String> names(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<String> texts(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asString()));
        return values;
    }

    private RevisionNumber head(ProjectId projectId) {
        RevisionNumber stored = revisions.head(projectId);
        assertThat(registry.get(projectId).runtime().head()).isEqualTo(stored);
        return stored;
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = send(
                owner,
                post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateProject("Warehouse", "Stock", null))));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private MvcResult send(
            SignedIn user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
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

    private record UpdateBody(JsonNode from, JsonNode to) {}
}
