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

/** Loads hierarchy roots under the project read lock. */
@Service
public final class GetHierarchyRootsHandler implements CommandHandler<GetHierarchyRoots, List<EntityNode>> {

    private final HierarchyQuery query;

    public GetHierarchyRootsHandler(HierarchyQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public Class<GetHierarchyRoots> commandType() {
        return GetHierarchyRoots.class;
    }

    @Override
    public Optional<ProjectId> project(GetHierarchyRoots command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetHierarchyRoots command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public List<EntityNode> handle(GetHierarchyRoots command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return query.roots(command.projectId(), command.kind());
    }
}
