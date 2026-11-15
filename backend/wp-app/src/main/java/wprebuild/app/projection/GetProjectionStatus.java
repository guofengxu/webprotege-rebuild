package wprebuild.app.projection;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Reads projection catch-up status for one project. Requires {@code EDIT_APPLICATION_SETTINGS}. */
public record GetProjectionStatus(ProjectId projectId) implements Command<ProjectionStatus> {

    public GetProjectionStatus {
        Objects.requireNonNull(projectId, "project id");
    }
}
