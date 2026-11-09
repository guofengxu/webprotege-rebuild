package wprebuild.app.ontology.frame;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.GetClassFrame;
import wprebuild.kernel.id.ProjectId;

/** Loads a class frame under the project read lock. Permission is checked by the command bus. */
@Service
public final class GetClassFrameHandler implements CommandHandler<GetClassFrame, ClassFrame> {

    private final ClassFrameQuery query;

    public GetClassFrameHandler(ClassFrameQuery query) {
        this.query = Objects.requireNonNull(query, "query");
    }

    @Override
    public Class<GetClassFrame> commandType() {
        return GetClassFrame.class;
    }

    @Override
    public Optional<ProjectId> project(GetClassFrame command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetClassFrame command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public ClassFrame handle(GetClassFrame command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return query.frame(command.projectId(), command.iri());
    }
}
