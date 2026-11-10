package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.search.DataDirectoryProperties;
import wprebuild.app.search.ProjectSearchIndex;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Entity search over the per-project Lucene index (P3-19). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class SearchTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String THING = "http://www.w3.org/2002/07/owl#Thing";

    private static final String TOP_OBJECT = "http://www.w3.org/2002/07/owl#topObjectProperty";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectSearchIndex index;

    private final DataDirectoryProperties dataDirectory;

    SearchTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectSearchIndex index,
            DataDirectoryProperties dataDirectory) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.index = index;
        this.dataDirectory = dataDirectory;
    }

    @Test
    void chinese_query_matches_a_longer_label() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        createClass(owner, projectId, "周转托盘");

        JsonNode hits = search(owner, projectId, "托盘", null, 20);
        assertThat(names(hits)).contains("周转托盘");
    }

    @Test
    void english_prefix_matches_pallet() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        createClass(owner, projectId, "Pallet");

        JsonNode hits = search(owner, projectId, "pal", null, 20);
        assertThat(names(hits)).contains("Pallet");
    }

    @Test
    void created_entity_is_searchable_immediately() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        long revision = createClass(owner, projectId, "Carton");

        JsonNode hits = search(owner, projectId, "car", null, 20);
        assertThat(names(hits)).contains("Carton");
        assertThat(index.indexedRevision(projectId)).isEqualTo(revision);
    }

    @Test
    void deleted_index_directory_is_rebuilt_when_reopened() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        long revision = createClass(owner, projectId, "Bin");
        assertThat(names(search(owner, projectId, "bin", null, 20))).contains("Bin");

        index.close(projectId);
        deleteTree(dataDirectory.indexDirectory(projectId));

        JsonNode hits = search(owner, projectId, "bin", null, 20);
        assertThat(names(hits)).contains("Bin");
        assertThat(index.indexedRevision(projectId)).isEqualTo(revision);
        assertThat(Files.isDirectory(dataDirectory.indexDirectory(projectId))).isTrue();
    }

    @Test
    void type_filter_keeps_the_requested_entity_types() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = createProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        createClass(owner, projectId, "Pallet");
        createEntity(owner, projectId, "OBJECT_PROPERTY", "palletId", List.of(TOP_OBJECT));

        assertThat(names(search(owner, projectId, "pal", "class", 20))).containsExactly("Pallet");
        assertThat(names(search(viewer, projectId, "pal", "object-property", 20)))
                .containsExactly("palletId");
        assertThat(names(search(owner, projectId, "pal", "class,object-property", 20)))
                .containsExactlyInAnyOrder("Pallet", "palletId");

        MvcResult denied = mockMvc.perform(get("/api/projects/{id}/search", projectId.toString())
                        .param("q", "pal")
                        .session(stranger.session()))
                .andReturn();
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void full_name_does_not_match_a_different_entity() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);
        createClass(owner, projectId, "周转托盘");
        createClass(owner, projectId, "托盘架");
        createClass(owner, projectId, "Part");
        createClass(owner, projectId, "has-part");
        createClass(owner, projectId, "Order");

        assertThat(names(search(owner, projectId, "周转托盘", null, 20)))
                .contains("周转托盘")
                .doesNotContain("托盘架");
        assertThat(names(search(owner, projectId, "托盘", null, 20))).contains("周转托盘", "托盘架");
        assertThat(names(search(owner, projectId, "has-part", null, 20)))
                .contains("has-part")
                .doesNotContain("Part");
        assertThat(names(search(owner, projectId, "ord", "class", 20)))
                .contains("Order")
                .doesNotContain("Part");
    }

    @Test
    void blank_query_unknown_type_and_oversized_limit_are_rejected() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = createProject(owner);

        assertThat(searchStatus(owner, projectId, " ", null, null)).isEqualTo(400);
        assertThat(searchStatus(owner, projectId, "pal", "individual", null)).isEqualTo(400);
        assertThat(searchStatus(owner, projectId, "pal", null, "201")).isEqualTo(400);
    }

    private long createClass(SignedIn owner, ProjectId projectId, String name) throws Exception {
        return createEntity(owner, projectId, "CLASS", name, List.of(THING));
    }

    private long createEntity(SignedIn owner, ProjectId projectId, String type, String name, List<String> parents)
            throws Exception {
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                new CreateEntitiesBody(type, List.of(name), null, parents));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return read(created).path("revision").asLong();
    }

    private JsonNode search(SignedIn user, ProjectId projectId, String query, String types, int limit)
            throws Exception {
        var request = get("/api/projects/{id}/search", projectId.toString())
                .param("q", query)
                .param("limit", Integer.toString(limit))
                .session(user.session());
        if (types != null) {
            request.param("types", types);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private int searchStatus(SignedIn user, ProjectId projectId, String query, String types, String limit)
            throws Exception {
        var request = get("/api/projects/{id}/search", projectId.toString())
                .param("q", query)
                .session(user.session());
        if (types != null) {
            request.param("types", types);
        }
        if (limit != null) {
            request.param("limit", limit);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(read(result).path("code").asString()).isEqualTo("VALIDATION_FAILED");
        return result.getResponse().getStatus();
    }

    private SignedIn owner() throws Exception {
        account("owner", "PROJECT_CREATOR");
        return login("owner");
    }

    private ProjectId createProject(SignedIn owner) throws Exception {
        MvcResult created = postJson(owner, "/api/projects", new CreateProject("Warehouse", "Stock", null));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return mockMvc.perform(post(path)
                        .session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body)))
                .andReturn();
    }

    private void account(String userId, String... appRoles) {
        users.create(userId, userId, userId + "@example.com", PASSWORD, List.of(appRoles));
    }

    private SignedIn login(String username) throws Exception {
        MockHttpSession session = new MockHttpSession();
        Cookie csrf = csrfCookie(session);
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .session(session)
                        .cookie(csrf)
                        .header(CSRF_HEADER, csrf.getValue())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new Login(username, PASSWORD))))
                .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
        return new SignedIn(session, csrfAfter(login, csrf));
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

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static List<String> names(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static void deleteTree(Path root) throws IOException {
        if (Files.notExists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}

    private record CreateEntitiesBody(String entityType, List<String> names, String lang, List<String> parents) {}
}
