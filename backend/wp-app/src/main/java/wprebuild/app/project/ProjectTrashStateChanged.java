package wprebuild.app.project;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/** Published after a project's trash flag actually changes. */
public record ProjectTrashStateChanged(ProjectId projectId, boolean trashed) {

    public ProjectTrashStateChanged {
        Objects.requireNonNull(projectId, "project id");
    }
}
