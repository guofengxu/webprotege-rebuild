package wprebuild.app.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
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
import wprebuild.app.ontology.upload.OntologySourceParser;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectSnapshotter;
import wprebuild.kernel.testsupport.TestOntologies;

/** Download returns a project revision in one of the BS-12 formats (P3-12). */
@DatabaseTest
@AutoConfigureMockMvc
@Transactional
class DownloadTest {

    private static final String PASSWORD = "correct-password";

    private static final String CSRF_COOKIE = "XSRF-TOKEN";

    private static final String CSRF_HEADER = "X-XSRF-TOKEN";

    private static final List<String> FORMATS = List.of("owl", "ttl", "owx", "omn", "ofn");

    private final MockMvc mockMvc;

    private final JsonMapper json;

    private final UserService users;

    private final JdbcRoleAssignmentRepository roles;

    private final ProjectRuntimeRegistry registry;

    private final OntologySourceParser parser;

    private final SnapshotStore snapshots;

    private final SnapshotCodec snapshotCodec;

    DownloadTest(
            MockMvc mockMvc,
            JsonMapper json,
            UserService users,
            JdbcRoleAssignmentRepository roles,
            ProjectRuntimeRegistry registry,
            OntologySourceParser parser,
            SnapshotStore snapshots,
            SnapshotCodec snapshotCodec) {
        this.mockMvc = mockMvc;
        this.json = json;
        this.users = users;
        this.roles = roles;
        this.registry = registry;
        this.parser = parser;
        this.snapshots = snapshots;
        this.snapshotCodec = snapshotCodec;
    }

    @Test
    void uploaded_download_parses_to_the_same_axioms(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner, "Ware/house", "formats/warehouse-sample.ttl");
        Set<OWLAxiom> live = axioms(registry.get(projectId).runtime().ontologies());

        for (String format : FORMATS) {
            MvcResult downloaded = download(owner, projectId, format, 1L);
            assertThat(downloaded.getResponse().getStatus())
                    .as(downloaded.getResponse().getContentAsString())
                    .isEqualTo(200);
            assertThat(downloaded.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                    .contains("filename=\"Ware_house-r1." + format + "\"");
            Set<OWLAxiom> parsed = axioms(parseDocument(temp, "roundtrip." + format, downloaded));
            if ("omn".equals(format)) {
                // Manchester parsing adds declarations for built-in entities used in annotations.
                assertThat(parsed).containsAll(live);
                Set<OWLAxiom> extra = new HashSet<>(parsed);
                extra.removeAll(live);
                assertThat(extra).allSatisfy(axiom -> {
                    assertThat(axiom).isInstanceOf(OWLDeclarationAxiom.class);
                    assertThat(((OWLDeclarationAxiom) axiom)
                                    .getEntity()
                                    .getIRI()
                                    .toString())
                            .doesNotStartWith("http://example.org/");
                });
            } else {
                assertThat(parsed).as(format).isEqualTo(live);
            }
        }
    }

    @Test
    void revision_one_differs_from_head_and_both_match(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner, "Warehouse", "formats/warehouse-sample.ttl");
        Set<OWLAxiom> revisionOne = axioms(registry.get(projectId).runtime().ontologies());
        declare(projectId, "http://example.org/wp-test/warehouse#ExtraDownload");
        Set<OWLAxiom> head = axioms(registry.get(projectId).runtime().ontologies());
        assertThat(head).containsAll(revisionOne).hasSize(revisionOne.size() + 1);

