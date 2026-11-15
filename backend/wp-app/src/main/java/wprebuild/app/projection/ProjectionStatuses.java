package wprebuild.app.projection;

import java.util.Objects;
import java.util.Optional;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.RevisionLog;

/** Assembles {@link ProjectionStatus} from revision head and {@code project_state}. */
final class ProjectionStatuses {

    private ProjectionStatuses() {}

    static ProjectionStatus read(ProjectId projectId, ProjectionWriter writer, RevisionLog revisions) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(writer, "writer");
        Objects.requireNonNull(revisions, "revisions");
        long head = revisions.head(projectId).value();
        Optional<ProjectionWriter.ProjectState> state = writer.projectState(projectId);
        if (state.isEmpty()) {
            return new ProjectionStatus(projectId, null, head, null, null, null);
        }
        ProjectionWriter.ProjectState row = state.get();
        // Forward-only writes can leave rev_no ahead of head briefly; lag is never negative for ops.
        long lag = Math.max(0L, head - row.revNo());
        return new ProjectionStatus(projectId, row.revNo(), head, lag, row.rebuiltAt(), row.updatedAt());
    }
}
