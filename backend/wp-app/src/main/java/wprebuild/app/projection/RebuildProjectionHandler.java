package wprebuild.app.projection;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.RevisionLog;

@Component
public final class RebuildProjectionHandler implements CommandHandler<RebuildProjection, ProjectionStatus> {

    private static final Logger log = LoggerFactory.getLogger(RebuildProjectionHandler.class);

    private final ProjectionRebuilder rebuilder;

    private final ProjectionWriter writer;

    private final RevisionLog revisions;

    public RebuildProjectionHandler(ProjectionRebuilder rebuilder, ProjectionWriter writer, RevisionLog revisions) {
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
    }

    @Override
    public Class<RebuildProjection> commandType() {
        return RebuildProjection.class;
    }

    @Override
    public Optional<ProjectId> project(RebuildProjection command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(RebuildProjection command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.EDIT_APPLICATION_SETTINGS);
    }

    @Override
    public ProjectionStatus handle(RebuildProjection command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        rebuilder.rebuild(command.projectId());
        ProjectionStatus status = ProjectionStatuses.read(command.projectId(), writer, revisions);
        log.info(
                "rebuilt projection for {} by {} (revNo={}, head={})",
                command.projectId(),
                forLog(context.user().value()),
                status.revNo(),
                status.head());
        return status;
    }

    private static String forLog(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\r', ' ').replace('\n', ' ');
    }
}
