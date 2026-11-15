package wprebuild.app.web.admin;

import java.time.Instant;
import java.util.Objects;
import wprebuild.app.projection.ProjectionStatus;

/** HTTP body for projection status and rebuild admin endpoints. */
public record ProjectionStatusResponse(
        String projectId, Long revNo, long head, Long lag, Instant rebuiltAt, Instant updatedAt) {

    public ProjectionStatusResponse {
        Objects.requireNonNull(projectId, "project id");
    }

    static ProjectionStatusResponse from(ProjectionStatus status) {
        Objects.requireNonNull(status, "status");
        return new ProjectionStatusResponse(
                status.projectId().value().toString(),
                status.revNo(),
                status.head(),
                status.lag(),
                status.rebuiltAt(),
                status.updatedAt());
    }
}
