package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/**
 * Returns a project from the trash. The handler allows a trashed project and the bus requires
 * {@code MOVE_ANY_PROJECT_TO_TRASH}.
 */
public record RestoreProjectFromTrash(ProjectId projectId) implements Command<ProjectDetails> {

    public RestoreProjectFromTrash {
        Objects.requireNonNull(projectId, "project id");
    }
}
