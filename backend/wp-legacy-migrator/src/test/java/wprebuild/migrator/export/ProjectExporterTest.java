package wprebuild.migrator.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.change.AddAxiomData;
import org.semanticweb.owlapi.change.AddImportData;
import org.semanticweb.owlapi.change.AddOntologyAnnotationData;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.change.RemoveAxiomData;
import org.semanticweb.owlapi.change.RemoveImportData;
import org.semanticweb.owlapi.change.RemoveOntologyAnnotationData;
import org.semanticweb.owlapi.change.SetOntologyIDData;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import picocli.CommandLine;
import wprebuild.migrator.Main;
import wprebuild.migrator.legacy.LegacyLogWriter;
import wprebuild.migrator.legacy.LegacyRevision;

class ProjectExporterTest {

    private static final String PROJECT_ID = "2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11";
    private static final String OTHER_PROJECT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final Instant EXPORTED_AT = Instant.parse("2026-11-05T08:00:00Z");
    private static final IRI PALLET = IRI.create("http://example.org/wh#Pallet");
    private static final IRI BOX = IRI.create("http://example.org/anon#Box");
    private static final IRI IMPORTED = IRI.create("http://example.org/other");
    private static final IRI ANON_IMPORT = IRI.create("http://example.org/anon-import");
    private static final IRI NAMED = IRI.create("http://example.org/wh");
    private static final IRI VERSION = IRI.create("http://example.org/wh/1");

