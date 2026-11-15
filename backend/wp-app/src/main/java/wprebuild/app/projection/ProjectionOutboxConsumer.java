package wprebuild.app.projection;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import wprebuild.app.bus.ProjectExistenceCheck;
import wprebuild.app.bus.ProjectExistenceCheck.ProjectState;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.revision.OutboxConsumer;
import wprebuild.app.revision.OutboxMessage;
import wprebuild.app.revision.OutboxRetryLater;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Applies {@code REVISION_COMMITTED} outbox rows to projection tables: incremental subject replace
 * when possible, otherwise a full rebuild (at most once per batch).
 */
@Component
@ConditionalOnProperty(prefix = "wp.projection", name = "enabled", havingValue = "true")
public class ProjectionOutboxConsumer implements OutboxConsumer {

    static final String CONSUMER_NAME = "projection";

    private static final String REVISION_COMMITTED = "REVISION_COMMITTED";

    private final ProjectionWriter writer;

    private final ProjectionRebuilder rebuilder;

    private final ProjectRuntimeRegistry registry;

    private final RevisionLog revisionLog;

    private final ProjectExistenceCheck projects;

    private final ProjectionProperties properties;

    public ProjectionOutboxConsumer(
            ProjectionWriter writer,
            ProjectionRebuilder rebuilder,
            ProjectRuntimeRegistry registry,
            RevisionLog revisionLog,
            ProjectExistenceCheck projects,
            ProjectionProperties properties) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.rebuilder = Objects.requireNonNull(rebuilder, "rebuilder");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    @Override
    public String name() {
        return CONSUMER_NAME;
    }

    @Override
    public StartPosition startFrom() {
        return StartPosition.EARLIEST;
    }

    @Override
    public long accept(List<OutboxMessage> batch) {
        Objects.requireNonNull(batch, "batch");
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("outbox batch must not be empty");
        }
        Set<ProjectId> handled = new HashSet<>();
        boolean rebuiltThisBatch = false;
        long lastAck = batch.getFirst().id() - 1;
        for (OutboxMessage message : batch) {
            if (!REVISION_COMMITTED.equals(message.type())) {
                lastAck = message.id();
                continue;
            }
            ProjectId projectId = message.projectId();
            if (handled.contains(projectId)) {
                lastAck = message.id();
                continue;
            }
            if (rebuiltThisBatch) {
                return lastAck;
            }
            List<OutboxMessage> projectMessages = batch.stream()
                    .filter(m -> projectId.equals(m.projectId()) && REVISION_COMMITTED.equals(m.type()))
                    .toList();
            ProjectOutcome outcome = processProject(projectId, projectMessages);
            handled.add(projectId);
            lastAck = message.id();
            if (outcome == ProjectOutcome.FULL_REBUILD) {
                rebuiltThisBatch = true;
            }
        }
        return lastAck;
    }

    private ProjectOutcome processProject(ProjectId projectId, List<OutboxMessage> projectMessages) {
        ProjectState state = projects.state(projectId);
        if (state == ProjectState.MISSING || state == ProjectState.TRASHED) {
            return ProjectOutcome.SKIPPED;
        }
        long maxRevNo =
                projectMessages.stream().mapToLong(OutboxMessage::revNo).max().orElseThrow();
        writer.lockProject(projectId);
        Optional<Long> stored = writer.projectRevNo(projectId);
        if (stored.isPresent() && maxRevNo <= stored.get()) {
            return ProjectOutcome.SKIPPED;
        }
        ProjectHandle handle = registry.get(projectId);
        handle.gate().read(() -> {
            ProjectRuntime runtime = handle.runtime();
            if (runtime.isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + projectId);
            }
            if (runtime.head().value() < maxRevNo) {
                throw new OutboxRetryLater(
                        "projection waiting for runtime head " + runtime.head().value() + " to reach " + maxRevNo);
            }
            return null;
        });
        if (stored.isEmpty()) {
            rebuilder.rebuild(projectId);
            return ProjectOutcome.FULL_REBUILD;
        }
        long fromRev = stored.get() + 1;
        List<OntologyChange> changes;
        try (var stream = revisionLog.read(projectId, RevisionNumber.of(fromRev), RevisionNumber.of(maxRevNo))) {
            changes = stream.map(Revision::changes).flatMap(List::stream).toList();
        }
        Set<IRI> subjects = AffectedSubjects.of(changes);
        if (subjects.size() > properties.fullRebuildThreshold()) {
            rebuilder.rebuild(projectId);
            return ProjectOutcome.FULL_REBUILD;
        }
        ProjectionSnapshot rows = handle.gate().read(() -> {
            ProjectRuntime runtime = handle.runtime();
            if (runtime.isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + projectId);
            }
            return ProjectionRows.computeFor(runtime, subjects);
        });
        if (!writer.replaceSubjects(projectId, subjects, rows, maxRevNo)) {
            throw new IllegalStateException("projection replaceSubjects skipped for "
                    + projectId
                    + " at rev "
                    + maxRevNo
                    + " (stored rev_no is newer under lock)");
        }
        return ProjectOutcome.INCREMENTAL;
    }

    private enum ProjectOutcome {
        SKIPPED,
        INCREMENTAL,
        FULL_REBUILD
    }
}
