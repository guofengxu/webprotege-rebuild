package wprebuild.app.web;

import java.time.Instant;
import java.util.UUID;
import wprebuild.app.project.ProjectDetails;

/** Project metadata returned by {@code /api/projects}. */
public record ProjectResponse(
        UUID projectId,
        String displayName,
        String description,
        String owner,
        boolean inTrash,
        Instant createdAt,
        String createdBy,
        Instant modifiedAt,
        String modifiedBy) {

    public static ProjectResponse from(ProjectDetails details) {
        return new ProjectResponse(
                details.projectId().value(),
                details.displayName(),
                details.description(),
                details.owner().value(),
                details.inTrash(),
                details.createdAt(),
                details.createdBy().value(),
                details.modifiedAt(),
                details.modifiedBy().value());
    }
}
