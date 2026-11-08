package wprebuild.app.project;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class RestoreProjectFromTrashHandler implements CommandHandler<RestoreProjectFromTrash, ProjectDetails> {

    private final ProjectRepository projects;

    RestoreProjectFromTrashHandler(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Class<RestoreProjectFromTrash> commandType() {
        return RestoreProjectFromTrash.class;
    }

    @Override
    public Optional<ProjectId> project(RestoreProjectFromTrash command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(RestoreProjectFromTrash command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.MOVE_ANY_PROJECT_TO_TRASH);
    }

    @Override
    public boolean allowsTrashedProject() {
        return true;
    }

    @Override
    public ProjectDetails handle(RestoreProjectFromTrash command, CommandContext context) {
        return projects.setTrash(command.projectId(), false, context.user(), Instant.now());
    }
}
