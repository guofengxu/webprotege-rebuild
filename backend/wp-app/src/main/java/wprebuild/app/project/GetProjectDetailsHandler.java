package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

@Component
class GetProjectDetailsHandler implements CommandHandler<GetProjectDetails, ProjectDetails> {

    private final ProjectRepository projects;

    GetProjectDetailsHandler(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Class<GetProjectDetails> commandType() {
        return GetProjectDetails.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectDetails command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectDetails command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public ProjectDetails handle(GetProjectDetails command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return projects.find(command.projectId()).orElseThrow(() -> new NotFoundException("project not found"));
    }
}
