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
class ListTrashedProjectsHandler implements CommandHandler<ListTrashedProjects, List<ProjectDetails>> {

    private final ProjectRepository projects;

    private final ProjectVisibility visibility;

    ListTrashedProjectsHandler(ProjectRepository projects, ProjectVisibility visibility) {
        this.projects = Objects.requireNonNull(projects, "projects");
        this.visibility = Objects.requireNonNull(visibility, "visibility");
    }

    @Override
    public Class<ListTrashedProjects> commandType() {
        return ListTrashedProjects.class;
    }

    @Override
    public Optional<ProjectId> project(ListTrashedProjects command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(ListTrashedProjects command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public List<ProjectDetails> handle(ListTrashedProjects command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        if (visibility.seesEveryProject(context.user())) {
            return projects.listAll(true);
        }
        return projects.listFor(context.user(), true);
    }
}
