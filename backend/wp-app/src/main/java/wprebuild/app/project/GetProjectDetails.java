package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Reads one active project. The bus requires {@code VIEW_PROJECT}. */
public record GetProjectDetails(ProjectId projectId) implements Command<ProjectDetails> {

    public GetProjectDetails {
        Objects.requireNonNull(projectId, "project id");
    }
}
