package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.AccessTokenService;
import wprebuild.app.access.ApiKeyService;
import wprebuild.app.access.IssuedKey;
import wprebuild.app.access.IssuedTokens;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserDeleted;
import wprebuild.app.access.UserService;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.web.security.ApiKeyAuthenticationFilter;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Soft-delete of a user account by a system administrator (P6-29). */
@DatabaseTest
@AutoConfigureMockMvc
@RecordApplicationEvents
class AdminDeleteUserTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String USER_PASSWORD = "user-password-12";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final ApiKeyService apiKeys;

    private final AccessTokenService tokens;

    private final ProjectEventHub hub;

    private final JdbcClient jdbc;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    @Autowired
    private ApplicationEvents applicationEvents;

    AdminDeleteUserTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            ApiKeyService apiKeys,
            AccessTokenService tokens,
            ProjectEventHub hub,
            JdbcClient jdbc) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.apiKeys = apiKeys;
        this.tokens = tokens;
        this.hub = hub;
        this.jdbc = jdbc;
    }

    @Test
    void admin_delete_revokes_credentials_clears_profile_and_keeps_history() throws Exception {
        SignedIn admin = admin();
        SignedIn target = account("alice");
        ProjectId first = projectOwnedBy(admin);
        ProjectId second = projectOwnedBy(admin);
        grant(admin, first, target, ProjectRole.CAN_EDIT);
        grant(admin, second, target, ProjectRole.CAN_EDIT);

        IssuedKey apiKey = apiKeys.issue(new UserId(target.userId()), "ci");
        IssuedTokens bearer = tokens.issue(new UserId(target.userId()), "phone");
        createClass(target, first, "Pallet");
        ProjectId owned = projectOwnedBy(target);
        // Owner row stays Alice; a second manager is required so soft-delete is allowed.
        grant(target, owned, admin, ProjectRole.CAN_MANAGE);

        CountDownLatch shut = new CountDownLatch(1);
        AtomicBoolean closed = new AtomicBoolean();
        ProjectEventHub.Subscription subscription =
                hub.subscribe(first, new UserId(target.userId()), OptionalLong.empty(), new ProjectEventHub.LiveSink() {
                    @Override
                    public void onEvents(ProjectEventHub.Delivery delivery) {}

                    @Override
                    public void onHeartbeat() {}

                    @Override
                    public void onShutdown() {
                        closed.set(true);
                        shut.countDown();
                    }
                });
        try {
            applicationEvents.clear();
            MvcResult deleted = deleteUser(admin, target.userId());
            assertThat(deleted.getResponse().getStatus()).isEqualTo(204);
            assertThat(applicationEvents.stream(UserDeleted.class))
                    .extracting(event -> event.userId().value())
                    .containsExactly(target.userId());
            assertThat(shut.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(closed).isTrue();
        } finally {
            subscription.close();
        }

        mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey.plaintext()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer.accessToken()))
                .andExpect(status().isUnauthorized());

        MvcResult login = postLogin(new MockHttpSession(), target.userId(), USER_PASSWORD);
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(login.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isNotEqualTo(ApiProblems.PASSWORD_RESET_REQUIRED);

        MvcResult issued = mockMvc.perform(post("/api/auth/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new IssueToken(target.userId(), USER_PASSWORD, "tablet"))))
                .andReturn();
        assertThat(issued.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(issued.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isNotEqualTo(ApiProblems.PASSWORD_RESET_REQUIRED);

        assertThat(memberIds(admin, first)).doesNotContain(target.userId());
        assertThat(memberIds(admin, second)).doesNotContain(target.userId());

        JsonNode revisions = json.readTree(mockMvc.perform(get("/api/projects/" + first + "/revisions")
                        .session(admin.session())
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(revisions.path("revisions"))
                .extracting(node -> node.path("userId").asString())
                .contains(target.userId());

        mockMvc.perform(get("/api/projects/" + owned).session(admin.session())).andExpect(status().isOk());
        assertThat(jdbc.sql("select owner from projects where project_id = ?")
                        .param(owned.value())
                        .query(String.class)
                        .single())
                .isEqualTo(target.userId());

        JsonNode listed = json.readTree(
                mockMvc.perform(get("/api/admin/users").session(admin.session()).param("size", "50"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        JsonNode alice = userNamed(listed, target.userId());
        assertThat(alice.path("deletedAt").asString()).isNotBlank();
        assertThat(alice.path("email").isNull()).isTrue();
        assertThat(alice.path("appRoles")).isEmpty();
        assertThat(alice.path("passwordResetRequired").asBoolean()).isFalse();
        assertThat(alice.path("displayName").asString()).isEqualTo(target.userId());

        JsonNode candidates = json.readTree(mockMvc.perform(get("/api/projects/" + first + "/member-candidates")
                        .session(admin.session())
                        .param("q", target.userId())
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(candidates)
                .extracting(node -> node.path("userId").asString())
                .doesNotContain(target.userId());

        MvcResult recreate = createUser(admin, target.userId(), "Again", "again@example.com", USER_PASSWORD);
        assertThat(recreate.getResponse().getStatus()).isEqualTo(409);

        MvcResult roles = setRoles(admin, target.userId(), List.of("PROJECT_CREATOR"));
        assertThat(roles.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(roles.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("USER_DELETED");

        MvcResult password = resetPassword(admin, target.userId(), "replacement-pw12");
        assertThat(password.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(password.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("USER_DELETED");

        assertThat(deleteUser(admin, target.userId()).getResponse().getStatus()).isEqualTo(204);
    }

    @Test
    void rejects_self_and_last_manager_without_changes() throws Exception {
        SignedIn admin = admin();

        MvcResult self = deleteUser(admin, admin.userId());
        assertThat(self.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(self.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("CANNOT_DELETE_SELF");
        assertThat(deletedAt(admin.userId())).isNull();

        SignedIn manager = account("mgr");
        ProjectId alone = projectOwnedBy(manager);
        String emailBefore = email(manager.userId());
        String hashBefore = passwordHash(manager.userId());
        int keysBefore = keyCount(manager.userId());
        MvcResult lastManager = deleteUser(admin, manager.userId());
        assertThat(lastManager.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = json.readTree(lastManager.getResponse().getContentAsString());
        assertThat(problem.path("code").asString()).isEqualTo("LAST_MANAGER");
        assertThat(problem.path("projectIds")).extracting(JsonNode::asString).containsExactly(alone.toString());
        assertThat(deletedAt(manager.userId())).isNull();
        assertThat(email(manager.userId())).isEqualTo(emailBefore);
        assertThat(passwordHash(manager.userId())).isEqualTo(hashBefore);
        assertThat(keyCount(manager.userId())).isEqualTo(keysBefore);
        assertThat(memberIds(admin, alone)).contains(manager.userId());
    }

    @Test
    void cannot_delete_the_last_system_admin_account() {
        String only = "only-admin-" + suffix;
        users.create(only, "Only", only + "@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        // Prior tests may have left other SYSTEM_ADMIN rows; strip them so this account is the last one.
        jdbc.sql("update users set app_roles = '{}' where user_id <> ? and ? = any (app_roles)")
                .param(only)
                .param("SYSTEM_ADMIN")
                .update();
        String helper = "helper-" + suffix;
        users.create(helper, "Helper", helper + "@example.com", ADMIN_PASSWORD, List.of());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> users.delete(new UserId(only), new UserId(helper)))
                .isInstanceOf(wprebuild.app.shared.ConflictException.class)
                .extracting(ex -> ((wprebuild.app.shared.ConflictException) ex).code())
                .isEqualTo(UserService.LAST_SYSTEM_ADMIN);
        assertThat(deletedAt(only)).isNull();
        assertThat(appRoles(only)).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void unauthorized_and_missing_user() throws Exception {
        SignedIn admin = admin();
        users.create("plain-" + suffix, "Plain", "plain@example.com", USER_PASSWORD, List.of("PROJECT_CREATOR"));
        SignedIn plain = login("plain-" + suffix, USER_PASSWORD);

        MockHttpSession anonymous = new MockHttpSession();
        Cookie anonCsrf = csrf(anonymous);
        mockMvc.perform(delete("/api/admin/users/plain-" + suffix)
                        .session(anonymous)
                        .cookie(anonCsrf)
                        .header(CSRF_HEADER, anonCsrf.getValue()))
                .andExpect(status().isUnauthorized());
        MvcResult forbidden = deleteUser(plain, "plain-" + suffix);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        MvcResult missing = deleteUser(admin, "missing-" + suffix);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
    }

    private SignedIn admin() throws Exception {
        String userId = "root-" + suffix;
        users.create(userId, "Root", userId + "@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        return login(userId, ADMIN_PASSWORD);
    }

    private SignedIn account(String role) throws Exception {
        String userId = role + "-" + suffix;
        users.create(userId, userId, userId + "@example.com", USER_PASSWORD, List.of("PROJECT_CREATOR"));
        return login(userId, USER_PASSWORD);
    }

    private SignedIn login(String userId, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(postLogin(session, userId, password).getResponse().getStatus())
                .isEqualTo(200);
        Cookie csrf = csrf(session);
        return new SignedIn(userId, session, csrf);
    }

    private ProjectId projectOwnedBy(SignedIn owner) throws Exception {
        MvcResult created = send(
                owner,
                post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateProject("Warehouse", "Stock"))));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(json.readTree(created.getResponse().getContentAsString())
                .path("projectId")
                .asString());
    }

    private void grant(SignedIn actor, ProjectId projectId, SignedIn user, ProjectRole role) throws Exception {
        MvcResult result = send(
                actor,
                put("/api/projects/" + projectId + "/members/" + user.userId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RoleBody(role.name()))));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void createClass(SignedIn actor, ProjectId projectId, String name) throws Exception {
        MvcResult result = send(
                actor,
                post("/api/projects/" + projectId + "/entities")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new CreateEntitiesBody("CLASS", List.of(name), null, List.of(THING)))));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(201);
    }

    private List<String> memberIds(SignedIn actor, ProjectId projectId) throws Exception {
        JsonNode members = json.readTree(send(actor, get("/api/projects/" + projectId + "/members"))
                .getResponse()
                .getContentAsString());
        List<String> ids = new java.util.ArrayList<>();
        for (JsonNode member : members) {
            ids.add(member.path("userId").asString());
        }
        return ids;
    }

    private MvcResult deleteUser(SignedIn actor, String userId) throws Exception {
        return send(actor, delete("/api/admin/users/" + userId));
    }

    private MvcResult createUser(SignedIn session, String userId, String displayName, String email, String password)
            throws Exception {
        return send(
                session,
                post("/api/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateBody(userId, displayName, email, password))));
    }

    private MvcResult resetPassword(SignedIn session, String userId, String password) throws Exception {
        return send(
                session,
                put("/api/admin/users/" + userId + "/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PasswordBody(password))));
    }

    private MvcResult setRoles(SignedIn session, String userId, List<String> appRoles) throws Exception {
        return send(
                session,
                put("/api/admin/users/" + userId + "/app-roles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RolesBody(appRoles))));
    }

    private MvcResult send(
            SignedIn user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
    }

    private MvcResult postLogin(MockHttpSession session, String username, String password) throws Exception {
        Cookie csrf = csrf(session);
        return mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, password))))
                .andReturn();
    }

    private Cookie csrf(MockHttpSession session) throws Exception {
        MvcResult primed = mockMvc.perform(get("/api/auth/me").session(session)).andReturn();
        Cookie csrf = primed.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        return csrf;
    }

    private static JsonNode userNamed(JsonNode page, String userId) {
        for (JsonNode user : page.path("users")) {
            if (userId.equals(user.path("userId").asString())) {
                return user;
            }
        }
        throw new AssertionError("missing user " + userId);
    }

    private String deletedAt(String userId) {
        return jdbc.sql("select deleted_at from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getString(1))
                .optional()
                .orElse(null);
    }

    private String email(String userId) {
        return jdbc.sql("select email from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private String passwordHash(String userId) {
        return jdbc.sql("select password_hash from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private int keyCount(String userId) {
        return jdbc.sql("select count(*) from api_keys where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private List<String> appRoles(String userId) {
        return jdbc.sql("select app_roles from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> List.of((String[]) rs.getArray(1).getArray()))
                .single();
    }

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description) {}

    private record RoleBody(String role) {}

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}

    private record CreateBody(String userId, String displayName, String email, String initialPassword) {}

    private record PasswordBody(String newPassword) {}

    private record RolesBody(List<String> appRoles) {}

    private record IssueToken(String username, String password, String deviceName) {}
}
