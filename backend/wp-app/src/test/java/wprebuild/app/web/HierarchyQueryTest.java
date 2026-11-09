package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
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
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.testsupport.TestOntologies;

/** Hierarchy roots, children, and paths for the four BS-08 trees (P3-13). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class HierarchyQueryTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String TOP_OBJECT =
            OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY.getIRI().toString();

    private static final String TOP_DATA =
            OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY.getIRI().toString();

    private static final String RDFS_LABEL =
            OWLRDFVocabulary.RDFS_LABEL.getIRI().toString();

    private static final String RDFS_COMMENT =
            OWLRDFVocabulary.RDFS_COMMENT.getIRI().toString();

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    HierarchyQueryTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectRuntimeRegistry registry) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.registry = registry;
    }

    @Test
    void class_hierarchy_returns_roots_sorted_children_and_paths() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner);

        JsonNode roots = ok(owner, projectId, "class/roots");
        assertThat(roots).hasSize(1);
        assertNode(roots.get(0), THING, "CLASS", "owl:Thing", true, false);

        // BS-09: the default language tag is empty, so @zh and @en labels fall through to the local name.
        assertThat(names(ok(owner, projectId, "class/children", "parent", THING)))
                .containsExactly(
                        "Aisle",
                        "Dock",
                        "Equipment",
                        "HandlingUnit",
                        "Inventory",
                        "Location",
                        "Lot",
                        "Operator",
                        "Rack",
                        "Sku",
                        "Task",
                        "Warehouse",
                        "Workstation",
                        "Zone");

        JsonNode locations = ok(owner, projectId, "class/children", "parent", WH + "Location");
        assertThat(names(locations)).containsExactly("BufferLocation", "PickingLocation", "StorageLocation");
        assertThat(locations).allSatisfy(node -> {
            assertThat(node.path("entityType").asString()).isEqualTo("CLASS");
            assertThat(node.path("hasChildren").asBoolean()).isFalse();
            assertThat(node.path("deprecated").asBoolean()).isFalse();
        });
        assertThat(names(ok(owner, projectId, "class/children", "parent", WH + "Equipment")))
                .containsExactly("Agv", "Conveyor", "StackerCrane");

        JsonNode paths = ok(owner, projectId, "class/paths", "entity", WH + "StorageLocation");
        assertThat(paths).hasSize(1);
        assertThat(iris(paths.get(0))).containsExactly(THING, WH + "Location", WH + "StorageLocation");
        assertThat(names(paths.get(0))).containsExactly("owl:Thing", "Location", "StorageLocation");
    }

    @Test
    void property_hierarchies_use_their_own_roots() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner);

        JsonNode objectRoots = ok(owner, projectId, "object-property/roots");
        assertThat(objectRoots).hasSize(1);
        assertNode(objectRoots.get(0), TOP_OBJECT, "OBJECT_PROPERTY", "owl:topObjectProperty", true, false);
        JsonNode objectChildren = ok(owner, projectId, "object-property/children", "parent", TOP_OBJECT);
        assertThat(names(objectChildren)).containsExactly("contains", "storedAt");
        assertThat(objectChildren)
                .allSatisfy(
                        node -> assertThat(node.path("hasChildren").asBoolean()).isFalse());
        assertThat(iris(ok(owner, projectId, "object-property/paths", "entity", WH + "contains")
                        .get(0)))
                .containsExactly(TOP_OBJECT, WH + "contains");

        JsonNode dataRoots = ok(owner, projectId, "data-property/roots");
        assertThat(dataRoots).hasSize(1);
        assertNode(dataRoots.get(0), TOP_DATA, "DATA_PROPERTY", "owl:topDataProperty", true, false);
        assertThat(names(ok(owner, projectId, "data-property/children", "parent", TOP_DATA)))
                .containsExactly("code");

        JsonNode annotationRoots = ok(owner, projectId, "annotation-property/roots");
        assertThat(names(annotationRoots)).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
        assertThat(names(annotationRoots)).contains("rdfs:comment", "rdfs:label");
        JsonNode label = find(annotationRoots, RDFS_LABEL);
        assertNode(label, RDFS_LABEL, "ANNOTATION_PROPERTY", "rdfs:label", false, false);
        assertThat(find(annotationRoots, RDFS_COMMENT).path("displayName").asString())
                .isEqualTo("rdfs:comment");
        assertThat(iris(ok(owner, projectId, "annotation-property/paths", "entity", RDFS_LABEL)
                        .get(0)))
                .containsExactly(RDFS_LABEL);
        assertThat(ok(owner, projectId, "annotation-property/children", "parent", RDFS_LABEL))
                .isEmpty();
    }

    @Test
    void unknown_iri_returns_an_empty_list() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner);
        String missing = "http://example.org/wp-test/warehouse#DoesNotExist";

        assertThat(ok(owner, projectId, "class/children", "parent", missing)).isEmpty();
        assertThat(ok(owner, projectId, "class/paths", "entity", missing)).isEmpty();
        assertThat(ok(owner, projectId, "object-property/children", "parent", missing))
                .isEmpty();

        MvcResult badKind = mockMvc.perform(
                        get("/api/projects/{id}/hierarchies/{kind}/roots", projectId.toString(), "individual")
                                .session(owner.session()))
                .andReturn();
        assertThat(badKind.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(badKind).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult relative = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/children", projectId.toString())
                        .param("parent", "Pallet")
                        .session(owner.session()))
                .andReturn();
        assertThat(relative.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void deprecated_flag_follows_owl_deprecated_true() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner);
        deprecate(projectId, WH + "Pallet");

        JsonNode units = ok(owner, projectId, "class/children", "parent", WH + "HandlingUnit");
        assertThat(names(units)).containsExactly("Bin", "Carton", "Pallet");
        assertThat(find(units, WH + "Pallet").path("deprecated").asBoolean()).isTrue();
        assertThat(find(units, WH + "Bin").path("deprecated").asBoolean()).isFalse();
        assertThat(find(units, WH + "Carton").path("deprecated").asBoolean()).isFalse();
    }

    @Test
    void viewer_can_read_and_unassigned_user_is_forbidden() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = uploadProject(owner);
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);

        assertThat(ok(viewer, projectId, "class/roots")).hasSize(1);

        MvcResult forbidden = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/roots", projectId.toString())
                        .session(stranger.session()))
                .andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MvcResult anonymous = mockMvc.perform(get("/api/projects/{id}/hierarchies/class/roots", projectId.toString()))
                .andReturn();
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(401);
    }

    private void deprecate(ProjectId projectId, String iri) {
        var ontologies = registry.get(projectId).runtime().ontologies();
        OntologyKey key = ontologies.keys().iterator().next();
        var factory = ontologies.dataFactory();
        registry.get(projectId)
                .pipeline()
                .apply(
                        new UserId("owner"),
                        context -> new GeneratedChanges<>(
                                List.of(new AddAxiom(
                                        key,
                                        factory.getOWLAnnotationAssertionAxiom(
                                                factory.getOWLDeprecated(),
                                                IRI.create(iri),
                                                factory.getOWLLiteral(true)))),
                                "deprecated"));
    }

    private JsonNode ok(SignedIn user, ProjectId projectId, String path) throws Exception {
        return ok(user, projectId, path, null, null);
    }

    private JsonNode ok(SignedIn user, ProjectId projectId, String path, String param, String value) throws Exception {
        var request = get("/api/projects/{id}/hierarchies/" + path, projectId.toString())
                .session(user.session());
        if (param != null) {
            request.param(param, value);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        return read(result);
    }

    private static void assertNode(
            JsonNode node, String iri, String type, String name, boolean hasChildren, boolean deprecated) {
        assertThat(node.path("iri").asString()).isEqualTo(iri);
        assertThat(node.path("entityType").asString()).isEqualTo(type);
        assertThat(node.path("displayName").asString()).isEqualTo(name);
        assertThat(node.path("hasChildren").isBoolean()).isTrue();
        assertThat(node.path("hasChildren").asBoolean()).isEqualTo(hasChildren);
        assertThat(node.path("deprecated").isBoolean()).isTrue();
        assertThat(node.path("deprecated").asBoolean()).isEqualTo(deprecated);
    }

    private static JsonNode find(JsonNode nodes, String iri) {
        for (JsonNode node : nodes) {
            if (iri.equals(node.path("iri").asString())) {
                return node;
            }
        }
        throw new AssertionError("missing " + iri + " in " + nodes);
    }

    private static List<String> names(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("displayName").asString()));
        return values;
    }

    private static List<String> iris(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("iri").asString()));
        return values;
    }

    private ProjectId uploadProject(SignedIn owner) throws Exception {
        MvcResult created = upload(owner, corpus("formats/warehouse-sample.ttl"));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return ProjectId.of(read(created).path("projectId").asString());
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

    private MvcResult upload(SignedIn user, Path file) throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
        return mockMvc.perform(multipart("/api/projects/upload")
                        .file(part)
                        .param("name", "Warehouse")
                        .param("description", "sample")
                        .session(user.session())
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

    private static Cookie csrfAfter(MvcResult result, Cookie previous) {
        Cookie updated = result.getResponse().getCookie(CSRF_COOKIE);
        return updated == null ? previous : updated;
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}
}
