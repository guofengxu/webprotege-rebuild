package wprebuild.migrator.legacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.semanticweb.binaryowl.BinaryOWLMetadata;
import org.semanticweb.binaryowl.BinaryOWLOntologyChangeLog;
import org.semanticweb.binaryowl.change.OntologyChangeRecordList;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.change.AddAxiomData;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.change.SetOntologyIDData;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyID;
import picocli.CommandLine;
import wprebuild.migrator.Main;

class LegacyRevisionReaderTest {

    private static final String PROJECT_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String PROJECT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    private final OWLDataFactory dataFactory = OWLManager.getOWLDataFactory();
    private final LegacyLogWriter writer = new LegacyLogWriter();
    private final LegacyRevisionReader reader = new LegacyRevisionReader();

    @TempDir
    Path tempDir;

    @Test
    void reads_three_revisions_with_matching_fields() throws IOException {
        OWLOntologyID alpha = ontology("http://example.org/alpha");
        OWLOntologyID beta = ontology("http://example.org/beta");
        LegacyRevision first = revision(10, "alice", 1_700_000_000_000L, "add alpha", declaration(alpha, "A"));
        LegacyRevision second = revision(11, "bob", 1_700_000_001_000L, "add beta", declaration(beta, "B"));
        LegacyRevision third = revision(
                12,
                "alice",
                1_700_000_002_000L,
                "add another alpha class",
                declaration(alpha, "C"),
                declaration(alpha, "D"));
        Path log = changeLog(PROJECT_A);
        writer.append(log, first);
        writer.append(log, second);
        writer.append(log, third);

        List<LegacyRevision> read = new ArrayList<>();
        reader.read(log, read::add);

        assertThat(read).containsExactly(first, second, third);
    }

    @Test
    void truncated_log_fails_with_last_successful_revision() throws IOException {
        Path log = changeLog(PROJECT_A);
        writer.append(log, revision(10, "alice", 1L, "first", declaration(ontology("http://example.org/a"), "A")));
        writer.append(log, revision(20, "bob", 2L, "second", declaration(ontology("http://example.org/a"), "B")));
        long completeLength = Files.size(log);
        writer.append(log, revision(30, "carol", 3L, "third", declaration(ontology("http://example.org/a"), "C")));
        byte[] bytes = Files.readAllBytes(log);
        Files.write(log, Arrays.copyOf(bytes, (int) completeLength + 1));

        assertThatThrownBy(() -> reader.read(log, revision -> {}))
                .isExactlyInstanceOf(LegacyReadException.class)
                .hasMessageContaining("project=" + PROJECT_A)
                .hasMessageContaining("lastSuccessfulRevision=20")
                .hasMessageContaining("offset=");
    }

    @Test
    void corrupt_header_fails_before_any_revision() throws IOException {
        Path log = changeLog(PROJECT_A);
        Files.write(log, new byte[] {0, 1, 2, 3});

        assertThatThrownBy(() -> reader.read(log, revision -> {}))
                .isExactlyInstanceOf(LegacyReadException.class)
                .hasMessageContaining("project=" + PROJECT_A)
                .hasMessageContaining("lastSuccessfulRevision=none")
                .hasMessageContaining("offset=");
    }

    @Test
    void trailing_non_marker_byte_fails_after_the_last_revision() throws IOException {
        Path log = changeLog(PROJECT_A);
        writer.append(log, revision(10, "alice", 1L, "only", declaration(ontology("http://example.org/a"), "A")));
        Files.write(log, new byte[] {0}, StandardOpenOption.APPEND);

        assertThatThrownBy(() -> reader.read(log, revision -> {}))
                .isExactlyInstanceOf(LegacyReadException.class)
                .hasMessageContaining("project=" + PROJECT_A)
                .hasMessageContaining("lastSuccessfulRevision=10")
                .hasMessageContaining("non-chunk marker");
    }

    @Test
    void missing_revision_attribute_fails() throws IOException {
        Path log = changeLog(PROJECT_A);
        BinaryOWLMetadata metadata = new BinaryOWLMetadata();
        metadata.setStringAttribute("username", "alice");
        metadata.setStringAttribute("description", "no revision");
        metadata.setStringAttribute("revisionType", "EDIT");
        OntologyChangeRecordList chunk =
                new OntologyChangeRecordList(1L, metadata, List.of(declaration(ontology("http://example.org/a"), "A")));
        new BinaryOWLOntologyChangeLog().appendChanges(chunk, log.toFile());

        assertThatThrownBy(() -> reader.read(log, revision -> {}))
                .isExactlyInstanceOf(LegacyReadException.class)
                .hasMessageContaining("project=" + PROJECT_A)
                .hasMessageContaining("lastSuccessfulRevision=none")
                .hasMessageContaining("revision attribute is missing");
    }

