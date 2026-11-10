package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLLiteral;
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
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.GeneratedChanges;

/** Class frame update: minimal axiom diff, hierarchy, optimistic conflict, and untouched axioms (P3-16). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ClassFrameUpdateTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String NS = "http://example.org/frame#";

    private static final String PALLET = NS + "Pallet";

    private static final String ZONE = NS + "Zone";

    private static final String OTHER = "Pallet SubClassOf storedAt only Location";

    private final MockMvc mockMvc;

    private final tools.jackson.databind.json.JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    ClassFrameUpdateTest(
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
    void chinese_label_edit_replaces_one_axiom_and_keeps_other_axioms() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = createProject(owner);
        plant(projectId);
        JsonNode from = frame(owner, projectId);
        ObjectNode to = copy(from);
        label(to, "zh").put("lexical", "栈板");

        MvcResult updated = putFrame(owner, projectId, from, to);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(updated);
        assertThat(body.path("revision").asLong()).isEqualTo(3);
        assertThat(label(body.path("frame"), "zh").path("lexical").asString()).isEqualTo("栈板");
        assertThat(label(body.path("frame"), "en").path("lexical").asString()).isEqualTo("Pallet");
        assertThat(body.path("frame").path("propertyValues")).hasSize(4);
        assertThat(texts(body.path("frame").path("otherAxioms"))).containsExactly(OTHER);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));

        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.description()).isEqualTo("Edited class Pallet");
        assertThat(revision.changes()).hasSize(2);
        assertThat(revision.changes().get(0)).isInstanceOf(RemoveAxiom.class);
        assertThat(revision.changes().get(1)).isInstanceOf(AddAxiom.class);
        assertThat(lexical(revision.changes().get(0).axiom().orElseThrow())).isEqualTo("托盘");
        assertThat(lexical(revision.changes().get(1).axiom().orElseThrow())).isEqualTo("栈板");
        assertThat(containsOtherAxiom(projectId)).isTrue();
    }

    @Test
    void label_edit_removes_the_stored_axiom_when_it_has_an_axiom_annotation() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = createProject(owner);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var pallet = data.getOWLClass(IRI.create(PALLET));
        OWLAxiom annotated = data.getOWLAnnotationAssertionAxiom(
                        data.getRDFSLabel(), pallet.getIRI(), data.getOWLLiteral("托盘", "zh"))
                .getAnnotatedAxiom(Set.of(data.getRDFSComment(data.getOWLLiteral("note"))));
        plantAxioms(projectId, List.of(data.getOWLDeclarationAxiom(pallet), annotated));

        JsonNode from = frame(owner, projectId);
        ObjectNode to = copy(from);
        label(to, "zh").put("lexical", "栈板");
        MvcResult updated = putFrame(owner, projectId, from, to);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);

        Revision revision = revisions.get(projectId, RevisionNumber.of(3)).orElseThrow();
        assertThat(revision.changes()).hasSize(2);
        assertThat(((RemoveAxiom) revision.changes().get(0)).owlAxiom()).isEqualTo(annotated);
        assertThat(containsAxiom(projectId, annotated)).isFalse();
        assertThat(label(read(updated).path("frame"), "zh").path("lexical").asString())
                .isEqualTo("栈板");
    }

    @Test
    void added_parent_appears_in_the_class_hierarchy() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = createProject(owner);
        plant(projectId);
        JsonNode before = hierarchy(owner, projectId, ZONE);
        assertThat(names(before)).isEmpty();

        JsonNode from = frame(owner, projectId);
        ObjectNode to = copy(from);
        ((ArrayNode) to.get("parents")).add(classNode(ZONE, "Zone"));

        MvcResult updated = putFrame(owner, projectId, from, to);
        assertThat(updated.getResponse().getStatus())
                .as(updated.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(names(read(updated).path("frame").path("parents"))).containsExactly("Aisle", "Zone");
        assertThat(names(hierarchy(owner, projectId, ZONE))).containsExactly("Pallet");
        assertThat(containsOtherAxiom(projectId)).isTrue();
    }

    @Test
    void second_update_from_the_same_frame_is_a_conflict() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        account("viewer");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        SignedIn viewer = login("viewer");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        plant(projectId);
        JsonNode from = frame(owner, projectId);
        assertThat(frame(editor, projectId)).isEqualTo(from);

        ObjectNode labelEdit = copy(from);
        label(labelEdit, "zh").put("lexical", "栈板");
        MvcResult first = putFrame(owner, projectId, from, labelEdit);
        assertThat(first.getResponse().getStatus())
                .as(first.getResponse().getContentAsString())
                .isEqualTo(200);

        ObjectNode parentEdit = copy(from);
        ((ArrayNode) parentEdit.get("parents")).add(classNode(ZONE, "Zone"));
        MvcResult conflict = putFrame(editor, projectId, from, parentEdit);
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(conflict).path("code").asString()).isEqualTo("FRAME_CHANGED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));
        assertThat(names(hierarchy(owner, projectId, ZONE))).isEmpty();
        assertThat(label(frame(owner, projectId), "zh").path("lexical").asString())
                .isEqualTo("栈板");

        MvcResult forbidden = putFrame(viewer, projectId, from, labelEdit);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    private void plant(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var pallet = data.getOWLClass(IRI.create(PALLET));
        var aisle = data.getOWLClass(IRI.create(NS + "Aisle"));
        var zone = data.getOWLClass(IRI.create(ZONE));
        var part = data.getOWLClass(IRI.create(NS + "Part"));
        var location = data.getOWLClass(IRI.create(NS + "Location"));
        var hasPart = data.getOWLObjectProperty(IRI.create(NS + "hasPart"));
        var hasStatus = data.getOWLObjectProperty(IRI.create(NS + "hasStatus"));
        var storedAt = data.getOWLObjectProperty(IRI.create(NS + "storedAt"));
        var code = data.getOWLDataProperty(IRI.create(NS + "code"));
        var quantity = data.getOWLDataProperty(IRI.create(NS + "quantity"));
        var active = data.getOWLNamedIndividual(IRI.create(NS + "Active"));
        var label = data.getRDFSLabel();
        List<OWLAxiom> axioms = List.of(
                data.getOWLDeclarationAxiom(zone),
                data.getOWLAnnotationAssertionAxiom(label, pallet.getIRI(), data.getOWLLiteral("Pallet", "en")),
                data.getOWLAnnotationAssertionAxiom(label, pallet.getIRI(), data.getOWLLiteral("托盘", "zh")),
                data.getOWLSubClassOfAxiom(pallet, aisle),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectSomeValuesFrom(hasPart, part)),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectHasValue(hasStatus, active)),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLDataHasValue(code, data.getOWLLiteral("P-1"))),
                data.getOWLSubClassOfAxiom(
                        pallet, data.getOWLDataSomeValuesFrom(quantity, data.getIntegerOWLDatatype())),
                data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectAllValuesFrom(storedAt, location)));
        plantAxioms(projectId, axioms);
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

    private boolean containsOtherAxiom(ProjectId projectId) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        var pallet = data.getOWLClass(IRI.create(PALLET));
        var location = data.getOWLClass(IRI.create(NS + "Location"));
        var storedAt = data.getOWLObjectProperty(IRI.create(NS + "storedAt"));
        OWLAxiom axiom = data.getOWLSubClassOfAxiom(pallet, data.getOWLObjectAllValuesFrom(storedAt, location));
        var handle = registry.get(projectId);
        return handle.gate()
                .read(() -> handle.runtime().ontologies().keys().stream()
                        .anyMatch(key -> handle.runtime().ontologies().containsAxiom(key, axiom)));
    }

    private JsonNode frame(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", PALLET)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult putFrame(SignedIn user, ProjectId projectId, JsonNode from, JsonNode to) throws Exception {
        return send(
                user,
                put("/api/projects/{id}/frames/class", projectId.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateBody(from, to))));
    }

    private JsonNode hierarchy(SignedIn user, ProjectId projectId, String parent) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/children", projectId.toString())
                        .param("parent", parent)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private ObjectNode copy(JsonNode node) throws Exception {
        return (ObjectNode) json.readTree(json.writeValueAsString(node));
    }

    private ObjectNode classNode(String iri, String name) {
        ObjectNode node = json.createObjectNode();
        node.put("iri", iri);
        node.put("entityType", "CLASS");
        node.put("displayName", name);
        node.put("hasChildren", false);
        node.put("deprecated", false);
        return node;
    }

    private static ObjectNode label(JsonNode frame, String lang) {
        for (JsonNode annotation : frame.path("annotations")) {
            if (lang.equals(annotation.path("lang").asString())) {
                return (ObjectNode) annotation.path("value");
            }
        }
        throw new AssertionError("missing label " + lang);
    }

    private static String lexical(OWLAxiom axiom) {
        OWLAnnotationValue value = ((OWLAnnotationAssertionAxiom) axiom).getValue();
        return ((OWLLiteral) value).getLiteral();
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

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record UpdateBody(JsonNode from, JsonNode to) {}
}
