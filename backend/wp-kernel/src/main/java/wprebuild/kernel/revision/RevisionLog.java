package wprebuild.kernel.revision;

import java.util.Optional;
import java.util.stream.Stream;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Append-only log of project revisions. Implementations must reject concurrent appends that share
 * the same expected head (WP-5, ADR-004).
 */
public interface RevisionLog {

    /** Head is {@link RevisionNumber#ZERO} when the project has no revisions. */
    RevisionNumber head(ProjectId projectId);

    /**
     * Appends {@code revision} after {@code expectedHead}.
     *
     * @throws IllegalArgumentException if {@code revision.number()} is not {@code expectedHead.next()}
     * @throws ConcurrentAppendException if the stored head is not {@code expectedHead}
     */
    void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision);

    Optional<Revision> get(ProjectId projectId, RevisionNumber number);

    /**
     * Revisions whose numbers fall in {@code [fromInclusive, toInclusive]}, in ascending order.
     * Missing numbers are skipped. The stream should be closed by the caller.
     */
    Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive);
}
