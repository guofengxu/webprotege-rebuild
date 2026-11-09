package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * Builds a temporary ontology collection at one revision. The result is not registered.
 *
 * <p>The latest snapshot at or before the revision is replayed forward. {@link RevisionLog#read}
 * uses its own connection, so it cannot see rows the caller's transaction has not committed; those
 * rows are loaded with {@link RevisionLog#get}.
 */
final class RevisionOntologies {

    private final RevisionLog revisionLog;

    private final SnapshotStore snapshotStore;

    private final SnapshotCodec snapshotCodec;

    RevisionOntologies(RevisionLog revisionLog, SnapshotStore snapshotStore, SnapshotCodec snapshotCodec) {
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshot store");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshot codec");
    }

    /** Log head, including a revision whose memory apply failed. */
    RevisionNumber storedHead(ProjectId projectId) {
        return revisionLog.head(Objects.requireNonNull(projectId, "project id"));
    }

    MutableProjectOntologies at(ProjectId projectId, RevisionNumber target) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(target, "revision");
        Optional<StoredSnapshot> stored = snapshotStore.latestAtOrBefore(projectId, target);
        if (stored.isEmpty()) {
            MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
            replay(projectId, ontologies, RevisionNumber.ZERO, target);
            return ontologies;
        }
        StoredSnapshot snapshot = stored.get();
        if (snapshot.revision().isAfter(target)) {
            throw new IllegalStateException("snapshot revision %s is after %s for %s"
                    .formatted(snapshot.revision().value(), target.value(), projectId));
        }
        if (!snapshot.codecId().equals(snapshotCodec.id())) {
            throw new IllegalStateException(
                    "snapshot codec id is %s, loader codec is %s".formatted(snapshot.codecId(), snapshotCodec.id()));
        }
        MutableProjectOntologies ontologies =
                Objects.requireNonNull(snapshotCodec.decode(snapshot.payload()), "decoded ontologies");
        replay(projectId, ontologies, snapshot.revision(), target);
        return ontologies;
    }

    private void replay(
            ProjectId projectId, MutableProjectOntologies ontologies, RevisionNumber after, RevisionNumber target) {
        if (!target.isAfter(after)) {
            return;
        }
        RevisionNumber from = after.next();
        RevisionNumber expected = from;
        boolean appliedTarget = false;
        for (Revision revision : revisions(projectId, from, target)) {
            Revision current = Objects.requireNonNull(revision, "revision");
            if (appliedTarget || !current.number().equals(expected)) {
                throw missing(projectId, expected);
            }
            ontologies.apply(current.changes());
            if (current.number().equals(target)) {
                appliedTarget = true;
            } else {
                expected = current.number().next();
            }
        }
        if (!appliedTarget) {
            throw missing(projectId, expected);
        }
    }

    private List<Revision> revisions(ProjectId projectId, RevisionNumber from, RevisionNumber to) {
        List<Revision> streamed;
        try (Stream<Revision> revisions = revisionLog.read(projectId, from, to)) {
            streamed = revisions.toList();
        }
        if (covers(streamed, from, to)) {
            return streamed;
        }
        List<Revision> rows = new ArrayList<>();
        RevisionNumber number = from;
        while (!number.isAfter(to)) {
            RevisionNumber current = number;
            rows.add(revisionLog.get(projectId, current).orElseThrow(() -> missing(projectId, current)));
            if (current.equals(to)) {
                break;
            }
            number = current.next();
        }
        return List.copyOf(rows);
    }

    private static boolean covers(List<Revision> rows, RevisionNumber from, RevisionNumber to) {
        if (rows.isEmpty() || rows.getFirst() == null || rows.getLast() == null) {
            return false;
        }
        if (!rows.getFirst().number().equals(from) || !rows.getLast().number().equals(to)) {
            return false;
        }
        RevisionNumber expected = from;
        for (Revision revision : rows) {
            if (revision == null || !revision.number().equals(expected)) {
                return false;
            }
            expected = revision.number().equals(to) ? to : revision.number().next();
        }
        return true;
    }

    private static IllegalStateException missing(ProjectId projectId, RevisionNumber number) {
        return new IllegalStateException(
                "revision log for %s is missing revision %s".formatted(projectId, number.value()));
    }
}
