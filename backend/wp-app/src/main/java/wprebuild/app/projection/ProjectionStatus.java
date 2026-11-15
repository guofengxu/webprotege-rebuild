package wprebuild.app.projection;

import java.time.Instant;
import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/**
 * Projection catch-up status for one project. When there is no {@code project_state} row, {@code
 * revNo}, {@code lag}, {@code rebuiltAt}, and {@code updatedAt} are {@code null}.
 */
public record ProjectionStatus(
        ProjectId projectId, Long revNo, long head, Long lag, Instant rebuiltAt, Instant updatedAt) {

    public ProjectionStatus {
        Objects.requireNonNull(projectId, "project id");
    }
}
