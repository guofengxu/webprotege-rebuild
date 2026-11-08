package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
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
import wprebuild.app.access.ApiKeyService;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.web.security.ApiKeyAuthenticationFilter;
import wprebuild.kernel.id.UserId;

@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyTest {

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String TOKEN = "wpk_[0-9A-Za-z]{43}";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final ApiKeyService apiKeys;

    private final JdbcClient jdbc;

    ApiKeyTest(MockMvc mockMvc, JsonMapper json, UserService users, ApiKeyService apiKeys, JdbcClient jdbc) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.apiKeys = apiKeys;
        this.jdbc = jdbc;
    }

    @Test
    void issued_key_can_call_me_until_it_is_revoked() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of("PROJECT_CREATOR"));
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);

        MvcResult created = createKey(session, "ci");
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String apiKey = body.path("apiKey").asString();
        UUID keyId = UUID.fromString(body.path("keyId").asString());
        assertThat(apiKey).matches(TOKEN);
        assertThat(body.path("name").asString()).isEqualTo("ci");
        assertThat(body.path("plaintext").isMissingNode()).isTrue();
        assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/me/api-keys/" + keyId);
        assertThat(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(created.getResponse().getContentAsString()).doesNotContain("keySha256");

        MvcResult listed = mockMvc.perform(get("/api/me/api-keys").session(session))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(listed.getResponse().getContentAsString()).doesNotContain(apiKey);
        JsonNode keys = json.readTree(listed.getResponse().getContentAsString());
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).path("keyId").asString()).isEqualTo(keyId.toString());
        assertThat(keys.get(0).path("name").asString()).isEqualTo("ci");
        assertThat(keys.get(0).path("apiKey").isMissingNode()).isTrue();
        assertThat(keys.get(0).path("lastUsedAt").isNull()).isTrue();

        MvcResult authed = mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(authed.getRequest().getSession(false)).isNull();
        JsonNode me =
                json.readTree(mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(me.path("userId").asString()).isEqualTo("alice");
        assertThat(me.path("displayName").asString()).isEqualTo("Alice");
        assertThat(me.path("appRoles").get(0).asString()).isEqualTo("PROJECT_CREATOR");
        assertThat(me.path("passwordHash").isMissingNode()).isTrue();

        JsonNode afterUse =
                json.readTree(mockMvc.perform(get("/api/me/api-keys").session(session))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
        assertThat(afterUse.get(0).path("lastUsedAt").asString()).isNotBlank();

        Cookie csrf = csrf(session);
        mockMvc.perform(delete("/api/me/api-keys/" + keyId)
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isUnauthorized());
        assertThat(countKeys(keyId)).isZero();
    }

    @Test
    void database_stores_the_digest_not_the_plaintext() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        JsonNode body = json.readTree(createKey(session, "ci").getResponse().getContentAsString());
        String apiKey = body.path("apiKey").asString();
        UUID keyId = UUID.fromString(body.path("keyId").asString());

        Stored row = jdbc.sql("""
                        select user_id, name, key_sha256
                        from api_keys
                        where key_id = ?
                        """)
                .param(keyId)
                .query((rs, ignored) ->
                        new Stored(rs.getString("user_id"), rs.getString("name"), rs.getBytes("key_sha256")))
                .single();

        assertThat(row.userId()).isEqualTo("alice");
        assertThat(row.name()).isEqualTo("ci");
        assertThat(row.digest()).hasSize(32);
        assertThat(row.digest())
                .isEqualTo(MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8)));
        assertThat(row.name()).doesNotContain(apiKey);
        assertThat(new String(row.digest(), StandardCharsets.ISO_8859_1)).doesNotContain(apiKey);
    }

    @Test
    void cannot_revoke_another_users_key() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        users.create("bob", "Bob", "bob@example.com", "other-password", List.of());
        MockHttpSession alice = new MockHttpSession();
        assertThat(login(alice, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        JsonNode body = json.readTree(createKey(alice, "ci").getResponse().getContentAsString());
        String apiKey = body.path("apiKey").asString();
        UUID keyId = UUID.fromString(body.path("keyId").asString());

        MockHttpSession bob = new MockHttpSession();
        assertThat(login(bob, "bob", "other-password").getResponse().getStatus())
                .isEqualTo(200);
        Cookie csrf = csrf(bob);
        mockMvc.perform(delete("/api/me/api-keys/" + keyId)
                        .session(bob)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isOk());
        assertThat(countKeys(keyId)).isEqualTo(1);
    }

    @Test
    void api_key_post_does_not_require_csrf() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        String apiKey = json.readTree(createKey(session, "ci").getResponse().getContentAsString())
                .path("apiKey")
                .asString();

        mockMvc.perform(post("/api/me/api-keys")
                        .header(ApiKeyAuthenticationFilter.HEADER, apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("nightly"))))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("denied"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void presented_api_key_does_not_fall_back_to_the_session() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        String wrong = "wpk_" + "a".repeat(43);

        mockMvc.perform(get("/api/auth/me").session(session).header(ApiKeyAuthenticationFilter.HEADER, wrong))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, "not-a-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void bogus_api_key_does_not_let_a_session_skip_csrf() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        String wrong = "wpk_" + "b".repeat(43);

        mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .header(ApiKeyAuthenticationFilter.HEADER, wrong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("sneaky"))))
                .andExpect(status().isUnauthorized());

        assertThat(countKeysFor("alice")).isZero();
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void api_key_does_not_sign_in_an_account_that_must_reset_its_password() throws Exception {
        users.create("migrated", "Migrated", "migrated@example.com", null, List.of());
        String apiKey = apiKeys.issue(new UserId("migrated"), "ci").plaintext();

        mockMvc.perform(get("/api/auth/me").header(ApiKeyAuthenticationFilter.HEADER, apiKey))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blank_name_is_rejected() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        Cookie csrf = csrf(session);
        mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("  "))))
                .andExpect(status().isBadRequest());
        assertThat(countKeysFor("alice")).isZero();
    }

    private MvcResult createKey(MockHttpSession session, String name) throws Exception {
        Cookie csrf = csrf(session);
        return mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name(name))))
                .andReturn();
    }

    private MvcResult login(MockHttpSession session, String username, String password) throws Exception {
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
        if (csrf == null) {
            throw new AssertionError("CSRF cookie missing");
        }
        return csrf;
    }

    private int countKeys(UUID keyId) {
        return jdbc.sql("select count(*) from api_keys where key_id = ?")
                .param(keyId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private int countKeysFor(String userId) {
        return jdbc.sql("select count(*) from api_keys where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private record Login(String username, String password) {}

    private record Name(String name) {}

    private record Stored(String userId, String name, byte[] digest) {}
}
