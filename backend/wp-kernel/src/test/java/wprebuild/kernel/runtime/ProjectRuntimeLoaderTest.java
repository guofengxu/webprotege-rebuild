package wprebuild.kernel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
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
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.SnapshotStore;

/**
 * Loads a project from the revision log, and from a snapshot plus the tail.
 *
 * <p>The 100 revisions use seed {@code 20261103L}. Changes written to the log are already
 * effective, so the loader must apply them directly.
 */
class ProjectRuntimeLoaderTest {

    private static final long SEED = 20261103L;

    private static final int REVISIONS = 100;

    private static final int SNAPSHOT_AT = 50;

    private static final OntologyKey FIRST = OntologyKey.of(1);

    private static final OntologyKey SECOND = OntologyKey.of(2);

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final IRI IMPORT = IRI.create("http://example.org/runtime/imported");

    private static final OWLAnnotation NOTE =
            DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("runtime"));

    private static final List<OWLAxiom> AXIOMS = axioms();

    @Test
    void empty_project_loads_at_revision_zero_and_rebuilds_once() {
        ProjectId projectId = ProjectId.random();
        ProjectRuntimeLoader loader = loader(
                new InMemoryRevisionLog(), new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), CountingView::new);

        ProjectRuntime loaded = loader.load(projectId);

        assertThat(loaded.head()).isEqualTo(RevisionNumber.ZERO);
        assertThat(loaded.ontologies()).isNotInstanceOf(MutableProjectOntologies.class);
        assertThat(loaded.ontologies().keys()).isEmpty();
        assertThat(loaded.ontologies().axiomCount()).isZero();
        CountingView view = loaded.view(CountingView.class);
        assertThat(view.rebuilds).isEqualTo(1);
        assertThat(view.applied).isZero();
        assertThat(loaded.view(CountingView.class)).isSameAs(view);
        assertThatThrownBy(() -> loader.load(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void load_without_a_snapshot_matches_applying_every_revision() {
        ProjectId projectId = ProjectId.random();
        RecordingRevisionLog log = new RecordingRevisionLog(new InMemoryRevisionLog());
        CountingCodec codec = new CountingCodec(new OfnZipSnapshotCodec());
        List<Revision> revisions = revisions();
        append(log, projectId, revisions);
        MutableProjectOntologies oracle = applyAll(revisions);

        ProjectRuntime loaded = loader(log, new InMemorySnapshotStore(), codec, CountingView::new)
                .load(projectId);

        assertThat(oracle.keys()).contains(FIRST, SECOND);
        assertThat(oracle.axiomCount()).isPositive();
        assertThat(loaded.head()).isEqualTo(RevisionNumber.of(REVISIONS));
        assertSameStructure(oracle, loaded.ontologies());
        CountingView view = loaded.view(CountingView.class);
        assertThat(view.rebuilds).isEqualTo(1);
        assertThat(view.applied).isZero();
        assertThat(codec.decodes).isZero();
        assertThat(log.ranges()).containsExactly(new Range(RevisionNumber.of(1), RevisionNumber.of(REVISIONS)));
        assertThatThrownBy(() -> loaded.view(RecordingView.class)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshot_at_revision_50_then_load_matches_a_full_replay() {
        ProjectId projectId = ProjectId.random();
        RecordingRevisionLog log = new RecordingRevisionLog(new InMemoryRevisionLog());
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        CountingCodec codec = new CountingCodec(new OfnZipSnapshotCodec());
        List<Revision> revisions = revisions();
        ProjectRuntimeLoader loader = loader(log, store, codec, CountingView::new);

        append(log, projectId, revisions.subList(0, SNAPSHOT_AT));
        ProjectRuntime atSnapshot = loader.load(projectId);
        assertThat(atSnapshot.head()).isEqualTo(RevisionNumber.of(SNAPSHOT_AT));
        assertThat(atSnapshot.view(CountingView.class).rebuilds).isEqualTo(1);
        ProjectSnapshotter.snapshot(atSnapshot, store, codec);

        ProjectRuntime atHeadSnapshot = loader.load(projectId);
        assertSameStructure(applyAll(revisions.subList(0, SNAPSHOT_AT)), atHeadSnapshot.ontologies());
        assertThat(atHeadSnapshot.view(CountingView.class).rebuilds).isEqualTo(1);
        assertThat(atHeadSnapshot.view(CountingView.class).applied).isZero();
        assertThat(codec.decodes).isEqualTo(1);
        assertThat(store.latest(projectId)).hasValueSatisfying(snapshot -> {
            assertThat(snapshot.revision()).isEqualTo(RevisionNumber.of(SNAPSHOT_AT));
            assertThat(snapshot.codecId()).isEqualTo(OfnZipSnapshotCodec.ID);
            assertThat(snapshot.payload()).isNotEmpty();
        });

        append(log, projectId, revisions.subList(SNAPSHOT_AT, REVISIONS));
        ProjectRuntime loaded = loader.load(projectId);

        assertThat(loaded.head()).isEqualTo(RevisionNumber.of(REVISIONS));
        assertSameStructure(applyAll(revisions), loaded.ontologies());
        assertThat(loaded.view(CountingView.class).rebuilds).isEqualTo(1);
        assertThat(loaded.view(CountingView.class).applied).isZero();
        assertThat(codec.decodes).isEqualTo(2);
        assertThat(log.ranges())
                .containsExactly(
                        new Range(RevisionNumber.of(1), RevisionNumber.of(SNAPSHOT_AT)),
                        new Range(RevisionNumber.of(SNAPSHOT_AT + 1), RevisionNumber.of(REVISIONS)));
    }

    @Test
    void rejects_a_snapshot_whose_codec_id_does_not_match() {
        ProjectId projectId = ProjectId.random();
        InMemoryRevisionLog log = new InMemoryRevisionLog();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        append(log, projectId, List.of(revisions().getFirst()));
        store.save(projectId, RevisionNumber.of(1), "binaryowl/0", new byte[] {1});
        ProjectRuntimeLoader loader = loader(log, store, new OfnZipSnapshotCodec(), CountingView::new);

        assertThatThrownBy(() -> loader.load(projectId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("binaryowl/0")
                .hasMessageContaining(OfnZipSnapshotCodec.ID);
    }

    @Test
    void rejects_a_snapshot_newer_than_the_head() {
        ProjectId projectId = ProjectId.random();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        store.save(projectId, RevisionNumber.of(4), OfnZipSnapshotCodec.ID, new byte[] {1});
        ProjectRuntimeLoader loader =
                loader(new InMemoryRevisionLog(), store, new OfnZipSnapshotCodec(), CountingView::new);

        assertThatThrownBy(() -> loader.load(projectId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("after head");
    }

    @Test
    void rejects_a_log_that_skips_a_revision() {
        ProjectId projectId = ProjectId.random();
        RevisionLog log =
                new StubRevisionLog(RevisionNumber.of(2), List.of(revisions().getFirst()), false);
        ProjectRuntimeLoader loader =
                loader(log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), CountingView::new);

        assertThatThrownBy(() -> loader.load(projectId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing revision 2");
    }

    @Test
    void rejects_a_log_that_returns_a_revision_beyond_the_head() {
        ProjectId projectId = ProjectId.random();
        List<Revision> revisions = revisions();
        RevisionLog log =
                new StubRevisionLog(RevisionNumber.of(1), List.of(revisions.getFirst(), revisions.get(1)), true);
        ProjectRuntimeLoader loader =
                loader(log, new InMemorySnapshotStore(), new OfnZipSnapshotCodec(), CountingView::new);

        assertThatThrownBy(() -> loader.load(projectId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("beyond head");
    }

    @Test
    void replays_the_revision_at_long_max_value() {
        ProjectId projectId = ProjectId.random();
        RevisionNumber max = RevisionNumber.of(Long.MAX_VALUE);
        Revision tail = new Revision(
                max,
                UserId.SYSTEM,
                Instant.EPOCH,
                "tail",
                List.of(new DeclareOntology(FIRST, Optional.empty(), Optional.empty())));
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        store.save(
                projectId,
                RevisionNumber.of(Long.MAX_VALUE - 1),
                OfnZipSnapshotCodec.ID,
                new OfnZipSnapshotCodec().encode(OwlApiProjectOntologies.empty()));
        RevisionLog log = new StubRevisionLog(max, List.of(tail), false);

        ProjectRuntime loaded =
                loader(log, store, new OfnZipSnapshotCodec(), CountingView::new).load(projectId);

        assertThat(loaded.head()).isEqualTo(max);
        assertThat(loaded.ontologies().keys()).containsExactly(FIRST);
        assertThat(loaded.view(CountingView.class).rebuilds).isEqualTo(1);
    }

    @Test
    void apply_committed_advances_head_and_notifies_views_in_supplier_order() {
        ProjectId projectId = ProjectId.random();
        List<String> events = new ArrayList<>();
        ProjectRuntime runtime = new ProjectRuntimeLoader(
                        new InMemoryRevisionLog(),
                        new InMemorySnapshotStore(),
                        new OfnZipSnapshotCodec(),
                        List.of(() -> new RecordingView("first", events), () -> new RecordingView("second", events)))
                .load(projectId);
        List<Revision> revisions = revisions();
        assertThat(events).containsExactly("first:rebuild", "second:rebuild");
        assertThat(runtime.head().isZero()).isTrue();

        events.clear();
        assertThatThrownBy(() -> runtime.applyCommitted(revisions.get(1))).isInstanceOf(IllegalArgumentException.class);
        assertThat(runtime.head().isZero()).isTrue();
        assertThat(runtime.ontologies().keys()).isEmpty();
        assertThat(events).isEmpty();
        assertThatThrownBy(() -> runtime.applyCommitted(null)).isInstanceOf(NullPointerException.class);

        runtime.applyCommitted(revisions.getFirst());
        assertThat(runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(events).containsExactly("first:applied", "second:applied");
        assertSameStructure(applyAll(List.of(revisions.getFirst())), runtime.ontologies());

        events.clear();
        runtime.applyCommitted(revisions.get(1));
        assertThat(runtime.head()).isEqualTo(RevisionNumber.of(2));
        assertThat(events).containsExactly("first:applied", "second:applied");
        assertSameStructure(applyAll(revisions.subList(0, 2)), runtime.ontologies());
    }

    private static ProjectRuntimeLoader loader(
            RevisionLog log, SnapshotStore store, SnapshotCodec codec, Supplier<DerivedView> view) {
        return new ProjectRuntimeLoader(log, store, codec, List.of(view));
    }

    private static void append(RevisionLog log, ProjectId projectId, List<Revision> revisions) {
        for (Revision revision : revisions) {
            log.append(projectId, RevisionNumber.of(revision.number().value() - 1), revision);
        }
    }

    private static MutableProjectOntologies applyAll(List<Revision> revisions) {
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        for (Revision revision : revisions) {
            ontologies.apply(revision.changes());
        }
        return ontologies;
    }

    private static void assertSameStructure(ProjectOntologies expected, ProjectOntologies actual) {
        assertThat(actual.axiomCount()).isEqualTo(expected.axiomCount());
        assertThat(actual.keys()).containsExactlyInAnyOrderElementsOf(expected.keys());
        for (OntologyKey key : expected.keys()) {
            assertThat(actual.info(key)).as("info %s", key.value()).isEqualTo(expected.info(key));
            assertThat(actual.axioms(key))
                    .as("axioms %s", key.value())
                    .containsExactlyInAnyOrderElementsOf(expected.axioms(key).toList());
            assertThat(actual.annotations(key))
                    .as("annotations %s", key.value())
                    .isEqualTo(expected.annotations(key));
            assertThat(actual.imports(key)).as("imports %s", key.value()).isEqualTo(expected.imports(key));
        }
    }

    /** Seed {@code 20261103L}. Revision 60 registers the second ontology, after the snapshot. */
    private static List<Revision> revisions() {
        Random random = new Random(SEED);
        Map<OntologyKey, Presence> present = new HashMap<>();
        List<OntologyKey> active = new ArrayList<>();
        List<Revision> revisions = new ArrayList<>();
        for (int number = 1; number <= REVISIONS; number++) {
            List<OntologyChange> changes = new ArrayList<>();
            if (number == 1) {
                changes.add(new DeclareOntology(
                        FIRST,
                        Optional.of(IRI.create("http://example.org/runtime")),
                        Optional.of(IRI.create("http://example.org/runtime/1"))));
                active.add(FIRST);
                present.put(FIRST, new Presence());
            }
            if (number == 60) {
                changes.add(new DeclareOntology(SECOND, Optional.empty(), Optional.empty()));
                active.add(SECOND);
                present.put(SECOND, new Presence());
            }
            int edits = 1 + random.nextInt(3);
            for (int edit = 0; edit < edits; edit++) {
                OntologyKey key = active.get(random.nextInt(active.size()));
                changes.add(edit(random, key, present.get(key)));
            }
            revisions.add(new Revision(
                    RevisionNumber.of(number),
                    UserId.SYSTEM,
                    Instant.ofEpochMilli(number),
                    "revision " + number,
                    changes));
        }
        return List.copyOf(revisions);
    }

    private static OntologyChange edit(Random random, OntologyKey key, Presence presence) {
        int roll = random.nextInt(10);
        if (roll == 0) {
            presence.annotated = !presence.annotated;
            return presence.annotated ? new AddOntologyAnnotation(key, NOTE) : new RemoveOntologyAnnotation(key, NOTE);
        }
        if (roll == 1) {
            presence.imported = !presence.imported;
            return presence.imported ? new AddImport(key, IMPORT) : new RemoveImport(key, IMPORT);
        }
        OWLAxiom axiom = AXIOMS.get(random.nextInt(AXIOMS.size()));
        if (presence.axioms.add(axiom)) {
            return new AddAxiom(key, axiom);
        }
        presence.axioms.remove(axiom);
        return new RemoveAxiom(key, axiom);
    }

    private static List<OWLAxiom> axioms() {
        List<OWLClass> classes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            classes.add(DATA.getOWLClass(IRI.create("http://example.org/runtime/C" + index)));
        }
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int left = 0; left < classes.size(); left++) {
            for (int right = left + 1; right < classes.size(); right++) {
                axioms.add(DATA.getOWLSubClassOfAxiom(classes.get(left), classes.get(right)));
            }
        }
        return List.copyOf(axioms);
    }

    private static final class Presence {
        private final Set<OWLAxiom> axioms = new HashSet<>();
        private boolean annotated;
        private boolean imported;
    }

    private record Range(RevisionNumber from, RevisionNumber to) {}

    private static final class CountingView implements DerivedView {
        private int rebuilds;
        private int applied;

        @Override
        public void rebuild(ProjectOntologies ontologies) {
            rebuilds++;
        }

        @Override
        public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
            applied++;
        }
    }

    private static final class RecordingView implements DerivedView {
        private final String name;
        private final List<String> events;

        private RecordingView(String name, List<String> events) {
            this.name = name;
            this.events = events;
        }

        @Override
        public void rebuild(ProjectOntologies ontologies) {
            events.add(name + ":rebuild");
        }

        @Override
        public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
            events.add(name + ":applied");
        }
    }

    private static final class CountingCodec implements SnapshotCodec {
        private final SnapshotCodec delegate;
        private int decodes;

        private CountingCodec(SnapshotCodec delegate) {
            this.delegate = delegate;
        }

        @Override
        public String id() {
            return delegate.id();
        }

        @Override
        public byte[] encode(ProjectOntologies ontologies) {
            return delegate.encode(ontologies);
        }

        @Override
        public MutableProjectOntologies decode(byte[] payload) {
            decodes++;
            return delegate.decode(payload);
        }
    }

    /** A log whose head and returned revisions are fixed, including ranges a real log would not produce. */
    private static final class StubRevisionLog implements RevisionLog {
        private final RevisionNumber head;
        private final List<Revision> revisions;
        private final boolean ignoreUpperBound;

        private StubRevisionLog(RevisionNumber head, List<Revision> revisions, boolean ignoreUpperBound) {
            this.head = head;
            this.revisions = revisions;
            this.ignoreUpperBound = ignoreUpperBound;
        }

        @Override
        public RevisionNumber head(ProjectId projectId) {
            return head;
        }

        @Override
        public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
            return revisions.stream()
                    .filter(revision -> revision.number().equals(number))
                    .findFirst();
        }

        @Override
        public Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
            return revisions.stream()
                    .filter(revision -> revision.number().compareTo(fromInclusive) >= 0
                            && (ignoreUpperBound || revision.number().compareTo(toInclusive) <= 0));
        }
    }

    private static final class RecordingRevisionLog implements RevisionLog {
        private final RevisionLog delegate;
        private final List<Range> ranges = new ArrayList<>();

        private RecordingRevisionLog(RevisionLog delegate) {
            this.delegate = delegate;
        }

        private List<Range> ranges() {
            return List.copyOf(ranges);
        }

        @Override
        public RevisionNumber head(ProjectId projectId) {
            return delegate.head(projectId);
        }

        @Override
        public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
            delegate.append(projectId, expectedHead, revision);
        }

        @Override
        public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
            return delegate.get(projectId, number);
        }

        @Override
        public Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
            ranges.add(new Range(fromInclusive, toInclusive));
            return delegate.read(projectId, fromInclusive, toInclusive);
        }
    }
}
