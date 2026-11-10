package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;

@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AdminUserManagementTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String INITIAL_PASSWORD = "initial-password";

    private static final String REPLACEMENT_PASSWORD = "replacement-password";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    AdminUserManagementTest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcClient jdbc) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
    }

    @Test
    void admin_can_list_create_reset_password_and_assign_roles() throws Exception {
        users.create("root", "Root", "root@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        MockHttpSession admin = login("root", ADMIN_PASSWORD);

        MvcResult listed = mockMvc.perform(get("/api/admin/users")
                        .session(admin)
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode page = json.readTree(listed.getResponse().getContentAsString());
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(10);
        assertThat(page.path("total").asLong()).isEqualTo(1);
        assertThat(page.path("users")).hasSize(1);
        assertThat(page.path("users").get(0).path("userId").asString()).isEqualTo("root");
        assertThat(page.path("users").get(0).path("passwordResetRequired").asBoolean())
                .isFalse();
        assertThat(listed.getResponse().getContentAsString())
                .doesNotContain("passwordHash", "{bcrypt}", ADMIN_PASSWORD);

        MvcResult created = createUser(admin, "amy", "Amy", "amy@example.com", INITIAL_PASSWORD);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/admin/users/amy");
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        assertThat(body.path("userId").asString()).isEqualTo("amy");
        assertThat(body.path("displayName").asString()).isEqualTo("Amy");
        assertThat(body.path("email").asString()).isEqualTo("amy@example.com");
        assertThat(body.path("appRoles")).isEmpty();
        assertThat(body.path("passwordResetRequired").asBoolean()).isFalse();
        assertThat(created.getResponse().getContentAsString())
                .doesNotContain("passwordHash", "{bcrypt}", INITIAL_PASSWORD);
        String hash = passwordHash("amy");
        assertThat(hash).startsWith("{bcrypt}");
        assertThat(hash).doesNotContain(INITIAL_PASSWORD);

        MvcResult roles = setRoles(admin, "amy", List.of("PROJECT_CREATOR", "SYSTEM_ADMIN"));
        assertThat(roles.getResponse().getStatus()).isEqualTo(200);
        assertThat(json.readTree(roles.getResponse().getContentAsString()).path("appRoles"))
                .extracting(JsonNode::asString)
                .containsExactly("PROJECT_CREATOR", "SYSTEM_ADMIN");

        setRoles(admin, "amy", List.of("PROJECT_CREATOR"));
        assertThat(appRoles("amy")).containsExactly("PROJECT_CREATOR");
        assertThat(resetPassword(admin, "amy", REPLACEMENT_PASSWORD)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);
        assertThat(loginStatus("amy", INITIAL_PASSWORD)).isEqualTo(401);
        assertThat(loginStatus("amy", REPLACEMENT_PASSWORD)).isEqualTo(200);

        MockHttpSession amy = login("amy", REPLACEMENT_PASSWORD);
        mockMvc.perform(get("/api/auth/me").session(amy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appRoles[0]").value("PROJECT_CREATOR"));

        users.create("bea", "Bea", "bea@example.com", INITIAL_PASSWORD, List.of());
        JsonNode first = json.readTree(mockMvc.perform(get("/api/admin/users")
                        .session(admin)
                        .param("page", "0")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        JsonNode second = json.readTree(mockMvc.perform(get("/api/admin/users")
                        .session(admin)
                        .param("page", "1")
                        .param("size", "1"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
        assertThat(first.path("total").asLong()).isEqualTo(3);
        assertThat(second.path("total").asLong()).isEqualTo(3);
        assertThat(first.path("users").get(0).path("userId").asString()).isEqualTo("amy");
        assertThat(second.path("users").get(0).path("userId").asString()).isEqualTo("bea");
    }

    @Test
    void ordinary_user_is_forbidden() throws Exception {
        users.create("root", "Root", "root@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        users.create("creator", "Creator", "creator@example.com", INITIAL_PASSWORD, List.of("PROJECT_CREATOR"));
        MockHttpSession creator = login("creator", INITIAL_PASSWORD);
        String hashBefore = passwordHash("root");

        mockMvc.perform(get("/api/admin/users").session(creator))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("PERMISSION_DENIED"));
        assertForbidden(createUser(creator, "ada", "Ada", "ada@example.com", INITIAL_PASSWORD));
        assertForbidden(setRoles(creator, "root", List.of()));
        assertForbidden(resetPassword(creator, "root", REPLACEMENT_PASSWORD));
        assertThat(passwordHash("root")).isEqualTo(hashBefore);
        assertThat(appRoles("root")).containsExactly("SYSTEM_ADMIN");
        assertThat(loginStatus("root", ADMIN_PASSWORD)).isEqualTo(200);
    }

    @Test
    void migrated_user_can_sign_in_after_an_admin_sets_a_password() throws Exception {
        users.create("root", "Root", "root@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        users.create("migrated", "Migrated User", "migrated@example.com", null, List.of());
        MvcResult blocked = postLogin(new MockHttpSession(), "migrated", REPLACEMENT_PASSWORD);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(json.readTree(blocked.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("PASSWORD_RESET_REQUIRED");

        MockHttpSession admin = login("root", ADMIN_PASSWORD);
        JsonNode before = json.readTree(
                mockMvc.perform(get("/api/admin/users").session(admin).param("size", "10"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(userNamed(before, "migrated").path("passwordResetRequired").asBoolean())
                .isTrue();

        assertThat(resetPassword(admin, "migrated", REPLACEMENT_PASSWORD)
                        .getResponse()
                        .getStatus())
                .isEqualTo(204);

        assertThat(loginStatus("migrated", REPLACEMENT_PASSWORD)).isEqualTo(200);
        JsonNode after = json.readTree(
                mockMvc.perform(get("/api/admin/users").session(admin).param("size", "10"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(userNamed(after, "migrated").path("passwordResetRequired").asBoolean())
                .isFalse();
    }

    @Test
    void cannot_remove_the_last_system_admin() throws Exception {
        users.create("root", "Root", "root@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        MockHttpSession admin = login("root", ADMIN_PASSWORD);

        MvcResult rejected = setRoles(admin, "root", List.of("PROJECT_CREATOR"));
        assertThat(rejected.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(rejected.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("LAST_SYSTEM_ADMIN");
        assertThat(appRoles("root")).containsExactly("SYSTEM_ADMIN");

        createUser(admin, "ada", "Ada", "ada@example.com", INITIAL_PASSWORD);
        assertThat(setRoles(admin, "ada", List.of("SYSTEM_ADMIN")).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(setRoles(admin, "root", List.of()).getResponse().getStatus()).isEqualTo(200);
        assertThat(appRoles("root")).isEmpty();

        MockHttpSession ada = login("ada", INITIAL_PASSWORD);
        MvcResult last = setRoles(ada, "ada", List.of());
        assertThat(last.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(last.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("LAST_SYSTEM_ADMIN");
        assertThat(appRoles("ada")).containsExactly("SYSTEM_ADMIN");
    }

    @Test
    void rejects_a_short_password_a_bad_user_id_and_a_duplicate() throws Exception {
        users.create("root", "Root", "root@example.com", ADMIN_PASSWORD, List.of("SYSTEM_ADMIN"));
        MockHttpSession admin = login("root", ADMIN_PASSWORD);

        assertThat(problemCode(createUser(admin, "amy", "Amy", "amy@example.com", "12345678901")))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(userCount("amy")).isZero();
        assertThat(createUser(admin, "amy", "Amy", "amy@example.com", "123456789012")
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        assertThat(problemCode(resetPassword(admin, "amy", "12345678901"))).isEqualTo("VALIDATION_FAILED");
        assertThat(loginStatus("amy", "123456789012")).isEqualTo(200);

        String longest = "a".repeat(64);
        assertThat(createUser(admin, longest, "Long", "long@example.com", INITIAL_PASSWORD)
                        .getResponse()
                        .getStatus())
                .isEqualTo(201);
        assertThat(problemCode(createUser(admin, longest + "b", "Longer", "longer@example.com", INITIAL_PASSWORD)))
                .isEqualTo("VALIDATION_FAILED");
        assertThat(problemCode(createUser(admin, "bad@id", "Amy", "amy@example.com", INITIAL_PASSWORD)))
                .isEqualTo("VALIDATION_FAILED");

        MvcResult duplicate = createUser(admin, "amy", "Amy Again", "other@example.com", INITIAL_PASSWORD);
        assertThat(duplicate.getResponse().getStatus()).isEqualTo(409);
        assertThat(json.readTree(duplicate.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("CONFLICT");
        assertThat(userCount("amy")).isEqualTo(1);
        assertThat(displayName("amy")).isEqualTo("Amy");

        MvcResult missing = resetPassword(admin, "missing", REPLACEMENT_PASSWORD);
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(missing.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
        MvcResult missingRoles = setRoles(admin, "missing", List.of("PROJECT_CREATOR"));
        assertThat(missingRoles.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(missingRoles.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("NOT_FOUND");
    }

    private void assertForbidden(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(json.readTree(result.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("PERMISSION_DENIED");
    }

    private MvcResult createUser(
            MockHttpSession session, String userId, String displayName, String email, String password)
            throws Exception {
        Cookie csrf = csrf(session);
        return mockMvc.perform(post("/api/admin/users")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new CreateBody(userId, displayName, email, password))))
                .andReturn();
    }

    private MvcResult resetPassword(MockHttpSession session, String userId, String password) throws Exception {
        Cookie csrf = csrf(session);
        return mockMvc.perform(put("/api/admin/users/" + userId + "/password")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new PasswordBody(password))))
                .andReturn();
    }

    private MvcResult setRoles(MockHttpSession session, String userId, List<String> appRoles) throws Exception {
        Cookie csrf = csrf(session);
        return mockMvc.perform(put("/api/admin/users/" + userId + "/app-roles")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RolesBody(appRoles))))
                .andReturn();
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MockHttpSession session = new MockHttpSession();
        assertThat(postLogin(session, username, password).getResponse().getStatus())
                .isEqualTo(200);
        return session;
    }

    private int loginStatus(String username, String password) throws Exception {
        return postLogin(new MockHttpSession(), username, password)
                .getResponse()
                .getStatus();
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

    private String problemCode(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        return json.readTree(result.getResponse().getContentAsString())
                .path("code")
                .asString();
    }

    private String passwordHash(String userId) {
        return jdbc.sql("select password_hash from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private List<String> appRoles(String userId) {
        return jdbc.sql("select app_roles from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> List.of((String[]) rs.getArray(1).getArray()))
                .single();
    }

    private String displayName(String userId) {
        return jdbc.sql("select display_name from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getString(1))
                .single();
    }

    private int userCount(String userId) {
        return jdbc.sql("select count(*) from users where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private record Login(String username, String password) {}

    private record CreateBody(String userId, String displayName, String email, String initialPassword) {}

    private record PasswordBody(String newPassword) {}

    private record RolesBody(List<String> appRoles) {}
}
