package wprebuild.app.projection;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.RevisionLog;

@Component
public final class GetProjectionStatusHandler implements CommandHandler<GetProjectionStatus, ProjectionStatus> {

    private final ProjectionWriter writer;

    private final RevisionLog revisions;

    public GetProjectionStatusHandler(ProjectionWriter writer, RevisionLog revisions) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
    }

    @Override
    public Class<GetProjectionStatus> commandType() {
        return GetProjectionStatus.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectionStatus command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectionStatus command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_APPLICATION_SETTINGS);
    }

    @Override
    public boolean allowsTrashedProject() {
        return true;
    }

    @Override
    public ProjectionStatus handle(GetProjectionStatus command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        return ProjectionStatuses.read(command.projectId(), writer, revisions);
    }
}
