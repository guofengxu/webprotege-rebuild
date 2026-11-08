package wprebuild.app.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;

@DatabaseTest
class JdbcRevisionLogOutboxTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T12:00:00Z");
    private static final OntologyKey ONTOLOGY = OntologyKey.of(1);

    private final JdbcRevisionLog log;
    private final JdbcClient jdbc;
    private final TestData data;

    JdbcRevisionLogOutboxTest(JdbcRevisionLog log, JdbcClient jdbc) {
        this.log = log;
        this.jdbc = jdbc;
        this.data = new TestData(jdbc);
    }

    @Test
    void each_append_writes_one_revision_committed_outbox_row() {
        ProjectId projectId = ProjectId.random();
        data.insertProject(projectId, UserId.SYSTEM);
        UserId editor = new UserId("editor");
        Revision first = revision(1, editor, "import", declare("http://example.org/Person"));
        Revision second = revision(2, UserId.SYSTEM, "empty");

        log.append(projectId, RevisionNumber.ZERO, first);
        log.append(projectId, RevisionNumber.of(1), second);

        List<OutboxRow> rows = outboxRows(projectId);
        assertThat(rows).containsExactly(outboxRow(projectId, first), outboxRow(projectId, second));
        assertThat(codecs(projectId)).containsExactly("ofn-lines/1", "ofn-lines/1");
    }

    @Test
    void revision_timestamp_and_outbox_ts_share_microsecond_precision() {
        ProjectId projectId = ProjectId.random();
        data.insertProject(projectId, UserId.SYSTEM);
        Instant timestamp = Instant.parse("2026-01-15T12:00:00.123456789Z");
        Revision revision = new Revision(RevisionNumber.of(1), UserId.SYSTEM, timestamp, "nanos", List.of());

        log.append(projectId, RevisionNumber.ZERO, revision);

        Instant stored = timestamp.truncatedTo(ChronoUnit.MICROS);
        assertThat(log.get(projectId, RevisionNumber.of(1)).orElseThrow().timestamp())
                .isEqualTo(stored);
        assertThat(outboxRows(projectId))
                .containsExactly(new OutboxRow(
                        1, "REVISION_COMMITTED", projectId.toString(), 1, UserId.SYSTEM.value(), stored.toString(), 0));
    }

    @Test
    void revision_subjects_store_signature_iris_and_iri_annotation_subjects_once() {
        ProjectId projectId = ProjectId.random();
        data.insertProject(projectId, UserId.SYSTEM);
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        IRI person = IRI.create("http://example.org/Person");
        IRI employee = IRI.create("http://example.org/Employee");
        IRI bareSubject = IRI.create("http://example.org/bare-subject");
        Revision revision = revision(
                1,
                UserId.SYSTEM,
                "subjects",
                new AddAxiom(ONTOLOGY, factory.getOWLDeclarationAxiom(factory.getOWLClass(person))),
                new AddAxiom(
                        ONTOLOGY,
                        factory.getOWLSubClassOfAxiom(factory.getOWLClass(person), factory.getOWLClass(employee))),
                new AddAxiom(
                        ONTOLOGY,
                        factory.getOWLAnnotationAssertionAxiom(
                                factory.getRDFSLabel(), bareSubject, factory.getOWLLiteral("bare"))),
                new AddAxiom(
                        ONTOLOGY,
                        factory.getOWLAnnotationAssertionAxiom(
                                factory.getRDFSLabel(), person, factory.getOWLLiteral("Person"))),
                new AddAxiom(
                        ONTOLOGY,
                        factory.getOWLAnnotationAssertionAxiom(
                                factory.getRDFSComment(),
                                factory.getOWLAnonymousIndividual("anon"),
                                factory.getOWLLiteral("hidden"))));

        log.append(projectId, RevisionNumber.ZERO, revision);

        assertThat(subjectIris(projectId))
                .containsExactlyInAnyOrder(
                        person.toString(),
                        employee.toString(),
                        bareSubject.toString(),
                        factory.getRDFSLabel().getIRI().toString(),
                        factory.getRDFSComment().getIRI().toString(),
                        factory.getStringOWLDatatype().getIRI().toString());
    }

    @Nested
    class WhenOutboxWriteFails {

        @MockitoBean
        private RevisionCommitOutbox outbox;

        @Autowired
        private JdbcRevisionLog failingLog;

        @Autowired
        private JdbcClient failingJdbc;

        @BeforeEach
        void outbox_write_throws() {
            doThrow(new IllegalStateException("outbox write failed"))
                    .when(outbox)
                    .record(any(), any());
        }

        @Test
        void failed_outbox_write_rolls_back_the_revision() {
            ProjectId projectId = ProjectId.random();
            new TestData(failingJdbc).insertProject(projectId, UserId.SYSTEM);

            assertThatThrownBy(() -> failingLog.append(
                            projectId,
                            RevisionNumber.ZERO,
                            revision(1, UserId.SYSTEM, "lost", declare("http://example.org/Lost"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("outbox");

            assertThat(count("revisions", projectId)).isZero();
            assertThat(count("revision_subjects", projectId)).isZero();
            assertThat(count("outbox", projectId)).isZero();
        }

        private long count(String table, ProjectId projectId) {
            String sql = switch (table) {
                case "revisions" -> "select count(*) from revisions where project_id = ?";
                case "revision_subjects" -> "select count(*) from revision_subjects where project_id = ?";
                case "outbox" -> "select count(*) from outbox where project_id = ?";
                default -> throw new IllegalArgumentException("unexpected table: " + table);
            };
            return failingJdbc
                    .sql(sql)
                    .param(projectId.value())
                    .query(Long.class)
                    .single();
        }
    }

    private List<String> subjectIris(ProjectId projectId) {
        return jdbc.sql("""
                        select subject_iri
                        from revision_subjects
                        where project_id = ?
                        order by subject_iri
                        """).param(projectId.value()).query(String.class).list();
    }

    private List<String> codecs(ProjectId projectId) {
        return jdbc.sql("""
                        select codec
                        from revisions
                        where project_id = ?
                        order by rev_no
                        """).param(projectId.value()).query(String.class).list();
    }

    private List<OutboxRow> outboxRows(ProjectId projectId) {
        return jdbc.sql("""
                        select rev_no,
                               type,
                               payload->>'projectId' as project_id_text,
                               payload->>'revNo' as rev_no_text,
                               payload->>'userId' as user_id_text,
                               payload->>'ts' as ts_text,
                               payload->>'changeCount' as change_count_text
                        from outbox
                        where project_id = ?
                        order by rev_no
                        """)
                .param(projectId.value())
                .query((rs, rowNum) -> new OutboxRow(
                        rs.getLong("rev_no"),
                        rs.getString("type"),
                        rs.getString("project_id_text"),
                        Long.parseLong(rs.getString("rev_no_text")),
                        rs.getString("user_id_text"),
                        rs.getString("ts_text"),
                        Integer.parseInt(rs.getString("change_count_text"))))
                .list();
    }

    private static OutboxRow outboxRow(ProjectId projectId, Revision revision) {
        return new OutboxRow(
                revision.number().value(),
                "REVISION_COMMITTED",
                projectId.toString(),
                revision.number().value(),
                revision.user().value(),
                revision.timestamp().toString(),
                revision.changes().size());
    }

    private static OntologyChange declare(String iri) {
        OWLDataFactory factory = OWLManager.getOWLDataFactory();
        return new AddAxiom(ONTOLOGY, factory.getOWLDeclarationAxiom(factory.getOWLClass(IRI.create(iri))));
    }

    private static Revision revision(long number, UserId user, String description, OntologyChange... changes) {
        return new Revision(RevisionNumber.of(number), user, TIMESTAMP, description, List.of(changes));
    }

    private record OutboxRow(
            long revNo,
            String type,
            String projectId,
            long payloadRevNo,
            String userId,
            String timestamp,
            int changeCount) {}
}
