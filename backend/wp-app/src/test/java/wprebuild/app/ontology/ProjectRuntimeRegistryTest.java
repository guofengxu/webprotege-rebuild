package wprebuild.app.ontology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.StoredSnapshot;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;
import wprebuild.kernel.runtime.ProjectRuntimeCorruptedException;
import wprebuild.kernel.shortform.DisplayNameView;

@DatabaseTest
@TestPropertySource(properties = "wp.runtime.snapshot-every=2")
class ProjectRuntimeRegistryTest {

    private static final UserId USER = UserId.SYSTEM;

    private static final Duration IDLE = Duration.ofMinutes(30);

    private final ProjectRuntimeRegistry registry;

    private final JdbcClient jdbc;

    private final ConfigurableApplicationContext context;

    private final RuntimeProperties properties;

    ProjectRuntimeRegistryTest(
            ProjectRuntimeRegistry registry,
            JdbcClient jdbc,
            ConfigurableApplicationContext context,
            RuntimeProperties properties) {
        this.registry = registry;
        this.jdbc = jdbc;
        this.context = context;
        this.properties = properties;
    }

    @Test
    void uses_default_idle_timeout_and_max_loaded() {
        assertThat(properties.idleTimeout()).isEqualTo(IDLE);
        assertThat(properties.maxLoaded()).isEqualTo(20);
        assertThat(properties.snapshotEvery()).isEqualTo(2);
    }

    @Test
    void rejects_non_positive_runtime_limits() {
        assertThatThrownBy(() -> new RuntimeProperties(null, 20, 500)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new RuntimeProperties(Duration.ZERO, 20, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeProperties(Duration.ofMinutes(-1), 20, 500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeProperties(IDLE, 0, 500)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RuntimeProperties(IDLE, 20, 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void get_returns_the_same_handle() {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectId projectId = ProjectId.random();

        ProjectHandle first = harness.registry.get(projectId);

        assertThat(harness.registry.get(projectId)).isSameAs(first);
        assertThat(harness.loads).hasValue(1);
        assertThat(harness.meters.get("wp.runtime.load").timer().count()).isEqualTo(1);
        assertThat(harness.meters.get("wp.runtime.load").timer().getId().getTags())
                .isEmpty();
        assertThat(harness.meters.get("wp.runtime.loaded").gauge().value()).isEqualTo(1.0);
        assertThat(harness.meters.get("wp.runtime.loaded").gauge().getId().getTags())
                .isEmpty();
    }

    @Test
    void concurrent_get_loads_the_project_once() throws Exception {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectId projectId = ProjectId.random();
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProjectRuntimeRegistry shared = ProjectRuntimeRegistry.withLoader(
                id -> {
                    loads.incrementAndGet();
                    entered.countDown();
                    await(release);
                    return harness.factory.create(id);
                },
                new RuntimeProperties(IDLE, 20, 500),
                harness.ticker,
                new SimpleMeterRegistry());
        CountDownLatch done = new CountDownLatch(10);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<ProjectHandle> handles = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < 10; i++) {
            Thread.ofVirtual()
                    .name("load-" + i)
                    .uncaughtExceptionHandler((thread, error) -> failure.set(error))
                    .start(() -> {
                        try {
                            await(start);
                            handles.add(shared.get(projectId));
                        } finally {
                            done.countDown();
                        }
                    });
        }

        start.countDown();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(Duration.ofMillis(50));
        release.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("loader thread failed", failure.get());
        }

        assertThat(loads).hasValue(1);
        assertThat(handles).hasSize(10);
        ProjectHandle first = handles.getFirst();
        assertThat(handles).allSatisfy(handle -> assertThat(handle).isSameAs(first));
    }

    @Test
    void evicts_the_runtime_after_the_idle_timeout() {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectId projectId = ProjectId.random();
        ProjectHandle first = harness.registry.get(projectId);
        first.pipeline().apply(USER, declare(1, false));

        harness.ticker.advance(IDLE.dividedBy(2));
        assertThat(harness.registry.get(projectId)).isSameAs(first);

        harness.ticker.advance(IDLE);
        ProjectHandle second = harness.registry.get(projectId);

        assertThat(second).isNotSameAs(first);
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(1));
        assertThat(second.runtime().isCorrupted()).isFalse();
        assertThatThrownBy(() -> first.pipeline().apply(USER, declare(2, false)))
                .isInstanceOf(ProjectRuntimeCorruptedException.class);
        second.pipeline().apply(USER, declare(2, false));
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.loads).hasValue(2);
        assertThat(harness.meters.get("wp.runtime.load").timer().count()).isEqualTo(2);
        assertThat(harness.meters.get("wp.runtime.loaded").gauge().value()).isEqualTo(1.0);
    }

    @Test
    void evict_drops_the_cached_handle() {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectId projectId = ProjectId.random();
        ProjectHandle first = harness.registry.get(projectId);

        harness.registry.evict(projectId);

        assertThat(harness.meters.get("wp.runtime.loaded").gauge().value()).isEqualTo(0.0);
        assertThatThrownBy(() -> first.pipeline().apply(USER, declare(1, false)))
                .isInstanceOf(ProjectRuntimeCorruptedException.class);
        ProjectHandle second = harness.registry.get(projectId);
        assertThat(second).isNotSameAs(first);
        second.pipeline().apply(USER, declare(1, false));
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(1));
        assertThat(harness.loads).hasValue(2);
    }

