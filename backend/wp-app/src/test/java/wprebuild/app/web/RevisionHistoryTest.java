package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
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
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.RevisionLog;

/** Revision history, per-entity history, and revert (P3-20, BS-04). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class RevisionHistoryTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    RevisionHistoryTest(
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
    void lists_revisions_newest_first_one_page_at_a_time() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        createClass(owner, projectId, "Pallet");
        createClass(owner, projectId, "Bin");
        createClass(owner, projectId, "Carton");

        JsonNode first = list(owner, projectId, 0, 2);
        assertThat(first.path("page").asInt()).isEqualTo(0);
        assertThat(first.path("size").asInt()).isEqualTo(2);
        assertThat(first.path("total").asLong()).isEqualTo(4);
        assertThat(revNos(first.path("revisions"))).containsExactly(4L, 3L);
        JsonNode newest = first.path("revisions").get(0);
        assertThat(newest.path("userId").asString()).isEqualTo("owner");
        assertThat(newest.path("description").asString()).contains("Carton");
        assertThat(newest.path("changeCount").asInt()).isPositive();
        assertThat(newest.path("ts").asString()).isNotBlank();
        assertThat(newest.has("payload")).isFalse();

        JsonNode second = list(viewer, projectId, 1, 2);
        assertThat(revNos(second.path("revisions"))).containsExactly(2L, 1L);
        assertThat(second.path("revisions").get(1).path("description").asString())
                .isEqualTo("Created empty project");
        assertThat(second.path("revisions").get(1).path("changeCount").asInt()).isEqualTo(1);
        assertThat(list(owner, projectId, 2, 2).path("revisions")).isEmpty();

        MvcResult denied = mockMvc.perform(get("/api/projects/{id}/revisions", projectId.toString())
                        .session(stranger.session()))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void diff_groups_changes_by_subject_and_honours_the_limit() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        String container = createClass(owner, projectId, "Container").iri();
        Created both = createClasses(owner, projectId, List.of("Pallet", "Bin"), List.of(container));

        JsonNode diff = diff(owner, projectId, both.revision(), null);
        assertThat(diff.path("revNo").asLong()).isEqualTo(both.revision());
        assertThat(diff.path("total").asInt()).isEqualTo(diff.path("changes").size());
        assertThat(diff.path("limit").asInt()).isEqualTo(200);
        List<String> subjects = subjectNames(diff.path("changes"));
        assertThat(subjects).contains("Pallet", "Bin");
        assertThat(grouped(subjects)).isTrue();
        assertThat(subclassSubjects(diff.path("changes"))).containsExactlyInAnyOrder("Pallet", "Bin");

        JsonNode limited = diff(owner, projectId, both.revision(), 1);
        assertThat(limited.path("changes")).hasSize(1);
        assertThat(limited.path("total").asInt()).isGreaterThan(1);
        assertThat(limited.path("limit").asInt()).isEqualTo(1);
        assertThat(limited.path("changes")
                        .get(0)
                        .path("subject")
                        .path("displayName")
                        .asString())
                .isEqualTo(subjectNames(diff.path("changes")).getFirst());
    }

    @Test
    void entity_history_contains_only_revisions_that_mention_the_entity() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        Created pallet = createClass(owner, projectId, "Pallet");
        Created bin = createClass(owner, projectId, "Bin");

        JsonNode palletHistory = history(owner, projectId, pallet.iri(), 0, 20);
        assertThat(revNos(palletHistory.path("revisions"))).containsExactly(pallet.revision());
        assertThat(palletHistory.path("total").asLong()).isEqualTo(1);

        JsonNode binHistory = history(owner, projectId, bin.iri(), 0, 20);
        assertThat(revNos(binHistory.path("revisions"))).containsExactly(bin.revision());
        assertThat(revNos(binHistory.path("revisions"))).doesNotContain(pallet.revision(), 1L);
    }

    @Test
    void revert_restores_deleted_content_as_the_next_revision() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        Created pallet = createClass(owner, projectId, "Pallet");
        deleteEntities(owner, projectId, List.of(pallet.iri()));
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));
        assertThat(classNames(owner, projectId)).doesNotContain("Pallet");

        MvcResult denied = postEmpty(viewer, "/api/projects/" + projectId + "/revisions/3/revert");
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(3));

        MvcResult reverted = postEmpty(owner, "/api/projects/" + projectId + "/revisions/3/revert");
        assertThat(reverted.getResponse().getStatus())
                .as(reverted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(reverted).path("revision").asLong()).isEqualTo(4);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(4));
        assertThat(classNames(owner, projectId)).contains("Pallet");
        assertThat(revisions.get(projectId, RevisionNumber.of(4)).orElseThrow().description())
                .isEqualTo("Reverted revision 3");
    }

    @Test
    void reverting_the_initial_revision_is_conflict() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(1));

        JsonNode initial = diff(owner, projectId, 1, null);
        assertThat(initial.path("total").asInt()).isEqualTo(1);
        assertThat(initial.path("changes").get(0).path("op").asString()).isEqualTo("ADD");
        assertThat(initial.path("changes").get(0).path("text").asString()).contains("Ontology");

        MvcResult conflict = postEmpty(owner, "/api/projects/" + projectId + "/revisions/1/revert");
        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(head(projectId)).isEqualTo(RevisionNumber.of(1));

        MvcResult missing = postEmpty(owner, "/api/projects/" + projectId + "/revisions/9/revert");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        MvcResult missingDiff = mockMvc.perform(get("/api/projects/{id}/revisions/{rev}", projectId.toString(), 9)
                        .session(owner.session()))
                .andReturn();
        assertThat(missingDiff.getResponse().getStatus()).isEqualTo(404);
    }

    private Created createClass(SignedIn owner, ProjectId projectId, String name) throws Exception {
        return createClasses(owner, projectId, List.of(name), List.of(THING));
    }

    private Created createClasses(SignedIn owner, ProjectId projectId, List<String> names, List<String> parents)
            throws Exception {
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody("CLASS", names, null, parents));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode body = read(created);
        return new Created(
                body.path("entities").get(0).path("iri").asString(),
                body.path("revision").asLong());
    }

    private void deleteEntities(SignedIn owner, ProjectId projectId, List<String> iris) throws Exception {
        MvcResult deleted = deleteJson(owner, "/api/projects/" + projectId + "/entities", new DeleteEntitiesBody(iris));
        assertThat(deleted.getResponse().getStatus())
                .as(deleted.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private JsonNode list(SignedIn user, ProjectId projectId, int page, int size) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/revisions", projectId.toString())
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size))
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode diff(SignedIn user, ProjectId projectId, long revision, Integer limit) throws Exception {
        var request = get("/api/projects/{id}/revisions/{rev}", projectId.toString(), revision)
                .session(user.session());
        if (limit != null) {
            request.param("limit", Integer.toString(limit));
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode history(SignedIn user, ProjectId projectId, String iri, int page, int size) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/entities/history", projectId.toString())
                        .param("iri", iri)
                        .param("page", Integer.toString(page))
                        .param("size", Integer.toString(size))
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private List<String> classNames(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/children", projectId.toString())
                        .param("parent", THING)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        read(result).forEach(node -> names.add(node.path("displayName").asString()));
        return names;
    }

    private RevisionNumber head(ProjectId projectId) {
        RevisionNumber stored = revisions.head(projectId);
        assertThat(registry.get(projectId).runtime().head()).isEqualTo(stored);
        return stored;
    }

    private SignedIn owner() throws Exception {
        account("owner", "PROJECT_CREATOR");
        return login("owner");
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = postJson(owner, "/api/projects", new CreateProject("Warehouse", "Stock", null));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult deleteJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, delete(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult postEmpty(SignedIn user, String path) throws Exception {
        return send(user, post(path));
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

    private static List<Long> revNos(JsonNode revisions) {
        List<Long> values = new ArrayList<>();
        revisions.forEach(node -> values.add(node.path("revNo").asLong()));
        return values;
    }

    private static List<String> subjectNames(JsonNode changes) {
        List<String> names = new ArrayList<>();
        changes.forEach(
                change -> names.add(change.path("subject").path("displayName").asString()));
        return names;
    }

    private static List<String> subclassSubjects(JsonNode changes) {
        List<String> names = new ArrayList<>();
        changes.forEach(change -> {
            String text = change.path("text").asString();
            if (text.contains("SubClassOf")) {
                assertThat(change.path("op").asString()).isEqualTo("ADD");
                String name = change.path("subject").path("displayName").asString();
                assertThat(text).contains(name).contains("Container");
                assertThat(change.path("subject").path("entityType").asString()).isEqualTo("CLASS");
                names.add(name);
            }
        });
        return names;
    }

    /** A subject does not reappear after a different subject has started. */
    private static boolean grouped(List<String> subjects) {
        List<String> seen = new ArrayList<>();
        String current = null;
        for (String subject : subjects) {
            if (subject.equals(current)) {
                continue;
            }
            if (seen.contains(subject)) {
                return false;
            }
            seen.add(subject);
            current = subject;
        }
        return true;
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}

    private record DeleteEntitiesBody(List<String> iris) {}

    private record Created(String iri, long revision) {}
}
