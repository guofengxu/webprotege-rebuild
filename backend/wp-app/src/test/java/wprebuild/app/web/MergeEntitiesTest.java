package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.events.EntityFrameChanged;
import wprebuild.app.events.EntityHierarchyChanged;
import wprebuild.app.events.ProjectEvent;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.testsupport.TestOntologies;

/** Merge entities API (P6-24 / BS-13). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class MergeEntitiesTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String DOCK = WH + "Dock";

    private static final String INBOUND = WH + "InboundDock";

    private static final String OUTBOUND = WH + "OutboundDock";

    private static final String LOCATION = WH + "Location";

    private static final String CONTAINS = WH + "contains";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String ALT_LABEL = SKOSVocabulary.ALTLABEL.getIRI().toString();

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectEventHub hub;

    MergeEntitiesTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectEventHub hub) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.hub = hub;
    }

    @Test
    void deprecate_merge_moves_children_demotes_labels_and_records_history() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);

        JsonNode before = children(owner, projectId, DOCK);
        assertThat(iris(before)).containsExactlyInAnyOrder(INBOUND, OUTBOUND);

        List<ProjectEvent> seen = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        ProjectEventHub.Subscription subscription =
                hub.subscribe(projectId, new UserId("owner"), OptionalLong.empty(), collecting(seen, latch));
        long revision;
        try {
            MvcResult merged = merge(owner, projectId, List.of(DOCK), LOCATION, "DEPRECATE_MERGED_ENTITY", null);
            assertThat(merged.getResponse().getStatus())
                    .as(merged.getResponse().getContentAsString())
                    .isEqualTo(200);
            JsonNode body = read(merged);
            assertThat(body.path("target").path("iri").asString()).isEqualTo(LOCATION);
            assertThat(body.path("target").path("entityType").asString()).isEqualTo("CLASS");
            revision = body.path("revision").asLong();
            assertThat(revision).isPositive();
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            subscription.close();
        }

        assertThat(iris(children(owner, projectId, LOCATION))).contains(INBOUND, OUTBOUND);
        assertThat(iris(children(owner, projectId, DOCK))).isEmpty();

        JsonNode frame = classFrame(owner, projectId, LOCATION);
        assertThat(altLabels(frame)).contains("月台", "Dock");

        JsonNode dock = classFrame(owner, projectId, DOCK);
        assertThat(dock.path("subject").path("deprecated").asBoolean()).isTrue();

        JsonNode history = revisions(owner, projectId);
        assertThat(history.path("revisions")).anySatisfy(node -> {
            assertThat(node.path("revNo").asLong()).isEqualTo(revision);
            assertThat(node.path("description").asString()).isEqualTo("Merged 1 entities into Location");
        });

        Set<String> types = new HashSet<>();
        for (ProjectEvent event : seen) {
            types.add(event.type());
        }
        assertThat(types).contains("EntityHierarchyChanged", "EntityFrameChanged");
    }

    @Test
    void delete_merge_removes_source_from_hierarchy_and_search_and_can_be_reverted() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);

        MvcResult merged =
                merge(owner, projectId, List.of(DOCK), LOCATION, "DELETE_MERGED_ENTITY", "Merge dock into location");
        assertThat(merged.getResponse().getStatus())
                .as(merged.getResponse().getContentAsString())
                .isEqualTo(200);
        long revision = read(merged).path("revision").asLong();

        assertThat(iris(children(owner, projectId, LOCATION))).contains(INBOUND, OUTBOUND);
        JsonNode roots = roots(owner, projectId);
        assertThat(iris(roots)).doesNotContain(DOCK);

        JsonNode hits = search(owner, projectId, "Dock");
        assertThat(iris(hits)).doesNotContain(DOCK);

        MvcResult reverted = postPath(owner, "/api/projects/" + projectId + "/revisions/" + revision + "/revert");
        assertThat(reverted.getResponse().getStatus())
                .as(reverted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(iris(children(owner, projectId, DOCK))).containsExactlyInAnyOrder(INBOUND, OUTBOUND);
        assertThat(iris(children(owner, projectId, LOCATION))).doesNotContain(INBOUND, OUTBOUND);
    }

    @Test
    void can_view_forbidden_can_edit_allowed_and_permissions_list_merge_action() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("editor");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn editor = login("editor");
        ProjectId projectId = uploadWarehouse(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);

        MvcResult denied = merge(viewer, projectId, List.of(DOCK), LOCATION, null, null);
        assertThat(denied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(denied).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MvcResult allowed = merge(editor, projectId, List.of(DOCK), LOCATION, null, null);
        assertThat(allowed.getResponse().getStatus())
                .as(allowed.getResponse().getContentAsString())
                .isEqualTo(200);

        JsonNode permissions = read(getJson(editor, "/api/projects/" + projectId + "/permissions/me"));
        assertThat(actionNames(permissions)).contains("MERGE_ENTITIES");
    }

    @Test
    void validation_errors_map_to_problem_details_and_trashed_is_conflict() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);

        MvcResult unknown = merge(owner, projectId, List.of(WH + "Missing"), LOCATION, null, null);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(unknown).path("code").asString()).isEqualTo("ENTITY_NOT_FOUND");

        MvcResult targetInSources = merge(owner, projectId, List.of(DOCK, LOCATION), LOCATION, null, null);
        assertThat(targetInSources.getResponse().getStatus()).isEqualTo(400);
        JsonNode targetBody = read(targetInSources);
        assertThat(targetBody.path("code").asString()).isEqualTo("INVALID_MERGE");
        assertThat(targetBody.path("reason").asString()).isEqualTo("TARGET_IN_SOURCES");

        MvcResult typeMismatch = merge(owner, projectId, List.of(CONTAINS), LOCATION, null, null);
        assertThat(typeMismatch.getResponse().getStatus()).isEqualTo(400);
        JsonNode typeBody = read(typeMismatch);
        assertThat(typeBody.path("code").asString()).isEqualTo("INVALID_MERGE");
        assertThat(typeBody.path("reason").asString()).isEqualTo("TYPE_MISMATCH");

        MvcResult builtIn = merge(owner, projectId, List.of(THING), LOCATION, null, null);
        assertThat(builtIn.getResponse().getStatus()).isEqualTo(400);
        JsonNode builtInBody = read(builtIn);
        assertThat(builtInBody.path("code").asString()).isEqualTo("INVALID_MERGE");
        assertThat(builtInBody.path("reason").asString()).isEqualTo("BUILT_IN_ENTITY");

        assertThat(postPath(owner, "/api/projects/" + projectId + "/trash")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult trashed = merge(owner, projectId, List.of(DOCK), LOCATION, null, null);
        assertThat(trashed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(trashed).path("code").asString()).isEqualTo("PROJECT_TRASHED");
    }

    private static ProjectEventHub.LiveSink collecting(List<ProjectEvent> seen, CountDownLatch latch) {
        return new ProjectEventHub.LiveSink() {
            @Override
            public void onEvents(ProjectEventHub.Delivery delivery) {
                boolean hierarchy = false;
                boolean frame = false;
                for (ProjectEvent event : delivery.events()) {
                    seen.add(event);
                    if (event instanceof EntityHierarchyChanged) {
                        hierarchy = true;
                    }
                    if (event instanceof EntityFrameChanged) {
                        frame = true;
                    }
                }
                if (hierarchy && frame) {
                    latch.countDown();
                }
            }

            @Override
            public void onHeartbeat() {}

            @Override
            public void onShutdown() {}
        };
    }

    private ProjectId uploadWarehouse(SignedIn owner) throws Exception {
        Path file = TestOntologies.dir().resolve("warehouse-sample.ttl");
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
        MvcResult created = mockMvc.perform(multipart("/api/projects/upload")
                        .file(part)
                        .param("name", "Warehouse")
                        .param("description", "sample")
                        .session(owner.session())
                        .cookie(owner.csrf())
                        .header(CSRF_HEADER, owner.csrf().getValue()))
                .andReturn();
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
    }

    private MvcResult merge(
            SignedIn user,
            ProjectId projectId,
            List<String> sources,
            String target,
            String treatment,
            String description)
            throws Exception {
        return postJson(
                user,
                "/api/projects/" + projectId + "/entities/merge",
                new MergeBody(sources, target, treatment, description));
    }

    private JsonNode children(SignedIn user, ProjectId projectId, String parent) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/children", projectId.toString())
                        .param("parent", parent)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode roots(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/roots", projectId.toString())
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode classFrame(SignedIn user, ProjectId projectId, String iri) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/frames/class", projectId.toString())
                        .param("iri", iri)
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode search(SignedIn user, ProjectId projectId, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/search", projectId.toString())
                        .param("q", query)
                        .param("limit", "50")
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private JsonNode revisions(SignedIn user, ProjectId projectId) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/revisions", projectId.toString())
                        .param("page", "0")
                        .param("size", "50")
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return read(result);
    }

    private MvcResult postJson(SignedIn user, String path, Object body) throws Exception {
        return send(user, post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private MvcResult postPath(SignedIn user, String path) throws Exception {
        return send(user, post(path));
    }

    private MvcResult getJson(SignedIn user, String path) throws Exception {
        return mockMvc.perform(get(path).session(user.session())).andReturn();
    }

    private MvcResult send(
            SignedIn user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
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

    private static List<String> iris(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("iri").asString()));
        return values;
    }

    private static List<String> altLabels(JsonNode frame) {
        List<String> values = new ArrayList<>();
        frame.path("annotations").forEach(annotation -> {
            if (ALT_LABEL.equals(annotation.path("property").path("iri").asString())) {
                values.add(annotation.path("value").path("lexical").asString());
            }
        });
        return values;
    }

    private static List<String> actionNames(JsonNode body) {
        List<String> values = new ArrayList<>();
        body.path("actions").forEach(node -> values.add(node.asString()));
        return values;
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record MergeBody(List<String> sources, String target, String treatment, String description) {}
}
