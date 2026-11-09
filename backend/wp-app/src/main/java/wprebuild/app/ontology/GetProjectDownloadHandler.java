package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.project.ProjectDetails;
import wprebuild.app.project.ProjectRepository;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.SnapshotStore;

/**
 * Writes the head from the loaded runtime under the read lock. An older revision is rebuilt from
 * the latest snapshot at or before that revision and is not put in the registry.
 */
@Service
public final class GetProjectDownloadHandler implements CommandHandler<GetProjectDownload, ProjectDownload> {

    private static final Logger log = LoggerFactory.getLogger(GetProjectDownloadHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final ProjectRepository projects;

    private final RevisionOntologies history;

    private final OntologyDownloadWriter writer = new OntologyDownloadWriter();

    public GetProjectDownloadHandler(
            ProjectRuntimeRegistry registry,
            ProjectRepository projects,
            RevisionLog revisionLog,
            SnapshotStore snapshotStore,
            SnapshotCodec snapshotCodec) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.history = new RevisionOntologies(revisionLog, snapshotStore, snapshotCodec);
    }

    @Override
    public Class<GetProjectDownload> commandType() {
        return GetProjectDownload.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectDownload command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectDownload command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.DOWNLOAD_PROJECT);
    }

    @Override
    public ProjectDownload handle(GetProjectDownload command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectDetails details =
                projects.find(command.projectId()).orElseThrow(() -> new NotFoundException("project not found"));
        ProjectHandle handle = registry.get(command.projectId());
        Optional<RevisionNumber> memoryHead = readableHead(handle);
        // A failed apply leaves a partial ontology in memory and does not advance that head.
        RevisionNumber head = memoryHead.orElseGet(() -> history.storedHead(command.projectId()));
        RevisionNumber target = command.revision().orElse(head);
        if (target.value() < 1 || target.isAfter(head)) {
            throw new NotFoundException("revision not found");
        }
        Optional<ProjectDownload> live = memoryHead.isPresent() && target.equals(memoryHead.get())
                ? live(handle, target, command, details)
                : Optional.empty();
        ProjectDownload download = live.orElseGet(() ->
                writer.write(history.at(command.projectId(), target), target, command.format(), details.displayName()));
        log.info(
                "downloaded project {} revision {} as {}",
                command.projectId(),
                target.value(),
                command.format().extension());
        return download;
    }

    private static Optional<RevisionNumber> readableHead(ProjectHandle handle) {
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted()) {
                return Optional.empty();
            }
            return Optional.of(handle.runtime().head());
        });
    }

    private Optional<ProjectDownload> live(
            ProjectHandle handle, RevisionNumber target, GetProjectDownload command, ProjectDetails details) {
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted() || !handle.runtime().head().equals(target)) {
                return Optional.empty();
            }
            return Optional.of(
                    writer.write(handle.runtime().ontologies(), target, command.format(), details.displayName()));
        });
    }
}
