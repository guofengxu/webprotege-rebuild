package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;

/** Puts an active project in the trash. The bus requires {@code MOVE_ANY_PROJECT_TO_TRASH}. */
public record MoveProjectToTrash(ProjectId projectId) implements Command<ProjectDetails> {

    public MoveProjectToTrash {
        Objects.requireNonNull(projectId, "project id");
    }
}
