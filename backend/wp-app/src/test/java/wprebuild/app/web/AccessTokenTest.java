package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.UserId;

@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class AccessTokenTest {

    private static final Instant START = Instant.parse("2026-06-01T00:00:00Z");

    private static final Duration ACCESS_TTL = Duration.ofHours(12);

    private static final Duration REFRESH_TTL = Duration.ofDays(30);

    private static final String ACCESS = "wpa_[0-9A-Za-z]{43}";

    private static final String REFRESH = "wpr_[0-9A-Za-z]{43}";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final AtomicReference<Instant> now = new AtomicReference<>(START);

    @MockitoBean
    private java.time.Clock clock;

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    AccessTokenTest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcClient jdbc) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void freezeClock() {
        now.set(START);
        lenient().when(clock.instant()).thenAnswer(invocation -> now.get());
        lenient().when(clock.millis()).thenAnswer(invocation -> now.get().toEpochMilli());
        lenient().when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    @Test
    void bearer_token_authenticates_until_the_access_token_expires() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of("PROJECT_CREATOR"));

        MvcResult created = issue("alice", "correct-password", "phone");
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        assertThat(created.getRequest().getSession(false)).isNull();
        assertThat(created.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode body = json.readTree(created.getResponse().getContentAsString());
        String accessToken = body.path("accessToken").asString();
        String refreshToken = body.path("refreshToken").asString();
        assertThat(accessToken).matches(ACCESS);
        assertThat(refreshToken).matches(REFRESH);
        assertThat(accessToken).isNotEqualTo(refreshToken);
        assertThat(Instant.parse(body.path("accessExpiresAt").asString())).isEqualTo(START.plus(ACCESS_TTL));
        assertThat(Instant.parse(body.path("refreshExpiresAt").asString())).isEqualTo(START.plus(REFRESH_TTL));
        assertThat(body.path("password").isMissingNode()).isTrue();
        assertThat(body.toString()).doesNotContain("sha256");

        Stored row = stored("phone");
        assertThat(row.userId()).isEqualTo("alice");
        assertThat(row.accessDigest()).isEqualTo(sha256(accessToken));
        assertThat(row.refreshDigest()).isEqualTo(sha256(refreshToken));
        assertThat(row.lastUsedAt()).isNull();
        assertThat(row.revokedAt()).isNull();
        assertThat(new String(row.accessDigest(), StandardCharsets.ISO_8859_1)).doesNotContain(accessToken);
        assertThat(new String(row.refreshDigest(), StandardCharsets.ISO_8859_1)).doesNotContain(refreshToken);

        MvcResult authed = me(accessToken);
        assertThat(authed.getResponse().getStatus()).isEqualTo(200);
        assertThat(authed.getRequest().getSession(false)).isNull();
        JsonNode me = json.readTree(authed.getResponse().getContentAsString());
        assertThat(me.path("userId").asString()).isEqualTo("alice");
        assertThat(me.path("displayName").asString()).isEqualTo("Alice");
        assertThat(me.path("appRoles").get(0).asString()).isEqualTo("PROJECT_CREATOR");
        assertThat(me.path("passwordHash").isMissingNode()).isTrue();
        assertThat(stored("phone").lastUsedAt()).isNotNull();

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + refreshToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "bearer " + accessToken))
                .andExpect(status().isOk());

        Instant accessExpiresAt = Instant.parse(body.path("accessExpiresAt").asString());
        now.set(accessExpiresAt);
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
        now.set(accessExpiresAt.minusMillis(1));
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk());
    }

    @Test
    void refresh_rotates_tokens_and_replay_revokes_only_that_device() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        JsonNode phone = issued("alice", "correct-password", "phone");
        JsonNode tablet = issued("alice", "correct-password", "tablet");
        String phoneAccess = phone.path("accessToken").asString();
        String phoneRefresh = phone.path("refreshToken").asString();
        String tabletAccess = tablet.path("accessToken").asString();
        UUID phoneId = deviceId(phoneAccess, "phone");

        MvcResult unknown = refresh("wpr_" + "b".repeat(43));
        assertThat(unknown.getResponse().getStatus()).isEqualTo(401);
        assertThat(problem(unknown).path("code").asString()).isNotEqualTo("REFRESH_TOKEN_REUSED");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + phoneAccess))
                .andExpect(status().isOk());

        JsonNode first = refreshed(phoneRefresh);
        String firstRefresh = first.path("refreshToken").asString();
        JsonNode secondBody = refreshed(firstRefresh);
        String latestAccess = secondBody.path("accessToken").asString();
        assertThat(latestAccess).isNotEqualTo(phoneAccess);
        assertThat(deviceId(latestAccess, "phone")).isEqualTo(phoneId);
        assertThat(devices(latestAccess)).hasSize(2);
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + phoneAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + latestAccess))
                .andExpect(status().isOk());
        assertThat(countRotated(sha256(phoneRefresh))).isEqualTo(1);
        assertThat(countRotated(sha256(firstRefresh))).isEqualTo(1);

        MvcResult replay = refresh(phoneRefresh);
        assertThat(replay.getResponse().getStatus()).isEqualTo(401);
        assertThat(replay.getResponse().getContentAsString()).doesNotContain(phoneRefresh);
        assertThat(problem(replay).path("code").asString()).isEqualTo("REFRESH_TOKEN_REUSED");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + latestAccess))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + tabletAccess))
                .andExpect(status().isOk());
        JsonNode remaining = devices(tabletAccess);
        assertThat(remaining).hasSize(1);
        assertThat(remaining.get(0).path("deviceName").asString()).isEqualTo("tablet");
        assertThat(remaining.get(0).path("accessToken").isMissingNode()).isTrue();
        assertThat(stored("phone").revokedAt()).isNotNull();
        assertThat(stored("tablet").revokedAt()).isNull();
    }

    @Test
    void owner_can_revoke_a_device_and_cannot_revoke_someone_elses() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        users.create("bob", "Bob", "bob@example.com", "other-password", List.of());
        JsonNode alice = issued("alice", "correct-password", "phone");
        String aliceAccess = alice.path("accessToken").asString();
        String aliceRefresh = alice.path("refreshToken").asString();
        UUID aliceDevice = deviceId(aliceAccess, "phone");
        JsonNode bob = issued("bob", "other-password", "tablet");

        mockMvc.perform(delete("/api/me/devices/" + aliceDevice)
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + bob.path("accessToken").asString()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceAccess))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/me/devices/" + aliceDevice)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceAccess))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + aliceAccess))
                .andExpect(status().isUnauthorized());
        MvcResult refresh = refresh(aliceRefresh);
        assertThat(refresh.getResponse().getStatus()).isEqualTo(401);
        assertThat(problem(refresh).path("code").asString()).isNotEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(countActive("alice")).isZero();
    }

    @Test
    void bearer_skips_csrf_and_does_not_fall_back_to_the_session() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        MockHttpSession session = new MockHttpSession();
        assertThat(login(session, "alice", "correct-password").getResponse().getStatus())
                .isEqualTo(200);
        String accessToken =
                issued("alice", "correct-password", "phone").path("accessToken").asString();

        mockMvc.perform(post("/api/me/api-keys")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("nightly"))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("denied"))))
                .andExpect(status().isForbidden());

        String wrong = "wpa_" + "a".repeat(43);
        mockMvc.perform(get("/api/auth/me").session(session).header(HttpHeaders.AUTHORIZATION, "Bearer " + wrong))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/me/api-keys")
                        .session(session)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + wrong)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Name("sneaky"))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        assertThat(countKeys("alice")).isEqualTo(1);
    }

    @Test
    void expired_refresh_token_is_rejected_without_the_reuse_code() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        JsonNode body = issued("alice", "correct-password", "phone");
        now.set(Instant.parse(body.path("refreshExpiresAt").asString()));

        MvcResult refresh = refresh(body.path("refreshToken").asString());
        assertThat(refresh.getResponse().getStatus()).isEqualTo(401);
        assertThat(problem(refresh).path("code").asString()).isNotEqualTo("REFRESH_TOKEN_REUSED");
        assertThat(stored("phone").revokedAt()).isNull();
    }

    @Test
    void bad_credentials_and_a_blocked_account_do_not_mint_tokens() throws Exception {
        users.create("alice", "Alice", "alice@example.com", "correct-password", List.of());
        assertThat(issue("alice", "wrong-password", "phone").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(issue("alice", "correct-password", "  ").getResponse().getStatus())
                .isEqualTo(400);
        assertThat(issue("alice", "correct-password", "x".repeat(201))
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);
        assertThat(countActive("alice")).isZero();

        users.create("migrated", "Migrated", "migrated@example.com", null, List.of());
        MvcResult blocked = issue("migrated", "any-password", "phone");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(401);
        assertThat(problem(blocked).path("code").asString()).isEqualTo("PASSWORD_RESET_REQUIRED");
        assertThat(countActive("migrated")).isZero();

        JsonNode body = issued("alice", "correct-password", "phone");
        jdbc.sql("update users set password_hash = null where user_id = ?")
                .param("alice")
                .update();
        mockMvc.perform(get("/api/auth/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + body.path("accessToken").asString()))
                .andExpect(status().isUnauthorized());
        MvcResult refresh = refresh(body.path("refreshToken").asString());
        assertThat(refresh.getResponse().getStatus()).isEqualTo(401);
        assertThat(problem(refresh).path("code").asString()).isEqualTo("PASSWORD_RESET_REQUIRED");
        assertThat(stored("phone").refreshDigest())
                .isEqualTo(sha256(body.path("refreshToken").asString()));

        users.setPassword(new UserId("alice"), "chosen-password");
        mockMvc.perform(get("/api/auth/me")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                "Bearer " + body.path("accessToken").asString()))
                .andExpect(status().isOk());
    }

    private JsonNode issued(String username, String password, String deviceName) throws Exception {
        MvcResult result = issue(username, password, deviceName);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode refreshed(String refreshToken) throws Exception {
        MvcResult result = refresh(refreshToken);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(result.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(result.getRequest().getSession(false)).isNull();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult issue(String username, String password, String deviceName) throws Exception {
        return mockMvc.perform(post("/api/auth/tokens")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Issue(username, password, deviceName))))
                .andReturn();
    }

    private MvcResult refresh(String refreshToken) throws Exception {
        return mockMvc.perform(post("/api/auth/tokens/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Refresh(refreshToken))))
                .andReturn();
    }

    private MvcResult me(String accessToken) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andReturn();
    }

    private JsonNode devices(String accessToken) throws Exception {
        MvcResult result = mockMvc.perform(
                        get("/api/me/devices").header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID deviceId(String accessToken, String deviceName) throws Exception {
        JsonNode devices = devices(accessToken);
        for (JsonNode device : devices) {
            if (deviceName.equals(device.path("deviceName").asString())) {
                return UUID.fromString(device.path("tokenId").asString());
            }
        }
        throw new AssertionError("device " + deviceName + " missing from " + devices);
    }

    private JsonNode problem(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentType()).contains("application/problem+json");
        return json.readTree(result.getResponse().getContentAsString());
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

    private Stored stored(String deviceName) {
        return jdbc.sql("""
                        select user_id, access_sha256, refresh_sha256, last_used_at, revoked_at
                        from access_tokens
                        where device_name = ?
                        """)
                .param(deviceName)
                .query((rs, row) -> new Stored(
                        rs.getString("user_id"),
                        rs.getBytes("access_sha256"),
                        rs.getBytes("refresh_sha256"),
                        rs.getObject("last_used_at", OffsetDateTime.class),
                        rs.getObject("revoked_at", OffsetDateTime.class)))
                .single();
    }

    private int countRotated(byte[] digest) {
        return jdbc.sql("select count(*) from rotated_refresh_tokens where refresh_sha256 = ?")
                .param(digest)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private int countActive(String userId) {
        return jdbc.sql("select count(*) from access_tokens where user_id = ? and revoked_at is null")
                .param(userId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private int countKeys(String userId) {
        return jdbc.sql("select count(*) from api_keys where user_id = ?")
                .param(userId)
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private static byte[] sha256(String token) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private record Issue(String username, String password, String deviceName) {}

    private record Refresh(String refreshToken) {}

    private record Login(String username, String password) {}

    private record Name(String name) {}

    private record Stored(
            String userId,
            byte[] accessDigest,
            byte[] refreshDigest,
            OffsetDateTime lastUsedAt,
            OffsetDateTime revokedAt) {}
}
