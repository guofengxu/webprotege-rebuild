package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.Action;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.EntityActions;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.RevisionLog;

/** Creating and deleting entities through the project API (P3-14). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class EntityCreateDeleteTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String PREFIX = "http://www.example.org/";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String TOP_OBJECT =
            OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI().toString();

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    EntityCreateDeleteTest(
            MockMvc mockMvc,
            JsonMapper json,
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
    void created_subclasses_appear_in_the_hierarchy_and_disappear_when_deleted() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(1));

        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", List.of("Pallet", "Bin"), null, List.of(THING)));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode body = read(created);
        assertThat(body.path("revision").asLong()).isEqualTo(2);
        assertThat(names(body.path("entities"))).containsExactly("Pallet", "Bin");
        assertThat(body.path("entities")).allSatisfy(node -> {
            assertThat(node.path("iri").asString()).startsWith(PREFIX);
            assertThat(node.path("entityType").asString()).isEqualTo("CLASS");
            assertThat(node.path("hasChildren").asBoolean()).isFalse();
            assertThat(node.path("deprecated").asBoolean()).isFalse();
        });
        List<String> createdIris = iris(body.path("entities"));
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(2));

        JsonNode children = hierarchy(owner, projectId, "class/children", "parent", THING);
        assertThat(names(children)).containsExactly("Bin", "Pallet");
        assertThat(iris(children)).containsExactlyInAnyOrderElementsOf(createdIris);

        MvcResult forbidden = postJson(
                viewer,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", List.of("Carton"), null, List.of(THING)));
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(2));

        MvcResult deniedDelete =
                deleteJson(viewer, "/api/projects/" + projectId + "/entities", new DeleteEntitiesBody(createdIris));
        assertThat(deniedDelete.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(deniedDelete).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(2));

        MvcResult deleted =
                deleteJson(owner, "/api/projects/" + projectId + "/entities", new DeleteEntitiesBody(createdIris));
        assertThat(deleted.getResponse().getStatus())
                .as(deleted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(deleted).path("revision").asLong()).isEqualTo(3);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));
        assertThat(hierarchy(owner, projectId, "class/children", "parent", THING))
                .isEmpty();
    }

    @Test
    void mixed_delete_removes_each_entity_type_in_one_revision() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = createProject(owner);

        JsonNode classes = read(postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", List.of("Aisle"), null, List.of())));
        JsonNode properties = read(postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("OBJECT_PROPERTY", List.of("contains"), null, List.of(TOP_OBJECT))));
        JsonNode individuals = read(postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody(
                        "NAMED_INDIVIDUAL",
                        List.of("Ada"),
                        null,
                        List.of(classes.path("entities").get(0).path("iri").asString()))));
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(4));
        assertThat(names(hierarchy(owner, projectId, "object-property/children", "parent", TOP_OBJECT)))
                .containsExactly("contains");
        String classIri = classes.path("entities").get(0).path("iri").asString();
        String propertyIri = properties.path("entities").get(0).path("iri").asString();
        String individualIri = individuals.path("entities").get(0).path("iri").asString();
        assertThat(individuals.path("entities").get(0).path("displayName").asString())
                .isEqualTo("Ada");
        assertThat(signatureContains(projectId, individualIri)).isTrue();

        MvcResult deleted = deleteJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new DeleteEntitiesBody(List.of(classIri, propertyIri, individualIri)));
        assertThat(deleted.getResponse().getStatus())
                .as(deleted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(deleted).path("revision").asLong()).isEqualTo(5);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(5));
        assertThat(hierarchy(owner, projectId, "class/children", "parent", THING))
                .isEmpty();
        assertThat(hierarchy(owner, projectId, "object-property/children", "parent", TOP_OBJECT))
                .isEmpty();
        assertThat(signatureContains(projectId, individualIri)).isFalse();
    }

    @Test
    void delete_actions_follow_each_entity_type() {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI iri = IRI.create("http://example.org/x");
        assertThat(EntityActions.deleteAll(List.of(
                        data.getOWLClass(iri),
                        data.getOWLObjectProperty(iri),
                        data.getOWLDataProperty(iri),
                        data.getOWLAnnotationProperty(iri),
                        data.getOWLNamedIndividual(iri))))
                .containsExactlyInAnyOrder(Action.DELETE_CLASS, Action.DELETE_PROPERTY, Action.DELETE_INDIVIDUAL);
        assertThat(EntityActions.create(data.getOWLClass(iri).getEntityType())).isEqualTo(Action.CREATE_CLASS);
        assertThat(EntityActions.create(data.getOWLObjectProperty(iri).getEntityType()))
                .isEqualTo(Action.CREATE_PROPERTY);
        assertThat(EntityActions.create(data.getOWLNamedIndividual(iri).getEntityType()))
                .isEqualTo(Action.CREATE_INDIVIDUAL);
    }

    private RevisionNumber head(ProjectId projectId) {
        RevisionNumber stored = revisions.head(projectId);
        assertThat(registry.get(projectId).runtime().head()).isEqualTo(stored);
        return stored;
    }

    private boolean signatureContains(ProjectId projectId, String iri) {
        var handle = registry.get(projectId);
        return handle.gate()
                .read(() -> handle.runtime()
                        .ontologies()
                        .signature()
                        .anyMatch(entity -> entity.getIRI().toString().equals(iri)));
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = postJson(owner, "/api/projects", new CreateProject("Warehouse", "Stock", null));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private JsonNode hierarchy(SignedIn user, ProjectId projectId, String path, String param, String value)
            throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/hierarchies/" + path, projectId.toString())
                        .param(param, value)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult deleteJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, delete(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
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

    private static List<String> iris(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("iri").asString()));
        return values;
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}

    private record DeleteEntitiesBody(List<String> iris) {}
}
