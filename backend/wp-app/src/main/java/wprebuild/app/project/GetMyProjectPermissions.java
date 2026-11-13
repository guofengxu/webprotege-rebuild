package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.access.ProjectAccess;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Returns the caller's role and allowed project-scoped actions on one project. */
public record GetMyProjectPermissions(ProjectId projectId) implements Command<ProjectAccess> {

    public GetMyProjectPermissions {
        Objects.requireNonNull(projectId, "project id");
    }
}
