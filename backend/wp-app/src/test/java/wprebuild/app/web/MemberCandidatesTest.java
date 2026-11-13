package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.Iterator;
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
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

/** GET /api/projects/{id}/member-candidates (P6-02). */
@DatabaseTest
@AutoConfigureMockMvc
class MemberCandidatesTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    MemberCandidatesTest(MockMvc mockMvc, JsonMapper json, UserService users) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
    }

    @Test
    void matches_user_id_prefix_and_display_name_substring() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        createUser("alice-" + suffix, "Alice Smith");
        createUser("bob-" + suffix, "周转托盘");
        createUser("carol-" + suffix, "Carol");

        JsonNode byId = read(search(owner, projectId, "alice", 10));
        assertThat(ids(byId)).containsExactly("alice-" + suffix);

        JsonNode byName = read(search(owner, projectId, "周转", 10));
        assertThat(ids(byName)).containsExactly("bob-" + suffix);
        assertThat(byName.get(0).path("displayName").asString()).isEqualTo("周转托盘");

        JsonNode caseInsensitive = read(search(owner, projectId, "ALICE", 10));
        assertThat(ids(caseInsensitive)).containsExactly("alice-" + suffix);
    }

    @Test
    void sorts_exact_user_id_then_prefix_then_display_name() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        String marker = "zx" + suffix;
        createUser(marker, "Other");
        createUser(marker + "a", "Prefix A");
        createUser(marker + "b", "Prefix B");
        createUser("nn-" + suffix + "-a", marker + " in name");
        createUser("nn-" + suffix + "-b", "also " + marker);

        JsonNode hits = read(search(owner, projectId, marker, 20));
        assertThat(ids(hits))
                .containsExactly(marker, marker + "a", marker + "b", "nn-" + suffix + "-a", "nn-" + suffix + "-b");
    }

    @Test
    void like_wildcards_are_literal() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        createUser("a_b-" + suffix, "underscore");
        createUser("axb-" + suffix, "wildcard bait");
        createUser("pct-" + suffix, "has%sign");

        JsonNode underscore = read(search(owner, projectId, "a_b", 10));
        assertThat(ids(underscore)).containsExactly("a_b-" + suffix);
        assertThat(ids(underscore)).doesNotContain("axb-" + suffix);

        JsonNode percent = read(search(owner, projectId, "has%", 10));
        assertThat(ids(percent)).containsExactly("pct-" + suffix);
    }

    @Test
    void limit_and_query_length_are_validated() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        for (int i = 0; i < 5; i++) {
            createUser("lim" + i + "-" + suffix, "Limit User " + i);
        }

        JsonNode capped = read(search(owner, projectId, "lim", 2));
        assertThat(capped.size()).isEqualTo(2);

        assertThat(search(owner, projectId, "lim", 21).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(search(owner, projectId, "a", 10).getResponse().getStatus()).isEqualTo(400);
        assertThat(search(owner, projectId, "  a  ", 10).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(search(owner, projectId, "x".repeat(65), 10).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(search(owner, projectId, "  ok  ", 10).getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void response_keys_are_only_user_id_and_display_name() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        ProjectId projectId = projectId(create(owner));
        createUser("keys-" + suffix, "Key User");

        JsonNode hits = read(search(owner, projectId, "keys", 10));
        assertThat(hits).isNotEmpty();
        for (JsonNode hit : hits) {
            List<String> names = new ArrayList<>();
            Iterator<String> it = hit.propertyNames().iterator();
            while (it.hasNext()) {
                names.add(it.next());
            }
            assertThat(names).containsExactlyInAnyOrder("userId", "displayName");
            assertThat(hit.path("email").isMissingNode()).isTrue();
            assertThat(hit.path("appRoles").isMissingNode()).isTrue();
            assertThat(hit.path("passwordResetRequired").isMissingNode()).isTrue();
        }
    }

    @Test
    void only_managers_and_system_admins_may_search() throws Exception {
        SignedIn owner = account("owner", "PROJECT_CREATOR");
        SignedIn editor = account("editor");
        SignedIn stranger = account("stranger");
        SignedIn admin = account("admin", "SYSTEM_ADMIN");
        ProjectId projectId = projectId(create(owner));
        grant(owner, projectId, editor, ProjectRole.CAN_EDIT);
        createUser("seek-" + suffix, "Seekable");

        assertThat(search(editor, projectId, "seek", 10).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(search(stranger, projectId, "seek", 10).getResponse().getStatus())
                .isEqualTo(403);

        MockHttpSession anonymous = new MockHttpSession();
        assertThat(mockMvc.perform(get(candidates(projectId, "seek", 10)).session(anonymous))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);

        assertThat(search(owner, projectId, "seek", 10).getResponse().getStatus())
                .isEqualTo(200);
        assertThat(ids(read(search(admin, projectId, "seek", 10)))).contains("seek-" + suffix);
    }

    private void createUser(String userId, String displayName) {
        users.create(userId, displayName, userId + "@example.com", PASSWORD, List.of());
    }

    private static List<String> ids(JsonNode hits) {
        List<String> values = new ArrayList<>();
        for (JsonNode hit : hits) {
            values.add(hit.path("userId").asString());
        }
        return values;
    }

    private MvcResult search(SignedIn user, ProjectId projectId, String query, int limit) throws Exception {
        return send(
                user,
                get("/api/projects/" + projectId + "/member-candidates")
                        .param("q", query)
                        .param("limit", String.valueOf(limit)));
    }

    private static String candidates(ProjectId projectId, String query, int limit) {
        return "/api/projects/" + projectId + "/member-candidates?q=" + query + "&limit=" + limit;
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
        String userId = "p602-" + role + "-" + suffix;
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
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isBetween(200, 299);
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
