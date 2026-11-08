package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.BootstrapAdmin;
import wprebuild.app.access.BootstrapAdminProperties;
import wprebuild.app.access.JdbcUserRepository;
import wprebuild.app.access.UserAccount;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.UserId;

@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcUserRepository repository;

    private final JdbcClient jdbc;

    private final BootstrapAdmin bootstrapAdmin;

    private final PlatformTransactionManager transactions;

    AuthFlowTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcUserRepository repository,
            JdbcClient jdbc,
            BootstrapAdmin bootstrapAdmin,
            PlatformTransactionManager transactions) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.repository = repository;
        this.jdbc = jdbc;
        this.bootstrapAdmin = bootstrapAdmin;
        this.transactions = transactions;
    }

    @Test
    void login_then_me_returns_the_user() throws Exception {
        users.create(
                "alice",
                "Alice Doe",
                "alice@example.com",
                "correct-password",
                List.of("SYSTEM_ADMIN", "PROJECT_CREATOR"));

        MockHttpSession session = new MockHttpSession();
        MvcResult login = postLogin(session, "alice", "correct-password");
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        assertThat(login.getResponse().getContentAsString()).doesNotContain("passwordHash");

        SecurityContext stored = (SecurityContext)
                session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
        assertThat(stored.getAuthentication().getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("SYSTEM_ADMIN", "PROJECT_CREATOR");

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("alice"))
                .andExpect(jsonPath("$.displayName").value("Alice Doe"))
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.appRoles[0]").value("SYSTEM_ADMIN"))
                .andExpect(jsonPath("$.appRoles[1]").value("PROJECT_CREATOR"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());

        String hash = jdbc.sql("select password_hash from users where user_id = 'alice'")
                .query((rs, row) -> rs.getString(1))
                .single();
        assertThat(hash).startsWith("{bcrypt}");
    }

    @Test
    void wrong_password_returns_401() throws Exception {
        users.create("bob", "Bob", "bob@example.com", "correct-password", List.of());
        MvcResult result = postLogin(new MockHttpSession(), "bob", "wrong-password");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        assertThat(result.getResponse().getContentAsString()).doesNotContain("PASSWORD_RESET_REQUIRED");
    }

    @Test
    void unknown_user_returns_401() throws Exception {
        MvcResult result = postLogin(new MockHttpSession(), "nobody", "whatever-password");
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("PASSWORD_RESET_REQUIRED");
    }

    @Test
    void unauthenticated_me_returns_401_problem_detail() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andReturn();

        Cookie csrf = result.getResponse().getCookie(CSRF_COOKIE);
        assertThat(csrf).isNotNull();
        assertThat(csrf.isHttpOnly()).isFalse();
        assertThat(csrf.getValue()).isNotBlank();
        assertThat(result.getResponse().getCookie("JSESSIONID")).isNull();
    }

    @Test
    void login_without_csrf_returns_403_problem_detail() throws Exception {
        users.create("cara", "Cara", "cara@example.com", "correct-password", List.of());
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login("cara", "correct-password"))))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION));
    }

    @Test
    void empty_password_hash_returns_password_reset_required() throws Exception {
        users.create("migrated", "Migrated User", "migrated@example.com", null, List.of());
        MvcResult blocked = postLogin(new MockHttpSession(), "migrated", "any-password");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(blocked.getResponse().getContentType()).contains("application/problem+json");
        assertThat(json.readTree(blocked.getResponse().getContentAsString())
                        .path("code")
                        .asString())
                .isEqualTo("PASSWORD_RESET_REQUIRED");

        users.setPassword(new UserId("migrated"), "chosen-password");
        MvcResult signedIn = postLogin(new MockHttpSession(), "migrated", "chosen-password");
        assertThat(signedIn.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void logout_ends_the_session() throws Exception {
        users.create("dora", "Dora", "dora@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        MvcResult login = postLogin(session, "dora", "correct-password");
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        Cookie csrf = csrfAfter(login, csrfCookie(session));

        mockMvc.perform(post("/api/auth/logout").session(session).cookie(csrf).header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void set_password_rejects_a_password_bcrypt_would_truncate() {
        users.create("erin", "Erin", "erin@example.com", "correct-password", List.of());
        String tooLong = "x".repeat(73);
        assertThatThrownBy(() -> users.setPassword(new UserId("erin"), tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void bootstrap_admin_runs_only_when_the_users_table_is_empty() {
        emptyUsers();
        bootstrapAdmin.run(null);
        assertThat(countUsers()).isZero();

        admin(new BootstrapAdminProperties("only-name", null, null)).run(null);
        assertThat(countUsers()).isZero();

        BootstrapAdmin configured = admin(new BootstrapAdminProperties("root", "bootstrap-secret", "root@example.com"));
        configured.run(null);
        UserAccount created = users.findById(new UserId("root")).orElseThrow();
        assertThat(created.displayName()).isEqualTo("root");
        assertThat(created.email()).isEqualTo("root@example.com");
        assertThat(created.appRoles()).containsExactly("SYSTEM_ADMIN");
        assertThat(created.passwordHash()).startsWith("{bcrypt}");
        assertThat(created.passwordResetRequired()).isFalse();

        configured.run(null);
        assertThat(countUsers()).isEqualTo(1);

        users.create("other", "Other", "other@example.com", "other-password", List.of());
        admin(new BootstrapAdminProperties("second", "bootstrap-secret", "second@example.com"))
                .run(null);
        assertThat(users.findById(new UserId("second"))).isEmpty();
        assertThat(countUsers()).isEqualTo(2);
    }

    private BootstrapAdmin admin(BootstrapAdminProperties properties) {
        return new BootstrapAdmin(properties, users, repository, transactions);
    }

    private MvcResult postLogin(MockHttpSession session, String username, String password) throws Exception {
        Cookie csrf = csrfCookie(session);
        return mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, password))))
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

    private void emptyUsers() {
        jdbc.sql("delete from revision_subjects").update();
        jdbc.sql("delete from revisions").update();
        jdbc.sql("delete from snapshots").update();
        jdbc.sql("delete from project_settings").update();
        jdbc.sql("delete from role_assignments").update();
        jdbc.sql("delete from outbox").update();
        jdbc.sql("delete from projects").update();
        jdbc.sql("delete from api_keys").update();
        jdbc.sql("delete from users").update();
    }

    private int countUsers() {
        return jdbc.sql("select count(*) from users")
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private record Login(String username, String password) {}
}
