package wprebuild.app.project;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

@Component
class UpdateProjectDetailsHandler implements CommandHandler<UpdateProjectDetails, ProjectDetails> {

    private final ProjectRepository projects;

    UpdateProjectDetailsHandler(ProjectRepository projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public Class<UpdateProjectDetails> commandType() {
        return UpdateProjectDetails.class;
    }

    @Override
    public Optional<ProjectId> project(UpdateProjectDetails command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(UpdateProjectDetails command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_PROJECT_SETTINGS);
    }

    @Override
    public ProjectDetails handle(UpdateProjectDetails command, CommandContext context) {
        String description = command.description();
        if (description == null) {
            description = projects.find(command.projectId())
                    .orElseThrow(() -> new NotFoundException("project not found"))
                    .description();
        }
        return projects.updateDetails(command.projectId(), command.name(), description, context.user(), Instant.now());
    }
}
