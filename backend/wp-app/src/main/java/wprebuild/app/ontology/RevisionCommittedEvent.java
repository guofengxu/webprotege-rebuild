package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;

/**
 * Published synchronously after a revision is stored and applied.
 *
 * <p>{@code silent} is the generator flag. Search and events subscribe and decide what to emit.
 */
public record RevisionCommittedEvent(ProjectId projectId, Revision revision, boolean silent) {

    public RevisionCommittedEvent {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
    }
}
