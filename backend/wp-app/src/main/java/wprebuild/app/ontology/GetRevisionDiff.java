package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Renders one revision, grouped by subject entity. Requires {@code VIEW_CHANGES}.
 *
 * <p>{@code limit} caps how many changes are returned. The response still reports the full count.
 */
public record GetRevisionDiff(ProjectId projectId, RevisionNumber revision, int limit)
        implements Command<RevisionDiff> {

    public static final int MAX_LIMIT = 200;

    public GetRevisionDiff {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
        if (revision.isZero()) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be from 1 to " + MAX_LIMIT);
        }
    }
}
