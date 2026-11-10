package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/** Decodes one revision and renders its changes under the project read lock. */
@Service
public final class GetRevisionDiffHandler implements CommandHandler<GetRevisionDiff, RevisionDiff> {

    private final RevisionLog revisions;

    private final ProjectRuntimeRegistry registry;

    private final HierarchyQuery hierarchy;

    public GetRevisionDiffHandler(RevisionLog revisions, ProjectRuntimeRegistry registry, HierarchyQuery hierarchy) {
        this.revisions = Objects.requireNonNull(revisions, "revisions");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    @Override
    public Class<GetRevisionDiff> commandType() {
        return GetRevisionDiff.class;
    }

    @Override
    public Optional<ProjectId> project(GetRevisionDiff command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetRevisionDiff command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_CHANGES);
    }

    @Override
    public RevisionDiff handle(GetRevisionDiff command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        Revision revision = revisions
                .get(command.projectId(), command.revision())
                .orElseThrow(() -> new NotFoundException("revision not found"));
        RevisionDiffRenderer.Prepared prepared = RevisionDiffRenderer.prepare(revision.changes());
        ProjectHandle handle = registry.get(command.projectId());
        return handle.gate().read(() -> render(handle, command.projectId(), revision, prepared, command.limit()));
    }

    private RevisionDiff render(
            ProjectHandle handle,
            ProjectId projectId,
            Revision revision,
            RevisionDiffRenderer.Prepared prepared,
            int limit) {
        if (handle.runtime().isCorrupted()) {
            throw new IllegalStateException("project runtime is corrupted: " + projectId);
        }
        return new RevisionDiff(
                revision.number().value(),
                RevisionDiffRenderer.render(handle.runtime(), hierarchy, prepared, limit),
                limit,
                prepared.size());
    }
}
