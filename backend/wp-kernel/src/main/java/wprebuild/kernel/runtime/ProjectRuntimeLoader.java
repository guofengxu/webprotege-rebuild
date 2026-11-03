package wprebuild.kernel.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.revision.StoredSnapshot;

/**
 * Builds a {@link ProjectRuntime} from the latest snapshot, then the revisions after that snapshot
 * (BS-03).
 *
 * <p>Log entries are already effective, so replay calls {@code apply} directly. Each view is
 * rebuilt once from the loaded ontologies; {@link DerivedView#onApplied} is not used here.
 */
public final class ProjectRuntimeLoader {

    private final RevisionLog revisionLog;

    private final SnapshotStore snapshotStore;

    private final SnapshotCodec snapshotCodec;

    private final List<Supplier<DerivedView>> viewSuppliers;

    public ProjectRuntimeLoader(
            RevisionLog revisionLog,
            SnapshotStore snapshotStore,
            SnapshotCodec snapshotCodec,
            List<Supplier<DerivedView>> viewSuppliers) {
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshot store");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshot codec");
        this.viewSuppliers = List.copyOf(Objects.requireNonNull(viewSuppliers, "view suppliers"));
    }

    /**
     * @throws IllegalStateException if the latest snapshot uses another codec, is newer than the
     *     head, or the log skips a revision or returns one beyond the head
     */
    public ProjectRuntime load(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        RevisionNumber head = revisionLog.head(projectId);
        MutableProjectOntologies ontologies = loadOntologies(projectId, head);
        List<DerivedView> views = newViews();
        ProjectRuntime runtime = new ProjectRuntime(projectId, ontologies, head, views);
        for (DerivedView view : views) {
            view.rebuild(runtime.ontologies());
        }
        return runtime;
    }

    private MutableProjectOntologies loadOntologies(ProjectId projectId, RevisionNumber head) {
        Optional<StoredSnapshot> latest = snapshotStore.latest(projectId);
        if (latest.isEmpty()) {
            MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
            replayAfter(projectId, ontologies, RevisionNumber.ZERO, head);
            return ontologies;
        }
        StoredSnapshot snapshot = latest.get();
        if (!snapshot.codecId().equals(snapshotCodec.id())) {
            throw new IllegalStateException(
                    "snapshot codec id is %s, loader codec is %s".formatted(snapshot.codecId(), snapshotCodec.id()));
        }
        if (snapshot.revision().isAfter(head)) {
            throw new IllegalStateException("snapshot revision %s is after head %s for %s"
                    .formatted(snapshot.revision().value(), head.value(), projectId));
        }
        MutableProjectOntologies ontologies =
                Objects.requireNonNull(snapshotCodec.decode(snapshot.payload()), "decoded ontologies");
        replayAfter(projectId, ontologies, snapshot.revision(), head);
        return ontologies;
    }

    private void replayAfter(
            ProjectId projectId, MutableProjectOntologies ontologies, RevisionNumber after, RevisionNumber head) {
        if (!head.isAfter(after)) {
            return;
        }
        RevisionNumber expected = after.next();
        boolean appliedHead = false;
        try (Stream<Revision> revisions =
                Objects.requireNonNull(revisionLog.read(projectId, expected, head), "revisions")) {
            for (Revision revision : revisions.toList()) {
                Revision current = Objects.requireNonNull(revision, "revision");
                if (appliedHead || current.number().isAfter(head)) {
                    throw beyondHead(projectId, current.number(), head);
                }
                if (!current.number().equals(expected)) {
                    throw missing(projectId, expected);
                }
                ontologies.apply(current.changes());
                if (current.number().equals(head)) {
                    appliedHead = true;
                } else {
                    expected = current.number().next();
                }
            }
        }
        if (!appliedHead) {
            throw missing(projectId, expected);
        }
    }

    private List<DerivedView> newViews() {
        List<DerivedView> views = new ArrayList<>(viewSuppliers.size());
        for (Supplier<DerivedView> supplier : viewSuppliers) {
            views.add(Objects.requireNonNull(supplier.get(), "derived view"));
        }
        return views;
    }

    private static IllegalStateException missing(ProjectId projectId, RevisionNumber number) {
        return new IllegalStateException(
                "revision log for %s is missing revision %s".formatted(projectId, number.value()));
    }

    private static IllegalStateException beyondHead(ProjectId projectId, RevisionNumber number, RevisionNumber head) {
        return new IllegalStateException("revision log for %s returned revision %s beyond head %s"
                .formatted(projectId, number.value(), head.value()));
    }
}
