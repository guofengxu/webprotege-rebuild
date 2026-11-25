package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import wprebuild.app.ontology.ApplyUploadMergeHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.testsupport.TestOntologies;

/**
 * Upload-and-merge preview / apply / discard API (P6-50 / BS-14).
 *
 * <p>Gate facts: Surefire runs {@code UploadMergeTest}; corpus under {@code testdata/ontologies/};
 * user asked "完成下一个任务卡".
 */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class UploadMergeTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String WH = "http://example.org/wp-test/warehouse#";

    private static final String EXTRA = WH + "ExtraShelf";

    private static final String CONVEYOR = WH + "Conveyor";

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    UploadMergeTest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcRoleAssignmentRepository roles) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
    }

    @Test
    void new_version_preview_counts_apply_matches_upload_and_revert_restores(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);
        Path modified = modifiedWarehouse(temp);

        MvcResult previewed = preview(owner, projectId, modified, "NEW_VERSION", null);
        assertThat(previewed.getResponse().getStatus())
                .as(previewed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode preview = read(previewed);
        assertThat(preview.path("mode").asString()).isEqualTo("NEW_VERSION");
        assertThat(preview.path("baseRevision").asLong()).isEqualTo(1);
        assertThat(preview.path("totalAdded").asLong()).isPositive();
        assertThat(preview.path("totalRemoved").asLong()).isPositive();
        JsonNode patch = preview.path("ontologies").get(0);
        assertThat(patch.path("key").asString()).isEqualTo("o1");
        assertThat(patch.path("addedAxiomCount").asLong()).isPositive();
        assertThat(patch.path("removedAxiomCount").asLong()).isPositive();
        assertThat(texts(patch.path("addedAxioms"))).isNotEmpty();
        assertThat(texts(patch.path("removedAxioms"))).isNotEmpty();
        assertThat(texts(patch.path("addedAxioms")).stream().anyMatch(text -> text.contains("ExtraShelf")))
                .isTrue();

        String uploadId = preview.path("uploadId").asString();
        long baseRevision = preview.path("baseRevision").asLong();
        MvcResult applied = apply(owner, projectId, uploadId, baseRevision, "Merged warehouse v2");
        assertThat(applied.getResponse().getStatus())
                .as(applied.getResponse().getContentAsString())
                .isEqualTo(200);
        long revision = read(applied).path("revision").asLong();
        assertThat(revision).isEqualTo(2);

        assertThat(searchIris(owner, projectId, "ExtraShelf")).contains(EXTRA);
        assertThat(searchIris(owner, projectId, "Conveyor")).doesNotContain(CONVEYOR);

        JsonNode history = revisions(owner, projectId);
        assertThat(history.path("revisions")).anySatisfy(node -> {
            assertThat(node.path("revNo").asLong()).isEqualTo(revision);
            assertThat(node.path("description").asString()).isEqualTo("Merged warehouse v2");
        });

        MvcResult reverted = postPath(owner, "/api/projects/" + projectId + "/revisions/" + revision + "/revert");
        assertThat(reverted.getResponse().getStatus())
                .as(reverted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(searchIris(owner, projectId, "Conveyor")).contains(CONVEYOR);
        assertThat(searchIris(owner, projectId, "ExtraShelf")).doesNotContain(EXTRA);
    }

    @Test
    void additions_only_adds_and_missing_target_is_bad_request(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);
        Path addition = Files.writeString(temp.resolve("addition.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix wh: <http://example.org/wp-test/warehouse#> .
                <http://example.org/wp-test/warehouse> a owl:Ontology .
                wh:ExtraShelf a owl:Class .
                """);

        MvcResult missingTarget = preview(owner, projectId, addition, "ADDITIONS", null);
        assertThat(missingTarget.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(missingTarget).path("code").asString()).isEqualTo(ProblemDetailsAdvice.VALIDATION_FAILED);

        MvcResult unknownTarget = preview(owner, projectId, addition, "ADDITIONS", "o99");
        assertThat(unknownTarget.getResponse().getStatus()).isEqualTo(400);

        MvcResult previewed = preview(owner, projectId, addition, "ADDITIONS", "o1");
        assertThat(previewed.getResponse().getStatus())
                .as(previewed.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode preview = read(previewed);
        assertThat(preview.path("mode").asString()).isEqualTo("ADDITIONS");
        assertThat(preview.path("totalRemoved").asLong()).isZero();
        assertThat(preview.path("totalAdded").asLong()).isPositive();
        assertThat(preview.path("ontologies").get(0).path("removedAxiomCount").asLong())
                .isZero();

        MvcResult applied = apply(
                owner,
                projectId,
                preview.path("uploadId").asString(),
                preview.path("baseRevision").asLong(),
                null);
        assertThat(applied.getResponse().getStatus())
                .as(applied.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(searchIris(owner, projectId, "ExtraShelf")).contains(EXTRA);
        assertThat(searchIris(owner, projectId, "Conveyor")).contains(CONVEYOR);
    }

    @Test
    void stale_head_keeps_other_users_edit_and_allows_repreview(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("editor");
        SignedIn owner = login("owner");
        SignedIn editor = login("editor");
        ProjectId projectId = uploadWarehouse(owner);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        Path modified = modifiedWarehouse(temp);

        MvcResult previewed = preview(owner, projectId, modified, "NEW_VERSION", null);
        assertThat(previewed.getResponse().getStatus()).isEqualTo(200);
        JsonNode preview = read(previewed);
        String uploadId = preview.path("uploadId").asString();
        long baseRevision = preview.path("baseRevision").asLong();

        MvcResult created = postJson(
                editor,
                "/api/projects/" + projectId + "/entities",
                Map.of("entityType", "CLASS", "names", List.of("EditorClass"), "parents", List.of()));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        long editorRevision = read(created).path("revision").asLong();
        assertThat(editorRevision).isGreaterThan(baseRevision);

        MvcResult stale = apply(owner, projectId, uploadId, baseRevision, "stale");
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(stale).path("code").asString())
                .isEqualTo(ApplyUploadMergeHandler.PROJECT_CHANGED_SINCE_PREVIEW);

        JsonNode history = revisions(owner, projectId);
        assertThat(history.path("revisions"))
                .anySatisfy(node -> assertThat(node.path("revNo").asLong()).isEqualTo(editorRevision));
        assertThat(history.path("revisions"))
                .noneSatisfy(
                        node -> assertThat(node.path("description").asString()).isEqualTo("stale"));

        MvcResult again = preview(owner, projectId, modified, "NEW_VERSION", null);
        assertThat(again.getResponse().getStatus()).isEqualTo(200);
        JsonNode fresh = read(again);
        MvcResult applied = apply(
                owner,
                projectId,
                fresh.path("uploadId").asString(),
                fresh.path("baseRevision").asLong(),
                "after repreview");
        assertThat(applied.getResponse().getStatus())
                .as(applied.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(read(applied).path("revision").asLong()).isPositive();
    }

    @Test
    void foreign_upload_discard_and_permissions_and_trash(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("other", "PROJECT_CREATOR");
        account("editor");
        SignedIn owner = login("owner");
        SignedIn other = login("other");
        SignedIn editor = login("editor");
        ProjectId projectId = uploadWarehouse(owner);
        ProjectId otherProject = uploadWarehouse(other);
        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_EDIT);
        // Other manager can call apply but does not own the staged upload → 404, not 403.
        roles.assign(projectId, new UserId("other"), ProjectRole.CAN_MANAGE);
        Path modified = modifiedWarehouse(temp);

        MvcResult previewed = preview(owner, projectId, modified, "NEW_VERSION", null);
        assertThat(previewed.getResponse().getStatus()).isEqualTo(200);
        String uploadId = read(previewed).path("uploadId").asString();
        long baseRevision = read(previewed).path("baseRevision").asLong();

        MvcResult otherUser = apply(other, projectId, uploadId, baseRevision, null);
        assertThat(otherUser.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(otherUser).path("code").asString()).isEqualTo(NotFoundException.UPLOAD_NOT_FOUND);

        // Same uploadId against another project the caller manages → still UPLOAD_NOT_FOUND.
        MvcResult otherProj = apply(other, otherProject, uploadId, baseRevision, null);
        assertThat(otherProj.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(otherProj).path("code").asString()).isEqualTo(NotFoundException.UPLOAD_NOT_FOUND);

        MvcResult editorDenied = preview(editor, projectId, modified, "NEW_VERSION", null);
        assertThat(editorDenied.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(editorDenied).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        roles.assign(projectId, new UserId("editor"), ProjectRole.CAN_MANAGE);
        MvcResult managerOk = preview(editor, projectId, modified, "NEW_VERSION", null);
        assertThat(managerOk.getResponse().getStatus())
                .as(managerOk.getResponse().getContentAsString())
                .isEqualTo(200);

        MvcResult discarded = discard(owner, projectId, uploadId);
        assertThat(discarded.getResponse().getStatus()).isEqualTo(204);
        MvcResult afterDiscard = apply(owner, projectId, uploadId, baseRevision, null);
        assertThat(afterDiscard.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(afterDiscard).path("code").asString()).isEqualTo(NotFoundException.UPLOAD_NOT_FOUND);

        assertThat(postPath(owner, "/api/projects/" + projectId + "/trash")
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        MvcResult trashed = preview(owner, projectId, modified, "NEW_VERSION", null);
        assertThat(trashed.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(trashed).path("code").asString()).isEqualTo("PROJECT_TRASHED");
    }

    @Test
    void zip_path_traversal_is_rejected_without_writing_outside(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadWarehouse(owner);
        Path slipped = temp.resolve("slipped.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(slipped))) {
            out.putNextEntry(new ZipEntry("../escaped.owl"));
            Files.copy(corpus("imports/a.owl"), out);
            out.closeEntry();
        }

        MvcResult escaped = preview(owner, projectId, slipped, "NEW_VERSION", null);
        assertThat(escaped.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(escaped).path("detail").asString()).contains("escapes");
        assertThat(Files.exists(temp.resolve("escaped.owl"))).isFalse();
    }

    private Path modifiedWarehouse(Path temp) throws Exception {
        String original = Files.readString(corpus("warehouse-sample.ttl"));
        String modified = original.replace(
                        "wh:Conveyor a owl:Class ; rdfs:subClassOf wh:Equipment ; rdfs:label \"输送机\"@zh , \"Conveyor\"@en .",
                        "")
                .concat("\nwh:ExtraShelf a owl:Class ; rdfs:label \"Extra shelf\"@en .\n");
        return Files.writeString(temp.resolve("warehouse-v2.ttl"), modified);
    }

    private ProjectId uploadWarehouse(SignedIn owner) throws Exception {
        Path file = corpus("warehouse-sample.ttl");
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

    private MvcResult preview(SignedIn user, ProjectId projectId, Path file, String mode, String targetOntology)
            throws Exception {
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
        var request = multipart("/api/projects/{id}/merge-uploads", projectId.toString())
                .file(part)
                .param("mode", mode)
                .session(user.session())
                .cookie(user.csrf())
                .header(CSRF_HEADER, user.csrf().getValue());
        if (targetOntology != null) {
            request = request.param("targetOntology", targetOntology);
        }
        return mockMvc.perform(request).andReturn();
    }

    private MvcResult apply(SignedIn user, ProjectId projectId, String uploadId, long baseRevision, String description)
            throws Exception {
        Map<String, Object> body = description == null
                ? Map.of("baseRevision", baseRevision)
                : Map.of("baseRevision", baseRevision, "description", description);
        return postJson(user, "/api/projects/" + projectId + "/merge-uploads/" + uploadId + "/apply", body);
    }

    private MvcResult discard(SignedIn user, ProjectId projectId, String uploadId) throws Exception {
        return mockMvc.perform(delete("/api/projects/{id}/merge-uploads/{uploadId}", projectId.toString(), uploadId)
                        .session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
    }

    private List<String> searchIris(SignedIn user, ProjectId projectId, String query) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/projects/{id}/search", projectId.toString())
                        .param("q", query)
                        .param("limit", "50")
                        .session(user.session()))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        List<String> iris = new ArrayList<>();
        read(result).forEach(node -> iris.add(node.path("iri").asString()));
        return iris;
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
        return mockMvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(body))
                        .session(user.session())
                        .cookie(user.csrf())
                        .header(CSRF_HEADER, user.csrf().getValue()))
                .andReturn();
    }

    private MvcResult postPath(SignedIn user, String path) throws Exception {
        return mockMvc.perform(post(path)
                        .session(user.session())
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
                        .content(json.writeValueAsString(Map.of("username", username, "password", PASSWORD))))
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

    private static List<String> texts(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}
}
