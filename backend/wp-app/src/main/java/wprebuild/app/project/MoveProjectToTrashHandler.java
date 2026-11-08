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
class MoveProjectToTrashHandler implements CommandHandler<MoveProjectToTrash, ProjectDetails> {

    private final ProjectRepository projects;

    MoveProjectToTrashHandler(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Class<MoveProjectToTrash> commandType() {
        return MoveProjectToTrash.class;
    }

    @Override
    public Optional<ProjectId> project(MoveProjectToTrash command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(MoveProjectToTrash command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.MOVE_ANY_PROJECT_TO_TRASH);
    }

    @Override
    public ProjectDetails handle(MoveProjectToTrash command, CommandContext context) {
        return projects.setTrash(command.projectId(), true, context.user(), Instant.now());
    }
}
