package wprebuild.migrator.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.change.AddAxiomData;
import org.semanticweb.owlapi.change.AddImportData;
import org.semanticweb.owlapi.change.AddOntologyAnnotationData;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.change.RemoveImportData;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyID;
import picocli.CommandLine;
import wprebuild.migrator.Main;
import wprebuild.migrator.legacy.LegacyLogWriter;
import wprebuild.migrator.legacy.LegacyRevision;

/**
 * Export a synthetic legacy project and check the head baseline, migration report, and verify.
 */
class EndToEndExportTest {

    private static final String PROJECT_ID = "2f0b8a6e-6c1e-4c2d-9a51-0b5e0c3c1d11";
    private static final Instant EXPORTED_AT = Instant.parse("2026-11-05T08:00:00Z");
    private static final IRI PALLET = IRI.create("http://example.org/wh#Pallet");
    private static final IRI BOX = IRI.create("http://example.org/wh#Box");
    private static final IRI IMPORTED = IRI.create("http://example.org/other");
    private static final IRI NAMED = IRI.create("http://example.org/wh");

    private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();
    private final LegacyLogWriter writer = new LegacyLogWriter();
    private final ObjectMapper json = new ObjectMapper();
    private final Clock clock = Clock.fixed(EXPORTED_AT, ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void export_then_verify_writes_a_sorted_head_and_a_success_report() throws Exception {
        Path out = export();

        Path projectDir = out.resolve("projects").resolve(PROJECT_ID);
        List<String> head = headLines(projectDir.resolve("head.ofn-lines.gz"));
        assertThat(head).isSorted();
        assertThat(head).hasSize(4);
        assertThat(head)
                .allSatisfy(line -> assertThat(line).doesNotContain("\n").doesNotContain("\r"));
        assertThat(String.join("\n", head)).contains("\\n");
        assertThat(head.get(0)).startsWith("+\tANNOTATION\to1\t");
        assertThat(head).anySatisfy(line -> assertThat(line).contains("wh#Box"));
        assertThat(head).anySatisfy(line -> assertThat(line).contains("wh#Pallet"));
        assertThat(head).anySatisfy(line -> assertThat(line).contains("SubClassOf("));
        assertThat(head).noneSatisfy(line -> assertThat(line).contains("example.org/other"));

        JsonNode report = json.readTree(Files.readString(out.resolve("migration-report.json")));
        assertThat(report.get("warnings")).isEmpty();
        JsonNode project = report.get("projects").get(0);
        assertThat(project.get("projectId").asText()).isEqualTo(PROJECT_ID);
        assertThat(project.get("status").asText()).isEqualTo("success");
        assertThat(project.get("revisionCount").longValue()).isEqualTo(2);
        assertThat(project.get("changeCount").longValue()).isEqualTo(6);
        assertThat(project.get("axiomCounts").get("o1").get("Declaration").intValue())
                .isEqualTo(2);
        assertThat(project.get("axiomCounts").get("o1").get("SubClassOf").intValue())
                .isEqualTo(1);
        assertThat(Files.readString(projectDir.resolve("SHA256SUMS"))).contains("head.ofn-lines.gz");

        Capture verified = run("verify", "--package", out.toString());
        assertThat(verified.exitCode()).isZero();
        assertThat(verified.err()).isEmpty();
    }

    @Test
    void verify_reports_ic_1_when_one_byte_of_revisions_changes() throws Exception {
        Path out = export();
        Path revisions = out.resolve("projects").resolve(PROJECT_ID).resolve("revisions.jsonl");
        byte[] bytes = Files.readAllBytes(revisions);
        bytes[bytes.length / 2] ^= 0x01;
        Files.write(revisions, bytes);

        Capture verified = run("verify", "--package", out.toString());

        assertThat(verified.exitCode()).isNotZero();
        assertThat(verified.err()).contains("IC-1");
        assertThat(verified.err()).contains("revisions.jsonl");
    }

    @Test
    void verify_reports_ic_3_when_a_revision_line_is_deleted() throws Exception {
        Path out = export();
        Path projectDir = out.resolve("projects").resolve(PROJECT_ID);
        List<String> lines = new ArrayList<>(Files.readAllLines(projectDir.resolve("revisions.jsonl")));
        lines.remove(lines.size() - 1);
        Files.write(projectDir.resolve("revisions.jsonl"), lines, StandardCharsets.UTF_8);
        // A deleted line also breaks the checksum. Refresh it so this case is IC-3, not IC-1.
        rewriteChecksums(projectDir);

        Capture verified = run("verify", "--package", out.toString());

        assertThat(verified.exitCode()).isNotZero();
        assertThat(verified.err()).contains("IC-3");
        assertThat(verified.err()).doesNotContain("IC-1");
    }

    @Test
    void verify_reports_ic_2_ic_4_ic_5_and_ic_6() throws Exception {
        assertThat(tamper(this::formatVersion)).contains("IC-2").doesNotContain("IC-1");
        assertThat(tamper(this::changeCount)).contains("IC-4").doesNotContain("IC-1");
        assertThat(tamper(this::unknownOntologyKey)).contains("IC-5").doesNotContain("IC-1");
        assertThat(tamper(this::unparseableOfn)).contains("IC-6").doesNotContain("IC-1");
    }

    private String tamper(Tamper tamper) throws Exception {
        Path out = export();
        Path projectDir = out.resolve("projects").resolve(PROJECT_ID);
        tamper.apply(projectDir);
        rewriteChecksums(projectDir);
        return run("verify", "--package", out.toString()).err();
    }

    private void formatVersion(Path projectDir) throws IOException {
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(projectDir.resolve("manifest.json")));
        manifest.put("formatVersion", 2);
        Files.writeString(projectDir.resolve("manifest.json"), json.writeValueAsString(manifest));
    }

