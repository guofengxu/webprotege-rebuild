package wprebuild.app.project;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Removes one member. The bus requires {@code EDIT_SHARING_SETTINGS}. */
public record RemoveProjectMember(ProjectId projectId, UserId userId) implements Command<Void> {

    public RemoveProjectMember {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(userId, "user id");
    }
}
