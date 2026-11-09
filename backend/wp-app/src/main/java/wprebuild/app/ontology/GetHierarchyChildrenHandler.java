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

/** Loads the direct children of one hierarchy node under the project read lock. */
@Service
public final class GetHierarchyChildrenHandler implements CommandHandler<GetHierarchyChildren, List<EntityNode>> {

    private final HierarchyQuery query;

    public GetHierarchyChildrenHandler(HierarchyQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public Class<GetHierarchyChildren> commandType() {
        return GetHierarchyChildren.class;
    }

    @Override
    public Optional<ProjectId> project(GetHierarchyChildren command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetHierarchyChildren command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public List<EntityNode> handle(GetHierarchyChildren command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return query.children(command.projectId(), command.kind(), command.parent());
    }
}
