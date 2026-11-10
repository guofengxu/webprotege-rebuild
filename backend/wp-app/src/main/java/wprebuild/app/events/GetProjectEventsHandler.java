package wprebuild.app.events;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.kernel.id.ProjectId;

/** Checks {@code VIEW_PROJECT} and loads the project so the event buffer has a head revision. */
@Service
public final class GetProjectEventsHandler implements CommandHandler<GetProjectEvents, ProjectId> {

    private final ProjectRuntimeRegistry registry;

    public GetProjectEventsHandler(ProjectRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Class<GetProjectEvents> commandType() {
        return GetProjectEvents.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectEvents command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectEvents command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public ProjectId handle(GetProjectEvents command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        registry.get(command.projectId());
        return command.projectId();
    }
}