        MvcResult older = download(owner, projectId, "ttl", 1L);
        MvcResult latest = download(owner, projectId, "ttl", null);
        assertThat(older.getResponse().getStatus()).isEqualTo(200);
        assertThat(latest.getResponse().getStatus()).isEqualTo(200);
        assertThat(older.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"Warehouse-r1.ttl\"");
        assertThat(latest.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"Warehouse-r2.ttl\"");
        assertThat(axioms(parseDocument(temp, "r1.ttl", older))).isEqualTo(revisionOne);
        assertThat(axioms(parseDocument(temp, "head.ttl", latest))).isEqualTo(head);
        assertThat(axioms(registry.get(projectId).runtime().ontologies())).isEqualTo(head);
    }

    @Test
    void older_revision_replays_the_snapshot_then_later_changes(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner, "Warehouse", "formats/warehouse-sample.ttl");
        ProjectSnapshotter.snapshot(registry.get(projectId).runtime(), snapshots, snapshotCodec);
        declare(projectId, "http://example.org/wp-test/warehouse#FirstExtra");
        Set<OWLAxiom> atRevisionTwo = axioms(registry.get(projectId).runtime().ontologies());
        declare(projectId, "http://example.org/wp-test/warehouse#SecondExtra");

        MvcResult downloaded = download(owner, projectId, "ofn", 2L);
        assertThat(downloaded.getResponse().getStatus()).isEqualTo(200);
        assertThat(downloaded.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"Warehouse-r2.ofn\"");
        assertThat(axioms(parseDocument(temp, "r2.ofn", downloaded))).isEqualTo(atRevisionTwo);
        assertThat(axioms(registry.get(projectId).runtime().ontologies())).hasSize(atRevisionTwo.size() + 1);
    }

    @Test
    void several_ontologies_download_as_a_zip(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        Path zip = zip(
                temp.resolve("imports.zip"),
                corpus("imports/a.owl"),
                corpus("imports/b.owl"),
                corpus("imports/main.owl"));
        MvcResult created = upload(owner, "Imports", "closure", zip);
        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        ProjectId projectId = projectId(created);
        ProjectOntologies ontologies = registry.get(projectId).runtime().ontologies();

        MvcResult downloaded = download(owner, projectId, "ttl", null);
        assertThat(downloaded.getResponse().getStatus()).isEqualTo(200);
        assertThat(downloaded.getResponse().getContentType()).startsWith("application/zip");
        assertThat(downloaded.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("filename=\"Imports-r1.zip\"");

        Path archive = temp.resolve("download.zip");
        Files.write(archive, downloaded.getResponse().getContentAsByteArray());
        List<String> names = new ArrayList<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                names.add(entry.getName());
                Path extracted = temp.resolve(entry.getName());
                Files.write(extracted, in.readAllBytes());
                int dash = entry.getName().indexOf('-');
                OntologyKey key =
                        OntologyKey.of(Integer.parseInt(entry.getName().substring(1, dash)));
                String segment = lastSegment(
                        ontologies.info(key).orElseThrow().ontologyIri().orElseThrow());
                assertThat(entry.getName()).isEqualTo(key.value() + "-" + segment + ".ttl");
                OWLOntology parsed = parser.parse(extracted).getFirst();
                assertThat(axioms(List.of(parsed))).isEqualTo(axioms(ontologies, key));
                assertThat(parsed.getDirectImportsDocuments())
                        .containsExactlyInAnyOrderElementsOf(ontologies.imports(key));
            }
        }
        assertThat(names).hasSize(ontologies.keys().size());
    }

    @Test
    void viewer_can_download_and_unassigned_user_is_forbidden() throws Exception {
        account("owner", "PROJECT_CREATOR");
        account("viewer");
        account("stranger");
        SignedIn owner = login("owner");
        SignedIn viewer = login("viewer");
        SignedIn stranger = login("stranger");
        ProjectId projectId = uploadProject(owner, "Warehouse", "formats/warehouse-sample.ttl");
        roles.assign(projectId, new UserId("viewer"), ProjectRole.CAN_VIEW);

        MvcResult allowed = download(viewer, projectId, "ofn", null);
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
        assertThat(allowed.getResponse().getContentAsByteArray()).isNotEmpty();

        MvcResult forbidden = download(stranger, projectId, "ofn", null);
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        assertThat(read(forbidden).path("code").asString()).isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void non_latin_project_name_stays_in_an_iso_8859_1_header(@TempDir Path temp) throws Exception {
        account("owner", "PROJECT_CREATOR");
        SignedIn owner = login("owner");
        ProjectId projectId = uploadProject(owner, "仓库", "formats/warehouse-sample.ttl");

        MvcResult downloaded = download(owner, projectId, "ttl", null);
        assertThat(downloaded.getResponse().getStatus()).isEqualTo(200);
        String header = downloaded.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(header).contains("filename*=UTF-8''%E4%BB%93%E5%BA%93-r1.ttl");
        assertThat(header.chars().allMatch(ch -> ch <= 0xFF)).isTrue();
        assertThat(axioms(parseDocument(temp, "warehouse.ttl", downloaded)))
                .isEqualTo(axioms(registry.get(projectId).runtime().ontologies()));
    }

    private void declare(ProjectId projectId, String classIri) {
        ProjectOntologies ontologies = registry.get(projectId).runtime().ontologies();
        OntologyKey key = ontologies.keys().iterator().next();
        OWLDataFactory factory = ontologies.dataFactory();
        registry.get(projectId)
                .pipeline()
                .apply(
                        new UserId("owner"),
                        context -> new GeneratedChanges<>(
                                List.of(new AddAxiom(
                                        key,
                                        factory.getOWLDeclarationAxiom(factory.getOWLClass(IRI.create(classIri))))),
                                "extra"));
    }

    private ProjectId uploadProject(SignedIn owner, String name, String relative) throws Exception {
        MvcResult created = upload(owner, name, "sample", corpus(relative));
        assertThat(created.getResponse().getStatus())
                .as(created.getResponse().getContentAsString())
                .isEqualTo(201);
        return projectId(created);
    }

    private MvcResult download(SignedIn user, ProjectId projectId, String format, Long revision) throws Exception {
        var request = get("/api/projects/{id}/download", projectId.toString())
                .param("format", format)
                .session(user.session());
        if (revision != null) {
            request.param("revision", Long.toString(revision));
        }
        return mockMvc.perform(request).andReturn();
    }

    private List<OWLOntology> parseDocument(Path temp, String name, MvcResult downloaded) throws Exception {
        Path file = temp.resolve(name);
        Files.write(file, downloaded.getResponse().getContentAsByteArray());
        return parser.parse(file);
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
        MockMultipartFile part = new MockMultipartFile(
                "file", file.getFileName().toString(), "application/octet-stream", Files.readAllBytes(file));
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

    private static Set<OWLAxiom> axioms(ProjectOntologies ontologies) {
        Set<OWLAxiom> all = new HashSet<>();
        List<OntologyKey> keys = new ArrayList<>(ontologies.keys());
        keys.sort(Comparator.comparing(OntologyKey::value));
        for (OntologyKey key : keys) {
            all.addAll(axioms(ontologies, key));
        }
        return all;
    }

    private static Set<OWLAxiom> axioms(ProjectOntologies ontologies, OntologyKey key) {
        return ontologies.axioms(key).collect(Collectors.toSet());
    }

    private static Set<OWLAxiom> axioms(List<OWLOntology> ontologies) {
        Set<OWLAxiom> all = new HashSet<>();
        for (OWLOntology ontology : ontologies) {
            all.addAll(ontology.getAxioms());
        }
        return all;
    }

    private static String lastSegment(IRI iri) {
        String text = iri.toString();
        int cut = Math.max(text.lastIndexOf('/'), text.lastIndexOf('#'));
        return cut < 0 || cut == text.length() - 1 ? "ontology" : text.substring(cut + 1);
    }

    private static Path corpus(String relative) {
        return TestOntologies.dir().resolve(relative).toAbsolutePath().normalize();
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
