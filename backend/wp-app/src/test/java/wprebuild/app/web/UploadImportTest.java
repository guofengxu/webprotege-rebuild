package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.UserService;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.upload.OntologySourceParser;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.testsupport.TestOntologies;

/** Upload creates a project whose first revision is the parsed document (P3-09, BS-12). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "wp.upload.max-size=64KB")
class UploadImportTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final List<String> FORMATS = List.of(
            "formats/warehouse-sample.ttl",
            "formats/warehouse-sample.owl",
            "formats/warehouse-sample.owx",
            "formats/warehouse-sample.omn",
            "formats/warehouse-sample.ofn");

    private static final String BROKEN_TURTLE = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            <http://example.org/broken> rdf:type owl:Ontology
            <http://example.org/broken#A> rdf:type owl:Class .
            """;

    private static final String OFFLINE_TURTLE = """
            @prefix owl: <http://www.w3.org/2002/07/owl#> .
            @prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .

            <http://example.org/wp-test/offline> rdf:type owl:Ontology ;
                owl:imports <http://invalid.invalid/> .

            <http://example.org/wp-test/offline#Local> rdf:type owl:Class .
            """;

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcClient jdbc;

    private final RevisionLog revisions;

    private final ProjectRuntimeRegistry registry;

    private final OntologySourceParser parser;

    UploadImportTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcClient jdbc,
            RevisionLog revisions,
            ProjectRuntimeRegistry registry,
            OntologySourceParser parser) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.jdbc = jdbc;
        this.revisions = revisions;
        this.registry = registry;
        this.parser = parser;
    }

    @Test
    void five_formats_import_the_same_axiom_count() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        List<Long> counts = new ArrayList<>();

        for (String relative : FORMATS) {
            MvcResult created = upload(owner, "Warehouse", relative, corpus(relative));
            assertThat(created.getResponse().getStatus()).isEqualTo(201);
            ProjectId projectId = projectId(created);
            assertThat(created.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/api/projects/" + projectId);

            ProjectOntologies ontologies = registry.get(projectId).runtime().ontologies();
            long imported = ontologies.axiomCount();
            assertThat(imported).isEqualTo(sourceAxioms(relative));
            assertThat(revisions.head(projectId)).isEqualTo(RevisionNumber.of(1));
            Revision revision = revisions.get(projectId, RevisionNumber.of(1)).orElseThrow();
            assertThat(revision.description()).isEqualTo("Imported 1 ontologies");
            assertThat(ontologies.info(OntologyKey.of(1)))
                    .get()
                    .extracting(info -> info.ontologyIri().orElseThrow().toString())
                    .isEqualTo("http://example.org/wp-test/warehouse");
            counts.add(imported);
        }

        // Manchester writes declarations for rdfs:label, rdfs:comment, rdf:langString, and xsd:string.
        // The other four documents omit those built-in declarations, so their axiom counts match each other.
        assertThat(counts).containsExactly(119L, 119L, 119L, 123L, 119L);
    }

    @Test
    void zip_of_imports_creates_three_ontologies(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        Path zip = zip(
                temp.resolve("imports.zip"),
                corpus("imports/a.owl"),
                corpus("imports/b.owl"),
                corpus("imports/main.owl"));

        MvcResult created = upload(owner, "Imports", "closure", zip);
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        ProjectId projectId = projectId(created);
        ProjectOntologies ontologies = registry.get(projectId).runtime().ontologies();
        assertThat(ontologies.keys()).hasSize(3);
        assertThat(ontologies.axiomCount())
                .isEqualTo(sourceAxioms("imports/a.owl")
                        + sourceAxioms("imports/b.owl")
                        + sourceAxioms("imports/main.owl"));
        assertThat(ontologies.containsEntity(ontologies
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp-test/imports/a#ComponentA"))))
                .isTrue();
        assertThat(ontologies.containsEntity(ontologies
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp-test/imports/b#ComponentB"))))
                .isTrue();
        assertThat(ontologies.containsEntity(ontologies
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp-test/imports/main#MainConcept"))))
                .isTrue();
        assertThat(ontologies.keys())
                .anyMatch(key -> ontologies.imports(key).contains(IRI.create("http://example.org/wp-test/imports/a"))
                        && ontologies.imports(key).contains(IRI.create("http://example.org/wp-test/imports/b")));
        assertThat(revisions.get(projectId, RevisionNumber.of(1)).orElseThrow().description())
                .isEqualTo("Imported 3 ontologies");
    }

    @Test
    void missing_remote_import_is_recorded_without_network(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        parser.parse(corpus("formats/warehouse-sample.ttl"));
        Path file = Files.writeString(temp.resolve("offline.ttl"), OFFLINE_TURTLE);

        long started = System.nanoTime();
        MvcResult created = upload(owner, "Offline", "missing import", file);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(elapsed).isLessThan(Duration.ofSeconds(2));
        ProjectId projectId = projectId(created);
        ProjectOntologies ontologies = registry.get(projectId).runtime().ontologies();
        assertThat(ontologies.keys()).hasSize(1);
        Revision revision = revisions.get(projectId, RevisionNumber.of(1)).orElseThrow();
        assertThat(revision.changes())
                .anyMatch(change ->
                        change instanceof AddImport add && add.iri().toString().equals("http://invalid.invalid/"));
    }

    @Test
    void corrupt_document_returns_the_parser_line(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        Path file = Files.writeString(temp.resolve("broken.ttl"), BROKEN_TURTLE);

        MvcResult failed = upload(owner, "Broken", "bad syntax", file);
        assertThat(failed.getResponse().getStatus()).isEqualTo(400);
        JsonNode body = read(failed);
        assertThat(body.path("code").asString()).isEqualTo(ProblemDetailsAdvice.ONTOLOGY_PARSE_FAILED);
        assertThat(body.path("detail").asString()).isNotBlank();
        assertThat(body.path("line").asInt()).isPositive();
        assertThat(projectCount()).isZero();
    }

    @Test
    void oversized_upload_returns_413() throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");

        MvcResult failed = uploadBytes(owner, "Huge", "too big", "huge.owl", new byte[70_000]);
        assertThat(failed.getResponse().getStatus()).isEqualTo(413);
        assertThat(read(failed).path("code").asString()).isEqualTo(ApiProblems.UPLOAD_TOO_LARGE);
        assertThat(projectCount()).isZero();
    }

    @Test
    void upload_requires_permission_and_a_name() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("plain");
        SignedIn owner = login("owner");
        SignedIn plain = login("plain");
        Path file = corpus("formats/warehouse-sample.ttl");

        MvcResult forbidden = upload(plain, "Warehouse", "no", file);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");

        MvcResult blank = upload(owner, "   ", "no", file);
        assertThat(blank.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(blank).path("code").asString()).isEqualTo(ProblemDetailsAdvice.VALIDATION_FAILED);
    }

    @Test
    void zip_with_several_roots_or_a_path_escape_is_rejected(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        Path roots = zip(temp.resolve("roots.zip"), corpus("imports/a.owl"), corpus("imports/b.owl"));
        MvcResult many = upload(owner, "Roots", "two", roots);
        assertThat(many.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(many).path("detail").asString()).contains("root");

        Path slipped = temp.resolve("slipped.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(slipped))) {
            out.putNextEntry(new ZipEntry("../escaped.owl"));
            Files.copy(corpus("imports/a.owl"), out);
            out.closeEntry();
        }
        MvcResult escaped = upload(owner, "Escape", "no", slipped);
        assertThat(escaped.getResponse().getStatus()).isEqualTo(400);
        assertThat(read(escaped).path("detail").asString()).contains("escapes");
        assertThat(Files.exists(temp.resolve("escaped.owl"))).isFalse();
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

    private MvcResult upload(SignedIn user, String name, String description, Path file) throws Exception {
        return uploadBytes(user, name, description, file.getFileName().toString(), Files.readAllBytes(file));
    }

    private MvcResult uploadBytes(SignedIn user, String name, String description, String filename, byte[] body)
            throws Exception {
        MockMultipartFile part = new MockMultipartFile("file", filename, "application/octet-stream", body);
        return mockMvc.perform(multipart("/api/projects/upload")
                        .file(part)
                        .param("name", name)
                        .param("description", description)
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

    private ProjectId projectId(MvcResult result) throws Exception {
        return ProjectId.of(read(result).path("projectId").asString());
    }

    private JsonNode read(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private int projectCount() {
        return jdbc.sql("select count(*) from projects")
                .query((rs, row) -> rs.getInt(1))
                .single();
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
    }

    private static int sourceAxioms(String relative) {
        return TestOntologies.load(relative).getAxioms().size();
    }

    private static Path zip(Path destination, Path... files) throws Exception {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(destination))) {
            for (Path file : files) {
                out.putNextEntry(new ZipEntry(file.getFileName().toString()));
                Files.copy(file, out);
                out.closeEntry();
            }
        }
        return destination;
    }

    private record SignedIn(MockHttpSession session, Cookie csrf) {}

    private record Login(String username, String password) {}
}
