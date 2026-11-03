package wprebuild.kernel.revision;

import java.util.Optional;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Persists encoded project snapshots keyed by revision. */
public interface SnapshotStore {

    void save(ProjectId projectId, RevisionNumber revision, String codecId, byte[] payload);

    Optional<StoredSnapshot> latest(ProjectId projectId);

    Optional<StoredSnapshot> latestAtOrBefore(ProjectId projectId, RevisionNumber revision);
}
