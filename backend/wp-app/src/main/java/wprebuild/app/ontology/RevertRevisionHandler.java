package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.generator.RevertResult;
import wprebuild.kernel.generator.RevertRevisionGenerator;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.ChangeApplicationResult;

/** Commits the inverse of one revision through {@link RevertRevisionGenerator} (BS-04). */
@Service
public final class RevertRevisionHandler implements CommandHandler<RevertRevision, RevisionNumber> {

    private static final Logger log = LoggerFactory.getLogger(RevertRevisionHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisions;

    public RevertRevisionHandler(ProjectRuntimeRegistry registry, RevisionLog revisions) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.revisions = Objects.requireNonNull(revisions, "revisions");
    }

    @Override
    public Class<RevertRevision> commandType() {
        return RevertRevision.class;
    }

    @Override
    public Optional<ProjectId> project(RevertRevision command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(RevertRevision command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.REVERT_CHANGES);
    }

    @Override
    public RevisionNumber handle(RevertRevision command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        // The generation lock stays held after apply returns, so a no-op revert cannot report a
        // later writer's head (ADR-006). apply reenters the same lock.
        return handle.gate().generate(() -> revert(handle, command, context));
    }

    private RevisionNumber revert(ProjectHandle handle, RevertRevision command, CommandContext context) {
        ChangeApplicationResult<RevertResult> applied = handle.pipeline()
                .apply(context.user(), new RevertRevisionGenerator(command.projectId(), command.revision(), revisions));
        if (!applied.result().reverted()) {
            throw rejected(command, applied.result());
        }
        if (applied.revision().isEmpty()) {
            RevisionNumber head = handle.runtime().head();
            log.info(
                    "revert of revision {} in project {} produced no effective changes; head is {}",
                    command.revision().value(),
                    command.projectId(),
                    head.value());
            return head;
        }
        RevisionNumber number = applied.revision().orElseThrow().number();
        log.info(
                "reverted revision {} of project {} as {}",
                command.revision().value(),
                command.projectId(),
                number.value());
        return number;
    }

    private static RuntimeException rejected(RevertRevision command, RevertResult result) {
        // BS-04: RevertRevisionGenerator's rejection text names a missing revision or DeclareOntology.
        String reason = result.reason().orElse("revert rejected");
        log.info(
                "rejected revert of revision {} in project {}: {}",
                command.revision().value(),
                command.projectId(),
                reason);
        if (reason.contains("does not exist")) {
            return new NotFoundException("revision not found");
        }
        return new ConflictException(reason);
    }
}