    @Test
    void reloads_a_corrupted_runtime_from_the_log() {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectId projectId = ProjectId.random();
        ProjectHandle first = harness.registry.get(projectId);
        first.pipeline().apply(USER, declare(1, false));
        first.runtime().markCorrupted();

        assertThatThrownBy(() -> first.pipeline().apply(USER, context -> {
                    throw new AssertionError("corrupted runtime must not generate");
                }))
                .isInstanceOf(ProjectRuntimeCorruptedException.class);
        assertThat(harness.log.head(projectId)).isEqualTo(RevisionNumber.of(1));

        ProjectHandle second = harness.registry.get(projectId);

        assertThat(second.runtime()).isNotSameAs(first.runtime());
        assertThat(second.runtime().isCorrupted()).isFalse();
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(1));
        second.pipeline().apply(USER, declare(2, false));
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(2));
        assertThat(harness.loads).hasValue(2);
    }

    @Test
    void reload_waits_for_an_in_flight_commit() throws Exception {
        InMemoryRevisionLog store = new InMemoryRevisionLog();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RuntimeProperties limits = new RuntimeProperties(IDLE, 20, 500);
        ProjectRuntimeFactory factory = new ProjectRuntimeFactory(
                new GatedRevisionLog(store, entered, release),
                new InMemorySnapshotStore(),
                new OfnZipSnapshotCodec(),
                new DefaultProjectSettingsProvider(),
                collecting(new ArrayList<>()),
                Runnable::run,
                limits);
        ManualTicker ticker = new ManualTicker();
        ProjectRuntimeRegistry loading =
                ProjectRuntimeRegistry.withLoader(factory::create, limits, ticker, new SimpleMeterRegistry());
        ProjectId projectId = ProjectId.random();
        ProjectHandle first = loading.get(projectId);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = Thread.ofVirtual()
                .uncaughtExceptionHandler((thread, error) -> failure.set(error))
                .start(() -> first.pipeline().apply(USER, declare(1, false)));
        await(entered);
        AtomicReference<ProjectHandle> reloaded = new AtomicReference<>();
        Thread loader = Thread.ofVirtual()
                .uncaughtExceptionHandler((thread, error) -> failure.set(error))
                .start(() -> {
                    ticker.advance(IDLE.plusNanos(1));
                    reloaded.set(loading.get(projectId));
                });
        awaitQueued(loader);
        release.countDown();

        assertThat(writer.join(Duration.ofSeconds(5))).isTrue();
        assertThat(loader.join(Duration.ofSeconds(5))).isTrue();
        if (failure.get() != null) {
            throw new AssertionError("reload failed", failure.get());
        }
        ProjectHandle second = reloaded.get();
        assertThat(second.runtime()).isNotSameAs(first.runtime());
        assertThat(second.runtime().isCorrupted()).isFalse();
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(1));
        assertThat(first.runtime().isCorrupted()).isTrue();
        second.pipeline().apply(USER, declare(2, false));
        assertThat(second.runtime().head()).isEqualTo(RevisionNumber.of(2));
    }

    @Test
    void loads_hierarchies_and_mints_with_default_settings() {
        Harness harness = Harness.create(IDLE, 20, 500, Runnable::run);
        ProjectHandle handle = harness.registry.get(ProjectId.random());
        OWLDataFactory data = handle.runtime().ontologies().dataFactory();

        assertThat(handle.runtime().view(ClassHierarchyView.class).roots()).containsExactly(data.getOWLThing());
        assertThat(handle.runtime().view(ObjectPropertyHierarchyView.class).roots())
                .containsExactly(data.getOWLTopObjectProperty());
        assertThat(handle.runtime().view(DataPropertyHierarchyView.class).roots())
                .containsExactly(data.getOWLTopDataProperty());
        assertThat(handle.runtime().view(AnnotationPropertyHierarchyView.class).roots())
                .isEmpty();
        assertThat(handle.runtime().view(DisplayNameView.class).displayName(data.getOWLThing()))
                .isEqualTo("owl:Thing");

        handle.pipeline().apply(USER, declare(1, false));
        IRI fresh = new FreshEntityIri("Person", "", "1", Set.of()).toIri();
        handle.pipeline()
                .apply(
                        USER,
                        edits(
                                List.of(new AddAxiom(
                                        OntologyKey.of(1), data.getOWLDeclarationAxiom(data.getOWLClass(fresh)))),
                                false));

        OWLClass created = handle.gate()
                .read(() -> handle.runtime()
                        .ontologies()
                        .signature()
                        .filter(OWLEntity::isOWLClass)
                        .map(OWLEntity::asOWLClass)
                        .filter(cls ->
                                cls.getIRI().toString().startsWith(DefaultProjectSettingsProvider.DEFAULT_IRI_PREFIX))
                        .findFirst()
                        .orElseThrow());
        assertThat(handle.gate()
                        .read(() -> handle.runtime().view(DisplayNameView.class).displayName(created)))
                .isEqualTo("Person");
        assertThat(handle.gate()
                        .read(() ->
                                handle.runtime().view(ClassHierarchyView.class).children(data.getOWLThing())))
                .contains(created);
    }

    @Test
    void snapshots_when_the_revision_number_is_a_multiple_of_snapshot_every() {
        Harness harness = Harness.create(IDLE, 20, 2, Runnable::run);
        ProjectId projectId = ProjectId.random();
        ProjectHandle handle = harness.registry.get(projectId);

        handle.pipeline().apply(USER, declare(1, false));

        assertThat(harness.snapshots.latest(projectId)).isEmpty();
        assertThat(harness.events).extracting(RevisionCommittedEvent::silent).containsExactly(false);

        handle.pipeline().apply(USER, declare(2, true));

        StoredSnapshot snapshot = harness.snapshots.latest(projectId).orElseThrow();
        assertThat(snapshot.revision()).isEqualTo(RevisionNumber.of(2));
        assertThat(snapshot.codecId()).isEqualTo(OfnZipSnapshotCodec.ID);
        assertThat(harness.events)
                .extracting(RevisionCommittedEvent::projectId, RevisionCommittedEvent::silent)
                .containsExactly(tuple(projectId, false), tuple(projectId, true));
    }

    @Test
    void writes_a_snapshot_row_after_snapshot_every_revisions() throws Exception {
        ProjectId projectId = ProjectId.random();
        new TestData(jdbc).insertProject(projectId, USER);
        List<RevisionCommittedEvent> published = new CopyOnWriteArrayList<>();
        context.addApplicationListener(event -> {
            if (event instanceof PayloadApplicationEvent<?> payload
                    && payload.getPayload() instanceof RevisionCommittedEvent committed
                    && committed.projectId().equals(projectId)) {
                published.add(committed);
            }
        });
        ProjectHandle handle = registry.get(projectId);

        handle.pipeline().apply(USER, declare(1, false));
        handle.pipeline().apply(USER, declare(2, true));

        SnapshotRow row = awaitSnapshot(projectId);
        assertThat(row.revision()).isEqualTo(2);
        assertThat(row.codec()).isEqualTo(OfnZipSnapshotCodec.ID);
        assertThat(published).extracting(RevisionCommittedEvent::silent).containsExactly(false, true);
    }

    private SnapshotRow awaitSnapshot(ProjectId projectId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            List<SnapshotRow> rows = snapshotRows(projectId);
            if (!rows.isEmpty()) {
                assertThat(rows).hasSize(1);
                return rows.getFirst();
            }
            Thread.sleep(Duration.ofMillis(20));
        }
        throw new AssertionError("snapshot row did not appear for " + projectId);
    }

    private List<SnapshotRow> snapshotRows(ProjectId projectId) {
        return jdbc.sql("select rev_no, codec from snapshots where project_id = ?")
                .param(projectId.value())
                .query((rs, row) -> new SnapshotRow(rs.getLong("rev_no"), rs.getString("codec")))
                .list();
    }

    private static ChangeListGenerator<String> declare(int ontology, boolean silent) {
        return edits(
                List.of(new DeclareOntology(OntologyKey.of(ontology), Optional.empty(), Optional.empty())), silent);
    }

    private static ChangeListGenerator<String> edits(List<OntologyChange> changes, boolean silent) {
        return new ChangeListGenerator<>() {
            @Override
            public GeneratedChanges<String> generate(ChangeContext context) {
                return new GeneratedChanges<>(changes, "ok");
            }

            @Override
            public boolean silent() {
                return silent;
            }
        };
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

    private static void awaitQueued(Thread thread) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Thread.State state = thread.getState();
            if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED) {
                return;
            }
            if (!thread.isAlive()) {
                throw new AssertionError("thread finished before it queued on the lock");
            }
            Thread.onSpinWait();
        }
        throw new AssertionError("thread did not queue on the lock, state=" + thread.getState());
    }

    private static Tuple tuple(Object... values) {
        return Tuple.tuple(values);
    }

    private record SnapshotRow(long revision, String codec) {}

    private static final class GatedRevisionLog implements RevisionLog {

        private final InMemoryRevisionLog delegate;

        private final CountDownLatch entered;

        private final CountDownLatch release;

        private GatedRevisionLog(InMemoryRevisionLog delegate, CountDownLatch entered, CountDownLatch release) {
            this.delegate = delegate;
            this.entered = entered;
            this.release = release;
        }

        @Override
        public RevisionNumber head(ProjectId projectId) {
            return delegate.head(projectId);
        }

        @Override
        public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
            entered.countDown();
            await(release);
            delegate.append(projectId, expectedHead, revision);
        }

        @Override
        public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
            return delegate.get(projectId, number);
        }

        @Override
        public Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
            return delegate.read(projectId, fromInclusive, toInclusive);
        }
    }

    private static final class Harness {

        private final AtomicInteger loads = new AtomicInteger();

        private final ManualTicker ticker = new ManualTicker();

        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

        private final List<RevisionCommittedEvent> events = new ArrayList<>();

        private final InMemoryRevisionLog log = new InMemoryRevisionLog();

        private final InMemorySnapshotStore snapshots = new InMemorySnapshotStore();

        private final ProjectRuntimeFactory factory;

        private final ProjectRuntimeRegistry registry;

        private Harness(Duration idle, int maxLoaded, int snapshotEvery, Executor executor) {
            RuntimeProperties properties = new RuntimeProperties(idle, maxLoaded, snapshotEvery);
            this.factory = new ProjectRuntimeFactory(
                    log,
                    snapshots,
                    new OfnZipSnapshotCodec(),
                    new DefaultProjectSettingsProvider(),
                    collecting(events),
                    executor,
                    properties);
            this.registry = ProjectRuntimeRegistry.withLoader(
                    id -> {
                        loads.incrementAndGet();
                        return factory.create(id);
                    },
                    properties,
                    ticker,
                    meters);
        }

        private static Harness create(Duration idle, int maxLoaded, int snapshotEvery, Executor executor) {
            return new Harness(idle, maxLoaded, snapshotEvery, executor);
        }
    }

    private static ApplicationEventPublisher collecting(List<RevisionCommittedEvent> events) {
        return new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                publishEvent((Object) event);
            }

            @Override
            public void publishEvent(Object event) {
                if (event instanceof RevisionCommittedEvent committed) {
                    events.add(committed);
                }
            }
        };
    }

    private static final class ManualTicker implements Ticker {

        private final AtomicLong nanos = new AtomicLong(1_000_000_000L);

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }

        @Override
        public long read() {
            return nanos.get();
        }
    }
}
