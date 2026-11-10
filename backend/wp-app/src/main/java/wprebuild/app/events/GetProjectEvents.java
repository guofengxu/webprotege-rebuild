package wprebuild.app.events;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Authorizes a project event stream or long poll. Requires {@code VIEW_PROJECT}. */
public record GetProjectEvents(ProjectId projectId) implements Command<ProjectId> {

    public GetProjectEvents {
        Objects.requireNonNull(projectId, "project id");
    }
}
