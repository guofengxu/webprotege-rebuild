package wprebuild.kernel.revision;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Shared SnapshotStore behaviour. In-memory and PostgreSQL adapters both run this suite. */
public abstract class SnapshotStoreContract {

    private static final String CODEC = "ofn-zip/1";

    private SnapshotStore store;

    protected abstract SnapshotStore newStore();

    @BeforeEach
    public void create_store() {
        store = newStore();
    }

    @Test
    public void empty_project_has_no_snapshot() {
        ProjectId projectId = ProjectId.random();

        assertThat(store.latest(projectId)).isEmpty();
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(1))).isEmpty();
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.ZERO)).isEmpty();
    }

    @Test
    public void sequential_save_exposes_latest_and_copies_payload() {
        ProjectId projectId = ProjectId.random();
        byte[] firstPayload = bytes("first");
        store.save(projectId, RevisionNumber.of(1), CODEC, firstPayload);
        firstPayload[0] = 'X';

        StoredSnapshot first = store.latest(projectId).orElseThrow();
        assertThat(first.revision()).isEqualTo(RevisionNumber.of(1));
        assertThat(first.codecId()).isEqualTo(CODEC);
        assertThat(first.payload()).isEqualTo(bytes("first"));

        first.payload()[0] = 'Y';
        assertThat(first.payload()).isEqualTo(bytes("first"));
        assertThat(store.latest(projectId).orElseThrow().payload()).isEqualTo(bytes("first"));

        store.save(projectId, RevisionNumber.of(3), CODEC, bytes("third"));
        StoredSnapshot latest = store.latest(projectId).orElseThrow();
        assertThat(latest.revision()).isEqualTo(RevisionNumber.of(3));
        assertThat(latest.payload()).isEqualTo(bytes("third"));
        assertThat(store.latest(ProjectId.random())).isEmpty();
    }

    @Test
    public void latest_at_or_before_returns_the_newest_snapshot_not_after_the_bound() {
        ProjectId projectId = ProjectId.random();
        store.save(projectId, RevisionNumber.of(1), CODEC, bytes("one"));
        store.save(projectId, RevisionNumber.of(3), CODEC, bytes("three"));

        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.ZERO)).isEmpty();
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(1)))
                .map(StoredSnapshot::revision)
                .contains(RevisionNumber.of(1));
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(2)))
                .map(StoredSnapshot::payload)
                .contains(bytes("one"));
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(3)))
                .map(StoredSnapshot::payload)
                .contains(bytes("three"));
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(99)))
                .map(StoredSnapshot::revision)
                .contains(RevisionNumber.of(3));
    }

    @Test
    public void save_replaces_the_payload_at_the_same_revision() {
        ProjectId projectId = ProjectId.random();
        store.save(projectId, RevisionNumber.of(1), CODEC, bytes("old"));
        store.save(projectId, RevisionNumber.of(1), "ofn-zip/1", bytes("new"));

        StoredSnapshot snapshot = store.latest(projectId).orElseThrow();
        assertThat(snapshot.payload()).isEqualTo(bytes("new"));
        assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(1)))
                .map(StoredSnapshot::payload)
                .contains(bytes("new"));
    }

    @Test
    public void concurrent_saves_keep_every_revision() throws Exception {
        ProjectId projectId = ProjectId.random();
        int threads = 8;
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 1; i <= threads; i++) {
                long number = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    store.save(projectId, RevisionNumber.of(number), CODEC, bytes("r" + number));
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

        assertThat(store.latest(projectId).orElseThrow().revision()).isEqualTo(RevisionNumber.of(threads));
        for (int i = 1; i <= threads; i++) {
            int number = i;
            assertThat(store.latestAtOrBefore(projectId, RevisionNumber.of(number)))
                    .map(StoredSnapshot::revision)
                    .contains(RevisionNumber.of(number));
        }
    }

    protected static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