    private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();
    private final LegacyLogWriter writer = new LegacyLogWriter();
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void exports_a_package_that_matches_the_golden_file_and_ic_2_through_ic_6() throws Exception {
        OWLOntologyID named = new OWLOntologyID(NAMED);
        OWLOntologyID anonymous = new OWLOntologyID();
        OWLAxiom pallet = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PALLET));
        OWLAnnotation demo = comment("demo");
        OWLAxiom box = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(BOX));
        OWLAnnotation boxComment = comment("box");
        List<LegacyRevision> revisions = List.of(
                revision(
                        1,
                        "alice",
                        1_690_000_000_000L,
                        "Uploaded project",
                        addAxiom(named, pallet),
                        addAnnotation(named, demo),
                        addImport(named, IMPORTED)),
                revision(
                        2,
                        "bob",
                        1_690_000_001_000L,
                        "Edit anonymous",
                        addAxiom(anonymous, box),
                        addAnnotation(anonymous, boxComment),
                        addImport(anonymous, ANON_IMPORT),
                        removeImport(anonymous, ANON_IMPORT),
                        removeAnnotation(anonymous, boxComment),
                        removeAxiom(anonymous, box)),
                revision(
                        3,
                        "alice",
                        1_690_000_002_000L,
                        "Drop the pallet",
                        removeImport(named, IMPORTED),
                        removeAnnotation(named, demo),
                        removeAxiom(named, pallet)));
        Path dataDir = dataDir();
        writeLog(dataDir, PROJECT_ID, revisions);

        new ProjectExporter(clock).export(dataDir, outDir());

        Path projectDir = projectDir(PROJECT_ID);
        assertThat(Files.readString(projectDir.resolve("revisions.jsonl"), StandardCharsets.UTF_8))
                .isEqualTo(golden());
        JsonNode manifest = json.readTree(Files.readString(projectDir.resolve("manifest.json")));
        assertThat(fieldNames(manifest))
                .containsExactly("formatVersion", "projectId", "exportedAt", "source", "ontologies", "stats");
        assertThat(manifest.get("formatVersion").intValue()).isEqualTo(1);
        assertThat(manifest.get("projectId").asText()).isEqualTo(PROJECT_ID);
        assertThat(manifest.get("exportedAt").asText()).isEqualTo("2026-11-05T08:00:00Z");
        assertThat(fieldNames(manifest.get("source"))).containsExactly("tool", "toolVersion", "legacyDataDir");
        assertThat(manifest.get("source").get("tool").asText()).isEqualTo("wp-legacy-migrator");
        assertThat(manifest.get("source").get("toolVersion").asText()).isEqualTo(toolVersion());
        assertThat(manifest.get("source").get("legacyDataDir").asText())
                .isEqualTo(dataDir.toAbsolutePath().normalize().toString());
        assertThat(manifest.get("ontologies")).hasSize(2);
        assertOntology(manifest.get("ontologies").get(0), "o1", "http://example.org/wh", null);
        assertOntology(manifest.get("ontologies").get(1), "o2", null, null);
        assertThat(fieldNames(manifest.get("stats"))).containsExactly("revisionCount", "headRevision", "changeCount");
        assertThat(manifest.get("stats").get("revisionCount").longValue()).isEqualTo(3);
        assertThat(manifest.get("stats").get("headRevision").longValue()).isEqualTo(3);
        assertThat(manifest.get("stats").get("changeCount").longValue()).isEqualTo(12);
        assertChecksums(projectDir);
        assertIcRules(
                projectDir,
                List.of(
                        pallet,
                        demo,
                        IMPORTED,
                        box,
                        boxComment,
                        ANON_IMPORT,
                        ANON_IMPORT,
                        boxComment,
                        box,
                        IMPORTED,
                        demo,
                        pallet));
    }

    @Test
    void assigns_distinct_keys_for_versioned_named_and_anonymous_ontologies() throws Exception {
        OWLOntologyID versioned = new OWLOntologyID(NAMED, VERSION);
        OWLOntologyID unversioned = new OWLOntologyID(NAMED);
        OWLOntologyID firstAnonymous = new OWLOntologyID();
        OWLOntologyID secondAnonymous = new OWLOntologyID();
        OWLAxiom axiom = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PALLET));
        Path dataDir = dataDir();
        writeLog(
                dataDir,
                PROJECT_ID,
                List.of(revision(
                        1,
                        "alice",
                        10L,
                        "keys",
                        addAxiom(versioned, axiom),
                        addAxiom(unversioned, axiom),
                        addAxiom(firstAnonymous, axiom),
                        addAxiom(secondAnonymous, axiom),
                        addAxiom(versioned, dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(BOX))))));

        new ProjectExporter(clock).export(dataDir, outDir(), PROJECT_ID);

        JsonNode ontologies = json.readTree(
                        Files.readString(projectDir(PROJECT_ID).resolve("manifest.json")))
                .get("ontologies");
        assertThat(ontologies).hasSize(4);
        assertOntology(ontologies.get(0), "o1", "http://example.org/wh", "http://example.org/wh/1");
        assertOntology(ontologies.get(1), "o2", "http://example.org/wh", null);
        assertOntology(ontologies.get(2), "o3", null, null);
        assertOntology(ontologies.get(3), "o4", null, null);
        JsonNode changes = json.readTree(Files.readString(projectDir(PROJECT_ID).resolve("revisions.jsonl"))
                        .lines()
                        .findFirst()
                        .orElseThrow())
                .get("changes");
        assertThat(changes).hasSize(5);
        assertThat(keyOf(changes.get(0).asText())).isEqualTo("o1");
        assertThat(keyOf(changes.get(1).asText())).isEqualTo("o2");
        assertThat(keyOf(changes.get(2).asText())).isEqualTo("o3");
        assertThat(keyOf(changes.get(3).asText())).isEqualTo("o4");
        assertThat(keyOf(changes.get(4).asText())).isEqualTo("o1");
    }

    @Test
    void keeps_literal_newlines_and_writes_builtin_datatype_iris_in_full() throws Exception {
        OWLOntologyID named = new OWLOntologyID(NAMED);
        OWLAnnotation broken = comment("keep\nme");
        OWLAxiom typed = dataFactory.getOWLDataPropertyAssertionAxiom(
                dataFactory.getOWLDataProperty(IRI.create("http://example.org/wh#note")),
                dataFactory.getOWLNamedIndividual(PALLET),
                dataFactory.getOWLLiteral("30", dataFactory.getIntegerOWLDatatype()));
        OWLAxiom anonymousIndividual = dataFactory.getOWLClassAssertionAxiom(
                dataFactory.getOWLClass(PALLET), dataFactory.getOWLAnonymousIndividual("b1"));
        Path dataDir = dataDir();
        writeLog(
                dataDir,
                PROJECT_ID,
                List.of(revision(
                        1,
                        "alice",
                        10L,
                        "literals",
                        addAnnotation(named, broken),
                        addAxiom(named, typed),
                        addAxiom(named, anonymousIndividual))));

        new ProjectExporter(clock).export(dataDir, outDir());

        String jsonl = Files.readString(projectDir(PROJECT_ID).resolve("revisions.jsonl"), StandardCharsets.UTF_8);
        assertThat(jsonl.lines()).hasSize(1);
        JsonNode changes = json.readTree(jsonl.trim()).get("changes");
        String annotation = changes.get(0).asText();
        assertThat(annotation).contains("\"keep\nme\"");
        assertThat(newlinesOutsideLiterals(annotation)).isZero();
        assertThat(changes.get(1).asText())
                .contains("^^<http://www.w3.org/2001/XMLSchema#integer>")
                .doesNotContain("xsd:");
        String anonymousLine = changes.get(2).asText();
        // BinaryOWL renumbers blank nodes (b1 becomes _:0, _:1, …). OWL API 4's functional parser
        // then remints them, so the parsed individual is not equal to the one written into the log.
        assertThat(anonymousLine).containsPattern("_:[A-Za-z0-9]+");
        assertThat(parseAnnotation(ofn(annotation))).isEqualTo(broken);
        assertThat(parseAxiom(ofn(changes.get(1).asText()))).isEqualTo(typed);
        OWLAxiom parsedAnonymous = parseAxiom(ofn(anonymousLine));
        assertThat(parsedAnonymous.getAxiomType()).isEqualTo(AxiomType.CLASS_ASSERTION);
        assertThat(parsedAnonymous.getAnonymousIndividuals()).hasSize(1);
        assertThat(OfnLineRenderer.collapseFormattingNewlines(
                        "SubClassOf(Annotation(<http://example.org/p> \"keep\nme\")\r\n"
                                + "<http://example.org/A> <http://example.org/B>)"))
                .isEqualTo("SubClassOf(Annotation(<http://example.org/p> \"keep\nme\") "
                        + "<http://example.org/A> <http://example.org/B>)");
    }

    @Test
    void rejects_a_revision_gap_without_writing_a_package() throws Exception {
        Path dataDir = dataDir();
        OWLOntologyID named = new OWLOntologyID(NAMED);
        OWLAxiom axiom = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PALLET));
        writeLog(
                dataDir,
                PROJECT_ID,
                List.of(
                        revision(1, "alice", 1L, "first", addAxiom(named, axiom)),
                        revision(3, "alice", 2L, "skipped", addAxiom(named, axiom))));

        assertThatThrownBy(() -> new ProjectExporter(clock).export(dataDir, outDir()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(PROJECT_ID)
                .hasMessageContaining("3");
        assertThat(outDir()).doesNotExist();
    }

    @Test
    void exports_an_empty_project_directory() throws Exception {
        Path dataDir = dataDir();
        Files.createDirectories(
                dataDir.resolve("data-store").resolve("project-data").resolve("empty-project"));

        new ProjectExporter(clock).export(dataDir, outDir());

        Path projectDir = projectDir("empty-project");
        JsonNode manifest = json.readTree(Files.readString(projectDir.resolve("manifest.json")));
        assertThat(manifest.get("stats").get("revisionCount").longValue()).isZero();
        assertThat(manifest.get("stats").get("headRevision").longValue()).isZero();
        assertThat(manifest.get("stats").get("changeCount").longValue()).isZero();
        assertThat(manifest.get("ontologies")).isEmpty();
        assertThat(Files.readAllBytes(projectDir.resolve("revisions.jsonl"))).isEmpty();
        assertChecksums(projectDir);
    }

    @Test
    void export_command_selects_projects_and_reports_read_failures() throws Exception {
        Path dataDir = dataDir();
        OWLAxiom axiom = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PALLET));
        writeLog(
                dataDir,
                OTHER_PROJECT,
                List.of(revision(1, "bob", 1L, "other", addAxiom(new OWLOntologyID(NAMED), axiom))));
        writeLog(
                dataDir,
                PROJECT_ID,
                List.of(revision(1, "alice", 1L, "one", addAxiom(new OWLOntologyID(NAMED), axiom))));

        Capture selected =
                run("export", "--data-dir", dataDir.toString(), "--out", outDir().toString(), "--project", PROJECT_ID);

        assertThat(selected.exitCode()).isZero();
        assertThat(projectDir(PROJECT_ID).resolve("manifest.json")).exists();
        assertThat(outDir().resolve("projects").resolve(OTHER_PROJECT)).doesNotExist();

        Capture missing = run(
                "export",
                "--data-dir",
                dataDir.toString(),
                "--out",
                outDir().resolve("missing").toString(),
                "--project",
                "not-a-project");
        assertThat(missing.exitCode()).isEqualTo(1);
        assertThat(missing.err()).contains("not-a-project");
        assertThat(outDir().resolve("missing")).doesNotExist();

        Capture usage = run("export");
        assertThat(usage.exitCode()).isEqualTo(2);
        assertThat(usage.err()).contains("--data-dir");

        Path badOut = outDir().resolve("bad");
        writeLog(
                dataDir,
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
                List.of(revision(
                        1,
                        "carol",
                        1L,
                        "rename",
                        new OWLOntologyChangeRecord(
                                new OWLOntologyID(NAMED),
                                new SetOntologyIDData(new OWLOntologyID(IRI.create("http://example.org/renamed")))))));
        Capture rejected = run(
                "export",
                "--data-dir",
                dataDir.toString(),
                "--out",
                badOut.toString(),
                "--project",
                "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        assertThat(rejected.exitCode()).isEqualTo(1);
        assertThat(rejected.err()).contains("SetOntologyID");
        assertThat(badOut).doesNotExist();
    }

    private void assertIcRules(Path projectDir, List<Object> payloads) throws Exception {
        JsonNode manifest = json.readTree(Files.readString(projectDir.resolve("manifest.json")));
        List<String> lines = Files.readAllLines(projectDir.resolve("revisions.jsonl"), StandardCharsets.UTF_8);
        List<Long> revisionNumbers = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        for (String line : lines) {
            JsonNode revision = json.readTree(line);
            revisionNumbers.add(revision.get("rev").longValue());
            revision.get("changes").forEach(change -> changes.add(change.asText()));
        }
        assertThat(revisionNumbers).containsExactly(1L, 2L, 3L);
        assertThat(manifest.get("stats").get("headRevision").longValue())
                .isEqualTo(revisionNumbers.get(revisionNumbers.size() - 1));
        assertThat(changes).hasSize(manifest.get("stats").get("changeCount").intValue());
        assertThat(changes).hasSize(payloads.size());
        List<String> registered = new ArrayList<>();
        manifest.get("ontologies")
                .forEach(ontology -> registered.add(ontology.get("key").asText()));
        for (int index = 0; index < changes.size(); index++) {
            assertChange(changes.get(index), payloads.get(index), registered, index);
        }
    }

    private void assertChange(String change, Object payload, List<String> registered, int index) {
        String[] parts = change.split("\t", 4);
        assertThat(parts).hasSize(4);
        assertThat(parts[0]).isIn("+", "-");
        assertThat(registered).contains(parts[2]);
        try {
            switch (parts[1]) {
                case "AXIOM" -> assertThat(parseAxiom(parts[3])).isEqualTo(payload);
                case "ANNOTATION" -> assertThat(parseAnnotation(parts[3])).isEqualTo(payload);
                case "IMPORT" -> assertThat((Object) parseImport(parts[3])).isEqualTo(payload);
                default -> throw new AssertionError("unexpected kind " + parts[1]);
            }
        } catch (RuntimeException | OWLOntologyCreationException exception) {
            throw new AssertionError("parse failed at changeIndex " + index + ": " + change, exception);
        }
    }

    private static void assertOntology(JsonNode ontology, String key, String ontologyIri, String versionIri) {
        assertThat(fieldNames(ontology)).containsExactly("key", "ontologyIri", "versionIri");
        assertThat(ontology.get("key").asText()).isEqualTo(key);
        assertThat(textOrNull(ontology.get("ontologyIri"))).isEqualTo(ontologyIri);
        assertThat(textOrNull(ontology.get("versionIri"))).isEqualTo(versionIri);
    }

    private static void assertChecksums(Path projectDir) throws Exception {
        String expected = sha256(projectDir.resolve("manifest.json"))
                + "  manifest.json\n"
                + sha256(projectDir.resolve("revisions.jsonl"))
                + "  revisions.jsonl\n";
        assertThat(Files.readString(projectDir.resolve("SHA256SUMS"), StandardCharsets.UTF_8))
                .isEqualTo(expected);
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String keyOf(String change) {
        return change.split("\t", 4)[2];
    }

    private static String ofn(String change) {
        return change.split("\t", 4)[3];
    }

    private static int newlinesOutsideLiterals(String rendered) {
        int count = 0;
        boolean inLiteral = false;
        for (int i = 0; i < rendered.length(); i++) {
            char current = rendered.charAt(i);
            if (inLiteral) {
                if (current == '\\' && i + 1 < rendered.length()) {
                    i++;
                } else if (current == '"') {
                    inLiteral = false;
                }
            } else if (current == '"') {
                inLiteral = true;
            } else if (current == '\n' || current == '\r') {
                count++;
            }
        }
        return count;
    }

    private OWLAxiom parseAxiom(String ofn) throws OWLOntologyCreationException {
        return one(ofn, ontology -> {
            assertThat(ontology.getAnnotations()).isEmpty();
            assertThat(ontology.getAxioms()).hasSize(1);
            return ontology.getAxioms().iterator().next();
        });
    }

    private OWLAnnotation parseAnnotation(String ofn) throws OWLOntologyCreationException {
        return one(ofn, ontology -> {
            assertThat(ontology.getAxioms()).isEmpty();
            assertThat(ontology.getAnnotations()).hasSize(1);
            return ontology.getAnnotations().iterator().next();
        });
    }

    private <T> T one(String ofn, Extractor<T> extractor) throws OWLOntologyCreationException {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.addIRIMapper(iri -> IRI.create("urn:wp:blocked-import"));
        OWLOntologyLoaderConfiguration configuration = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(1);
        OWLOntology ontology = manager.loadOntologyFromOntologyDocument(
                new StringDocumentSource("Ontology(" + ofn + ")"), configuration);
        try {
            assertThat(ontology.getImportsDeclarations()).isEmpty();
            return extractor.extract(ontology);
        } finally {
            manager.removeOntology(ontology);
        }
    }

    private static IRI parseImport(String ofn) {
        assertThat(ofn).startsWith("<").endsWith(">");
        return IRI.create(ofn.substring(1, ofn.length() - 1));
    }

    private OWLAnnotation comment(String lexical) {
        return dataFactory.getOWLAnnotation(dataFactory.getRDFSComment(), dataFactory.getOWLLiteral(lexical));
    }

    private static OWLOntologyChangeRecord addAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) {
        return new OWLOntologyChangeRecord(ontologyId, new AddAxiomData(axiom));
    }

    private static OWLOntologyChangeRecord removeAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) {
        return new OWLOntologyChangeRecord(ontologyId, new RemoveAxiomData(axiom));
    }

    private static OWLOntologyChangeRecord addAnnotation(OWLOntologyID ontologyId, OWLAnnotation annotation) {
        return new OWLOntologyChangeRecord(ontologyId, new AddOntologyAnnotationData(annotation));
    }

    private static OWLOntologyChangeRecord removeAnnotation(OWLOntologyID ontologyId, OWLAnnotation annotation) {
        return new OWLOntologyChangeRecord(ontologyId, new RemoveOntologyAnnotationData(annotation));
    }

    private OWLOntologyChangeRecord addImport(OWLOntologyID ontologyId, IRI iri) {
        return new OWLOntologyChangeRecord(ontologyId, new AddImportData(dataFactory.getOWLImportsDeclaration(iri)));
    }

    private OWLOntologyChangeRecord removeImport(OWLOntologyID ontologyId, IRI iri) {
        return new OWLOntologyChangeRecord(ontologyId, new RemoveImportData(dataFactory.getOWLImportsDeclaration(iri)));
    }

    private static LegacyRevision revision(
            long number, String username, long timestamp, String description, OWLOntologyChangeRecord... records) {
        return new LegacyRevision(number, username, timestamp, description, List.of(records));
    }

    private void writeLog(Path dataDir, String projectId, List<LegacyRevision> revisions) throws IOException {
        Path log = dataDir.resolve("data-store")
                .resolve("project-data")
                .resolve(projectId)
                .resolve("change-data")
                .resolve("change-data.binary");
        Files.createDirectories(log.getParent());
        for (LegacyRevision revision : revisions) {
            writer.append(log, revision);
        }
    }

    private Path dataDir() {
        return tempDir.resolve("data");
    }

    private Path outDir() {
        return tempDir.resolve("out");
    }

    private Path projectDir(String projectId) {
        return outDir().resolve("projects").resolve(projectId);
    }

    private static String golden() throws IOException {
        try (InputStream in = ProjectExporterTest.class.getResourceAsStream("/export/golden-revisions.jsonl")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String toolVersion() throws IOException {
        try (InputStream in = ProjectExporterTest.class.getResourceAsStream("/wprebuild/migrator/version.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version");
        }
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(out, true, StandardCharsets.UTF_8);
        PrintWriter errWriter = new PrintWriter(err, true, StandardCharsets.UTF_8);
        CommandLine commandLine = new CommandLine(new Main());
        commandLine.setOut(outWriter);
        commandLine.setErr(errWriter);
        int exitCode = commandLine.execute(args);
        outWriter.flush();
        errWriter.flush();
        return new Capture(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @FunctionalInterface
    private interface Extractor<T> {
        T extract(OWLOntology ontology);
    }

    private record Capture(int exitCode, String out, String err) {}
}
