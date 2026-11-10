package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Project prefixes, new-entity rules, and display languages (BS-06, BS-09, P3-22). */
@DatabaseTest
@AutoConfigureMockMvc
class ProjectSettingsTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static final String LABEL = "http://www.w3.org/2000/01/rdf-schema#label";

    private static final String PREF = "http://www.w3.org/2004/02/skos/core#prefLabel";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final JdbcClient jdbc;

    private final ProjectEventHub hub;

    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    ProjectSettingsTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            JdbcClient jdbc,
            ProjectEventHub hub) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.jdbc = jdbc;
        this.hub = hub;
    }

    @Test
    void viewer_reads_defaults_and_cannot_change_them() throws Exception {
        SignedIn owner = owner();
        SignedIn viewer = account("viewer");
        ProjectId projectId = projectId(create(owner, "Warehouse"));
        roles.assign(projectId, new UserId(viewer.userId()), ProjectRole.CAN_VIEW);

        mockMvc.perform(get("/api/projects/" + projectId + "/settings/prefixes"))
                .andExpect(
                        status -> assertThat(status.getResponse().getStatus()).isEqualTo(401));
        assertThat(read(getJson(viewer, "/api/projects/" + projectId + "/settings/prefixes"))
                        .size())
                .isZero();
        JsonNode entity = read(getJson(viewer, "/api/projects/" + projectId + "/settings/new-entity"));
        assertThat(entity.path("iriPrefix").asString()).isEqualTo("http://www.example.org/");
        assertThat(entity.path("kit").path("type").asString()).isEqualTo("UUID");
        assertThat(entity.path("labelProperty").asString()).isEqualTo(LABEL);
        assertThat(entity.path("defaultLang").asString()).isEmpty();
        assertThat(entity.path("targetOntology").asString()).isEqualTo("o1");
        assertThat(read(getJson(viewer, "/api/projects/" + projectId + "/settings/display-names")))
                .isEmpty();

        MvcResult denied = putJson(viewer, "/api/projects/" + projectId + "/settings/prefixes", json.readTree("""
                {"wh":"http://example.org/wh#"}
                """));
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");
        assertThat(putJson(viewer, "/api/projects/" + projectId + "/settings/new-entity", entity)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(putJson(viewer, "/api/projects/" + projectId + "/settings/display-names", json.createArrayNode())
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    void manager_replaces_prefixes_and_new_entity_rules() throws Exception {
        SignedIn owner = owner();
        SignedIn editor = account("editor");
        ProjectId projectId = projectId(create(owner, "Warehouse"));
        roles.assign(projectId, new UserId(editor.userId()), ProjectRole.CAN_EDIT);

        MvcResult editorDenied =
                putJson(editor, "/api/projects/" + projectId + "/settings/prefixes", json.readTree("""
                {"box":"http://example.org/box#"}
                """));
        assertThat(editorDenied.getResponse().getStatus()).isEqualTo(403);

        MvcResult prefixes = putJson(owner, "/api/projects/" + projectId + "/settings/prefixes", json.readTree("""
                {"box":"http://example.org/box#","ex":"http://example.org/ex#"}
                """));
        assertThat(prefixes.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(prefixes).path("box").asString()).isEqualTo("http://example.org/box#");
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateBody("CLASS", List.of("box:Cart"), null, List.of(THING)));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(read(created).path("entities").get(0).path("iri").asString())
                .isEqualTo("http://example.org/box#Cart");

        MvcResult rules = putJson(
                owner, "/api/projects/" + projectId + "/settings/new-entity", json.readTree("""
                {"iriPrefix":"http://example.org/id/","kit":{"type":"SUPPLIED_NAME","whiteSpace":"CAMEL_CASE"},
                 "labelProperty":"%s","defaultLang":"EN","targetOntology":"o1"}
                """.formatted(LABEL)));
        assertThat(rules.getResponse().getStatus())
                .as(rules.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(rules).path("kit").path("whiteSpace").asString()).isEqualTo("CAMEL_CASE");
        assertThat(read(rules).path("defaultLang").asString()).isEqualTo("en");
        MvcResult named = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateBody("CLASS", List.of("red box"), null, List.of(THING)));
        assertThat(named.getResponse().getStatus())
                .as(named.getResponse().getContentAsString())
                .isEqualTo(201);
        assertThat(read(named).path("entities").get(0).path("iri").asString())
                .isEqualTo("http://example.org/id/redBox");
    }

    @Test
    void display_language_change_rebuilds_names_and_sends_resync() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Warehouse"));
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateBody("CLASS", List.of("Alpha"), null, List.of(THING)));
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        String iri = read(created).path("entities").get(0).path("iri").asString();
        assertThat(displayName(owner, projectId, iri)).isEqualTo("Alpha");
        assertThat(searchIris(owner, projectId, "Alpha")).contains(iri);

        CountDownLatch resync = new CountDownLatch(1);
        AtomicReference<ProjectEventHub.Delivery> seen = new AtomicReference<>();
        ProjectEventHub.Subscription subscription =
                hub.subscribe(projectId, OptionalLong.empty(), new ProjectEventHub.LiveSink() {
                    @Override
                    public void onEvents(ProjectEventHub.Delivery delivery) {
                        if (delivery.resync()) {
                            seen.set(delivery);
                            resync.countDown();
                        }
                    }

                    @Override
                    public void onHeartbeat() {}

                    @Override
                    public void onShutdown() {}
                });
        try {
            MvcResult names = putJson(
                    owner, "/api/projects/" + projectId + "/settings/display-names", json.readTree("""
                    [{"annotationProperty":"%s","lang":"de"}]
                    """.formatted(
                                    PREF)));
            assertThat(names.getResponse().getStatus())
                    .as(names.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(read(names).get(0).path("lang").asString()).isEqualTo("de");
            assertThat(resync.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(seen.get().resync()).isTrue();
            assertThat(displayName(owner, projectId, iri)).isNotEqualTo("Alpha");
            assertThat(searchIris(owner, projectId, "Alpha")).doesNotContain(iri);
        } finally {
            subscription.close();
        }
    }

    @Test
    void migrated_document_shape_is_what_the_api_returns() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Warehouse"));
        jdbc.sql("""
                        insert into project_settings (project_id, key, value)
                        values (?, 'new-entity', cast(? as jsonb))
                        """).param(projectId.value()).param("""
                        {"iriPrefix":"http://example.org/wh#","kit":{"type":"SUPPLIED_NAME","whiteSpace":"UNDERSCORES"},
                         "labelProperty":"%s","defaultLang":"en","targetOntology":"o1"}
                        """.formatted(LABEL)).update();
        jdbc.sql("""
                        insert into project_settings (project_id, key, value)
                        values (?, 'display-names', cast(? as jsonb))
                        """).param(projectId.value()).param("""
                        [{"annotationProperty":"%s","lang":"en"},{"annotationProperty":"%s","lang":"de"}]
                        """.formatted(LABEL, PREF)).update();

        JsonNode entity = read(getJson(owner, "/api/projects/" + projectId + "/settings/new-entity"));
        assertThat(entity.path("kit").path("type").asString()).isEqualTo("SUPPLIED_NAME");
        assertThat(entity.path("kit").path("whiteSpace").asString()).isEqualTo("UNDERSCORES");
        JsonNode names = read(getJson(owner, "/api/projects/" + projectId + "/settings/display-names"));
        assertThat(names).hasSize(2);
        assertThat(names.get(1).path("annotationProperty").asString()).isEqualTo(PREF);
    }

    @Test
    void invalid_documents_and_a_trashed_project_are_rejected() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Warehouse"));

        MvcResult badPrefix = putJson(owner, "/api/projects/" + projectId + "/settings/prefixes", json.readTree("""
                {"a:b":"http://example.org/a#"}
                """));
        assertThat(badPrefix.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(badPrefix).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        MvcResult badEntity = putJson(
                owner, "/api/projects/" + projectId + "/settings/new-entity", json.readTree("""
                {"iriPrefix":"not-an-iri","kit":{"type":"CUSTOM"},"labelProperty":"%s",
                 "defaultLang":"","targetOntology":"o1"}
                """.formatted(LABEL)));
        assertThat(badEntity.getResponse().getStatus()).isEqualTo(400);
        assertThat(getJson(owner, "/api/projects/not-a-uuid/settings/prefixes")
                        .getResponse()
                        .getStatus())
                .isEqualTo(400);

        assertThat(postPath(owner, "/api/projects/" + projectId + "/trash")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult trashed = getJson(owner, "/api/projects/" + projectId + "/settings/prefixes");
        assertThat(trashed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(trashed).path("code").asString()).isEqualTo("PROJECT_TRASHED");
    }

    private String displayName(SignedIn user, ProjectId projectId, String iri) throws Exception {
        MvcResult result = send(
                user,
                get("/api/projects/" + projectId + "/hierarchies/class/children")
                        .param("parent", THING));
        for (JsonNode child : read(result)) {
            if (iri.equals(child.path("iri").asString())) {
                return child.path("displayName").asString();
            }
        }
        throw new AssertionError("missing " + iri + " in " + read(result));
    }

    private List<String> searchIris(SignedIn user, ProjectId projectId, String query) throws Exception {
        MvcResult result = send(
                user,
                get("/api/projects/" + projectId + "/search").param("q", query).param("limit", "20"));
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        List<String> iris = new java.util.ArrayList<>();
        read(result).forEach(node -> iris.add(node.path("iri").asString()));
        return iris;
    }

    private SignedIn owner() throws Exception {
        return account("owner", "PROJECT_CREATOR");
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p322-" + role + "-" + suffix;
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
        return postJson(user, "/api/projects", new CreateProject(name, "Stock", null));
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

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record CreateBody(String entityType, List<String> names, String lang, List<String> parents) {}
}
