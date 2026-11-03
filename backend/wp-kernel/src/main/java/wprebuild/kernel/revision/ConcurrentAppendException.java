package wprebuild.kernel.revision;

import java.util.Objects;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Thrown when {@link RevisionLog#append} sees a different head than the caller expected (WP-5,
 * ADR-004).
 */
public final class ConcurrentAppendException extends RuntimeException {

    private final ProjectId projectId;
    private final RevisionNumber expectedHead;
    private final RevisionNumber actualHead;

    public ConcurrentAppendException(ProjectId projectId, RevisionNumber expectedHead, RevisionNumber actualHead) {
        super("concurrent append for project %s: expected head %s but was %s"
                .formatted(
                        Objects.requireNonNull(projectId, "project id"),
                        Objects.requireNonNull(expectedHead, "expected head").value(),
                        Objects.requireNonNull(actualHead, "actual head").value()));
        this.projectId = projectId;
        this.expectedHead = expectedHead;
        this.actualHead = actualHead;
    }

    public ProjectId projectId() {
        return projectId;
    }

    public RevisionNumber expectedHead() {
        return expectedHead;
    }

    public RevisionNumber actualHead() {
        return actualHead;
    }
}
