package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.integration.HostResolver;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Webhook management API (P6-49 / BS-15).
 *
 * <p>GateGuard facts: called by Surefire as {@code WebhookManagementTest}; Glob found none;
 * uses {@code project_webhooks}/{@code webhook_deliveries}; user asked {@code 完成 P6-49任务卡}.
 */
@DatabaseTest
@AutoConfigureMockMvc
@Import(WebhookManagementTest.StubDns.class)
@TestPropertySource(properties = "wp.webhooks.enabled=false")
class WebhookManagementTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String PUBLIC_URL = "https://203.0.113.10/hooks/notify";

    private static final String SECRET = "sixteen-chars!!!";

    private final MockMvc mockMvc;
    private final JsonMapper json;
    private final UserService users;
    private final JdbcRoleAssignmentRepository roles;
    private final JdbcClient jdbc;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    WebhookManagementTest(
            MockMvc mockMvc, JsonMapper json, UserService users, JdbcRoleAssignmentRepository roles, JdbcClient jdbc) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.jdbc = jdbc;
    }

    @Test
    void manager_can_crud_replace_and_clear_secret() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Hooks"));

        MvcResult created = postJson(
                owner,
                path(projectId),
                Map.of(
                        "payloadUrl",
                        PUBLIC_URL,
                        "eventTypes",
                        List.of("PROJECT_CHANGED"),
                        "secret",
                        SECRET,
                        "enabled",
                        true));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        JsonNode body = read(created);
        assertThat(body.path("hasSecret").asBoolean()).isTrue();
        assertThat(body.path("payloadUrl").asString()).isEqualTo(PUBLIC_URL);
        assertThat(body.toString()).doesNotContain(SECRET);
        assertThat(body.toString().toLowerCase()).doesNotContain("\"secret\"");
        String webhookId = body.path("webhookId").asString();

        MvcResult listed = getJson(owner, path(projectId));
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(listed)).hasSize(1);
        assertThat(read(listed).toString()).doesNotContain(SECRET);

        MvcResult replaced = putJson(
                owner,
                path(projectId, webhookId),
                Map.of(
                        "payloadUrl",
                        PUBLIC_URL,
                        "eventTypes",
                        List.of("PROJECT_CHANGED"),
                        "secretChange",
                        "REPLACE",
                        "secret",
                        "another-sixteen!!",
                        "enabled",
                        true));
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(replaced).path("hasSecret").asBoolean()).isTrue();
        assertThat(read(replaced).toString()).doesNotContain("another-sixteen!!");

        MvcResult cleared = putJson(
                owner,
                path(projectId, webhookId),
                Map.of(
                        "payloadUrl",
                        PUBLIC_URL,
                        "eventTypes",
                        List.of("PROJECT_CHANGED"),
                        "secretChange",
                        "CLEAR",
                        "enabled",
                        true));
        assertThat(cleared.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(cleared).path("hasSecret").asBoolean()).isFalse();

        MvcResult deliveries = getJson(owner, path(projectId, webhookId) + "/deliveries");
        assertThat(deliveries.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(deliveries).isArray()).isTrue();
        assertThat(read(deliveries)).isEmpty();

        MvcResult missingEnabled = putJson(
                owner,
                path(projectId, webhookId),
                Map.of("payloadUrl", PUBLIC_URL, "eventTypes", List.of("PROJECT_CHANGED"), "secretChange", "KEEP"));
        assertThat(missingEnabled.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(missingEnabled).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        assertThat(deletePath(owner, path(projectId, webhookId)).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(read(getJson(owner, path(projectId)))).isEmpty();
    }

    @Test
    void disabling_cancels_pending_deliveries() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Hooks"));
        String webhookId = createWebhook(owner, projectId, PUBLIC_URL, true);
        insertPending(UUID.fromString(webhookId), 1L);
        insertPending(UUID.fromString(webhookId), 2L);

        MvcResult disabled = putJson(
                owner,
                path(projectId, webhookId),
                Map.of(
                        "payloadUrl",
                        PUBLIC_URL,
                        "eventTypes",
                        List.of("PROJECT_CHANGED"),
                        "secretChange",
                        "KEEP",
                        "enabled",
                        false));
        assertThat(disabled.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(disabled).path("enabled").asBoolean()).isFalse();

        List<String> statuses = jdbc.sql("""
                        select status from webhook_deliveries where webhook_id = ? order by delivery_id
                        """)
                .param(UUID.fromString(webhookId))
                .query((rs, row) -> rs.getString(1))
                .list();
        assertThat(statuses).containsExactly("CANCELLED", "CANCELLED");
    }

    @Test
    void url_policy_errors_and_limit_and_authz() throws Exception {
        SignedIn owner = owner();
        SignedIn editor = account("editor");
        ProjectId projectId = projectId(create(owner, "Hooks"));
        roles.assign(projectId, new UserId(editor.userId()), ProjectRole.CAN_EDIT);

        assertCode(
                postJson(
                        owner,
                        path(projectId),
                        Map.of(
                                "payloadUrl",
                                "ftp://203.0.113.10/hook",
                                "eventTypes",
                                List.of("PROJECT_CHANGED"),
                                "enabled",
                                true)),
                400,
                "INVALID_WEBHOOK_URL");
        assertCode(
                postJson(
                        owner,
                        path(projectId),
                        Map.of(
                                "payloadUrl",
                                "https://no-such-webhook-host.invalid/hook",
                                "eventTypes",
                                List.of("PROJECT_CHANGED"),
                                "enabled",
                                true)),
                400,
                "WEBHOOK_URL_UNRESOLVABLE");
        assertCode(
                postJson(
                        owner,
                        path(projectId),
                        Map.of(
                                "payloadUrl",
                                "http://127.0.0.1/hook",
                                "eventTypes",
                                List.of("PROJECT_CHANGED"),
                                "enabled",
                                true)),
                400,
                "WEBHOOK_TARGET_NOT_ALLOWED");

        for (int i = 0; i < 10; i++) {
            createWebhook(owner, projectId, "https://203.0.113.10/hooks/" + i, true);
        }
        assertCode(
                postJson(
                        owner,
                        path(projectId),
                        Map.of(
                                "payloadUrl",
                                "https://203.0.113.10/hooks/overflow",
                                "eventTypes",
                                List.of("PROJECT_CHANGED"),
                                "enabled",
                                true)),
                409,
                "WEBHOOK_LIMIT_REACHED");

        assertThat(getJson(editor, path(projectId)).getResponse().getStatus()).isEqualTo(403);
        assertThat(mockMvc.perform(get(path(projectId)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
        assertThat(getJson(owner, "/api/projects/" + UUID.randomUUID() + "/webhooks")
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);

        ProjectId other = projectId(create(owner, "Other"));
        String foreign = createWebhook(owner, other, PUBLIC_URL, true);
        assertThat(getJson(owner, path(projectId, foreign) + "/deliveries")
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        assertThat(deletePath(owner, path(projectId, foreign)).getResponse().getStatus())
                .isEqualTo(404);

        assertThat(postPath(owner, "/api/projects/" + projectId + "/trash")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertCode(getJson(owner, path(projectId)), 409, "PROJECT_TRASHED");
    }

    private void assertCode(MvcResult result, int status, String code) throws Exception {
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(status);
        assertThat(read(result).path("code").asString()).isEqualTo(code);
    }

    private String createWebhook(SignedIn user, ProjectId projectId, String url, boolean enabled) throws Exception {
        MvcResult created = postJson(
                user,
                path(projectId),
                Map.of("payloadUrl", url, "eventTypes", List.of("PROJECT_CHANGED"), "enabled", enabled));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return read(created).path("webhookId").asString();
    }

    private void insertPending(UUID webhookId, long outboxId) {
        jdbc.sql("""
                        insert into webhook_deliveries (
                            webhook_id, outbox_id, event_type, payload, status, attempts
                        ) values (
                            ?, ?, 'PROJECT_CHANGED', cast('{"projectId":"x"}' as jsonb), 'PENDING', 0
                        )
                        """).param(webhookId).param(outboxId).update();
    }

    private String path(ProjectId projectId) {
        return "/api/projects/" + projectId + "/webhooks";
    }

    private String path(ProjectId projectId, String webhookId) {
        return path(projectId) + "/" + webhookId;
    }

    private SignedIn owner() throws Exception {
        return account("owner", "PROJECT_CREATOR");
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p649-" + role + "-" + suffix;
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

    private MvcResult create(SignedIn user, String name) throws Exception {
        return postJson(user, "/api/projects", new CreateProject(name, "Hooks", null));
    }

    private MvcResult getJson(SignedIn user, String path) throws Exception {
        return send(user, get(path));
    }

    private MvcResult postPath(SignedIn user, String path) throws Exception {
        return send(user, post(path));
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
        return json.readTree(result.getResponse().getContentAsString());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubDns {
        @Bean
        @Primary
        HostResolver refusingResolver() {
            return host -> {
                throw new UnknownHostException(host);
            };
        }
    }

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}
}