    private void changeCount(Path projectDir) throws IOException {
        ObjectNode manifest = (ObjectNode) json.readTree(Files.readString(projectDir.resolve("manifest.json")));
        ((ObjectNode) manifest.get("stats")).put("changeCount", 0);
        Files.writeString(projectDir.resolve("manifest.json"), json.writeValueAsString(manifest));
    }

    private void unknownOntologyKey(Path projectDir) throws IOException {
        Path revisions = projectDir.resolve("revisions.jsonl");
        // Jackson writes the tab separators as the two characters '\' and 't'.
        Files.writeString(revisions, Files.readString(revisions).replace("\\to1\\t", "\\to9\\t"));
    }

    private void unparseableOfn(Path projectDir) throws IOException {
        Path revisions = projectDir.resolve("revisions.jsonl");
        Files.writeString(revisions, Files.readString(revisions).replace("Declaration(", "NotAnAxiom("));
    }

    private Path export() throws IOException {
        Path dataDir = tempDir.resolve("data-" + System.nanoTime());
        Path out = tempDir.resolve("out-" + System.nanoTime());
        exportTo(dataDir, out);
        return out;
    }

    private void exportTo(Path dataDir, Path out) throws IOException {
        OWLOntologyID named = new OWLOntologyID(NAMED);
        OWLAxiom pallet = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(PALLET));
        OWLAxiom box = dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(BOX));
        OWLAxiom subclass =
                dataFactory.getOWLSubClassOfAxiom(dataFactory.getOWLClass(BOX), dataFactory.getOWLClass(PALLET));
        OWLAnnotation comment =
                dataFactory.getOWLAnnotation(dataFactory.getRDFSComment(), dataFactory.getOWLLiteral("keep\nme"));
        List<LegacyRevision> revisions = List.of(
                revision(
                        1,
                        "alice",
                        1_690_000_000_000L,
                        "Uploaded project",
                        addAxiom(named, pallet),
                        addAnnotation(named, comment),
                        addImport(named, IMPORTED)),
                revision(
                        2,
                        "bob",
                        1_690_000_001_000L,
                        "Name the box",
                        removeImport(named, IMPORTED),
                        addAxiom(named, box),
                        addAxiom(named, subclass)));
        Path log = dataDir.resolve("data-store")
                .resolve("project-data")
                .resolve(PROJECT_ID)
                .resolve("change-data")
                .resolve("change-data.binary");
        Files.createDirectories(log.getParent());
        for (LegacyRevision revision : revisions) {
            writer.append(log, revision);
        }
        new ProjectExporter(clock).export(dataDir, out);
    }

    private static List<String> headLines(Path gzip) throws IOException {
        byte[] compressed = Files.readAllBytes(gzip);
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (text.isEmpty()) {
                return List.of();
            }
            assertThat(text).endsWith("\n");
            return List.of(text.substring(0, text.length() - 1).split("\n", -1));
        }
    }

    private static void rewriteChecksums(Path projectDir) throws Exception {
        StringBuilder sums = new StringBuilder();
        for (String name : List.of("manifest.json", "revisions.jsonl", "head.ofn-lines.gz")) {
            sums.append(sha256(projectDir.resolve(name)))
                    .append("  ")
                    .append(name)
                    .append('\n');
        }
        Files.writeString(projectDir.resolve("SHA256SUMS"), sums.toString());
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(Files.readAllBytes(file));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static OWLOntologyChangeRecord addAxiom(OWLOntologyID ontologyId, OWLAxiom axiom) {
        return new OWLOntologyChangeRecord(ontologyId, new AddAxiomData(axiom));
    }

    private static OWLOntologyChangeRecord addAnnotation(OWLOntologyID ontologyId, OWLAnnotation annotation) {
        return new OWLOntologyChangeRecord(ontologyId, new AddOntologyAnnotationData(annotation));
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
    private interface Tamper {
        void apply(Path projectDir) throws IOException;
    }

    private record Capture(int exitCode, String out, String err) {}
}
