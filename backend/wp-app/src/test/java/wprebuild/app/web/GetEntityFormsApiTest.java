package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
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
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Entity form read API: selector matching + TEXT/NUMBER PROPERTY projection (P6-69 / BS-17).
 *
 * <p>GateGuard facts: called by Surefire as {@code GetEntityFormsApiTest}; Glob found none; uses
 * {@code project_form}/{@code project_form_selector}; user asked {@code 完成下一个任务卡}.
 */
@DatabaseTest
@AutoConfigureMockMvc
class GetEntityFormsApiTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final String NS = "http://example.org/forms#";

    private static final String THING = OWLRDFVocabulary.OWL_THING.getIRI().toString();

    private static final String COMMENT = "http://www.w3.org/2000/01/rdf-schema#comment";

    private final MockMvc mockMvc;
    private final JsonMapper json;
    private final UserService users;
    private final JdbcRoleAssignmentRepository roles;
    private final ProjectRuntimeRegistry registry;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);

    GetEntityFormsApiTest(
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
    void matches_by_entity_type_and_subclass_projects_text_and_rejects_without_view() throws Exception {
        SignedIn owner = owner();
        SignedIn stranger = account("stranger");
        ProjectId projectId = projectId(create(owner, "EntityForms"));

        String aminoAcid = createClass(owner, projectId, "AminoAcid", List.of(THING));
        String specific = createClass(owner, projectId, "SpecificAminoAcid", List.of(aminoAcid));
        String glycine = createClass(owner, projectId, "Glycine", List.of(specific));
        plantComment(projectId, glycine, "smallest amino acid");

        UUID typeForm = UUID.randomUUID();
        UUID subclassForm = UUID.randomUUID();
        UUID unsupportedForm = UUID.randomUUID();
        UUID noSelectorForm = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();

        putForm(owner, projectId, typeForm, textDescriptor(typeForm, fieldId), entityTypeCriteria("CLASS"));
        putForm(owner, projectId, subclassForm, textDescriptor(subclassForm, fieldId), subclassCriteria(aminoAcid));
        putForm(
                owner,
                projectId,
                unsupportedForm,
                textDescriptor(unsupportedForm, fieldId),
                unsupportedCriteria(aminoAcid));
        putForm(owner, projectId, noSelectorForm, textDescriptor(noSelectorForm, fieldId), null);

        MvcResult glycineForms = getEntityForms(owner, projectId, glycine);
        assertThat(glycineForms.getResponse().getStatus())
                .as(glycineForms.getResponse().getContentAsString())
                .isEqualTo(200);
        JsonNode body = read(glycineForms);
        assertThat(body.path("entity").asString()).isEqualTo(glycine);
        assertThat(body.path("head").asLong()).isGreaterThan(0);
        assertThat(formIds(body)).containsExactlyInAnyOrder(typeForm.toString(), subclassForm.toString());
        assertThat(formIds(body)).doesNotContain(unsupportedForm.toString(), noSelectorForm.toString());

        JsonNode projected = form(body, typeForm.toString());
        assertThat(projected.path("descriptor").path("label").path("en").asString())
                .isEqualTo("Water");
        JsonNode values = projected.path("values");
        assertThat(values).hasSize(1);
        assertThat(values.get(0).path("fieldId").asString()).isEqualTo(fieldId.toString());
        assertThat(values.get(0).path("value").path("type").asString()).isEqualTo("literal");
        assertThat(values.get(0).path("value").path("lexical").asString()).isEqualTo("smallest amino acid");

        MvcResult filtered = getEntityForms(owner, projectId, glycine, subclassForm);
        assertThat(filtered.getResponse().getStatus()).isEqualTo(200);
        assertThat(formIds(read(filtered))).containsExactly(subclassForm.toString());

        MvcResult parentOnly = getEntityForms(owner, projectId, aminoAcid);
        assertThat(parentOnly.getResponse().getStatus()).isEqualTo(200);
        assertThat(formIds(read(parentOnly))).containsExactly(typeForm.toString());

        MvcResult forbidden = getEntityForms(stranger, projectId, glycine);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void entity_type_miss_and_missing_entity_return_empty_or_404() throws Exception {
        SignedIn owner = owner();
        SignedIn viewer = account("viewer");
        ProjectId projectId = projectId(create(owner, "EntityFormsMiss"));
        roles.assign(projectId, new UserId(viewer.userId()), ProjectRole.CAN_VIEW);

        String aminoAcid = createClass(owner, projectId, "AminoAcid", List.of(THING));
        UUID formId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        putForm(owner, projectId, formId, textDescriptor(formId, fieldId), entityTypeCriteria("NAMED_INDIVIDUAL"));

        MvcResult miss = getEntityForms(viewer, projectId, aminoAcid);
        assertThat(miss.getResponse().getStatus()).isEqualTo(200);
        assertThat(formIds(read(miss))).isEmpty();

        MvcResult missing = getEntityForms(viewer, projectId, NS + "Missing");
        assertThat(missing.getResponse().getStatus()).isEqualTo(404);
        assertThat(read(missing).path("code").asString()).isEqualTo("NOT_FOUND");
    }

    @Test
    void any_composite_with_unsupported_node_does_not_partially_match() throws Exception {
        SignedIn owner = owner();
        ProjectId projectId = projectId(create(owner, "EntityFormsAny"));
        String aminoAcid = createClass(owner, projectId, "AminoAcid", List.of(THING));
        UUID formId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        putForm(owner, projectId, formId, textDescriptor(formId, fieldId), anyWithUnsupported(aminoAcid));

        MvcResult result = getEntityForms(owner, projectId, aminoAcid);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(formIds(read(result))).isEmpty();
    }

    private Map<String, Object> textDescriptor(UUID formId, UUID fieldId) {
        Map<String, Object> control = new LinkedHashMap<>();
        control.put("type", "TEXT");
        control.put("placeholder", Map.of("en", "Enter brand name"));
        control.put("stringType", "SIMPLE_STRING");
        control.put("lineMode", "SINGLE_LINE");

        Map<String, Object> field = new LinkedHashMap<>();
        field.put("id", fieldId.toString());
        field.put("owlBinding", Map.of("type", "PROPERTY", "property", COMMENT));
        field.put("label", Map.of("en", "Comment"));
        field.put("fieldRun", "START");
        field.put("control", control);
        field.put("repeatability", "NON_REPEATABLE");
        field.put("optionality", "OPTIONAL");
        field.put("readOnly", false);
        field.put("initialExpansionState", "COLLAPSED");
        field.put("help", Map.of("en", "Help text"));

        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("formId", formId.toString());
        descriptor.put("label", Map.of("en", "Water", "de", "Wasser"));
        descriptor.put("fields", List.of(field));
        return descriptor;
    }

    private JsonNode entityTypeCriteria(String type) throws Exception {
        return json.readTree("""
                {
                  "type": "CompositeCriteria",
                  "matchType": "ALL",
                  "criteria": [{
                    "type": "EntityTypeIsOneOf",
                    "types": ["%s"]
                  }]
                }
                """.formatted(type));
    }

    private JsonNode subclassCriteria(String target) throws Exception {
        return json.readTree("""
                {
                  "type": "CompositeCriteria",
                  "matchType": "ALL",
                  "criteria": [{
                    "type": "SubClassOf",
                    "target": "%s",
                    "filterType": "ALL"
                  }]
                }
                """.formatted(target));
    }

    private JsonNode unsupportedCriteria(String target) throws Exception {
        return json.readTree("""
                {
                  "type": "CompositeCriteria",
                  "matchType": "ALL",
                  "criteria": [{
                    "type": "SubClassOf",
                    "target": "%s",
                    "filterType": "ALL"
                  }, {
                    "type": "InstanceOf",
                    "target": "%s"
                  }]
                }
                """.formatted(target, target));
    }

    private JsonNode anyWithUnsupported(String target) throws Exception {
        return json.readTree("""
                {
                  "type": "CompositeCriteria",
                  "matchType": "ANY",
                  "criteria": [{
                    "type": "EntityTypeIsOneOf",
                    "types": ["CLASS"]
                  }, {
                    "type": "InstanceOf",
                    "target": "%s"
                  }]
                }
                """.formatted(target));
    }

    private void putForm(
            SignedIn user, ProjectId projectId, UUID formId, Map<String, Object> descriptor, JsonNode criteria)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("descriptor", descriptor);
        if (criteria != null) {
            body.put("criteria", criteria);
        }
        MvcResult result = putJson(user, "/api/projects/" + projectId + "/forms/" + formId, body);
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private String createClass(SignedIn owner, ProjectId projectId, String name, List<String> parents)
            throws Exception {
        MvcResult created = postJson(
                owner,
                "/api/projects/" + projectId + "/entities",
                Map.of("entityType", "CLASS", "names", List.of(name), "parents", parents));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return read(created).path("entities").get(0).path("iri").asString();
    }

    private void plantComment(ProjectId projectId, String entityIri, String comment) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        OWLAxiom axiom = data.getOWLAnnotationAssertionAxiom(
                data.getRDFSComment(), IRI.create(entityIri), data.getOWLLiteral(comment));
        var handle = registry.get(projectId);
        OntologyKey key = handle.runtime().ontologies().keys().iterator().next();
        List<OntologyChange> changes = new ArrayList<>();
        changes.add(new AddAxiom(key, axiom));
        handle.pipeline().apply(new UserId("owner"), context -> new GeneratedChanges<>(changes, "comment"));
    }

    private MvcResult getEntityForms(SignedIn user, ProjectId projectId, String entity) throws Exception {
        return send(
                user,
                get("/api/projects/{id}/entity-forms", projectId.toString()).param("entity", entity));
    }

    private MvcResult getEntityForms(SignedIn user, ProjectId projectId, String entity, UUID formId) throws Exception {
        return send(
                user,
                get("/api/projects/{id}/entity-forms", projectId.toString())
                        .param("entity", entity)
                        .param("formId", formId.toString()));
    }

    private static List<String> formIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (JsonNode form : body.path("forms")) {
            ids.add(form.path("formId").asString());
        }
        return ids;
    }

    private static JsonNode form(JsonNode body, String formId) {
        for (JsonNode form : body.path("forms")) {
            if (formId.equals(form.path("formId").asString())) {
                return form;
            }
        }
        throw new AssertionError("missing form " + formId);
    }

    private SignedIn owner() throws Exception {
        return account("owner", "PROJECT_CREATOR");
    }

    private SignedIn account(String role, String... appRoles) throws Exception {
        String userId = "p669-" + role + "-" + suffix;
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
        return postJson(user, "/api/projects", new CreateProject(name, "Entity forms", null));
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
}