    @Test
    void set_ontology_id_fails_with_revision_and_change_index() throws IOException {
        OWLOntologyID alpha = ontology("http://example.org/alpha");
        OWLOntologyID renamed = ontology("http://example.org/renamed");
        Path log = changeLog(PROJECT_A);
        writer.append(log, revision(4, "alice", 10L, "ok", declaration(alpha, "A")));
        writer.append(
                log,
                revision(
                        5,
                        "alice",
                        11L,
                        "rename",
                        declaration(alpha, "B"),
                        new OWLOntologyChangeRecord(alpha, new SetOntologyIDData(renamed))));
        List<LegacyRevision> read = new ArrayList<>();
        long fileSize = Files.size(log);

        assertThatThrownBy(() -> reader.read(log, read::add))
                .isInstanceOfSatisfying(UnsupportedLegacyChangeException.class, exception -> {
                    assertThat(exception.revision()).isEqualTo(5);
                    assertThat(exception.changeIndex()).isEqualTo(1);
                    assertThat(exception.offset()).isEqualTo(fileSize);
                    assertThat(exception.getMessage()).contains("project=" + PROJECT_A);
                    assertThat(exception.getMessage()).contains("lastSuccessfulRevision=4");
                });
        assertThat(read).hasSize(1);
        assertThat(read.get(0).revision()).isEqualTo(4);
    }

    @Test
    void inspect_prints_one_summary_line_per_project() throws IOException {
        Path root = dataDirectory();
        OWLOntologyID alpha = ontology("http://example.org/alpha");
        OWLOntologyID beta = ontology("http://example.org/beta");
        writer.append(
                changeLog(PROJECT_B),
                revision(9, "bob", 2L, "two ontologies", declaration(alpha, "A"), declaration(beta, "B")));
        writer.append(changeLog(PROJECT_B), revision(8, "bob", 3L, "again", declaration(alpha, "C")));
        writer.append(changeLog(PROJECT_A), revision(7, "alice", 1L, "one", declaration(alpha, "A")));
        Files.createDirectories(
                root.resolve("data-store").resolve("project-data").resolve("empty-project"));

        Capture capture = run("inspect", "--data-dir", root.toString());

        assertThat(capture.exitCode()).isZero();
        assertThat(capture.out().lines().toList())
                .containsExactly(PROJECT_A + " 1 7 1 1", PROJECT_B + " 2 8 3 2", "empty-project 0 - 0 0");
    }

    @Test
    void inspect_prints_nothing_when_a_later_log_is_corrupt() throws IOException {
        writer.append(
                changeLog(PROJECT_A),
                revision(7, "alice", 1L, "one", declaration(ontology("http://example.org/a"), "A")));
        Files.write(changeLog(PROJECT_B), new byte[] {0, 1, 2});

        Capture capture = run("inspect", "--data-dir", dataDirectory().toString());

        assertThat(capture.exitCode()).isEqualTo(1);
        assertThat(capture.out()).isEmpty();
        assertThat(capture.err()).contains("project=" + PROJECT_B).contains("lastSuccessfulRevision=none");
    }

    private Path dataDirectory() {
        return tempDir.resolve("data");
    }

    private Path changeLog(String projectId) throws IOException {
        Path log = dataDirectory()
                .resolve("data-store")
                .resolve("project-data")
                .resolve(projectId)
                .resolve("change-data")
                .resolve("change-data.binary");
        Files.createDirectories(log.getParent());
        return log;
    }

    private LegacyRevision revision(
            long number, String username, long timestamp, String description, OWLOntologyChangeRecord... records) {
        return new LegacyRevision(number, username, timestamp, description, List.of(records));
    }

    private OWLOntologyID ontology(String iri) {
        return new OWLOntologyID(IRI.create(iri));
    }

    private OWLOntologyChangeRecord declaration(OWLOntologyID ontologyId, String localName) {
        IRI iri = IRI.create("http://example.org/" + localName);
        return new OWLOntologyChangeRecord(
                ontologyId, new AddAxiomData(dataFactory.getOWLDeclarationAxiom(dataFactory.getOWLClass(iri))));
    }

    private static Capture run(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintWriter outWriter = new PrintWriter(new PrintStream(out, true, StandardCharsets.UTF_8), true);
        PrintWriter errWriter = new PrintWriter(new PrintStream(err, true, StandardCharsets.UTF_8), true);
        CommandLine commandLine = new CommandLine(new Main());
        commandLine.setOut(outWriter);
        commandLine.setErr(errWriter);
        int exitCode = commandLine.execute(args);
        outWriter.flush();
        errWriter.flush();
        return new Capture(exitCode, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int exitCode, String out, String err) {}
}
