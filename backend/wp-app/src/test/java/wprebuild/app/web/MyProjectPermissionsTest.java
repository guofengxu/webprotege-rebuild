package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** GET /api/projects/{id}/permissions/me (P6-01). */
@DatabaseTest
@AutoConfigureMockMvc
class MyProjectPermissionsTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final AccessService access;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    MyProjectPermissionsTest(MockMvc mockMvc, JsonMapper json, UserService users, AccessService access) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.access = access;
    }

    @Test
    void four_roles_match_project_role_actions() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn viewer = account("viewer");
        SignedIn commenter = account("commenter");
        SignedIn editor = account("editor");
        ProjectId projectId = projectId(create(owner));
        grant(owner, projectId, viewer, ProjectRole.CAN_VIEW);
        grant(owner, projectId, commenter, ProjectRole.CAN_COMMENT);
        grant(owner, projectId, editor, ProjectRole.CAN_EDIT);

        assertRoleActions(viewer, projectId, ProjectRole.CAN_VIEW);
        assertRoleActions(commenter, projectId, ProjectRole.CAN_COMMENT);
        assertRoleActions(editor, projectId, ProjectRole.CAN_EDIT);
        assertRoleActions(owner, projectId, ProjectRole.CAN_MANAGE);
    }

    @Test
    void system_admin_without_role_sees_manage_actions() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn admin = account("admin", "SYSTEM_ADMIN");
        ProjectId projectId = projectId(create(owner));

        JsonNode body = read(getJson(admin, permissions(projectId)));
        assertThat(body.path("role").isNull()).isTrue();
        assertThat(body.path("systemAdmin").asBoolean()).isTrue();
        assertThat(actionNames(body)).containsExactlyElementsOf(orderedNames(ProjectRole.CAN_MANAGE.actions()));
        assertActionsMatchCan(admin, projectId, body);
    }

    @Test
    void non_member_gets_403_anonymous_401_trashed_409_bad_id_400() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn stranger = account("stranger");
        ProjectId projectId = projectId(create(owner));

        assertThat(getJson(stranger, permissions(projectId)).getResponse().getStatus())
                .isEqualTo(403);

        MockHttpSession anonymous = new MockHttpSession();
        assertThat(mockMvc.perform(get(permissions(projectId)).session(anonymous))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        assertThat(getJson(owner, "/api/projects/not-a-uuid/permissions/me")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        assertThat(postPath(owner, "/api/projects/" + projectId + "/trash")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult trashed = getJson(owner, permissions(projectId));
        assertThat(trashed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(trashed).path("code").asString()).isEqualTo("PROJECT_TRASHED");
    }

    @Test
    void role_change_is_visible_immediately() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn member = account("member");
        ProjectId projectId = projectId(create(owner));
        grant(owner, projectId, member, ProjectRole.CAN_VIEW);
        assertRoleActions(member, projectId, ProjectRole.CAN_VIEW);

        grant(owner, projectId, member, ProjectRole.CAN_EDIT);
        assertRoleActions(member, projectId, ProjectRole.CAN_EDIT);
    }

    private void assertRoleActions(SignedIn user, ProjectId projectId, ProjectRole role) throws Exception {
        JsonNode body = read(getJson(user, permissions(projectId)));
        assertThat(body.path("role").asString()).isEqualTo(role.name());
        assertThat(body.path("systemAdmin").asBoolean()).isFalse();
        assertThat(actionNames(body)).containsExactlyElementsOf(orderedNames(role.actions()));
        assertActionsMatchCan(user, projectId, body);
    }

    private void assertActionsMatchCan(SignedIn user, ProjectId projectId, JsonNode body) {
        UserId userId = new UserId(user.userId());
        List<String> reported = actionNames(body);
        for (Action action : Action.values()) {
            if (!action.projectScoped()) {
                assertThat(reported).doesNotContain(action.name());
                continue;
            }
            boolean allowed = access.can(userId, projectId, action);
            assertThat(reported.contains(action.name()))
                    .as("%s for %s", action, user.userId())
                    .isEqualTo(allowed);
        }
    }

    private static List<String> actionNames(JsonNode body) {
        List<String> names = new ArrayList<>();
        for (JsonNode item : body.path("actions")) {
            names.add(item.asString());
        }
        return names;
    }

    private static List<String> orderedNames(java.util.Set<Action> actions) {
        List<String> names = new ArrayList<>();
        for (Action action : Action.values()) {
            if (actions.contains(action)) {
                names.add(action.name());
            }
        }
        return names;
    }

    private static String permissions(ProjectId projectId) {
        return "/api/projects/" + projectId + "/permissions/me";
    }

    private MvcResult grant(SignedIn actor, ProjectId projectId, SignedIn user, ProjectRole role) throws Exception {
        MvcResult result =
                putJson(actor, "/api/projects/" + projectId + "/members/" + user.userId(), new RoleBody(role.name()));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p601-" + role + "-" + suffix;
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(session);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(userId, PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie updated = login.getResponse().getCookie(CSRF_COOKIE);
        return new SignedIn(userId, session, updated == null ? csrf : updated);
    }

    private MvcResult create(SignedIn user) throws Exception {
        return postJson(user, "/api/projects", new CreateProject("Warehouse", "Stock"));
    }

    private MvcResult getJson(SignedIn user, String path) throws Exception {
        return send(user, get(path));
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult postPath(SignedIn user, String path) throws Exception {
        return send(user, post(path));
    }

    private MvcResult putJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
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

    private ProjectId projectId(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(result).path("projectId").asString());
    }

    private JsonNode read(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        if (body == null || body.isBlank()) {
            return json.createObjectNode();
        }
        return json.readTree(body);
    }

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description) {}

    private record RoleBody(String role) {}
}
