package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.access.ProjectRole;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Grants or replaces one member's role. The bus requires {@code EDIT_SHARING_SETTINGS}. */
public record SetProjectMember(ProjectId projectId, UserId userId, ProjectRole role)
        implements Command<RoleAssignment> {

    public SetProjectMember {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
        Objects.requireNonNull(role, "role");
    }
}
