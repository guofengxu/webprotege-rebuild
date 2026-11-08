package wprebuild.app.access;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** One row of {@code role_assignments}. */
public record RoleAssignment(ProjectId projectId, UserId userId, ProjectRole role) {

    public RoleAssignment {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(role, "role");
    }
}
