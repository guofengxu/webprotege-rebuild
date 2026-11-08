package wprebuild.app.ontology;

import java.util.Objects;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.runtime.PostCommitListener;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectSnapshotter;
import wprebuild.kernel.runtime.ProjectWriteGate;

/**
 * Queues a snapshot when the committed revision number is a multiple of {@code snapshotEvery}.
 *
 * <p>The task runs on a separate executor so the generation lock is not held while the ontology is
 * encoded. The read lock then keeps a writer from applying changes during that encode (ADR-006).
 */
final class SnapshotOnCommit implements PostCommitListener {

    private static final Logger log = LoggerFactory.getLogger(SnapshotOnCommit.class);

    private final ProjectId projectId;

    private final ProjectWriteGate gate;

    private final SnapshotStore snapshotStore;

    private final SnapshotCodec snapshotCodec;

    private final Executor snapshotExecutor;

    private final int snapshotEvery;

    SnapshotOnCommit(
            ProjectId projectId,
            ProjectWriteGate gate,
            SnapshotStore snapshotStore,
            SnapshotCodec snapshotCodec,
            Executor snapshotExecutor,
            int snapshotEvery) {
        this.projectId = Objects.requireNonNull(projectId, "project id");
        this.gate = Objects.requireNonNull(gate, "write gate");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshot store");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshot codec");
        this.snapshotExecutor = Objects.requireNonNull(snapshotExecutor, "snapshot executor");
        if (snapshotEvery < 1) {
            throw new IllegalArgumentException("snapshotEvery must be positive");
        }
        this.snapshotEvery = snapshotEvery;
    }

    @Override
    public void onCommitted(ProjectRuntime runtime, Revision revision, boolean silent) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(revision, "revision");
        long number = revision.number().value();
        if (number % snapshotEvery != 0) {
            return;
        }
        snapshotExecutor.execute(() -> snapshot(runtime, number));
    }

    private void snapshot(ProjectRuntime runtime, long revision) {
        try {
            gate.read(() -> {
                capture(runtime);
                return null;
            });
        } catch (RuntimeException failure) {
            log.error("snapshot failed for project {} after revision {}", projectId, revision, failure);
        }
    }

    private void capture(ProjectRuntime runtime) {
        if (runtime.isCorrupted()) {
            log.warn("skipping snapshot of corrupted project runtime {}", projectId);
            return;
        }
        ProjectSnapshotter.snapshot(runtime, snapshotStore, snapshotCodec);
    }
}
