package wprebuild.kernel.runtime;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/**
 * The in-memory project no longer matches the revision log (WP-8). The next load rebuilds it
 * from the log. This runtime must not accept another write.
 */
public final class ProjectRuntimeCorruptedException extends RuntimeException {

    private final ProjectId projectId;

    public ProjectRuntimeCorruptedException(ProjectId projectId) {
        super("project runtime is corrupted: " + Objects.requireNonNull(projectId, "project id"));
        this.projectId = projectId;
    }

    public ProjectId projectId() {
        return projectId;
    }
}
