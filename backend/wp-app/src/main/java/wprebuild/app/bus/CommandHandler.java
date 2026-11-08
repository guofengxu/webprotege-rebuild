package wprebuild.app.bus;

import java.util.Optional;
import wprebuild.app.access.Action;
import wprebuild.kernel.id.ProjectId;

/**
 * Handles one command type. An empty {@link #requiredAction} means the caller only has to be logged
 * in. {@link #allowsTrashedProject} defaults to false, so a trashed project is rejected.
 */
public interface CommandHandler<C extends Command<R>, R> {

    Class<C> commandType();

    Optional<ProjectId> project(C command);

    Optional<Action> requiredAction(C command);

    default boolean allowsTrashedProject() {
        return false;
    }

    R handle(C command, CommandContext context);
}
