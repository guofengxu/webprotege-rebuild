package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/** Loads paths from a root to one entity under the project read lock. */
@Service
public final class GetHierarchyPathsHandler implements CommandHandler<GetHierarchyPaths, List<List<EntityNode>>> {

    private final HierarchyQuery query;

    public GetHierarchyPathsHandler(HierarchyQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public Class<GetHierarchyPaths> commandType() {
        return GetHierarchyPaths.class;
    }

    @Override
    public Optional<ProjectId> project(GetHierarchyPaths command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetHierarchyPaths command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public List<List<EntityNode>> handle(GetHierarchyPaths command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return query.paths(command.projectId(), command.kind(), command.entity());
    }
}
