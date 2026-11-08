package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
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
import wprebuild.app.ontology.RevisionCommittedEvent;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/**
 * Project create, list, details, and trash through the HTTP API (P3-08).
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ProjectLifecycleTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final JdbcClient jdbc;

    private final RevisionLog revisions;

    private final ProjectRuntimeRegistry registry;

    private final ApplicationEventPublisher events;

    ProjectLifecycleTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            JdbcClient jdbc,
            RevisionLog revisions,
            ProjectRuntimeRegistry registry,
            ApplicationEventPublisher events) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.jdbc = jdbc;
        this.revisions = revisions;
        this.registry = registry;
        this.events = events;
    }

    @Test
    void created_project_is_listed_for_the_owner_with_head_1() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("other");
        SignedIn owner = login("owner");
        SignedIn other = login("other");

        MvcResult created = create(owner, "Warehouse", "Stock", null);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        ProjectId projectId = projectId(created);
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/projects/" + projectId);
        assertThat(read(created).path("displayName").asString()).isEqualTo("Warehouse");
        assertThat(read(created).path("description").asString()).isEqualTo("Stock");

        IRI ontologyIri = IRI.create("http://example.org/wp/" + projectId.value());
        assertThat(revisions.head(projectId)).isEqualTo(RevisionNumber.of(1));
        assertThat(registry.get(projectId).runtime().head()).isEqualTo(RevisionNumber.of(1));
        Revision revision = revisions.get(projectId, RevisionNumber.of(1)).orElseThrow();
        assertThat(revision.user()).isEqualTo(new UserId("owner"));
        assertThat(revision.changes())
                .containsExactly(new DeclareOntology(OntologyKey.of(1), Optional.of(ontologyIri), Optional.empty()));
        assertThat(registry.get(projectId).runtime().ontologies().info(OntologyKey.of(1)))
                .get()
                .extracting(info -> info.ontologyIri())
                .isEqualTo(Optional.of(ontologyIri));

        assertThat(projectIds(list(owner, "/api/projects"))).contains(projectId.toString());
        assertThat(projectIds(list(other, "/api/projects"))).doesNotContain(projectId.toString());
        mockMvc.perform(get("/api/projects/" + projectId).session(other.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));

        jdbc.sql("update projects set modified_by = 'stale' where project_id = ?")
                .param(projectId.value())
                .update();
        events.publishEvent(new RevisionCommittedEvent(projectId, revision, false));
        assertThat(modifiedBy(projectId)).isEqualTo("owner");
        assertThat(modifiedAt(projectId)).isEqualTo(revision.timestamp().truncatedTo(ChronoUnit.MICROS));
    }

    @Test
    void supplied_ontology_iri_is_declared_on_revision_1() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");

        MvcResult created = create(owner, "Custom", "", "http://example.org/custom");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        ProjectId projectId = projectId(created);
        IRI ontologyIri = IRI.create("http://example.org/custom");
        Revision revision = revisions.get(projectId, RevisionNumber.of(1)).orElseThrow();
        assertThat(revision.changes())
                .containsExactly(new DeclareOntology(OntologyKey.of(1), Optional.of(ontologyIri), Optional.empty()));
    }

    @Test
    void system_admin_lists_projects_without_a_role() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("admin", "SYSTEM_ADMIN");
        SignedIn owner = login("owner");
        SignedIn admin = login("admin");
        ProjectId projectId = projectId(create(owner, "Shared", "Visible to admin", null));

        assertThat(projectIds(list(admin, "/api/projects"))).contains(projectId.toString());
    }

    @Test
    void viewer_can_open_a_project_but_cannot_rename_it() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        ProjectId projectId = projectId(create(owner, "Warehouse", "Stock", null));
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);

        mockMvc.perform(get("/api/projects/" + projectId).session(viewer.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Warehouse"));
        assertThat(projectIds(list(viewer, "/api/projects"))).contains(projectId.toString());

        MvcResult denied = patchProject(viewer, projectId, "Renamed", "Stock");
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        mockMvc.perform(get("/api/projects/" + projectId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Warehouse"));

        MvcResult renamed = patchProject(owner, projectId, "Renamed", "Updated");
        assertThat(renamed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(renamed).path("displayName").asString()).isEqualTo("Renamed");
        assertThat(read(renamed).path("description").asString()).isEqualTo("Updated");

        MvcResult nameOnly = patchProject(owner, projectId, "Final", null);
        assertThat(nameOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(nameOnly).path("displayName").asString()).isEqualTo("Final");
        assertThat(read(nameOnly).path("description").asString()).isEqualTo("Updated");
    }

    @Test
    void trash_hides_the_project_until_it_is_restored() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("other");
        SignedIn owner = login("owner");
        SignedIn other = login("other");
        ProjectId projectId = projectId(create(owner, "Warehouse", "Stock", null));

        MvcResult trashed = postPath(owner, "/api/projects/" + projectId + "/trash");
        assertThat(trashed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(trashed).path("inTrash").asBoolean()).isTrue();
        assertThat(projectIds(list(owner, "/api/projects"))).doesNotContain(projectId.toString());
        assertThat(projectIds(list(owner, "/api/projects/trash"))).contains(projectId.toString());
        assertThat(projectIds(list(other, "/api/projects/trash"))).doesNotContain(projectId.toString());
        mockMvc.perform(get("/api/projects/" + projectId).session(owner.session()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_TRASHED"));

        MvcResult restored = deletePath(owner, "/api/projects/" + projectId + "/trash");
        assertThat(restored.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(restored).path("inTrash").asBoolean()).isFalse();
        mockMvc.perform(get("/api/projects/" + projectId).session(owner.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Warehouse"));
        assertThat(projectIds(list(owner, "/api/projects"))).contains(projectId.toString());
        assertThat(projectIds(list(owner, "/api/projects/trash"))).doesNotContain(projectId.toString());
    }

    @Test
    void blank_name_and_missing_create_permission_are_rejected() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("plain");
        SignedIn owner = login("owner");
        SignedIn plain = login("plain");

        MvcResult blank = create(owner, "   ", "Stock", null);
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(blank).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult relative = create(owner, "Warehouse", "Stock", "not-an-absolute-iri");
        assertThat(relative.getResponse().getStatus()).isEqualTo(400);

        MvcResult forbidden = create(plain, "Warehouse", "Stock", null);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
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

    private MvcResult create(SignedIn user, String name, String description, String ontologyIri) throws Exception {
        return postJson(user, "/api/projects", new CreateProject(name, description, ontologyIri));
    }

    private MvcResult patchProject(SignedIn user, ProjectId projectId, String name, String description)
            throws Exception {
        return send(
                user,
                patch("/api/projects/" + projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateProject(name, description))));
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult postPath(SignedIn user, String path) throws Exception {
        return send(user, post(path));
    }

    private MvcResult deletePath(SignedIn user, String path) throws Exception {
        return send(user, delete(path));
    }

    private JsonNode list(SignedIn user, String path) throws Exception {
        MvcResult result = mockMvc.perform(get(path).session(user.session()))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private MvcResult send(
            SignedIn user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
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

    private ProjectId projectId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return ProjectId.of(read(result).path("projectId").asString());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> projectIds(JsonNode listed) {
        List<String> ids = new ArrayList<>();
        listed.forEach(node -> ids.add(node.path("projectId").asString()));
        return ids;
    }

    private String modifiedBy(ProjectId projectId) {
        return jdbc.sql("select modified_by from projects where project_id = ?")
                .param(projectId.value())
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private Instant modifiedAt(ProjectId projectId) {
        return jdbc.sql("select modified_at from projects where project_id = ?")
                .param(projectId.value())
                .query((rs, row) -> rs.getObject(1, OffsetDateTime.class).toInstant())
                .single();
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record UpdateProject(String name, String description) {}
}
