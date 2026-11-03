package wprebuild.kernel.revision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/**
 * Shared RevisionLog behaviour. In-memory and PostgreSQL adapters both run this suite.
 *
 * <p>BS-03, WP-5, ADR-004.
 */
public abstract class RevisionLogContract {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T12:00:00Z");

    private static final OntologyChange DECLARE =
            new DeclareOntology(OntologyKey.of(1), Optional.empty(), Optional.empty());

    private RevisionLog log;

    protected abstract RevisionLog newLog();

    @BeforeEach
    public void create_log() {
        log = newLog();
    }

    @Test
    public void empty_project_head_is_zero() {
        ProjectId projectId = ProjectId.random();

        assertThat(log.head(projectId)).isEqualTo(RevisionNumber.ZERO);
        assertThat(log.get(projectId, RevisionNumber.of(1))).isEmpty();
        assertThat(read(projectId, RevisionNumber.ZERO, RevisionNumber.of(1))).isEmpty();
    }

    @Test
    public void sequential_append_advances_head_and_stores_each_revision() {
        ProjectId projectId = ProjectId.random();
        Revision first = revision(1, "import", DECLARE);
        Revision second = revision(2, "edit");

        log.append(projectId, RevisionNumber.ZERO, first);
        log.append(projectId, RevisionNumber.of(1), second);

        assertThat(log.head(projectId)).isEqualTo(RevisionNumber.of(2));
        assertThat(log.get(projectId, RevisionNumber.of(1))).contains(first);
        assertThat(log.get(projectId, RevisionNumber.of(2))).contains(second);
        assertThat(log.get(projectId, RevisionNumber.of(3))).isEmpty();
        assertThat(log.head(ProjectId.random())).isEqualTo(RevisionNumber.ZERO);
    }

    @Test
    public void read_returns_the_inclusive_revision_range_in_order() {
        ProjectId projectId = ProjectId.random();
        Revision first = revision(1, "one");
        Revision second = revision(2, "two");
        Revision third = revision(3, "three");
        log.append(projectId, RevisionNumber.ZERO, first);
        log.append(projectId, RevisionNumber.of(1), second);
        log.append(projectId, RevisionNumber.of(2), third);

        assertThat(read(projectId, RevisionNumber.of(1), RevisionNumber.of(3))).containsExactly(first, second, third);
        assertThat(read(projectId, RevisionNumber.of(2), RevisionNumber.of(2))).containsExactly(second);
        assertThat(read(projectId, RevisionNumber.of(2), RevisionNumber.of(3))).containsExactly(second, third);
        assertThat(read(projectId, RevisionNumber.ZERO, RevisionNumber.of(2))).containsExactly(first, second);
        assertThat(read(projectId, RevisionNumber.of(4), RevisionNumber.of(8))).isEmpty();
        assertThat(read(projectId, RevisionNumber.of(3), RevisionNumber.of(1))).isEmpty();
    }

    @Test
    public void append_rejects_a_revision_number_that_is_not_the_successor_of_expected_head() {
        ProjectId projectId = ProjectId.random();

        assertThatThrownBy(() -> log.append(projectId, RevisionNumber.ZERO, revision(2, "gap")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("2");

        log.append(projectId, RevisionNumber.ZERO, revision(1, "ok"));

        assertThatThrownBy(() -> log.append(projectId, RevisionNumber.of(1), revision(3, "gap")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(log.head(projectId)).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    public void append_rejects_a_stale_expected_head() {
        ProjectId projectId = ProjectId.random();
        log.append(projectId, RevisionNumber.ZERO, revision(1, "first"));

        assertThatThrownBy(() -> log.append(projectId, RevisionNumber.ZERO, revision(1, "again")))
                .isInstanceOf(ConcurrentAppendException.class);
        assertThat(log.head(projectId)).isEqualTo(RevisionNumber.of(1));
    }

    @Test
    public void concurrent_append_with_the_same_expected_head_allows_exactly_one_success() throws Exception {
        ProjectId projectId = ProjectId.random();
        RevisionNumber expectedHead = RevisionNumber.ZERO;
        int threads = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    try {
                        log.append(projectId, expectedHead, revision(1, "race-" + index));
                        successes.incrementAndGet();
                    } catch (ConcurrentAppendException ignored) {
                        conflicts.incrementAndGet();
                    }
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(5, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes.get()).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(7);
        assertThat(log.head(projectId)).isEqualTo(RevisionNumber.of(1));
        assertThat(log.get(projectId, RevisionNumber.of(1))).isPresent();
        assertThat(read(projectId, RevisionNumber.of(1), RevisionNumber.of(1))).hasSize(1);
    }

    private List<Revision> read(ProjectId projectId, RevisionNumber from, RevisionNumber to) {
        try (Stream<Revision> stream = log.read(projectId, from, to)) {
            return stream.toList();
        }
    }

    protected static Revision revision(long number, String description, OntologyChange... changes) {
        return new Revision(RevisionNumber.of(number), UserId.SYSTEM, TIMESTAMP, description, List.of(changes));
    }
}
