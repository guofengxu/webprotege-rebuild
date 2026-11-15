package wprebuild.app.projection;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Synchronously rebuilds one project's projection tables. Requires {@code EDIT_APPLICATION_SETTINGS}. */
public record RebuildProjection(ProjectId projectId) implements Command<ProjectionStatus> {

    public RebuildProjection {
        Objects.requireNonNull(projectId, "project id");
    }
}
