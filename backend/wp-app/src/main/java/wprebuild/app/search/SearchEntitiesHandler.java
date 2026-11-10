package wprebuild.app.search;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.id.ProjectId;

/** Runs a project search under the caller's {@code VIEW_PROJECT} permission. */
@Service
public final class SearchEntitiesHandler implements CommandHandler<SearchEntities, List<EntityNode>> {

    private final ProjectSearchIndex index;

    public SearchEntitiesHandler(ProjectSearchIndex index) {
        this.index = Objects.requireNonNull(index, "index");
    }

    @Override
    public Class<SearchEntities> commandType() {
        return SearchEntities.class;
    }

    @Override
    public Optional<ProjectId> project(SearchEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(SearchEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public List<EntityNode> handle(SearchEntities command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return index.search(command.projectId(), command.query(), command.types(), command.limit());
    }
}
