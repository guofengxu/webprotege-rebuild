package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.web.security.DeletedAccountFilter;
import wprebuild.kernel.id.UserId;

/** Soft-deleted accounts lose their browser sessions on the next request (P6-52). */
@DatabaseTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "wp.security.deleted-check-ttl=30s")
class DeletedUserSessionTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String ADMIN_PASSWORD = "admin-password";

    private static final String USER_PASSWORD = "user-password-12";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final SessionRegistry sessions;

    private final DeletedAccountFilter deletedAccounts;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    DeletedUserSessionTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            SessionRegistry sessions,
            DeletedAccountFilter deletedAccounts) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.sessions = sessions;
        this.deletedAccounts = deletedAccounts;
    }

    @Test
    void deleted_users_session_is_rejected_while_others_keep_working() throws Exception {
        SignedIn admin = admin();
        SignedIn alice = account("alice");
        SignedIn bob = account("bob");

        // /api/projects does not itself soft-delete-check; the filter must return 401.
        mockMvc.perform(get("/api/projects").session(alice.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").session(bob.session())).andExpect(status().isOk());

        assertThat(deleteUser(admin, alice.userId()).getResponse().getStatus()).isEqualTo(204);

        MvcResult rejected =
                mockMvc.perform(get("/api/projects").session(alice.session())).andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(rejected.getResponse().getContentType()).contains("application/problem+json");
        assertThat(rejected.getResponse().getHeader("Location")).isNull();

        mockMvc.perform(get("/api/projects").session(bob.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/projects").session(admin.session())).andExpect(status().isOk());
    }

    @Test
    void session_without_registry_entry_is_rejected_after_delete() throws Exception {
        SignedIn admin = admin();
        SignedIn alice = account("ghost");
        mockMvc.perform(get("/api/projects").session(alice.session())).andExpect(status().isOk());

        clearRegistry(alice.userId());
        assertThat(sessions.getAllSessions(alice.userId(), true)).isEmpty();

        assertThat(deleteUser(admin, alice.userId()).getResponse().getStatus()).isEqualTo(204);

        MvcResult rejected =
                mockMvc.perform(get("/api/projects").session(alice.session())).andReturn();
        assertThat(rejected.getResponse().getStatus()).isEqualTo(401);
        assertThat(rejected.getResponse().getContentType()).contains("application/problem+json");
    }

    @Test
    void deleted_check_cache_avoids_repeat_lookups_within_ttl() throws Exception {
        SignedIn alice = account("cached");
        // Login primes /me for the CSRF cookie; drop that entry so this test owns the counters.
        deletedAccounts.invalidate(new UserId(alice.userId()));
        long missesBefore = deletedAccounts.cacheStats().missCount();
        long hitsBefore = deletedAccounts.cacheStats().hitCount();
        long loadsBefore = deletedAccounts.cacheStats().loadCount();

        mockMvc.perform(get("/api/auth/me").session(alice.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(alice.session())).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(alice.session())).andExpect(status().isOk());

        assertThat(deletedAccounts.cacheStats().missCount() - missesBefore).isEqualTo(1);
        assertThat(deletedAccounts.cacheStats().hitCount() - hitsBefore).isEqualTo(2);
        assertThat(deletedAccounts.cacheStats().loadCount() - loadsBefore).isEqualTo(1);
    }

    private void clearRegistry(String userId) {
        for (SessionInformation info : List.copyOf(sessions.getAllSessions(userId, true))) {
            sessions.removeSessionInformation(info.getSessionId());
        }
    }

    private SignedIn admin() throws Exception {
        String userId = "root-" + suffix + "-" + UUID.randomUUID().toString().substring(0, 4);
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

    private MvcResult deleteUser(SignedIn actor, String userId) throws Exception {
        return mockMvc.perform(delete("/api/admin/users/" + userId)
                        .session(actor.session())
                        .cookie(actor.csrf())
                        .header(CSRF_HEADER, actor.csrf().getValue()))
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

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}
}
