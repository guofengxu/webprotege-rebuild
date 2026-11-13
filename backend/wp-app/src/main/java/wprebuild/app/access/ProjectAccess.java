package wprebuild.app.access;

import java.util.Objects;
import java.util.Set;

/**
 * The signed-in user's role and project-scoped actions on one project. {@code role} is null when the
 * user has no assignment (for example a system administrator who was never granted a project role).
 */
public record ProjectAccess(ProjectRole role, boolean systemAdmin, Set<Action> actions) {

    public ProjectAccess {
        actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
        for (Action action : actions) {
            if (!action.projectScoped()) {
                throw new IllegalArgumentException("actions must be project-scoped: " + action);
            }
        }
    }
}
