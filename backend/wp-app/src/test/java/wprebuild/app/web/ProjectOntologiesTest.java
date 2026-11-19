package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
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
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * List project ontologies API (P6-27).
 *
 * <p>Gate facts: called by Surefire as {@code ProjectOntologiesTest}; kernel has a different
 * {@code ProjectOntologiesTest} for OWL storage; HTTP JSON fields key/ontologyIri/versionIri/axiomCount;
 * user asked "完成下一个任务卡".
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ProjectOntologiesTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    ProjectOntologiesTest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcRoleAssignmentRepository roles) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
    }

    @Test
    void can_view_member_reads_ontology_key_iri_and_axiom_count() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        ProjectId projectId = createEmpty(owner, "Ontologies");
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);

        MvcResult result = mockMvc.perform(get("/api/projects/{id}/ontologies", projectId.toString())
                        .session(viewer.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(result);
        assertThat(body.isArray()).isTrue();
        assertThat(body).hasSize(1);
        JsonNode row = body.get(0);
        assertThat(row.path("key").asString()).isEqualTo("o1");
        assertThat(row.path("ontologyIri").asString()).isEqualTo("http://example.org/wp/" + projectId.value());
        assertThat(row.path("versionIri").isNull()).isTrue();
        assertThat(row.path("axiomCount").asLong()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void non_member_is_forbidden_and_missing_project_is_not_found() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createEmpty(owner, "Private");

        MvcResult denied = mockMvc.perform(get("/api/projects/{id}/ontologies", projectId.toString())
                        .session(stranger.session()))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MvcResult missing = mockMvc.perform(
                        get("/api/projects/{id}/ontologies", UUID.randomUUID().toString())
                                .session(owner.session()))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(missing).path("code").asString()).isEqualTo("NOT_FOUND");
    }

    private ProjectId createEmpty(SignedIn owner, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"\"}")
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

    private SignedIn login(String userId) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(session);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + userId + "\",\"password\":\"" + PASSWORD + "\"}"))
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
}
