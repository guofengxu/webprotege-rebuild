package wprebuild.kernel.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;

/**
 * BS-04, ported from {@code RevisionReverterChangeListGenerator_TestCase}.
 *
 * <p>A revert is a new revision. Inverse changes are filtered by BS-02, so an axiom removed by a
 * later revision does not reappear as a deletion.
 */
class BS04_RevertRevisionGeneratorTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-04T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OWLClass CLASS_A = DATA.getOWLClass(IRI.create("http://example.org/A"));

    private static final OWLClass CLASS_B = DATA.getOWLClass(IRI.create("http://example.org/B"));

    private static final OWLAxiom AXIOM_A = DATA.getOWLDeclarationAxiom(CLASS_A);

    private static final OWLAxiom AXIOM_B = DATA.getOWLDeclarationAxiom(CLASS_B);

    private static final OWLAnnotation ANNOTATION =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("note"));

    private static final IRI IMPORTED = IRI.create("http://example.org/imported");

    @Test
    void generates_inverse_changes_in_reverse_order() {
        AddAxiom addAxiom = new AddAxiom(ROOT, AXIOM_A);
        AddOntologyAnnotation addAnnotation = new AddOntologyAnnotation(ROOT, ANNOTATION);
        AddImport addImport = new AddImport(ROOT, IMPORTED);
        StoredLog stored = store(revision(3, List.of(addAxiom, addAnnotation, addImport)));

        GeneratedChanges<RevertResult> generated = generator(stored, 3).generate(context());

        assertThat(generated.result().reverted()).isTrue();
        assertThat(generated.result().reason()).isEmpty();
        assertThat(generated.changes())
                .containsExactly(
                        new RemoveImport(ROOT, IMPORTED),
                        new RemoveOntologyAnnotation(ROOT, ANNOTATION),
                        new RemoveAxiom(ROOT, AXIOM_A));
    }

    @Test
    void returns_no_changes_when_the_target_revision_is_missing() {
        StoredLog stored = new StoredLog(ProjectId.random(), new InMemoryRevisionLog());

        GeneratedChanges<RevertResult> generated = generator(stored, 3).generate(context());

        assertThat(generated.changes()).isEmpty();
        assertThat(generated.result()).isEqualTo(rejected("revision does not exist: 3"));
    }

    @Test
    void describes_the_revision_being_reverted() {
        StoredLog stored = store(revision(3, List.of(new AddAxiom(ROOT, AXIOM_A))));
        RevertRevisionGenerator generator = generator(stored, 3);
        GeneratedChanges<RevertResult> generated = generator.generate(context());

        assertThat(generator.describe(generated, generated.changes())).isEqualTo("Reverted revision 3");
    }

    @Test
    void rename_result_keeps_the_revert_result() {
        RevertResult result = new RevertResult(true, Optional.empty());
        RevertRevisionGenerator generator = generator(new StoredLog(ProjectId.random(), new InMemoryRevisionLog()), 3);

        assertThat(generator.renameResult(result, Map.of(IRI.create("http://example.org/tmp"), CLASS_A.getIRI())))
                .isSameAs(result);
    }

    @Test
    void rejects_a_revision_that_declares_an_ontology() {
        StoredLog stored = store(revision(1, List.of(declare(), new AddAxiom(ROOT, AXIOM_A))));

        GeneratedChanges<RevertResult> generated = generator(stored, 1).generate(context());

        assertThat(generated.changes()).isEmpty();
        assertThat(generated.result()).isEqualTo(rejected("revision contains DeclareOntology: 1"));
    }

    @Test
    void reverting_revision_2_keeps_only_the_deletion_of_b() {
        Session session = Session.open();
        session.apply(List.of(declare()), "Declared the ontology");
        session.apply(List.of(new AddAxiom(ROOT, AXIOM_A), new AddAxiom(ROOT, AXIOM_B)), "Added A and B");
        session.apply(List.of(new RemoveAxiom(ROOT, AXIOM_A)), "Deleted A");

        ChangeApplicationResult<RevertResult> reverted = session.revert(2);

        assertThat(reverted.result()).isEqualTo(new RevertResult(true, Optional.empty()));
        Revision revision = reverted.revision().orElseThrow();
        assertThat(revision.number()).isEqualTo(RevisionNumber.of(4));
        assertThat(revision.description()).isEqualTo("Reverted revision 2");
        assertThat(revision.changes()).containsExactly(new RemoveAxiom(ROOT, AXIOM_B));
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_A)).isFalse();
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_B)).isFalse();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(4));

        ChangeApplicationResult<RevertResult> declared = session.revert(1);

        assertThat(declared.result()).isEqualTo(rejected("revision contains DeclareOntology: 1"));
        assertThat(declared.effectiveChanges()).isEmpty();
        assertThat(declared.revision()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(4));
        assertThat(session.log.get(session.projectId, RevisionNumber.of(5))).isEmpty();
        assertThat(session.runtime.ontologies().keys()).containsExactly(ROOT);

        ChangeApplicationResult<RevertResult> missing = session.revert(9);

        assertThat(missing.result()).isEqualTo(rejected("revision does not exist: 9"));
        assertThat(missing.revision()).isEmpty();
        assertThat(session.runtime.head()).isEqualTo(RevisionNumber.of(4));
        assertThat(session.runtime.ontologies().containsAxiom(ROOT, AXIOM_B)).isFalse();
    }

    @Test
    void rejects_a_blank_or_contradictory_result() {
        assertThatThrownBy(() -> new RevertResult(false, Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RevertResult(false, Optional.of("  ")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RevertResult(true, Optional.of("revision does not exist: 1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RevertResult(false, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_null_constructor_arguments_and_a_null_context() {
        InMemoryRevisionLog log = new InMemoryRevisionLog();
        RevisionNumber target = RevisionNumber.of(1);
        assertThatThrownBy(() -> new RevertRevisionGenerator(null, target, log))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RevertRevisionGenerator(ProjectId.random(), null, log))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RevertRevisionGenerator(ProjectId.random(), target, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RevertRevisionGenerator(ProjectId.random(), target, log).generate(null))
                .isInstanceOf(NullPointerException.class);
        RevertRevisionGenerator generator = new RevertRevisionGenerator(ProjectId.random(), target, log);
        GeneratedChanges<RevertResult> generated =
                new GeneratedChanges<>(List.of(), rejected("revision does not exist: 1"));
        assertThatThrownBy(() -> generator.describe(null, List.of())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> generator.describe(generated, null)).isInstanceOf(NullPointerException.class);
    }

    private static RevertResult rejected(String reason) {
        return new RevertResult(false, Optional.of(reason));
    }

    private static DeclareOntology declare() {
        return new DeclareOntology(ROOT, Optional.empty(), Optional.empty());
    }

    private static ChangeContext context() {
        return new ChangeContext(USER, OwlApiProjectOntologies.empty());
    }

    private static RevertRevisionGenerator generator(StoredLog stored, long target) {
        return new RevertRevisionGenerator(stored.projectId(), RevisionNumber.of(target), stored.log());
    }

    private static StoredLog store(Revision target) {
        InMemoryRevisionLog log = new InMemoryRevisionLog();
        ProjectId projectId = ProjectId.random();
        RevisionNumber head = RevisionNumber.ZERO;
        for (long number = 1; number < target.number().value(); number++) {
            Revision filler = revision(number, List.of(new AddAxiom(ROOT, AXIOM_B)));
            log.append(projectId, head, filler);
            head = filler.number();
        }
        log.append(projectId, head, target);
        return new StoredLog(projectId, log);
    }

    private static Revision revision(long number, List<OntologyChange> changes) {
        return new Revision(RevisionNumber.of(number), USER, TIMESTAMP, "Edited", changes);
    }

    private record StoredLog(ProjectId projectId, InMemoryRevisionLog log) {}

    private static final class Session {
        private final ProjectId projectId;
        private final InMemoryRevisionLog log;
        private final ProjectRuntime runtime;
        private final ChangePipeline pipeline;

        private Session(ProjectId projectId, InMemoryRevisionLog log, ProjectRuntime runtime, ChangePipeline pipeline) {
            this.projectId = projectId;
            this.log = log;
            this.runtime = runtime;
            this.pipeline = pipeline;
        }

        private static Session open() {
            ProjectId projectId = ProjectId.random();
            InMemoryRevisionLog log = new InMemoryRevisionLog();
            ProjectRuntime runtime = new ProjectRuntimeLoader(
                            log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), List.of())
                    .load(projectId);
            ChangePipeline pipeline =
                    new ChangePipeline(runtime, new ProjectWriteGate(), log, FreshIriMinter.NONE, List.of(), CLOCK);
            return new Session(projectId, log, runtime, pipeline);
        }

        private void apply(List<OntologyChange> changes, String description) {
            pipeline.apply(USER, new ChangeListGenerator<String>() {
                @Override
                public GeneratedChanges<String> generate(ChangeContext context) {
                    return new GeneratedChanges<>(changes, "ok");
                }

                @Override
                public String describe(GeneratedChanges<String> generated, List<OntologyChange> effective) {
                    return description;
                }
            });
        }

        private ChangeApplicationResult<RevertResult> revert(long target) {
            return pipeline.apply(USER, new RevertRevisionGenerator(projectId, RevisionNumber.of(target), log));
        }
    }
}
