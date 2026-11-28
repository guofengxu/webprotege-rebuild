package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.JdbcRoleAssignmentRepository;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.UserService;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Form descriptor management API (P6-67 / BS-16).
 *
 * <p>GateGuard facts: called by Surefire as {@code FormDescriptorApiTest}; Glob found none;
 * uses {@code project_form}/{@code project_form_selector}; user asked {@code 完成下一个任务卡}.
 */
@DatabaseTest
@AutoConfigureMockMvc
class FormDescriptorApiTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String CRITERIA = """
            {
              "type": "CompositeCriteria",
              "matchType": "ALL",
              "criteria": [{
                "type": "SubClassOf",
                "target": "http://example.org/SpecificAminoAcid",
                "filterType": "DIRECT"
              }]
            }
            """;

    private final MockMvc mockMvc;
    private final JsonMapper json;
    private final UserService users;
    private final JdbcRoleAssignmentRepository roles;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    FormDescriptorApiTest(MockMvc mockMvc, JsonMapper json, UserService users, JdbcRoleAssignmentRepository roles) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
    }

    @Test
    void manager_can_allocate_upsert_list_get_and_delete() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "Forms"));

        MvcResult fresh = postPath(owner, path(projectId) + "/fresh-id");
        assertThat(fresh.getResponse().getStatus())
                .as(fresh.getResponse().getContentAsString())
                .isEqualTo(200);
        UUID formId = UUID.fromString(read(fresh).path("formId").asString());

        Map<String, Object> descriptor = textDescriptor(formId);
        MvcResult created = putJson(
                owner, path(projectId, formId), Map.of("descriptor", descriptor, "criteria", json.readTree(CRITERIA)));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(created);
        assertThat(body.path("formId").asString()).isEqualTo(formId.toString());
        assertThat(body.path("ordinal").asInt()).isZero();
        assertThat(body.path("descriptor").path("label").path("en").asString()).isEqualTo("Water");
        assertThat(body.path("criteria").path("type").asString()).isEqualTo("CompositeCriteria");

        MvcResult listed = getJson(owner, path(projectId));
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(listed)).hasSize(1);
        assertThat(read(listed).get(0).path("formId").asString()).isEqualTo(formId.toString());

        MvcResult got = getJson(owner, path(projectId, formId));
        assertThat(got.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(got)
                        .path("descriptor")
                        .path("fields")
                        .get(0)
                        .path("control")
                        .path("type")
                        .asString())
                .isEqualTo("TEXT");

        descriptor = new LinkedHashMap<>(descriptor);
        @SuppressWarnings("unchecked")
        Map<String, Object> label = new LinkedHashMap<>((Map<String, Object>) descriptor.get("label"));
        label.put("en", "H2O");
        descriptor.put("label", label);
        MvcResult updated = putJson(owner, path(projectId, formId), Map.of("descriptor", descriptor));
        assertThat(updated.getResponse().getStatus()).isEqualTo(200);
        assertThat(read(updated).path("descriptor").path("label").path("en").asString())
                .isEqualTo("H2O");
        assertThat(read(updated).path("ordinal").asInt()).isZero();
        assertThat(read(updated).path("criteria").isNull()).isTrue();

        assertThat(deletePath(owner, path(projectId, formId)).getResponse().getStatus())
                .isEqualTo(204);
        assertThat(getJson(owner, path(projectId, formId)).getResponse().getStatus())
                .isEqualTo(404);
        assertThat(read(getJson(owner, path(projectId)))).isEmpty();
    }

    @Test
    void editor_without_edit_forms_gets_403_and_bad_shapes_get_400() throws Exception {
        SignedIn owner = owner();
        SignedIn editor = account("editor");
        ProjectId projectId = projectId(create(owner, "Forms"));
        roles.assign(projectId, new UserId(editor.userId()), ProjectRole.CAN_EDIT);

        UUID formId = UUID.randomUUID();
        Map<String, Object> body = Map.of("descriptor", textDescriptor(formId), "criteria", json.readTree(CRITERIA));

        assertThat(putJson(editor, path(projectId, formId), body).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(getJson(editor, path(projectId)).getResponse().getStatus()).isEqualTo(403);
        assertThat(getJson(editor, path(projectId, formId)).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(deletePath(editor, path(projectId, formId)).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(postPath(editor, path(projectId) + "/fresh-id").getResponse().getStatus())
                .isEqualTo(403);

        MvcResult arrayBody = putRaw(owner, path(projectId, formId), "[1,2]");
        assertThat(arrayBody.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(arrayBody).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult missingDescriptor =
                putJson(owner, path(projectId, formId), Map.of("criteria", json.readTree(CRITERIA)));
        assertThat(missingDescriptor.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(missingDescriptor).path("code").asString()).isEqualTo("VALIDATION_FAILED");

        MvcResult badCriteria = putJson(
                owner,
                path(projectId, formId),
                Map.of("descriptor", textDescriptor(formId), "criteria", "not-an-object"));
        assertThat(badCriteria.getResponse().getStatus()).isEqualTo(400);

        UUID other = UUID.randomUUID();
        MvcResult mismatched = putJson(owner, path(projectId, formId), Map.of("descriptor", textDescriptor(other)));
        assertThat(mismatched.getResponse().getStatus()).isEqualTo(400);

        assertThat(mockMvc.perform(get(path(projectId)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(401);
    }

    private Map<String, Object> textDescriptor(UUID formId) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("type", "TEXT");
        control.put("placeholder", Map.of("en", "Enter brand name"));
        control.put("stringType", "SIMPLE_STRING");
        control.put("lineMode", "SINGLE_LINE");

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", UUID.randomUUID().toString());
        field.put("owlBinding", Map.of("type", "PROPERTY", "property", "http://www.w3.org/2000/01/rdf-schema#comment"));
        field.put("label", Map.of("en", "Comment"));
        field.put("fieldRun", "START");
        field.put("control", control);
        field.put("repeatability", "NON_REPEATABLE");
        field.put("optionality", "OPTIONAL");
        field.put("readOnly", true);
        field.put("initialExpansionState", "COLLAPSED");
        field.put("help", Map.of("en", "Help text"));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("formId", formId.toString());
        descriptor.put("label", Map.of("en", "Water", "de", "Wasser"));
        descriptor.put("fields", List.of(field));
        return descriptor;
    }

    private String path(ProjectId projectId) {
        return "/api/projects/" + projectId + "/forms";
    }

    private String path(ProjectId projectId, UUID formId) {
        return path(projectId) + "/" + formId;
    }

    private SignedIn owner() throws Exception {
        return account("owner", "PROJECT_CREATOR");
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p667-" + role + "-" + suffix;
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
        return postJson(user, "/api/projects", new CreateProject(name, "Forms", null));
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

    private MvcResult putRaw(SignedIn user, String path, String body) throws Exception {
        return send(user, put(path).contentType(MediaType.APPLICATION_JSON).content(body));
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

    private record SignedIn(String userId, MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}

    private record CreateProject(String name, String description, String ontologyIri) {}
}
