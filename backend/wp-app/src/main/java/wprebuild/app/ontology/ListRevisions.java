package wprebuild.app.ontology;

import java.util.Objects;
import wprebuild.app.bus.Command;
import wprebuild.app.revision.RevisionHistoryQueries;
import wprebuild.app.revision.RevisionPage;
import wprebuild.kernel.id.ProjectId;

/** Lists revision metadata, newest first. Requires {@code VIEW_CHANGES}. {@code page} is zero-based. */
public record ListRevisions(ProjectId projectId, int page, int size) implements Command<RevisionPage> {

    public ListRevisions {
        Objects.requireNonNull(projectId, "project id");
        if (page < 0) {
            throw new IllegalArgumentException("page must be at least 0");
        }
        if (size < 1 || size > RevisionHistoryQueries.MAX_SIZE) {
            throw new IllegalArgumentException("size must be from 1 to " + RevisionHistoryQueries.MAX_SIZE);
        }
    }
}
