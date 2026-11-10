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

/** Reads entity history from {@code revision_subjects} without scanning every revision. */
@Service
public final class ListEntityRevisionsHandler implements CommandHandler<ListEntityRevisions, RevisionPage> {

    private final RevisionHistoryQueries history;

    public ListEntityRevisionsHandler(RevisionHistoryQueries history) {
        this.history = Objects.requireNonNull(history, "history");
    }

    @Override
    public Class<ListEntityRevisions> commandType() {
        return ListEntityRevisions.class;
    }

    @Override
    public Optional<ProjectId> project(ListEntityRevisions command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListEntityRevisions command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_CHANGES);
    }

    @Override
    public RevisionPage handle(ListEntityRevisions command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return history.listBySubject(command.projectId(), command.iri(), command.page(), command.size());
    }
}
