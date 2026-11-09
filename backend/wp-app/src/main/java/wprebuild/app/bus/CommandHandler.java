package wprebuild.app.bus;

import java.util.Optional;
import java.util.Set;
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

    /**
     * Every action checked before {@link #handle}. The default is the single {@link #requiredAction}.
     * A command that touches several entity types returns one action per type (BS-10).
     */
    default Set<Action> requiredActions(C command) {
        Optional<Action> action = requiredAction(command);
        if (action == null) {
            throw new IllegalStateException(
                    "required action returned null for " + commandType().getName());
        }
        return action.map(Set::of).orElseGet(Set::of);
    }

    default boolean allowsTrashedProject() {
        return false;
    }

    R handle(C command, CommandContext context);
}
