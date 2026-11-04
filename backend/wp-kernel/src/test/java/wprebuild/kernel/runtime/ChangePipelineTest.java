package wprebuild.kernel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatObject;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAxiom;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/**
 * Write pipeline: persist the revision, then update memory, then notify listeners (write-path.md).
 */
@Timeout(10)
class ChangePipelineTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-11-04T00:00:00Z");

    private static final Clock CLOCK = Clock.fixed(TIMESTAMP, ZoneOffset.UTC);

    private static final UserId USER = new UserId("editor");

    private static final OntologyKey ROOT = OntologyKey.of(1);

    private static final IRI PERSON = IRI.create("http://example.org/wp/Person");

    @Test
    void commits_the_revision_then_updates_memory_and_notifies_listeners() {
        Harness harness = Harness.create();
        OWLAxiom declaration = declaration(harness);
        RecordingListener listener = new RecordingListener();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of(listener));

        ChangeApplicationResult<String> first = pipeline.apply(
                USER, generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "ok", "Added a class", false));

        assertThat(first.result()).isEqualTo("ok");
        assertThat(first.renameMap()).isEmpty();
        assertThat(first.effectiveChanges()).containsExactly(declare(), new AddAxiom(ROOT, declaration));
        assertThatThrownBy(() -> first.effectiveChanges().clear()).isInstanceOf(UnsupportedOperationException.class);
        Revision revision = first.revision().orElseThrow();
        assertThat(revision.number()).isEqualTo(RevisionNumber.of(1));
        assertThat(revision.user()).isEqualTo(USER);
        assertThat(revision.timestamp()).isEqualTo(TIMESTAMP);
        assertThat(revision.description()).isEqualTo("Added a class");
        assertThat(revision.changes()).containsExactlyElementsOf(first.effectiveChanges());
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(1))).contains(revision);
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.runtime.ontologies().containsAxiom(ROOT, declaration))
                .isTrue();
        assertThat(harness.view.applied).containsExactly(first.effectiveChanges());
        assertThat(listener.calls).hasSize(1);
        assertThat(listener.calls.getFirst().runtime()).isSameAs(harness.runtime);
        assertThat(listener.calls.getFirst().revision()).isEqualTo(revision);
        assertThat(listener.calls.getFirst().silent()).isFalse();
        assertThat(harness.runtime.isCorrupted()).isFalse();

        OWLAxiom secondAxiom = harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Agent")));
        ChangeApplicationResult<String> second =
                pipeline.apply(USER, generator(List.of(new AddAxiom(ROOT, secondAxiom)), "next", "Added Agent", false));

        assertThat(second.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.log.head(harness.projectId)).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.runtime.ontologies().containsAxiom(ROOT, secondAxiom))
                .isTrue();
        assertThat(listener.calls).hasSize(2);
    }

    @Test
    void wp3_failed_append_leaves_memory_head_and_views_unchanged() {
        Harness harness = Harness.create();
        IllegalStateException failure = new IllegalStateException("append failed");
        AtomicInteger attempts = new AtomicInteger();
        RevisionLog log = new ForwardingRevisionLog(harness.log) {
            @Override
            public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
                if (attempts.getAndIncrement() == 0) {
                    throw failure;
                }
                delegate.append(projectId, expectedHead, revision);
            }
        };
        RecordingListener listener = new RecordingListener();
        ChangePipeline pipeline = harness.pipeline(log, FreshIriMinter.NONE, List.of(listener));
        OWLAxiom declaration = declaration(harness);
        ChangeListGenerator<String> generator =
                generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "ok", "Added a class", false);

        assertThatThrownBy(() -> pipeline.apply(USER, generator)).isSameAs(failure);

        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.ZERO);
        assertThat(harness.log.head(harness.projectId)).isEqualTo(RevisionNumber.ZERO);
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(1))).isEmpty();
        assertThat(harness.runtime.ontologies().keys()).isEmpty();
        assertThat(harness.view.applied).isEmpty();
        assertThat(listener.calls).isEmpty();
        assertThat(harness.runtime.isCorrupted()).isFalse();

        ChangeApplicationResult<String> retried = pipeline.apply(USER, generator);

        assertThat(retried.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.runtime.ontologies().containsAxiom(ROOT, declaration))
                .isTrue();
        assertThat(harness.view.applied).hasSize(1);
        assertThat(listener.calls).hasSize(1);
    }

    @Test
    void wp4_empty_effective_changes_skip_the_revision_and_the_listeners() {
        Harness harness = Harness.create();
        RecordingListener listener = new RecordingListener();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of(listener));

        ChangeApplicationResult<String> empty = pipeline.apply(USER, generator(List.of(), "nothing", "unused", false));

        assertThat(empty.result()).isEqualTo("nothing");
        assertThat(empty.effectiveChanges()).isEmpty();
        assertThat(empty.revision()).isEmpty();
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.ZERO);
        assertThat(harness.log.head(harness.projectId)).isEqualTo(RevisionNumber.ZERO);
        assertThat(listener.calls).isEmpty();
        assertThat(harness.view.applied).isEmpty();

        OWLAxiom declaration = declaration(harness);
        pipeline.apply(
                USER, generator(List.of(declare(), new AddAxiom(ROOT, declaration)), "ok", "Added a class", false));
        ChangeApplicationResult<String> redundant =
                pipeline.apply(USER, generator(List.of(new AddAxiom(ROOT, declaration)), "same", "unused", true));

        assertThat(redundant.revision()).isEmpty();
        assertThat(redundant.effectiveChanges()).isEmpty();
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(2))).isEmpty();
        assertThat(listener.calls).hasSize(1);

        OWLAxiom agent = harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(harness.runtime
                        .ontologies()
                        .dataFactory()
                        .getOWLClass(IRI.create("http://example.org/wp/Agent")));
        ChangeApplicationResult<String> next =
                pipeline.apply(USER, generator(List.of(new AddAxiom(ROOT, agent)), "agent", "Added Agent", false));

        assertThat(next.revision().orElseThrow().number()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.log.head(harness.projectId)).isEqualTo(RevisionNumber.of(2));
    }

    @Test
    void wp6_a_failing_listener_does_not_roll_back_or_skip_the_next_listener() {
        Harness harness = Harness.create();
        RecordingListener second = new RecordingListener();
        ChangePipeline pipeline = harness.pipeline(
                FreshIriMinter.NONE,
                List.of(
                        (runtime, revision, silent) -> {
                            throw new IllegalStateException("listener failed");
                        },
                        second));

        ChangeApplicationResult<String> applied =
                pipeline.apply(USER, generator(List.of(declare()), "ok", "Declared", true));

        Revision revision = applied.revision().orElseThrow();
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(1))).contains(revision);
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.runtime.ontologies().keys()).containsExactly(ROOT);
        assertThat(harness.runtime.isCorrupted()).isFalse();
        assertThat(second.calls).hasSize(1);
        assertThat(second.calls.getFirst().revision()).isEqualTo(revision);
        assertThat(second.calls.getFirst().silent()).isTrue();

        ChangeApplicationResult<String> again = pipeline.apply(USER, generator(List.of(), "noop", "unused", false));
        assertThat(again.revision()).isEmpty();
        assertThat(harness.runtime.isCorrupted()).isFalse();
    }

    @Test
    void wp8_a_view_failure_during_apply_marks_the_runtime_corrupted() {
        Harness harness = Harness.create();
        IllegalStateException failure = new IllegalStateException("view failed");
        AtomicInteger generated = new AtomicInteger();
        harness.view.boom = failure;
        harness.view.beforeThrow = () -> {
            if (harness.log.get(harness.projectId, RevisionNumber.of(1)).isEmpty()) {
                throw new IllegalStateException("revision was not persisted before memory apply");
            }
        };
        RecordingListener listener = new RecordingListener();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of(listener));
        ChangeListGenerator<String> generator = context -> {
            generated.incrementAndGet();
            return new GeneratedChanges<>(List.of(declare()), "ok");
        };

        assertThatThrownBy(() -> pipeline.apply(USER, generator)).isSameAs(failure);

        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(1))).isPresent();
        assertThat(harness.runtime.isCorrupted()).isTrue();
        assertThat(listener.calls).isEmpty();
        assertThat(generated).hasValue(1);

        assertThatThrownBy(() -> pipeline.apply(USER, generator))
                .isInstanceOf(ProjectRuntimeCorruptedException.class)
                .hasMessageContaining(harness.projectId.toString());
        assertThat(generated).hasValue(1);
        assertThat(harness.log.get(harness.projectId, RevisionNumber.of(2))).isEmpty();
        assertThat(listener.calls).isEmpty();
    }

    @Test
    void append_does_not_hold_the_write_lock() throws Exception {
        Harness harness = Harness.create();
        CountDownLatch appendEntered = new CountDownLatch(1);
        CountDownLatch readerEntered = new CountDownLatch(1);
        RevisionLog log = new ForwardingRevisionLog(harness.log) {
            @Override
            public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
                appendEntered.countDown();
                await(readerEntered);
                delegate.append(projectId, expectedHead, revision);
            }
        };
        ChangePipeline pipeline = harness.pipeline(log, FreshIriMinter.NONE, List.of());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = start("reader", failure, () -> {
            await(appendEntered);
            harness.gate.read(() -> {
                readerEntered.countDown();
                return null;
            });
        });

        pipeline.apply(USER, generator(List.of(declare()), "ok", "Declared", false));

        join(reader, failure);
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void listeners_run_before_the_generation_lock_is_released() throws Exception {
        Harness harness = Harness.create();
        CountDownLatch inListener = new CountDownLatch(1);
        AtomicInteger otherPhase = new AtomicInteger();
        AtomicReference<Thread> other = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of((runtime, revision, silent) -> {
            inListener.countDown();
            awaitBlockedBeforeCritical(other.get(), otherPhase);
        }));
        Thread second = start("second-apply", failure, () -> {
            await(inListener);
            otherPhase.set(1);
            pipeline.apply(USER, context -> {
                otherPhase.set(2);
                return new GeneratedChanges<>(List.of(), "late");
            });
        });
        other.set(second);

        pipeline.apply(USER, generator(List.of(declare()), "ok", "Declared", false));

        join(second, failure);
        assertThat(otherPhase).hasValue(2);
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void listeners_run_after_the_write_lock_is_released() throws Exception {
        Harness harness = Harness.create();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch readerEntered = new CountDownLatch(1);
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of((runtime, revision, silent) -> {
            listenerEntered.countDown();
            await(readerEntered);
        }));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = start("reader-during-listener", failure, () -> {
            await(listenerEntered);
            harness.gate.read(() -> {
                readerEntered.countDown();
                return null;
            });
        });

        pipeline.apply(USER, generator(List.of(declare()), "ok", "Declared", false));

        join(reader, failure);
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    void minted_changes_are_filtered_and_the_result_is_renamed() {
        Harness harness = Harness.create();
        IRI fresh = IRI.create("urn:fresh");
        IRI minted = IRI.create("urn:minted");
        DeclareOntology declared = declare();
        AtomicReference<List<OntologyChange>> describedGenerated = new AtomicReference<>();
        AtomicReference<List<OntologyChange>> describedEffective = new AtomicReference<>();
        FreshIriMinter minter =
                (user, changes, ontologies) -> new FreshIriMinter.MintResult(List.of(declared), Map.of(fresh, minted));
        ChangeListGenerator<IRI> generator = new ChangeListGenerator<>() {
            @Override
            public GeneratedChanges<IRI> generate(ChangeContext context) {
                return new GeneratedChanges<>(List.of(), fresh);
            }

            @Override
            public String describe(GeneratedChanges<IRI> generated, List<OntologyChange> effective) {
                describedGenerated.set(generated.changes());
                describedEffective.set(effective);
                return "Minted";
            }

            @Override
            public IRI renameResult(IRI result, Map<IRI, IRI> renameMap) {
                return renameMap.getOrDefault(result, result);
            }
        };
        ChangePipeline pipeline = harness.pipeline(minter, List.of());

        ChangeApplicationResult<IRI> applied = pipeline.apply(USER, generator);

        assertThatObject(applied.result()).isEqualTo(minted);
        assertThat(applied.renameMap()).containsExactly(entry(fresh, minted));
        assertThat(applied.effectiveChanges()).containsExactly(declared);
        assertThat(describedGenerated.get()).isEmpty();
        assertThat(describedEffective.get()).containsExactly(declared);
        assertThat(applied.revision().orElseThrow().description()).isEqualTo("Minted");
        assertThat(applied.revision().orElseThrow().changes()).containsExactly(declared);
        assertThat(harness.runtime.ontologies().keys()).containsExactly(ROOT);
    }

    @Test
    void default_description_counts_effective_changes() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of());

        ChangeApplicationResult<String> applied =
                pipeline.apply(USER, context -> new GeneratedChanges<>(List.of(declare()), "ok"));

        assertThat(applied.revision().orElseThrow().description()).isEqualTo("Edited 1 axioms");
    }

    @Test
    void a_change_for_an_unknown_ontology_fails_before_append_or_corruption() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of());
        OWLAxiom axiom = declaration(harness);

        assertThatThrownBy(() ->
                        pipeline.apply(USER, generator(List.of(new AddAxiom(ROOT, axiom)), "bad", "unused", false)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(harness.runtime.isCorrupted()).isFalse();
        assertThat(harness.runtime.head()).isEqualTo(RevisionNumber.ZERO);
        assertThat(harness.log.head(harness.projectId)).isEqualTo(RevisionNumber.ZERO);
    }

    @Test
    void rejects_null_arguments() {
        Harness harness = Harness.create();
        ChangePipeline pipeline = harness.pipeline(FreshIriMinter.NONE, List.of());

        assertThatThrownBy(() ->
                        new ChangePipeline(null, harness.gate, harness.log, FreshIriMinter.NONE, List.of(), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new ChangePipeline(harness.runtime, null, harness.log, FreshIriMinter.NONE, List.of(), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() ->
                        new ChangePipeline(harness.runtime, harness.gate, null, FreshIriMinter.NONE, List.of(), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangePipeline(harness.runtime, harness.gate, harness.log, null, List.of(), CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangePipeline(
                        harness.runtime, harness.gate, harness.log, FreshIriMinter.NONE, null, CLOCK))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangePipeline(
                        harness.runtime, harness.gate, harness.log, FreshIriMinter.NONE, List.of(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pipeline.apply(null, context -> new GeneratedChanges<>(List.of(), "ok")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> pipeline.apply(USER, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ChangeContext(null, harness.runtime.ontologies()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GeneratedChanges<String>(null, "ok")).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new FreshIriMinter.MintResult(null, Map.of()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ProjectRuntimeCorruptedException(null)).isInstanceOf(NullPointerException.class);
        Revision stored = new Revision(RevisionNumber.of(1), USER, TIMESTAMP, "Declared", List.of(declare()));
        assertThatThrownBy(() -> new ChangeApplicationResult<>("ok", List.of(declare()), Map.of(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ChangeApplicationResult<>("ok", List.of(), Map.of(), Optional.of(stored)))
                .isInstanceOf(IllegalArgumentException.class);
        Revision mismatched = new Revision(RevisionNumber.of(1), USER, TIMESTAMP, "Other", List.of());
        assertThatThrownBy(() ->
                        new ChangeApplicationResult<>("ok", List.of(declare()), Map.of(), Optional.of(mismatched)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DeclareOntology declare() {
        return new DeclareOntology(ROOT, Optional.empty(), Optional.empty());
    }

    private static OWLAxiom declaration(Harness harness) {
        return harness.runtime
                .ontologies()
                .dataFactory()
                .getOWLDeclarationAxiom(
                        harness.runtime.ontologies().dataFactory().getOWLClass(PERSON));
    }

    private static ChangeListGenerator<String> generator(
            List<OntologyChange> changes, String result, String description, boolean silent) {
        return new ChangeListGenerator<>() {
            @Override
            public GeneratedChanges<String> generate(ChangeContext context) {
                return new GeneratedChanges<>(changes, result);
            }

            @Override
            public String describe(GeneratedChanges<String> generated, List<OntologyChange> effective) {
                return description;
            }

            @Override
            public boolean silent() {
                return silent;
            }
        };
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, Runnable body) {
        return Thread.ofVirtual()
                .name(name)
                .uncaughtExceptionHandler((thread, error) -> failure.set(error))
                .start(body);
    }

    private static void join(Thread thread, AtomicReference<Throwable> failure) {
        try {
            assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining " + thread.getName(), ex);
        }
        if (failure.get() != null) {
            throw new AssertionError(thread.getName() + " failed", failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", ex);
        }
    }

    private static void awaitBlockedBeforeCritical(Thread thread, AtomicInteger phase) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            int seen = phase.get();
            if (seen >= 2) {
                throw new AssertionError("thread entered the critical section while another holder was active");
            }
            if (seen == 1 && isQueued(thread)) {
                return;
            }
            if (!thread.isAlive() && seen < 2) {
                throw new AssertionError("thread finished before the critical section; phase=" + seen);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "thread was not queued on the lock; state=" + thread.getState() + " phase=" + phase.get());
    }

    private static boolean isQueued(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED;
    }

    private static final class Harness {
        private final ProjectId projectId;
        private final ProjectRuntime runtime;
        private final ProjectWriteGate gate;
        private final InMemoryRevisionLog log;
        private final ExplodingView view;

        private Harness(
                ProjectId projectId,
                ProjectRuntime runtime,
                ProjectWriteGate gate,
                InMemoryRevisionLog log,
                ExplodingView view) {
            this.projectId = projectId;
            this.runtime = runtime;
            this.gate = gate;
            this.log = log;
            this.view = view;
        }

        private static Harness create() {
            ProjectId projectId = ProjectId.random();
            ExplodingView view = new ExplodingView();
            ProjectRuntime runtime =
                    new ProjectRuntime(projectId, OwlApiProjectOntologies.empty(), RevisionNumber.ZERO, List.of(view));
            return new Harness(projectId, runtime, new ProjectWriteGate(), new InMemoryRevisionLog(), view);
        }

        private ChangePipeline pipeline(FreshIriMinter minter, List<PostCommitListener> listeners) {
            return pipeline(log, minter, listeners);
        }

        private ChangePipeline pipeline(
                RevisionLog revisionLog, FreshIriMinter minter, List<PostCommitListener> listeners) {
            return new ChangePipeline(runtime, gate, revisionLog, minter, listeners, CLOCK);
        }
    }

    private static final class ExplodingView implements DerivedView {
        private final List<List<OntologyChange>> applied = new ArrayList<>();
        private RuntimeException boom;
        private Runnable beforeThrow;

        @Override
        public void rebuild(ProjectOntologies ontologies) {}

        @Override
        public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
            if (boom != null) {
                if (beforeThrow != null) {
                    beforeThrow.run();
                }
                throw boom;
            }
            applied.add(effectiveChanges);
        }
    }

    private record ListenerCall(ProjectRuntime runtime, Revision revision, boolean silent) {}

    private static final class RecordingListener implements PostCommitListener {
        private final List<ListenerCall> calls = new ArrayList<>();

        @Override
        public void onCommitted(ProjectRuntime runtime, Revision revision, boolean silent) {
            calls.add(new ListenerCall(runtime, revision, silent));
        }
    }

    private static class ForwardingRevisionLog implements RevisionLog {
        protected final RevisionLog delegate;

        private ForwardingRevisionLog(RevisionLog delegate) {
            this.delegate = delegate;
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
        public java.util.stream.Stream<Revision> read(
                ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
            return delegate.read(projectId, fromInclusive, toInclusive);
        }
    }
}
