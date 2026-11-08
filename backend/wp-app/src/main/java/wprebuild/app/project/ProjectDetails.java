package wprebuild.app.project;

import java.time.Instant;
import java.util.Objects;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** One row of {@code projects}. */
public record ProjectDetails(
        ProjectId projectId,
        String displayName,
        String description,
        UserId owner,
        boolean inTrash,
        Instant createdAt,
        UserId createdBy,
        Instant modifiedAt,
        UserId modifiedBy) {

    public ProjectDetails {
        Objects.requireNonNull(projectId, "project id");
        displayName = ProjectText.displayName(displayName);
        description = ProjectText.description(description);
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(createdAt, "created at");
        Objects.requireNonNull(createdBy, "created by");
        Objects.requireNonNull(modifiedAt, "modified at");
        Objects.requireNonNull(modifiedBy, "modified by");
    }
}
