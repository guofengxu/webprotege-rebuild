package wprebuild.app.project;

import java.util.List;
import java.util.Objects;
import wprebuild.app.access.RoleAssignment;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Lists project members. The bus requires {@code EDIT_SHARING_SETTINGS}. */
public record GetProjectMembers(ProjectId projectId) implements Command<List<RoleAssignment>> {

    public GetProjectMembers {
        Objects.requireNonNull(projectId, "project id");
    }
}
