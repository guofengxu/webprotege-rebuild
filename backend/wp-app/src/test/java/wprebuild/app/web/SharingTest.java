package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.events.PermissionsChanged;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project members and the last-manager rule (BS-10, P3-22). */
@DatabaseTest
@AutoConfigureMockMvc
class SharingTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final ProjectEventHub hub;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    SharingTest(MockMvc mockMvc, JsonMapper json, UserService users, ProjectEventHub hub) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.hub = hub;
    }

    @Test
    void owner_is_listed_and_a_viewer_cannot_change_members() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn viewer = account("viewer");
        SignedIn editor = account("editor");
        ProjectId projectId = projectId(create(owner));
        grant(owner, projectId, viewer, ProjectRole.CAN_VIEW);
        grant(owner, projectId, editor, ProjectRole.CAN_EDIT);

        assertThat(getJson(viewer, members(projectId)).getResponse().getStatus())
                .isEqualTo(403);
        JsonNode members = read(getJson(owner, members(projectId)));
        assertThat(members)
                .extracting(node -> node.path("userId").asString())
                .contains(owner.userId(), viewer.userId());
        assertThat(roleOf(members, owner.userId())).isEqualTo("CAN_MANAGE");
        assertThat(putRole(editor, projectId, viewer, ProjectRole.CAN_COMMENT)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(putRole(viewer, projectId, editor, ProjectRole.CAN_VIEW)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void manager_can_grant_and_remove_a_member() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn colleague = account("colleague");
        ProjectId projectId = projectId(create(owner));
        CountDownLatch changed = new CountDownLatch(1);
        AtomicReference<String> type = new AtomicReference<>();
        ProjectEventHub.Subscription subscription =
                hub.subscribe(projectId, new UserId(owner.userId()), OptionalLong.empty(), sink(changed, type));
        try {
            MvcResult granted = putRole(owner, projectId, colleague, ProjectRole.CAN_EDIT);
            assertThat(granted.getResponse().getStatus())
                    .as(granted.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(read(granted).path("role").asString()).isEqualTo("CAN_EDIT");
            assertThat(changed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(type.get()).isEqualTo("PermissionsChanged");

            MvcResult removed = deletePath(owner, member(projectId, colleague));
            assertThat(removed.getResponse().getStatus()).isEqualTo(204);
            assertThat(read(getJson(owner, members(projectId))))
                    .extracting(node -> node.path("userId").asString())
                    .doesNotContain(colleague.userId());
        } finally {
            subscription.close();
        }
    }

    @Test
    void last_manager_cannot_be_removed_or_demoted() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn other = account("other");
        ProjectId projectId = projectId(create(owner));

        MvcResult demoted = putRole(owner, projectId, owner, ProjectRole.CAN_VIEW);
        assertThat(demoted.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(demoted).path("code").asString()).isEqualTo("LAST_MANAGER");
        MvcResult removed = deletePath(owner, member(projectId, owner));
        assertThat(removed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(removed).path("code").asString()).isEqualTo("LAST_MANAGER");
        assertThat(roleOf(read(getJson(owner, members(projectId))), owner.userId()))
                .isEqualTo("CAN_MANAGE");

        assertThat(putRole(owner, projectId, other, ProjectRole.CAN_MANAGE)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(putRole(owner, projectId, owner, ProjectRole.CAN_EDIT)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(deletePath(other, member(projectId, other)).getResponse().getStatus())
                .isEqualTo(409);
        assertThat(deletePath(other, member(projectId, owner)).getResponse().getStatus())
                .isEqualTo(204);
    }

    @Test
    void unknown_user_and_role_are_rejected() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        MvcResult missing = putJson(owner, member(projectId, "nobody-" + suffix), new RoleBody("CAN_VIEW"));
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(missing).path("code").asString()).isEqualTo("NOT_FOUND");
        MvcResult badRole = putJson(owner, member(projectId, owner), new RoleBody("OWNER"));
        assertThat(badRole.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(badRole).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        MvcResult absent = deletePath(owner, member(projectId, "nobody-" + suffix));
        assertThat(absent.getResponse().getStatus()).isEqualTo(404);
    }

    private static ProjectEventHub.LiveSink sink(CountDownLatch changed, AtomicReference<String> type) {
        return new ProjectEventHub.LiveSink() {
            @Override
            public void onEvents(ProjectEventHub.Delivery delivery) {
                for (var event : delivery.events()) {
                    if (event instanceof PermissionsChanged) {
                        type.set(event.type());
                        changed.countDown();
                    }
                }
            }

            @Override
            public void onHeartbeat() {}

            @Override
            public void onShutdown() {}
        };
    }

    private static String members(ProjectId projectId) {
        return "/api/projects/" + projectId + "/members";
    }

    private static String member(ProjectId projectId, SignedIn user) {
        return member(projectId, user.userId());
    }

    private static String member(ProjectId projectId, String userId) {
        return members(projectId) + "/" + userId;
    }

    private static String roleOf(JsonNode members, String userId) {
        for (JsonNode member : members) {
            if (userId.equals(member.path("userId").asString())) {
                return member.path("role").asString();
            }
        }
        throw new AssertionError("missing member " + userId);
    }

    private MvcResult grant(SignedIn actor, ProjectId projectId, SignedIn user, ProjectRole role) throws Exception {
        MvcResult result = putRole(actor, projectId, user, role);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    private MvcResult putRole(SignedIn actor, ProjectId projectId, SignedIn user, ProjectRole role) throws Exception {
        return putJson(actor, member(projectId, user), new RoleBody(role.name()));
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p322s-" + role + "-" + suffix;
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

    private MvcResult putJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, put(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult deletePath(SignedIn user, String path) throws Exception {
        return send(user, delete(path));
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
