package wprebuild.kernel.revision;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Thread-safe {@link SnapshotStore} that keeps payloads in process memory. */
public final class InMemorySnapshotStore implements SnapshotStore {

    private final ConcurrentHashMap<ProjectId, ConcurrentSkipListMap<RevisionNumber, StoredSnapshot>> projects =
            new ConcurrentHashMap<>();

    @Override
    public void save(ProjectId projectId, RevisionNumber revision, String codecId, byte[] payload) {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(codecId, "codec id");
        Objects.requireNonNull(payload, "payload");
        StoredSnapshot snapshot = new StoredSnapshot(revision, codecId, payload);
        project(requireProject(projectId)).put(revision, snapshot);
    }

    @Override
    public Optional<StoredSnapshot> latest(ProjectId projectId) {
        ConcurrentSkipListMap<RevisionNumber, StoredSnapshot> snapshots = projects.get(requireProject(projectId));
        if (snapshots == null || snapshots.isEmpty()) {
            return Optional.empty();
        }
        var latest = snapshots.lastEntry();
        return latest == null ? Optional.empty() : Optional.of(copyOf(latest.getValue()));
    }

    @Override
    public Optional<StoredSnapshot> latestAtOrBefore(ProjectId projectId, RevisionNumber revision) {
        Objects.requireNonNull(revision, "revision");
        ConcurrentSkipListMap<RevisionNumber, StoredSnapshot> snapshots = projects.get(requireProject(projectId));
        if (snapshots == null || snapshots.isEmpty()) {
            return Optional.empty();
        }
        var entry = snapshots.floorEntry(revision);
        return entry == null ? Optional.empty() : Optional.of(copyOf(entry.getValue()));
    }

    private ConcurrentSkipListMap<RevisionNumber, StoredSnapshot> project(ProjectId projectId) {
        return projects.computeIfAbsent(projectId, id -> new ConcurrentSkipListMap<>());
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }

    private static StoredSnapshot copyOf(StoredSnapshot snapshot) {
        return new StoredSnapshot(snapshot.revision(), snapshot.codecId(), snapshot.payload());
    }
}
