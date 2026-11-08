package wprebuild.app.project;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class ListMyProjectsHandler implements CommandHandler<ListMyProjects, List<ProjectDetails>> {

    private final ProjectRepository projects;

    private final ProjectVisibility visibility;

    ListMyProjectsHandler(ProjectRepository projects, ProjectVisibility visibility) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    @Override
    public Class<ListMyProjects> commandType() {
        return ListMyProjects.class;
    }

    @Override
    public Optional<ProjectId> project(ListMyProjects command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(ListMyProjects command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public List<ProjectDetails> handle(ListMyProjects command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        if (visibility.seesEveryProject(context.user())) {
            return projects.listAll(false);
        }
        return projects.listFor(context.user(), false);
    }
}
