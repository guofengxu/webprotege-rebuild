package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.revision.RevisionHistoryQueries;
import wprebuild.app.revision.RevisionPage;
import wprebuild.kernel.id.ProjectId;

/** Reads a page of revision metadata without decoding payloads. */
@Service
public final class ListRevisionsHandler implements CommandHandler<ListRevisions, RevisionPage> {

    private final RevisionHistoryQueries history;

    public ListRevisionsHandler(RevisionHistoryQueries history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public Class<ListRevisions> commandType() {
        return ListRevisions.class;
    }

    @Override
    public Optional<ProjectId> project(ListRevisions command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListRevisions command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_CHANGES);
    }

    @Override
    public RevisionPage handle(ListRevisions command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return history.list(command.projectId(), command.page(), command.size());
    }
}
